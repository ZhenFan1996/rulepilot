<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import CardOcrCapture from '@/components/CardOcrCapture.vue'
import VoiceQuestionCapture from '@/components/VoiceQuestionCapture.vue'
import { buildCardQuestion } from '@/lib/cardOcr'
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
import { mergeVoiceQuestion } from '@/lib/voiceQuestion'

interface TeachingPlan {
  id: string
  documentVersionId: string
  playerCount: number
  beginnerCount: number
  durationMinutes: number
  gameTitle: string
  premise: string
}

interface IllustratedLesson {
  id: string
  status: 'COMPLETE' | 'INCOMPLETE'
  sections: LessonSection[]
}

interface LessonSection {
  position: number
  topicKey: string
  coverageTags: string[]
  title: string
  required: boolean
  evidenceStatus: 'SUPPORTED' | 'INSUFFICIENT_EVIDENCE'
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

interface LessonQualityReport {
  status: 'READY' | 'NEEDS_REVIEW' | 'BLOCKED'
  score: number
  checks: Array<{
    topicKey: string
    status: 'PASS' | 'FAIL' | 'NOT_EVALUATED'
    summary: string
    detail: string
  }>
}

interface NarrationScript {
  id: string
  status: 'READY' | 'INCOMPLETE'
  chapters: Array<{
    position: number
    type: string
    title: string
    supported: boolean
    segments: Array<{ position: number; text: string; sourcePages: number[] }>
  }>
}

interface NarrationPlayback {
  script: NarrationScript
  provider: string
  durationMillis: number
  cues: SpeechCue[]
}

interface SpeechCue {
  chapterPosition: number
  segmentPosition: number
  startMillis: number
  endMillis: number
}

interface ChapterVideo {
  id: string
  status: 'READY' | 'INCOMPLETE'
  durationMillis: number
  chapters: VideoChapter[]
}

interface MediaConsistencyReport {
  status: 'CONSISTENT' | 'INCONSISTENT'
  consistencyPercent: number
  checks: Array<{
    type: string
    status: 'PASS' | 'FAIL'
    summary: string
    detail: string
  }>
}

interface StructuredRuleAnswer {
  status: 'ANSWERED' | 'CLARIFICATION_REQUIRED' | 'INSUFFICIENT_EVIDENCE' | 'MODEL_TIMEOUT' | 'INVALID_MODEL_OUTPUT' | 'VERSION_CONFLICT'
  shortVerdict: string
  explanation: string
  citations: RuleCitation[]
  exceptions: string[]
  confidence: 'HIGH' | 'MEDIUM' | 'LOW'
  official: boolean
  confirmedRulingId: string | null
  confirmedRulingVersion: number | null
  clarification: string | null
}

interface AnswerCreation {
  assistantRunId: string
  answer: StructuredRuleAnswer
}

interface AnswerTurn {
  question: string
  answer: StructuredRuleAnswer
  learningIntent: LearningIntent | null
}

type LearningIntent = 'SIMPLIFY' | 'EXAMPLE' | 'WHY' | 'EXCEPTIONS'

interface RuleCitation {
  chunkId: string
  sectionType: string
  heading: string
  excerpt: string
  pageFrom: number
  pageTo: number
}

interface ConfirmedRuling {
  id: string
  shortVerdict: string
  explanation: string
  citations: RuleCitation[]
  exceptions: string[]
  confidence: StructuredRuleAnswer['confidence']
  status: 'CONFIRMED' | 'SUPERSEDED'
  version: number
}

interface CsrfResponse {
  headerName: string
  token: string
}

interface VideoChapter {
  position: number
  type: string
  title: string
  evidenceStatus: 'SUPPORTED' | 'INSUFFICIENT_EVIDENCE'
  visualKind: 'REFERENCE_CARD' | 'TABLE_LAYOUT' | 'FLOW_DIAGRAM' | 'SCOREBOARD'
  visualCaption: string
  startMillis: number
  endMillis: number
  frames: Array<{
    segmentPosition: number
    startMillis: number
    endMillis: number
    subtitle: string
    sourcePages: number[]
  }>
}

type MediaMode = 'TEXT' | 'AUDIO' | 'VIDEO'

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const errorMessage = ref('')
const online = ref(navigator.onLine)
const plan = ref<TeachingPlan | null>(null)
const lesson = ref<IllustratedLesson | null>(null)
const quality = ref<LessonQualityReport | null>(null)
const narration = ref<NarrationScript | null>(null)
const video = ref<ChapterVideo | null>(null)
const mediaConsistency = ref<MediaConsistencyReport | null>(null)
const mediaWarnings = ref<string[]>([])
const audioAvailable = ref(false)
const mediaMode = ref<MediaMode>('TEXT')
const narrationPlayer = ref<HTMLAudioElement | null>(null)
const narrationProvider = ref('')
const narrationDurationMillis = ref(0)
const narrationCues = ref<SpeechCue[]>([])
const narrationMillis = ref(0)
const narrationPlaying = ref(false)
const narrationRate = ref(1)
const narrationRestoreTarget = ref<number | null>(null)
const progress = ref<LessonProgress>(initialLessonProgress())
const question = ref('')
const answer = ref<StructuredRuleAnswer | null>(null)
const answeredQuestion = ref('')
const answerTurns = ref<AnswerTurn[]>([])
const activeLearningIntent = ref<LearningIntent | null>(null)
const answerLoading = ref(false)
const answerError = ref('')
const ruling = ref<ConfirmedRuling | null>(null)
const rulingSaving = ref(false)
const rulingError = ref('')
const rulingConflict = ref(false)
const editingRuling = ref(false)
const editedVerdict = ref('')
const editedExplanation = ref('')
const offlineKnowledge = ref<OfflineKnowledgeEntry[]>([])
const cardOcrOpen = ref(false)
const visualImageFailed = ref(false)
const resumingLesson = ref(false)

const planId = computed(() => String(route.params.planId ?? ''))
const currentSection = computed(() => lesson.value?.sections[progress.value.currentIndex] ?? null)
const currentVisualPageUrl = computed(() => {
  const page = currentSection.value?.visualSourcePages[0]
  return pageImageUrl(page)
})

function pageImageUrl(page: number | undefined) {
  if (!plan.value || !page) return ''
  return `/api/v1/document-versions/${plan.value.documentVersionId}/pages/${page}/image`
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
const activeCue = computed(() =>
  narrationCues.value.find(
    (cue) => narrationMillis.value >= cue.startMillis && narrationMillis.value < cue.endMillis,
  ),
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
const previousAnswerTurns = computed(() => answerTurns.value.slice(0, -1))
const currentAnswerTurn = computed(() => answerTurns.value[answerTurns.value.length - 1] ?? null)

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

const hasVisualBlock = computed(() => currentSection.value?.steps.some((step) => step.kind === 'VISUAL') ?? false)

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

watch(currentVisualPageUrl, () => {
  visualImageFailed.value = false
})

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

async function optionalFetch(url: string) {
  try {
    return await fetch(url, { credentials: 'include' })
  } catch {
    return null
  }
}

function addMediaWarning(message: string) {
  if (!mediaWarnings.value.includes(message)) mediaWarnings.value.push(message)
}

async function loadLesson() {
  loading.value = true
  errorMessage.value = ''
  narration.value = null
  video.value = null
  mediaConsistency.value = null
  mediaWarnings.value = []
  audioAvailable.value = false
  narrationProvider.value = ''
  narrationDurationMillis.value = 0
  narrationCues.value = []
  narrationMillis.value = 0
  let targetPlanId = planId.value
  if (!targetPlanId) {
    await router.replace({ name: 'lessons' })
    loading.value = false
    return
  }
  refreshOfflineKnowledge(targetPlanId)
  try {
    const [planResponse, lessonResponse, qualityResponse, narrationResponse, videoResponse, consistencyResponse] = await Promise.all([
      fetch(`/api/v1/teaching-plans/${targetPlanId}`, { credentials: 'include' }),
      fetch(`/api/v1/teaching-plans/${targetPlanId}/illustrated-lessons/latest`, { credentials: 'include' }),
      fetch(`/api/v1/teaching-plans/${targetPlanId}/illustrated-lessons/latest/quality`, { credentials: 'include' }),
      optionalFetch(`/api/v1/teaching-plans/${targetPlanId}/narration/playback`),
      optionalFetch(`/api/v1/teaching-plans/${targetPlanId}/video`),
      optionalFetch(`/api/v1/teaching-plans/${targetPlanId}/media-consistency`),
    ])
    if (
      planResponse.status === 401 ||
      lessonResponse.status === 401 ||
      qualityResponse.status === 401
    ) {
      await router.push({ name: 'login' })
      return
    }
    if (
      !planResponse.ok ||
      !lessonResponse.ok ||
      !qualityResponse.ok
    ) {
      throw new Error('无法读取这份讲解，请重新生成。')
    }
    plan.value = (await planResponse.json()) as TeachingPlan
    lesson.value = (await lessonResponse.json()) as IllustratedLesson
    quality.value = (await qualityResponse.json()) as LessonQualityReport
    if (narrationResponse?.ok) {
      const playback = (await narrationResponse.json()) as NarrationPlayback
      narration.value = playback.script
      narrationProvider.value = playback.provider
      narrationDurationMillis.value = playback.durationMillis
      narrationCues.value = playback.cues
      audioAvailable.value = true
    } else {
      addMediaWarning('语音暂不可用，已保留完整图文讲解。')
    }
    if (videoResponse?.ok) {
      video.value = (await videoResponse.json()) as ChapterVideo
    } else {
      addMediaWarning('视频暂不可用，可继续使用图文或语音讲解。')
    }
    if (consistencyResponse?.ok) {
      mediaConsistency.value = (await consistencyResponse.json()) as MediaConsistencyReport
    }
    localStorage.setItem('rulepilot:last-plan-id', targetPlanId)
    progress.value = {
      ...restoreLessonProgress(
        localStorage.getItem(`rulepilot:lesson-progress:${lesson.value.id}`),
        lesson.value.sections.length,
      ),
      paused: false,
    }
    const restoredNarration = Number(localStorage.getItem(narrationPositionKey()))
    if (
      Number.isFinite(restoredNarration) &&
      restoredNarration >= 0 &&
      restoredNarration < narrationDurationMillis.value
    ) {
      narrationRestoreTarget.value = restoredNarration
      narrationMillis.value = restoredNarration
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '讲解加载失败。'
  } finally {
    loading.value = false
  }
}

function selectSection(index: number) {
  progress.value = { ...progress.value, currentIndex: index }
  question.value = ''
  answer.value = null
  answeredQuestion.value = ''
  answerTurns.value = []
  answerError.value = ''
  ruling.value = null
  rulingError.value = ''
  rulingConflict.value = false
  editingRuling.value = false
  saveProgress()
  seekToChapter(index)
}

function learningIntentLabel(intent: LearningIntent | null) {
  if (intent === null) return '规则答疑'
  return {
    SIMPLIFY: '换个简单说法',
    EXAMPLE: '走一个具体例子',
    WHY: '梳理前后关系',
    EXCEPTIONS: '查找例外和限制',
  }[intent]
}

function learningPrompt(intent: LearningIntent) {
  const title = currentSection.value?.title ?? '这一节'
  return {
    SIMPLIFY: `请用更简单的话重新讲解“${title}”，告诉我现在最需要记住什么。`,
    EXAMPLE: `请根据“${title}”的规则，走一个具体、合法的桌面例子。`,
    WHY: `请说明“${title}”里的步骤前后怎么衔接：哪一步完成后必须做什么，只讲规则明确写出的关系。`,
    EXCEPTIONS: `请整理“${title}”中规则明确写出的时机、限制、禁止和例外。`,
  }[intent]
}

function currentLessonContext() {
  const section = currentSection.value
  if (!section) return null
  return [section.topicKey, section.title, ...section.coverageTags].join(' ')
}

async function submitQuestion(text: string, learningIntent: LearningIntent | null) {
  if (!text || !plan.value || !currentSection.value || answerLoading.value || !online.value) return
  answerLoading.value = true
  activeLearningIntent.value = learningIntent
  answerError.value = ''
  answer.value = null
  try {
    const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'include' })
    if (csrfResponse.status === 401) {
      await router.push({ name: 'login' })
      return
    }
    if (!csrfResponse.ok) throw new Error('无法建立安全会话，请稍后重试。')
    const csrf = (await csrfResponse.json()) as CsrfResponse
    const previousTurn = answerTurns.value[answerTurns.value.length - 1]
    const response = await fetch(`/api/v1/document-versions/${plan.value.documentVersionId}/answers`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({
        question: text,
        currentLessonSection: currentLessonContext(),
        playerCount: plan.value.playerCount,
        previousQuestion: previousTurn?.question,
        learningIntent,
      }),
    })
    if (response.status === 401) {
      await router.push({ name: 'login' })
      return
    }
    if (!response.ok) throw new Error('暂时无法回答这个问题，请稍后重试。')
    const creation = (await response.json()) as AnswerCreation
    const received = creation.answer
    answer.value = received
    answeredQuestion.value = text
    answerTurns.value.push({ question: text, answer: received, learningIntent })
    if (received.status === 'ANSWERED') {
      cacheOfflineAnswer(planId.value, text, currentSection.value.title, received)
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
  } catch (error) {
    answerError.value = error instanceof Error ? error.message : '提问失败，请稍后重试。'
  } finally {
    answerLoading.value = false
    activeLearningIntent.value = null
  }
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
  question.value = buildCardQuestion(text)
  cardOcrOpen.value = false
  answer.value = null
  answerError.value = ''
}

function useVoiceTranscript(text: string) {
  question.value = mergeVoiceQuestion(question.value, text)
  answer.value = null
  answerError.value = ''
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

function confidenceLabel(confidence: StructuredRuleAnswer['confidence']) {
  return { HIGH: '高置信度', MEDIUM: '中等置信度', LOW: '低置信度' }[confidence]
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

function answerFailureMessage(status: StructuredRuleAnswer['status']) {
  return {
    ANSWERED: '',
    CLARIFICATION_REQUIRED: '',
    INSUFFICIENT_EVIDENCE: '当前规则资料没有足够依据，系统没有生成推测性结论。',
    MODEL_TIMEOUT: '回答生成超时。你可以重新提交，已加载的讲解和原始规则证据不受影响。',
    INVALID_MODEL_OUTPUT: '生成结果未通过结构或引用校验，未经验证的内容没有显示。',
    VERSION_CONFLICT: '检索证据与当前规则版本不一致，请返回讲解并确认所选版本。',
  }[status]
}

function previousSection() {
  if (progress.value.currentIndex === 0) return
  selectSection(progress.value.currentIndex - 1)
}

function finish(outcome: 'completed' | 'skipped') {
  if (!lesson.value || progress.value.paused) return
  progress.value = finishSection(progress.value, lesson.value.sections.length, outcome)
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
  narrationRestoreTarget.value = null
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

function seekNarration(event: Event) {
  const player = narrationPlayer.value
  if (!player) return
  const millis = Number((event.target as HTMLInputElement).value)
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
}

onMounted(() => {
  void loadLesson()
  window.addEventListener('online', updateOnlineStatus)
  window.addEventListener('offline', updateOnlineStatus)
  window.addEventListener('keydown', handleKeydown)
})

onUnmounted(() => {
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
          <RouterLink :to="{ name: 'lessons' }" class="text-sm font-semibold text-indigo">← 我的讲解</RouterLink>
          <div class="flex items-center gap-4">
            <div v-if="plan" class="hidden text-right text-xs text-ink/50 sm:block">
              <p class="font-semibold text-ink/75">这次讲解</p>
              <p>{{ plan.playerCount }} 人 · {{ plan.beginnerCount }} 位新手 · {{ plan.durationMinutes }} 分钟</p>
            </div>
          </div>
        </div>
        <div v-if="lesson" class="h-1 bg-ink/8"><div class="h-full bg-copper transition-all" :style="{ width: `${progressPercent}%` }" /></div>
      </header>

      <p v-if="!online" class="bg-amber-100 px-5 py-3 text-center text-sm font-semibold text-amber-900" role="status">当前离线；只能查看本地讲解进度、最近答案和已确认裁定，生成式答疑已停用。</p>
      <div v-if="mediaWarnings.length" class="bg-amber-50 px-5 py-3 text-center text-sm font-semibold text-amber-900" role="status">
        <p v-for="warning in mediaWarnings" :key="warning">{{ warning }}</p>
      </div>

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
        <aside class="min-w-0 max-w-full overflow-hidden lg:sticky lg:top-28 lg:h-[calc(100vh-8rem)] lg:overflow-auto" aria-label="讲解章节">
          <div class="flex items-end justify-between">
            <div><p class="text-xs font-medium text-copper">讲解目录</p><h1 class="mt-2 font-display text-2xl font-semibold">完整规则讲解</h1></div>
            <span class="text-sm font-semibold text-copper">{{ progressPercent }}%</span>
          </div>
          <details v-if="quality" class="mt-4 hidden rounded-2xl border border-ink/10 bg-paper/70 p-3 lg:block">
            <summary class="cursor-pointer list-none text-sm font-semibold">
              <span class="flex items-center justify-between gap-3">
                <span>讲解有问题？查看诊断</span>
                <span :class="quality.status === 'READY' ? 'text-emerald-700' : quality.status === 'BLOCKED' ? 'text-red-700' : 'text-amber-700'">{{ quality.status === 'READY' ? '可交付' : quality.status === 'BLOCKED' ? '有阻塞项' : '需要复核' }}</span>
              </span>
            </summary>
            <ul class="mt-3 space-y-2 border-t border-ink/10 pt-3">
              <li v-for="check in quality.checks" :key="check.topicKey" class="text-xs leading-5">
                <p class="font-semibold"><span aria-hidden="true">{{ check.status === 'PASS' ? '✓' : check.status === 'FAIL' ? '×' : '?' }}</span> {{ check.summary }}</p>
                <p class="mt-0.5 text-ink/50">{{ check.detail }}</p>
              </li>
            </ul>
          </details>
          <div v-if="lesson.status === 'INCOMPLETE'" class="mt-3 hidden rounded-2xl border border-amber-300/70 bg-amber-50 p-3 text-sm text-amber-950 lg:block">
            <p class="font-semibold">已验证 {{ supportedSectionCount }} / {{ lesson.sections.length }} 节</p>
            <p class="mt-1 text-xs leading-5 text-amber-900/75">继续时会保留已经通过引用与事实检查的章节，只补尚未通过的部分。</p>
            <button
              class="mt-3 min-h-10 rounded-xl bg-amber-900 px-3 py-2 text-xs font-semibold text-white disabled:cursor-not-allowed disabled:opacity-40"
              :disabled="resumingLesson || !online"
              @click="resumeLesson"
            >
              {{ resumingLesson ? '正在补全…' : '继续补全讲解' }}
            </button>
          </div>
          <details v-if="mediaConsistency" class="mt-3 hidden rounded-2xl border border-ink/10 bg-paper/70 p-3 lg:block">
            <summary class="cursor-pointer list-none text-sm font-semibold">
              <span class="flex items-center justify-between gap-3">
                <span>图文与音视频状态</span>
                <span :class="mediaConsistency.status === 'CONSISTENT' ? 'text-emerald-700' : 'text-red-700'">{{ mediaConsistency.status === 'CONSISTENT' ? '一致' : '需检查' }}</span>
              </span>
            </summary>
            <ul class="mt-3 space-y-2 border-t border-ink/10 pt-3">
              <li v-for="check in mediaConsistency.checks" :key="check.type" class="text-xs leading-5">
                <p class="font-semibold">{{ check.status === 'PASS' ? '✓' : '×' }} {{ check.summary }}</p>
                <p class="text-ink/50">{{ check.detail }}</p>
              </li>
            </ul>
          </details>
          <div class="mt-4 grid grid-cols-3 rounded-2xl border border-ink/10 bg-paper/70 p-1" aria-label="讲解形式">
            <button
              v-for="mode in ([['TEXT', '图文'], ['AUDIO', '语音'], ['VIDEO', '视频']] as const)"
              :key="mode[0]"
              class="min-h-10 rounded-xl px-2 text-xs font-semibold transition"
              :disabled="!mediaModeAvailable(mode[0])"
              :class="mediaMode === mode[0] ? 'bg-ink-panel text-panel-text' : 'text-ink/55 hover:text-ink disabled:cursor-not-allowed disabled:opacity-35'"
              :aria-pressed="mediaMode === mode[0]"
              @click="selectMediaMode(mode[0])"
            >
              {{ mode[1] }}
            </button>
          </div>
          <ol class="mt-5 flex max-w-full gap-2 overflow-x-auto pb-2 lg:block lg:space-y-2 lg:overflow-visible">
            <li v-for="(section, index) in lesson.sections" :key="section.topicKey" class="shrink-0 lg:shrink">
              <button
                class="flex min-h-12 w-52 items-center gap-3 rounded-2xl px-3 py-2 text-left text-sm transition lg:w-full"
                :class="index === progress.currentIndex ? 'bg-ink-panel text-panel-text' : 'bg-paper/60 text-ink hover:bg-paper'"
                :aria-current="index === progress.currentIndex ? 'step' : undefined"
                @click="selectSection(index)"
              >
                <span class="grid size-7 shrink-0 place-items-center rounded-full border text-xs font-bold">{{ progress.completed.includes(index) ? '✓' : progress.skipped.includes(index) ? '–' : section.position }}</span>
                <span class="truncate">{{ section.title }}</span>
              </button>
            </li>
          </ol>
        </aside>

        <section v-if="currentSection" class="min-w-0" aria-live="polite">
          <div class="rounded-[2rem] border border-ink/10 bg-paper p-5 shadow-sm sm:p-8">
            <div class="flex flex-wrap items-start justify-between gap-4">
              <div><p class="text-xs font-semibold text-ink/45">第 {{ currentSection.position }} / {{ lesson.sections.length }} 节</p><h2 class="mt-2 font-display text-3xl font-semibold sm:text-4xl">{{ currentSection.title }}</h2></div>
              <span v-if="currentSection.evidenceStatus === 'INSUFFICIENT_EVIDENCE'" class="rounded-md bg-amber-100 px-3 py-1.5 text-xs font-semibold text-amber-900">原文内容不足</span>
              <span v-else class="rounded-md bg-emerald-100 px-3 py-1.5 text-xs font-semibold text-emerald-900">可查看原文</span>
            </div>

            <div class="mt-6 rounded-2xl border border-copper/20 bg-copper/8 px-4 py-4 sm:px-5">
              <p class="text-xs font-semibold uppercase tracking-[0.16em] text-copper">这一节学完，你应该能</p>
              <p class="mt-2 text-base font-semibold leading-7 text-ink/80">{{ lessonOutcome(currentSection) }}</p>
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

            <section v-if="mediaMode === 'VIDEO' && currentVideoChapter && activeVideoFrame" class="mt-7" aria-label="分章节视频">
              <div class="relative aspect-[4/3] overflow-hidden rounded-3xl bg-ink-panel text-panel-text shadow-xl sm:aspect-video">
                <div class="relative flex h-full flex-col justify-between p-5 sm:p-8">
                  <div class="flex items-start justify-between gap-4">
                    <div>
                      <p class="text-xs font-semibold text-panel-text/55">{{ visualKindLabel(currentVideoChapter.visualKind) }}</p>
                      <h3 class="mt-2 font-display text-2xl font-semibold sm:text-4xl">{{ currentVideoChapter.title }}</h3>
                    </div>
                    <span class="rounded-full border border-panel-text/20 px-3 py-1 text-xs">第 {{ currentVideoChapter.position }} 章</span>
                  </div>
                  <div class="mx-auto flex w-full max-w-md items-center justify-center gap-3" aria-hidden="true">
                    <span v-for="frame in Math.min(currentVideoChapter.frames.length, 5)" :key="frame" class="grid size-12 place-items-center rounded-2xl border border-panel-text/25 bg-panel-text/8 font-display text-lg font-semibold">{{ frame }}</span>
                  </div>
                  <div>
                    <p class="rounded-2xl bg-black/35 px-4 py-3 text-center text-sm leading-6 sm:text-base">{{ activeVideoFrame.subtitle }}</p>
                    <p v-if="activeVideoFrame.sourcePages.length" class="mt-2 text-center text-xs text-panel-text/60">规则书第 {{ activeVideoFrame.sourcePages.join('、') }} 页</p>
                  </div>
                </div>
              </div>
              <p class="mt-3 text-sm text-ink/55">{{ currentVideoChapter.visualCaption }}</p>
              <input class="mt-4 w-full accent-copper" type="range" min="0" :max="video?.durationMillis ?? 0" :value="narrationMillis" aria-label="视频播放位置" @input="seekNarration">
              <div class="mt-1 flex justify-between text-xs tabular-nums text-ink/45"><span>{{ formatDuration(narrationMillis) }}</span><span>{{ formatDuration(video?.durationMillis ?? 0) }}</span></div>
              <div class="mt-3 grid grid-cols-3 gap-2">
                <button :disabled="!audioAvailable" class="min-h-11 rounded-xl bg-copper px-3 text-sm font-semibold text-white disabled:opacity-35" @click="toggleNarration">{{ audioAvailable ? (narrationPlaying ? '暂停视频' : '播放视频') : '音轨不可用' }}</button>
                <button :disabled="!audioAvailable" class="min-h-11 rounded-xl border border-ink/15 px-3 text-sm font-semibold disabled:opacity-35" @click="replayCurrentSegment">重播画面</button>
                <button :disabled="!audioAvailable" class="min-h-11 rounded-xl border border-ink/15 px-3 text-sm font-semibold disabled:opacity-35" @click="cycleNarrationRate">{{ narrationRate }}×</button>
              </div>
              <nav class="mt-4 flex gap-2 overflow-x-auto pb-2" aria-label="视频章节跳转">
                <button
                  v-for="(chapter, index) in video?.chapters"
                  :key="chapter.position"
                  class="shrink-0 rounded-full border px-3 py-2 text-xs font-semibold"
                  :class="index === progress.currentIndex ? 'border-copper bg-copper/10 text-copper' : 'border-ink/10 text-ink/50'"
                  @click="selectSection(index)"
                >
                  {{ chapter.position }}. {{ chapter.title }}
                </button>
              </nav>
            </section>

            <div v-if="mediaMode !== 'VIDEO' && !hasVisualBlock" class="mt-7 rounded-3xl bg-indigo/8 p-4 sm:p-5">
              <p class="text-xs font-semibold text-indigo">对应原文页 · {{ visualKindLabel(currentSection.visualKind) }}</p>
              <figure v-if="currentVisualPageUrl && !visualImageFailed" class="my-5 overflow-hidden rounded-2xl border border-indigo/15 bg-paper">
                <a :href="currentVisualPageUrl" target="_blank" rel="noopener" title="打开大图">
                  <img :src="currentVisualPageUrl" :alt="`规则书第 ${currentSection.visualSourcePages[0]} 页，${currentSection.visualCaption}`" class="max-h-[26rem] w-full object-contain" loading="lazy" @error="visualImageFailed = true">
                </a>
                <figcaption class="flex items-center justify-between gap-3 border-t border-indigo/10 px-4 py-3 text-xs text-ink/50">
                  <span>配合第 {{ currentSection.visualSourcePages[0] }} 页来看</span>
                  <a :href="currentVisualPageUrl" target="_blank" rel="noopener" class="font-semibold text-indigo">放大</a>
                </figcaption>
              </figure>
              <div v-else class="my-6 flex items-center gap-2" aria-hidden="true">
                <span v-for="step in Math.min(currentSection.steps.length, 5)" :key="step" class="grid size-11 place-items-center rounded-full border-2 border-indigo/25 bg-paper font-display font-semibold text-indigo">{{ step }}</span>
                <span v-if="currentSection.steps.length > 1" class="h-0.5 flex-1 bg-indigo/20" />
              </div>
              <p class="text-sm leading-6 text-ink/65"><span class="font-semibold text-ink/80">本节规则关系：</span>{{ currentSection.visualCaption }}</p>
              <p v-if="currentSection.visualSourcePages.length" class="mt-2 text-xs font-semibold text-indigo">
                原文页：规则书第 {{ currentSection.visualSourcePages.join('、') }} 页
              </p>
            </div>

            <ol v-if="mediaMode !== 'VIDEO'" class="mt-7 space-y-4">
              <li
                v-for="step in currentSection.steps"
                :key="step.position"
                class="grid gap-3 rounded-2xl border p-4 sm:grid-cols-[3rem_1fr] sm:p-5"
                :class="{
                  'border-ink/20 bg-ink/[0.035]': step.kind === 'CHECK',
                  'border-indigo/20 bg-indigo/[0.035]': step.kind === 'VISUAL',
                  'border-sky-200 bg-sky-50/60': step.kind === 'FLOW',
                  'border-emerald-200 bg-emerald-50/60': step.kind === 'LEDGER',
                  'border-ink/8': !['CHECK', 'VISUAL', 'FLOW', 'LEDGER'].includes(step.kind),
                }"
              >
                <div class="flex items-center gap-2 sm:block">
                  <span class="grid size-10 place-items-center rounded-xl text-sm font-bold" :class="moveMeta(step.kind).tone">{{ moveMeta(step.kind).marker }}</span>
                  <span class="text-xs font-semibold text-ink/45 sm:mt-2 sm:block sm:text-center">{{ step.position }}</span>
                </div>
                <div>
                  <p class="text-xs font-semibold" :class="moveMeta(step.kind).tone.split(' ')[1]">{{ moveMeta(step.kind).label }}</p>
                  <h3 class="mt-1 font-display text-xl font-semibold leading-7">{{ step.heading || `第 ${step.position} 步` }}</h3>
                  <figure v-if="step.kind === 'VISUAL' && step.visualFocus && pageImageUrl(step.visualFocus.pageNumber) && !visualImageFailed" class="mt-4 overflow-hidden rounded-2xl border border-indigo/15 bg-paper">
                    <a :href="pageImageUrl(step.visualFocus.pageNumber)" target="_blank" rel="noopener" title="打开规则书大图" class="relative block">
                      <img :src="pageImageUrl(step.visualFocus.pageNumber)" :alt="`规则书第 ${step.visualFocus.pageNumber} 页，${step.text}`" class="block h-auto w-full" loading="lazy" @error="visualImageFailed = true">
                      <span class="pointer-events-none absolute rounded-md border-2 border-copper bg-copper/10 shadow-[0_0_0_2px_rgba(255,255,255,0.8)]" :style="visualFocusStyle(step.visualFocus)" aria-hidden="true" />
                    </a>
                    <figcaption class="flex flex-wrap items-center justify-between gap-2 border-t border-indigo/10 px-4 py-3 text-xs text-ink/50">
                      <span>框选位置：{{ step.visualFocus.label }}</span>
                      <span>第 {{ step.visualFocus.pageNumber }} 页</span>
                    </figcaption>
                  </figure>
                  <p class="mt-2 text-base leading-8 text-ink/75">{{ step.text }}</p>
                  <details v-if="step.sourcePages.length" class="mt-3">
                    <summary class="cursor-pointer text-sm font-semibold text-indigo">核对第 {{ step.sourcePages.join('、') }} 页</summary>
                    <p class="mt-2 rounded-xl bg-indigo/8 px-3 py-2 text-sm text-indigo">来源：规则书第 {{ step.sourcePages.join('、') }} 页</p>
                  </details>
                </div>
              </li>
            </ol>

            <details v-if="currentNarration" v-show="mediaMode === 'AUDIO'" open class="mt-7 rounded-2xl border border-indigo/15 bg-indigo/5 p-4 sm:p-5">
              <summary class="cursor-pointer list-none font-semibold text-indigo">
                <span class="flex items-center justify-between gap-3">
                  <span>本节解说稿</span>
                  <span class="text-xs">{{ currentNarration.supported ? '可回到原文核对' : '原文不足，跳过这一段' }}</span>
                </span>
              </summary>
              <ol class="mt-4 space-y-3 border-t border-indigo/10 pt-4" aria-label="同步字幕">
                <li
                  v-for="segment in currentNarration.segments"
                  :key="segment.position"
                  class="rounded-xl border px-3 py-2 text-sm leading-7 transition"
                  :class="activeCue?.chapterPosition === currentNarration.position && activeCue?.segmentPosition === segment.position ? 'border-copper/40 bg-copper/10 text-ink' : 'border-transparent text-ink/70'"
                >
                  <p>{{ segment.text }}</p>
                  <p v-if="segment.sourcePages.length" class="mt-1 text-xs font-semibold text-indigo">规则书第 {{ segment.sourcePages.join('、') }} 页</p>
                  <button class="mt-1 text-xs font-semibold text-copper" @click="seekToSegment(segment.position)">从本段播放</button>
                </li>
              </ol>
              <div class="mt-5 border-t border-indigo/10 pt-4">
                <p class="text-xs leading-5 text-ink/50">当前为 {{ narrationProvider }} 媒体管线测试音轨（非语音）；字幕和音轨共用同一份已验证稿件。</p>
                <input
                  class="mt-4 w-full accent-copper"
                  type="range"
                  min="0"
                  :max="narrationDurationMillis"
                  :value="narrationMillis"
                  aria-label="解说播放位置"
                  @input="seekNarration"
                >
                <div class="mt-1 flex justify-between text-xs tabular-nums text-ink/45">
                  <span>{{ formatDuration(narrationMillis) }}</span>
                  <span>{{ formatDuration(narrationDurationMillis) }}</span>
                </div>
                <div class="mt-3 grid grid-cols-3 gap-2">
                  <button class="min-h-10 rounded-xl bg-indigo px-3 text-sm font-semibold text-white" @click="toggleNarration">{{ narrationPlaying ? '暂停' : '播放' }}</button>
                  <button class="min-h-10 rounded-xl border border-indigo/20 px-3 text-sm font-semibold text-indigo" @click="replayCurrentSegment">重播本段</button>
                  <button class="min-h-10 rounded-xl border border-indigo/20 px-3 text-sm font-semibold text-indigo" @click="cycleNarrationRate">{{ narrationRate }}×</button>
                </div>
              </div>
            </details>

            <details class="mt-8 border-t border-ink/10 pt-7" aria-labelledby="lesson-question-title">
              <summary class="cursor-pointer list-none rounded-2xl border border-ink/10 px-4 py-4 font-semibold hover:bg-canvas">还有没明白的？展开问这一节</summary>
              <div class="mt-6">
                <p class="text-xs font-semibold text-copper">问问这一节</p>
                <div class="mt-2 flex flex-wrap items-end justify-between gap-3">
                  <div>
                    <h3 id="lesson-question-title" class="font-display text-2xl font-semibold">关于本节继续追问</h3>
                    <p class="mt-2 text-sm leading-6 text-ink/55">问题会自动沿用“{{ currentSection.title }}”及当前规则版本。</p>
                  </div>
                  <span class="rounded-full bg-indigo/8 px-3 py-1.5 text-xs font-semibold text-indigo">第 {{ currentSection.position }} 节上下文</span>
                </div>

                <ol v-if="previousAnswerTurns.length" class="mt-5 space-y-3" aria-label="本节之前的问答">
                  <li v-for="(turn, index) in previousAnswerTurns" :key="`${index}-${turn.question}`" class="rounded-2xl border border-ink/8 bg-canvas p-4">
                    <p class="text-xs font-semibold text-ink/45">{{ turn.learningIntent ? learningIntentLabel(turn.learningIntent) : '你问' }}</p>
                    <p class="mt-1 text-sm leading-6">{{ turn.question }}</p>
                    <p class="mt-3 border-l-2 border-copper pl-3 text-sm font-semibold leading-6">{{ turn.answer.shortVerdict }}</p>
                  </li>
                </ol>

                <div class="mt-5 rounded-2xl bg-copper/[0.07] p-4">
                  <p class="text-sm font-semibold">哪里还没弄明白？</p>
                  <p class="mt-1 text-xs leading-5 text-ink/50">选择一种方式，规则助手会重新查这一节的依据再讲一次。</p>
                  <div class="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
                    <button type="button" :disabled="answerLoading || !online" class="min-h-11 rounded-xl border border-copper/20 bg-paper px-3 text-sm font-semibold disabled:opacity-40" @click="requestLearningHelp('SIMPLIFY')">讲简单点</button>
                    <button type="button" :disabled="answerLoading || !online" class="min-h-11 rounded-xl border border-copper/20 bg-paper px-3 text-sm font-semibold disabled:opacity-40" @click="requestLearningHelp('EXAMPLE')">走个例子</button>
                    <button type="button" :disabled="answerLoading || !online" class="min-h-11 rounded-xl border border-copper/20 bg-paper px-3 text-sm font-semibold disabled:opacity-40" @click="requestLearningHelp('WHY')">前后怎么接</button>
                    <button type="button" :disabled="answerLoading || !online" class="min-h-11 rounded-xl border border-copper/20 bg-paper px-3 text-sm font-semibold disabled:opacity-40" @click="requestLearningHelp('EXCEPTIONS')">例外和限制</button>
                  </div>
                </div>

                <form class="mt-5" @submit.prevent="askCurrentSection">
                  <div class="mb-3 flex flex-wrap items-start gap-3">
                    <button
                      type="button"
                      class="min-h-11 rounded-xl border border-indigo/25 bg-indigo/5 px-4 text-sm font-semibold text-indigo transition hover:bg-indigo/10 disabled:cursor-not-allowed disabled:opacity-40"
                      :disabled="answerLoading || !online"
                      @click="cardOcrOpen = true"
                    >
                      拍照识别卡牌文字
                    </button>
                    <VoiceQuestionCapture :disabled="answerLoading || !online" @transcript="useVoiceTranscript" />
                  </div>
                  <label for="lesson-question" class="sr-only">针对当前讲解章节提问</label>
                  <textarea
                    id="lesson-question"
                    v-model="question"
                    rows="3"
                    maxlength="800"
                    :disabled="answerLoading || !online"
                    placeholder="例如：为什么完成目标后才计算这一分？"
                    class="w-full resize-y rounded-2xl border border-ink/15 bg-canvas px-4 py-3 leading-7 outline-none transition placeholder:text-ink/35 focus:border-indigo focus:ring-4 focus:ring-indigo/10 disabled:cursor-not-allowed disabled:opacity-55"
                  />
                  <div class="mt-3 flex flex-wrap items-center justify-between gap-3">
                    <p class="text-xs text-ink/45">{{ question.length }}/800 · 回答必须附带当前版本中的规则依据</p>
                    <button
                      type="submit"
                      :disabled="answerLoading || !online || !question.trim()"
                      class="min-h-11 rounded-xl bg-indigo px-5 text-sm font-semibold text-white transition hover:-translate-y-0.5 disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      {{ answerLoading ? '正在查找规则依据…' : online ? '提交问题' : '离线时无法提问' }}
                    </button>
                  </div>
                </form>

                <p v-if="answerError" class="mt-4 rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ answerError }}</p>
                <div v-else-if="answerLoading" class="mt-5 space-y-3 rounded-2xl border border-ink/8 p-5" aria-live="polite">
                  <p class="text-sm font-semibold">正在{{ activeLearningIntent ? learningIntentLabel(activeLearningIntent) : '理解这次追问' }}并重新核对规则书…</p>
                  <p class="text-xs leading-5 text-ink/50">上一问只帮助理解“它、这样、再一次”指什么；结论仍会重新查找并验证规则依据。</p>
                  <div class="h-4 w-4/5 animate-pulse rounded bg-ink/10" />
                  <div class="h-4 w-3/5 animate-pulse rounded bg-ink/10" />
                </div>

                <article v-else-if="answer" class="mt-5 overflow-hidden rounded-3xl border border-ink/10 bg-canvas" aria-live="polite">
                  <div class="p-5 sm:p-6">
                    <p class="text-xs font-semibold text-ink/45">{{ currentAnswerTurn?.learningIntent ? learningIntentLabel(currentAnswerTurn.learningIntent) : '你问' }}：{{ answeredQuestion }}</p>
                    <div class="flex flex-wrap items-center gap-2 text-xs font-semibold">
                      <span :class="answer.confidence === 'LOW' ? 'bg-red-50 text-red-700' : 'bg-emerald-50 text-emerald-700'" class="rounded-full px-3 py-1.5">{{ confidenceLabel(answer.confidence) }}</span>
                      <span class="rounded-full bg-ink/6 px-3 py-1.5 text-ink/60">{{ answer.confirmedRulingId ? '已确认裁定' : answer.official ? '官方来源' : '上传规则资料' }}</span>
                    </div>
                    <p class="mt-4 font-display text-xl font-semibold leading-8">{{ answer.shortVerdict }}</p>

                    <p v-if="answer.status === 'CLARIFICATION_REQUIRED'" class="mt-4 rounded-2xl bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-900">{{ answer.clarification }}</p>
                    <p v-else-if="answer.status !== 'ANSWERED'" class="mt-4 rounded-2xl bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-900">{{ answerFailureMessage(answer.status) }}</p>

                    <details v-if="answer.status === 'ANSWERED'" class="mt-5 border-t border-ink/10 pt-4">
                      <summary class="cursor-pointer font-semibold text-indigo">查看详细解释与例外</summary>
                      <p class="mt-3 leading-7 text-ink/70">{{ answer.explanation }}</p>
                      <ul v-if="answer.exceptions.length" class="mt-3 list-disc space-y-1 pl-5 text-sm leading-6 text-ink/65">
                        <li v-for="exception in answer.exceptions" :key="exception">{{ exception }}</li>
                      </ul>
                    </details>

                    <div v-if="answer.status === 'ANSWERED'" class="mt-5 flex flex-wrap gap-2 border-t border-ink/10 pt-4" aria-label="继续追问">
                      <button type="button" :disabled="answerLoading" class="min-h-10 rounded-xl border border-ink/12 px-3 text-sm font-semibold hover:bg-paper disabled:opacity-40" @click="requestLearningHelp('WHY')">前后怎么接</button>
                      <button type="button" :disabled="answerLoading" class="min-h-10 rounded-xl border border-ink/12 px-3 text-sm font-semibold hover:bg-paper disabled:opacity-40" @click="requestLearningHelp('EXAMPLE')">走个例子</button>
                      <button type="button" :disabled="answerLoading" class="min-h-10 rounded-xl border border-ink/12 px-3 text-sm font-semibold hover:bg-paper disabled:opacity-40" @click="requestLearningHelp('EXCEPTIONS')">例外和限制</button>
                    </div>
                  </div>

                  <details v-if="answer.citations.length" class="border-t border-indigo/15 bg-indigo/5 p-5 sm:p-6">
                    <summary class="cursor-pointer font-semibold text-indigo">规则出处与页码（{{ answer.citations.length }}）</summary>
                    <ol class="mt-4 space-y-3">
                      <li v-for="citation in answer.citations" :key="citation.chunkId" class="rounded-2xl border border-indigo/15 bg-paper p-4">
                        <div class="flex flex-wrap items-center justify-between gap-2">
                          <p class="font-semibold">{{ citation.heading }}</p>
                          <span class="text-xs font-semibold text-indigo">{{ citationPages(citation) }}</span>
                        </div>
                        <p class="mt-2 text-sm leading-6 text-ink/65">{{ citation.excerpt }}</p>
                      </li>
                    </ol>
                  </details>

                  <div v-if="answer.status === 'ANSWERED'" class="border-t border-ink/10 p-5 sm:p-6">
                    <p v-if="rulingError" class="rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ rulingError }}</p>
                    <div v-if="rulingConflict" class="rounded-2xl border border-amber-300 bg-amber-50 p-4" role="alert">
                      <p class="font-semibold text-amber-950">另一处编辑已经更新了这条裁定</p>
                      <p class="mt-1 text-sm leading-6 text-amber-900">为避免覆盖他人的修改，请加载服务器版本后重新编辑。</p>
                      <button class="mt-3 min-h-11 rounded-xl bg-amber-900 px-4 text-sm font-semibold text-white" :disabled="rulingSaving" @click="reloadRuling">加载最新版本</button>
                    </div>

                    <div v-else-if="ruling && editingRuling" class="space-y-4">
                      <div>
                        <label for="ruling-verdict" class="text-sm font-semibold">一句话裁定</label>
                        <textarea id="ruling-verdict" v-model="editedVerdict" rows="2" maxlength="2000" class="mt-2 w-full rounded-2xl border border-ink/15 bg-paper px-4 py-3 outline-none focus:border-indigo" />
                      </div>
                      <div>
                        <label for="ruling-explanation" class="text-sm font-semibold">详细解释</label>
                        <textarea id="ruling-explanation" v-model="editedExplanation" rows="5" maxlength="20000" class="mt-2 w-full rounded-2xl border border-ink/15 bg-paper px-4 py-3 outline-none focus:border-indigo" />
                      </div>
                      <div class="flex flex-wrap gap-3">
                        <button class="min-h-11 rounded-xl bg-indigo px-5 text-sm font-semibold text-white disabled:opacity-40" :disabled="rulingSaving || !editedVerdict.trim() || !editedExplanation.trim()" @click="saveRulingRevision">{{ rulingSaving ? '保存中…' : '保存修改' }}</button>
                        <button class="min-h-11 rounded-xl border border-ink/15 px-5 text-sm font-semibold" :disabled="rulingSaving" @click="editingRuling = false">取消</button>
                      </div>
                    </div>

                    <div v-else-if="ruling" class="flex flex-wrap items-center justify-between gap-3 rounded-2xl bg-emerald-50 p-4">
                      <div>
                        <p class="font-semibold text-emerald-900">已保存为确认裁定</p>
                        <p class="mt-1 text-xs text-emerald-800">版本 {{ ruling.version }} · 引用 {{ ruling.citations.length }} 条</p>
                      </div>
                      <button class="min-h-11 rounded-xl border border-emerald-700 px-4 text-sm font-semibold text-emerald-900" @click="editingRuling = true">编辑裁定</button>
                    </div>

                    <button v-else class="min-h-11 w-full rounded-xl border border-indigo/30 px-5 text-sm font-semibold text-indigo transition hover:bg-indigo/5 disabled:opacity-40" :disabled="rulingSaving" @click="confirmAnswer">{{ rulingSaving ? '正在保存…' : '保存为已确认裁定' }}</button>
                  </div>
                </article>
              </div>
            </details>
          </div>
        </section>
      </div>

      <nav v-if="lesson" class="fixed inset-x-0 bottom-0 z-30 border-t border-ink/10 bg-canvas/95 p-3 backdrop-blur lg:sticky lg:mx-auto lg:max-w-4xl lg:rounded-2xl lg:border" aria-label="讲解控制">
        <div class="mx-auto grid max-w-3xl grid-cols-[0.8fr_1fr_1.5fr] gap-2">
          <button :disabled="progress.currentIndex === 0" class="min-h-12 rounded-xl border border-ink/15 px-3 text-sm font-semibold disabled:opacity-35" @click="previousSection">上一节</button>
          <button class="min-h-12 rounded-xl border border-ink/15 px-3 text-sm font-semibold" @click="finish('skipped')">稍后再看</button>
          <button class="min-h-12 rounded-xl bg-copper px-3 text-sm font-semibold text-white" @click="finish('completed')">{{ progress.currentIndex === lesson.sections.length - 1 ? '我学完了' : '看懂了，下一节' }}</button>
        </div>
      </nav>

      <CardOcrCapture v-if="cardOcrOpen" @close="cardOcrOpen = false" @recognized="useCardText" />
    </div>
  </AppShell>
</template>
