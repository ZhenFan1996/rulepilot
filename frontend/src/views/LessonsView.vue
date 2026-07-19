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
  sections: Array<{ required: boolean; topicKey: string; title: string }>
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
  activities: Array<{ sequence: number; operation: string; summary: string; outcome: string }>
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
const loading = ref(true)
const errorMessage = ref('')
const launchingPlanId = ref('')
const now = ref(Date.now())
const rememberedPlanId = localStorage.getItem('rulepilot:last-plan-id')
const terminalStates = new Set(['COMPLETED', 'INSUFFICIENT_EVIDENCE', 'DEGRADED', 'FAILED'])
let pollTimer: ReturnType<typeof setTimeout> | undefined
let clockTimer: ReturnType<typeof setInterval> | undefined

const readableCount = computed(() => Object.values(progress.value).filter((item) => item.lesson).length)
const hasActiveRuns = computed(() => Object.values(progress.value).some(
  (item) => item.run && !terminalStates.has(item.run.run.state),
))
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

function progressText(plan: TeachingPlan) {
  const item = progress.value[plan.id]
  const state = stateOf(plan.id)
  if (state === 'GENERATING') {
    const seconds = Math.max(0, Math.floor((now.value - new Date(item!.run!.run.createdAt).getTime()) / 1000))
    const latest = item!.run!.activities.at(-1)
    const stage = latest?.operation.startsWith('searchRuleEvidence') ? '正在查找规则依据'
      : latest?.operation.startsWith('composeTeachingSection') ? '正在编写章节'
        : latest?.operation.startsWith('reviewGeneratedContent') ? '正在核对规则'
          : latest?.operation.startsWith('reviseTeachingSection') ? '正在修正讲解'
            : '正在准备讲解'
    return `${stage} · 已用时 ${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}。可以关闭或离开此页。`
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
  const [runResponse, lessonResponse] = await Promise.all([
    checkedFetch(`/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(plan.id)}`),
    checkedFetch(`/api/v1/teaching-plans/${plan.id}/illustrated-lessons/latest`),
  ])
  progress.value = {
    ...progress.value,
    [plan.id]: {
      run: runResponse.ok ? await runResponse.json() as AssistantRun : null,
      lesson: lessonResponse.ok ? await lessonResponse.json() as LessonSummary : null,
    },
  }
}

async function refreshProgress() {
  await Promise.all(plans.value.map(loadProgress))
  if (pollTimer) clearTimeout(pollTimer)
  if (hasActiveRuns.value) pollTimer = setTimeout(() => void refreshProgress(), 1500)
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
    await loadProgress(plans.value.find((plan) => plan.id === planId)!)
    if (!pollTimer) pollTimer = setTimeout(() => void refreshProgress(), 1000)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '讲解任务没有启动。'
  } finally {
    launchingPlanId.value = ''
  }
}

onMounted(() => {
  clockTimer = setInterval(() => { now.value = Date.now() }, 1000)
  void loadPlans()
})
onBeforeUnmount(() => {
  if (pollTimer) clearTimeout(pollTimer)
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
          <p class="mt-4 min-h-12 text-sm leading-6 text-ink/60" aria-live="polite">{{ progressText(plan) }}</p>
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
