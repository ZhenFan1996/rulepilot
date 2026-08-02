<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import { notifyLoginRequired } from '@/lib/authSession'
import LessonComprehensionPanel from '@/components/LessonComprehensionPanel.vue'
import LessonGenerationStatus from '@/components/LessonGenerationStatus.vue'
import LessonNarrationPanel from '@/components/LessonNarrationPanel.vue'
import LessonOfflineKnowledgePanel from '@/components/LessonOfflineKnowledgePanel.vue'
import LessonReaderStateSurface from '@/components/LessonReaderStateSurface.vue'
import RulebookIconGlossaryPanel from '@/components/RulebookIconGlossaryPanel.vue'
import LessonVideoPanel from '@/components/LessonVideoPanel.vue'
import type { CsrfResponse } from '@/composables/useLessonAnswers'
import {
  useLessonSupportingContent,
  type MediaWarningCode,
} from '@/composables/useLessonSupportingContent'
import { useLessonNarrationPlayback, type LessonMediaMode } from '@/composables/useLessonNarrationPlayback'
import { useLessonLocalization } from '@/composables/useLessonLocalization'
import { useLessonGenerationPresentation } from '@/composables/useLessonGenerationPresentation'
import { useConditionalPolling } from '@/composables/useConditionalPolling'
import { useLessonComprehensionFeedback } from '@/composables/useLessonComprehensionFeedback'
import { useLessonReaderProgress } from '@/composables/useLessonReaderProgress'
import { acceptProgressiveLesson } from '@/lib/liveLesson'
import { loadOfflineKnowledge, type OfflineKnowledgeEntry } from '@/lib/offlineKnowledge'
import {
  mergeTeachingRunProgress,
  teachingActivityCursor,
  type TeachingRunProgress,
} from '@/lib/teachingProgress'
import { useLocale } from '@/lib/locale'
import {
  parseRulebookIconGlossary,
  type RulebookIconGlossary,
} from '@/lib/rulebookIconGlossary'

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

type MediaMode = LessonMediaMode
const availableMediaModes: MediaMode[] = ['TEXT', 'AUDIO', 'VIDEO']

const route = useRoute()
const router = useRouter()
const { locale, t } = useLocale()
const loading = ref(true)
const errorMessage = ref('')
const online = ref(navigator.onLine)
const plan = ref<TeachingPlan | null>(null)
const lesson = ref<IllustratedLesson | null>(null)
const sourceLesson = ref<IllustratedLesson | null>(null)
const mediaMode = ref<MediaMode>('TEXT')
const offlineKnowledge = ref<OfflineKnowledgeEntry[]>([])
const teachingRun = ref<TeachingRunProgress | null>(null)
const visualEnrichmentRun = ref<TeachingRunProgress | null>(null)
const iconGlossary = ref<RulebookIconGlossary | null>(null)
const iconGlossaryLoading = ref(false)
const iconGlossaryError = ref('')
const iconGlossaryStarting = ref(false)
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
  narration,
  video,
  mediaWarningCodes,
  audioAvailable,
  narrationDurationMillis,
  narrationCues,
  narrationMillis,
  narrationPlaying,
  narrationRestoreTarget,
  addMediaWarning,
  clearSupportingContent,
  loadSupportingContent: loadSupportingContentForCurrentLesson,
} = useLessonSupportingContent()

const mediaWarnings = computed(() => mediaWarningCodes.value.map(mediaWarningMessage))

const planId = computed(() => String(route.params.planId ?? ''))
const {
  progress,
  reset: resetLessonProgress,
  restore: restoreLessonReaderProgress,
  selectSection,
  synchronizeChapter,
} = useLessonReaderProgress({
  lesson,
  onSectionSelected: (index) => {
    seekToChapter(index)
  },
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

const currentNarration = computed(() => narration.value?.chapters[progress.value.currentIndex] ?? null)
const currentVideoChapter = computed(() => video.value?.chapters[progress.value.currentIndex] ?? null)
const narrationAudioUrl = computed(() => `/api/v1/teaching-plans/${planId.value}/narration/audio`)
const activeVideoFrame = computed(() => {
  const chapter = currentVideoChapter.value
  if (!chapter) return null
  return (
    chapter.frames.find(
      (frame) => narrationMillis.value >= frame.startMillis && narrationMillis.value < frame.endMillis,
    ) ?? chapter.frames[0] ?? null
  )
})
const {
  narrationPlayer,
  narrationRate,
  activeCue,
  narrationPositionKey,
  onNarrationLoaded,
  onNarrationTimeUpdate,
  onNarrationSeeked,
  onNarrationPaused,
  onNarrationError,
  toggleNarration,
  seekToChapter,
  seekToSegment,
  replayCurrentSegment,
  cycleNarrationRate,
  seekNarration,
  formatDuration,
} = useLessonNarrationPlayback({
  lessonId: computed(() => lesson.value?.id ?? null),
  durationMillis: narrationDurationMillis,
  cues: narrationCues,
  narrationMillis,
  narrationPlaying,
  narrationRestoreTarget,
  audioAvailable,
  mediaMode,
  currentSectionIndex: computed(() => progress.value.currentIndex),
  synchronizeChapter: (chapterIndex) => {
    synchronizeChapter(chapterIndex)
  },
  addWarning: addMediaWarning,
  audioFailureWarning: 'AUDIO_LOAD_FAILED',
})

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
  narrationPlayer.value?.pause()
  plan.value = null
  lesson.value = null
  sourceLesson.value = null
  resetLessonLocalization()
  resetLessonProgress()
  offlineKnowledge.value = []
  mediaMode.value = 'TEXT'
  narrationPlaying.value = false
  narrationRestoreTarget.value = null
}

async function optionalFetch(url: string) {
  try {
    return await fetch(url, { credentials: 'include' })
  } catch {
    return null
  }
}

async function loadSupportingContent(targetPlanId: string, request = latestLessonLoad) {
  await loadSupportingContentForCurrentLesson({
    planId: targetPlanId,
    isCurrent: () => isCurrentLessonLoad(request, targetPlanId),
    narrationPositionKey,
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
  iconGlossary.value = null
  iconGlossaryLoading.value = true
  iconGlossaryError.value = ''
  iconGlossaryStarting.value = false
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
    const [planResponse, lessonResponse, runResponse, visualRunResponse, iconGlossaryResponse] = await Promise.all([
      fetch(`/api/v1/teaching-plans/${targetPlanId}`, { credentials: 'include' }),
      fetch(`/api/v1/teaching-plans/${targetPlanId}/illustrated-lessons/latest`, { credentials: 'include' }),
      optionalFetch(`/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(targetPlanId)}`),
      optionalFetch(`/api/v1/assistant-runs/latest?mode=VISUAL_ENRICHMENT&subjectId=${encodeURIComponent(targetPlanId)}`),
      optionalFetch(iconGlossaryEndpoint(targetPlanId)),
    ])
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    if (planResponse.status === 401 || lessonResponse.status === 401 || runResponse?.status === 401 || visualRunResponse?.status === 401 || iconGlossaryResponse?.status === 401) {
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
    sourceLesson.value = loadedLesson
    lesson.value = sourceLesson.value
    await applySelectedLocale(targetPlanId, request)
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    teachingRun.value = loadedRun
    visualEnrichmentRun.value = loadedVisualRun
    await acceptIconGlossaryResponse(iconGlossaryResponse, targetPlanId, request)
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
    errorMessage.value = t('lesson.reader.error.load')
  } finally {
    if (isCurrentLessonLoad(request, targetPlanId)) {
      loading.value = false
      iconGlossaryLoading.value = false
    }
  }
}

async function refreshVisualEnrichment() {
  if (!online.value || lessonViewDisposed) return
  const targetPlanId = planId.value
  const request = latestLessonLoad
  let retryDelay = 2500
  try {
    const [response, glossaryResponse] = await Promise.all([
      optionalFetch(`/api/v1/assistant-runs/latest?mode=VISUAL_ENRICHMENT&subjectId=${encodeURIComponent(targetPlanId)}`),
      optionalFetch(iconGlossaryEndpoint(targetPlanId)),
    ])
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    if (response?.status === 401 || glossaryResponse?.status === 401) {
      notifyLoginRequired()
      return
    }
    if (response?.ok) {
      const incomingRun = await response.json() as TeachingRunProgress
      if (!isCurrentLessonLoad(request, targetPlanId)) return
      visualEnrichmentRun.value = incomingRun
    }
    await acceptIconGlossaryResponse(glossaryResponse, targetPlanId, request)
  } catch {
    retryDelay = 5000
  } finally {
    if (isCurrentLessonLoad(request, targetPlanId)) visualPolling.schedule(retryDelay)
  }
}

function iconGlossaryEndpoint(targetPlanId = planId.value) {
  return `/api/v1/teaching-plans/${encodeURIComponent(targetPlanId)}/illustrated-lessons/latest/icon-glossary`
}

function iconGlossaryImageUrl(occurrenceId: string) {
  return `${iconGlossaryEndpoint()}/icons/${encodeURIComponent(occurrenceId)}/image`
}

async function acceptIconGlossaryResponse(
  response: Response | null,
  targetPlanId: string,
  request = latestLessonLoad,
) {
  if (!isCurrentLessonLoad(request, targetPlanId)) return
  if (!response || !response.ok) {
    iconGlossaryError.value = t('iconGlossary.error.load')
    return
  }
  try {
    const received = parseRulebookIconGlossary(await response.json())
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    iconGlossary.value = received
    iconGlossaryError.value = ''
  } catch {
    if (isCurrentLessonLoad(request, targetPlanId)) {
      iconGlossaryError.value = t('iconGlossary.error.load')
    }
  } finally {
    if (isCurrentLessonLoad(request, targetPlanId)) iconGlossaryLoading.value = false
  }
}

async function reloadIconGlossary() {
  const targetPlanId = planId.value
  const request = latestLessonLoad
  iconGlossaryLoading.value = iconGlossary.value === null
  iconGlossaryError.value = ''
  const response = await optionalFetch(iconGlossaryEndpoint(targetPlanId))
  if (response?.status === 401) {
    notifyLoginRequired()
    iconGlossaryError.value = t('lesson.reader.error.loginRequired')
    iconGlossaryLoading.value = false
    return
  }
  await acceptIconGlossaryResponse(response, targetPlanId, request)
}

async function generateIconGlossary() {
  if (!online.value || iconGlossaryStarting.value) return
  const targetPlanId = planId.value
  iconGlossaryStarting.value = true
  iconGlossaryError.value = ''
  try {
    const csrf = await csrfToken()
    const response = await fetch(iconGlossaryEndpoint(targetPlanId), {
      method: 'POST',
      credentials: 'include',
      headers: { [csrf.headerName]: csrf.token },
    })
    if (response.status === 401) {
      notifyLoginRequired()
      throw new Error(t('lesson.reader.error.loginRequired'))
    }
    if (!response.ok) throw new Error(t('iconGlossary.error.generate'))
    await refreshVisualEnrichment()
    visualPolling.schedule(1500)
  } catch (error) {
    iconGlossaryError.value = error instanceof Error ? error.message : t('iconGlossary.error.generate')
  } finally {
    iconGlossaryStarting.value = false
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

function visualKindLabel(kind: LessonSection['visualKind']) {
  return {
    REFERENCE_CARD: t('lesson.chapter.visualKind.reference'),
    TABLE_LAYOUT: t('lesson.chapter.visualKind.layout'),
    FLOW_DIAGRAM: t('lesson.chapter.visualKind.flow'),
    SCOREBOARD: t('lesson.chapter.visualKind.scoreboard'),
  }[kind]
}

function mediaWarningMessage(code: MediaWarningCode) {
  return {
    QUALITY_UNAVAILABLE: t('lesson.reader.media.qualityUnavailable'),
    AUDIO_UNAVAILABLE: t('lesson.reader.media.audioUnavailable'),
    VIDEO_UNAVAILABLE: t('lesson.reader.media.videoUnavailable'),
    AUDIO_LOAD_FAILED: t('lesson.reader.media.audioLoadFailed'),
    SOURCE_LANGUAGE_MEDIA: t('lesson.reader.media.sourceLanguageOnly'),
  }[code]
}

function selectMediaMode(mode: MediaMode) {
  if (!mediaModeAvailable(mode)) return
  mediaMode.value = mode
  if (mode === 'TEXT') narrationPlayer.value?.pause()
}

function mediaModeAvailable(mode: MediaMode) {
  if (mode === 'AUDIO') return narration.value !== null && audioAvailable.value
  if (mode === 'VIDEO') return video.value !== null
  return true
}

function mediaModeLabel(mode: MediaMode) {
  return mode === 'TEXT'
    ? t('lesson.sidebar.media.text')
    : mode === 'AUDIO'
      ? t('lesson.sidebar.media.audio')
      : t('lesson.sidebar.media.video')
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

watch(locale, () => {
  if (locale.value === 'en' && mediaMode.value !== 'TEXT') {
    mediaMode.value = 'TEXT'
    addMediaWarning('SOURCE_LANGUAGE_MEDIA')
  }
  void applySelectedLocale()
})

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
    <div class="min-h-screen overflow-x-hidden bg-canvas pb-24 text-ink lg:pb-10">
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
      <div v-if="mediaWarnings.length" class="bg-amber-50 px-5 py-3 text-center text-sm font-semibold text-amber-900" role="status">
        <p v-for="warning in mediaWarnings" :key="warning">{{ warning }}</p>
      </div>
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

      <article v-else class="mx-auto max-w-4xl px-5 py-9 sm:px-8 lg:py-14" data-testid="private-lesson-reader">
        <header class="border-b border-ink/10 pb-8">
          <p class="text-sm font-semibold text-copper">{{ t('lesson.reader.guideEyebrow') }}</p>
          <h1 class="mt-2 font-display text-4xl font-semibold tracking-tight sm:text-5xl">{{ plan?.gameTitle }}</h1>
          <p class="mt-4 max-w-2xl leading-7 text-ink/60">{{ t('lesson.reader.guideDescription') }}</p>
        </header>

        <RulebookIconGlossaryPanel
          :glossary="iconGlossary"
          :loading="iconGlossaryLoading"
          :error-message="iconGlossaryError"
          :can-generate="true"
          :generating="iconGlossaryStarting"
          :online="online"
          :image-url="iconGlossaryImageUrl"
          @generate="generateIconGlossary"
          @retry="reloadIconGlossary"
        />

        <nav class="mt-8 flex gap-2 overflow-x-auto border-b border-ink/10 pb-5" :aria-label="t('lesson.reader.chapterDirectory')">
          <a v-for="section in lesson.sections" :key="section.position" :href="`#private-chapter-${section.position}`" class="min-h-10 shrink-0 rounded-xl border border-ink/12 bg-paper px-3 py-2 text-sm font-semibold text-ink/65 transition hover:border-indigo/35 hover:text-indigo">{{ t('public.question.chapter', { position: section.position, title: section.title }) }}</a>
        </nav>

        <section v-for="section in lesson.sections" :id="`private-chapter-${section.position}`" :key="section.position" class="scroll-mt-24 border-b border-ink/10 py-10">
          <p class="text-sm font-semibold text-copper">{{ t('public.chapter', { position: section.position }) }}</p>
          <h2 class="mt-2 font-display text-3xl font-semibold tracking-tight">{{ section.title }}</h2>
          <p v-if="section.visualCaption" class="mt-3 max-w-2xl leading-7 text-ink/60">{{ section.visualCaption }}</p>

          <ol class="mt-7 space-y-5">
            <li v-for="step in section.steps" :key="`${section.position}-${step.position}`" class="rounded-xl border border-ink/10 bg-paper p-5 shadow-sm sm:p-6" data-testid="private-rule-step">
              <div class="flex gap-4">
                <span class="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-copper/15 text-sm font-bold text-copper">{{ step.position }}</span>
                <div class="min-w-0 flex-1">
                  <h3 class="font-display text-xl font-semibold">{{ step.heading }}</h3>
                  <p class="mt-2 leading-7 text-ink/75">{{ step.text }}</p>
                  <figure v-if="step.visualFocus" class="mt-5 overflow-hidden rounded-2xl border border-indigo/15 bg-canvas">
                    <figcaption class="border-b border-indigo/10 bg-indigo/[0.045] px-4 py-3">
                      <p class="text-xs font-bold uppercase tracking-[0.12em] text-indigo">{{ t('lesson.chapter.visual.observationEyebrow') }}</p>
                      <p class="mt-1 text-sm leading-6 text-ink/70">{{ step.visualFocus.visibleDescription || step.visualFocus.label }}</p>
                    </figcaption>
                    <a :href="pageImageUrl(step.visualFocus.pageNumber)" target="_blank" rel="noopener" class="block">
                      <img :src="focusedPageImageUrl(step.visualFocus)" :alt="t('lesson.chapter.visual.alt', { label: step.visualFocus.label, page: step.visualFocus.pageNumber })" class="max-h-96 w-full object-contain" loading="lazy">
                      <span class="block border-t border-ink/10 px-3 py-2 text-sm font-semibold text-indigo">{{ t('public.step.openSource', { label: step.visualFocus.label }) }}</span>
                    </a>
                  </figure>
                  <a v-if="step.sourcePages.length" :href="pageImageUrl(step.sourcePages[0])" target="_blank" rel="noopener" class="mt-4 inline-flex text-sm text-ink/45 transition hover:text-indigo">{{ t('lesson.chapter.source', { pages: step.sourcePages.join(locale === 'en' ? ', ' : '、') }) }}</a>
                </div>
              </div>
            </li>
          </ol>
        </section>

        <LessonComprehensionPanel
          v-if="!generationActive && (comprehension || comprehensionError)"
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

        <details v-if="!generationActive && (audioAvailable || video)" class="mt-8 rounded-3xl border border-ink/10 bg-paper p-5 sm:p-6">
          <summary class="cursor-pointer font-semibold text-ink">{{ t('lesson.reader.mediaOptional') }}</summary>
          <div class="mt-4 flex flex-wrap gap-2">
            <button v-for="mode in availableMediaModes" :key="mode" type="button" :disabled="!mediaModeAvailable(mode)" class="min-h-10 rounded-xl border px-3 text-sm font-semibold disabled:opacity-35" :class="mediaMode === mode ? 'border-indigo bg-indigo/8 text-indigo' : 'border-ink/12 text-ink/60'" @click="selectMediaMode(mode)">{{ mediaModeLabel(mode) }}</button>
          </div>
          <audio v-if="narration" ref="narrationPlayer" class="hidden" preload="metadata" :src="narrationAudioUrl" @loadedmetadata="onNarrationLoaded" @seeked="onNarrationSeeked" @timeupdate="onNarrationTimeUpdate" @play="narrationPlaying = true" @pause="onNarrationPaused" @ended="narrationPlaying = false" @error="onNarrationError">{{ t('lesson.reader.audio.unsupported') }}</audio>
          <LessonVideoPanel v-if="mediaMode === 'VIDEO'" :chapter="currentVideoChapter" :active-frame="activeVideoFrame" :chapters="video?.chapters ?? []" :active-chapter-index="progress.currentIndex" :duration-millis="video?.durationMillis ?? 0" :playback-millis="narrationMillis" :playing="narrationPlaying" :playback-rate="narrationRate" :audio-available="audioAvailable" :format-duration="formatDuration" :visual-kind-label="visualKindLabel" @seek="seekNarration" @toggle-playback="toggleNarration" @replay="replayCurrentSegment" @cycle-rate="cycleNarrationRate" @select-chapter="selectSection" />
          <LessonNarrationPanel :visible="mediaMode === 'AUDIO'" :chapter="currentNarration" :active-cue="activeCue" :duration-millis="narrationDurationMillis" :playback-millis="narrationMillis" :playing="narrationPlaying" :playback-rate="narrationRate" :format-duration="formatDuration" @seek-segment="seekToSegment" @seek="seekNarration" @toggle-playback="toggleNarration" @replay="replayCurrentSegment" @cycle-rate="cycleNarrationRate" />
        </details>
      </article>
    </div>
  </AppShell>
</template>
