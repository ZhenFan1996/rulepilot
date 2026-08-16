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
import type { CatalogGamePresentation } from '@/lib/catalogGamePresentation'
import {
  mergeTeachingRunProgress,
  teachingActivityCursor,
  type TeachingRunProgress,
} from '@/lib/teachingProgress'
import { useLocale } from '@/lib/locale'

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
    kind: 'UNDERSTAND' | 'DO' | 'EXAMPLE' | 'WATCH' | 'CHECK' | 'VISUAL' | 'FLOW' | 'LEDGER'
    text: string
    sourcePages: number[]
    visualFocus: {
      pageNumber: number
      label: string
      visibleDescription?: string
      x: number
      y: number
      width: number
      height: number
    } | null
  }>
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
const generationFinishedMessage = ref('')
const generationNow = ref(Date.now())
let generationClockTimer: ReturnType<typeof setInterval> | undefined
let lessonViewDisposed = false
let latestLessonLoad = 0
let loadedLessonPlanId: string | null = null
let activeLessonLoadController: AbortController | null = null
let activeGenerationController: AbortController | null = null
let activeVisualController: AbortController | null = null
let activeSupportingController: AbortController | null = null

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

const generationPolling = useConditionalPolling({
  enabled: () => !lessonViewDisposed && online.value && generationActive.value,
  refresh: refreshGeneration,
  defaultDelay: 1_500,
})
const visualPolling = useConditionalPolling({
  enabled: () => !lessonViewDisposed && online.value && visualEnrichmentActive.value,
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
  visualEnrichmentRun.value = null
  generationStatusUnknown.value = false
  generationRefreshError.value = ''
  generationFinishedMessage.value = ''
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
    const [planResponse, lessonResponse, runResponse, visualRunResponse, loadedCatalogPresentation] = await Promise.all([
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
      optionalFetch(
        `/api/v1/assistant-runs/latest?mode=VISUAL_ENRICHMENT&subjectId=${encodeURIComponent(targetPlanId)}`,
        controller.signal,
      ),
      loadCatalogPresentation(targetPlanId, controller.signal),
    ])
    if (!isCurrentRead(request, targetPlanId, controller, activeLessonLoadController)) return
    if (planResponse.status === 401 || lessonResponse.status === 401 || runResponse?.status === 401 || visualRunResponse?.status === 401) {
      notifyLoginRequired()
      errorMessage.value = t('lesson.reader.error.loginRequired')
      return
    }
    if (!planResponse.ok || !lessonResponse.ok) {
      throw new Error(t('lesson.reader.error.load'))
    }
    const [loadedPlan, loadedLesson, loadedRun, loadedVisualRun] = await Promise.all([
      planResponse.json() as Promise<TeachingPlan>,
      lessonResponse.json() as Promise<IllustratedLesson>,
      runResponse?.ok ? runResponse.json() as Promise<TeachingRunProgress> : Promise.resolve(null),
      visualRunResponse?.ok ? visualRunResponse.json() as Promise<TeachingRunProgress> : Promise.resolve(null),
    ])
    if (!isCurrentRead(request, targetPlanId, controller, activeLessonLoadController)) return
    if (loadedPlan.id !== targetPlanId
      || !responseMatchesPlan(targetPlanId, loadedLesson, loadedRun)
      || !responseMatchesPlan(targetPlanId, null, loadedVisualRun)) {
      throw new Error()
    }
    plan.value = loadedPlan
    catalogPresentation.value = loadedCatalogPresentation
    sourceLesson.value = loadedLesson
    lesson.value = sourceLesson.value
    await applySelectedLocale(targetPlanId, request)
    if (!isCurrentRead(request, targetPlanId, controller, activeLessonLoadController)) return
    teachingRun.value = loadedRun
    visualEnrichmentRun.value = loadedVisualRun
    generationStatusUnknown.value = runResponse === null || (!runResponse.ok && runResponse.status !== 404)
    if (generationStatusUnknown.value) generationRefreshError.value = t('lesson.generation.refreshFailed')
    localStorage.setItem('rulepilot:last-plan-id', targetPlanId)
    restoreLessonReaderProgress()
    loadedLessonPlanId = targetPlanId
    if (generationActive.value) generationPolling.schedule()
    else await loadSupportingContent(targetPlanId, request)
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    if (visualEnrichmentActive.value) visualPolling.schedule()
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
  if (!online.value || lessonViewDisposed) return
  const targetPlanId = planId.value
  const request = latestLessonLoad
  activeVisualController?.abort()
  const controller = new AbortController()
  activeVisualController = controller
  let retryDelay = 2500
  try {
    const [response, lessonResponse] = await Promise.all([
      optionalFetch(
        `/api/v1/assistant-runs/latest?mode=VISUAL_ENRICHMENT&subjectId=${encodeURIComponent(targetPlanId)}`,
        controller.signal,
      ),
      fetch(`/api/v1/teaching-plans/${encodeURIComponent(targetPlanId)}/illustrated-lessons/latest`, {
        credentials: 'include', signal: controller.signal,
      }),
    ])
    if (!isCurrentRead(request, targetPlanId, controller, activeVisualController)) return
    if (response?.status === 401 || lessonResponse.status === 401) {
      notifyLoginRequired()
      return
    }
    if (!lessonResponse.ok) throw new Error(t('lesson.generation.refreshFailed'))
    const incomingLesson = await lessonResponse.json() as IllustratedLesson
    const incomingRun = response?.ok ? await response.json() as TeachingRunProgress : null
    if (!isCurrentRead(request, targetPlanId, controller, activeVisualController)) return
    if (!responseMatchesPlan(targetPlanId, incomingLesson, incomingRun)) {
      throw new Error()
    }
    sourceLesson.value = acceptProgressiveLesson(sourceLesson.value, incomingLesson)
    lesson.value = sourceLesson.value
    await applySelectedLocale(targetPlanId, request)
    if (!isCurrentRead(request, targetPlanId, controller, activeVisualController)) return
    if (incomingRun) visualEnrichmentRun.value = incomingRun
  } catch {
    if (!isCurrentRead(request, targetPlanId, controller, activeVisualController) || controller.signal.aborted) return
    controller.abort()
    retryDelay = 5000
  } finally {
    if (isCurrentRead(request, targetPlanId, controller, activeVisualController)) {
      activeVisualController = null
      visualPolling.schedule(retryDelay)
    }
  }
}

function terminalGenerationMessage(state: string, availableSectionCount: number) {
  if (availableSectionCount === 0) return t('lesson.generation.finished.noReadable')
  if (state === 'COMPLETED') return t('lesson.generation.finished.complete')
  if (state === 'INSUFFICIENT_EVIDENCE' || state === 'DEGRADED') {
    return t('lesson.generation.finished.incomplete')
  }
  if (state === 'FAILED') return t('lesson.generation.finished.failed')
  return ''
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
    if (runResponse.status === 401 || lessonResponse.status === 401) {
      notifyLoginRequired()
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
    generationStatusUnknown.value = false
    generationRefreshError.value = ''

    if (lessonReplaced) {
      restoreLessonReaderProgress()
      selectSection(progress.value.currentIndex)
    } else if (acceptedLesson.sections.length > previousCount) {
      selectSection(Math.min(progress.value.currentIndex, acceptedLesson.sections.length - 1))
    }

    if (wasActive && !generationActive.value) {
      const terminalState = acceptedRun?.run.state ?? ''
      generationFinishedMessage.value = terminalGenerationMessage(terminalState, acceptedLesson.sections.length)
      await loadSupportingContent(targetPlanId, request)
      if (!isCurrentLessonLoad(request, targetPlanId)) return
      await refreshVisualEnrichment()
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
  if (visualEnrichmentActive.value) visualPolling.schedule(0)
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
        :available-section-count="lesson?.sections.length ?? 0"
        :total-section-count="plan?.sections.length ?? null"
        :elapsed="generationElapsed"
        :processed-chapter-count="processedGenerationChapters"
        :supported-chapter-count="supportedGenerationChapters"
        :progress-width="generationProgressWidth"
        :remaining-time="generationRemainingTime"
        :activities="recentGenerationActivities"
        :refresh-failed="Boolean(generationRefreshError)"
        :finished-message="generationFinishedMessage"
        :finished-complete="teachingRun?.run.state === 'COMPLETED' && Boolean(lesson?.sections.length)"
      />

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
