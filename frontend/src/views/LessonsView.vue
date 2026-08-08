<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import { notifyLoginRequired } from '@/lib/authSession'
import { hasReadableLesson, mergeLessonProgress, type LessonProgressSummary } from '@/lib/lessonProgressState'
import { groupPlansForReading, playerFacingTitle } from '@/lib/lessonPresentation'
import { useLocale } from '@/lib/locale'
import { notifyTeachingLaunched, type TeachingLaunch } from '@/lib/teachingLaunch'
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

interface PlanProgress {
  run: TeachingRunProgress | null
  lesson: LessonProgressSummary | null
}

interface CsrfResponse { headerName: string; token: string }
type PlanFilter = 'READABLE' | 'PENDING' | 'ALL'

const route = useRoute()
const { locale, t } = useLocale()
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
const knownRunIds = new Map<string, string>()
const requestVersions = new Map<string, number>()
const terminalSettlingReads = new Map<string, number>()
let pollTimer: ReturnType<typeof setTimeout> | undefined
let clockTimer: ReturnType<typeof setInterval> | undefined
let disposed = false

const startedPlanId = computed(() => typeof route.query.started === 'string' ? route.query.started : '')
const startedRunId = computed(() => typeof route.query.run === 'string' ? route.query.run : '')

function stateOf(planId: string) {
  const item = progress.value[planId]
  if (item?.run && !terminalStates.has(item.run.run.state)) return 'GENERATING'
  if (item?.lesson?.status === 'COMPLETE') return 'COMPLETE'
  if (item?.lesson?.status === 'DRAFT_READY') return 'DRAFT_READY'
  if (item?.lesson?.status === 'INCOMPLETE') return 'INCOMPLETE'
  if (item?.run?.run.state === 'FAILED') return 'FAILED'
  if (item?.run && terminalStates.has(item.run.run.state)) return 'NEEDS_ATTENTION'
  return 'PLANNED'
}

function stateLabel(planId: string) {
  if (stateOf(planId) === 'GENERATING' && progress.value[planId]?.lesson?.status === 'DRAFT_READY') {
    return t('lessons.state.readableReviewing')
  }
  return ({
    GENERATING: t('lessons.state.generating'),
    COMPLETE: t('lessons.state.complete'),
    DRAFT_READY: t('lessons.state.draftReady'),
    INCOMPLETE: t('lessons.state.incomplete'),
    FAILED: t('lessons.state.failed'),
    NEEDS_ATTENTION: t('lessons.state.needsAttention'),
    PLANNED: t('lessons.state.planned'),
  } as const)[stateOf(planId)]
}

function stateClass(planId: string) {
  const state = stateOf(planId)
  if (state === 'COMPLETE' || state === 'DRAFT_READY'
    || state === 'GENERATING' && progress.value[planId]?.lesson?.status === 'DRAFT_READY') {
    return 'bg-emerald-50 text-emerald-800'
  }
  if (state === 'GENERATING') return 'bg-indigo/10 text-indigo'
  if (state === 'FAILED' || state === 'NEEDS_ATTENTION') return 'bg-red-50 text-red-800'
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
  if (item?.run && terminalStates.has(item.run.run.state)) return 100
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
    ? hasReadableLesson(progress.value[plan.id]?.lesson)
    : !hasReadableLesson(progress.value[plan.id]?.lesson)
}))
const readableGroupCount = computed(() => planGroups.value.filter((group) => hasReadableLesson(progress.value[group.plan.id]?.lesson)).length)
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
  return teachingActivityText(plan, activities, activity, locale.value)
}

function currentActivity(plan: TeachingPlan) {
  return progress.value[plan.id]?.run?.activities.at(-1)
}

function remainingTimeText(plan: TeachingPlan) {
  return teachingRemainingTimeText(plan, progress.value[plan.id]?.run ?? null, now.value, locale.value)
}

function recentActivities(plan: TeachingPlan) {
  return (progress.value[plan.id]?.run?.activities ?? []).slice(-3).reverse()
}

function progressText(plan: TeachingPlan) {
  const item = progress.value[plan.id]
  const state = stateOf(plan.id)
  if (state === 'GENERATING') {
    if (item?.lesson?.status === 'DRAFT_READY') {
      return t('lessons.progress.draftReviewing', { elapsed: elapsedLabel(plan) })
    }
    return t('lessons.progress.generating', { activity: activityText(plan, item!.run!.activities.at(-1)), elapsed: elapsedLabel(plan) })
  }
  if (state === 'DRAFT_READY') return t('lessons.progress.draftReady')
  if (state === 'INCOMPLETE') {
    const supported = item?.lesson?.sections.filter((section) => section.evidenceStatus === 'SUPPORTED').length ?? 0
    return t('lessons.progress.incomplete', { supported })
  }
  if (state === 'FAILED') return t('lessons.progress.failed')
  if (state === 'NEEDS_ATTENTION') return t('lessons.progress.needsAttention')
  if (state === 'COMPLETE') return t('lessons.progress.complete')
  return t('lessons.progress.planned')
}

function createdLabel(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return t('lessons.createdUnknown')
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

async function checkedFetch(path: string, options?: Parameters<typeof fetch>[1]) {
  const response = await fetch(path, { credentials: 'include', ...options })
  if (response.status === 401) {
    notifyLoginRequired()
    throw new Error(t('lessons.error.login'))
  }
  return response
}

async function loadProgress(plan: TeachingPlan) {
  const requestVersion = (requestVersions.get(plan.id) ?? 0) + 1
  requestVersions.set(plan.id, requestVersion)
  try {
    const previousRun = progress.value[plan.id]?.run
    const expectedRunId = knownRunIds.get(plan.id)
    const activityCursor = expectedRunId ? '' : teachingActivityCursor(previousRun ?? null)
    const runPath = expectedRunId
      ? `/api/v1/assistant-runs/${encodeURIComponent(expectedRunId)}`
      : `/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(plan.id)}${activityCursor}`
    const [runResponse, lessonResponse] = await Promise.all([
      checkedFetch(runPath),
      checkedFetch(`/api/v1/teaching-plans/${plan.id}/illustrated-lessons/latest`),
    ])
    if (!runResponse.ok && runResponse.status !== 404) throw new Error(t('lessons.error.runProgress'))
    if (!lessonResponse.ok && lessonResponse.status !== 404) throw new Error(t('lessons.error.contentProgress'))
    const incomingRun = runResponse.ok ? await runResponse.json() as TeachingRunProgress : null
    const incomingLesson = lessonResponse.ok ? await lessonResponse.json() as LessonProgressSummary : null
    if (requestVersions.get(plan.id) !== requestVersion) return
    const run = mergeTeachingRunProgress(previousRun ?? null, incomingRun)
    const lesson = mergeLessonProgress(progress.value[plan.id]?.lesson ?? null, incomingLesson)
    progress.value = {
      ...progress.value,
      [plan.id]: {
        run,
        lesson,
      },
    }
    if (expectedRunId && run?.run.id === expectedRunId && terminalStates.has(run.run.state)) {
      const settlingRead = terminalSettlingReads.get(plan.id) ?? 0
      if (run.run.state !== 'COMPLETED' || lesson || settlingRead >= 3) {
        knownRunIds.delete(plan.id)
        terminalSettlingReads.delete(plan.id)
      } else {
        terminalSettlingReads.set(plan.id, settlingRead + 1)
      }
    }
    if (progressErrors.value[plan.id]) {
      const next = { ...progressErrors.value }
      delete next[plan.id]
      progressErrors.value = next
    }
  } catch (error) {
    progressErrors.value = {
      ...progressErrors.value,
      [plan.id]: error instanceof Error ? error.message : t('lessons.error.latestProgress'),
    }
    throw error
  }
}

function plansNeedingRefresh() {
  return plans.value.filter((plan) => knownRunIds.has(plan.id)
    || stateOf(plan.id) === 'GENERATING'
    || Boolean(progressErrors.value[plan.id]))
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
    if (!response.ok) throw new Error(t('lessons.error.load'))
    plans.value = (await response.json()) as TeachingPlan[]
    if (startedPlanId.value && startedRunId.value && plans.value.some((plan) => plan.id === startedPlanId.value)) {
      knownRunIds.set(startedPlanId.value, startedRunId.value)
    }
    await refreshProgress()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('lessons.error.loadShort')
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
    if (!csrfResponse.ok) throw new Error(t('lessons.error.secureSession'))
    const csrf = await csrfResponse.json() as CsrfResponse
    const response = await checkedFetch(`/api/v1/teaching-plans/${planId}/illustrated-lessons`, {
      method: 'POST',
      headers: { [csrf.headerName]: csrf.token },
    })
    if (!response.ok) throw new Error(t('lessons.error.launch'))
    const launch = await response.json() as TeachingLaunch
    knownRunIds.set(planId, launch.assistantRunId)
    terminalSettlingReads.delete(planId)
    const plan = plans.value.find((candidate) => candidate.id === planId)!
    notifyTeachingLaunched({ planId, runId: launch.assistantRunId, gameTitle: displayPlanTitle(plan) })
    localStorage.setItem('rulepilot:last-plan-id', planId)
    await loadProgress(plan).catch(() => undefined)
    scheduleProgressRefresh(1000)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('lessons.error.launchShort')
  } finally {
    launchingPlanId.value = ''
  }
}

async function deletePlan(plan: TeachingPlan) {
  if (deletingPlanId.value || cleanupLoading.value) return
  if (!window.confirm(t('lessons.delete.confirm', { title: displayPlanTitle(plan) }))) return
  deletingPlanId.value = plan.id
  errorMessage.value = ''
  try {
    const csrfResponse = await checkedFetch('/api/auth/csrf')
    if (!csrfResponse.ok) throw new Error(t('lessons.error.secureSession'))
    const csrf = await csrfResponse.json() as CsrfResponse
    const response = await checkedFetch(`/api/v1/teaching-plans/${encodeURIComponent(plan.id)}`, {
      method: 'DELETE', headers: { [csrf.headerName]: csrf.token },
    })
    if (!response.ok) throw new Error(t('lessons.delete.failed'))
    if (localStorage.getItem('rulepilot:last-plan-id') === plan.id) localStorage.removeItem('rulepilot:last-plan-id')
    plans.value = plans.value.filter((item) => item.id !== plan.id)
    const nextProgress = { ...progress.value }
    delete nextProgress[plan.id]
    progress.value = nextProgress
    cleanupMessage.value = t('lessons.delete.done')
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('lessons.delete.failedShort')
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
    if (!previewResponse.ok) throw new Error(t('lessons.cleanup.previewFailed'))
    const preview = await previewResponse.json() as { duplicateCount: number }
    if (preview.duplicateCount === 0) {
      cleanupMessage.value = t('lessons.cleanup.none')
      return
    }
    if (!window.confirm(t('lessons.cleanup.confirm', { count: preview.duplicateCount }))) return
    const csrfResponse = await checkedFetch('/api/auth/csrf')
    if (!csrfResponse.ok) throw new Error(t('lessons.error.secureSession'))
    const csrf = await csrfResponse.json() as CsrfResponse
    const response = await checkedFetch('/api/v1/teaching-plans/cleanup-duplicates', {
      method: 'POST', headers: { [csrf.headerName]: csrf.token },
    })
    if (!response.ok) throw new Error(t('lessons.cleanup.failed'))
    const result = await response.json() as { deletedCount: number }
    await loadPlans()
    cleanupMessage.value = result.deletedCount ? t('lessons.cleanup.done', { count: result.deletedCount }) : t('lessons.cleanup.nothing')
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('lessons.cleanup.failedShort')
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
    <section class="tabletop-page max-w-6xl">
      <p class="tabletop-kicker">{{ t('lessons.eyebrow') }}</p>
      <div class="mt-4 flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
        <div>
          <h1 class="font-display text-4xl font-semibold tracking-tight">{{ t('lessons.title') }}</h1>
          <p class="mt-4 max-w-2xl leading-7 text-ink/55">{{ t('lessons.description') }}</p>
        </div>
        <div class="flex flex-wrap gap-2">
          <button v-if="plans.length > 1" type="button" :disabled="cleanupLoading || Boolean(deletingPlanId)" class="inline-flex min-h-11 items-center justify-center rounded-lg border border-ink/15 px-4 text-sm font-semibold hover:border-copper/50 disabled:opacity-40" @click="cleanDuplicates">{{ cleanupLoading ? t('lessons.cleanup.loading') : t('lessons.cleanup.action') }}</button>
          <RouterLink :to="{ name: 'teach' }" class="inline-flex min-h-11 items-center justify-center rounded-lg bg-copper px-4 text-sm font-semibold text-white">{{ t('lessons.upload') }}</RouterLink>
        </div>
      </div>

      <p v-if="startedPlanId" class="mt-6 rounded-lg bg-indigo/5 px-4 py-3 text-sm text-indigo" role="status">{{ t('lessons.started') }}</p>
      <p v-if="cleanupMessage" class="mt-6 rounded-lg bg-emerald-50 px-4 py-3 text-sm text-emerald-800" role="status">{{ cleanupMessage }}</p>
      <div v-if="plans.length" class="mt-6 flex flex-wrap items-center gap-x-4 gap-y-2 text-sm text-ink/45">
        <p>{{ t('lessons.summary', { versions: plans.length, rulebooks: planGroups.length, readable: readableGroupCount }) }}</p>
        <button v-if="plans.length > planGroups.length" type="button" class="font-semibold text-indigo underline decoration-indigo-soft underline-offset-4 " @click="showingAllVersions ? hideAllVersions() : showAllVersions()">{{ showingAllVersions ? t('lessons.history.hide') : t('lessons.history.show', { count: plans.length }) }}</button>
      </div>

      <div v-if="plans.length" class="mt-5 flex flex-wrap gap-2" role="group" :aria-label="t('lessons.filter.aria')">
        <button type="button" class="min-h-10 rounded-full px-4 text-sm font-semibold transition" :class="selectedPlanFilter === 'READABLE' ? 'bg-ink text-paper' : 'border border-ink/15 text-ink/65 hover:border-ink/35'" :aria-pressed="selectedPlanFilter === 'READABLE'" @click="planFilter = 'READABLE'">{{ t('lessons.filter.readable', { count: readableGroupCount }) }}</button>
        <button type="button" class="min-h-10 rounded-full px-4 text-sm font-semibold transition" :class="selectedPlanFilter === 'PENDING' ? 'bg-ink text-paper' : 'border border-ink/15 text-ink/65 hover:border-ink/35'" :aria-pressed="selectedPlanFilter === 'PENDING'" @click="planFilter = 'PENDING'">{{ t('lessons.filter.pending', { count: pendingGroupCount }) }}</button>
        <button type="button" class="min-h-10 rounded-full px-4 text-sm font-semibold transition" :class="selectedPlanFilter === 'ALL' ? 'bg-ink text-paper' : 'border border-ink/15 text-ink/65 hover:border-ink/35'" :aria-pressed="selectedPlanFilter === 'ALL'" @click="planFilter = 'ALL'">{{ t('lessons.filter.all', { count: planGroups.length }) }}</button>
      </div>

      <div v-if="loading" class="mt-8 rounded-xl border border-ink/10 bg-paper p-8 text-ink/50" role="status">{{ t('lessons.loading') }}</div>

      <div v-else-if="errorMessage" class="mt-10 rounded-3xl border border-red-200 bg-red-50 p-6 text-red-800" role="alert">
        <p>{{ errorMessage }}</p>
        <button class="mt-4 text-sm font-semibold underline underline-offset-4" @click="loadPlans">{{ t('lessons.reload') }}</button>
      </div>

      <div v-else-if="plans.length === 0" class="mt-8 rounded-xl border border-dashed border-ink/20 px-6 py-14 text-center">
        <h2 class="font-display text-2xl font-semibold">{{ t('lessons.empty.title') }}</h2>
        <p class="mx-auto mt-3 max-w-lg leading-7 text-ink/55">{{ t('lessons.empty.description') }}</p>
        <RouterLink :to="{ name: 'teach' }" class="mt-7 inline-flex rounded-lg bg-copper px-5 py-3 font-semibold text-white">{{ t('lessons.empty.action') }}</RouterLink>
      </div>

      <div v-else-if="displayedPlans.length === 0" class="mt-8 rounded-xl border border-dashed border-ink/20 px-6 py-12 text-center">
        <h2 class="font-display text-2xl font-semibold">{{ t('lessons.noReadable.title') }}</h2>
        <p class="mx-auto mt-3 max-w-lg leading-7 text-ink/55">{{ t('lessons.noReadable.description') }}</p>
        <button type="button" class="mt-6 text-sm font-semibold text-indigo underline underline-offset-4" @click="planFilter = 'PENDING'">{{ t('lessons.noReadable.action') }}</button>
      </div>

      <ol v-else class="score-track mt-10 grid gap-5 md:grid-cols-2">
        <li v-for="plan in displayedPlans" :key="plan.id" class="tabletop-panel player-board relative overflow-hidden p-6" :class="plan.id === startedPlanId ? 'ring-2 ring-copper/30' : ''">
          <div class="flex items-start justify-between gap-4">
            <div class="flex min-w-0 items-start gap-3">
              <span class="score-token shrink-0" aria-hidden="true" />
              <div class="min-w-0">
                <p class="text-xs font-medium text-ink/40">{{ createdLabel(plan.createdAt) }}</p>
                <h2 class="mt-1 truncate font-display text-2xl font-semibold">{{ displayPlanTitle(plan) }}</h2>
              </div>
            </div>
            <span :class="stateClass(plan.id)" class="rounded-full px-3 py-1.5 text-xs font-semibold">{{ stateLabel(plan.id) }}</span>
          </div>
          <div v-if="stateOf(plan.id) === 'GENERATING'" class="mt-5 rounded-xl border border-indigo/15 bg-indigo/5 p-4" aria-live="polite" aria-atomic="true">
            <div class="flex items-start justify-between gap-4">
              <div>
                <p class="text-sm font-semibold text-indigo">{{ activityText(plan, currentActivity(plan)) }}</p>
                <p class="mt-1 text-xs leading-5 text-ink/50">{{ progress[plan.id]?.lesson?.status === 'DRAFT_READY' ? t('lessons.live.draftHint') : t('lessons.live.activityHint') }}</p>
              </div>
              <span class="shrink-0 font-mono text-sm font-semibold text-indigo">{{ elapsedLabel(plan) }}</span>
            </div>
            <div class="mt-4 h-2 overflow-hidden rounded-full bg-indigo/10" role="progressbar" :aria-valuemin="0" :aria-valuemax="plan.sections.length" :aria-valuenow="processedChapterCount(plan)" :aria-label="t('lessons.live.progressAria', { processed: processedChapterCount(plan), total: plan.sections.length })">
              <div class="h-full rounded-full bg-indigo transition-[width] duration-500" :style="{ width: chapterProgressWidth(plan) }" />
            </div>
            <div class="mt-2 flex flex-wrap justify-between gap-2 text-xs text-ink/55">
              <span>{{ t('lessons.live.processed', { processed: processedChapterCount(plan), total: plan.sections.length, supported: supportedChapterCount(plan) }) }}</span>
              <span>{{ t('lessons.live.modelCalls', { count: progress[plan.id]?.run?.budget.usedModelCalls ?? 0 }) }}</span>
            </div>
            <p class="mt-3 text-xs leading-5 text-ink/50">{{ remainingTimeText(plan) }} {{ progress[plan.id]?.lesson?.status === 'DRAFT_READY' ? t('lessons.live.readNow') : t('lessons.live.background') }}</p>
            <ol v-if="recentActivities(plan).length" class="mt-4 stack-y-sm border-t border-indigo/10 pt-3" :aria-label="t('lessons.live.recent')">
              <li v-for="activity in recentActivities(plan)" :key="activity.sequence" class="flex items-start gap-2 text-xs leading-5 text-ink/55">
                <span class="mt-1.5 size-1.5 shrink-0 rounded-full" :class="activity.outcome === 'RUNNING' ? 'animate-pulse bg-copper' : activity.outcome === 'SUCCEEDED' ? 'bg-emerald-600' : 'bg-amber-600'" />
                <span>{{ activityText(plan, activity) }}</span>
              </li>
            </ol>
          </div>
          <p v-else class="mt-4 min-h-12 text-sm leading-6 text-ink/60" aria-live="polite">{{ progressText(plan) }}</p>
          <p v-if="progressErrors[plan.id]" class="mt-3 rounded-lg bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-800" role="status">{{ t('lessons.live.retrying') }}</p>
          <p v-if="!showingAllVersions && versionCount(plan.id) > 1" class="mt-3 text-xs leading-5 text-ink/45">{{ t('lessons.history.hidden', { count: versionCount(plan.id) - 1 }) }}</p>
          <dl class="mt-5 grid grid-cols-2 gap-3 rounded-2xl bg-canvas p-4 text-sm">
            <div><dt class="text-ink/45">{{ t('lessons.meta.beginners') }}</dt><dd class="mt-1 font-semibold">{{ t('lessons.meta.people', { count: plan.beginnerCount }) }}</dd></div>
            <div><dt class="text-ink/45">{{ t('lessons.meta.duration') }}</dt><dd class="mt-1 font-semibold">{{ t('lessons.meta.minutes', { count: plan.durationMinutes }) }}</dd></div>
          </dl>
          <div class="mt-6 flex items-center justify-between gap-3">
            <span v-if="plan.id === rememberedPlanId" class="text-xs font-semibold text-indigo">{{ t('lessons.lastOpened') }}</span>
            <span v-else class="text-xs text-ink/35">{{ t('lessons.chapterCount', { count: plan.sections.length }) }}</span>
            <div class="flex items-center gap-2">
              <RouterLink v-if="hasReadableLesson(progress[plan.id]?.lesson)" :to="{ name: 'lesson', params: { planId: plan.id } }" class="rounded-lg bg-indigo px-4 py-2.5 text-sm font-semibold text-white">{{ progress[plan.id]?.lesson?.status === 'DRAFT_READY' ? t('lessons.action.readFull') : stateOf(plan.id) === 'GENERATING' ? t('lessons.action.readPublished') : stateOf(plan.id) === 'INCOMPLETE' ? t('lessons.action.readAndComplete') : t('lessons.action.open') }}</RouterLink>
              <button v-else-if="stateOf(plan.id) !== 'GENERATING'" :disabled="launchingPlanId === plan.id || Boolean(deletingPlanId)" class="rounded-lg bg-indigo px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-40" @click="launch(plan.id)">{{ launchingPlanId === plan.id ? t('lessons.action.launching') : stateOf(plan.id) === 'FAILED' || stateOf(plan.id) === 'NEEDS_ATTENTION' ? t('lessons.action.regenerate') : t('lessons.action.generate') }}</button>
              <span v-else class="inline-flex items-center gap-2 text-sm font-semibold text-indigo"><span class="size-3 animate-spin rounded-full border-2 border-indigo/20 border-t-indigo" />{{ t('lessons.action.background') }}</span>
              <button type="button" :disabled="Boolean(deletingPlanId) || cleanupLoading" class="min-h-10 rounded-lg px-2 text-sm font-semibold text-ink/40 hover:bg-red-50 hover:text-red-700 disabled:opacity-40" @click="deletePlan(plan)">{{ deletingPlanId === plan.id ? t('lessons.action.deleting') : t('lessons.action.delete') }}</button>
            </div>
          </div>
        </li>
      </ol>
    </section>
  </AppShell>
</template>
