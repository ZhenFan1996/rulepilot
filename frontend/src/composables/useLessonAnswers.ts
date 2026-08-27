import { computed, getCurrentScope, onScopeDispose, ref } from 'vue'

import type { AppLocale } from '@/lib/locale'
import {
  answerAgentTrace,
  streamedAnswerTraceItem,
  type AnswerAgentActivity,
  type AnswerAgentTraceItem,
} from '@/lib/answerAgentTrace'
import {
  isAnswerRulingReference,
  parsePlayerFacingRuleAnswer,
  type AnswerRulingReference,
  type PlayerFacingRuleAnswer,
  type PlayerRuleCitation,
} from '@/lib/playerAnswerContract'
import {
  StructuredAnswerRequestError,
  StructuredAnswerStreamError,
  streamStructuredAnswer,
} from '@/lib/structuredAnswerStream'

const ANSWER_SOFT_BUDGET_SECONDS = 8

export type RuleCitation = PlayerRuleCitation
export type StructuredRuleAnswer = PlayerFacingRuleAnswer
export type { AnswerRulingReference } from '@/lib/playerAnswerContract'

export interface AnswerCreation {
  answer: StructuredRuleAnswer
  conversationTurnId?: string | null
  rulingReference: AnswerRulingReference
}

export type LearningIntent = 'SIMPLIFY' | 'EXAMPLE' | 'DEFINE' | 'WHY' | 'EXCEPTIONS' | 'SOURCE' | 'VERIFY'

export interface AnswerTurn {
  question: string
  answer: StructuredRuleAnswer
  learningIntent: LearningIntent | null
  rulingReference?: AnswerRulingReference | null
}

export interface ConfirmedRulingCitation extends RuleCitation {
  chunkId: string
  sectionType: string
}

export interface ConfirmedRuling {
  id: string
  shortVerdict: string
  explanation: string
  citations: ConfirmedRulingCitation[]
  exceptions: string[]
  confidence: StructuredRuleAnswer['confidence']
  status: 'CONFIRMED' | 'SUPERSEDED'
  version: number
}

export interface CsrfResponse {
  headerName: string
  token: string
}

interface AnswerContext {
  planId: string
  documentVersionId: string
  locale: AppLocale
  gameSessionId?: string
}

interface UseLessonAnswersOptions {
  currentContext: () => AnswerContext | null
  currentLessonRequest: () => number
  isCurrentLessonLoad: (request: number, planId: string) => boolean
  canRead?: () => boolean
  requestLogin: () => Promise<unknown>
  onReceived: (
    context: AnswerContext,
    question: string,
    answer: StructuredRuleAnswer,
    rulingReference: AnswerRulingReference,
  ) => void
}

/** Keeps a question attached to the exact lesson workspace that created it. */
export function useLessonAnswers(options: UseLessonAnswersOptions) {
  const question = ref('')
  const answer = ref<StructuredRuleAnswer | null>(null)
  const answeredQuestion = ref('')
  const answerTurns = ref<AnswerTurn[]>([])
  const answerRulingReference = ref<AnswerRulingReference | null>(null)
  const activeLearningIntent = ref<LearningIntent | null>(null)
  const answerLoading = ref(false)
  const answerError = ref('')
  const answerOutcome = ref<'none' | 'failed' | 'cancelled'>('none')
  const agentTrace = ref<AnswerAgentTraceItem[]>([])
  const streamedAnswerParts = ref<{ verdict: string; explanation: string }>({ verdict: '', explanation: '' })
  const answerElapsedSeconds = ref(0)
  const answerSoftBudgetReached = computed(() =>
    answerLoading.value && answerElapsedSeconds.value >= ANSWER_SOFT_BUDGET_SECONDS)
  let latestAnswerRequest = 0
  let traceTimer: ReturnType<typeof setTimeout> | null = null
  let answerClock: ReturnType<typeof setInterval> | null = null
  let activeAnswerController: AbortController | null = null
  let activeTraceController: AbortController | null = null
  let activeAnswerRunId: string | null = null
  let activeCancellationCsrf: CsrfResponse | null = null
  let disposed = false

  function isCurrentAnswerRequest(answerRequest: number, lessonRequest: number, planId: string) {
    return !disposed
      && answerRequest === latestAnswerRequest
      && options.isCurrentLessonLoad(lessonRequest, planId)
  }

  function resetConversation(clearQuestion = false) {
    latestAnswerRequest++
    requestServerAnswerCancellation()
    activeAnswerController?.abort()
    activeAnswerController = null
    cancelReadTransport()
    stopAnswerClock()
    if (clearQuestion) question.value = ''
    answer.value = null
    answeredQuestion.value = ''
    answerTurns.value = []
    answerRulingReference.value = null
    activeLearningIntent.value = null
    answerLoading.value = false
    answerError.value = ''
    answerOutcome.value = 'none'
    agentTrace.value = []
    streamedAnswerParts.value = { verdict: '', explanation: '' }
    clearActiveServerAnswer()
  }

  function restoreConversation(turns: AnswerTurn[], clearQuestion = true) {
    resetConversation(clearQuestion)
    answerTurns.value = [...turns]
    const latest = answerTurns.value.at(-1)
    if (!latest) return
    answer.value = latest.answer
    answerRulingReference.value = latest.rulingReference ?? null
    answeredQuestion.value = latest.question
  }

  function clearAnswerFeedback() {
    cancelReadTransport()
    answer.value = null
    answerError.value = ''
    answerOutcome.value = 'none'
    answerRulingReference.value = null
  }

  async function submitQuestion(text: string, learningIntent: LearningIntent | null) {
    const context = options.currentContext()
    if (!text || !context || answerLoading.value) return
    const responseLocale = context.locale
    let failureKind: AnswerRequestFailure = 'request'
    const lessonRequest = options.currentLessonRequest()
    const answerRequest = ++latestAnswerRequest
    cancelReadTransport()
    const controller = new AbortController()
    activeAnswerController = controller
    answerLoading.value = true
    startAnswerClock()
    activeLearningIntent.value = learningIntent
    answerError.value = ''
    answerOutcome.value = 'none'
    answer.value = null
    agentTrace.value = []
    streamedAnswerParts.value = { verdict: '', explanation: '' }
    answerRulingReference.value = null
    try {
      const csrfResponse = await fetch('/api/auth/csrf', {
        credentials: 'include',
        signal: controller.signal,
      })
      if (!isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId)) return
      if (csrfResponse.status === 401) {
        await options.requestLogin()
        return
      }
      if (!csrfResponse.ok) {
        failureKind = 'session'
        throw new Error('secure session unavailable')
      }
      const csrf = (await csrfResponse.json()) as CsrfResponse
      activeCancellationCsrf = csrf
      const previousTurn = answerTurns.value.at(-1)
      startRunIdentityFallback(context, answerRequest, lessonRequest)
      const response = await streamStructuredAnswer(`/api/v1/document-versions/${context.documentVersionId}/answers/stream`, {
        method: 'POST',
        credentials: 'include',
        signal: controller.signal,
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({
          question: text,
          gameSessionId: context.gameSessionId,
          previousQuestion: previousTurn?.question,
          learningIntent,
          language: context.locale,
        }),
      }, (runId) => {
        if (isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId)) {
          activeAnswerRunId = runId
          stopTracePolling()
        }
      }, {
        onActivity: (activity) => {
          if (!isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId)) return
          const item = streamedAnswerTraceItem(activity, context.locale)
          const current = agentTrace.value.findIndex(existing => existing.sequence === item.sequence)
          if (current < 0) agentTrace.value = [...agentTrace.value, item]
          else agentTrace.value = agentTrace.value.map((existing, index) => index === current ? item : existing)
        },
        onAnswerPart: (part) => {
          if (!isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId)) return
          streamedAnswerParts.value = { ...streamedAnswerParts.value, [part.field]: part.text }
        },
      })
      if (!isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId)) return
      const creation = parseAnswerCreation(response)
      if (!creation) {
        failureKind = 'unavailable'
        throw new Error('player answer response is invalid')
      }
      if (!isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId)) return
      const received = creation.answer
      const rulingReference = safeRulingReference(creation.rulingReference, received.citations.length)
      answerRulingReference.value = rulingReference
      answer.value = received
      answeredQuestion.value = text
      answerTurns.value = [
        ...answerTurns.value,
        { question: text, answer: received, learningIntent, rulingReference },
      ]
      question.value = ''
      options.onReceived(context, text, received, rulingReference)
    } catch (error) {
      if (!isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId)) return
      if (error instanceof StructuredAnswerRequestError && error.status === 401) {
        await options.requestLogin()
        return
      }
      if (error instanceof StructuredAnswerRequestError || error instanceof StructuredAnswerStreamError) {
        failureKind = 'unavailable'
      }
      answerError.value = answerRequestFailureCopy(failureKind, responseLocale)
      answerOutcome.value = 'failed'
    } finally {
      if (activeAnswerController === controller) activeAnswerController = null
      if (isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId)) {
        stopTracePolling()
        stopAnswerClock()
        clearActiveServerAnswer()
        answerLoading.value = false
        activeLearningIntent.value = null
      }
    }
  }

  function cancelAnswer() {
    if (!answerLoading.value) {
      cancelReadTransport()
      return
    }
    latestAnswerRequest++
    requestServerAnswerCancellation()
    activeAnswerController?.abort()
    activeAnswerController = null
    cancelReadTransport()
    stopAnswerClock()
    clearActiveServerAnswer()
    answerLoading.value = false
    activeLearningIntent.value = null
    agentTrace.value = []
    const fallback = options.currentContext()?.locale ?? 'zh-CN'
    answerError.value = answerRequestFailureCopy('cancelled', fallback)
    answerOutcome.value = 'cancelled'
  }

  function stopTracePolling() {
    if (traceTimer !== null) clearTimeout(traceTimer)
    traceTimer = null
    activeTraceController?.abort()
    activeTraceController = null
  }

  function startRunIdentityFallback(context: AnswerContext, answerRequest: number, lessonRequest: number) {
    stopTracePolling()
    if (options.canRead?.() === false) return
    traceTimer = setTimeout(async () => {
      traceTimer = null
      if (!isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId) || !answerLoading.value) return
      const controller = new AbortController()
      activeTraceController = controller
      try {
        const parameters = new URLSearchParams({ mode: 'QUESTION_ANSWER', subjectId: context.documentVersionId })
        const response = await fetch(`/api/v1/assistant-runs/latest?${parameters}`, {
          credentials: 'include',
          signal: controller.signal,
        })
        if (!response.ok || activeTraceController !== controller
          || !isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId)) return
        const details = await response.json() as AnswerRunDetails
        if (details.run.subjectId !== context.documentVersionId) return
        activeAnswerRunId = details.run.id
        if (!agentTrace.value.length) agentTrace.value = answerAgentTrace(details.activities, context.locale)
      } catch {
        // The SSE remains authoritative; this single lookup only recovers a lost run identity for cancellation.
      } finally {
        if (activeTraceController === controller) activeTraceController = null
      }
    }, 250)
  }

  function cancelReadTransport() {
    stopTracePolling()
  }

  function startAnswerClock() {
    stopAnswerClock()
    answerElapsedSeconds.value = 0
    const startedAt = Date.now()
    answerClock = setInterval(() => {
      answerElapsedSeconds.value = Math.max(0, Math.floor((Date.now() - startedAt) / 1_000))
    }, 1_000)
  }

  function stopAnswerClock() {
    if (answerClock !== null) clearInterval(answerClock)
    answerClock = null
  }

  function clearActiveServerAnswer() {
    activeAnswerRunId = null
    activeCancellationCsrf = null
  }

  function requestServerAnswerCancellation() {
    const runId = activeAnswerRunId
    const csrf = activeCancellationCsrf
    if (!runId || !csrf) return
    void fetch(`/api/v1/assistant-runs/${encodeURIComponent(runId)}/cancellation`, {
      method: 'POST',
      credentials: 'include',
      headers: { [csrf.headerName]: csrf.token },
    }).catch(() => {
      // The server-side hard deadline remains authoritative if cancellation cannot be delivered.
    })
  }

  if (getCurrentScope()) {
    onScopeDispose(() => {
      requestServerAnswerCancellation()
      disposed = true
      latestAnswerRequest += 1
      activeAnswerController?.abort()
      activeAnswerController = null
      cancelReadTransport()
      stopAnswerClock()
      clearActiveServerAnswer()
    })
  }

  return {
    question,
    answer,
    answeredQuestion,
    answerTurns,
    answerRulingReference,
    activeLearningIntent,
    answerLoading,
    answerError,
    answerOutcome,
    agentTrace,
    streamedAnswerParts,
    answerElapsedSeconds,
    answerSoftBudgetReached,
    cancelReadTransport,
    clearAnswerFeedback,
    cancelAnswer,
    resetConversation,
    restoreConversation,
    submitQuestion,
  }
}

type AnswerRequestFailure = 'session' | 'unavailable' | 'request' | 'cancelled'

function answerRequestFailureCopy(failure: AnswerRequestFailure, locale: AppLocale) {
  if (locale === 'en') {
    if (failure === 'session') {
      return "I couldn't establish a secure session. Your question is still here; review it and try again."
    }
    if (failure === 'unavailable') {
      return 'The rules answer service is unavailable right now. Your question is still here; review it and try again.'
    }
    if (failure === 'cancelled') {
      return 'Stopped waiting. This unfinished result will not replace the current page. You can edit the question and send it again.'
    }
    return "I couldn't send this question. It is still here; review it and try again."
  }
  if (failure === 'session') return '无法建立安全会话。问题仍保留在这里；检查后可以直接重试。'
  if (failure === 'unavailable') return '规则答疑暂时不可用。问题仍保留在这里；检查后可以直接重试。'
  if (failure === 'cancelled') return '已停止等待；这次未完成的结果不会替换当前页面。你可以修改问题后重新发送。'
  return '这次没有成功发送问题。问题仍保留在这里；检查后可以直接重试。'
}


function parseAnswerCreation(value: unknown): AnswerCreation | null {
  if (!isRecord(value) || !isAnswerRulingReference(value.rulingReference)
    || !(value.conversationTurnId === undefined
      || value.conversationTurnId === null
      || typeof value.conversationTurnId === 'string')) return null
  const answer = parsePlayerFacingRuleAnswer(value.answer)
  if (!answer) return null
  return {
    answer,
    conversationTurnId: value.conversationTurnId,
    rulingReference: {
      citationIds: [...value.rulingReference.citationIds],
      confirmedRulingId: value.rulingReference.confirmedRulingId,
      confirmedRulingVersion: value.rulingReference.confirmedRulingVersion,
    },
  }
}

function safeRulingReference(value: unknown, citationCount: number): AnswerRulingReference {
  if (!isAnswerRulingReference(value)
    || value.citationIds.length !== citationCount
    || new Set(value.citationIds).size !== value.citationIds.length
    || (value.confirmedRulingId === null) !== (value.confirmedRulingVersion === null)) {
    return { citationIds: [], confirmedRulingId: null, confirmedRulingVersion: null }
  }
  return {
    citationIds: [...value.citationIds],
    confirmedRulingId: value.confirmedRulingId,
    confirmedRulingVersion: value.confirmedRulingVersion,
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

interface AnswerRunDetails {
  run: { id: string; subjectId: string }
  activities: AnswerAgentActivity[]
}
