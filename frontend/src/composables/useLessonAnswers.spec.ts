import { afterEach, describe, expect, it, vi } from 'vitest'

import { useLessonAnswers } from './useLessonAnswers'
import { setLocale } from '@/lib/locale'

function answerStreamResponse(result: unknown) {
  return new Response(`event: result\ndata: ${JSON.stringify(result)}\n\n`, {
    headers: { 'Content-Type': 'text/event-stream' },
  })
}

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

function createSessionAnswers() {
  return useLessonAnswers({
    currentContext: () => ({
      planId: 'plan-1',
      documentVersionId: 'document-1',
      locale: 'en',
      gameSessionId: 'session-1',
    }),
    currentLessonRequest: () => 1,
    isCurrentLessonLoad: () => true,
    requestLogin: vi.fn(),
    onReceived: vi.fn(),
  })
}

describe('useLessonAnswers', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    setLocale('zh-CN')
  })

  it('uses the explicit answer-context locale for a secure-session failure', async () => {
    setLocale('zh-CN')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 503 })))
    const answers = createAnswers()
    answers.question.value = 'When does this resolve?'

    await answers.submitQuestion(answers.question.value, null)

    expect(answers.answerError.value).toBe(
      "I couldn't establish a secure session. Your question is still here; review it and try again.",
    )
    expect(answers.answerOutcome.value).toBe('failed')
    expect(answers.question.value).toBe('When does this resolve?')
  })

  it('localizes an unavailable answer after a secure session is established', async () => {
    setLocale('en')
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'token' }))
      .mockResolvedValueOnce(new Response(null, { status: 503 })))
    const answers = createAnswers()

    await answers.submitQuestion('When does this resolve?', null)

    expect(answers.answerError.value).toBe(
      'The rules answer service is unavailable right now. Your question is still here; review it and try again.',
    )
  })

  it('rejects a malformed answer envelope without exposing schema or runtime diagnostics', async () => {
    setLocale('zh-CN')
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'token' }))
      .mockResolvedValueOnce(answerStreamResponse({
        schemaDiagnostic: 'citations is required by PlayerFacingRuleAnswer',
        answer: { status: 'INVALID_MODEL_OUTPUT' },
      })))
    const answers = createAnswers()
    answers.question.value = 'Can the cobalt spindle move now?'

    await answers.submitQuestion(answers.question.value, null)

    expect(answers.answer.value).toBeNull()
    expect(answers.answerTurns.value).toEqual([])
    expect(answers.answerError.value).toBe(
      'The rules answer service is unavailable right now. Your question is still here; review it and try again.',
    )
    expect(answers.answerError.value).not.toMatch(/schema|citations|PlayerFacingRuleAnswer|undefined/i)
    expect(answers.question.value).toBe('Can the cobalt spindle move now?')
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
      language: 'en' as const,
      status: 'ANSWERED' as const,
      shortVerdict: 'Resolve it after scoring.', explanation: 'The cited order puts scoring first.',
      citations: [], exceptions: [], confidence: 'HIGH' as const, source: 'UPLOADED' as const,
      clarification: null, recovery: null, warnings: [],
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
    expect(answers.answerOutcome.value).toBe('cancelled')
    expect(answers.agentTrace.value).toEqual([])
  })

  it('exposes the eight-second soft boundary and cancels a known server run with the same secure session', async () => {
    vi.useFakeTimers()
    const runId = '11111111-1111-4111-8111-111111111111'
    let answerSignal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/auth/csrf') {
        return Promise.resolve(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'token' }))
      }
      if (path.includes('/answers') && init?.method === 'POST') {
        answerSignal = init.signal ?? undefined
        return new Promise<Response>((_resolve, reject) => {
          answerSignal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
        })
      }
      if (path.includes('/assistant-runs/latest')) {
        return Promise.resolve(Response.json({
          ...answerRunDetails('document-1', 'readRulePages'),
          run: {
            ...answerRunDetails('document-1', 'readRulePages').run,
            id: runId,
            createdAt: new Date().toISOString(),
          },
        }))
      }
      if (path === `/api/v1/assistant-runs/${runId}/cancellation`) {
        return Promise.resolve(new Response(null, { status: 202 }))
      }
      return Promise.resolve(new Response(null, { status: 404 }))
    }))
    const answers = createAnswers()
    answers.question.value = 'What if the prior timing clause applies?'

    const pending = answers.submitQuestion(answers.question.value, null)
    expect(answers.answerLoading.value).toBe(true)
    expect(answers.answerElapsedSeconds.value).toBe(0)
    expect(answers.answerSoftBudgetReached.value).toBe(false)
    await vi.advanceTimersByTimeAsync(8_000)

    expect(answers.answerElapsedSeconds.value).toBe(8)
    expect(answers.answerSoftBudgetReached.value).toBe(true)
    answers.cancelAnswer()
    await vi.advanceTimersByTimeAsync(0)
    await pending

    expect(answerSignal?.aborted).toBe(true)
    expect(vi.mocked(fetch).mock.calls).toContainEqual([
      `/api/v1/assistant-runs/${runId}/cancellation`,
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        headers: { 'X-CSRF-TOKEN': 'token' },
      }),
    ])
  })

  it('keeps completed audit identities outside the player answer state', async () => {
    const internalId = '11111111-1111-4111-8111-111111111111'
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'token' })
      if (path.includes('/answers')) {
        return answerStreamResponse({
          assistantRunId: internalId,
          answer: {
            language: 'en',
            status: 'INSUFFICIENT_EVIDENCE', shortVerdict: 'Not enough evidence.', explanation: '', citations: [{
              heading: 'Timing', excerpt: 'The timing clause is incomplete.', pageFrom: 4, pageTo: 4,
              chunkId: internalId, sectionType: 'TIMING',
            }],
            exceptions: [], confidence: 'LOW', source: 'UPLOADED', clarification: null,
            recovery: {
              message: 'Add the exact object or timing.', actionLabel: 'Add detail', draft: '',
            },
            warnings: [],
            documentVersionId: internalId,
          },
          rulingReference: {
            citationIds: [internalId], confirmedRulingId: null, confirmedRulingVersion: null,
          },
          conversationTurnId: null,
        })
      }
      return new Response(null, { status: 404 })
    }))
    const answers = createAnswers()

    await answers.submitQuestion('When does this resolve?', null)

    expect('answerRunId' in answers).toBe(false)
    expect(answers.answerRulingReference.value).toEqual({
      citationIds: [internalId], confirmedRulingId: null, confirmedRulingVersion: null,
    })
    expect(JSON.stringify(answers.answer.value)).not.toContain('assistantRunId')
    expect(JSON.stringify(answers.answer.value)).not.toContain('documentVersionId')
    expect(JSON.stringify(answers.answer.value)).not.toContain(internalId)
    expect(vi.mocked(fetch).mock.calls.some(([input]) => String(input).includes('/assistant-runs/'))).toBe(false)
  })

  it('sends a verification challenge with the exact previous question as retrieval context', async () => {
    const answerRequests: Array<Record<string, unknown>> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'token' })
      if (path.includes('/answers') && init?.method === 'POST') {
        answerRequests.push(JSON.parse(String(init.body)) as Record<string, unknown>)
        return answerStreamResponse(answerCreation({
            status: 'ANSWERED', shortVerdict: 'Verified.', explanation: 'Supported.', citations: [],
            exceptions: [], confidence: 'HIGH', source: 'UPLOADED', clarification: null, warnings: [],
          }))
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

  it('attaches the durable game session when the answer workspace provides one', async () => {
    let requestBody: Record<string, unknown> | undefined
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'token' })
      if (path.includes('/answers') && init?.method === 'POST') {
        requestBody = JSON.parse(String(init.body)) as Record<string, unknown>
        return answerStreamResponse(answerCreation({
            status: 'ANSWERED', shortVerdict: 'Verified.', explanation: 'Supported.', citations: [],
            exceptions: [], confidence: 'HIGH', source: 'UPLOADED', clarification: null, warnings: [],
          }))
      }
      return new Response(null, { status: 404 })
    }))
    const answers = createSessionAnswers()

    await answers.submitQuestion('When does this resolve?', null)

    expect(requestBody).toMatchObject({
      question: 'When does this resolve?',
      gameSessionId: 'session-1',
      language: 'en',
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
        return answerStreamResponse(answerCreation({
            status: answerNumber === 1 ? 'CLARIFICATION_REQUIRED' : 'ANSWERED',
            shortVerdict: answerNumber === 1 ? 'Please identify the object.' : 'It triggers after scoring.',
            explanation: '', citations: [], exceptions: [], confidence: answerNumber === 1 ? 'LOW' : 'HIGH',
            source: 'UPLOADED',
            clarification: answerNumber === 1 ? 'What does “this” refer to?' : null, warnings: [],
          }))
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
      language: 'en' as const,
      status: 'ANSWERED' as const,
      shortVerdict: 'Resolve it after scoring.', explanation: 'The cited sequence puts it afterward.',
      citations: [], exceptions: [], confidence: 'HIGH' as const, source: 'UPLOADED' as const,
      clarification: null, recovery: null, warnings: [],
    }

    answers.restoreConversation([{ question: 'When does it resolve?', answer: restored, learningIntent: null }])

    expect(answers.answerTurns.value).toHaveLength(1)
    expect(answers.answeredQuestion.value).toBe('When does it resolve?')
    expect(answers.answer.value?.shortVerdict).toBe('Resolve it after scoring.')
    expect(answers.question.value).toBe('')
    expect(answers.answerRulingReference.value).toBeNull()
    expect(answers.agentTrace.value).toEqual([])
  })

  it('aborts an active progress read and prevents a late poll from rescheduling after reset', async () => {
    vi.useFakeTimers()
    let traceSignal: AbortSignal | undefined
    let resolveTrace!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/auth/csrf') {
        return Promise.resolve(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'token' }))
      }
      if (path.includes('/answers')) return new Promise<Response>(() => undefined)
      return new Promise<Response>((resolve) => {
        traceSignal = init?.signal ?? undefined
        resolveTrace = resolve
      })
    }))
    const answers = createAnswers()
    void answers.submitQuestion('When does this resolve?', null)
    await vi.advanceTimersByTimeAsync(250)
    expect(traceSignal).toBeDefined()

    answers.resetConversation(false)
    expect(traceSignal?.aborted).toBe(true)
    resolveTrace(Response.json(answerRunDetails('document-1', 'nativeModelTurn|1')))
    await Promise.resolve()
    await vi.advanceTimersByTimeAsync(2_000)

    const traceReads = vi.mocked(fetch).mock.calls
      .filter(([input]) => String(input).includes('/assistant-runs/latest'))
    expect(traceReads).toHaveLength(1)
    expect(answers.agentTrace.value).toEqual([])
    vi.useRealTimers()
  })

  it('does not fetch completed audit details after the player answer arrives', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const path = String(input)
      if (path === '/api/auth/csrf') {
        return Promise.resolve(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'token' }))
      }
      if (path.includes('/answers')) {
        return Promise.resolve(answerStreamResponse(answerCreation(answerFixture('Completed answer.'))))
      }
      return Promise.resolve(new Response(null, { status: 404 }))
    }))
    const answers = createAnswers()

    await answers.submitQuestion('When does this resolve?', null)

    expect(vi.mocked(fetch).mock.calls.filter(([input]) => String(input).includes('/assistant-runs/'))).toEqual([])
    expect(answers.agentTrace.value).toEqual([])
    expect(answers.answer.value?.shortVerdict).toBe('Completed answer.')
  })
})

function answerFixture(shortVerdict: string) {
  return {
    language: 'en' as const,
    status: 'ANSWERED' as const,
    shortVerdict,
    explanation: 'Supported.',
    citations: [{
      heading: 'Timing', excerpt: 'Resolve after scoring.', pageFrom: 2, pageTo: 2,
    }],
    exceptions: [],
    confidence: 'HIGH' as const,
    answerBasis: 'DIRECT_RULE' as const,
    source: 'UPLOADED' as const,
    clarification: null,
    recovery: null,
    warnings: [],
  }
}

function emptyRulingReference() {
  return { citationIds: [], confirmedRulingId: null, confirmedRulingVersion: null }
}

function answerCreation(answer: ReturnType<typeof answerFixture> | Record<string, unknown>) {
  const playerAnswer = answer as Record<string, unknown>
  const publishesConclusion = playerAnswer.status === 'ANSWERED'
    || playerAnswer.status === 'ANSWERED_WITH_WARNING'
  const citations = publishesConclusion
    && Array.isArray(playerAnswer.citations)
    && playerAnswer.citations.length === 0
    ? [{ heading: 'Timing', excerpt: 'Resolve after scoring.', pageFrom: 2, pageTo: 2 }]
    : playerAnswer.citations
  return {
    answer: {
      ...playerAnswer,
      language: playerAnswer.language ?? 'en',
      citations,
      answerBasis: publishesConclusion ? playerAnswer.answerBasis ?? 'DIRECT_RULE' : null,
      recovery: publishesConclusion
        ? null
        : playerAnswer.recovery ?? {
            message: 'Add the missing detail and try again.',
            actionLabel: 'Add detail',
            draft: playerAnswer.status === 'CLARIFICATION_REQUIRED' ? 'I mean: ' : '',
          },
    },
    rulingReference: emptyRulingReference(),
    conversationTurnId: null,
  }
}

function answerRunDetails(subjectId: string, operation: string) {
  return {
    run: { id: '11111111-1111-4111-8111-111111111111', subjectId, createdAt: '2026-08-03T00:00:00Z' },
    activities: [{
      sequence: 1,
      type: 'MODEL',
      operation,
      outcome: 'SUCCEEDED',
      latencyMs: 10,
      occurredAt: '2026-08-03T00:00:00Z',
    }],
  }
}
