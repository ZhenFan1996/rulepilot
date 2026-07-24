import { ref } from 'vue'

import type { AppLocale } from '@/lib/locale'

export interface RuleCitation {
  chunkId: string
  sectionType: string
  heading: string
  excerpt: string
  pageFrom: number
  pageTo: number
}

export interface StructuredRuleAnswer {
  status: 'ANSWERED' | 'CLARIFICATION_REQUIRED' | 'INSUFFICIENT_EVIDENCE' | 'MODEL_TIMEOUT' | 'INVALID_MODEL_OUTPUT' | 'VERSION_CONFLICT'
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
}

export interface AnswerCreation {
  assistantRunId: string
  answer: StructuredRuleAnswer
}

export type LearningIntent = 'SIMPLIFY' | 'EXAMPLE' | 'WHY' | 'EXCEPTIONS'

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
  playerCount: number
  section: {
    topicKey: string
    title: string
    coverageTags: string[]
  }
  locale: AppLocale
}

interface UseLessonAnswersOptions {
  currentContext: () => AnswerContext | null
  currentLessonRequest: () => number
  isCurrentLessonLoad: (request: number, planId: string) => boolean
  requestLogin: () => Promise<unknown>
  onReceived: (context: AnswerContext, question: string, answer: StructuredRuleAnswer) => void
}

/** Keeps a question attached to the exact lesson and chapter that created it. */
export function useLessonAnswers(options: UseLessonAnswersOptions) {
  const question = ref('')
  const answer = ref<StructuredRuleAnswer | null>(null)
  const answeredQuestion = ref('')
  const answerTurns = ref<AnswerTurn[]>([])
  const activeLearningIntent = ref<LearningIntent | null>(null)
  const answerLoading = ref(false)
  const answerError = ref('')
  let latestAnswerRequest = 0

  function isCurrentAnswerRequest(answerRequest: number, lessonRequest: number, planId: string) {
    return answerRequest === latestAnswerRequest && options.isCurrentLessonLoad(lessonRequest, planId)
  }

  function resetConversation(clearQuestion = false) {
    latestAnswerRequest++
    if (clearQuestion) question.value = ''
    answer.value = null
    answeredQuestion.value = ''
    answerTurns.value = []
    activeLearningIntent.value = null
    answerLoading.value = false
    answerError.value = ''
  }

  function clearAnswerFeedback() {
    answer.value = null
    answerError.value = ''
  }

  async function submitQuestion(text: string, learningIntent: LearningIntent | null) {
    const context = options.currentContext()
    if (!text || !context || answerLoading.value) return
    const lessonRequest = options.currentLessonRequest()
    const answerRequest = ++latestAnswerRequest
    answerLoading.value = true
    activeLearningIntent.value = learningIntent
    answerError.value = ''
    answer.value = null
    try {
      const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'include' })
      if (!isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId)) return
      if (csrfResponse.status === 401) {
        await options.requestLogin()
        return
      }
      if (!csrfResponse.ok) throw new Error('无法建立安全会话，请稍后重试。')
      const csrf = (await csrfResponse.json()) as CsrfResponse
      const previousTurn = answerTurns.value.at(-1)
      const response = await fetch(`/api/v1/document-versions/${context.documentVersionId}/answers`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({
          question: text,
          currentLessonSection: [context.section.topicKey, context.section.title, ...context.section.coverageTags].join(' '),
          playerCount: context.playerCount,
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
      if (!response.ok) throw new Error('暂时无法回答这个问题，请稍后重试。')
      const creation = (await response.json()) as AnswerCreation
      if (!isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId)) return
      const received = creation.answer
      answer.value = received
      answeredQuestion.value = text
      answerTurns.value.push({ question: text, answer: received, learningIntent })
      options.onReceived(context, text, received)
    } catch (error) {
      if (!isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId)) return
      answerError.value = error instanceof Error ? error.message : '提问失败，请稍后重试。'
    } finally {
      if (isCurrentAnswerRequest(answerRequest, lessonRequest, context.planId)) {
        answerLoading.value = false
        activeLearningIntent.value = null
      }
    }
  }

  return {
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
  }
}
