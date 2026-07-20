<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'

interface TeachingPlan {
  id: string
  documentVersionId: string
  playerCount: number
  beginnerCount: number
  durationMinutes: number
  gameTitle: string
  premise: string
  createdAt: string
  sections: Array<{ position: number; required: boolean; topicKey: string; title: string; visualEvidenceRecommended: boolean }>
}

interface AssistantRun {
  run: {
    id: string
    state: string
    createdAt: string
    updatedAt: string
    completedAt: string | null
    lastErrorCode: string | null
  }
  budget: { usedModelCalls: number; maxModelCalls: number }
  activities: Array<{
    sequence: number
    type: 'TOOL' | 'MODEL' | 'CRITIC' | 'VALIDATION'
    operation: string
    summary: string
    outcome: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'REJECTED'
    latencyMs: number
    occurredAt: string
  }>
}

interface LessonSummary {
  id: string
  status: 'COMPLETE' | 'INCOMPLETE'
  sections: Array<{ evidenceStatus: 'SUPPORTED' | 'INSUFFICIENT_EVIDENCE' }>
}

interface PlanProgress {
  run: AssistantRun | null
  lesson: LessonSummary | null
}

interface CsrfResponse { headerName: string; token: string }

const route = useRoute()
const router = useRouter()
const plans = ref<TeachingPlan[]>([])
const progress = ref<Record<string, PlanProgress>>({})
const progressErrors = ref<Record<string, string>>({})
const loading = ref(true)
const errorMessage = ref('')
const launchingPlanId = ref('')
const now = ref(Date.now())
const rememberedPlanId = localStorage.getItem('rulepilot:last-plan-id')
const terminalStates = new Set(['COMPLETED', 'INSUFFICIENT_EVIDENCE', 'DEGRADED', 'FAILED'])
let pollTimer: ReturnType<typeof setTimeout> | undefined
let clockTimer: ReturnType<typeof setInterval> | undefined
let disposed = false

const readableCount = computed(() => Object.values(progress.value).filter((item) => item.lesson).length)
const startedPlanId = computed(() => typeof route.query.started === 'string' ? route.query.started : '')

function stateOf(planId: string) {
  const item = progress.value[planId]
  if (item?.run && !terminalStates.has(item.run.run.state)) return 'GENERATING'
  if (item?.lesson?.status === 'COMPLETE') return 'COMPLETE'
  if (item?.lesson?.status === 'INCOMPLETE') return 'INCOMPLETE'
  if (item?.run?.run.state === 'FAILED') return 'FAILED'
  return 'PLANNED'
}

function stateLabel(planId: string) {
  return ({
    GENERATING: '正在生成',
    COMPLETE: '可以阅读',
    INCOMPLETE: '部分完成',
    FAILED: '生成失败',
    PLANNED: '等待开始',
  } as const)[stateOf(planId)]
}

function stateClass(planId: string) {
  const state = stateOf(planId)
  if (state === 'COMPLETE') return 'bg-emerald-50 text-emerald-800'
  if (state === 'GENERATING') return 'bg-indigo/10 text-indigo'
  if (state === 'FAILED') return 'bg-red-50 text-red-800'
  return 'bg-amber-50 text-amber-800'
}

function elapsedLabel(plan: TeachingPlan) {
  const startedAt = progress.value[plan.id]?.run?.run.createdAt
  const seconds = startedAt ? Math.max(0, Math.floor((now.value - new Date(startedAt).getTime()) / 1000)) : 0
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

function operationPosition(operation: string) {
  const value = Number(operation.split('|')[1])
  return Number.isInteger(value) && value > 0 ? value : null
}

function chapterFor(plan: TeachingPlan, operation: string) {
  const position = operationPosition(operation)
  if (!position) return null
  return plan.sections.find((section) => section.position === position) ?? plan.sections[position - 1] ?? null
}

function chapterForActivity(plan: TeachingPlan, activity: AssistantRun['activities'][number]) {
  const direct = chapterFor(plan, activity.operation)
  if (direct) return direct
  const activities = progress.value[plan.id]?.run?.activities ?? []
  for (let index = activities.findIndex((candidate) => candidate.sequence === activity.sequence) - 1; index >= 0; index--) {
    const recent = chapterFor(plan, activities[index]!.operation)
    if (recent) return recent
  }
  return null
}

function processedChapterCount(plan: TeachingPlan) {
  const activities = progress.value[plan.id]?.run?.activities ?? []
  return new Set(activities
    .filter((activity) => activity.operation.startsWith('publishTeachingSection|'))
    .map((activity) => operationPosition(activity.operation))
    .filter((position): position is number => position !== null)).size
}

function supportedChapterCount(plan: TeachingPlan) {
  const activities = progress.value[plan.id]?.run?.activities ?? []
  return new Set(activities
    .filter((activity) => activity.operation.startsWith('publishTeachingSection|') && activity.outcome === 'SUCCEEDED')
    .map((activity) => operationPosition(activity.operation))
    .filter((position): position is number => position !== null)).size
}

function chapterProgressWidth(plan: TeachingPlan) {
  return `${Math.round(processedChapterCount(plan) / Math.max(1, plan.sections.length) * 100)}%`
}

function activityText(plan: TeachingPlan, activity: AssistantRun['activities'][number] | undefined) {
  if (!activity) return '正在准备规则依据和章节顺序'
  const chapter = chapterForActivity(plan, activity)
  const target = chapter ? `“${chapter.title}”` : '当前内容'
  if (activity.operation.startsWith('searchRuleEvidence')) return `正在为${target}查找规则依据`
  if (activity.operation.startsWith('composeTeachingSection')) {
    return chapter?.visualEvidenceRecommended
      ? `正在阅读规则书图片并编写${target}`
      : `正在编写${target}`
  }
  if (activity.operation.startsWith('reviseTeachingSection')) return `正在根据核对结果修正${target}`
  if (activity.operation.startsWith('confirmGeneratedClaims')) return `正在逐条复核${target}的规则陈述`
  if (activity.operation.startsWith('reviewGeneratedContent')) return `正在核对${target}的规则和出处`
  if (activity.operation.startsWith('reviewObjectiveCoverage')) return `正在检查${target}有没有漏讲关键步骤`
  if (activity.operation.startsWith('validateTeachingSection')) {
    return activity.outcome === 'SUCCEEDED' ? `${target}已通过结构检查` : `${target}需要继续修正`
  }
  if (activity.operation.startsWith('publishTeachingSection')) {
    return activity.outcome === 'SUCCEEDED' ? `${target}已经完成` : `${target}暂未通过，继续处理下一节`
  }
  return '正在整理并核对讲解'
}

function currentActivity(plan: TeachingPlan) {
  return progress.value[plan.id]?.run?.activities.at(-1)
}

function remainingTimeText(plan: TeachingPlan) {
  const completed = processedChapterCount(plan)
  const total = plan.sections.length
  if (completed === 0) return '第一节完成后，会按这本规则书的真实速度估算剩余时间。'
  if (completed >= total) return '所有章节已经处理，正在保存最终结果。'
  const run = progress.value[plan.id]!.run!
  const elapsedMinutes = Math.max(0.1, (now.value - new Date(run.run.createdAt).getTime()) / 60_000)
  const estimatedMinutes = elapsedMinutes / completed * (total - completed)
  const low = Math.max(1, Math.floor(estimatedMinutes * 0.7))
  const high = Math.max(low + 1, Math.ceil(estimatedMinutes * 1.5))
  return `按目前速度，剩余章节大约还需 ${low}–${high} 分钟；图片章节可能更久。`
}

function recentActivities(plan: TeachingPlan) {
  return (progress.value[plan.id]?.run?.activities ?? []).slice(-3).reverse()
}

function progressText(plan: TeachingPlan) {
  const item = progress.value[plan.id]
  const state = stateOf(plan.id)
  if (state === 'GENERATING') {
    return `${activityText(plan, item!.run!.activities.at(-1))} · 已用时 ${elapsedLabel(plan)}。可以关闭或离开此页。`
  }
  if (state === 'INCOMPLETE') {
    const supported = item?.lesson?.sections.filter((section) => section.evidenceStatus === 'SUPPORTED').length ?? 0
    return `已有 ${supported} 节通过规则核对，可以先阅读，也可以继续补全。`
  }
  if (state === 'FAILED') return `任务已停止（${item?.run?.run.lastErrorCode ?? '未知原因'}），可以重新生成。`
  if (state === 'COMPLETE') return '讲解已经通过规则依据核对，可以继续上次的阅读位置。'
  return '讲解目录已经准备好，开始后会在后台逐节生成并核对。'
}

function createdLabel(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '创建时间未知'
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

async function checkedFetch(path: string, options?: Parameters<typeof fetch>[1]) {
  const response = await fetch(path, { credentials: 'include', ...options })
  if (response.status === 401) {
    await router.push({ name: 'login' })
    throw new Error('请先登录。')
  }
  return response
}

async function loadProgress(plan: TeachingPlan) {
  try {
    const previousRun = progress.value[plan.id]?.run
    const previousSequence = previousRun?.activities.at(-1)?.sequence ?? 0
    const activityCursor = previousRun
      ? `&activityRunId=${encodeURIComponent(previousRun.run.id)}&afterActivitySequence=${previousSequence}`
      : ''
    const [runResponse, lessonResponse] = await Promise.all([
      checkedFetch(`/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(plan.id)}${activityCursor}`),
      checkedFetch(`/api/v1/teaching-plans/${plan.id}/illustrated-lessons/latest`),
    ])
    if (!runResponse.ok && runResponse.status !== 404) throw new Error('讲解任务进度暂时不可用。')
    if (!lessonResponse.ok && lessonResponse.status !== 404) throw new Error('讲解内容进度暂时不可用。')
    const incomingRun = runResponse.ok ? await runResponse.json() as AssistantRun : null
    const run = incomingRun && previousRun?.run.id === incomingRun.run.id
      ? {
          ...incomingRun,
          activities: Array.from(new Map(
            [...previousRun.activities, ...incomingRun.activities]
              .map((activity) => [activity.sequence, activity]),
          ).values()).sort((left, right) => left.sequence - right.sequence),
        }
      : incomingRun
    progress.value = {
      ...progress.value,
      [plan.id]: {
        run,
        lesson: lessonResponse.ok ? await lessonResponse.json() as LessonSummary : null,
      },
    }
    if (progressErrors.value[plan.id]) {
      const next = { ...progressErrors.value }
      delete next[plan.id]
      progressErrors.value = next
    }
  } catch (error) {
    progressErrors.value = {
      ...progressErrors.value,
      [plan.id]: error instanceof Error ? error.message : '暂时无法取得最新进度。',
    }
    throw error
  }
}

function plansNeedingRefresh() {
  return plans.value.filter((plan) => stateOf(plan.id) === 'GENERATING' || Boolean(progressErrors.value[plan.id]))
}

function clearProgressTimer() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = undefined
}

function scheduleProgressRefresh(delay = 1500) {
  clearProgressTimer()
  if (disposed || plansNeedingRefresh().length === 0) return
  pollTimer = setTimeout(() => {
    pollTimer = undefined
    void refreshProgress(plansNeedingRefresh())
  }, delay)
}

async function refreshProgress(targetPlans = plans.value) {
  await Promise.allSettled(targetPlans.map(loadProgress))
  scheduleProgressRefresh()
}

async function loadPlans() {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await checkedFetch('/api/v1/teaching-plans')
    if (!response.ok) throw new Error('无法读取你的讲解，请稍后重试。')
    plans.value = (await response.json()) as TeachingPlan[]
    await refreshProgress()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '无法读取你的讲解。'
  } finally {
    loading.value = false
  }
}

async function launch(planId: string) {
  if (launchingPlanId.value) return
  launchingPlanId.value = planId
  errorMessage.value = ''
  try {
    const csrfResponse = await checkedFetch('/api/auth/csrf')
    if (!csrfResponse.ok) throw new Error('无法建立安全会话。')
    const csrf = await csrfResponse.json() as CsrfResponse
    const response = await checkedFetch(`/api/v1/teaching-plans/${planId}/illustrated-lessons`, {
      method: 'POST',
      headers: { [csrf.headerName]: csrf.token },
    })
    if (!response.ok) throw new Error('讲解任务没有启动，请稍后重试。')
    localStorage.setItem('rulepilot:last-plan-id', planId)
    await loadProgress(plans.value.find((plan) => plan.id === planId)!).catch(() => undefined)
    scheduleProgressRefresh(1000)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '讲解任务没有启动。'
  } finally {
    launchingPlanId.value = ''
  }
}

onMounted(() => {
  disposed = false
  clockTimer = setInterval(() => { now.value = Date.now() }, 1000)
  void loadPlans()
})
onBeforeUnmount(() => {
  disposed = true
  clearProgressTimer()
  if (clockTimer) clearInterval(clockTimer)
})
</script>

<template>
  <AppShell>
    <section class="mx-auto max-w-6xl px-5 py-10 sm:px-8 lg:px-12 lg:py-14">
      <p class="text-sm font-medium text-copper">我的讲解</p>
      <div class="mt-4 flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
        <div>
          <h1 class="font-display text-4xl font-semibold tracking-tight">准备中的，也不会丢</h1>
          <p class="mt-4 max-w-2xl leading-7 text-ink/55">生成会在后台继续。回来后可以看进度、处理失败，或从上次阅读的位置继续。</p>
        </div>
        <RouterLink :to="{ name: 'teach' }" class="inline-flex min-h-11 items-center justify-center rounded-lg bg-copper px-4 text-sm font-semibold text-white">准备新讲解</RouterLink>
      </div>

      <p v-if="startedPlanId" class="mt-6 rounded-lg bg-indigo/5 px-4 py-3 text-sm text-indigo" role="status">任务已经交给后台。你可以留在这里看进度，也可以先去做别的。</p>
      <p v-if="plans.length" class="mt-6 text-sm text-ink/45">共 {{ plans.length }} 份，{{ readableCount }} 份已有内容可以阅读。</p>

      <div v-if="loading" class="mt-8 rounded-xl border border-ink/10 bg-paper p-8 text-ink/50" role="status">正在读取讲解…</div>

      <div v-else-if="errorMessage" class="mt-10 rounded-3xl border border-red-200 bg-red-50 p-6 text-red-800" role="alert">
        <p>{{ errorMessage }}</p>
        <button class="mt-4 text-sm font-semibold underline underline-offset-4" @click="loadPlans">重新加载</button>
      </div>

      <div v-else-if="plans.length === 0" class="mt-8 rounded-xl border border-dashed border-ink/20 px-6 py-14 text-center">
        <h2 class="font-display text-2xl font-semibold">还没有准备过讲解</h2>
        <p class="mx-auto mt-3 max-w-lg leading-7 text-ink/55">先添加一本规则书，RulePilot 会从设置讲到计分。</p>
        <RouterLink :to="{ name: 'teach' }" class="mt-7 inline-flex rounded-lg bg-copper px-5 py-3 font-semibold text-white">添加规则书</RouterLink>
      </div>

      <ol v-else class="mt-10 grid gap-5 md:grid-cols-2">
        <li v-for="plan in plans" :key="plan.id" class="rounded-xl border border-ink/10 bg-paper p-6" :class="plan.id === startedPlanId ? 'ring-2 ring-copper/30' : ''">
          <div class="flex items-start justify-between gap-4">
            <div>
              <p class="text-xs font-medium text-ink/40">{{ createdLabel(plan.createdAt) }}</p>
              <h2 class="mt-2 font-display text-2xl font-semibold">{{ plan.gameTitle }}</h2>
            </div>
            <span :class="stateClass(plan.id)" class="rounded-full px-3 py-1.5 text-xs font-semibold">{{ stateLabel(plan.id) }}</span>
          </div>
          <div v-if="stateOf(plan.id) === 'GENERATING'" class="mt-5 rounded-xl border border-indigo/15 bg-indigo/5 p-4" aria-live="polite" aria-atomic="true">
            <div class="flex items-start justify-between gap-4">
              <div>
                <p class="text-sm font-semibold text-indigo">{{ activityText(plan, currentActivity(plan)) }}</p>
                <p class="mt-1 text-xs leading-5 text-ink/50">模型返回前，这一行也会保留当前正在做的事。</p>
              </div>
              <span class="shrink-0 font-mono text-sm font-semibold text-indigo">{{ elapsedLabel(plan) }}</span>
            </div>
            <div class="mt-4 h-2 overflow-hidden rounded-full bg-indigo/10" role="progressbar" :aria-valuemin="0" :aria-valuemax="plan.sections.length" :aria-valuenow="processedChapterCount(plan)" :aria-label="`已处理 ${processedChapterCount(plan)} 个章节，共 ${plan.sections.length} 个`">
              <div class="h-full rounded-full bg-indigo transition-[width] duration-500" :style="{ width: chapterProgressWidth(plan) }" />
            </div>
            <div class="mt-2 flex flex-wrap justify-between gap-2 text-xs text-ink/55">
              <span>已处理 {{ processedChapterCount(plan) }}/{{ plan.sections.length }} 节，其中 {{ supportedChapterCount(plan) }} 节已通过核对</span>
              <span>{{ progress[plan.id]?.run?.budget.usedModelCalls ?? 0 }} 次模型调用</span>
            </div>
            <p class="mt-3 text-xs leading-5 text-ink/50">{{ remainingTimeText(plan) }} 生成会在后台继续，可以关闭或离开此页。</p>
            <ol v-if="recentActivities(plan).length" class="mt-4 space-y-2 border-t border-indigo/10 pt-3" aria-label="最近进度">
              <li v-for="activity in recentActivities(plan)" :key="activity.sequence" class="flex items-start gap-2 text-xs leading-5 text-ink/55">
                <span class="mt-1.5 size-1.5 shrink-0 rounded-full" :class="activity.outcome === 'RUNNING' ? 'animate-pulse bg-copper' : activity.outcome === 'SUCCEEDED' ? 'bg-emerald-600' : 'bg-amber-600'" />
                <span>{{ activityText(plan, activity) }}</span>
              </li>
            </ol>
          </div>
          <p v-else class="mt-4 min-h-12 text-sm leading-6 text-ink/60" aria-live="polite">{{ progressText(plan) }}</p>
          <p v-if="progressErrors[plan.id]" class="mt-3 rounded-lg bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-800" role="status">暂时没拿到最新进度，正在自动重试。生成任务不会受影响。</p>
          <dl class="mt-5 grid grid-cols-2 gap-3 rounded-2xl bg-canvas p-4 text-sm">
            <div><dt class="text-ink/45">新手人数</dt><dd class="mt-1 font-semibold">{{ plan.beginnerCount }} 人</dd></div>
            <div><dt class="text-ink/45">计划时长</dt><dd class="mt-1 font-semibold">{{ plan.durationMinutes }} 分钟</dd></div>
          </dl>
          <div class="mt-6 flex items-center justify-between gap-3">
            <span v-if="plan.id === rememberedPlanId" class="text-xs font-semibold text-indigo">上次打开</span>
            <span v-else class="text-xs text-ink/35">{{ plan.sections.length }} 个章节</span>
            <RouterLink v-if="progress[plan.id]?.lesson" :to="{ name: 'lesson', params: { planId: plan.id } }" class="rounded-lg bg-indigo px-4 py-2.5 text-sm font-semibold text-white">{{ stateOf(plan.id) === 'INCOMPLETE' ? '阅读并补全' : '打开讲解' }}</RouterLink>
            <button v-else-if="stateOf(plan.id) !== 'GENERATING'" :disabled="launchingPlanId === plan.id" class="rounded-lg bg-indigo px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-40" @click="launch(plan.id)">{{ launchingPlanId === plan.id ? '正在启动…' : stateOf(plan.id) === 'FAILED' ? '重新生成' : '开始生成' }}</button>
            <span v-else class="inline-flex items-center gap-2 text-sm font-semibold text-indigo"><span class="size-3 animate-spin rounded-full border-2 border-indigo/20 border-t-indigo" />后台处理中</span>
          </div>
        </li>
      </ol>
    </section>
  </AppShell>
</template>
