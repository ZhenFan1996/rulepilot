<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import CardOcrCapture from '@/components/CardOcrCapture.vue'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import LessonAnswerPanel from '@/components/LessonAnswerPanel.vue'
import LessonChapterContent from '@/components/LessonChapterContent.vue'
import LessonComprehensionPanel from '@/components/LessonComprehensionPanel.vue'
import LessonGenerationStatus, { type LessonGenerationActivity } from '@/components/LessonGenerationStatus.vue'
import LessonNarrationPanel from '@/components/LessonNarrationPanel.vue'
import LessonReaderSidebar from '@/components/LessonReaderSidebar.vue'
import LessonVideoPanel from '@/components/LessonVideoPanel.vue'
import {
  useLessonAnswers,
  type ConfirmedRuling,
  type CsrfResponse,
  type LearningIntent,
  type RuleCitation,
} from '@/composables/useLessonAnswers'
import { buildCardQuestion } from '@/lib/cardOcr'
import {
  useLessonSupportingContent,
} from '@/composables/useLessonSupportingContent'
import type {
  LessonComprehensionReport,
  SpeechCue,
} from '@/composables/lessonSupportingContent'
import { acceptProgressiveLesson, teachingRunIsActive } from '@/lib/liveLesson'
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
  processedTeachingChapterCount,
  supportedTeachingChapterCount,
  teachingActivityCursor,
  teachingActivityText,
  teachingElapsedLabel,
  teachingRemainingTimeText,
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

type MediaMode = 'TEXT' | 'AUDIO' | 'VIDEO'

const route = useRoute()
const router = useRouter()
const { locale, t } = useLocale()
const loading = ref(true)
const errorMessage = ref('')
const online = ref(navigator.onLine)
const plan = ref<TeachingPlan | null>(null)
const lesson = ref<IllustratedLesson | null>(null)
const sourceLesson = ref<IllustratedLesson | null>(null)
const localizationStatus = ref<'PENDING' | 'RUNNING' | 'READY' | 'FAILED' | null>(null)
const localizationPreparing = ref(false)
let localizationRefreshTimer: ReturnType<typeof setTimeout> | undefined
const mediaMode = ref<MediaMode>('TEXT')
const narrationPlayer = ref<HTMLAudioElement | null>(null)
const narrationRate = ref(1)
const progress = ref<LessonProgress>(initialLessonProgress())
const ruling = ref<ConfirmedRuling | null>(null)
const rulingSaving = ref(false)
const rulingError = ref('')
const rulingConflict = ref(false)
const editingRuling = ref(false)
const editedVerdict = ref('')
const editedExplanation = ref('')
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
let generationRefreshTimer: ReturnType<typeof setTimeout> | undefined
let generationClockTimer: ReturnType<typeof setInterval> | undefined
let visualRefreshTimer: ReturnType<typeof setTimeout> | undefined
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
  mediaWarnings,
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
      ruling.value = null
    }
    rulingConflict.value = false
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
const generationActive = computed(
  () => generationStatusUnknown.value || teachingRunIsActive(teachingRun.value?.run.state),
)
const visualEnrichmentActive = computed(() => teachingRunIsActive(visualEnrichmentRun.value?.run.state))
const visualEnrichmentActivities = computed(() => (visualEnrichmentRun.value?.activities ?? [])
  .filter((activity) => activity.operation.startsWith('visualStep|') || activity.operation.startsWith('visualSection|')))
const visualEnrichmentSectionResults = computed(() => visualEnrichmentActivities.value
  .filter((activity) => activity.operation.startsWith('visualSection|')))
const visualEnrichmentSummary = computed(() => {
  const latest = visualEnrichmentActivities.value.at(-1)
  if (visualEnrichmentActive.value) return latest?.summary ?? '正在从规则书中挑选能帮助上桌的局部图示。'
  if (!visualEnrichmentRun.value || visualEnrichmentSectionResults.value.length === 0) return ''
  const added = visualEnrichmentSectionResults.value.filter((activity) => activity.outcome === 'SUCCEEDED').length
  return added > 0
    ? `已为 ${added} 节补入可核对的局部截图；其余章节只保留有可靠依据的配图。`
    : '这次没有找到可靠的局部图示，因此没有用整页规则书充数。'
})
const draftReady = computed(() => lesson.value?.status === 'DRAFT_READY')
const lessonStillGrowing = computed(() => generationActive.value && !draftReady.value)
const readingCurrentLastChapter = computed(
  () => Boolean(lesson.value?.sections.length) && progress.value.currentIndex === lesson.value!.sections.length - 1,
)
const generationActivities = computed(() => teachingRun.value?.activities ?? [])
const currentGenerationActivity = computed(() => generationActivities.value.at(-1))
const currentGenerationText = computed(() => plan.value
  ? teachingActivityText(plan.value, generationActivities.value, currentGenerationActivity.value)
  : '正在准备规则依据和章节顺序')
const generationElapsed = computed(() => teachingElapsedLabel(teachingRun.value, generationNow.value))
const processedGenerationChapters = computed(() => processedTeachingChapterCount(teachingRun.value))
const supportedGenerationChapters = computed(() => supportedTeachingChapterCount(teachingRun.value))
const generationProgressWidth = computed(() => `${Math.round(
  processedGenerationChapters.value / Math.max(1, plan.value?.sections.length ?? 1) * 100,
)}%`)
const generationRemainingTime = computed(() => plan.value
  ? teachingRemainingTimeText(plan.value, teachingRun.value, generationNow.value)
  : '')
const recentGenerationActivities = computed<LessonGenerationActivity[]>(() => generationActivities.value
  .slice(-3)
  .reverse()
  .map((activity) => ({
    sequence: activity.sequence,
    outcome: activity.outcome,
    text: plan.value
      ? teachingActivityText(plan.value, generationActivities.value, activity)
      : '正在整理并核对讲解',
  })))

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

function visualAidFor(sectionPosition: number, stepPosition: number) {
  return comprehension.value?.visualAids.find((aid) => aid.key === visualAidKey(sectionPosition, stepPosition)) ?? null
}

function visualAidKey(sectionPosition: number, stepPosition: number) {
  return `s${sectionPosition}-v${stepPosition}`
}

function visualAidResult(sectionPosition: number, stepPosition: number) {
  return visualAidFor(sectionPosition, stepPosition)?.result ?? 'NOT_RATED'
}

function hasVisualAid(sectionPosition: number, stepPosition: number) {
  return visualAidFor(sectionPosition, stepPosition) !== null
}

const currentNarration = computed(() => narration.value?.chapters[progress.value.currentIndex] ?? null)
const currentVideoChapter = computed(() => video.value?.chapters[progress.value.currentIndex] ?? null)
const narrationAudioUrl = computed(() => `/api/v1/teaching-plans/${planId.value}/narration/audio`)
const activeCue = computed(() =>
  narrationCues.value.find(
    (cue) => narrationMillis.value >= cue.startMillis && narrationMillis.value < cue.endMillis,
  ) ?? null,
)
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
  if (chapterPathSteps.value.some((step) => ['DO', 'FLOW'].includes(step.kind))) return '上桌时按这个顺序'
  return '再抓住这几个判断'
})

function stepSourceLabel(step: LessonSection['steps'][number]) {
  return step.sourcePages.length ? `原文 ${step.sourcePages.join('、')} 页` : ''
}

function lessonOutcome(section: LessonSection) {
  const tags = new Set(section.coverageTags)
  const key = section.topicKey.toLowerCase()
  if (tags.has('setup') || key.includes('setup')) return '把组件摆到正确位置，并能一眼确认开局已经准备好。'
  if (tags.has('scoring') || key.includes('scor')) return '按正确顺序算完分，知道每一项从哪里来。'
  if (tags.has('end') || key.includes('end')) return '认出游戏何时结束，以及结束后马上做什么。'
  if (tags.has('action') || key.includes('action') || key.includes('turn')) return '轮到你时知道有哪些选择，并能完整走完一次行动。'
  if (key.includes('objective') || key.includes('goal')) return '用一句话讲清你在争取什么，以及最后如何判断胜负。'
  return `用自己的话讲清“${section.title}”，并知道它在桌上什么时候会用到。`
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

function refreshOfflineKnowledge(targetPlanId = planId.value) {
  offlineKnowledge.value = loadOfflineKnowledge(targetPlanId)
}

function isCurrentLessonLoad(request: number, targetPlanId: string) {
  return !lessonViewDisposed && request === latestLessonLoad && targetPlanId === planId.value
}

function resetLessonReader() {
  narrationPlayer.value?.pause()
  plan.value = null
  lesson.value = null
  sourceLesson.value = null
  localizationStatus.value = null
  localizationPreparing.value = false
  progress.value = initialLessonProgress()
  resetConversation(true)
  ruling.value = null
  rulingSaving.value = false
  rulingError.value = ''
  rulingConflict.value = false
  editingRuling.value = false
  editedVerdict.value = ''
  editedExplanation.value = ''
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

interface LocalizationView {
  language: 'ZH_CN' | 'EN'
  status: 'PENDING' | 'RUNNING' | 'READY' | 'FAILED' | null
  lesson: IllustratedLesson | null
  failureCode: string | null
}

function clearLocalizationRefresh() {
  if (localizationRefreshTimer) clearTimeout(localizationRefreshTimer)
  localizationRefreshTimer = undefined
}

function scheduleLocalizationRefresh() {
  clearLocalizationRefresh()
  if (lessonViewDisposed || locale.value !== 'en' || !sourceLesson.value || !['PENDING', 'RUNNING'].includes(localizationStatus.value ?? '')) return
  localizationRefreshTimer = setTimeout(() => {
    localizationRefreshTimer = undefined
    void applySelectedLocale()
  }, 3000)
}

async function applySelectedLocale(targetPlanId = planId.value, request = latestLessonLoad) {
  if (!isCurrentLessonLoad(request, targetPlanId)) return
  clearLocalizationRefresh()
  const source = sourceLesson.value
  if (!source) return
  if (locale.value !== 'en') {
    localizationStatus.value = 'READY'
    lesson.value = source
    return
  }
  try {
    const response = await fetch(`/api/v1/teaching-plans/${targetPlanId}/illustrated-lessons/latest/localizations/en`, { credentials: 'include' })
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    if (response.status === 401) {
      await router.push({ name: 'login' })
      return
    }
    if (!response.ok) throw new Error('English guide is unavailable.')
    const localized = await response.json() as LocalizationView
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    localizationStatus.value = localized.status
    lesson.value = localized.status === 'READY' && localized.lesson ? localized.lesson : source
  } catch {
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    localizationStatus.value = 'FAILED'
    lesson.value = source
  } finally {
    if (isCurrentLessonLoad(request, targetPlanId)) scheduleLocalizationRefresh()
  }
}

async function prepareEnglishGuide() {
  if (!sourceLesson.value || localizationPreparing.value) return
  const targetPlanId = planId.value
  const request = latestLessonLoad
  localizationPreparing.value = true
  try {
    const csrf = await csrfToken()
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    const response = await fetch(`/api/v1/teaching-plans/${targetPlanId}/illustrated-lessons/latest/localizations/en`, {
      method: 'POST',
      credentials: 'include',
      headers: { [csrf.headerName]: csrf.token },
    })
    if (!response.ok) throw new Error('English guide could not be queued.')
    const localized = await response.json() as LocalizationView
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    localizationStatus.value = localized.status
  } catch {
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    localizationStatus.value = 'FAILED'
  } finally {
    if (isCurrentLessonLoad(request, targetPlanId)) {
      localizationPreparing.value = false
      scheduleLocalizationRefresh()
    }
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
  clearGenerationRefresh()
  clearVisualRefresh()
  clearLocalizationRefresh()
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
      throw new Error('无法读取这份讲解，请重新生成。')
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
    if (generationStatusUnknown.value) generationRefreshError.value = '暂时无法确认后台生成状态。'
    localStorage.setItem('rulepilot:last-plan-id', targetPlanId)
    progress.value = {
      ...restoreLessonProgress(
          localStorage.getItem(`rulepilot:lesson-progress:${lesson.value.id}`),
        lesson.value.sections.length,
      ),
      paused: false,
    }
    if (generationActive.value) scheduleGenerationRefresh()
    else await loadSupportingContent(targetPlanId, request)
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    if (visualEnrichmentActive.value) scheduleVisualRefresh()
  } catch (error) {
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    errorMessage.value = error instanceof Error ? error.message : '讲解加载失败。'
  } finally {
    if (isCurrentLessonLoad(request, targetPlanId)) loading.value = false
  }
}

function clearGenerationRefresh() {
  if (generationRefreshTimer) clearTimeout(generationRefreshTimer)
  generationRefreshTimer = undefined
}

function clearVisualRefresh() {
  if (visualRefreshTimer) clearTimeout(visualRefreshTimer)
  visualRefreshTimer = undefined
}

function scheduleVisualRefresh(delay = 2500) {
  clearVisualRefresh()
  if (lessonViewDisposed || !online.value || !visualEnrichmentActive.value) return
  visualRefreshTimer = setTimeout(() => {
    visualRefreshTimer = undefined
    void refreshVisualEnrichment()
  }, delay)
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
    if (isCurrentLessonLoad(request, targetPlanId)) scheduleVisualRefresh(retryDelay)
  }
}

function scheduleGenerationRefresh(delay = 1500) {
  clearGenerationRefresh()
  if (lessonViewDisposed || !online.value || !generationActive.value) return
  generationRefreshTimer = setTimeout(() => {
    generationRefreshTimer = undefined
    void refreshGeneration()
  }, delay)
}

function terminalGenerationMessage(state: string) {
  if (state === 'COMPLETED') return '讲解已经生成完成，全部章节都已载入。'
  if (state === 'INSUFFICIENT_EVIDENCE' || state === 'DEGRADED') {
    return '本轮生成已经结束；已通过核对的章节仍可阅读，缺少依据的部分可以继续补全。'
  }
  if (state === 'FAILED') return '后台生成已经停止，已完成章节仍然保留，可以稍后重新补全。'
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
      throw new Error('暂时没有取得最新章节。')
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
  } catch (error) {
    if (!isCurrentLessonLoad(request, targetPlanId)) return
    generationRefreshError.value = error instanceof Error ? error.message : '暂时没有取得最新章节。'
  } finally {
    if (isCurrentLessonLoad(request, targetPlanId)) scheduleGenerationRefresh()
  }
}

function selectSection(index: number) {
  waitingForNextChapter.value = false
  progress.value = { ...progress.value, currentIndex: index }
  resetConversation(true)
  ruling.value = null
  rulingError.value = ''
  rulingConflict.value = false
  editingRuling.value = false
  saveProgress()
  seekToChapter(index)
}

async function recordComprehension(
  taskType: LessonComprehensionReport['tasks'][number]['type'],
  result: 'CAN_DO' | 'NEEDS_HELP',
) {
  if (comprehensionSaving.value || !online.value) return
  comprehensionSaving.value = taskType
  comprehensionError.value = ''
  try {
    const csrf = await csrfToken()
    const response = await fetch(`/api/v1/teaching-plans/${planId.value}/comprehension/${taskType}`, {
      method: 'PUT',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({ result }),
    })
    if (!response.ok) throw new Error('这次学习检查没有保存，请重试。')
    comprehension.value = (await response.json()) as LessonComprehensionReport
  } catch (error) {
    comprehensionError.value = error instanceof Error ? error.message : '这次学习检查没有保存。'
  } finally {
    comprehensionSaving.value = null
  }
}

async function recordVisualAid(
  visualAidKey: string,
  result: 'HELPFUL' | 'NOT_HELPFUL',
) {
  if (comprehensionSaving.value || !online.value) return
  comprehensionSaving.value = `visual-${visualAidKey}`
  comprehensionError.value = ''
  try {
    const csrf = await csrfToken()
    const response = await fetch(`/api/v1/teaching-plans/${planId.value}/comprehension/visual-aids/${visualAidKey}`, {
      method: 'PUT',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({ result }),
    })
    if (!response.ok) throw new Error('图片帮助反馈没有保存，请重试。')
    comprehension.value = (await response.json()) as LessonComprehensionReport
  } catch (error) {
    comprehensionError.value = error instanceof Error ? error.message : '图片帮助反馈没有保存。'
  } finally {
    comprehensionSaving.value = null
  }
}

function recordChapterVisualAid(
  sectionPosition: number,
  stepPosition: number,
  result: 'HELPFUL' | 'NOT_HELPFUL',
) {
  void recordVisualAid(visualAidKey(sectionPosition, stepPosition), result)
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
    throw new Error('请先登录。')
  }
  if (!response.ok) throw new Error('无法建立安全会话，请稍后重试。')
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

function applyRuling(value: ConfirmedRuling) {
  ruling.value = value
  editedVerdict.value = value.shortVerdict
  editedExplanation.value = value.explanation
  rulingConflict.value = false
  editingRuling.value = false
  cacheOfflineRuling(
    planId.value,
    answeredQuestion.value,
    currentSection.value?.title ?? '规则答疑',
    value,
  )
  refreshOfflineKnowledge()
}

async function confirmAnswer() {
  if (!answer.value || answer.value.status !== 'ANSWERED' || !plan.value || rulingSaving.value) return
  rulingSaving.value = true
  rulingError.value = ''
  try {
    const csrf = await csrfToken()
    const response = await fetch('/api/v1/confirmed-rulings', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({
        documentVersionId: plan.value.documentVersionId,
        expansionIds: [],
        question: answeredQuestion.value,
        shortVerdict: answer.value.shortVerdict,
        explanation: answer.value.explanation,
        citationChunkIds: answer.value.citations.map((citation) => citation.chunkId),
        exceptions: answer.value.exceptions,
        confidence: answer.value.confidence,
      }),
    })
    if (!response.ok) throw new Error('无法保存这条裁定，可能已存在相同问题的确认版本。')
    applyRuling((await response.json()) as ConfirmedRuling)
  } catch (error) {
    rulingError.value = error instanceof Error ? error.message : '保存裁定失败。'
  } finally {
    rulingSaving.value = false
  }
}

async function saveRulingRevision() {
  if (!ruling.value || rulingSaving.value) return
  rulingSaving.value = true
  rulingError.value = ''
  rulingConflict.value = false
  try {
    const csrf = await csrfToken()
    const response = await fetch(`/api/v1/confirmed-rulings/${ruling.value.id}`, {
      method: 'PATCH',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({
        expectedVersion: ruling.value.version,
        shortVerdict: editedVerdict.value,
        explanation: editedExplanation.value,
        citationChunkIds: ruling.value.citations.map((citation) => citation.chunkId),
        exceptions: ruling.value.exceptions,
        confidence: ruling.value.confidence,
      }),
    })
    if (response.status === 409) {
      rulingConflict.value = true
      return
    }
    if (!response.ok) throw new Error('无法更新这条裁定。')
    applyRuling((await response.json()) as ConfirmedRuling)
  } catch (error) {
    rulingError.value = error instanceof Error ? error.message : '更新裁定失败。'
  } finally {
    rulingSaving.value = false
  }
}

async function reloadRuling() {
  if (!ruling.value) return
  rulingSaving.value = true
  try {
    const response = await fetch(`/api/v1/confirmed-rulings/${ruling.value.id}`, { credentials: 'include' })
    if (!response.ok) throw new Error('无法加载服务器上的最新裁定。')
    applyRuling((await response.json()) as ConfirmedRuling)
  } catch (error) {
    rulingError.value = error instanceof Error ? error.message : '加载最新裁定失败。'
  } finally {
    rulingSaving.value = false
  }
}

function citationPages(citation: RuleCitation) {
  return citation.pageFrom === citation.pageTo
    ? `第 ${citation.pageFrom} 页`
    : `第 ${citation.pageFrom}–${citation.pageTo} 页`
}

function cachedAtLabel(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
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

function narrationPositionKey() {
  return lesson.value ? `rulepilot:narration-position:${lesson.value.id}` : ''
}

function saveNarrationPosition() {
  const key = narrationPositionKey()
  if (key) localStorage.setItem(key, String(Math.round(narrationMillis.value)))
}

function onNarrationLoaded() {
  const player = narrationPlayer.value
  if (!player) return
  player.playbackRate = narrationRate.value
  const restored = Number(localStorage.getItem(narrationPositionKey()))
  if (Number.isFinite(restored) && restored >= 0 && restored < narrationDurationMillis.value) {
    narrationRestoreTarget.value = restored
    narrationMillis.value = restored
    player.currentTime = restored / 1_000
  }
}

function onNarrationTimeUpdate() {
  const player = narrationPlayer.value
  if (!player || narrationRestoreTarget.value !== null) return
  narrationMillis.value = Math.round(player.currentTime * 1_000)
  const cue = activeCue.value
  if (mediaMode.value === 'VIDEO' && cue && progress.value.currentIndex !== cue.chapterPosition - 1) {
    progress.value = { ...progress.value, currentIndex: cue.chapterPosition - 1 }
    saveProgress()
  }
  if (narrationPlaying.value) saveNarrationPosition()
}

function onNarrationSeeked() {
  const player = narrationPlayer.value
  const target = narrationRestoreTarget.value
  if (!player || target === null) return
  const current = Math.round(player.currentTime * 1_000)
  if (Math.abs(current - target) > 200) return
  narrationRestoreTarget.value = null
  narrationMillis.value = current
}

function onNarrationPaused() {
  if (narrationPlaying.value) {
    onNarrationTimeUpdate()
    saveNarrationPosition()
  }
  narrationPlaying.value = false
}

function onNarrationError() {
  audioAvailable.value = false
  narrationPlayer.value?.pause()
  if (mediaMode.value === 'AUDIO') mediaMode.value = 'TEXT'
  addMediaWarning('音轨加载失败，已切换到图文讲解。')
}

async function toggleNarration() {
  const player = narrationPlayer.value
  if (!player || !audioAvailable.value) return
  if (player.paused) await player.play()
  else player.pause()
}

function seekToChapter(index: number) {
  const cue = narrationCues.value.find((candidate) => candidate.chapterPosition === index + 1)
  seekToCue(cue)
}

function seekToSegment(segmentPosition: number) {
  const cue = narrationCues.value.find(
    (candidate) =>
      candidate.chapterPosition === progress.value.currentIndex + 1 &&
      candidate.segmentPosition === segmentPosition,
  )
  seekToCue(cue, true)
}

function seekToCue(cue: SpeechCue | undefined, play = false) {
  const player = narrationPlayer.value
  if (!player || !cue) return
  narrationRestoreTarget.value = cue.startMillis
  player.currentTime = cue.startMillis / 1_000
  narrationMillis.value = cue.startMillis
  saveNarrationPosition()
  if (play) void player.play()
}

function replayCurrentSegment() {
  const fallback = narrationCues.value.find(
    (cue) => cue.chapterPosition === progress.value.currentIndex + 1,
  )
  seekToCue(activeCue.value ?? fallback, true)
}

function cycleNarrationRate() {
  const rates = [0.75, 1, 1.25, 1.5, 2]
  const currentIndex = rates.indexOf(narrationRate.value)
  narrationRate.value = rates[(currentIndex + 1) % rates.length] ?? 1
  if (narrationPlayer.value) narrationPlayer.value.playbackRate = narrationRate.value
}

function seekNarration(millis: number) {
  const player = narrationPlayer.value
  if (!player) return
  narrationRestoreTarget.value = null
  player.currentTime = millis / 1_000
  narrationMillis.value = millis
  saveNarrationPosition()
}

function formatDuration(millis: number) {
  const seconds = Math.floor(millis / 1_000)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

function visualKindLabel(kind: LessonSection['visualKind']) {
  return {
    REFERENCE_CARD: '规则要点',
    TABLE_LAYOUT: '摆放示意',
    FLOW_DIAGRAM: '流程示意',
    SCOREBOARD: '计分示意',
  }[kind]
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
  if (online.value && generationActive.value) scheduleGenerationRefresh(0)
  else if (!online.value) clearGenerationRefresh()
  if (online.value && visualEnrichmentActive.value) scheduleVisualRefresh(0)
  else if (!online.value) clearVisualRefresh()
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
    addMediaWarning('English reading uses the text guide; narration and video remain in the source language.')
  }
  void applySelectedLocale()
})

watch(planId, () => {
  void loadLesson()
})

onUnmounted(() => {
  lessonViewDisposed = true
  clearGenerationRefresh()
  clearVisualRefresh()
  clearLocalizationRefresh()
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

      <p v-if="!online" class="bg-amber-100 px-5 py-3 text-center text-sm font-semibold text-amber-900" role="status">当前离线；只能查看本地讲解进度、最近答案和已确认裁定，生成式答疑已停用。</p>
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

      <section v-if="!online && offlineKnowledge.length" class="mx-auto max-w-4xl px-5 pt-7 sm:px-8" aria-labelledby="offline-knowledge-title">
        <div class="rounded-3xl border border-amber-300 bg-amber-50 p-5 sm:p-6">
          <p class="text-xs font-semibold text-copper">离线可用</p>
          <div class="mt-2 flex flex-wrap items-end justify-between gap-3">
            <div>
              <h2 id="offline-knowledge-title" class="font-display text-2xl font-semibold">本局已缓存规则结论</h2>
              <p class="mt-2 text-sm leading-6 text-amber-950/70">这里保留了此前确认过的回答。联网后才能继续提问。</p>
            </div>
            <span class="rounded-full bg-amber-900 px-3 py-1.5 text-xs font-semibold text-white">{{ offlineKnowledge.length }} 条</span>
          </div>
          <div class="mt-5 space-y-3">
            <details v-for="entry in offlineKnowledge" :key="`${entry.question}-${entry.cachedAt}`" class="rounded-2xl border border-amber-200 bg-paper p-4">
              <summary class="cursor-pointer list-none">
                <span class="flex flex-wrap items-start justify-between gap-3">
                  <span>
                    <span class="block text-xs font-semibold text-copper">{{ entry.sectionTitle }}</span>
                    <span class="mt-1 block font-semibold leading-6">{{ entry.question }}</span>
                  </span>
                  <span class="text-xs font-semibold text-ink/45">{{ entry.ruling ? '已确认裁定' : '最近答案' }} · {{ cachedAtLabel(entry.cachedAt) }}</span>
                </span>
              </summary>
              <p class="mt-4 border-t border-ink/10 pt-4 font-display text-lg font-semibold leading-7">{{ entry.ruling?.shortVerdict ?? entry.answer.shortVerdict }}</p>
              <p class="mt-3 text-sm leading-7 text-ink/70">{{ entry.ruling?.explanation ?? entry.answer.explanation }}</p>
              <ol class="mt-4 space-y-2">
                <li v-for="citation in (entry.ruling?.citations ?? entry.answer.citations)" :key="citation.chunkId" class="rounded-xl bg-indigo/5 p-3 text-sm">
                  <p class="font-semibold text-indigo">{{ citation.heading }} · {{ citationPages(citation) }}</p>
                  <p class="mt-1 leading-6 text-ink/60">{{ citation.excerpt }}</p>
                </li>
              </ol>
            </details>
          </div>
        </div>
      </section>

      <div v-if="loading" class="mx-auto max-w-7xl px-5 py-16 sm:px-8" aria-live="polite">
        <div class="h-7 w-44 animate-pulse rounded bg-ink/10" />
        <div class="mt-6 h-80 animate-pulse rounded-3xl bg-paper" />
      </div>

      <section v-else-if="errorMessage" class="mx-auto max-w-xl px-5 py-20 text-center">
        <p class="font-display text-2xl font-semibold">讲解暂时无法打开</p>
        <p class="mt-3 text-ink/60" role="alert">
          {{ online ? errorMessage : '离线时无法加载尚未缓存的讲解，联网后可继续学习。' }}
        </p>
        <button
          v-if="online"
          class="mt-6 rounded-xl bg-copper px-5 py-3 font-semibold text-white"
          @click="loadLesson"
        >
          重新加载
        </button>
      </section>

      <section v-else-if="!lesson" class="mx-auto max-w-xl px-5 py-20 text-center">
        <h1 class="font-display text-4xl font-semibold">还没有可以继续的讲解</h1>
        <p class="mt-4 leading-7 text-ink/60">先导入规则书，创建教学计划并生成图文讲解。</p>
        <RouterLink :to="{ name: 'teach' }" class="mt-7 inline-flex rounded-xl bg-copper px-5 py-3 font-semibold text-white">开始导入</RouterLink>
      </section>

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
            <div class="flex flex-wrap items-start justify-between gap-4 border-b border-ink/8 pb-5">
              <div class="max-w-3xl">
                <p class="text-xs font-semibold text-copper">第 {{ currentSection.position }} / {{ lesson.sections.length }} 节</p>
                <h2 class="mt-2 font-display text-3xl font-semibold leading-tight sm:text-4xl">{{ currentSection.title }}</h2>
                <p class="mt-3 hidden max-w-2xl text-sm leading-6 text-ink/55 sm:block">学完这一节，你应该能：{{ lessonOutcome(currentSection) }}</p>
                <button
                  type="button"
                  class="mt-4 inline-flex min-h-11 items-center rounded-xl bg-indigo px-4 text-sm font-semibold text-white shadow-sm transition hover:-translate-y-0.5 hover:bg-indigo/90"
                  @click="focusQuestionPanel"
                >
                  问这一节的规则
                </button>
              </div>
              <details class="relative hidden text-xs sm:block">
                <summary class="cursor-pointer list-none rounded-full border border-ink/10 px-3 py-2 font-semibold text-ink/55">
                  {{ currentSection.evidenceStatus === 'INSUFFICIENT_EVIDENCE' ? '原文不足' : currentSection.evidenceStatus === 'CITED_DRAFT' ? '有原文引用 · 细节核对中' : '引用已核对' }}
                </summary>
                <div class="absolute right-0 z-10 mt-2 w-56 rounded-xl border border-ink/10 bg-paper p-3 leading-5 text-ink/60 shadow-lg">
                  {{ currentSection.evidenceStatus === 'INSUFFICIENT_EVIDENCE' ? '这一节缺少足够原文，请把内容当作待补部分。' : currentSection.evidenceStatus === 'CITED_DRAFT' ? '每一步都可以回到对应原文，后台仍在核对细节。' : '本节引用与规则事实已经通过核对。' }}
                </div>
              </details>
            </div>
            <div v-if="lessonStillGrowing && readingCurrentLastChapter" class="mt-5 rounded-2xl border border-indigo/15 bg-indigo/5 p-4 text-sm leading-6 text-indigo" role="status">
              <p class="font-semibold">这是当前最后一节，后续章节仍在生成。</p>
              <p class="mt-1 text-ink/55">你可以先读完并标记本节；下一节完成后，页面会自动继续。</p>
            </div>

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
            >浏览器不支持音频播放。</audio>

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

      <nav v-if="lesson" class="fixed inset-x-0 bottom-0 z-30 border-t border-ink/10 bg-canvas/95 p-3 backdrop-blur lg:sticky lg:mx-auto lg:max-w-4xl lg:rounded-2xl lg:border" aria-label="讲解控制">
        <div class="mx-auto grid max-w-3xl grid-cols-[0.8fr_1fr_1.5fr] gap-2">
          <button :disabled="progress.currentIndex === 0" class="min-h-12 rounded-xl border border-ink/15 px-3 text-sm font-semibold disabled:opacity-35" @click="previousSection">上一节</button>
          <button class="min-h-12 rounded-xl border border-ink/15 px-3 text-sm font-semibold" @click="finish('skipped')">稍后再看</button>
          <button :disabled="waitingForNextChapter" class="min-h-12 rounded-xl bg-copper px-3 text-sm font-semibold text-white disabled:cursor-wait disabled:opacity-60" @click="finish('completed')">{{ lessonStillGrowing && readingCurrentLastChapter ? (waitingForNextChapter ? '等待下一节…' : '这节看懂了，等待下一节') : progress.currentIndex === lesson.sections.length - 1 ? '我学完了' : '看懂了，下一节' }}</button>
        </div>
      </nav>

      <CardOcrCapture v-if="cardOcrOpen" @close="cardOcrOpen = false" @recognized="useCardText" />
    </div>
  </AppShell>
</template>
