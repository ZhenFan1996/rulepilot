<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import CatalogGameAttribution from '@/components/CatalogGameAttribution.vue'
import LessonChapterList from '@/components/LessonChapterList.vue'
import { notifyLoginRequired } from '@/lib/authSession'
import LessonGenerationStatus from '@/components/LessonGenerationStatus.vue'
import LessonGuideHero from '@/components/LessonGuideHero.vue'
import LessonModeNav from '@/components/LessonModeNav.vue'
import LessonOfflineKnowledgePanel from '@/components/LessonOfflineKnowledgePanel.vue'
import LessonReaderStateSurface from '@/components/LessonReaderStateSurface.vue'
import type { CsrfResponse } from '@/composables/useLessonAnswers'
import { useLessonSupportingContent } from '@/composables/useLessonSupportingContent'
import { useLessonLocalization } from '@/composables/useLessonLocalization'
import { useLessonGenerationPresentation } from '@/composables/useLessonGenerationPresentation'
import { useConditionalPolling } from '@/composables/useConditionalPolling'
import { useLessonComprehensionFeedback } from '@/composables/useLessonComprehensionFeedback'
import { useLessonReaderProgress } from '@/composables/useLessonReaderProgress'
import { acceptProgressiveLesson } from '@/lib/liveLesson'
import { loadOfflineKnowledge, type OfflineKnowledgeEntry } from '@/lib/offlineKnowledge'
import { playerWorkStatus, type PlayerWorkStatus } from '@/lib/playerWorkStatus'
import type { CatalogGamePresentation } from '@/lib/catalogGamePresentation'
import {
  mergeTeachingRunProgress,
  teachingActivityCursor,
  type TeachingRunProgress,
} from '@/lib/teachingProgress'
import { useLocale } from '@/lib/locale'
import {
  fetchVisualStatusWithDeadline,
  VISUAL_LESSON_SETTLING_READS,
  VISUAL_REFRESH_FAILURE_LIMIT,
  VISUAL_RUN_DISCOVERY_LIMIT,
  visualRunIsTerminal,
} from '@/lib/visualEnrichment'

interface TeachingPlan {
  id: string
  documentVersionId: string
  gameTitle: string
  premise: string
  sections: Array<{
    position: number
    title: string
    visualEvidenceRecommended: boolean
  }>
}

interface IllustratedLesson {
  id: string
  teachingPlanId: string
  status: 'COMPLETE' | 'DRAFT_READY' | 'INCOMPLETE'
  sections: LessonSection[]
}

interface LessonVisualFocus {
  pageNumber: number
  label: string
  visibleDescription?: string
  x: number
  y: number
  width: number
  height: number
  sourceKind?: 'FULL_PAGE' | 'PAGE_REGION' | 'EMBEDDED_AUTHOR_IMAGE'
}

interface LessonSection {
  position: number
  topicKey: string
  coverageTags: string[]
  title: string
  required: boolean
  evidenceStatus: 'SUPPORTED' | 'CITED_DRAFT' | 'INSUFFICIENT_EVIDENCE'
  visualKind: 'REFERENCE_CARD' | 'TABLE_LAYOUT' | 'FLOW_DIAGRAM' | 'SCOREBOARD'
  visualCaption: string
  visualSourcePages: number[]
  visualSourceChunkIds: string[]
  steps: Array<{
    position: number
    heading: string
    kind: 'UNDERSTAND' | 'DO' | 'EXAMPLE' | 'WATCH' | 'CHECK' | 'VISUAL' | 'FLOW' | 'LEDGER' | 'REFERENCE_CARD' | 'LIMIT'
    text: string
    sourcePages: number[]
    visualFocus: LessonVisualFocus | null
    visualFoci?: LessonVisualFocus[]
  }>
}

type LessonTerminalKind = 'COMPLETE' | 'READABLE' | 'NEEDS_ACTION' | 'FAILED' | 'CANCELLED'

interface LessonTerminalPresentation {
  message: string
  workStatus: PlayerWorkStatus
}

const LessonComprehensionPanel = defineAsyncComponent(
  () => import('@/components/LessonComprehensionPanel.vue'),
)

const route = useRoute()
const router = useRouter()
const { locale, t } = useLocale()
const loading = ref(true)
const errorMessage = ref('')
const online = ref(navigator.onLine)
const plan = ref<TeachingPlan | null>(null)
const lesson = ref<IllustratedLesson | null>(null)
const sourceLesson = ref<IllustratedLesson | null>(null)
const offlineKnowledge = ref<OfflineKnowledgeEntry[]>([])
const teachingRun = ref<TeachingRunProgress | null>(null)
const visualEnrichmentRun = ref<TeachingRunProgress | null>(null)
const catalogPresentation = ref<CatalogGamePresentation | null>(null)
const catalogCoverUnavailable = ref(false)
const generationStatusUnknown = ref(false)
const generationRefreshError = ref('')
const generationIdentityBlocked = ref(false)
const visualRefreshWarning = ref('')
const visualRefreshStopped = ref(false)
const generationNow = ref(Date.now())
let generationClockTimer: ReturnType<typeof setInterval> | undefined
let lessonViewDisposed = false
let latestLessonLoad = 0
let loadedLessonPlanId: string | null = null
let activeLessonLoadController: AbortController | null = null
let activeGenerationController: AbortController | null = null
let activeVisualController: AbortController | null = null
let activeSupportingController: AbortController | null = null
let missingVisualRunRefreshes = 0
let visualRefreshFailures = 0
let visualLessonSettlingReads = 0
let visualDiscoveryScope: string | null = null
let observedTerminalVisualRunId: string | null = null
let visualIdentityBlocked = false

const VISUAL_RETRY_DELAY_MS = 250

type VisualRunRead =
  | { outcome: 'PRESENT'; value: TeachingRunProgress }
  | { outcome: 'ABSENT' }
  | { outcome: 'FAILED' }
  | { outcome: 'IDENTITY' }

const {
  comprehension,
  comprehensionSaving,
  comprehensionError,
  clearSupportingContent,
  loadSupportingContent: loadSupportingContentForCurrentLesson,
} = useLessonSupportingContent()

const planId = computed(() => String(route.params.planId ?? ''))
const {
  progress,
  reset: resetLessonProgress,
  restore: restoreLessonReaderProgress,
  selectSection,
} = useLessonReaderProgress({
  lesson,
  onSectionSelected: () => undefined,
})
const {
  generationActive,
  visualEnrichmentActive,
  visualEnrichmentFailed,
  visualEnrichmentSummary,
  draftReady,
  currentGenerationText,
  generationElapsed,
  processedGenerationChapters,
  supportedGenerationChapters,
  generationProgressWidth,
  generationRemainingTime,
  recentGenerationActivities,
} = useLessonGenerationPresentation({
  plan,
  lesson,
  currentSectionIndex: computed(() => progress.value.currentIndex),
  generationRun: teachingRun,
  visualEnrichmentRun,
  generationStatusUnknown,
  now: generationNow,
})

const readableLessonSectionCount = computed(() => lesson.value?.sections.filter(
  section => section.evidenceStatus === 'SUPPORTED' || section.evidenceStatus === 'CITED_DRAFT',
).length ?? 0)

function lessonIsFullySupported(candidate: IllustratedLesson) {
  return candidate.status === 'COMPLETE'
    && candidate.sections.length > 0
    && candidate.sections.every(section => section.evidenceStatus === 'SUPPORTED')
}

function terminalWorkStatus(kind: LessonTerminalKind, readable: boolean) {
  const capability = readable ? 'guide' : 'rulebook'
  if (kind === 'COMPLETE') {
    return playerWorkStatus('GUIDE_COMPLETE', {
      capability: 'guide', readiness: 'complete', terminality: 'terminal', outcome: 'none',
    }, locale.value)
  }
  if (kind === 'READABLE') {
    return playerWorkStatus('GUIDE_READABLE', {
      capability: 'guide', readiness: 'usable', terminality: 'terminal', outcome: 'none',
    }, locale.value)
  }
  if (kind === 'FAILED') {
    return playerWorkStatus('FAILED', {
      capability, readiness: readable ? 'usable' : 'unavailable', terminality: 'terminal', outcome: 'failed',
    }, locale.value)
  }
  if (kind === 'CANCELLED') {
    return playerWorkStatus('CANCELLED', {
      capability, readiness: readable ? 'usable' : 'unavailable', terminality: 'terminal', outcome: 'cancelled',
    }, locale.value)
  }
  return playerWorkStatus('NEEDS_ACTION', {
    capability, readiness: readable ? 'usable' : 'unavailable', terminality: 'terminal', outcome: 'needs-action',
  }, locale.value)
}

function terminalGenerationPresentation(
  state: string,
  candidate: IllustratedLesson,
): LessonTerminalPresentation | null {
  const readableCount = candidate.sections.filter(
    section => section.evidenceStatus === 'SUPPORTED' || section.evidenceStatus === 'CITED_DRAFT',
  ).length
  const readable = readableCount > 0
  const complete = state === 'COMPLETED' && lessonIsFullySupported(candidate)
  let kind: LessonTerminalKind
  let message: string

  if (state === 'FAILED') {
    kind = 'FAILED'
    message = locale.value === 'en'
      ? readable
        ? readableCount === 1
          ? 'This guide generation run failed. A readable chapter draft is preserved and can be completed later.'
          : `This guide generation run failed. ${readableCount} readable chapter drafts are preserved and can be completed later.`
        : 'This guide generation run failed without a readable chapter. Retry from My Guides.'
      : readable
        ? `本轮讲解生成失败；已保留 ${readableCount} 章可读讲解草稿，可以稍后重试补全。`
        : '本轮讲解生成失败，目前还没有可读章节；请在“我的讲解”中重试。'
  } else if (state === 'CANCELLED') {
    kind = 'CANCELLED'
    message = locale.value === 'en'
      ? readable
        ? readableCount === 1
          ? 'This guide generation run was cancelled. A readable chapter draft is preserved.'
          : `This guide generation run was cancelled. ${readableCount} readable chapter drafts are preserved.`
        : 'This guide generation run was cancelled before a readable chapter was ready.'
      : readable
        ? `本轮讲解生成已取消；已保留 ${readableCount} 章可读讲解草稿。`
        : '本轮讲解生成已取消，目前还没有可读章节。'
  } else if (complete) {
    kind = 'COMPLETE'
    message = t('lesson.generation.finished.complete')
  } else if (state === 'COMPLETED' && readable) {
    kind = 'READABLE'
    message = locale.value === 'en'
      ? 'This generation run has finished with a readable guide draft. Additional content review is not complete.'
      : '本轮生成已经结束；可读讲解草稿已经保留，额外内容复核尚未完成。'
  } else if ((state === 'INSUFFICIENT_EVIDENCE' || state === 'DEGRADED') && readable) {
    kind = 'READABLE'
    message = locale.value === 'en'
      ? `${readableCount} readable ${readableCount === 1 ? 'chapter draft is' : 'chapter drafts are'} preserved. Content without enough evidence or completed review was not published as a complete guide.`
      : `本轮生成已经结束；已保留 ${readableCount} 章可读讲解草稿，证据不足或未完成复核的部分没有作为完整讲解发布。`
  } else if (state === 'COMPLETED' || state === 'INSUFFICIENT_EVIDENCE' || state === 'DEGRADED') {
    kind = 'NEEDS_ACTION'
    message = t('lesson.generation.finished.noReadable')
  } else {
    return null
  }

  return { message, workStatus: terminalWorkStatus(kind, readable) }
}

const generationTerminalPresentation = computed(() => {
  if (!lesson.value || !teachingRun.value) return null
  return terminalGenerationPresentation(
    teachingRun.value.run.state,
    lesson.value,
  )
})

const visualEvidenceExpected = computed(() => plan.value?.sections.some(
  section => section.visualEvidenceRecommended,
) === true)
const teachingRunTerminal = computed(() => visualRunIsTerminal(teachingRun.value?.run.state))

function shouldPollVisualEnrichment() {
  if (visualIdentityBlocked || visualRefreshFailures >= VISUAL_REFRESH_FAILURE_LIMIT) return false
  if (visualLessonSettlingReads > 0) return true
  return visualEnrichmentActive.value
    ? missingVisualRunRefreshes < VISUAL_RUN_DISCOVERY_LIMIT
    : (!visualEnrichmentRun.value
      && teachingRunTerminal.value
      && visualEvidenceExpected.value
      && missingVisualRunRefreshes < VISUAL_RUN_DISCOVERY_LIMIT)
}

const generationPolling = useConditionalPolling({
  enabled: () => !lessonViewDisposed
    && online.value
    && generationActive.value
    && !generationIdentityBlocked.value,
  refresh: refreshGeneration,
  defaultDelay: 1_500,
})
const visualPolling = useConditionalPolling({
  enabled: () => !lessonViewDisposed && online.value && shouldPollVisualEnrichment(),
  refresh: refreshVisualEnrichment,
  defaultDelay: 2_500,
})

const {
  recordComprehension,
  recordVisualAid,
} = useLessonComprehensionFeedback({
  planId,
  online,
  currentRequest: () => latestLessonLoad,
  isCurrent: isCurrentLessonLoad,
  comprehension,
  saving: comprehensionSaving,
  errorMessage: comprehensionError,
  csrfToken,
  messages: {
    saveTaskRetry: () => t('lesson.comprehension.error.saveTaskRetry'),
    saveTask: () => t('lesson.comprehension.error.saveTask'),
    saveVisualRetry: () => t('lesson.comprehension.error.saveVisualRetry'),
    saveVisual: () => t('lesson.comprehension.error.saveVisual'),
  },
})

function pageImageUrl(page: number | undefined) {
  if (!plan.value || !page) return ''
  return `/api/v1/document-versions/${plan.value.documentVersionId}/pages/${page}/image`
}

function pagePreviewImageUrl(page: number) {
  if (!plan.value) return ''
  return `/api/v1/document-versions/${plan.value.documentVersionId}/pages/${page}/image/preview`
}

function focusedPageImageUrl(focus: NonNullable<LessonSection['steps'][number]['visualFocus']>) {
  if (!plan.value) return ''
  const query = new URLSearchParams({
    x: String(focus.x),
    y: String(focus.y),
    width: String(focus.width),
    height: String(focus.height),
  })
  return `/api/v1/document-versions/${plan.value.documentVersionId}/pages/${focus.pageNumber}/image/crop?${query}`
}

function visualFocusStyle(focus: NonNullable<LessonSection['steps'][number]['visualFocus']>) {
  return {
    left: `${focus.x / 10}%`,
    top: `${focus.y / 10}%`,
    width: `${focus.width / 10}%`,
    height: `${focus.height / 10}%`,
  }
}

function refreshOfflineKnowledge(targetPlanId = planId.value) {
  offlineKnowledge.value = loadOfflineKnowledge(targetPlanId)
}

function isCurrentLessonLoad(request: number, targetPlanId: string) {
  return !lessonViewDisposed && request === latestLessonLoad && targetPlanId === planId.value
}

function responseMatchesPlan(
  targetPlanId: string,
  incomingLesson: IllustratedLesson | null,
  incomingRun: TeachingRunProgress | null,
) {
  return (!incomingLesson || incomingLesson.teachingPlanId === targetPlanId)
    && (!incomingRun || incomingRun.run.subjectId === targetPlanId)
}

function resetVisualLifecycle(clearRun = true) {
  if (clearRun) visualEnrichmentRun.value = null
  missingVisualRunRefreshes = 0
  visualRefreshFailures = 0
  visualLessonSettlingReads = 0
  visualDiscoveryScope = null
  observedTerminalVisualRunId = null
  visualIdentityBlocked = false
  visualRefreshWarning.value = ''
  visualRefreshStopped.value = false
}

function syncVisualDiscoveryScope(targetPlanId: string) {
  const nextScope = teachingRun.value ? `${targetPlanId}:${teachingRun.value.run.id}` : null
  if (visualDiscoveryScope === nextScope) return
  const replacingRun = visualDiscoveryScope !== null && visualDiscoveryScope !== nextScope
  visualDiscoveryScope = nextScope
  missingVisualRunRefreshes = 0
  visualRefreshFailures = 0
  visualLessonSettlingReads = 0
  observedTerminalVisualRunId = null
  visualIdentityBlocked = false
  visualRefreshWarning.value = ''
  visualRefreshStopped.value = false
  if (replacingRun) visualEnrichmentRun.value = null
}

function acceptVisualRun(incoming: TeachingRunProgress) {
  visualEnrichmentRun.value = mergeTeachingRunProgress(visualEnrichmentRun.value, incoming)
  missingVisualRunRefreshes = 0
  visualRefreshFailures = 0
  visualRefreshWarning.value = ''
  visualRefreshStopped.value = false
  if (visualRunIsTerminal(visualEnrichmentRun.value?.run.state)
    && observedTerminalVisualRunId !== visualEnrichmentRun.value?.run.id) {
    observedTerminalVisualRunId = visualEnrichmentRun.value!.run.id
    visualLessonSettlingReads = VISUAL_LESSON_SETTLING_READS
  }
}

async function readVisualRunSnapshot(targetPlanId: string, signal: AbortSignal): Promise<VisualRunRead> {
  let response: Response
  try {
    response = await fetchVisualStatusWithDeadline(
      `/api/v1/assistant-runs/latest?mode=VISUAL_ENRICHMENT&subjectId=${encodeURIComponent(targetPlanId)}`,
      { credentials: 'include', signal },
    )
  } catch {
    if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
    return { outcome: 'FAILED' }
  }
  if (response.status === 401 || response.status === 403) return { outcome: 'IDENTITY' }
  if (response.status === 404) return { outcome: 'ABSENT' }
  if (!response.ok) return { outcome: 'FAILED' }
  try {
    return { outcome: 'PRESENT', value: await response.json() as TeachingRunProgress }
  } catch {
    return { outcome: 'FAILED' }
  }
}

function applyVisualRunRead(targetPlanId: string, incoming: VisualRunRead) {
  if (incoming.outcome === 'IDENTITY') {
    notifyLoginRequired()
    visualIdentityBlocked = true
    visualRefreshStopped.value = true
    visualRefreshWarning.value = t('lesson.reader.error.loginRequired')
    return
  }
  if (incoming.outcome === 'FAILED') {
    visualRefreshFailures += 1
    visualRefreshWarning.value = t('lesson.generation.visual.refreshFailed')
    if (visualRefreshFailures >= VISUAL_REFRESH_FAILURE_LIMIT) visualRefreshStopped.value = true
    return
  }
  if (incoming.outcome === 'ABSENT') {
    missingVisualRunRefreshes += 1
    visualRefreshFailures = 0
    if (missingVisualRunRefreshes >= VISUAL_RUN_DISCOVERY_LIMIT) {
      visualRefreshStopped.value = true
      visualRefreshWarning.value = t('lesson.generation.visual.refreshFailed')
    }
    return
  }
  if (incoming.value.run.subjectId !== targetPlanId) {
    visualIdentityBlocked = true
    visualRefreshStopped.value = true
    visualRefreshWarning.value = t('lesson.generation.visual.refreshFailed')
    return
  }
  acceptVisualRun(incoming.value)
}

function visualFocusSignature(candidate: IllustratedLesson | null) {
  if (!candidate) return ''
  const values: string[] = []
  for (const section of candidate.sections) {
    for (const step of section.steps) {
      const foci = step.visualFoci?.length
        ? step.visualFoci
        : step.visualFocus ? [step.visualFocus] : []
      for (const focus of foci) {
        values.push([
          section.position,
          step.position,
          focus.pageNumber,
          focus.x,
          focus.y,
          focus.width,
          focus.height,
        ].join(':'))
      }
    }
  }
  return values.join('|')
}

function retryVisualEnrichment() {
  if (!online.value || lessonViewDisposed) return
  missingVisualRunRefreshes = 0
  visualRefreshFailures = 0
  visualIdentityBlocked = false
  visualRefreshStopped.value = false
  visualRefreshWarning.value = ''
  visualPolling.schedule(VISUAL_RETRY_DELAY_MS)
}

function retryGenerationStatus() {
  if (!online.value || lessonViewDisposed) return
  generationIdentityBlocked.value = false
  generationRefreshError.value = ''
  generationPolling.schedule(0)
}

async function discoverInitialVisualRun() {
  if (!online.value || lessonViewDisposed || !shouldPollVisualEnrichment()) return
  const targetPlanId = planId.value
  const request = latestLessonLoad
  activeVisualController?.abort()
  const controller = new AbortController()
  activeVisualController = controller
  let retryDelay = VISUAL_RETRY_DELAY_MS
  try {
    const visualRunRead = await readVisualRunSnapshot(targetPlanId, controller.signal)
    if (!isCurrentRead(request, targetPlanId, controller, activeVisualController)) return
    applyVisualRunRead(targetPlanId, visualRunRead)
    if (visualRunRead.outcome === 'PRESENT' && visualEnrichmentActive.value) retryDelay = 2_500
  } catch {
    if (isCurrentRead(request, targetPlanId, controller, activeVisualController)
      && !controller.signal.aborted) {
      applyVisualRunRead(targetPlanId, { outcome: 'FAILED' })
    }
  } finally {
    if (isCurrentRead(request, targetPlanId, controller, activeVisualController)) {
      activeVisualController = null
      visualPolling.schedule(retryDelay)
    }
  }
}

function isCurrentRead(
  request: number,
  targetPlanId: string,
  controller: AbortController,
  activeController: AbortController | null,
) {
  return isCurrentLessonLoad(request, targetPlanId)
    && activeController === controller
}

const {
  status: localizationStatus,
  preparing: localizationPreparing,
  applySelectedLocale,
  prepareEnglishGuide,
  cancelReads: cancelLocalizationReads,
  reset: resetLessonLocalization,
  dispose: disposeLessonLocalization,
} = useLessonLocalization({
  locale,
  planId,
  sourceLesson,
  displayedLesson: lesson,
  currentRequest: () => latestLessonLoad,
  isCurrent: (request, targetPlanId) => isCurrentLessonLoad(request, targetPlanId),
  isLessonForPlan: (candidate, targetPlanId) => candidate.teachingPlanId === targetPlanId,
  canRead: () => online.value,
  requestLogin: async () => notifyLoginRequired(),
  csrfToken,
})

function resetLessonReader() {
  loadedLessonPlanId = null
  plan.value = null
  lesson.value = null
  sourceLesson.value = null
  catalogPresentation.value = null
  catalogCoverUnavailable.value = false
  resetLessonLocalization()
  resetLessonProgress()
  offlineKnowledge.value = []
}

function cancelReadTransport() {
  activeLessonLoadController?.abort()
  activeLessonLoadController = null
  activeGenerationController?.abort()
  activeGenerationController = null
  activeVisualController?.abort()
  activeVisualController = null
  activeSupportingController?.abort()
  activeSupportingController = null
  cancelLocalizationReads()
}

async function optionalFetch(url: string, signal: AbortSignal) {
  try {
    return await fetch(url, { credentials: 'include', signal })
  } catch {
    if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
    return null
  }
}

async function loadCatalogPresentation(targetPlanId: string, signal: AbortSignal) {
  try {
    const response = await fetch(
      `/api/v1/teaching-plans/${encodeURIComponent(targetPlanId)}/catalog-presentation`,
      { credentials: 'include', signal },
    )
    return response.ok ? await response.json() as CatalogGamePresentation : null
  } catch {
    if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
    return null
  }
}

async function loadSupportingContent(targetPlanId: string, request = latestLessonLoad) {
  activeSupportingController?.abort()
  const controller = new AbortController()
  activeSupportingController = controller
  try {
    await loadSupportingContentForCurrentLesson({
      planId: targetPlanId,
      signal: controller.signal,
      isCurrent: () => isCurrentLessonLoad(request, targetPlanId)
        && activeSupportingController === controller,
      requestLogin: async () => notifyLoginRequired(),
    })
  } finally {
    if (activeSupportingController === controller) activeSupportingController = null
  }
}

async function loadLesson() {
  const targetPlanId = planId.value
  const request = ++latestLessonLoad
  cancelReadTransport()
  generationPolling.clear()
  visualPolling.clear()
  loading.value = true
  errorMessage.value = ''
  resetLessonReader()
  teachingRun.value = null
  resetVisualLifecycle()
  generationStatusUnknown.value = false
  generationRefreshError.value = ''
  generationIdentityBlocked.value = false
  clearSupportingContent()
  if (!targetPlanId) {
    await router.replace({ name: 'lessons' })
    if (isCurrentLessonLoad(request, targetPlanId)) loading.value = false
    return
  }
  refreshOfflineKnowledge(targetPlanId)
  if (!online.value) {
    if (!offlineKnowledge.value.length) errorMessage.value = t('lesson.reader.error.load')
    loading.value = false
    return
  }
  const controller = new AbortController()
  activeLessonLoadController = controller
  try {
    const [planResponse, lessonResponse, runResponse, loadedCatalogPresentation] = await Promise.all([
      fetch(`/api/v1/teaching-plans/${encodeURIComponent(targetPlanId)}`, {
        credentials: 'include', signal: controller.signal,
      }),
      fetch(`/api/v1/teaching-plans/${encodeURIComponent(targetPlanId)}/illustrated-lessons/latest`, {
        credentials: 'include', signal: controller.signal,
      }),
      optionalFetch(
        `/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(targetPlanId)}`,
        controller.signal,
      ),
      loadCatalogPresentation(targetPlanId, controller.signal),
    ])
    if (!isCurrentRead(request, targetPlanId, controller, activeLessonLoadController)) return
    if ([planResponse.status, lessonResponse.status, runResponse?.status].some(
      status => status === 401 || status === 403,
    )) {
      notifyLoginRequired()
      errorMessage.value = t('lesson.reader.error.loginRequired')
      return
    }
    if (!planResponse.ok || !lessonResponse.ok) {
      throw new Error(t('lesson.reader.error.load'))
    }
    const [loadedPlan, loadedLesson, loadedRun] = await Promise.all([
      planResponse.json() as Promise<TeachingPlan>,
      lessonResponse.json() as Promise<IllustratedLesson>,
      runResponse?.ok ? runResponse.json() as Promise<TeachingRunProgress> : Promise.resolve(null),
    ])
    if (!isCurrentRead(request, targetPlanId, controller, activeLessonLoadController)) return
    if (loadedPlan.id !== targetPlanId
      || !responseMatchesPlan(targetPlanId, loadedLesson, loadedRun)) {
      throw new Error()
    }
    plan.value = loadedPlan
    catalogPresentation.value = loadedCatalogPresentation
    sourceLesson.value = loadedLesson
    lesson.value = sourceLesson.value
    await applySelectedLocale(targetPlanId, request)
    if (!isCurrentRead(request, targetPlanId, controller, activeLessonLoadController)) return
    teachingRun.value = loadedRun
    syncVisualDiscoveryScope(targetPlanId)
    generationStatusUnknown.value = runResponse === null || (!runResponse.ok && runResponse.status !== 404)
    if (generationStatusUnknown.value) generationRefreshError.value = t('lesson.generation.refreshFailed')
    localStorage.setItem('rulepilot:last-plan-id', targetPlanId)
    restoreLessonReaderProgress()
    loadedLessonPlanId = targetPlanId
    if (generationActive.value) generationPolling.schedule()
    else await loadSupportingContent(targetPlanId, request)
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    if (shouldPollVisualEnrichment()) {
      if (teachingRunTerminal.value && visualEvidenceExpected.value && !visualEnrichmentRun.value) {
        void discoverInitialVisualRun()
      } else {
        visualPolling.schedule(visualEnrichmentActive.value ? 2_500 : VISUAL_RETRY_DELAY_MS)
      }
    }
  } catch {
    if (!isCurrentRead(request, targetPlanId, controller, activeLessonLoadController)) return
    if (controller.signal.aborted) return
    controller.abort()
    if (offlineKnowledge.value.length) {
      online.value = false
    } else {
      online.value = navigator.onLine
      errorMessage.value = t('lesson.reader.error.load')
    }
  } finally {
    if (isCurrentRead(request, targetPlanId, controller, activeLessonLoadController)) {
      activeLessonLoadController = null
      loading.value = false
    }
  }
}

async function refreshVisualEnrichment() {
  if (!online.value || lessonViewDisposed || !shouldPollVisualEnrichment()) return
  const targetPlanId = planId.value
  const request = latestLessonLoad
  activeVisualController?.abort()
  const controller = new AbortController()
  activeVisualController = controller
  const settlingLessonRead = visualLessonSettlingReads > 0
  const previousVisualSignature = visualFocusSignature(sourceLesson.value)
  const queryVisualRun = !visualIdentityBlocked
    && visualRefreshFailures < VISUAL_REFRESH_FAILURE_LIMIT
    && (visualEnrichmentActive.value || !visualEnrichmentRun.value)
  let retryDelay = 2_500
  try {
    const [visualRunRead, lessonResponse] = await Promise.all([
      queryVisualRun
        ? readVisualRunSnapshot(targetPlanId, controller.signal)
        : Promise.resolve<VisualRunRead | null>(null),
      fetchVisualStatusWithDeadline(`/api/v1/teaching-plans/${encodeURIComponent(targetPlanId)}/illustrated-lessons/latest`, {
        credentials: 'include', signal: controller.signal,
      }),
    ])
    if (!isCurrentRead(request, targetPlanId, controller, activeVisualController)) return
    if (lessonResponse.status === 401 || lessonResponse.status === 403) {
      notifyLoginRequired()
      visualIdentityBlocked = true
      visualRefreshStopped.value = true
      visualRefreshWarning.value = t('lesson.reader.error.loginRequired')
      return
    }
    if (!lessonResponse.ok) throw new Error(t('lesson.generation.refreshFailed'))
    const incomingLesson = await lessonResponse.json() as IllustratedLesson
    if (!isCurrentRead(request, targetPlanId, controller, activeVisualController)) return
    if (!responseMatchesPlan(targetPlanId, incomingLesson, null)) {
      throw new Error()
    }
    sourceLesson.value = acceptProgressiveLesson(sourceLesson.value, incomingLesson)
    lesson.value = sourceLesson.value
    await applySelectedLocale(targetPlanId, request)
    if (!isCurrentRead(request, targetPlanId, controller, activeVisualController)) return
    if (visualRunRead) {
      applyVisualRunRead(targetPlanId, visualRunRead)
      if (visualRunRead.outcome === 'ABSENT' && !visualEnrichmentRun.value) {
        retryDelay = VISUAL_RETRY_DELAY_MS
      }
      if (visualRunRead.outcome === 'FAILED') {
        retryDelay = Math.min(12_000, 3_000 * 2 ** (visualRefreshFailures - 1))
      }
    }
    if (settlingLessonRead && visualLessonSettlingReads > 0) {
      visualLessonSettlingReads -= 1
    }
    if (visualFocusSignature(sourceLesson.value) !== previousVisualSignature) {
      await loadSupportingContent(targetPlanId, request)
      if (!isCurrentLessonLoad(request, targetPlanId)) return
    }
  } catch {
    if (!isCurrentRead(request, targetPlanId, controller, activeVisualController) || controller.signal.aborted) return
    controller.abort()
    visualRefreshFailures += 1
    visualRefreshWarning.value = t('lesson.generation.visual.refreshFailed')
    retryDelay = Math.min(16_000, 4_000 * 2 ** (visualRefreshFailures - 1))
    if (visualRefreshFailures >= VISUAL_REFRESH_FAILURE_LIMIT) visualRefreshStopped.value = true
  } finally {
    if (isCurrentRead(request, targetPlanId, controller, activeVisualController)) {
      activeVisualController = null
      visualPolling.schedule(retryDelay)
    }
  }
}

async function refreshGeneration() {
  if (!generationActive.value || !online.value || lessonViewDisposed) return
  const targetPlanId = planId.value
  const request = latestLessonLoad
  activeGenerationController?.abort()
  const controller = new AbortController()
  activeGenerationController = controller
  const wasActive = generationActive.value
  const activityCursor = teachingActivityCursor(teachingRun.value)
  try {
    const [runResponse, lessonResponse] = await Promise.all([
      fetch(`/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(targetPlanId)}${activityCursor}`, {
        credentials: 'include', signal: controller.signal,
      }),
      fetch(`/api/v1/teaching-plans/${encodeURIComponent(targetPlanId)}/illustrated-lessons/latest`, {
        credentials: 'include', signal: controller.signal,
      }),
    ])
    if (!isCurrentRead(request, targetPlanId, controller, activeGenerationController)) return
    if ([runResponse.status, lessonResponse.status].some(status => status === 401 || status === 403)) {
      if (!generationIdentityBlocked.value) notifyLoginRequired()
      generationIdentityBlocked.value = true
      generationRefreshError.value = t('lesson.reader.error.loginRequired')
      return
    }
    if ((!runResponse.ok && runResponse.status !== 404) || !lessonResponse.ok) {
      throw new Error(t('lesson.generation.refreshFailed'))
    }

    const [incomingRun, incomingLesson] = await Promise.all([
      runResponse.ok ? runResponse.json() as Promise<TeachingRunProgress> : Promise.resolve(null),
      lessonResponse.json() as Promise<IllustratedLesson>,
    ])
    if (!isCurrentRead(request, targetPlanId, controller, activeGenerationController)) return
    if (!responseMatchesPlan(targetPlanId, incomingLesson, incomingRun)) {
      throw new Error()
    }
    const acceptedRun = mergeTeachingRunProgress(teachingRun.value, incomingRun)
    const previousLesson = sourceLesson.value
    const previousCount = previousLesson?.sections.length ?? 0
    const acceptedLesson = acceptProgressiveLesson(previousLesson, incomingLesson)
    const lessonReplaced = previousLesson !== null && acceptedLesson.id !== previousLesson.id
    sourceLesson.value = acceptedLesson
    lesson.value = acceptedLesson
    await applySelectedLocale(targetPlanId, request)
    if (!isCurrentRead(request, targetPlanId, controller, activeGenerationController)) return
    teachingRun.value = acceptedRun
    syncVisualDiscoveryScope(targetPlanId)
    generationStatusUnknown.value = false
    generationIdentityBlocked.value = false
    generationRefreshError.value = ''

    if (lessonReplaced) {
      restoreLessonReaderProgress()
      selectSection(progress.value.currentIndex)
    } else if (acceptedLesson.sections.length > previousCount) {
      selectSection(Math.min(progress.value.currentIndex, acceptedLesson.sections.length - 1))
    }

    if (wasActive && !generationActive.value) {
      await loadSupportingContent(targetPlanId, request)
      if (!isCurrentLessonLoad(request, targetPlanId)) return
      void refreshVisualEnrichment()
    }
  } catch {
    if (!isCurrentRead(request, targetPlanId, controller, activeGenerationController) || controller.signal.aborted) return
    controller.abort()
    generationRefreshError.value = t('lesson.generation.refreshFailed')
  } finally {
    if (isCurrentRead(request, targetPlanId, controller, activeGenerationController)) {
      activeGenerationController = null
      generationPolling.schedule()
    }
  }
}

async function csrfToken() {
  const response = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (response.status === 401) {
    notifyLoginRequired()
    throw new Error(t('lesson.reader.error.loginRequired'))
  }
  if (!response.ok) throw new Error(t('lesson.reader.error.secureSession'))
  return (await response.json()) as CsrfResponse
}

function updateOnlineStatus() {
  online.value = navigator.onLine
  if (!online.value) {
    cancelReadTransport()
    generationPolling.clear()
    visualPolling.clear()
    refreshOfflineKnowledge()
    loading.value = false
    if (!lesson.value) errorMessage.value = t('lesson.reader.error.load')
    return
  }
  if (loadedLessonPlanId !== planId.value || !lesson.value) {
    void loadLesson()
    return
  }
  void applySelectedLocale(planId.value, latestLessonLoad)
  if (!generationActive.value) void loadSupportingContent(planId.value, latestLessonLoad)
  if (generationActive.value) generationPolling.schedule(0)
  if (shouldPollVisualEnrichment()) visualPolling.schedule(VISUAL_RETRY_DELAY_MS)
}

onMounted(() => {
  lessonViewDisposed = false
  generationClockTimer = setInterval(() => { generationNow.value = Date.now() }, 1000)
  void loadLesson()
  window.addEventListener('online', updateOnlineStatus)
  window.addEventListener('offline', updateOnlineStatus)
})

watch(locale, () => { void applySelectedLocale() })

watch(planId, () => {
  void loadLesson()
})

onUnmounted(() => {
  lessonViewDisposed = true
  latestLessonLoad += 1
  cancelReadTransport()
  generationPolling.dispose()
  visualPolling.dispose()
  disposeLessonLocalization()
  if (generationClockTimer) clearInterval(generationClockTimer)
  generationClockTimer = undefined
  window.removeEventListener('online', updateOnlineStatus)
  window.removeEventListener('offline', updateOnlineStatus)
})
</script>

<template>
  <AppShell>
    <div data-testid="private-lesson-surface" class="min-h-screen bg-canvas pb-10 text-ink">
      <header class="app-sticky-top sticky z-20 border-b border-ink/10 bg-canvas/90 backdrop-blur">
        <div class="mx-auto flex max-w-4xl items-center justify-between gap-4 px-5 py-4 sm:px-8">
          <RouterLink :to="{ name: 'lessons' }" class="text-sm font-semibold text-indigo">← {{ t('lesson.reader.back') }}</RouterLink>
          <LessonModeNav
            v-if="lesson"
            :plan-id="planId"
            guide-route="lesson"
            questions-route="lesson-questions"
            active="guide"
          />
        </div>
      </header>

      <section v-if="locale === 'en' && lesson && localizationStatus !== 'READY'" class="border-b border-indigo/15 bg-indigo/5 px-5 py-3" role="status">
        <div class="mx-auto flex max-w-4xl flex-wrap items-center justify-between gap-3 text-sm leading-6 text-indigo">
          <p>{{ localizationStatus === 'FAILED' ? 'The English guide could not be prepared. The cited Chinese guide is still available.' : 'The English guide is being prepared. The cited Chinese guide remains available while it finishes.' }}</p>
          <button v-if="!['PENDING', 'RUNNING'].includes(localizationStatus ?? '')" type="button" :disabled="localizationPreparing" class="min-h-10 rounded-xl bg-indigo px-4 text-sm font-semibold text-white disabled:opacity-50" @click="prepareEnglishGuide">{{ localizationPreparing ? 'Preparing…' : 'Prepare English guide' }}</button>
        </div>
      </section>

      <p v-if="!online" class="bg-amber-100 px-5 py-3 text-center text-sm font-semibold text-amber-900" role="status">{{ t('lesson.reader.offline.banner') }}</p>
      <LessonGenerationStatus
        :active="generationActive"
        :status-unknown="generationStatusUnknown"
        :status-text="currentGenerationText"
        :draft-ready="draftReady"
        :available-section-count="readableLessonSectionCount"
        :total-section-count="plan?.sections.length ?? null"
        :elapsed="generationElapsed"
        :processed-chapter-count="processedGenerationChapters"
        :supported-chapter-count="supportedGenerationChapters"
        :progress-width="generationProgressWidth"
        :remaining-time="generationRemainingTime"
        :activities="recentGenerationActivities"
        :refresh-failed="Boolean(generationRefreshError) && !generationIdentityBlocked"
        :finished-message="generationTerminalPresentation?.message ?? ''"
        :finished-status="generationTerminalPresentation?.workStatus ?? null"
      />

      <section
        v-if="generationIdentityBlocked"
        data-testid="lesson-generation-auth-stopped"
        class="border-b border-amber-200 bg-amber-50 px-5 py-3 text-amber-950"
        role="alert"
      >
        <div class="mx-auto flex max-w-4xl flex-wrap items-center justify-between gap-3 text-sm leading-6">
          <p>{{ generationRefreshError }}</p>
          <button type="button" class="min-h-10 rounded-xl border border-amber-300 px-4 text-xs font-semibold" @click="retryGenerationStatus">
            {{ t('lesson.reader.state.error.retry') }}
          </button>
        </div>
      </section>

      <section
        v-if="visualEnrichmentSummary || visualRefreshWarning"
        data-testid="lesson-visual-enrichment-status"
        class="border-b px-5 py-3"
        :class="visualEnrichmentFailed || visualRefreshWarning ? 'border-amber-200 bg-amber-50 text-amber-950' : 'border-indigo/15 bg-indigo/5 text-indigo'"
        role="status"
      >
        <div class="mx-auto flex max-w-4xl flex-wrap items-center justify-between gap-3 text-sm leading-6">
          <div class="min-w-0 flex-1">
            <p v-if="visualEnrichmentSummary" class="font-semibold">{{ visualEnrichmentActive ? t('lesson.sidebar.visual.enriching') : visualEnrichmentFailed ? t('lesson.sidebar.visual.failed') : t('lesson.sidebar.visual.completed') }}</p>
            <p v-if="visualEnrichmentSummary" class="break-words text-xs opacity-80">{{ visualEnrichmentSummary }}</p>
            <p v-if="visualRefreshWarning" class="break-words text-xs font-semibold">{{ visualRefreshWarning }}</p>
          </div>
          <button
            v-if="visualRefreshStopped"
            type="button"
            class="min-h-10 shrink-0 rounded-xl border border-amber-300 px-4 text-xs font-semibold"
            @click="retryVisualEnrichment"
          >
            {{ t('lesson.generation.visual.retry') }}
          </button>
        </div>
      </section>

      <LessonOfflineKnowledgePanel v-if="!online && offlineKnowledge.length" :entries="offlineKnowledge" />

      <div v-if="loading" class="mx-auto max-w-7xl px-5 py-16 sm:px-8" aria-live="polite">
        <div class="h-7 w-44 animate-pulse rounded bg-ink/10" />
        <div class="mt-6 h-80 animate-pulse rounded-3xl bg-paper" />
      </div>

      <LessonReaderStateSurface
        v-else-if="errorMessage || !lesson"
        :error-message="errorMessage"
        :online="online"
        @retry="loadLesson"
      />

      <article v-else class="mx-auto max-w-6xl px-5 py-9 sm:px-8 lg:py-14" data-testid="private-lesson-reader">
        <LessonGuideHero
          :title="catalogPresentation?.gameName ?? plan?.gameTitle ?? ''"
          :eyebrow="t('lesson.reader.guideEyebrow')"
          :description="t('lesson.reader.guideDescription')"
          :rulebook-title="catalogPresentation ? plan?.gameTitle : ''"
          :cover-url="catalogPresentation?.thumbnailUrl ?? ''"
          :cover-alt="t('lesson.catalog.coverAlt', { game: catalogPresentation?.gameName ?? '' })"
          :cover-href="catalogPresentation?.bggUrl ?? ''"
          :cover-unavailable="catalogCoverUnavailable"
          @cover-error="catalogCoverUnavailable = true"
        >
          <template #actions>
            <RouterLink :to="{ name: 'public-lesson', params: { planId } }" class="inline-flex min-h-11 items-center rounded-xl border border-[rgba(248,239,223,0.25)] bg-[rgba(248,239,223,0.1)] px-4 text-sm font-semibold text-[#f8efdf]">{{ t('lesson.reader.public') }}</RouterLink>
          </template>
        </LessonGuideHero>

        <CatalogGameAttribution v-if="catalogPresentation" :presentation="catalogPresentation" />

        <LessonChapterList
          :sections="lesson.sections"
          id-prefix="private-chapter"
          :page-image-url="pageImageUrl"
          :page-preview-image-url="pagePreviewImageUrl"
          :focused-page-image-url="focusedPageImageUrl"
          step-test-id="private-rule-step"
        />

        <LessonComprehensionPanel
          v-if="!generationActive && (comprehension || comprehensionError)"
          class="mx-auto max-w-4xl"
          :comprehension="comprehension"
          :error-message="comprehensionError"
          :saving="comprehensionSaving"
          :online="online"
          :page-image-url="pageImageUrl"
          :focused-page-image-url="focusedPageImageUrl"
          :visual-focus-style="visualFocusStyle"
          @rate-task="recordComprehension"
          @rate-visual-aid="recordVisualAid"
          @revisit-chapter="selectSection"
        />
      </article>
    </div>
  </AppShell>
</template>
