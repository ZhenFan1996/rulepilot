<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import CatalogGameAttribution from '@/components/CatalogGameAttribution.vue'
import LessonChapterList from '@/components/LessonChapterList.vue'
import { notifyLoginRequired } from '@/lib/authSession'
import LessonComprehensionPanel from '@/components/LessonComprehensionPanel.vue'
import LessonGenerationStatus from '@/components/LessonGenerationStatus.vue'
import LessonGuideHero from '@/components/LessonGuideHero.vue'
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

const {
  status: localizationStatus,
  preparing: localizationPreparing,
  applySelectedLocale,
  prepareEnglishGuide,
  reset: resetLessonLocalization,
  dispose: disposeLessonLocalization,
} = useLessonLocalization({
  locale,
  planId,
  sourceLesson,
  displayedLesson: lesson,
  currentRequest: () => latestLessonLoad,
  isCurrent: (request, targetPlanId) => isCurrentLessonLoad(request, targetPlanId),
  requestLogin: async () => notifyLoginRequired(),
  csrfToken,
})

function resetLessonReader() {
  plan.value = null
  lesson.value = null
  sourceLesson.value = null
  catalogPresentation.value = null
  catalogCoverUnavailable.value = false
  resetLessonLocalization()
  resetLessonProgress()
  offlineKnowledge.value = []
}

async function optionalFetch(url: string) {
  try {
    return await fetch(url, { credentials: 'include' })
  } catch {
    return null
  }
}

async function loadCatalogPresentation(targetPlanId: string) {
  try {
    const response = await fetch(
      `/api/v1/teaching-plans/${encodeURIComponent(targetPlanId)}/catalog-presentation`,
      { credentials: 'include' },
    )
    return response.ok ? await response.json() as CatalogGamePresentation : null
  } catch {
    return null
  }
}

async function loadSupportingContent(targetPlanId: string, request = latestLessonLoad) {
  await loadSupportingContentForCurrentLesson({
    planId: targetPlanId,
    isCurrent: () => isCurrentLessonLoad(request, targetPlanId),
    requestLogin: async () => notifyLoginRequired(),
  })
}

async function loadLesson() {
  const targetPlanId = planId.value
  const request = ++latestLessonLoad
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
  try {
    const [planResponse, lessonResponse, runResponse, visualRunResponse, loadedCatalogPresentation] = await Promise.all([
      fetch(`/api/v1/teaching-plans/${targetPlanId}`, { credentials: 'include' }),
      fetch(`/api/v1/teaching-plans/${targetPlanId}/illustrated-lessons/latest`, { credentials: 'include' }),
      optionalFetch(`/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(targetPlanId)}`),
      optionalFetch(`/api/v1/assistant-runs/latest?mode=VISUAL_ENRICHMENT&subjectId=${encodeURIComponent(targetPlanId)}`),
      loadCatalogPresentation(targetPlanId),
    ])
    if (!isCurrentLessonLoad(request, targetPlanId)) return
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
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    plan.value = loadedPlan
    catalogPresentation.value = loadedCatalogPresentation
    sourceLesson.value = loadedLesson
    lesson.value = sourceLesson.value
    await applySelectedLocale(targetPlanId, request)
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    teachingRun.value = loadedRun
    visualEnrichmentRun.value = loadedVisualRun
    generationStatusUnknown.value = runResponse === null || (!runResponse.ok && runResponse.status !== 404)
    if (generationStatusUnknown.value) generationRefreshError.value = t('lesson.generation.refreshFailed')
    localStorage.setItem('rulepilot:last-plan-id', targetPlanId)
    restoreLessonReaderProgress()
    if (generationActive.value) generationPolling.schedule()
    else await loadSupportingContent(targetPlanId, request)
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    if (visualEnrichmentActive.value) visualPolling.schedule()
  } catch {
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    if (offlineKnowledge.value.length) {
      online.value = false
    } else {
      updateOnlineStatus()
      errorMessage.value = t('lesson.reader.error.load')
    }
  } finally {
    if (isCurrentLessonLoad(request, targetPlanId)) loading.value = false
  }
}

async function refreshVisualEnrichment() {
  if (!online.value || lessonViewDisposed) return
  const targetPlanId = planId.value
  const request = latestLessonLoad
  let retryDelay = 2500
  try {
    const [response, lessonResponse] = await Promise.all([
      optionalFetch(`/api/v1/assistant-runs/latest?mode=VISUAL_ENRICHMENT&subjectId=${encodeURIComponent(targetPlanId)}`),
      fetch(`/api/v1/teaching-plans/${targetPlanId}/illustrated-lessons/latest`, { credentials: 'include' }),
    ])
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    if (response?.status === 401 || lessonResponse.status === 401) {
      notifyLoginRequired()
      return
    }
    if (!lessonResponse.ok) throw new Error(t('lesson.generation.refreshFailed'))
    const incomingLesson = await lessonResponse.json() as IllustratedLesson
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    sourceLesson.value = acceptProgressiveLesson(sourceLesson.value, incomingLesson)
    lesson.value = sourceLesson.value
    await applySelectedLocale(targetPlanId, request)
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    if (response?.ok) {
      const incomingRun = await response.json() as TeachingRunProgress
      if (!isCurrentLessonLoad(request, targetPlanId)) return
      visualEnrichmentRun.value = incomingRun
    }
  } catch {
    retryDelay = 5000
  } finally {
    if (isCurrentLessonLoad(request, targetPlanId)) visualPolling.schedule(retryDelay)
  }
}

function terminalGenerationMessage(state: string) {
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
  const wasActive = generationActive.value
  const activityCursor = teachingActivityCursor(teachingRun.value)
  try {
    const [runResponse, lessonResponse] = await Promise.all([
      fetch(`/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(targetPlanId)}${activityCursor}`, { credentials: 'include' }),
      fetch(`/api/v1/teaching-plans/${targetPlanId}/illustrated-lessons/latest`, { credentials: 'include' }),
    ])
    if (!isCurrentLessonLoad(request, targetPlanId)) return
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
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    const acceptedRun = mergeTeachingRunProgress(teachingRun.value, incomingRun)
    const previousLesson = sourceLesson.value
    const previousCount = previousLesson?.sections.length ?? 0
    const acceptedLesson = acceptProgressiveLesson(previousLesson, incomingLesson)
    const lessonReplaced = previousLesson !== null && acceptedLesson.id !== previousLesson.id
    sourceLesson.value = acceptedLesson
    lesson.value = acceptedLesson
    await applySelectedLocale(targetPlanId, request)
    if (!isCurrentLessonLoad(request, targetPlanId)) return
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
      generationFinishedMessage.value = terminalGenerationMessage(acceptedRun?.run.state ?? '')
      await loadSupportingContent(targetPlanId, request)
      if (!isCurrentLessonLoad(request, targetPlanId)) return
      await refreshVisualEnrichment()
    }
  } catch {
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    generationRefreshError.value = t('lesson.generation.refreshFailed')
  } finally {
    if (isCurrentLessonLoad(request, targetPlanId)) generationPolling.schedule()
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
  if (!online.value) refreshOfflineKnowledge()
  if (online.value && generationActive.value) generationPolling.schedule(0)
  else if (!online.value) generationPolling.clear()
  if (online.value && visualEnrichmentActive.value) visualPolling.schedule(0)
  else if (!online.value) visualPolling.clear()
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
    <div data-testid="private-lesson-surface" class="min-h-screen bg-canvas pb-24 text-ink lg:pb-10">
      <header class="sticky top-0 z-20 border-b border-ink/10 bg-canvas/90 backdrop-blur">
        <div class="mx-auto flex max-w-4xl items-center justify-between gap-4 px-5 py-4 sm:px-8">
          <RouterLink :to="{ name: 'lessons' }" class="text-sm font-semibold text-indigo">← {{ t('lesson.reader.back') }}</RouterLink>
          <div class="flex items-center gap-3 sm:gap-4">
            <RouterLink v-if="lesson" :to="{ name: 'public-lesson', params: { planId } }" class="text-sm font-semibold text-indigo">{{ t('lesson.reader.public') }}</RouterLink>
            <RouterLink v-if="lesson" :to="{ name: 'lesson-questions', params: { planId } }" class="inline-flex min-h-10 items-center rounded-xl bg-indigo px-4 text-sm font-semibold text-white shadow-sm transition hover:-translate-y-0.5">{{ t('questions.open') }}</RouterLink>
          </div>
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
        :model-call-count="teachingRun?.budget.usedModelCalls ?? 0"
        :progress-width="generationProgressWidth"
        :remaining-time="generationRemainingTime"
        :activities="recentGenerationActivities"
        :refresh-failed="Boolean(generationRefreshError)"
        :finished-message="generationFinishedMessage"
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
            <RouterLink :to="{ name: 'lesson-questions', params: { planId } }" class="inline-flex min-h-11 items-center rounded-xl bg-[#e2b85e] px-4 text-sm font-bold text-[#20302d] shadow-sm transition hover:-translate-y-0.5">{{ t('questions.open') }}</RouterLink>
            <RouterLink :to="{ name: 'public-lesson', params: { planId } }" class="inline-flex min-h-11 items-center rounded-xl border border-paper/25 bg-paper/10 px-4 text-sm font-semibold text-paper">{{ t('lesson.reader.public') }}</RouterLink>
          </template>
        </LessonGuideHero>

        <CatalogGameAttribution v-if="catalogPresentation" :presentation="catalogPresentation" />

        <LessonChapterList
          :sections="lesson.sections"
          id-prefix="private-chapter"
          :page-image-url="pageImageUrl"
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
