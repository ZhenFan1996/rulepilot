<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import ConversationResetDialog from '@/components/ConversationResetDialog.vue'
import LessonAnswerPanel from '@/components/LessonAnswerPanel.vue'
import { useConfirmedRuling } from '@/composables/useConfirmedRuling'
import {
  useLessonAnswers,
  type AnswerTurn,
  type ConfirmedRuling,
  type CsrfResponse,
  type StructuredRuleAnswer,
} from '@/composables/useLessonAnswers'
import { useLessonQuestionInput } from '@/composables/useLessonQuestionInput'
import { notifyLoginRequired } from '@/lib/authSession'
import { useLocale } from '@/lib/locale'

interface GameSession {
  id: string
  editionId: string | null
  documentVersionId: string
}

interface ConversationTurnResponse {
  id: string
  question: string
  answer: StructuredRuleAnswer
  createdAt: string
}

const NON_SEMANTIC_ANSWER_SESSION_PLAYER_COUNT = 1
const CardOcrCapture = defineAsyncComponent(() => import('@/components/CardOcrCapture.vue'))
const props = defineProps<{
  active: boolean
  documentVersionId: string
  planId: string
  editionId?: string | null
  gameTitle: string
}>()
const { locale, t } = useLocale()
const session = ref<GameSession | null>(null)
const initializedVersion = ref('')
const initializing = ref(false)
const initializationError = ref('')
const resettingSession = ref(false)
const resetDialogOpen = ref(false)
const resetError = ref('')
const restoreQuestionAfterReset = ref(false)
const answerPanel = ref<{ focusQuestion?: () => void } | null>(null)
const cardOcrOpen = ref(false)
const online = ref(navigator.onLine)
let workspaceRequest = 0
let csrf: CsrfResponse | null = null

const copy = computed(() => locale.value === 'zh-CN' ? {
  eyebrow: '规则答疑 Agent',
  title: `正在回答《${props.gameTitle}》`,
  description: '这里的问答绑定到已下载的同一版本规则书，并会保留本次追问上下文。推荐对话仍在，随时可以切回去。',
  loading: '正在恢复这款桌游的答疑会话…',
  error: '暂时无法建立绑定到这份规则书的答疑会话。',
  retry: '重试',
  ready: '已绑定规则书，可以开始提问',
  login: '请先登录后开始规则答疑。',
  session: '无法创建或恢复规则答疑会话。',
  conversation: '无法恢复此前的规则问答。',
} : {
  eyebrow: 'Rules Q&A Agent',
  title: `Answering from ${props.gameTitle}`,
  description: 'Questions stay bound to the downloaded rulebook edition and preserve follow-up context. Your recommendation conversation remains available whenever you switch back.',
  loading: 'Restoring this game’s rules Q&A session…',
  error: 'A Q&A session bound to this rulebook cannot be established right now.',
  retry: 'Retry',
  ready: 'Bound to the rulebook and ready for questions',
  login: 'Sign in to start rules Q&A.',
  session: 'The rules Q&A session could not be created or restored.',
  conversation: 'Earlier rules questions could not be restored.',
})

async function csrfToken() {
  if (csrf) return csrf
  const response = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (response.status === 401) {
    notifyLoginRequired()
    throw new Error(copy.value.login)
  }
  if (!response.ok) throw new Error(t('lesson.answer.error.session'))
  csrf = await response.json() as CsrfResponse
  return csrf
}

function storageKey(versionId = props.documentVersionId) {
  return `rulepilot:recommendation-answer-session:${versionId}`
}

function rememberedSession(versionId: string) {
  try {
    return localStorage.getItem(storageKey(versionId))?.trim() || null
  } catch {
    return null
  }
}

function rememberSession(versionId: string, sessionId: string) {
  try {
    localStorage.setItem(storageKey(versionId), sessionId)
  } catch {
    // The server-side conversation remains usable when local persistence is unavailable.
  }
}

function forgetSession(versionId: string) {
  try {
    localStorage.removeItem(storageKey(versionId))
  } catch {
    // A fresh in-memory session can still be created.
  }
}

async function createSession(versionId: string) {
  const token = await csrfToken()
  const response = await fetch('/api/v1/game-sessions', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', [token.headerName]: token.token },
    body: JSON.stringify({
      editionId: props.editionId || undefined,
      documentVersionId: versionId,
      expansionIds: [],
      playerCount: NON_SEMANTIC_ANSWER_SESSION_PLAYER_COUNT,
      phase: locale.value === 'zh-CN' ? '规则问答' : 'Rules Q&A',
      activePlayer: null,
    }),
  })
  if (response.status === 401) {
    notifyLoginRequired()
    throw new Error(copy.value.login)
  }
  if (!response.ok) throw new Error(copy.value.session)
  return await response.json() as GameSession
}

async function restoreOrCreateSession(versionId: string) {
  const remembered = rememberedSession(versionId)
  if (remembered) {
    const response = await fetch(`/api/v1/game-sessions/${encodeURIComponent(remembered)}`, { credentials: 'include' })
    if (response.status === 401) {
      notifyLoginRequired()
      throw new Error(copy.value.login)
    }
    if (response.ok) {
      const restored = await response.json() as GameSession
      if (restored.documentVersionId === versionId) return restored
    }
    forgetSession(versionId)
  }
  const created = await createSession(versionId)
  if (created.documentVersionId !== versionId) throw new Error(copy.value.session)
  rememberSession(versionId, created.id)
  return created
}

async function conversation(versionId: string, sessionId: string) {
  const parameters = new URLSearchParams({ gameSessionId: sessionId })
  const response = await fetch(
    `/api/v1/document-versions/${encodeURIComponent(versionId)}/answers/conversation?${parameters}`,
    { credentials: 'include' },
  )
  if (!response.ok) throw new Error(copy.value.conversation)
  return await response.json() as ConversationTurnResponse[]
}

function restoredTurns(turns: ConversationTurnResponse[]): AnswerTurn[] {
  return turns.map(turn => ({ question: turn.question, answer: turn.answer, learningIntent: null }))
}

function rulingFrom(answer: StructuredRuleAnswer): ConfirmedRuling | null {
  if (!answer.confirmedRulingId || answer.confirmedRulingVersion === null) return null
  return {
    id: answer.confirmedRulingId,
    shortVerdict: answer.shortVerdict,
    explanation: answer.explanation,
    citations: answer.citations,
    exceptions: answer.exceptions,
    confidence: answer.confidence,
    status: 'CONFIRMED',
    version: answer.confirmedRulingVersion,
  }
}

const {
  question, answer, answeredQuestion, answerTurns, activeLearningIntent, answerLoading, answerError,
  agentTrace, answerRunId, cancelAnswer, clearAnswerFeedback, resetConversation, restoreConversation, submitQuestion,
} = useLessonAnswers({
  currentContext: () => session.value && props.documentVersionId && online.value
    ? {
        planId: props.planId,
        documentVersionId: props.documentVersionId,
        locale: locale.value,
        gameSessionId: session.value.id,
      }
    : null,
  currentLessonRequest: () => workspaceRequest,
  isCurrentLessonLoad: (request, planId) => request === workspaceRequest && planId === props.planId,
  requestLogin: async () => notifyLoginRequired(),
  onReceived: (_context, _question, received) => {
    const confirmed = rulingFrom(received)
    if (confirmed) applyRuling(confirmed)
    else resetRuling()
  },
})

const {
  ruling, saving: rulingSaving, error: rulingError, conflict: rulingConflict, editing: editingRuling,
  editedVerdict, editedExplanation, applyRuling, confirmAnswer, saveRulingRevision, reloadRuling, reset: resetRuling,
} = useConfirmedRuling({
  documentVersionId: computed(() => props.documentVersionId || null),
  answer,
  answeredQuestion,
  csrfToken,
  onApplied: () => undefined,
  messages: {
    createFailed: () => t('lesson.answer.ruling.createFailed'),
    createRequestFailed: () => t('lesson.answer.ruling.createRequestFailed'),
    updateFailed: () => t('lesson.answer.ruling.updateFailed'),
    updateRequestFailed: () => t('lesson.answer.ruling.updateRequestFailed'),
    reloadFailed: () => t('lesson.answer.ruling.reloadFailed'),
    reloadRequestFailed: () => t('lesson.answer.ruling.reloadRequestFailed'),
  },
})

function learningAnchorQuestion() {
  for (let index = answerTurns.value.length - 1; index >= 0; index--) {
    const turn = answerTurns.value[index]
    if (turn?.learningIntent === null) return turn.question
  }
  return answeredQuestion.value
}

const { askQuestion, requestLearningHelp, useCardText, useVoiceTranscript } = useLessonQuestionInput({
  question,
  learningAnchorQuestion,
  submitQuestion,
  clearAnswerFeedback,
  closeCardOcr: () => { cardOcrOpen.value = false },
})

async function loadWorkspace() {
  const versionId = props.documentVersionId
  if (!props.active || !versionId || !props.planId) return
  if (initializedVersion.value === versionId && session.value) return
  const request = ++workspaceRequest
  initializing.value = true
  initializationError.value = ''
  resetConversation(true)
  resetRuling()
  try {
    const restoredSession = await restoreOrCreateSession(versionId)
    const turns = await conversation(versionId, restoredSession.id)
    if (request !== workspaceRequest || versionId !== props.documentVersionId) return
    session.value = restoredSession
    initializedVersion.value = versionId
    restoreConversation(restoredTurns(turns))
    const confirmed = turns.length ? rulingFrom(turns.at(-1)!.answer) : null
    if (confirmed) applyRuling(confirmed)
  } catch (error) {
    if (request === workspaceRequest) {
      session.value = null
      initializedVersion.value = ''
      initializationError.value = error instanceof Error ? error.message : copy.value.error
    }
  } finally {
    if (request === workspaceRequest) initializing.value = false
  }
}

function requestNewSession() {
  if (initializing.value || resettingSession.value || answerLoading.value || rulingSaving.value || editingRuling.value || !answerTurns.value.length) return
  resetError.value = ''
  restoreQuestionAfterReset.value = false
  resetDialogOpen.value = true
}

function cancelNewSession() {
  if (resettingSession.value) return
  resetDialogOpen.value = false
  resetError.value = ''
  restoreQuestionAfterReset.value = false
}

function newSessionRestoreTarget() {
  if (!restoreQuestionAfterReset.value) return null
  restoreQuestionAfterReset.value = false
  answerPanel.value?.focusQuestion?.()
  return document.activeElement instanceof HTMLElement ? document.activeElement : null
}

async function createAndSwitchToNewSession() {
  const versionId = props.documentVersionId
  if (!props.active || !versionId || !props.planId || resettingSession.value) return
  const request = ++workspaceRequest
  resettingSession.value = true
  resetError.value = ''
  try {
    const created = await createSession(versionId)
    if (created.documentVersionId !== versionId) throw new Error(copy.value.session)
    const turns = await conversation(versionId, created.id)
    if (request !== workspaceRequest || versionId !== props.documentVersionId) return

    rememberSession(versionId, created.id)
    session.value = created
    initializedVersion.value = versionId
    restoreConversation(restoredTurns(turns), false)
    resetRuling()
    const confirmed = turns.length ? rulingFrom(turns.at(-1)!.answer) : null
    if (confirmed) applyRuling(confirmed)
    resettingSession.value = false
    restoreQuestionAfterReset.value = true
    resetDialogOpen.value = false
  } catch (error) {
    if (request === workspaceRequest) {
      resetError.value = error instanceof Error ? error.message : copy.value.error
    }
  } finally {
    if (request === workspaceRequest) resettingSession.value = false
  }
}

function updateOnline() {
  online.value = navigator.onLine
}

watch(() => props.active, active => {
  if (active) void loadWorkspace()
}, { immediate: true })
watch(() => props.documentVersionId, (value, previous) => {
  if (value === previous) return
  workspaceRequest += 1
  resettingSession.value = false
  resetDialogOpen.value = false
  resetError.value = ''
  restoreQuestionAfterReset.value = false
  session.value = null
  initializedVersion.value = ''
  initializationError.value = ''
  resetConversation(true)
  resetRuling()
  if (props.active) void loadWorkspace()
})
onMounted(() => {
  window.addEventListener('online', updateOnline)
  window.addEventListener('offline', updateOnline)
})
onBeforeUnmount(() => {
  workspaceRequest += 1
  window.removeEventListener('online', updateOnline)
  window.removeEventListener('offline', updateOnline)
})
</script>

<template>
  <section data-testid="recommendation-answer-workspace" tabindex="-1" class="max-h-[70vh] min-h-72 overflow-y-auto px-4 py-5 outline-none sm:min-h-[31rem] sm:px-6 sm:py-7 lg:max-h-[46rem]" aria-live="polite">
    <header class="rounded-2xl border border-indigo/15 bg-indigo/5 px-4 py-4">
      <div class="flex flex-wrap items-start justify-between gap-3">
        <div class="min-w-0">
          <p class="text-xs font-bold uppercase tracking-[0.12em] text-indigo">{{ copy.eyebrow }}</p>
          <h3 class="mt-1 font-display text-xl font-semibold">{{ copy.title }}</h3>
          <p class="mt-1 max-w-2xl text-xs leading-5 text-ink/50">{{ copy.description }}</p>
        </div>
        <span v-if="session" class="rounded-full bg-emerald-50 px-3 py-1.5 text-xs font-semibold text-emerald-800">{{ copy.ready }}</span>
      </div>
    </header>

    <p v-if="initializing" class="mt-5 rounded-xl bg-canvas p-8 text-center text-sm text-ink/55" role="status">{{ copy.loading }}</p>
    <section v-else-if="initializationError || !session" class="mt-5 rounded-xl border border-red-200 bg-red-50 p-6 text-center text-sm text-red-800" role="alert">
      <p>{{ initializationError || copy.error }}</p>
      <button type="button" class="mt-3 min-h-11 rounded-lg bg-indigo px-5 font-semibold text-white" @click="loadWorkspace()">{{ copy.retry }}</button>
    </section>
    <LessonAnswerPanel
      v-else
      ref="answerPanel"
      :question="question" :answer="answer" :answered-question="answeredQuestion" :answer-turns="answerTurns"
      :active-learning-intent="activeLearningIntent" :answer-loading="answerLoading" :answer-error="answerError"
      :agent-trace="agentTrace" :answer-run-id="answerRunId" :online="online" :ruling="ruling"
      :ruling-saving="rulingSaving" :clear-thread-disabled="rulingSaving || editingRuling || resettingSession" :ruling-error="rulingError" :ruling-conflict="rulingConflict"
      :editing-ruling="editingRuling" :edited-verdict="editedVerdict" :edited-explanation="editedExplanation"
      :show-header="false"
      @update:question="question = $event" @update:editing-ruling="editingRuling = $event"
      @update:edited-verdict="editedVerdict = $event" @update:edited-explanation="editedExplanation = $event"
      @ask="askQuestion" @cancel-answer="cancelAnswer" @request-help="requestLearningHelp"
      @open-card-ocr="cardOcrOpen = true" @voice-transcript="useVoiceTranscript" @clear-thread="requestNewSession"
      @confirm-ruling="confirmAnswer" @reload-ruling="reloadRuling" @save-ruling-revision="saveRulingRevision"
    />
    <CardOcrCapture v-if="cardOcrOpen" @close="cardOcrOpen = false" @recognized="useCardText" />
    <ConversationResetDialog
      kind="server-session"
      :open="resetDialogOpen"
      :pending="resettingSession"
      :error="resetError"
      :game-title="gameTitle"
      :turn-count="answerTurns.length"
      :restore-focus="newSessionRestoreTarget"
      @cancel="cancelNewSession"
      @confirm="createAndSwitchToNewSession"
    />
  </section>
</template>
