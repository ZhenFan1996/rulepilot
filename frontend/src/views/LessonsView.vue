<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import { groupPlansForReading, playerFacingTitle } from '@/lib/lessonPresentation'
import {
  mergeTeachingRunProgress,
  processedTeachingChapterCount,
  supportedTeachingChapterCount,
  teachingActivityCursor,
  teachingActivityText,
  teachingElapsedLabel,
  teachingRemainingTimeText,
  type TeachingActivity,
  type TeachingRunProgress,
} from '@/lib/teachingProgress'

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

interface LessonSummary {
  id: string
  status: 'COMPLETE' | 'DRAFT_READY' | 'INCOMPLETE'
  sections: Array<{ evidenceStatus: 'SUPPORTED' | 'CITED_DRAFT' | 'INSUFFICIENT_EVIDENCE' }>
}

interface PlanProgress {
  run: TeachingRunProgress | null
  lesson: LessonSummary | null
}

interface CsrfResponse { headerName: string; token: string }
type PlanFilter = 'READABLE' | 'PENDING' | 'ALL'

const route = useRoute()
const router = useRouter()
const plans = ref<TeachingPlan[]>([])
const progress = ref<Record<string, PlanProgress>>({})
const progressErrors = ref<Record<string, string>>({})
const loading = ref(true)
const errorMessage = ref('')
const launchingPlanId = ref('')
const deletingPlanId = ref('')
const cleanupLoading = ref(false)
const cleanupMessage = ref('')
const showingAllVersions = ref(false)
const planFilter = ref<PlanFilter>('READABLE')
const now = ref(Date.now())
const rememberedPlanId = localStorage.getItem('rulepilot:last-plan-id')
const terminalStates = new Set(['COMPLETED', 'INSUFFICIENT_EVIDENCE', 'DEGRADED', 'FAILED'])
let pollTimer: ReturnType<typeof setTimeout> | undefined
let clockTimer: ReturnType<typeof setInterval> | undefined
let disposed = false

const startedPlanId = computed(() => typeof route.query.started === 'string' ? route.query.started : '')

function stateOf(planId: string) {
  const item = progress.value[planId]
  if (item?.run && !terminalStates.has(item.run.run.state)) return 'GENERATING'
  if (item?.lesson?.status === 'COMPLETE') return 'COMPLETE'
  if (item?.lesson?.status === 'DRAFT_READY') return 'DRAFT_READY'
  if (item?.lesson?.status === 'INCOMPLETE') return 'INCOMPLETE'
  if (item?.run?.run.state === 'FAILED') return 'FAILED'
  return 'PLANNED'
}

function stateLabel(planId: string) {
  if (stateOf(planId) === 'GENERATING' && progress.value[planId]?.lesson?.status === 'DRAFT_READY') {
    return '可读，核对中'
  }
  return ({
    GENERATING: '正在生成',
    COMPLETE: '可以阅读',
    DRAFT_READY: '基础讲解可读',
    INCOMPLETE: '部分完成',
    FAILED: '生成失败',
    PLANNED: '等待开始',
  } as const)[stateOf(planId)]
}

function stateClass(planId: string) {
  const state = stateOf(planId)
  if (state === 'COMPLETE' || state === 'DRAFT_READY'
    || state === 'GENERATING' && progress.value[planId]?.lesson?.status === 'DRAFT_READY') {
    return 'bg-emerald-50 text-emerald-800'
  }
  if (state === 'GENERATING') return 'bg-indigo/10 text-indigo'
  if (state === 'FAILED') return 'bg-red-50 text-red-800'
  return 'bg-amber-50 text-amber-800'
}

function displayPlanTitle(plan: TeachingPlan) {
  return playerFacingTitle(plan.gameTitle)
}

function continuationPriority(plan: TeachingPlan) {
  const item = progress.value[plan.id]
  if (item?.lesson?.status === 'COMPLETE') return 600
  if (item?.lesson?.status === 'DRAFT_READY' && item.run && !terminalStates.has(item.run.run.state)) return 550
  if (item?.lesson?.status === 'DRAFT_READY') return 500
  if (item?.lesson?.status === 'INCOMPLETE') return 400
  if (item?.run && !terminalStates.has(item.run.run.state)) return 300
  if (item?.run?.run.state === 'FAILED') return 100
  return 200
}

const planGroups = computed(() => groupPlansForReading(plans.value, continuationPriority))
const planGroupByPlanId = computed(() => {
  const groups = new Map<string, typeof planGroups.value[number]>()
  for (const group of planGroups.value) {
    for (const plan of group.plans) groups.set(plan.id, group)
  }
  return groups
})
const selectedPlans = computed(() => showingAllVersions.value ? plans.value : planGroups.value.map((group) => group.plan))
const selectedPlanFilter = computed<PlanFilter>(() => planFilter.value === 'READABLE' && readableGroupCount.value === 0
  ? 'PENDING'
  : planFilter.value)
const displayedPlans = computed(() => selectedPlans.value.filter((plan) => {
  if (selectedPlanFilter.value === 'ALL') return true
  return selectedPlanFilter.value === 'READABLE'
    ? Boolean(progress.value[plan.id]?.lesson)
    : !progress.value[plan.id]?.lesson
}))
const readableGroupCount = computed(() => planGroups.value.filter((group) => Boolean(progress.value[group.plan.id]?.lesson)).length)
const pendingGroupCount = computed(() => planGroups.value.length - readableGroupCount.value)

function versionCount(planId: string) {
  return planGroupByPlanId.value.get(planId)?.count ?? 1
}

function showAllVersions() {
  showingAllVersions.value = true
  planFilter.value = 'ALL'
}

function hideAllVersions() {
  showingAllVersions.value = false
  planFilter.value = 'READABLE'
}

function elapsedLabel(plan: TeachingPlan) {
  return teachingElapsedLabel(progress.value[plan.id]?.run ?? null, now.value)
}

function processedChapterCount(plan: TeachingPlan) {
  return processedTeachingChapterCount(progress.value[plan.id]?.run ?? null)
}

function supportedChapterCount(plan: TeachingPlan) {
  return supportedTeachingChapterCount(progress.value[plan.id]?.run ?? null)
}

function chapterProgressWidth(plan: TeachingPlan) {
  return `${Math.round(processedChapterCount(plan) / Math.max(1, plan.sections.length) * 100)}%`
}

function activityText(plan: TeachingPlan, activity: TeachingActivity | undefined) {
  const activities = progress.value[plan.id]?.run?.activities ?? []
  return teachingActivityText(plan, activities, activity)
}

function currentActivity(plan: TeachingPlan) {
  return progress.value[plan.id]?.run?.activities.at(-1)
}

function remainingTimeText(plan: TeachingPlan) {
  return teachingRemainingTimeText(plan, progress.value[plan.id]?.run ?? null, now.value)
}

function recentActivities(plan: TeachingPlan) {
  return (progress.value[plan.id]?.run?.activities ?? []).slice(-3).reverse()
}

function progressText(plan: TeachingPlan) {
  const item = progress.value[plan.id]
  const state = stateOf(plan.id)
  if (state === 'GENERATING') {
    if (item?.lesson?.status === 'DRAFT_READY') {
      return `完整基础讲解已经可以阅读；后台正在核对细节，已用时 ${elapsedLabel(plan)}。`
    }
    return `${activityText(plan, item!.run!.activities.at(-1))} · 已用时 ${elapsedLabel(plan)}。可以关闭或离开此页。`
  }
  if (state === 'DRAFT_READY') return '完整基础讲解已经可以使用；部分细节尚未完成二次核对。'
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
    const activityCursor = teachingActivityCursor(previousRun ?? null)
    const [runResponse, lessonResponse] = await Promise.all([
      checkedFetch(`/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(plan.id)}${activityCursor}`),
      checkedFetch(`/api/v1/teaching-plans/${plan.id}/illustrated-lessons/latest`),
    ])
    if (!runResponse.ok && runResponse.status !== 404) throw new Error('讲解任务进度暂时不可用。')
    if (!lessonResponse.ok && lessonResponse.status !== 404) throw new Error('讲解内容进度暂时不可用。')
    const incomingRun = runResponse.ok ? await runResponse.json() as TeachingRunProgress : null
    const run = mergeTeachingRunProgress(previousRun ?? null, incomingRun)
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

async function deletePlan(plan: TeachingPlan) {
  if (deletingPlanId.value || cleanupLoading.value) return
  if (!window.confirm(`删除“${displayPlanTitle(plan)}”的这份讲解吗？规则书会保留，你之后可以重新生成。`)) return
  deletingPlanId.value = plan.id
  errorMessage.value = ''
  try {
    const csrfResponse = await checkedFetch('/api/auth/csrf')
    if (!csrfResponse.ok) throw new Error('无法建立安全会话。')
    const csrf = await csrfResponse.json() as CsrfResponse
    const response = await checkedFetch(`/api/v1/teaching-plans/${encodeURIComponent(plan.id)}`, {
      method: 'DELETE', headers: { [csrf.headerName]: csrf.token },
    })
    if (!response.ok) throw new Error('讲解暂时无法删除，请稍后重试。')
    if (localStorage.getItem('rulepilot:last-plan-id') === plan.id) localStorage.removeItem('rulepilot:last-plan-id')
    plans.value = plans.value.filter((item) => item.id !== plan.id)
    const nextProgress = { ...progress.value }
    delete nextProgress[plan.id]
    progress.value = nextProgress
    cleanupMessage.value = '讲解已删除。'
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '讲解暂时无法删除。'
  } finally {
    deletingPlanId.value = ''
  }
}

async function cleanDuplicates() {
  if (cleanupLoading.value || deletingPlanId.value) return
  cleanupLoading.value = true
  cleanupMessage.value = ''
  errorMessage.value = ''
  try {
    const previewResponse = await checkedFetch('/api/v1/teaching-plans/cleanup-preview')
    if (!previewResponse.ok) throw new Error('暂时无法检查重复讲解。')
    const preview = await previewResponse.json() as { duplicateCount: number }
    if (preview.duplicateCount === 0) {
      cleanupMessage.value = '没有发现相同规则书和相同讲解偏好的重复项。'
      return
    }
    if (!window.confirm(`发现 ${preview.duplicateCount} 份重复讲解。将保留内容最完整且最新的一份，删除其余重复项。继续吗？`)) return
    const csrfResponse = await checkedFetch('/api/auth/csrf')
    if (!csrfResponse.ok) throw new Error('无法建立安全会话。')
    const csrf = await csrfResponse.json() as CsrfResponse
    const response = await checkedFetch('/api/v1/teaching-plans/cleanup-duplicates', {
      method: 'POST', headers: { [csrf.headerName]: csrf.token },
    })
    if (!response.ok) throw new Error('重复讲解暂时无法清理。')
    const result = await response.json() as { deletedCount: number }
    await loadPlans()
    cleanupMessage.value = result.deletedCount ? `已清理 ${result.deletedCount} 份重复讲解。` : '没有需要清理的讲解。'
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '重复讲解暂时无法清理。'
  } finally {
    cleanupLoading.value = false
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
        <div class="flex flex-wrap gap-2">
          <button v-if="plans.length > 1" type="button" :disabled="cleanupLoading || Boolean(deletingPlanId)" class="inline-flex min-h-11 items-center justify-center rounded-lg border border-ink/15 px-4 text-sm font-semibold hover:border-copper/50 disabled:opacity-40" @click="cleanDuplicates">{{ cleanupLoading ? '正在整理…' : '整理重复讲解' }}</button>
          <RouterLink :to="{ name: 'teach' }" class="inline-flex min-h-11 items-center justify-center rounded-lg bg-copper px-4 text-sm font-semibold text-white">上传规则书</RouterLink>
        </div>
      </div>

      <p v-if="startedPlanId" class="mt-6 rounded-lg bg-indigo/5 px-4 py-3 text-sm text-indigo" role="status">任务已经交给后台。你可以留在这里看进度，也可以先去做别的。</p>
      <p v-if="cleanupMessage" class="mt-6 rounded-lg bg-emerald-50 px-4 py-3 text-sm text-emerald-800" role="status">{{ cleanupMessage }}</p>
      <div v-if="plans.length" class="mt-6 flex flex-wrap items-center gap-x-4 gap-y-2 text-sm text-ink/45">
        <p>共 {{ plans.length }} 个版本，按 {{ planGroups.length }} 本规则书整理；{{ readableGroupCount }} 本可以继续阅读。</p>
        <button v-if="plans.length > planGroups.length" type="button" class="font-semibold text-indigo underline decoration-indigo/30 underline-offset-4 hover:decoration-indigo" @click="showingAllVersions ? hideAllVersions() : showAllVersions()">{{ showingAllVersions ? '收起历史版本' : `查看全部 ${plans.length} 个版本` }}</button>
      </div>

      <div v-if="plans.length" class="mt-5 flex flex-wrap gap-2" role="group" aria-label="讲解筛选">
        <button type="button" class="min-h-10 rounded-full px-4 text-sm font-semibold transition" :class="selectedPlanFilter === 'READABLE' ? 'bg-ink text-paper' : 'border border-ink/15 text-ink/65 hover:border-ink/35'" :aria-pressed="selectedPlanFilter === 'READABLE'" @click="planFilter = 'READABLE'">可阅读 {{ readableGroupCount }}</button>
        <button type="button" class="min-h-10 rounded-full px-4 text-sm font-semibold transition" :class="selectedPlanFilter === 'PENDING' ? 'bg-ink text-paper' : 'border border-ink/15 text-ink/65 hover:border-ink/35'" :aria-pressed="selectedPlanFilter === 'PENDING'" @click="planFilter = 'PENDING'">待处理 {{ pendingGroupCount }}</button>
        <button type="button" class="min-h-10 rounded-full px-4 text-sm font-semibold transition" :class="selectedPlanFilter === 'ALL' ? 'bg-ink text-paper' : 'border border-ink/15 text-ink/65 hover:border-ink/35'" :aria-pressed="selectedPlanFilter === 'ALL'" @click="planFilter = 'ALL'">全部 {{ planGroups.length }}</button>
      </div>

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

      <div v-else-if="displayedPlans.length === 0" class="mt-8 rounded-xl border border-dashed border-ink/20 px-6 py-12 text-center">
        <h2 class="font-display text-2xl font-semibold">这里还没有可阅读的讲解</h2>
        <p class="mx-auto mt-3 max-w-lg leading-7 text-ink/55">先从待处理的规则书开始生成，或添加一本新的规则书。</p>
        <button type="button" class="mt-6 text-sm font-semibold text-indigo underline underline-offset-4" @click="planFilter = 'PENDING'">查看待处理</button>
      </div>

      <ol v-else class="mt-10 grid gap-5 md:grid-cols-2">
        <li v-for="plan in displayedPlans" :key="plan.id" class="rounded-xl border border-ink/10 bg-paper p-6" :class="plan.id === startedPlanId ? 'ring-2 ring-copper/30' : ''">
          <div class="flex items-start justify-between gap-4">
            <div>
              <p class="text-xs font-medium text-ink/40">{{ createdLabel(plan.createdAt) }}</p>
              <h2 class="mt-2 font-display text-2xl font-semibold">{{ displayPlanTitle(plan) }}</h2>
            </div>
            <span :class="stateClass(plan.id)" class="rounded-full px-3 py-1.5 text-xs font-semibold">{{ stateLabel(plan.id) }}</span>
          </div>
          <div v-if="stateOf(plan.id) === 'GENERATING'" class="mt-5 rounded-xl border border-indigo/15 bg-indigo/5 p-4" aria-live="polite" aria-atomic="true">
            <div class="flex items-start justify-between gap-4">
              <div>
                <p class="text-sm font-semibold text-indigo">{{ activityText(plan, currentActivity(plan)) }}</p>
                <p class="mt-1 text-xs leading-5 text-ink/50">{{ progress[plan.id]?.lesson?.status === 'DRAFT_READY' ? '不用继续等待，核对结果会逐节更新。' : '模型返回前，这一行也会保留当前正在做的事。' }}</p>
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
            <p class="mt-3 text-xs leading-5 text-ink/50">{{ remainingTimeText(plan) }} {{ progress[plan.id]?.lesson?.status === 'DRAFT_READY' ? '现在就可以打开阅读。' : '生成会在后台继续，可以关闭或离开此页。' }}</p>
            <ol v-if="recentActivities(plan).length" class="mt-4 space-y-2 border-t border-indigo/10 pt-3" aria-label="最近进度">
              <li v-for="activity in recentActivities(plan)" :key="activity.sequence" class="flex items-start gap-2 text-xs leading-5 text-ink/55">
                <span class="mt-1.5 size-1.5 shrink-0 rounded-full" :class="activity.outcome === 'RUNNING' ? 'animate-pulse bg-copper' : activity.outcome === 'SUCCEEDED' ? 'bg-emerald-600' : 'bg-amber-600'" />
                <span>{{ activityText(plan, activity) }}</span>
              </li>
            </ol>
          </div>
          <p v-else class="mt-4 min-h-12 text-sm leading-6 text-ink/60" aria-live="polite">{{ progressText(plan) }}</p>
          <p v-if="progressErrors[plan.id]" class="mt-3 rounded-lg bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-800" role="status">暂时没拿到最新进度，正在自动重试。生成任务不会受影响。</p>
          <p v-if="!showingAllVersions && versionCount(plan.id) > 1" class="mt-3 text-xs leading-5 text-ink/45">同一本规则书的 {{ versionCount(plan.id) - 1 }} 个历史版本已收起。</p>
          <dl class="mt-5 grid grid-cols-2 gap-3 rounded-2xl bg-canvas p-4 text-sm">
            <div><dt class="text-ink/45">新手人数</dt><dd class="mt-1 font-semibold">{{ plan.beginnerCount }} 人</dd></div>
            <div><dt class="text-ink/45">计划时长</dt><dd class="mt-1 font-semibold">{{ plan.durationMinutes }} 分钟</dd></div>
          </dl>
          <div class="mt-6 flex items-center justify-between gap-3">
            <span v-if="plan.id === rememberedPlanId" class="text-xs font-semibold text-indigo">上次打开</span>
            <span v-else class="text-xs text-ink/35">{{ plan.sections.length }} 个章节</span>
            <div class="flex items-center gap-2">
              <RouterLink v-if="progress[plan.id]?.lesson" :to="{ name: 'lesson', params: { planId: plan.id } }" class="rounded-lg bg-indigo px-4 py-2.5 text-sm font-semibold text-white">{{ progress[plan.id]?.lesson?.status === 'DRAFT_READY' ? '立即阅读完整讲解' : stateOf(plan.id) === 'GENERATING' ? '阅读已完成章节' : stateOf(plan.id) === 'INCOMPLETE' ? '阅读并补全' : '打开讲解' }}</RouterLink>
              <button v-else-if="stateOf(plan.id) !== 'GENERATING'" :disabled="launchingPlanId === plan.id || Boolean(deletingPlanId)" class="rounded-lg bg-indigo px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-40" @click="launch(plan.id)">{{ launchingPlanId === plan.id ? '正在启动…' : stateOf(plan.id) === 'FAILED' ? '重新生成' : '开始生成' }}</button>
              <span v-else class="inline-flex items-center gap-2 text-sm font-semibold text-indigo"><span class="size-3 animate-spin rounded-full border-2 border-indigo/20 border-t-indigo" />后台处理中</span>
              <button type="button" :disabled="Boolean(deletingPlanId) || cleanupLoading" class="min-h-10 rounded-lg px-2 text-sm font-semibold text-ink/40 hover:bg-red-50 hover:text-red-700 disabled:opacity-40" @click="deletePlan(plan)">{{ deletingPlanId === plan.id ? '删除中…' : '删除' }}</button>
            </div>
          </div>
        </li>
      </ol>
    </section>
  </AppShell>
</template>
