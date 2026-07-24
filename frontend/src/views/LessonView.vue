<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import CardOcrCapture from '@/components/CardOcrCapture.vue'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import LessonAnswerPanel from '@/components/LessonAnswerPanel.vue'
import LessonChapterContent from '@/components/LessonChapterContent.vue'
import LessonComprehensionPanel from '@/components/LessonComprehensionPanel.vue'
import LessonGenerationStatus from '@/components/LessonGenerationStatus.vue'
import LessonNarrationPanel from '@/components/LessonNarrationPanel.vue'
import LessonReaderChapterHeader from '@/components/LessonReaderChapterHeader.vue'
import LessonReaderControls from '@/components/LessonReaderControls.vue'
import LessonOfflineKnowledgePanel from '@/components/LessonOfflineKnowledgePanel.vue'
import LessonReaderSidebar from '@/components/LessonReaderSidebar.vue'
import LessonReaderStateSurface from '@/components/LessonReaderStateSurface.vue'
import LessonVideoPanel from '@/components/LessonVideoPanel.vue'
import {
  useLessonAnswers,
  type CsrfResponse,
  type LearningIntent,
} from '@/composables/useLessonAnswers'
import { buildCardQuestion } from '@/lib/cardOcr'
import {
  useLessonSupportingContent,
  type MediaWarningCode,
} from '@/composables/useLessonSupportingContent'
import { useLessonNarrationPlayback, type LessonMediaMode } from '@/composables/useLessonNarrationPlayback'
import { useLessonLocalization } from '@/composables/useLessonLocalization'
import { useConfirmedRuling } from '@/composables/useConfirmedRuling'
import { useLessonGenerationPresentation } from '@/composables/useLessonGenerationPresentation'
import { useConditionalPolling } from '@/composables/useConditionalPolling'
import { useLessonComprehensionFeedback } from '@/composables/useLessonComprehensionFeedback'
import { acceptProgressiveLesson } from '@/lib/liveLesson'
import {
  finishSection,
  initialLessonProgress,
  restoreLessonProgress,
  type LessonProgress,
} from '@/lib/lessonProgress'
import {
  cacheOfflineAnswer,
  cacheOfflineRuling,
  loadOfflineKnowledge,
  type OfflineKnowledgeEntry,
} from '@/lib/offlineKnowledge'
import {
  mergeTeachingRunProgress,
  teachingActivityCursor,
  type TeachingRunProgress,
} from '@/lib/teachingProgress'
import { mergeVoiceQuestion } from '@/lib/voiceQuestion'
import { useLocale } from '@/lib/locale'

interface TeachingPlan {
  id: string
  documentVersionId: string
  playerCount: number
  beginnerCount: number
  durationMinutes: number
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
      x: number
      y: number
      width: number
      height: number
    } | null
  }>
}

type MediaMode = LessonMediaMode

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
const progress = ref<LessonProgress>(initialLessonProgress())
const offlineKnowledge = ref<OfflineKnowledgeEntry[]>([])
const cardOcrOpen = ref(false)
const resumingLesson = ref(false)
const teachingRun = ref<TeachingRunProgress | null>(null)
const visualEnrichmentRun = ref<TeachingRunProgress | null>(null)
const generationStatusUnknown = ref(false)
const generationRefreshError = ref('')
const generationFinishedMessage = ref('')
const waitingForNextChapter = ref(false)
const generationNow = ref(Date.now())
let generationClockTimer: ReturnType<typeof setInterval> | undefined
let lessonViewDisposed = false
let latestLessonLoad = 0

const {
  quality,
  comprehension,
  comprehensionSaving,
  comprehensionError,
  narration,
  video,
  mediaConsistency,
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
const currentSection = computed(() => lesson.value?.sections[progress.value.currentIndex] ?? null)
const {
  question,
  answer,
  answeredQuestion,
  answerTurns,
  activeLearningIntent,
  answerLoading,
  answerError,
  clearAnswerFeedback,
  resetConversation,
  submitQuestion,
} = useLessonAnswers({
  currentContext: () => {
    const activePlan = plan.value
    const section = currentSection.value
    if (!activePlan || !section || !online.value) return null
    return {
      planId: planId.value,
      documentVersionId: activePlan.documentVersionId,
      playerCount: activePlan.playerCount,
      section,
      locale: locale.value,
    }
  },
  currentLessonRequest: () => latestLessonLoad,
  isCurrentLessonLoad,
  requestLogin: () => router.push({ name: 'login' }),
  onReceived: (context, text, received) => {
    if (received.status === 'ANSWERED') {
      cacheOfflineAnswer(context.planId, text, context.section.title, received)
      refreshOfflineKnowledge()
    }
    if (received.confirmedRulingId !== null && received.confirmedRulingVersion !== null) {
      applyRuling({
        id: received.confirmedRulingId,
        shortVerdict: received.shortVerdict,
        explanation: received.explanation,
        citations: received.citations,
        exceptions: received.exceptions,
        confidence: received.confidence,
        status: 'CONFIRMED',
        version: received.confirmedRulingVersion,
      })
    } else {
      resetRuling()
    }
  },
})

const {
  ruling,
  saving: rulingSaving,
  error: rulingError,
  conflict: rulingConflict,
  editing: editingRuling,
  editedVerdict,
  editedExplanation,
  applyRuling,
  confirmAnswer,
  saveRulingRevision,
  reloadRuling,
  reset: resetRuling,
} = useConfirmedRuling({
  documentVersionId: computed(() => plan.value?.documentVersionId ?? null),
  answer,
  answeredQuestion,
  currentSectionTitle: () => currentSection.value?.title ?? t('lesson.answer.sectionFallback'),
  csrfToken,
  onApplied: (value, answered, sectionTitle) => {
    cacheOfflineRuling(planId.value, answered, sectionTitle, value)
    refreshOfflineKnowledge()
  },
  messages: {
    createFailed: () => t('lesson.answer.ruling.createFailed'),
    createRequestFailed: () => t('lesson.answer.ruling.createRequestFailed'),
    updateFailed: () => t('lesson.answer.ruling.updateFailed'),
    updateRequestFailed: () => t('lesson.answer.ruling.updateRequestFailed'),
    reloadFailed: () => t('lesson.answer.ruling.reloadFailed'),
    reloadRequestFailed: () => t('lesson.answer.ruling.reloadRequestFailed'),
  },
})
const chapterLeadStep = computed(() => {
  const steps = currentSection.value?.steps ?? []
  return steps.find((step) => step.kind === 'UNDERSTAND')
    ?? steps.find((step) => ['DO', 'FLOW'].includes(step.kind))
    ?? steps[0]
    ?? null
})
const chapterPathSteps = computed(() => (currentSection.value?.steps ?? []).filter((step) =>
  step.position !== chapterLeadStep.value?.position
  && ['UNDERSTAND', 'DO', 'FLOW', 'VISUAL'].includes(step.kind),
))
const chapterSupportSteps = computed(() => (currentSection.value?.steps ?? []).filter((step) =>
  step.position !== chapterLeadStep.value?.position
  && ['WATCH', 'EXAMPLE', 'LEDGER'].includes(step.kind),
))
const chapterCheckSteps = computed(() => (currentSection.value?.steps ?? []).filter((step) =>
  step.position !== chapterLeadStep.value?.position && step.kind === 'CHECK',
))
const chapterVisualSteps = computed(() => (currentSection.value?.steps ?? []).filter((step) =>
  step.position !== chapterLeadStep.value?.position && step.kind === 'VISUAL',
))
const chapterVisualFocus = computed(() =>
  chapterVisualSteps.value.find((step) => step.visualFocus)?.visualFocus
  ?? chapterLeadStep.value?.visualFocus
  ?? null,
)
const currentVisualPageNumber = computed(() =>
  chapterVisualFocus.value?.pageNumber ?? currentSection.value?.visualSourcePages[0],
)
const {
  generationActive,
  visualEnrichmentActive,
  visualEnrichmentSummary,
  draftReady,
  lessonStillGrowing,
  readingCurrentLastChapter,
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
  visualAidResult,
  hasVisualAid,
  recordComprehension,
  recordVisualAid,
  recordChapterVisualAid,
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
const completedCount = computed(() => new Set([...progress.value.completed, ...progress.value.skipped]).size)
const progressPercent = computed(() =>
  lesson.value?.sections.length ? Math.round((completedCount.value / lesson.value.sections.length) * 100) : 0,
)
const supportedSectionCount = computed(
  () => lesson.value?.sections.filter((section) => section.evidenceStatus === 'SUPPORTED').length ?? 0,
)

const teachingMoveMeta = {
  UNDERSTAND: { label: '先理解', marker: '想', tone: 'bg-indigo/10 text-indigo' },
  DO: { label: '照着做', marker: '做', tone: 'bg-copper/10 text-copper' },
  EXAMPLE: { label: '走一遍', marker: '例', tone: 'bg-emerald-100 text-emerald-800' },
  WATCH: { label: '别弄错', marker: '注', tone: 'bg-amber-100 text-amber-900' },
  CHECK: { label: '检查一下', marker: '验', tone: 'bg-ink-panel text-panel-text' },
  VISUAL: { label: '看桌面', marker: '图', tone: 'bg-indigo/10 text-indigo' },
  FLOW: { label: '顺着走', marker: '→', tone: 'bg-sky-100 text-sky-800' },
  LEDGER: { label: '算清楚', marker: '账', tone: 'bg-emerald-100 text-emerald-800' },
} as const

const chapterPathTitle = computed(() => {
  if (chapterPathSteps.value.some((step) => ['DO', 'FLOW'].includes(step.kind))) return t('lesson.chapter.path.action')
  return t('lesson.chapter.path.keyPoints')
})

function stepSourceLabel(step: LessonSection['steps'][number]) {
  if (!step.sourcePages.length) return ''
  return t('lesson.chapter.source', { pages: step.sourcePages.join(locale.value === 'en' ? ', ' : '、') })
}

function lessonOutcome(section: LessonSection) {
  const tags = new Set(section.coverageTags)
  const key = section.topicKey.toLowerCase()
  if (tags.has('setup') || key.includes('setup')) return t('lesson.reader.outcome.setup')
  if (tags.has('scoring') || key.includes('scor')) return t('lesson.reader.outcome.scoring')
  if (tags.has('end') || key.includes('end')) return t('lesson.reader.outcome.end')
  if (tags.has('action') || key.includes('action') || key.includes('turn')) return t('lesson.reader.outcome.action')
  if (key.includes('objective') || key.includes('goal')) return t('lesson.reader.outcome.objective')
  return t('lesson.reader.outcome.generic', { section: section.title })
}

function moveMeta(kind: LessonSection['steps'][number]['kind'] | undefined) {
  return teachingMoveMeta[kind ?? 'DO']
}

function progressKey() {
  return lesson.value ? `rulepilot:lesson-progress:${lesson.value.id}` : ''
}

function saveProgress() {
  const key = progressKey()
  if (key) localStorage.setItem(key, JSON.stringify(progress.value))
}

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
    progress.value = { ...progress.value, currentIndex: chapterIndex }
    saveProgress()
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
  requestLogin: () => router.push({ name: 'login' }),
  csrfToken,
})

function resetLessonReader() {
  narrationPlayer.value?.pause()
  plan.value = null
  lesson.value = null
  sourceLesson.value = null
  resetLessonLocalization()
  progress.value = initialLessonProgress()
  resetConversation(true)
  resetRuling()
  offlineKnowledge.value = []
  cardOcrOpen.value = false
  resumingLesson.value = false
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
    requestLogin: () => router.push({ name: 'login' }),
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
  waitingForNextChapter.value = false
  clearSupportingContent()
  if (!targetPlanId) {
    await router.replace({ name: 'lessons' })
    if (isCurrentLessonLoad(request, targetPlanId)) loading.value = false
    return
  }
  refreshOfflineKnowledge(targetPlanId)
  try {
    const [planResponse, lessonResponse, runResponse, visualRunResponse] = await Promise.all([
      fetch(`/api/v1/teaching-plans/${targetPlanId}`, { credentials: 'include' }),
      fetch(`/api/v1/teaching-plans/${targetPlanId}/illustrated-lessons/latest`, { credentials: 'include' }),
      optionalFetch(`/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(targetPlanId)}`),
      optionalFetch(`/api/v1/assistant-runs/latest?mode=VISUAL_ENRICHMENT&subjectId=${encodeURIComponent(targetPlanId)}`),
    ])
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    if (planResponse.status === 401 || lessonResponse.status === 401 || runResponse?.status === 401 || visualRunResponse?.status === 401) {
      await router.push({ name: 'login' })
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
    generationStatusUnknown.value = runResponse === null || (!runResponse.ok && runResponse.status !== 404)
    if (generationStatusUnknown.value) generationRefreshError.value = t('lesson.generation.refreshFailed')
    localStorage.setItem('rulepilot:last-plan-id', targetPlanId)
    progress.value = {
      ...restoreLessonProgress(
          localStorage.getItem(`rulepilot:lesson-progress:${lesson.value.id}`),
        lesson.value.sections.length,
      ),
      paused: false,
    }
    if (generationActive.value) generationPolling.schedule()
    else await loadSupportingContent(targetPlanId, request)
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    if (visualEnrichmentActive.value) visualPolling.schedule()
  } catch {
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    errorMessage.value = t('lesson.reader.error.load')
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
    const response = await optionalFetch(`/api/v1/assistant-runs/latest?mode=VISUAL_ENRICHMENT&subjectId=${encodeURIComponent(targetPlanId)}`)
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    if (response?.status === 401) {
      await router.push({ name: 'login' })
      return
    }
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
      await router.push({ name: 'login' })
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
      progress.value = {
        ...restoreLessonProgress(
          localStorage.getItem(`rulepilot:lesson-progress:${acceptedLesson.id}`),
          acceptedLesson.sections.length,
        ),
        paused: false,
      }
      selectSection(progress.value.currentIndex)
      waitingForNextChapter.value = false
    } else if (acceptedLesson.sections.length > previousCount && waitingForNextChapter.value) {
      waitingForNextChapter.value = false
      selectSection(previousCount)
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

function selectSection(index: number) {
  waitingForNextChapter.value = false
  progress.value = { ...progress.value, currentIndex: index }
  resetConversation(true)
  resetRuling()
  saveProgress()
  seekToChapter(index)
}

function learningPrompt(intent: LearningIntent) {
  const title = currentSection.value?.title ?? t('lesson.answer.sectionFallback')
  switch (intent) {
    case 'SIMPLIFY':
      return t('lesson.answer.prompt.simplify', { title })
    case 'EXAMPLE':
      return t('lesson.answer.prompt.example', { title })
    case 'WHY':
      return t('lesson.answer.prompt.why', { title })
    case 'EXCEPTIONS':
      return t('lesson.answer.prompt.exceptions', { title })
  }
}

function focusQuestionPanel() {
  const input = document.getElementById('lesson-question') as HTMLTextAreaElement | null
  if (!input) return
  input.scrollIntoView({ behavior: 'smooth', block: 'center' })
  window.setTimeout(() => input.focus(), 250)
}

async function askCurrentSection() {
  await submitQuestion(question.value.trim(), null)
}

async function requestLearningHelp(intent: LearningIntent) {
  const prompt = learningPrompt(intent)
  question.value = prompt
  await submitQuestion(prompt, intent)
}

function useCardText(text: string) {
  question.value = buildCardQuestion(text, t('cardOcr.questionPrefix'))
  cardOcrOpen.value = false
  clearAnswerFeedback()
}

function useVoiceTranscript(text: string) {
  question.value = mergeVoiceQuestion(question.value, text)
  clearAnswerFeedback()
}

async function csrfToken() {
  const response = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (response.status === 401) {
    await router.push({ name: 'login' })
    throw new Error(t('lesson.reader.error.loginRequired'))
  }
  if (!response.ok) throw new Error(t('lesson.reader.error.secureSession'))
  return (await response.json()) as CsrfResponse
}

async function resumeLesson() {
  if (!planId.value || resumingLesson.value || !online.value) return
  resumingLesson.value = true
  errorMessage.value = ''
  try {
    const csrf = await csrfToken()
    const response = await fetch(`/api/v1/teaching-plans/${planId.value}/illustrated-lessons`, {
      method: 'POST',
      credentials: 'include',
      headers: { [csrf.headerName]: csrf.token },
    })
    if (!response.ok) throw new Error('暂时无法继续补全讲解，请稍后重试。')
    await router.push({ name: 'lessons', query: { started: planId.value } })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '暂时无法继续补全讲解。'
  } finally {
    resumingLesson.value = false
  }
}

function previousSection() {
  if (progress.value.currentIndex === 0) return
  selectSection(progress.value.currentIndex - 1)
}

function finish(outcome: 'completed' | 'skipped') {
  if (!lesson.value || progress.value.paused) return
  const waitForNext = lessonStillGrowing.value && readingCurrentLastChapter.value
  progress.value = finishSection(progress.value, lesson.value.sections.length, outcome)
  waitingForNextChapter.value = waitForNext
  saveProgress()
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

function handleKeydown(event: KeyboardEvent) {
  if (event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement) return
  if (event.key === 'ArrowLeft') previousSection()
  if (event.key === 'ArrowRight') finish('completed')
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
  window.addEventListener('keydown', handleKeydown)
})

watch(locale, () => {
  resetConversation()
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
  window.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <AppShell immersive>
    <div class="min-h-screen overflow-x-hidden bg-canvas pb-28 text-ink lg:pb-8">
      <header class="sticky top-0 z-20 border-b border-ink/10 bg-canvas/90 backdrop-blur">
        <div class="mx-auto flex max-w-7xl items-center justify-between gap-4 px-5 py-4 sm:px-8">
          <RouterLink :to="{ name: 'lessons' }" class="text-sm font-semibold text-indigo">← {{ t('lesson.reader.back') }}</RouterLink>
          <div class="flex items-center gap-4">
            <LanguageSwitcher />
            <RouterLink v-if="lesson" :to="{ name: 'public-lesson', params: { planId } }" class="text-sm font-semibold text-indigo">{{ t('lesson.reader.public') }}</RouterLink>
            <RouterLink v-if="plan && (!generationActive || draftReady)" :to="{ name: 'table-mode', params: { planId } }" class="min-h-11 rounded-xl bg-ink px-4 py-3 text-sm font-semibold text-canvas">{{ t('lesson.reader.table') }}</RouterLink>
            <div v-if="plan" class="hidden text-right text-xs text-ink/50 sm:block">
              <p class="font-semibold text-ink/75">{{ t('lesson.reader.metaTitle') }}</p>
              <p>{{ t('lesson.reader.meta', { players: plan.playerCount, beginners: plan.beginnerCount, minutes: plan.durationMinutes }) }}</p>
            </div>
          </div>
        </div>
        <div v-if="lesson" class="h-1 bg-ink/8"><div class="h-full bg-copper transition-all" :style="{ width: `${progressPercent}%` }" /></div>
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

      <div v-else class="mx-auto grid min-w-0 max-w-7xl gap-6 px-5 py-7 sm:px-8 lg:grid-cols-[18rem_1fr] lg:py-10">
        <LessonReaderSidebar
          :lesson-status="lesson.status"
          :sections="lesson.sections"
          :current-index="progress.currentIndex"
          :completed="progress.completed"
          :skipped="progress.skipped"
          :progress-percent="progressPercent"
          :supported-section-count="supportedSectionCount"
          :lesson-still-growing="lessonStillGrowing"
          :generation-active="generationActive"
          :quality="quality"
          :visual-enrichment-summary="visualEnrichmentSummary"
          :visual-enrichment-active="visualEnrichmentActive"
          :media-consistency="mediaConsistency"
          :media-mode="mediaMode"
          :online="online"
          :resuming="resumingLesson"
          :media-mode-available="mediaModeAvailable"
          @select-section="selectSection"
          @select-media-mode="selectMediaMode"
          @resume="resumeLesson"
        />

        <section v-if="currentSection" class="min-w-0" aria-live="polite">
          <div class="rounded-[2rem] border border-ink/10 bg-paper p-5 shadow-sm sm:p-8">
            <LessonReaderChapterHeader
              :section="currentSection"
              :section-count="lesson.sections.length"
              :outcome="lessonOutcome(currentSection)"
              :lesson-still-growing="lessonStillGrowing"
              :reading-current-last-chapter="readingCurrentLastChapter"
              @ask-question="focusQuestionPanel"
            />

            <audio
              v-if="narration"
              ref="narrationPlayer"
              class="hidden"
              preload="metadata"
              :src="narrationAudioUrl"
              @loadedmetadata="onNarrationLoaded"
              @seeked="onNarrationSeeked"
              @timeupdate="onNarrationTimeUpdate"
              @play="narrationPlaying = true"
              @pause="onNarrationPaused"
              @ended="narrationPlaying = false"
              @error="onNarrationError"
            >{{ t('lesson.reader.audio.unsupported') }}</audio>

            <LessonVideoPanel
              v-if="mediaMode === 'VIDEO'"
              :chapter="currentVideoChapter"
              :active-frame="activeVideoFrame"
              :chapters="video?.chapters ?? []"
              :active-chapter-index="progress.currentIndex"
              :duration-millis="video?.durationMillis ?? 0"
              :playback-millis="narrationMillis"
              :playing="narrationPlaying"
              :playback-rate="narrationRate"
              :audio-available="audioAvailable"
              :format-duration="formatDuration"
              :visual-kind-label="visualKindLabel"
              @seek="seekNarration"
              @toggle-playback="toggleNarration"
              @replay="replayCurrentSegment"
              @cycle-rate="cycleNarrationRate"
              @select-chapter="selectSection"
            />

            <LessonChapterContent
              v-if="mediaMode !== 'VIDEO'"
              :section="currentSection"
              :lead-step="chapterLeadStep"
              :path-steps="chapterPathSteps"
              :support-steps="chapterSupportSteps"
              :check-steps="chapterCheckSteps"
              :visual-step-count="chapterVisualSteps.length"
              :path-title="chapterPathTitle"
              :current-visual-page-number="currentVisualPageNumber"
              :visual-feedback-saving="comprehensionSaving"
              :online="online"
              :page-image-url="pageImageUrl"
              :focused-page-image-url="focusedPageImageUrl"
              :step-source-label="stepSourceLabel"
              :move-meta="moveMeta"
              :visual-kind-label="visualKindLabel"
              :has-visual-aid="hasVisualAid"
              :visual-aid-result="visualAidResult"
              @rate-visual-aid="recordChapterVisualAid"
            />

            <LessonNarrationPanel
              :visible="mediaMode === 'AUDIO'"
              :chapter="currentNarration"
              :active-cue="activeCue"
              :duration-millis="narrationDurationMillis"
              :playback-millis="narrationMillis"
              :playing="narrationPlaying"
              :playback-rate="narrationRate"
              :format-duration="formatDuration"
              @seek-segment="seekToSegment"
              @seek="seekNarration"
              @toggle-playback="toggleNarration"
              @replay="replayCurrentSegment"
              @cycle-rate="cycleNarrationRate"
            />

            <LessonComprehensionPanel
              v-if="!generationActive && progress.currentIndex === lesson.sections.length - 1 && (comprehension || comprehensionError)"
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

            <LessonAnswerPanel
              :current-section="currentSection"
              :question="question"
              :answer="answer"
              :answered-question="answeredQuestion"
              :answer-turns="answerTurns"
              :active-learning-intent="activeLearningIntent"
              :answer-loading="answerLoading"
              :answer-error="answerError"
              :online="online"
              :ruling="ruling"
              :ruling-saving="rulingSaving"
              :ruling-error="rulingError"
              :ruling-conflict="rulingConflict"
              :editing-ruling="editingRuling"
              :edited-verdict="editedVerdict"
              :edited-explanation="editedExplanation"
              @update:question="question = $event"
              @update:editing-ruling="editingRuling = $event"
              @update:edited-verdict="editedVerdict = $event"
              @update:edited-explanation="editedExplanation = $event"
              @ask="askCurrentSection"
              @request-help="requestLearningHelp"
              @open-card-ocr="cardOcrOpen = true"
              @voice-transcript="useVoiceTranscript"
              @confirm-ruling="confirmAnswer"
              @reload-ruling="reloadRuling"
              @save-ruling-revision="saveRulingRevision"
            />
          </div>
        </section>
      </div>

      <LessonReaderControls
        v-if="lesson"
        :current-index="progress.currentIndex"
        :section-count="lesson.sections.length"
        :lesson-still-growing="lessonStillGrowing"
        :reading-current-last-chapter="readingCurrentLastChapter"
        :waiting-for-next-chapter="waitingForNextChapter"
        @previous="previousSection"
        @skip="finish('skipped')"
        @complete="finish('completed')"
      />

      <CardOcrCapture v-if="cardOcrOpen" @close="cardOcrOpen = false" @recognized="useCardText" />
    </div>
  </AppShell>
</template>
