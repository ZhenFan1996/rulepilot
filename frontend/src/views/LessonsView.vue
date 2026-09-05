<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import DestructiveActionDialog from '@/components/DestructiveActionDialog.vue'
import PlayerWorkStatusText from '@/components/PlayerWorkStatusText.vue'
import { notifyLoginRequired } from '@/lib/authSession'
import { notifyBackgroundWorkChanged } from '@/lib/backgroundWorkRefresh'
import { hasReadableLesson, mergeLessonProgress, type LessonProgressSummary } from '@/lib/lessonProgressState'
import { groupPlansForReading, playerFacingTitle } from '@/lib/lessonPresentation'
import { useLocale } from '@/lib/locale'
import {
  playerJourneyFailurePresentation,
  playerJourneyRunIsTerminal,
  typedFailurePolicy,
  type PlayerJourneyFailurePolicy,
} from '@/lib/playerJourney'
import { guideWorkStatus, playerWorkStatus } from '@/lib/playerWorkStatus'
import {
  buildPendingGuideJourneys,
  type PendingGuideCatalogGame,
  type PendingGuideDocument,
  type PendingGuideImport,
  type PendingGuideJourney,
  type PendingGuidePreparationRun,
  type PendingGuideUploadHandoff,
} from '@/lib/pendingGuideJourney'
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
  gameTitle: string
  premise: string
  createdAt: string
  sections: Array<{ position: number; required: boolean; topicKey: string; title: string; visualEvidenceRecommended: boolean }>
  lesson?: LessonProgressSummary | null
}

interface PlanProgress {
  run: TeachingRunProgress | null
  lesson: LessonProgressSummary | null
}

interface CsrfResponse { headerName: string; token: string }
type PlanFilter = 'READABLE' | 'PENDING' | 'ALL'
type DestructiveAction =
  | { kind: 'delete-plan'; plan: TeachingPlan }
  | { kind: 'delete-failed-preparation'; journey: PendingGuideJourney }
  | { kind: 'cleanup'; duplicateCount: number }

const route = useRoute()
const { locale, t } = useLocale()
const plans = ref<TeachingPlan[]>([])
const guideImports = ref<PendingGuideImport[]>([])
const guideUploadHandoffs = ref<PendingGuideUploadHandoff[]>([])
const preparationRuns = ref<PendingGuidePreparationRun[]>([])
const guideDocuments = ref<PendingGuideDocument[]>([])
const guideCatalog = ref<PendingGuideCatalogGame[]>([])
const progress = ref<Record<string, PlanProgress>>({})
const progressErrors = ref<Record<string, string>>({})
const loading = ref(true)
const errorMessage = ref('')
const loginRequired = ref(false)
const launchingPlanId = ref('')
const launchErrors = ref<Record<string, string>>({})
const deletingPlanId = ref('')
const deletingJourneyId = ref('')
const cleanupLoading = ref(false)
const cleanupMessage = ref('')
const destructiveAction = ref<DestructiveAction | null>(null)
const destructiveError = ref('')
const pageHeading = ref<HTMLElement | null>(null)
const restoreAfterDestructiveSuccess = ref(false)
const destructiveCopy = computed(() => locale.value === 'zh-CN' ? {
  deleteTitle: '删除这份讲解？', deleteDescription: (title: string) => `“${title}”的这份讲解和仍在进行的生成任务将被删除。规则书会保留，你之后可以重新生成。`,
  deleteCancel: '保留讲解', deleteConfirm: '删除讲解', deleteRetry: '重新尝试删除',
  failedTitle: '删除这次失败的讲解准备？', failedDescription: (title: string) => `“${title}”的失败记录将从我的讲解和后台任务中移除。规则书会保留，之后仍可重新生成。`,
  failedCancel: '保留记录', failedConfirm: '删除失败记录', failedRetry: '重新尝试删除',
  cleanupTitle: '清理重复讲解？', cleanupDescription: (count: number) => `发现 ${count} 份重复讲解。将保留内容最完整且最新的一份，删除其余重复项并停止它们仍在进行的任务。`,
  cleanupCancel: '保留全部', cleanupConfirm: '清理重复项', cleanupRetry: '重新尝试清理',
} : {
  deleteTitle: 'Delete this guide?', deleteDescription: (title: string) => `The guide for “${title}” and any generation still running for it will be deleted. Its rulebook stays available, and you can generate another guide later.`,
  deleteCancel: 'Keep guide', deleteConfirm: 'Delete guide', deleteRetry: 'Try deletion again',
  failedTitle: 'Delete this failed guide attempt?', failedDescription: (title: string) => `The failed attempt for “${title}” will be removed from My Guides and background work. Its rulebook stays available, so you can generate another guide later.`,
  failedCancel: 'Keep attempt', failedConfirm: 'Delete failed attempt', failedRetry: 'Try deletion again',
  cleanupTitle: 'Clean up duplicate guides?', cleanupDescription: (count: number) => `${count} duplicate guides were found. The newest, most complete copy will remain; the other duplicates and any work still running for them will be removed.`,
  cleanupCancel: 'Keep all guides', cleanupConfirm: 'Clean up duplicates', cleanupRetry: 'Try cleanup again',
})
const destructivePending = computed(() => Boolean(deletingPlanId.value || deletingJourneyId.value) || cleanupLoading.value)
const destructiveDialog = computed(() => {
  const action = destructiveAction.value
  if (action?.kind === 'cleanup') return {
    title: destructiveCopy.value.cleanupTitle,
    description: destructiveCopy.value.cleanupDescription(action.duplicateCount),
    cancel: destructiveCopy.value.cleanupCancel,
    confirm: destructiveCopy.value.cleanupConfirm,
    pending: t('lessons.cleanup.loading'),
    retry: destructiveCopy.value.cleanupRetry,
  }
  if (action?.kind === 'delete-failed-preparation') return {
    title: destructiveCopy.value.failedTitle,
    description: destructiveCopy.value.failedDescription(action.journey.title),
    cancel: destructiveCopy.value.failedCancel,
    confirm: destructiveCopy.value.failedConfirm,
    pending: t('lessons.action.deleting'),
    retry: destructiveCopy.value.failedRetry,
  }
  return {
    title: destructiveCopy.value.deleteTitle,
    description: destructiveCopy.value.deleteDescription(action?.kind === 'delete-plan' ? displayPlanTitle(action.plan) : ''),
    cancel: destructiveCopy.value.deleteCancel,
    confirm: destructiveCopy.value.deleteConfirm,
    pending: t('lessons.action.deleting'),
    retry: destructiveCopy.value.deleteRetry,
  }
})
const retryingJourneyId = ref('')
const journeyRetryErrors = ref<Record<string, string>>({})
const showingAllVersions = ref(false)
const planFilter = ref<PlanFilter>('READABLE')
const now = ref(Date.now())
const rememberedPlanId = localStorage.getItem('rulepilot:last-plan-id')
const knownRunIds = new Map<string, string>()
const requestVersions = new Map<string, number>()
const progressControllers = new Map<string, AbortController>()
const terminalSettlingReads = new Map<string, number>()
let pollTimer: ReturnType<typeof setTimeout> | undefined
let journeyTimer: ReturnType<typeof setTimeout> | undefined
let clockTimer: ReturnType<typeof setInterval> | undefined
let disposed = false
let latestListRequest = 0
let activeListController: AbortController | null = null
let shellIdentityResolved = false
let shellUsername = ''

const startedPlanId = computed(() => typeof route.query.started === 'string' ? route.query.started : '')
const startedRunId = computed(() => typeof route.query.run === 'string' ? route.query.run : '')
const loginTarget = computed(() => ({ name: 'login', query: { redirect: route.fullPath } }))
const signedOutCopy = computed(() => locale.value === 'zh-CN' ? {
  pageTitle: '回到你的规则讲解',
  pageDescription: '这里保存正在准备、已经可读和需要处理的讲解。登录后会回到当前地址。',
  title: '登录后查看你的讲解',
  description: '你的讲解和后台进度属于账户。登录后回到这里继续，不需要重新上传规则书。',
  action: '登录后继续',
} : {
  pageTitle: 'Return to your rule guides',
  pageDescription: 'This is where guides that are being prepared, ready to read, or need attention stay together. Sign in and return to this address.',
  title: 'Sign in to view your guides',
  description: 'Your guides and background progress belong to your account. Sign in to return here without adding the rulebook again.',
  action: 'Sign in and continue',
})
const pendingCopy = computed(() => locale.value === 'zh-CN' ? {
  eyebrow: '已进入我的讲解', title: '正在准备的讲解',
  detail: '这些条目来自持久化下载、规则书读取或讲解准备任务；刷新、离开页面或换入口都不会丢失。',
  rulebook: '规则书', downloading: '正在下载并核验来源文件', reading: '正在整理规则文字和原文页面',
  preparing: '规则书已可读，正在准备讲解', failed: '任务已经停止，请按下面的恢复说明处理',
  progress: '已确认下载进度', openRulebook: '先读规则书', openSource: '查看任务入口',
  retryPreparation: '重新准备讲解', retryingPreparation: '正在重新启动…',
  retryFailed: '没有成功启动新的讲解准备任务，请稍后再试。',
} : {
  eyebrow: 'In My Guides', title: 'Guides being prepared',
  detail: 'These entries come from persisted download, rulebook-reading, or guide-preparation work. Refreshing, leaving, or switching entry points will not lose them.',
  rulebook: 'Rulebook', downloading: 'Downloading and verifying the source file', reading: 'Organizing rule text and original pages',
  preparing: 'The rulebook is readable while the guide is prepared', failed: 'This task stopped; follow the recovery guidance below',
  progress: 'Confirmed download progress', openRulebook: 'Read rulebook now', openSource: 'Open task entry',
  retryPreparation: 'Retry guide preparation', retryingPreparation: 'Restarting…',
  retryFailed: 'A new guide-preparation task could not be started. Please try again shortly.',
})
function preparationForPlan(plan: TeachingPlan) {
  return preparationRuns.value
    .filter(run => run.subjectId === plan.documentVersionId)
    .sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt))[0]
}
function preparationStillOwnsPlanStartup(plan: TeachingPlan) {
  const preparation = preparationForPlan(plan)
  return Boolean(preparation
    && preparation.state !== 'COMPLETED'
    && !progress.value[plan.id]?.run
    && !progress.value[plan.id]?.lesson)
}
function preparationCanStillStartTeaching(plan: TeachingPlan) {
  const preparation = preparationForPlan(plan)
  return Boolean(preparation
    && !playerJourneyRunIsTerminal(preparation.state)
    && !progress.value[plan.id]?.run
    && !progress.value[plan.id]?.lesson)
}
const plansReplacingPendingJourneys = computed(() => plans.value.filter((plan) => {
  const preparation = preparationForPlan(plan)
  return !preparation
    || preparation.state === 'COMPLETED'
    || Boolean(progress.value[plan.id]?.run || progress.value[plan.id]?.lesson)
}))
const visiblePlans = computed(() => plans.value.filter(plan => !preparationStillOwnsPlanStartup(plan)))
const pendingJourneys = computed(() => buildPendingGuideJourneys(
  plansReplacingPendingJourneys.value,
  guideImports.value,
  preparationRuns.value,
  guideDocuments.value,
  guideCatalog.value,
  guideUploadHandoffs.value,
))

function stateOf(planId: string) {
  const item = progress.value[planId]
  if (item?.run && !playerJourneyRunIsTerminal(item.run.run.state)) return 'GENERATING'
  if (item?.lesson?.status === 'COMPLETE') return 'COMPLETE'
  if (item?.lesson?.status === 'DRAFT_READY') return 'DRAFT_READY'
  if (item?.lesson?.status === 'INCOMPLETE') return 'INCOMPLETE'
  if (item?.run?.run.state === 'FAILED') return 'FAILED'
  if (item?.run && playerJourneyRunIsTerminal(item.run.run.state)) return 'NEEDS_ATTENTION'
  return 'PLANNED'
}

function planFailurePolicy(planId: string): PlayerJourneyFailurePolicy | null {
  const run = progress.value[planId]?.run?.run
  if (!run || run.state === 'COMPLETED' || !playerJourneyRunIsTerminal(run.state)) return null
  return typedFailurePolicy(run.lastErrorCode ?? run.state, 'GENERATE_LESSON', false)
}

function failureGuidance(policy: PlayerJourneyFailurePolicy) {
  const presentation = playerJourneyFailurePresentation(policy, locale.value)
  const separator = locale.value === 'en' ? '. ' : '。'
  return `${presentation.title}${separator}${presentation.detail}`
}

function planFailurePresentation(planId: string) {
  const policy = planFailurePolicy(planId)
  return policy ? playerJourneyFailurePresentation(policy, locale.value) : null
}

function planWorkStatus(planId: string) {
  const state = stateOf(planId)
  const lesson = progress.value[planId]?.lesson
  const failurePolicy = planFailurePolicy(planId)
  if (state === 'GENERATING') {
    const readable = hasReadableLesson(lesson)
    return guideWorkStatus(
      readable ? 'readable' : 'organizing',
      readable ? 1 : 0,
      locale.value,
    )
  }
  if (state === 'COMPLETE') {
    if (!hasReadableLesson(lesson)) {
      return playerWorkStatus('NEEDS_ACTION', {
        capability: 'rulebook', readiness: 'usable', terminality: 'terminal', outcome: 'needs-action',
      }, locale.value)
    }
    return playerWorkStatus('GUIDE_COMPLETE', {
      capability: 'guide', readiness: 'complete', terminality: 'terminal', outcome: 'none',
    }, locale.value)
  }
  if (state === 'DRAFT_READY') {
    return playerWorkStatus('GUIDE_READABLE', {
      capability: 'guide', readiness: 'usable', terminality: 'terminal', outcome: 'none',
    }, locale.value)
  }
  if (state === 'INCOMPLETE') {
    const readable = hasReadableLesson(lesson)
    if (readable && failurePolicy?.failureClassification === 'local-degradation') {
      return playerWorkStatus('GUIDE_READABLE', {
        capability: 'guide', readiness: 'usable', terminality: 'terminal', outcome: 'none',
      }, locale.value)
    }
    return playerWorkStatus('NEEDS_ACTION', {
      capability: readable ? 'guide' : 'rulebook',
      readiness: 'usable',
      terminality: 'terminal',
      outcome: 'needs-action',
    }, locale.value)
  }
  if (state === 'FAILED' || state === 'NEEDS_ATTENTION') {
    if (failurePolicy?.failureClassification === 'local-degradation') {
      return playerWorkStatus('RULEBOOK_READY', {
        capability: 'rulebook', readiness: 'usable', terminality: 'terminal', outcome: 'none',
      }, locale.value)
    }
    return playerWorkStatus('NEEDS_ACTION', {
      capability: 'rulebook', readiness: 'usable', terminality: 'terminal', outcome: 'needs-action',
    }, locale.value)
  }
  return playerWorkStatus('WAITING_FOR_PLAYER', {
    capability: 'rulebook', readiness: 'usable', terminality: 'waiting', outcome: 'none',
  }, locale.value)
}

function stateClass(planId: string) {
  const state = stateOf(planId)
  const failurePolicy = planFailurePolicy(planId)
  if (state === 'COMPLETE' || state === 'DRAFT_READY') {
    return 'bg-emerald-50 text-emerald-800'
  }
  if (state === 'GENERATING') return 'bg-indigo/10 text-indigo'
  if (failurePolicy?.failureClassification === 'local-degradation') return 'bg-emerald-50 text-emerald-800'
  if (failurePolicy?.failureClassification === 'retry-preserved') return 'bg-amber-50 text-amber-800'
  if (state === 'FAILED' || state === 'NEEDS_ATTENTION') return 'bg-red-50 text-red-800'
  return 'bg-amber-50 text-amber-800'
}

function displayPlanTitle(plan: TeachingPlan) {
  return playerFacingTitle(plan.gameTitle)
}

function continuationPriority(plan: TeachingPlan) {
  const item = progress.value[plan.id]
  if (item?.lesson?.status === 'COMPLETE') return 600
  if (item?.lesson?.status === 'DRAFT_READY' && item.run && !playerJourneyRunIsTerminal(item.run.run.state)) return 550
  if (item?.lesson?.status === 'DRAFT_READY') return 500
  if (item?.lesson?.status === 'INCOMPLETE') return 400
  if (item?.run && !playerJourneyRunIsTerminal(item.run.run.state)) return 300
  if (item?.run && playerJourneyRunIsTerminal(item.run.run.state)) return 100
  return 200
}

const planGroups = computed(() => groupPlansForReading(visiblePlans.value, continuationPriority))
const planGroupByPlanId = computed(() => {
  const groups = new Map<string, typeof planGroups.value[number]>()
  for (const group of planGroups.value) {
    for (const plan of group.plans) groups.set(plan.id, group)
  }
  return groups
})
const selectedPlans = computed(() => showingAllVersions.value ? visiblePlans.value : planGroups.value.map((group) => group.plan))
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
  const failurePolicy = planFailurePolicy(plan.id)
  if (state === 'GENERATING') {
    return t('lessons.progress.generating', { activity: activityText(plan, item!.run!.activities.at(-1)), elapsed: elapsedLabel(plan) })
  }
  if (state === 'DRAFT_READY') {
    const detail = t('lessons.progress.draftReady')
    return failurePolicy ? `${detail} ${failureGuidance(failurePolicy)}` : detail
  }
  if (state === 'INCOMPLETE') {
    const supported = item?.lesson?.sections.filter((section) => section.evidenceStatus === 'SUPPORTED').length ?? 0
    const detail = t('lessons.progress.incomplete', { supported })
    return failurePolicy ? `${detail} ${failureGuidance(failurePolicy)}` : detail
  }
  if ((state === 'FAILED' || state === 'NEEDS_ATTENTION') && failurePolicy) {
    return failureGuidance(failurePolicy)
  }
  if (state === 'COMPLETE') {
    const detail = t('lessons.progress.complete')
    return failurePolicy ? `${detail} ${failureGuidance(failurePolicy)}` : detail
  }
  return t('lessons.progress.planned')
}

function createdLabel(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return t('lessons.createdUnknown')
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

function pendingPhaseDetail(journey: (typeof pendingJourneys.value)[number]) {
  if (journey.state === 'failed' && journey.failureClassification) {
    return failureGuidance({
      errorCode: journey.errorCode ?? 'UNKNOWN',
      retryAction: journey.retryAction,
      failureClassification: journey.failureClassification,
      failureRecovery: journey.failureRecovery,
    })
  }
  return {
    DOWNLOADING: pendingCopy.value.downloading,
    READING_RULEBOOK: pendingCopy.value.reading,
    PREPARING_GUIDE: pendingCopy.value.preparing,
    FAILED: pendingCopy.value.failed,
  }[journey.phase]
}

function pendingWorkStatus(journey: (typeof pendingJourneys.value)[number]) {
  if (journey.phase === 'DOWNLOADING') {
    return playerWorkStatus('ACQUIRING_RULEBOOK', {
      capability: 'none', readiness: 'unavailable', terminality: 'active', outcome: 'none',
    }, locale.value)
  }
  if (journey.phase === 'READING_RULEBOOK') {
    return playerWorkStatus('READING_RULEBOOK', {
      capability: 'none', readiness: 'unavailable', terminality: 'active', outcome: 'none',
    }, locale.value)
  }
  if (journey.phase === 'PREPARING_GUIDE') {
    return playerWorkStatus('ORGANIZING_GUIDE', {
      capability: journey.canReadRulebook ? 'rulebook' : 'none',
      readiness: journey.canReadRulebook ? 'usable' : 'unavailable',
      terminality: 'active',
      outcome: 'none',
    }, locale.value)
  }
  if (journey.failureClassification === 'local-degradation') {
    return playerWorkStatus('RULEBOOK_READY', {
      capability: journey.canReadRulebook ? 'rulebook' : 'none',
      readiness: journey.canReadRulebook ? 'usable' : 'unavailable',
      terminality: 'terminal',
      outcome: 'none',
    }, locale.value)
  }
  return playerWorkStatus('NEEDS_ACTION', {
    capability: journey.canReadRulebook ? 'rulebook' : 'none',
    readiness: journey.canReadRulebook ? 'usable' : 'unavailable',
    terminality: 'terminal',
    outcome: 'needs-action',
  }, locale.value)
}

async function checkedFetch(path: string, options?: Parameters<typeof fetch>[1]) {
  const response = await fetch(path, { credentials: 'include', ...options })
  if (response.status === 401) {
    if (!loginRequired.value) {
      loginRequired.value = true
      notifyLoginRequired({ showReminder: false })
    }
    throw new Error(t('lessons.error.login'))
  }
  return response
}

async function optionalList<T>(path: string, signal: AbortSignal): Promise<T[]> {
  const response = await checkedFetch(path, { signal })
  if (response.status === 404) return []
  if (!response.ok) throw new Error(t('lessons.error.load'))
  const payload = await response.json() as unknown
  if (!Array.isArray(payload)) throw new Error(t('lessons.error.load'))
  return payload as T[]
}

async function handoffPreparationRuns(
  imports: PendingGuideImport[],
  uploads: PendingGuideUploadHandoff[],
  active: PendingGuidePreparationRun[],
  signal: AbortSignal,
) {
  const expectedSubjects = new Map<string, string>()
  for (const job of imports) {
    if (job.teachingPreparationRunId && job.documentVersionId) {
      expectedSubjects.set(job.teachingPreparationRunId, job.documentVersionId)
    }
  }
  for (const handoff of uploads) {
    if (handoff.preparationRunId) expectedSubjects.set(handoff.preparationRunId, handoff.documentVersionId)
  }
  const byId = new Map(active
    .filter(run => !expectedSubjects.has(run.id) || expectedSubjects.get(run.id) === run.subjectId)
    .map(run => [run.id, run]))
  const missingIds = [...expectedSubjects.keys()].filter(id => !byId.has(id))
  const snapshots = await Promise.all(missingIds.map(async (runId) => {
    try {
      const response = await checkedFetch(`/api/v1/assistant-runs/${encodeURIComponent(runId)}`, { signal })
      if (response.status === 404) return null
      if (!response.ok) throw new Error(t('lessons.error.load'))
      const details = await response.json() as { run?: PendingGuidePreparationRun }
      return details.run?.id === runId && details.run.subjectId === expectedSubjects.get(runId)
        ? details.run
        : null
    } catch {
      if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
      return null
    }
  }))
  for (const snapshot of snapshots) {
    if (snapshot) byId.set(snapshot.id, snapshot)
  }
  return [...byId.values()]
}

async function loadProgress(plan: TeachingPlan, listRequest = latestListRequest, refreshLesson = true) {
  const requestVersion = (requestVersions.get(plan.id) ?? 0) + 1
  requestVersions.set(plan.id, requestVersion)
  progressControllers.get(plan.id)?.abort()
  const controller = new AbortController()
  progressControllers.set(plan.id, controller)
  try {
    const previousRun = progress.value[plan.id]?.run
    const expectedRunId = knownRunIds.get(plan.id)
    const activityCursor = expectedRunId ? '' : teachingActivityCursor(previousRun ?? null)
    const runPath = expectedRunId
      ? `/api/v1/assistant-runs/${encodeURIComponent(expectedRunId)}`
      : `/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(plan.id)}${activityCursor}`
    const [runResponse, lessonResponse] = await Promise.all([
      checkedFetch(runPath, { signal: controller.signal }),
      refreshLesson
        ? checkedFetch(`/api/v1/teaching-plans/${plan.id}/illustrated-lessons/latest/summary`, { signal: controller.signal })
        : Promise.resolve<Response | null>(null),
    ])
    if (!isCurrentProgressRead(plan.id, listRequest, requestVersion, controller)) return
    if (!runResponse.ok && runResponse.status !== 404) throw new Error(t('lessons.error.runProgress'))
    if (lessonResponse && !lessonResponse.ok && lessonResponse.status !== 404) throw new Error(t('lessons.error.contentProgress'))
    const incomingRun = runResponse.ok ? await runResponse.json() as TeachingRunProgress : null
    const incomingLesson = lessonResponse?.ok ? await lessonResponse.json() as LessonProgressSummary : null
    if (!isCurrentProgressRead(plan.id, listRequest, requestVersion, controller)) return
    if (incomingRun && (incomingRun.run.subjectId !== plan.id
      || expectedRunId && incomingRun.run.id !== expectedRunId)) {
      throw new Error(t('lessons.error.runProgress'))
    }
    if (incomingLesson && incomingLesson.teachingPlanId !== plan.id) {
      throw new Error(t('lessons.error.contentProgress'))
    }
    const run = mergeTeachingRunProgress(previousRun ?? null, incomingRun)
    const lesson = mergeLessonProgress(progress.value[plan.id]?.lesson ?? null, incomingLesson)
    progress.value = {
      ...progress.value,
      [plan.id]: {
        run,
        lesson,
      },
    }
    if (expectedRunId && run?.run.id === expectedRunId && playerJourneyRunIsTerminal(run.run.state)) {
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
    if (!isCurrentProgressRead(plan.id, listRequest, requestVersion, controller) || controller.signal.aborted) return
    progressErrors.value = {
      ...progressErrors.value,
      [plan.id]: error instanceof Error ? error.message : t('lessons.error.latestProgress'),
    }
    throw error
  } finally {
    controller.abort()
    if (progressControllers.get(plan.id) === controller) progressControllers.delete(plan.id)
  }
}

function isCurrentProgressRead(
  planId: string,
  listRequest: number,
  requestVersion: number,
  controller: AbortController,
) {
  return !disposed
    && listRequest === latestListRequest
    && requestVersions.get(planId) === requestVersion
    && progressControllers.get(planId) === controller
    && plans.value.some(plan => plan.id === planId)
}

function cancelProgressReads() {
  for (const controller of progressControllers.values()) controller.abort()
  progressControllers.clear()
  for (const planId of requestVersions.keys()) {
    requestVersions.set(planId, (requestVersions.get(planId) ?? 0) + 1)
  }
}

function plansNeedingRefresh() {
  return plans.value.filter((plan) => knownRunIds.has(plan.id)
    || stateOf(plan.id) === 'GENERATING'
    || preparationCanStillStartTeaching(plan)
    || Boolean(progressErrors.value[plan.id]))
}

function clearProgressTimer() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = undefined
}

function scheduleProgressRefresh(delay = 4_000) {
  clearProgressTimer()
  if (disposed || document.visibilityState === 'hidden' || plansNeedingRefresh().length === 0) return
  pollTimer = setTimeout(() => {
    pollTimer = undefined
    void refreshProgress(plansNeedingRefresh())
  }, delay)
}

async function refreshProgress(targetPlans = plans.value, listRequest = latestListRequest, refreshLessons = true) {
  if (disposed || listRequest !== latestListRequest) return
  await Promise.allSettled(targetPlans.map(plan => loadProgress(plan, listRequest, refreshLessons)))
  if (disposed || listRequest !== latestListRequest) return
  scheduleProgressRefresh()
}

function clearJourneyTimer() {
  if (journeyTimer) clearTimeout(journeyTimer)
  journeyTimer = undefined
}

function scheduleJourneyRefresh() {
  clearJourneyTimer()
  if (disposed || !pendingJourneys.value.some(journey => journey.state === 'active')) return
  journeyTimer = setTimeout(() => { void loadPlans(true) }, 4_000)
}

function handleVisibilityChange() {
  if (document.visibilityState === 'hidden') {
    cancelProgressReads()
    clearProgressTimer()
    clearJourneyTimer()
    return
  }
  void loadPlans(true)
}

async function loadPlans(background = false) {
  const request = ++latestListRequest
  activeListController?.abort()
  cancelProgressReads()
  clearProgressTimer()
  clearJourneyTimer()
  const controller = new AbortController()
  activeListController = controller
  if (!background) {
    loading.value = true
    errorMessage.value = ''
  }
  try {
    const ancillary = Promise.all([
      optionalList<PendingGuideImport>('/api/v1/documents/official-imports', controller.signal),
      optionalList<PendingGuideUploadHandoff>('/api/v1/documents/upload-teaching-handoffs', controller.signal),
      optionalList<PendingGuidePreparationRun>('/api/v1/assistant-runs/active?mode=TEACHING_PREPARATION', controller.signal),
      optionalList<PendingGuideDocument>('/api/v1/documents', controller.signal),
      optionalList<PendingGuideCatalogGame>('/api/v1/games', controller.signal),
    ]).then(value => ({ value }), error => ({ error }))
    const response = await checkedFetch('/api/v1/teaching-plans', { signal: controller.signal })
    if (!isCurrentListRequest(request, controller)) return
    if (!response.ok) throw new Error(t('lessons.error.load'))
    const receivedPlans = await response.json() as unknown
    if (!Array.isArray(receivedPlans)) throw new Error(t('lessons.error.load'))
    const nextPlans = receivedPlans as TeachingPlan[]
    plans.value = nextPlans
    const currentPlanIds = new Set(nextPlans.map(plan => plan.id))
    progress.value = Object.fromEntries(nextPlans.map(plan => [plan.id, {
      run: progress.value[plan.id]?.run ?? null,
      lesson: mergeLessonProgress(progress.value[plan.id]?.lesson ?? null, plan.lesson ?? null),
    }]))
    progressErrors.value = Object.fromEntries(Object.entries(progressErrors.value).filter(([planId]) => currentPlanIds.has(planId)))
    launchErrors.value = Object.fromEntries(Object.entries(launchErrors.value).filter(([planId]) => currentPlanIds.has(planId)))
    for (const planId of [...knownRunIds.keys()]) if (!currentPlanIds.has(planId)) knownRunIds.delete(planId)
    if (startedPlanId.value && startedRunId.value && currentPlanIds.has(startedPlanId.value)) {
      knownRunIds.set(startedPlanId.value, startedRunId.value)
    }
    if (!background) loading.value = false
    // During a rolling release an older API may not include the batched lesson field yet.
    // Keep that compatibility read lightweight by targeting the summary endpoint only.
    void refreshProgress(nextPlans, request, nextPlans.some(plan => plan.lesson === undefined))
    const ancillaryResult = await ancillary
    if (!isCurrentListRequest(request, controller)) return
    if ('value' in ancillaryResult) {
      const [imports, uploads, activePreparation, documents, catalog] = ancillaryResult.value
      const receivedPreparationRuns = await handoffPreparationRuns(
        imports, uploads, activePreparation, controller.signal,
      )
      if (!isCurrentListRequest(request, controller)) return
      guideImports.value = imports
      guideUploadHandoffs.value = uploads
      preparationRuns.value = receivedPreparationRuns
      guideDocuments.value = documents
      guideCatalog.value = catalog
    }
    scheduleJourneyRefresh()
  } catch (error) {
    if (!isCurrentListRequest(request, controller) || controller.signal.aborted) return
    if (!background) errorMessage.value = error instanceof Error ? error.message : t('lessons.error.loadShort')
  } finally {
    if (isCurrentListRequest(request, controller)) {
      activeListController = null
      if (!background) loading.value = false
      scheduleJourneyRefresh()
    }
    controller.abort()
  }
}

function isCurrentListRequest(request: number, controller: AbortController) {
  return !disposed && request === latestListRequest && activeListController === controller
}

async function launch(planId: string) {
  if (launchingPlanId.value || !canLaunchPlan(planId)) return
  const plan = plans.value.find((candidate) => candidate.id === planId)
  if (!plan) return
  launchingPlanId.value = planId
  const nextErrors = { ...launchErrors.value }
  delete nextErrors[planId]
  launchErrors.value = nextErrors
  try {
    const csrfResponse = await checkedFetch('/api/auth/csrf')
    if (!csrfResponse.ok) throw new Error(t('lessons.error.secureSession'))
    const csrf = await csrfResponse.json() as CsrfResponse
    const response = await checkedFetch(`/api/v1/teaching-plans/${encodeURIComponent(planId)}/illustrated-lessons`, {
      method: 'POST',
      headers: { [csrf.headerName]: csrf.token },
    })
    if (!response.ok) throw new Error(t('lessons.error.launch'))
    const launch = await response.json() as TeachingLaunch
    if (typeof launch.assistantRunId !== 'string' || !launch.assistantRunId.trim()) {
      throw new Error(t('lessons.error.launch'))
    }
    knownRunIds.set(planId, launch.assistantRunId)
    terminalSettlingReads.delete(planId)
    notifyTeachingLaunched({ planId, runId: launch.assistantRunId, gameTitle: displayPlanTitle(plan) })
    localStorage.setItem('rulepilot:last-plan-id', planId)
    await loadProgress(plan, latestListRequest).catch(() => undefined)
    scheduleProgressRefresh(1000)
  } catch (error) {
    launchErrors.value = {
      ...launchErrors.value,
      [planId]: error instanceof Error ? error.message : t('lessons.error.launchShort'),
    }
  } finally {
    launchingPlanId.value = ''
  }
}

function canLaunchPlan(planId: string) {
  if (stateOf(planId) === 'GENERATING') return false
  const failurePolicy = planFailurePolicy(planId)
  if (hasReadableLesson(progress.value[planId]?.lesson)) {
    return failurePolicy?.retryAction === 'GENERATE_LESSON'
  }
  return !failurePolicy || failurePolicy.retryAction === 'GENERATE_LESSON'
}

function planLaunchLabel(planId: string) {
  if (launchingPlanId.value === planId) return t('lessons.action.launching')
  return planFailurePresentation(planId)?.actionLabel ?? t('lessons.action.generate')
}

async function retryPendingJourney(journey: (typeof pendingJourneys.value)[number]) {
  if (retryingJourneyId.value || journey.retryAction !== 'PREPARE_TEACHING' || !journey.documentVersionId) return
  retryingJourneyId.value = journey.id
  const nextErrors = { ...journeyRetryErrors.value }
  delete nextErrors[journey.id]
  journeyRetryErrors.value = nextErrors
  try {
    const csrfResponse = await checkedFetch('/api/auth/csrf')
    if (!csrfResponse.ok) throw new Error(t('lessons.error.secureSession'))
    const csrf = await csrfResponse.json() as CsrfResponse
    const durableRetryPath = journey.importJobId
      ? `/api/v1/documents/official-imports/${encodeURIComponent(journey.importJobId)}/teaching-retry`
      : journey.uploadHandoffId
        ? `/api/v1/documents/upload-teaching-handoffs/${encodeURIComponent(journey.uploadHandoffId)}/retry`
        : null
    const response = await checkedFetch(durableRetryPath
      ?? `/api/v1/document-versions/${encodeURIComponent(journey.documentVersionId)}/teaching-plans`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify(durableRetryPath
        ? { expectedPreparationRunId: journey.preparationRunId }
        : { learningGoal: null }),
    })
    if (!response.ok) throw new Error(pendingCopy.value.retryFailed)
    if (durableRetryPath) {
      await response.json()
      notifyBackgroundWorkChanged()
    } else {
      const launch = await response.json() as { assistantRunId: string; state: string }
      preparationRuns.value = [
        ...preparationRuns.value.filter(run => run.id !== launch.assistantRunId),
        {
          id: launch.assistantRunId,
          subjectId: journey.documentVersionId,
          state: launch.state,
          updatedAt: new Date().toISOString(),
          lastErrorCode: null,
        },
      ]
    }
    scheduleJourneyRefresh()
    await loadPlans(true)
  } catch (error) {
    journeyRetryErrors.value = {
      ...journeyRetryErrors.value,
      [journey.id]: error instanceof Error ? error.message : pendingCopy.value.retryFailed,
    }
  } finally {
    retryingJourneyId.value = ''
  }
}

function requestDeletePlan(plan: TeachingPlan) {
  if (destructivePending.value) return
  destructiveAction.value = { kind: 'delete-plan', plan }
  destructiveError.value = ''
  restoreAfterDestructiveSuccess.value = false
}

function requestDeleteFailedPreparation(journey: PendingGuideJourney) {
  if (destructivePending.value || journey.state !== 'failed') return
  if (!journey.importJobId && !journey.uploadHandoffId) return
  destructiveAction.value = { kind: 'delete-failed-preparation', journey }
  destructiveError.value = ''
  restoreAfterDestructiveSuccess.value = false
}

function cancelDestructiveAction() {
  if (destructivePending.value) return
  destructiveAction.value = null
  destructiveError.value = ''
  restoreAfterDestructiveSuccess.value = false
}

function destructiveRestoreTarget() {
  if (!restoreAfterDestructiveSuccess.value) return null
  restoreAfterDestructiveSuccess.value = false
  return pageHeading.value
}

async function confirmDeletePlan(plan: TeachingPlan) {
  if (destructivePending.value) return
  deletingPlanId.value = plan.id
  destructiveError.value = ''
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
    restoreAfterDestructiveSuccess.value = true
    destructiveAction.value = null
    cleanupMessage.value = t('lessons.delete.done')
  } catch (error) {
    destructiveError.value = error instanceof Error ? error.message : t('lessons.delete.failedShort')
  } finally {
    deletingPlanId.value = ''
  }
}

async function confirmDeleteFailedPreparation(journey: PendingGuideJourney) {
  if (destructivePending.value) return
  const sourcePath = journey.importJobId
    ? `official-imports/${encodeURIComponent(journey.importJobId)}`
    : journey.uploadHandoffId
      ? `uploads/${encodeURIComponent(journey.uploadHandoffId)}`
      : ''
  if (!sourcePath) return
  deletingJourneyId.value = journey.id
  destructiveError.value = ''
  try {
    const csrfResponse = await checkedFetch('/api/auth/csrf')
    if (!csrfResponse.ok) throw new Error(t('lessons.error.secureSession'))
    const csrf = await csrfResponse.json() as CsrfResponse
    const response = await checkedFetch(`/api/v1/teaching-preparation-failures/${sourcePath}`, {
      method: 'DELETE', headers: { [csrf.headerName]: csrf.token },
    })
    if (!response.ok) throw new Error(locale.value === 'zh-CN' ? '没有成功删除这次失败记录。' : 'The failed attempt could not be deleted.')
    if (journey.importJobId) {
      guideImports.value = guideImports.value.map(job => job.id === journey.importJobId
        ? {
            ...job,
            teachingHandoffState: 'NOT_REQUESTED',
            teachingPreparationRunId: null,
            teachingErrorCode: null,
          }
        : job)
    }
    if (journey.uploadHandoffId) {
      guideUploadHandoffs.value = guideUploadHandoffs.value
        .filter(handoff => handoff.id !== journey.uploadHandoffId)
    }
    if (journey.preparationRunId) {
      preparationRuns.value = preparationRuns.value.filter(run => run.id !== journey.preparationRunId)
    }
    const nextErrors = { ...journeyRetryErrors.value }
    delete nextErrors[journey.id]
    journeyRetryErrors.value = nextErrors
    restoreAfterDestructiveSuccess.value = true
    destructiveAction.value = null
    cleanupMessage.value = locale.value === 'zh-CN'
      ? '失败的讲解准备记录已删除，规则书仍然保留。'
      : 'The failed guide attempt was deleted and its rulebook was kept.'
    notifyBackgroundWorkChanged(journey.importJobId
      ? { dismissedImportIds: [journey.importJobId] }
      : { dismissedUploadedHandoffIds: [journey.uploadHandoffId!] })
  } catch (error) {
    destructiveError.value = error instanceof Error
      ? error.message
      : locale.value === 'zh-CN' ? '没有成功删除这次失败记录。' : 'The failed attempt could not be deleted.'
  } finally {
    deletingJourneyId.value = ''
  }
}

async function requestCleanDuplicates() {
  if (destructivePending.value) return
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
    destructiveAction.value = { kind: 'cleanup', duplicateCount: preview.duplicateCount }
    destructiveError.value = ''
    restoreAfterDestructiveSuccess.value = false
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('lessons.cleanup.failedShort')
  } finally {
    cleanupLoading.value = false
  }
}

async function confirmCleanDuplicates() {
  if (destructivePending.value) return
  cleanupLoading.value = true
  destructiveError.value = ''
  try {
    const csrfResponse = await checkedFetch('/api/auth/csrf')
    if (!csrfResponse.ok) throw new Error(t('lessons.error.secureSession'))
    const csrf = await csrfResponse.json() as CsrfResponse
    const response = await checkedFetch('/api/v1/teaching-plans/cleanup-duplicates', {
      method: 'POST', headers: { [csrf.headerName]: csrf.token },
    })
    if (!response.ok) throw new Error(t('lessons.cleanup.failed'))
    const result = await response.json() as { deletedCount: number }
    await loadPlans()
    restoreAfterDestructiveSuccess.value = true
    destructiveAction.value = null
    cleanupMessage.value = result.deletedCount ? t('lessons.cleanup.done', { count: result.deletedCount }) : t('lessons.cleanup.nothing')
  } catch (error) {
    destructiveError.value = error instanceof Error ? error.message : t('lessons.cleanup.failedShort')
  } finally {
    cleanupLoading.value = false
  }
}

function confirmDestructiveAction() {
  const action = destructiveAction.value
  if (!action) return
  if (action.kind === 'delete-plan') void confirmDeletePlan(action.plan)
  else if (action.kind === 'delete-failed-preparation') void confirmDeleteFailedPreparation(action.journey)
  else void confirmCleanDuplicates()
}

function clearPlanSessionState() {
  plans.value = []
  guideImports.value = []
  guideUploadHandoffs.value = []
  preparationRuns.value = []
  guideDocuments.value = []
  guideCatalog.value = []
  progress.value = {}
  progressErrors.value = {}
  launchErrors.value = {}
  knownRunIds.clear()
  requestVersions.clear()
  terminalSettlingReads.clear()
  showingAllVersions.value = false
  planFilter.value = 'READABLE'
}

function enterSignedOutState() {
  loginRequired.value = true
  loading.value = false
  errorMessage.value = ''
  latestListRequest++
  activeListController?.abort()
  activeListController = null
  cancelProgressReads()
  clearProgressTimer()
  clearJourneyTimer()
  clearPlanSessionState()
}

function updateSessionIdentity(username: string) {
  if (disposed) return
  const normalizedUsername = username.trim()
  if (!shellIdentityResolved) {
    shellIdentityResolved = true
    shellUsername = normalizedUsername
    if (!normalizedUsername) enterSignedOutState()
    return
  }
  if (normalizedUsername === shellUsername) return
  shellUsername = normalizedUsername
  if (!normalizedUsername) {
    enterSignedOutState()
    return
  }
  clearPlanSessionState()
  loginRequired.value = false
  void loadPlans()
}

onMounted(() => {
  disposed = false
  document.addEventListener('visibilitychange', handleVisibilityChange)
  clockTimer = setInterval(() => { now.value = Date.now() }, 1000)
  void loadPlans()
})
onBeforeUnmount(() => {
  disposed = true
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  latestListRequest++
  activeListController?.abort()
  activeListController = null
  cancelProgressReads()
  clearProgressTimer()
  clearJourneyTimer()
  if (clockTimer) clearInterval(clockTimer)
})
</script>

<template>
  <AppShell :login-action-owned="loginRequired" @session-identity="updateSessionIdentity">
    <section class="tabletop-page max-w-6xl">
      <div class="mt-4 flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
        <div>
          <h1 ref="pageHeading" tabindex="-1" class="tabletop-title outline-none">{{ loginRequired ? signedOutCopy.pageTitle : t('lessons.title') }}</h1>
          <p class="mt-4 max-w-2xl leading-7 text-ink/55">{{ loginRequired ? signedOutCopy.pageDescription : t('lessons.description') }}</p>
        </div>
        <div class="flex flex-wrap gap-2">
          <button v-if="!loginRequired && visiblePlans.length > 1" type="button" :disabled="cleanupLoading || Boolean(deletingPlanId)" class="inline-flex min-h-11 items-center justify-center rounded-lg border border-ink/15 px-4 text-sm font-semibold hover:border-copper/50 disabled:opacity-40" @click="requestCleanDuplicates">{{ cleanupLoading && !destructiveAction ? t('lessons.cleanup.loading') : t('lessons.cleanup.action') }}</button>
          <RouterLink v-if="!loginRequired" :to="{ name: 'teach' }" class="inline-flex min-h-11 items-center justify-center rounded-lg bg-copper px-4 text-sm font-semibold text-on-accent">{{ t('lessons.upload') }}</RouterLink>
        </div>
      </div>

      <p v-if="!loginRequired && startedPlanId" class="mt-6 rounded-lg bg-indigo/5 px-4 py-3 text-sm text-indigo" role="status">{{ t('lessons.started') }}</p>
      <p v-if="!loginRequired && cleanupMessage" class="mt-6 rounded-lg bg-emerald-50 px-4 py-3 text-sm text-emerald-800" role="status">{{ cleanupMessage }}</p>
      <div v-if="visiblePlans.length" class="mt-6 flex flex-wrap items-center gap-x-4 gap-y-2 text-sm text-ink/45">
        <p>{{ t('lessons.summary', { versions: visiblePlans.length, rulebooks: planGroups.length, readable: readableGroupCount }) }}</p>
        <button v-if="visiblePlans.length > planGroups.length" type="button" class="font-semibold text-indigo underline decoration-indigo-soft underline-offset-4 " @click="showingAllVersions ? hideAllVersions() : showAllVersions()">{{ showingAllVersions ? t('lessons.history.hide') : t('lessons.history.show', { count: visiblePlans.length }) }}</button>
      </div>

      <div v-if="visiblePlans.length" class="mt-5 flex flex-wrap gap-2" role="group" :aria-label="t('lessons.filter.aria')">
        <button type="button" class="min-h-10 rounded-full px-4 text-sm font-semibold transition" :class="selectedPlanFilter === 'READABLE' ? 'bg-ink text-paper' : 'border border-ink/15 text-ink/65 hover:border-ink/35'" :aria-pressed="selectedPlanFilter === 'READABLE'" @click="planFilter = 'READABLE'">{{ t('lessons.filter.readable', { count: readableGroupCount }) }}</button>
        <button type="button" class="min-h-10 rounded-full px-4 text-sm font-semibold transition" :class="selectedPlanFilter === 'PENDING' ? 'bg-ink text-paper' : 'border border-ink/15 text-ink/65 hover:border-ink/35'" :aria-pressed="selectedPlanFilter === 'PENDING'" @click="planFilter = 'PENDING'">{{ t('lessons.filter.pending', { count: pendingGroupCount }) }}</button>
        <button type="button" class="min-h-10 rounded-full px-4 text-sm font-semibold transition" :class="selectedPlanFilter === 'ALL' ? 'bg-ink text-paper' : 'border border-ink/15 text-ink/65 hover:border-ink/35'" :aria-pressed="selectedPlanFilter === 'ALL'" @click="planFilter = 'ALL'">{{ t('lessons.filter.all', { count: planGroups.length }) }}</button>
      </div>

      <section v-if="!loginRequired && !loading && !errorMessage && pendingJourneys.length" class="mt-8 rounded-2xl border border-indigo/20 bg-indigo/[0.035] p-5 sm:p-6" aria-live="polite" data-testid="pending-guide-journeys">
        <p class="text-xs font-bold uppercase tracking-[0.12em] text-indigo">{{ pendingCopy.eyebrow }}</p>
        <h2 class="mt-1 font-display text-2xl font-semibold">{{ pendingCopy.title }}</h2>
        <p class="mt-2 max-w-3xl text-sm leading-6 text-ink/55">{{ pendingCopy.detail }}</p>
        <ol class="mt-5 grid gap-4 md:grid-cols-2">
          <li v-for="journey in pendingJourneys" :key="journey.id" data-testid="pending-guide-journey" :data-failure-classification="journey.failureClassification ?? undefined" :data-failure-recovery="journey.failureRecovery ?? undefined" class="rounded-xl border bg-paper p-4" :class="journey.state === 'failed' ? 'border-red-200' : 'border-indigo/15'">
            <div class="flex items-start justify-between gap-4">
              <div class="min-w-0">
                <h3 class="truncate font-display text-xl font-semibold">{{ journey.title }}</h3>
                <p v-if="journey.rulebookTitle" class="mt-1 truncate text-xs text-ink/45">{{ pendingCopy.rulebook }}：{{ journey.rulebookTitle }}</p>
                <PlayerWorkStatusText
                  :status="pendingWorkStatus(journey)"
                  class="mt-3 text-sm font-semibold"
                  :class="journey.state === 'failed' ? 'text-red-700' : 'text-indigo'"
                />
                <p class="mt-1 text-xs leading-5 text-ink/50">{{ pendingPhaseDetail(journey) }}</p>
              </div>
              <span v-if="journey.state === 'active'" class="mt-1 size-3 shrink-0 animate-pulse rounded-full bg-indigo" aria-hidden="true" />
            </div>
            <div v-if="journey.progress !== null" class="mt-4">
              <div class="flex justify-between text-xs text-ink/45"><span>{{ pendingCopy.progress }}</span><span class="font-mono">{{ journey.progress }}%</span></div>
              <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-indigo/10"><div class="h-full rounded-full bg-indigo" :style="{ width: `${journey.progress}%` }" /></div>
            </div>
            <div class="mt-4 flex flex-wrap gap-4 text-sm font-semibold text-indigo">
              <button v-if="journey.retryAction === 'PREPARE_TEACHING'" type="button" :disabled="Boolean(retryingJourneyId)" class="inline-flex min-h-10 items-center rounded-lg bg-indigo px-4 text-white disabled:opacity-40" @click="retryPendingJourney(journey)">{{ retryingJourneyId === journey.id ? pendingCopy.retryingPreparation : pendingCopy.retryPreparation }}</button>
              <button v-if="journey.state === 'failed' && (journey.importJobId || journey.uploadHandoffId)" type="button" data-testid="delete-failed-guide-attempt" :disabled="destructivePending" class="inline-flex min-h-10 items-center rounded-lg px-2 text-ink/45 hover:bg-red-50 hover:text-red-700 disabled:opacity-40" @click="requestDeleteFailedPreparation(journey)">{{ deletingJourneyId === journey.id ? (locale === 'zh-CN' ? '正在删除…' : 'Deleting…') : (locale === 'zh-CN' ? '删除失败记录' : 'Delete failed attempt') }}</button>
              <RouterLink v-if="journey.documentVersionId && journey.canReadRulebook" :to="{ name: 'rulebook-reader', params: { versionId: journey.documentVersionId } }" class="inline-flex min-h-10 items-center underline">{{ pendingCopy.openRulebook }}</RouterLink>
              <RouterLink v-if="journey.importJobId" :to="{ name: 'teach', query: { importJob: journey.importJobId } }" class="inline-flex min-h-10 items-center underline">{{ pendingCopy.openSource }}</RouterLink>
              <RouterLink v-else-if="journey.state === 'failed'" :to="{ name: 'teach' }" class="inline-flex min-h-10 items-center underline">{{ pendingCopy.openSource }}</RouterLink>
            </div>
            <p v-if="journeyRetryErrors[journey.id]" class="mt-3 rounded-lg bg-red-50 px-3 py-2 text-xs leading-5 text-red-800" role="alert">{{ journeyRetryErrors[journey.id] }}</p>
          </li>
        </ol>
      </section>

      <section v-if="loginRequired" class="mt-8 rounded-2xl border border-indigo/20 bg-paper p-6 sm:p-8" data-testid="signed-out-guides-gate" aria-labelledby="signed-out-guides-title">
        <h2 id="signed-out-guides-title" class="font-display text-2xl font-semibold">{{ signedOutCopy.title }}</h2>
        <p class="mt-3 max-w-2xl text-sm leading-6 text-ink/60">{{ signedOutCopy.description }}</p>
        <RouterLink :to="loginTarget" class="mt-5 inline-flex min-h-11 items-center justify-center rounded-lg bg-indigo px-5 font-semibold text-white">{{ signedOutCopy.action }}</RouterLink>
      </section>

      <div v-else-if="loading" class="mt-8 rounded-xl border border-ink/10 bg-paper p-8 text-ink/50" role="status">{{ t('lessons.loading') }}</div>

      <div v-else-if="errorMessage" class="mt-10 rounded-3xl border border-red-200 bg-red-50 p-6 text-red-800" role="alert">
        <p>{{ errorMessage }}</p>
        <button class="mt-4 text-sm font-semibold underline underline-offset-4" @click="loadPlans()">{{ t('lessons.reload') }}</button>
      </div>

      <div v-else-if="visiblePlans.length === 0 && pendingJourneys.length === 0" class="mt-8 rounded-xl border border-dashed border-ink/20 px-6 py-14 text-center">
        <h2 class="font-display text-2xl font-semibold">{{ t('lessons.empty.title') }}</h2>
        <p class="mx-auto mt-3 max-w-lg leading-7 text-ink/55">{{ t('lessons.empty.description') }}</p>
        <RouterLink :to="{ name: 'teach' }" class="mt-7 inline-flex rounded-lg bg-copper px-5 py-3 font-semibold text-on-accent">{{ t('lessons.empty.action') }}</RouterLink>
      </div>

      <div v-else-if="visiblePlans.length > 0 && displayedPlans.length === 0" class="mt-8 rounded-xl border border-dashed border-ink/20 px-6 py-12 text-center">
        <h2 class="font-display text-2xl font-semibold">{{ t('lessons.noReadable.title') }}</h2>
        <p class="mx-auto mt-3 max-w-lg leading-7 text-ink/55">{{ t('lessons.noReadable.description') }}</p>
        <button type="button" class="mt-6 text-sm font-semibold text-indigo underline underline-offset-4" @click="planFilter = 'PENDING'">{{ t('lessons.noReadable.action') }}</button>
      </div>

      <ol v-else-if="displayedPlans.length" class="score-track mt-10 grid gap-5 md:grid-cols-2">
        <li v-for="plan in displayedPlans" :key="plan.id" :data-failure-classification="planFailurePolicy(plan.id)?.failureClassification ?? undefined" :data-failure-recovery="planFailurePolicy(plan.id)?.failureRecovery ?? undefined" class="tabletop-panel player-board relative overflow-hidden p-6" :class="plan.id === startedPlanId ? 'ring-2 ring-copper/30' : ''">
          <div class="flex items-start justify-between gap-4">
            <div class="flex min-w-0 items-start gap-3">
              <span class="score-token shrink-0" aria-hidden="true" />
              <div class="min-w-0">
                <p class="text-xs font-medium text-ink/40">{{ createdLabel(plan.createdAt) }}</p>
                <h2 class="mt-1 truncate font-display text-2xl font-semibold">{{ displayPlanTitle(plan) }}</h2>
              </div>
            </div>
            <PlayerWorkStatusText
              :status="planWorkStatus(plan.id)"
              as="span"
              :class="stateClass(plan.id)"
              class="rounded-full px-3 py-1.5 text-xs font-semibold"
            />
          </div>
          <div v-if="stateOf(plan.id) === 'GENERATING'" class="mt-5 rounded-xl border border-indigo/15 bg-indigo/5 p-4" aria-live="polite" aria-atomic="true">
            <div class="flex items-start justify-between gap-4">
              <div>
                <p class="text-sm font-semibold text-indigo">{{ activityText(plan, currentActivity(plan)) }}</p>
                <p class="mt-1 text-xs leading-5 text-ink/50">{{ t('lessons.live.activityHint') }}</p>
              </div>
              <span class="shrink-0 font-mono text-sm font-semibold text-indigo">{{ elapsedLabel(plan) }}</span>
            </div>
            <div class="mt-4 h-2 overflow-hidden rounded-full bg-indigo/10" role="progressbar" :aria-valuemin="0" :aria-valuemax="plan.sections.length" :aria-valuenow="processedChapterCount(plan)" :aria-label="t('lessons.live.progressAria', { processed: processedChapterCount(plan), total: plan.sections.length })">
              <div class="h-full rounded-full bg-indigo transition-[width] duration-500" :style="{ width: chapterProgressWidth(plan) }" />
            </div>
            <div class="mt-2 text-xs text-ink/55">
              <span>{{ t('lessons.live.processed', { processed: processedChapterCount(plan), total: plan.sections.length, supported: supportedChapterCount(plan) }) }}</span>
            </div>
            <p class="mt-3 text-xs leading-5 text-ink/50">{{ remainingTimeText(plan) }} {{ t('lessons.live.background') }}</p>
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
          <div class="mt-6 flex flex-wrap items-center justify-between gap-3">
            <span v-if="plan.id === rememberedPlanId" class="text-xs font-semibold text-indigo">{{ t('lessons.lastOpened') }}</span>
            <span v-else class="text-xs text-ink/35">{{ t('lessons.chapterCount', { count: plan.sections.length }) }}</span>
            <div class="flex flex-wrap items-center justify-end gap-2">
              <RouterLink v-if="hasReadableLesson(progress[plan.id]?.lesson)" :to="{ name: 'lesson', params: { planId: plan.id } }" class="rounded-lg bg-indigo px-4 py-2.5 text-sm font-semibold text-white">{{ stateOf(plan.id) === 'GENERATING' ? t('lessons.action.readPublished') : stateOf(plan.id) === 'DRAFT_READY' || stateOf(plan.id) === 'INCOMPLETE' ? t('lessons.action.readAndComplete') : t('lessons.action.open') }}</RouterLink>
              <button v-if="canLaunchPlan(plan.id)" type="button" :disabled="Boolean(launchingPlanId) || Boolean(deletingPlanId)" :aria-busy="launchingPlanId === plan.id" class="rounded-lg bg-indigo px-4 py-2.5 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-40" @click="launch(plan.id)">{{ planLaunchLabel(plan.id) }}</button>
              <span v-else-if="stateOf(plan.id) === 'GENERATING'" class="inline-flex items-center gap-2 text-sm font-semibold text-indigo"><span class="size-3 animate-spin rounded-full border-2 border-indigo/20 border-t-indigo" />{{ t('lessons.action.background') }}</span>
              <button type="button" :disabled="Boolean(launchingPlanId) || Boolean(deletingPlanId) || cleanupLoading" class="min-h-10 rounded-lg px-2 text-sm font-semibold text-ink/40 hover:bg-red-50 hover:text-red-700 disabled:opacity-40" @click="requestDeletePlan(plan)">{{ deletingPlanId === plan.id ? t('lessons.action.deleting') : t('lessons.action.delete') }}</button>
            </div>
          </div>
          <p v-if="launchErrors[plan.id]" data-testid="lesson-launch-error" class="mt-3 rounded-lg bg-red-50 px-3 py-2 text-xs leading-5 text-red-800" role="alert">{{ launchErrors[plan.id] }}</p>
        </li>
      </ol>

      <DestructiveActionDialog
        :open="Boolean(destructiveAction)"
        :pending="destructivePending"
        :error="destructiveError"
        :title="destructiveDialog.title"
        :description="destructiveDialog.description"
        :cancel-label="destructiveDialog.cancel"
        :confirm-label="destructiveDialog.confirm"
        :pending-label="destructiveDialog.pending"
        :retry-label="destructiveDialog.retry"
        :restore-focus="destructiveRestoreTarget"
        @cancel="cancelDestructiveAction"
        @confirm="confirmDestructiveAction"
      />
    </section>
  </AppShell>
</template>
