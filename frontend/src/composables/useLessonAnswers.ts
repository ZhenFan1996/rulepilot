import { getCurrentScope, onScopeDispose, ref } from 'vue'

import { useLocale, type AppLocale } from '@/lib/locale'
import {
  answerAgentTrace,
  type AnswerAgentActivity,
  type AnswerAgentTraceItem,
} from '@/lib/answerAgentTrace'

const ANSWER_HISTORY_LIMIT = 12

export interface RuleCitation {
  chunkId: string
  sectionType: string
  heading: string
  excerpt: string
  pageFrom: number
  pageTo: number
}

export interface StructuredRuleAnswer {
  status: 'ANSWERED' | 'ANSWERED_WITH_WARNING' | 'CLARIFICATION_REQUIRED' | 'INSUFFICIENT_EVIDENCE' | 'MODEL_TIMEOUT' | 'INVALID_MODEL_OUTPUT' | 'VERSION_CONFLICT'
  shortVerdict: string
  explanation: string
  citations: RuleCitation[]
  exceptions: string[]
  confidence: 'HIGH' | 'MEDIUM' | 'LOW'
  answerBasis?: 'DIRECT_RULE' | 'GROUNDED_APPLICATION' | null
  official: boolean
  confirmedRulingId: string | null
  confirmedRulingVersion: number | null
  clarification: string | null
  warnings: Array<{
    type: 'INDIRECT_CITATION' | 'LOW_CONFIDENCE' | 'REVIEW_UNRESOLVED' | 'REVIEW_UNAVAILABLE'
  }>
  calculations?: Array<{
    expression: string
    result: string
  }>
  situationChecks?: Array<{
    requirement: string
    status: 'CONFIRMED' | 'CONTRADICTED' | 'NOT_PROVIDED'
    playerFact: string
  }>
  walkthroughSteps?: Array<{
    instruction: string
    explanation: string
    orderBasis: 'RULE_ORDER' | 'EXPLANATION_ORDER'
  }>
  decisionBranches?: Array<{
    condition: string
    outcome: string
    basis: 'EXPLICIT_RULE' | 'RULEBOOK_EXAMPLE'
  }>
  exceptionClauses?: Array<{
    condition: string
    effect: string
  }>
  termDefinitions?: Array<{
    term: string
    definition: string
    boundary: string
  }>
  workedExamples?: Array<{
    setup: string
    action: string
    outcome: string
    basis: 'RULEBOOK_EXAMPLE' | 'EVIDENCE_BOUND_ILLUSTRATION'
  }>
  priorityResolutions?: Array<{
    baseRule: string
    competingRule: string
    resolution: string
    basis: 'EXPLICIT_OVERRIDE' | 'IMPOSSIBILITY_PRIORITY' | 'CONFLICT_ONLY_OVERRIDE'
  }>
  timingResolutions?: Array<{
    timingContext: string
    resolutionOrder: string
    orderSource: string
    basis: 'CURRENT_PLAYER_CHOOSES' | 'PRINTED_TOP_TO_BOTTOM' | 'NORMAL_TURN_ORDER'
  }>
  tieResolutions?: Array<{
    tieContext: string
    resolutionSteps: string[]
    finalOutcome: string
    basis: 'SINGLE_TIEBREAKER' | 'ORDERED_TIEBREAKERS' | 'RANK_REWARD_SHIFT' | 'POSITIONAL_PRIORITY'
  }>
  scopeResolutions?: Array<{
    ruleContext: string
    governingCondition: string
    currentSituation: string
    matchStatus: 'MATCHES_SCOPE' | 'OUTSIDE_SCOPE' | 'NEEDS_CONTEXT'
    effect: string
    basis: 'PLAYER_COUNT' | 'ROLE_PRESENCE' | 'GAME_MODE' | 'VARIANT_SELECTION' | 'PLAYER_COUNT_EXCEPTION'
  }>
  conceptComparisons?: Array<{
    leftConcept: string
    leftDefinition: string
    rightConcept: string
    rightDefinition: string
    commonGround: string
    keyDifference: string
    practicalBoundary: string
    basis: 'ACTION_WINDOW' | 'RESOURCE_FUNCTION' | 'STORAGE_STATUS' | 'RULE_SCOPE' | 'DEFINITION_BOUNDARY'
  }>
  ruleOptions?: Array<{
    decisionContext: string
    selectionRule: string
    optionName: string
    availabilityCondition: string
    result: string
    basis: 'SOURCE_SELECTION' | 'TIMING_CATALOG' | 'ALTERNATIVE_ACTION' | 'EXCLUSIVE_CHOICE'
  }>
}

export interface AnswerCreation {
  assistantRunId: string
  answer: StructuredRuleAnswer
}

export type LearningIntent = 'SIMPLIFY' | 'EXAMPLE' | 'DEFINE' | 'WHY' | 'EXCEPTIONS' | 'SOURCE' | 'VERIFY'

export interface AnswerTurn {
  question: string
  answer: StructuredRuleAnswer
  learningIntent: LearningIntent | null
}

export interface ConfirmedRuling {
  id: string
  shortVerdict: string
  explanation: string
  citations: RuleCitation[]
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
  requestLogin: () => Promise<unknown>
  onReceived: (context: AnswerContext, question: string, answer: StructuredRuleAnswer) => void
}

/** Keeps a question attached to the exact lesson workspace that created it. */
export function useLessonAnswers(options: UseLessonAnswersOptions) {
  const { t } = useLocale()
  const question = ref('')
  const answer = ref<StructuredRuleAnswer | null>(null)
  const answeredQuestion = ref('')
  const answerTurns = ref<AnswerTurn[]>([])
  const activeLearningIntent = ref<LearningIntent | null>(null)
  const answerLoading = ref(false)
  const answerError = ref('')
  const agentTrace = ref<AnswerAgentTraceItem[]>([])
  const answerRunId = ref('')
  let latestAnswerRequest = 0
  let traceTimer: ReturnType<typeof setTimeout> | null = null
  let activeAnswerController: AbortController | null = null

  function isCurrentAnswerRequest(answerRequest: number, lessonRequest: number, planId: string) {
    return answerRequest === latestAnswerRequest && options.isCurrentLessonLoad(lessonRequest, planId)
  }

  function resetConversation(clearQuestion = false) {
    latestAnswerRequest++
    activeAnswerController?.abort()
    activeAnswerController = null
    stopTracePolling()
    if (clearQuestion) question.value = ''
    answer.value = null
    answeredQuestion.value = ''
    answerTurns.value = []
    activeLearningIntent.value = null
    answerLoading.value = false
    answerError.value = ''
    agentTrace.value = []
    answerRunId.value = ''
  }

  function restoreConversation(turns: AnswerTurn[]) {
    resetConversation(true)
    answerTurns.value = turns.slice(-ANSWER_HISTORY_LIMIT)
    const latest = answerTurns.value.at(-1)
    if (!latest) return
    answer.value = latest.answer
    answeredQuestion.value = latest.question
  }

  function clearAnswerFeedback() {
    answer.value = null
    answerError.value = ''
    answerRunId.value = ''
  }

  async function submitQuestion(text: string, learningIntent: LearningIntent | null) {
    const context = options.currentContext()
    if (!text || !context || answerLoading.value) return
    const lessonRequest = options.currentLessonRequest()
    const answerRequest = ++latestAnswerRequest
    const controller = new AbortController()
    activeAnswerController = controller
    answerLoading.value = true
    activeLearningIntent.value = learningIntent
    answerError.value = ''
    answer.value = null
    agentTrace.value = []
    answerRunId.value = ''
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
      if (!csrfResponse.ok) throw new Error(t('lesson.answer.error.session'))
      const csrf = (await csrfResponse.json()) as CsrfResponse
      const previousTurn = answerTurns.value.at(-1)
      const submittedAt = Date.now()
      startTracePolling(context, answerRequest, lessonRequest, submittedAt)
      const response = await fetch(`/api/v1/document-versions/${context.documentVersionId}/answers`, {
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
      })
      if (!isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId)) return
      if (response.status === 401) {
        await options.requestLogin()
        return
      }
      if (!response.ok) throw new Error(t('lesson.answer.error.unavailable'))
      const creation = (await response.json()) as AnswerCreation
      if (!isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId)) return
      const received = creation.answer
      answerRunId.value = creation.assistantRunId
      answer.value = received
      answeredQuestion.value = text
      answerTurns.value = [
        ...answerTurns.value,
        { question: text, answer: received, learningIntent },
      ].slice(-ANSWER_HISTORY_LIMIT)
      question.value = ''
      options.onReceived(context, text, received)
      void loadFinalTrace(creation.assistantRunId, context, answerRequest, lessonRequest)
    } catch (error) {
      if (!isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId)) return
      answerError.value = error instanceof Error ? error.message : t('lesson.answer.error.request')
    } finally {
      if (activeAnswerController === controller) activeAnswerController = null
      if (isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId)) {
        stopTracePolling()
        answerLoading.value = false
        activeLearningIntent.value = null
      }
    }
  }

  function cancelAnswer() {
    if (!answerLoading.value) return
    latestAnswerRequest++
    activeAnswerController?.abort()
    activeAnswerController = null
    stopTracePolling()
    answerLoading.value = false
    activeLearningIntent.value = null
    agentTrace.value = []
    answerRunId.value = ''
    answerError.value = t('lesson.answer.cancelled')
  }

  function startTracePolling(
    context: AnswerContext,
    answerRequest: number,
    lessonRequest: number,
    submittedAt: number,
  ) {
    stopTracePolling()
    const poll = async () => {
      if (!isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId) || !answerLoading.value) return
      try {
        const params = new URLSearchParams({ mode: 'QUESTION_ANSWER', subjectId: context.documentVersionId })
        const response = await fetch(`/api/v1/assistant-runs/latest?${params}`, { credentials: 'include' })
        if (response.ok) {
          const details = await response.json() as AnswerRunDetails
          const createdAt = Date.parse(details.run.createdAt)
          if (details.run.subjectId === context.documentVersionId && createdAt >= submittedAt - 1_000) {
            agentTrace.value = answerAgentTrace(details.activities, context.locale)
          }
        }
      } catch {
        // The answer request remains authoritative; progress polling retries without replacing the answer state.
      }
      if (isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId) && answerLoading.value) {
        traceTimer = setTimeout(poll, 600)
      }
    }
    traceTimer = setTimeout(poll, 250)
  }

  async function loadFinalTrace(
    runId: string,
    context: AnswerContext,
    answerRequest: number,
    lessonRequest: number,
  ) {
    try {
      const response = await fetch(`/api/v1/assistant-runs/${runId}`, { credentials: 'include' })
      if (!response.ok || !isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId)) return
      const details = await response.json() as AnswerRunDetails
      agentTrace.value = answerAgentTrace(details.activities, context.locale)
    } catch {
      // A completed answer remains usable even when optional execution details cannot be loaded.
    }
  }

  function stopTracePolling() {
    if (traceTimer !== null) clearTimeout(traceTimer)
    traceTimer = null
  }

  if (getCurrentScope()) {
    onScopeDispose(() => {
      activeAnswerController?.abort()
      activeAnswerController = null
      stopTracePolling()
    })
  }

  return {
    question,
    answer,
    answeredQuestion,
    answerTurns,
    activeLearningIntent,
    answerLoading,
    answerError,
    agentTrace,
    answerRunId,
    clearAnswerFeedback,
    cancelAnswer,
    resetConversation,
    restoreConversation,
    submitQuestion,
  }
}

interface AnswerRunDetails {
  run: {
    id: string
    subjectId: string
    createdAt: string
  }
  activities: AnswerAgentActivity[]
}
