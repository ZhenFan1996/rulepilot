import { afterEach, describe, expect, it, vi } from 'vitest'

import { useLessonAnswers } from './useLessonAnswers'
import { setLocale } from '@/lib/locale'

function createAnswers() {
  return useLessonAnswers({
    currentContext: () => ({
      planId: 'plan-1',
      documentVersionId: 'document-1',
      playerCount: 4,
      section: { topicKey: 'ACTIONS', title: 'Actions', coverageTags: [] },
      locale: 'en',
    }),
    currentLessonRequest: () => 1,
    isCurrentLessonLoad: () => true,
    requestLogin: vi.fn(),
    onReceived: vi.fn(),
  })
}

describe('useLessonAnswers', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    setLocale('zh-CN')
  })

  it('localizes a failed secure-session request in the active player language', async () => {
    setLocale('en')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 503 })))
    const answers = createAnswers()

    await answers.submitQuestion('When does this resolve?', null)

    expect(answers.answerError.value).toBe('We could not establish a secure session. Please try again shortly.')
  })

  it('localizes an unavailable answer after a secure session is established', async () => {
    setLocale('en')
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'token' }))
      .mockResolvedValueOnce(new Response(null, { status: 503 })))
    const answers = createAnswers()

    await answers.submitQuestion('When does this resolve?', null)

    expect(answers.answerError.value).toBe('We cannot answer that question right now. Please try again shortly.')
  })

  it('localizes an unexpected request failure instead of leaking a Chinese fallback', async () => {
    setLocale('en')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue({}))
    const answers = createAnswers()

    await answers.submitQuestion('When does this resolve?', null)

    expect(answers.answerError.value).toBe('Your question could not be sent. Please try again shortly.')
  })

  it('aborts a slow answer while preserving the editable question and prior thread', async () => {
    setLocale('en')
    let answerSignal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/auth/csrf') {
        return Promise.resolve(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'token' }))
      }
      answerSignal = init?.signal ?? undefined
      return new Promise<Response>((_resolve, reject) => {
        answerSignal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
      })
    }))
    const answers = createAnswers()
    const prior = {
      status: 'ANSWERED' as const,
      shortVerdict: 'Resolve it after scoring.', explanation: 'The cited order puts scoring first.',
      citations: [], exceptions: [], confidence: 'HIGH' as const, official: false,
      confirmedRulingId: null, confirmedRulingVersion: null, clarification: null, warnings: [],
    }
    answers.restoreConversation([{ question: 'When does it resolve?', answer: prior, learningIntent: null }])
    answers.question.value = 'What if scoring is interrupted?'

    const pending = answers.submitQuestion(answers.question.value, null)
    await vi.waitFor(() => expect(answerSignal).toBeDefined())
    answers.cancelAnswer()
    await pending

    expect(answerSignal?.aborted).toBe(true)
    expect(answers.answerLoading.value).toBe(false)
    expect(answers.question.value).toBe('What if scoring is interrupted?')
    expect(answers.answerTurns.value).toHaveLength(1)
    expect(answers.answerError.value).toBe('Stopped waiting. This unfinished result will not replace the current page. You can edit the question and send it again.')
    expect(answers.agentTrace.value).toEqual([])
  })

  it('keeps the completed run id available for player support and audit', async () => {
    const runId = '11111111-1111-4111-8111-111111111111'
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'token' })
      if (path.includes('/answers')) {
        return Response.json({
          assistantRunId: runId,
          answer: {
            status: 'INSUFFICIENT_EVIDENCE', shortVerdict: 'Not enough evidence.', explanation: '', citations: [],
            exceptions: [], confidence: 'LOW', official: false, confirmedRulingId: null,
            confirmedRulingVersion: null, clarification: null, warnings: [],
          },
        })
      }
      return Response.json({ run: { id: runId, subjectId: 'document-1', createdAt: '2026-08-03T00:00:00Z' }, activities: [] })
    }))
    const answers = createAnswers()

    await answers.submitQuestion('When does this resolve?', null)

    expect(answers.answerRunId.value).toBe(runId)
  })

  it('sends a verification challenge with the exact previous question as retrieval context', async () => {
    const answerRequests: Array<Record<string, unknown>> = []
    let answerNumber = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'token' })
      if (path.includes('/answers') && init?.method === 'POST') {
        answerRequests.push(JSON.parse(String(init.body)) as Record<string, unknown>)
        answerNumber += 1
        return Response.json({
          assistantRunId: `${answerNumber}1111111-1111-4111-8111-111111111111`,
          answer: {
            status: 'ANSWERED', shortVerdict: 'Verified.', explanation: 'Supported.', citations: [],
            exceptions: [], confidence: 'HIGH', official: false, confirmedRulingId: null,
            confirmedRulingVersion: null, clarification: null, warnings: [],
          },
        })
      }
      return new Response(null, { status: 404 })
    }))
    const answers = createAnswers()

    await answers.submitQuestion('When does this action score?', null)
    await answers.submitQuestion('Search again and verify the previous answer.', 'VERIFY')

    expect(answerRequests[1]).toMatchObject({
      question: 'Search again and verify the previous answer.',
      previousQuestion: 'When does this action score?',
      learningIntent: 'VERIFY',
    })
  })

  it('sends a clarification reply with the unresolved question as bounded context', async () => {
    const answerRequests: Array<Record<string, unknown>> = []
    let answerNumber = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'token' })
      if (path.includes('/answers') && init?.method === 'POST') {
        answerRequests.push(JSON.parse(String(init.body)) as Record<string, unknown>)
        answerNumber += 1
        return Response.json({
          assistantRunId: `${answerNumber}2222222-2222-4222-8222-222222222222`,
          answer: {
            status: answerNumber === 1 ? 'CLARIFICATION_REQUIRED' : 'ANSWERED',
            shortVerdict: answerNumber === 1 ? 'Please identify the object.' : 'It triggers after scoring.',
            explanation: '', citations: [], exceptions: [], confidence: answerNumber === 1 ? 'LOW' : 'HIGH',
            official: false, confirmedRulingId: null, confirmedRulingVersion: null,
            clarification: answerNumber === 1 ? 'What does “this” refer to?' : null, warnings: [],
          },
        })
      }
      return new Response(null, { status: 404 })
    }))
    const answers = createAnswers()

    await answers.submitQuestion('When does this trigger?', null)
    await answers.submitQuestion('I mean: the scoring token', null)

    expect(answerRequests[1]).toMatchObject({
      question: 'I mean: the scoring token',
      previousQuestion: 'When does this trigger?',
      learningIntent: null,
    })
    expect(answers.question.value).toBe('')
  })

  it('restores a bounded thread as visible answer context without reviving execution state', () => {
    const answers = createAnswers()
    const restored = {
      status: 'ANSWERED' as const,
      shortVerdict: 'Resolve it after scoring.', explanation: 'The cited sequence puts it afterward.',
      citations: [], exceptions: [], confidence: 'HIGH' as const, official: false,
      confirmedRulingId: null, confirmedRulingVersion: null, clarification: null, warnings: [],
    }

    answers.restoreConversation([{ question: 'When does it resolve?', answer: restored, learningIntent: null }])

    expect(answers.answerTurns.value).toHaveLength(1)
    expect(answers.answeredQuestion.value).toBe('When does it resolve?')
    expect(answers.answer.value?.shortVerdict).toBe('Resolve it after scoring.')
    expect(answers.question.value).toBe('')
    expect(answers.answerRunId.value).toBe('')
    expect(answers.agentTrace.value).toEqual([])
  })
})
