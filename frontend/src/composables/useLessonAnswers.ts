import { computed, getCurrentScope, onScopeDispose, ref } from 'vue'

import type { AppLocale } from '@/lib/locale'
import { answerFailureDescriptor } from '@/lib/playerFailureSemantics'
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
  type AnswerStreamFailureRecovery,
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

export interface AnswerFailureRecovery extends AnswerStreamFailureRecovery {
  code: string
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
  const answerFailureRecovery = ref<AnswerFailureRecovery | null>(null)
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
    answerFailureRecovery.value = null
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
    answerFailureRecovery.value = null
    answerOutcome.value = 'none'
    answerRulingReference.value = null
  }

  async function submitQuestion(text: string, learningIntent: LearningIntent | null) {
    const context = options.currentContext()
    if (!text || !context || answerLoading.value) return
    const responseLocale = context.locale
    const lessonRequest = options.currentLessonRequest()
    const answerRequest = ++latestAnswerRequest
    cancelReadTransport()
    const controller = new AbortController()
    activeAnswerController = controller
    answerLoading.value = true
    startAnswerClock()
    activeLearningIntent.value = learningIntent
    answerError.value = ''
    answerFailureRecovery.value = null
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
      if (!csrfResponse.ok) throw new StructuredAnswerRequestError(csrfResponse.status)
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
      if (!creation) throw new InvalidAnswerResultError()
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
      const recovery = error instanceof StructuredAnswerStreamError
        ? streamFailureRecovery(error, responseLocale)
        : error instanceof StructuredAnswerRequestError
          ? answerFailureRecoveryForHttpStatus(error.status, responseLocale)
          : error instanceof InvalidAnswerResultError
            ? answerFailureRecoveryFor('invalid-result', responseLocale)
            : answerFailureRecoveryFor('request', responseLocale)
      answerFailureRecovery.value = recovery
      answerError.value = recovery.message
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
    const recovery = answerFailureRecoveryFor('cancelled', fallback)
    answerFailureRecovery.value = recovery
    answerError.value = recovery.message
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
    answerFailureRecovery,
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

export type AnswerRequestFailure =
  | 'context'
  | 'service'
  | 'timeout'
  | 'rate-limit'
  | 'invalid-result'
  | 'request'
  | 'cancelled'

class InvalidAnswerResultError extends Error {}

function streamFailureRecovery(
  failure: StructuredAnswerStreamError,
  locale: AppLocale,
): AnswerFailureRecovery {
  if (!failure.recovery.message.trim() || !failure.recovery.actionLabel.trim()) {
    return answerFailureRecoveryFor('request', locale)
  }
  return {
    code: failure.code,
    message: failure.recovery.message,
    actionLabel: failure.recovery.actionLabel,
    draft: failure.recovery.draft,
    canRetryUnchanged: failure.recovery.canRetryUnchanged,
  }
}

export function answerFailureRecoveryForHttpStatus(
  status: number,
  locale: AppLocale,
): AnswerFailureRecovery {
  if (status === 408) return answerFailureRecoveryFor('timeout', locale)
  if (status === 429) return answerFailureRecoveryFor('rate-limit', locale)
  if (status >= 400 && status < 500) return answerFailureRecoveryFor('context', locale)
  if (status >= 500 && status < 600) return answerFailureRecoveryFor('service', locale)
  return answerFailureRecoveryFor('request', locale)
}

export function answerFailureRecoveryFor(
  failure: AnswerRequestFailure,
  locale: AppLocale,
): AnswerFailureRecovery {
  if (locale === 'en') {
    if (failure === 'context') {
      return {
        code: 'answer_context_invalid',
        message: 'This question no longer matches the current signed-in rulebook context. Refresh or reopen the rulebook or session before sending it again.',
        actionLabel: 'Restore the answer context',
        draft: '',
        canRetryUnchanged: false,
      }
    }
    if (failure === 'service') {
      return {
        code: 'answer_service_unavailable',
        message: 'The rules answer service is temporarily unavailable. Your question is still here; after the service recovers, the same question can be retried unchanged.',
        actionLabel: 'Retry after recovery',
        draft: '',
        canRetryUnchanged: true,
      }
    }
    if (failure === 'timeout') {
      return {
        code: 'answer_timeout',
        message: 'This answer attempt timed out and stopped before publishing a result. The question and completed context remain available for an unchanged retry.',
        actionLabel: 'Retry this question',
        draft: '',
        canRetryUnchanged: true,
      }
    }
    if (failure === 'rate-limit') {
      return {
        code: 'answer_rate_limited',
        message: 'Too many answer requests were sent in a short time. Wait for the limit to clear; then the same question can be retried unchanged.',
        actionLabel: 'Wait, then retry',
        draft: '',
        canRetryUnchanged: true,
      }
    }
    if (failure === 'invalid-result') {
      return {
        code: 'answer_result_invalid',
        message: 'The returned answer did not pass the response contract, so it was not shown. This is an internal correction failure; the question itself was not rejected.',
        actionLabel: 'Retry the same question',
        draft: '',
        canRetryUnchanged: true,
      }
    }
    if (failure === 'cancelled') {
      return {
        code: 'answer_cancelled',
        message: 'Stopped waiting. This unfinished result will not replace the current page; the question remains available for a fresh attempt.',
        actionLabel: 'Retry the same question',
        draft: '',
        canRetryUnchanged: true,
      }
    }
    return {
      code: 'answer_transport_failed',
      message: "I couldn't send this question because the request transport did not complete. The question remains available for an unchanged retry.",
      actionLabel: 'Retry the same question',
      draft: '',
      canRetryUnchanged: true,
    }
  }
  if (failure === 'context') {
    return {
      code: 'answer_context_invalid',
      message: '当前登录会话或规则书上下文已不匹配。请先刷新或重新打开规则书、恢复会话，再发送问题。',
      actionLabel: '恢复答疑上下文',
      draft: '',
      canRetryUnchanged: false,
    }
  }
  if (failure === 'service') {
    return {
      code: 'answer_service_unavailable',
      message: '规则答疑服务暂时不可用。问题仍保留在这里；服务恢复后，可以原样重试同一个问题。',
      actionLabel: '服务恢复后重试',
      draft: '',
      canRetryUnchanged: true,
    }
  }
  if (failure === 'timeout') {
    return {
      code: 'answer_timeout',
      message: '本轮答疑已超时停止，没有发布未完成结果；问题和已完成上下文都保留，可以原样发起新任务。',
      actionLabel: '重试这个问题',
      draft: '',
      canRetryUnchanged: true,
    }
  }
  if (failure === 'rate-limit') {
    return {
      code: 'answer_rate_limited',
      message: '短时间内的答疑请求过多。请等待限流解除；解除后，可以原样重试同一个问题。',
      actionLabel: '等待后重试',
      draft: '',
      canRetryUnchanged: true,
    }
  }
  if (failure === 'invalid-result') {
    return {
      code: 'answer_result_invalid',
      message: '返回的答案没有通过响应合同，因此未显示。这是内部修正失败，问题本身没有被拒绝。',
      actionLabel: '原样重试问题',
      draft: '',
      canRetryUnchanged: true,
    }
  }
  if (failure === 'cancelled') {
    return {
      code: 'answer_cancelled',
      message: '已停止等待；这次未完成的结果不会替换当前页面，原问题仍可用于发起新任务。',
      actionLabel: '原样重试问题',
      draft: '',
      canRetryUnchanged: true,
    }
  }
  return {
    code: 'answer_transport_failed',
    message: '这次请求传输没有完成。问题仍保留，可以原样发起新任务。',
    actionLabel: '原样重试问题',
    draft: '',
    canRetryUnchanged: true,
  }
}

export function answerFailureRetrySuitability(
  recovery: AnswerFailureRecovery,
  locale: AppLocale,
) {
  const english = locale === 'en'
  const category = answerFailureDescriptor(
    recovery.code,
    recovery.canRetryUnchanged,
    locale,
  ).category
  if (category === 'repair-required') {
    return english
      ? 'Repair the reported context, source, identity, or authorization boundary before sending this request again.'
      : '请先修复上面报告的上下文、来源、身份或认证边界，再重新发送。'
  }
  if (category === 'internal-correction') {
    return english
      ? 'The question was not rejected. Internal JSON correction stopped before publication; a fresh attempt can reuse it unchanged.'
      : '问题没有被拒绝；内部 JSON 修正未能发布结果，可以保留原问题启动新任务。'
  }
  return english
    ? 'The question and completed context are preserved and can be retried unchanged after the temporary boundary clears.'
    : '问题和已完成上下文都已保留；暂时边界解除后可以原样重试。'
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
