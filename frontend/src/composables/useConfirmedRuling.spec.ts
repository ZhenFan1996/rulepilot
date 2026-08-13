import { ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { useConfirmedRuling } from '@/composables/useConfirmedRuling'
import type { StructuredRuleAnswer } from '@/composables/useLessonAnswers'

const answer: StructuredRuleAnswer = {
  status: 'ANSWERED',
  shortVerdict: 'Resolve the tie with coins.',
  explanation: 'Compare coins after matching score.',
  citations: [{ chunkId: 'chunk-1', sectionType: 'SCORING', heading: 'Tie break', excerpt: 'Most coins wins.', pageFrom: 4, pageTo: 4 }],
  exceptions: [],
  confidence: 'HIGH',
  official: false,
  confirmedRulingId: null,
  confirmedRulingVersion: null,
  clarification: null,
  warnings: [],
}

function confirmedRuling(overrides = {}) {
  return {
    id: 'ruling-1',
    shortVerdict: 'Resolve the tie with coins.',
    explanation: 'Compare coins after matching score.',
    citations: answer.citations,
    exceptions: [],
    confidence: 'HIGH' as const,
    status: 'CONFIRMED' as const,
    version: 3,
    ...overrides,
  }
}

function createRuling(contextRef = ref('plan-1:version-1')) {
  const documentVersionId = ref<string | null>('version-1')
  const currentAnswer = ref<StructuredRuleAnswer | null>(answer)
  const answeredQuestion = ref('How is a tie resolved?')
  const csrfToken = vi.fn(async () => ({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
  const onApplied = vi.fn()
  const ruling = useConfirmedRuling({
    documentVersionId,
    answer: currentAnswer,
    answeredQuestion,
    csrfToken,
    onApplied,
    currentReadContext: () => contextRef.value,
    isCurrentReadContext: (context) => context === contextRef.value,
    messages: {
      createFailed: () => 'Could not confirm ruling.',
      createRequestFailed: () => 'Could not confirm ruling.',
      updateFailed: () => 'Could not update ruling.',
      updateRequestFailed: () => 'Could not update ruling.',
      reloadFailed: () => 'Could not reload ruling.',
      reloadRequestFailed: () => 'Could not reload ruling.',
    },
  })

  return { ruling, csrfToken, onApplied, contextRef }
}

describe('useConfirmedRuling', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('confirms the current evidence-backed answer and passes its offline-cache context to the owner', async () => {
    const fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
      expect(input).toBe('/api/v1/confirmed-rulings')
      expect(init?.method).toBe('POST')
      expect(init?.headers).toMatchObject({ 'Content-Type': 'application/json', 'X-CSRF-TOKEN': 'csrf' })
      expect(JSON.parse(String(init?.body))).toMatchObject({
        documentVersionId: 'version-1',
        question: 'How is a tie resolved?',
        citationChunkIds: ['chunk-1'],
      })
      return Response.json(confirmedRuling())
    })
    vi.stubGlobal('fetch', fetchMock)
    const fixture = createRuling()

    await fixture.ruling.confirmAnswer()

    expect(fixture.csrfToken).toHaveBeenCalledOnce()
    expect(fixture.ruling.ruling.value).toMatchObject({ id: 'ruling-1', version: 3 })
    expect(fixture.onApplied).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'ruling-1' }),
      'How is a tie resolved?',
    )
  })

  it('keeps the editor open and exposes a reloadable conflict when another revision wins', async () => {
    const fixture = createRuling()
    vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
      if (init?.method === 'PATCH') return new Response(null, { status: 409 })
      expect(input).toBe('/api/v1/confirmed-rulings/ruling-1')
      return Response.json(confirmedRuling({ version: 4, shortVerdict: 'Server version.' }))
    }))

    fixture.ruling.applyRuling(confirmedRuling())
    fixture.ruling.editing.value = true
    fixture.ruling.editedVerdict.value = 'My local version.'
    await fixture.ruling.saveRulingRevision()

    expect(fixture.ruling.conflict.value).toBe(true)
    expect(fixture.ruling.editing.value).toBe(true)

    await fixture.ruling.reloadRuling()

    expect(fixture.ruling.conflict.value).toBe(false)
    expect(fixture.ruling.ruling.value).toMatchObject({ version: 4, shortVerdict: 'Server version.' })
  })

  it('aborts a superseded reload and ignores its late response', async () => {
    let resolveReload!: (response: Response) => void
    let reloadSignal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((_input: string, init?: RequestInit) => {
      reloadSignal = init?.signal ?? undefined
      return new Promise<Response>((resolve) => { resolveReload = resolve })
    }))
    const fixture = createRuling()
    fixture.ruling.applyRuling(confirmedRuling())

    const pending = fixture.ruling.reloadRuling()
    await vi.waitFor(() => expect(reloadSignal).toBeDefined())
    fixture.ruling.cancelReads()
    expect(reloadSignal?.aborted).toBe(true)

    resolveReload(Response.json(confirmedRuling({ version: 9, shortVerdict: 'Stale server version.' })))
    await pending

    expect(fixture.ruling.ruling.value).toMatchObject({ version: 3, shortVerdict: 'Resolve the tie with coins.' })
    expect(fixture.ruling.error.value).toBe('')
    expect(fixture.ruling.saving.value).toBe(false)
  })

  it('rejects a reload payload for another ruling identity', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json(confirmedRuling({
      id: 'ruling-2',
      version: 8,
      shortVerdict: 'Another ruling.',
    }))))
    const fixture = createRuling()
    fixture.ruling.applyRuling(confirmedRuling())

    await fixture.ruling.reloadRuling()

    expect(fixture.ruling.ruling.value).toMatchObject({ id: 'ruling-1', version: 3 })
    expect(fixture.ruling.error.value).toBe('Could not reload ruling.')
  })

  it('lets an accepted confirmation finish on the server without applying it after workspace replacement', async () => {
    let resolveCreate!: (response: Response) => void
    let createSignal: AbortSignal | undefined
    let resolveCsrf!: (value: { headerName: string; token: string }) => void
    const csrfToken = vi.fn(() => new Promise<{ headerName: string; token: string }>((resolve) => {
      resolveCsrf = resolve
    }))
    vi.stubGlobal('fetch', vi.fn((_input: string, init?: RequestInit) => {
      createSignal = init?.signal ?? undefined
      return new Promise<Response>((resolve) => { resolveCreate = resolve })
    }))
    const fixture = createRuling()
    const originalCsrf = fixture.csrfToken
    originalCsrf.mockImplementation(csrfToken)

    const pending = fixture.ruling.confirmAnswer()
    await vi.waitFor(() => expect(resolveCsrf).toBeDefined())
    resolveCsrf({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
    await vi.waitFor(() => expect(resolveCreate).toBeDefined())
    fixture.contextRef.value = 'plan-2:version-2'
    fixture.ruling.reset()

    expect(createSignal).toBeUndefined()
    resolveCreate(Response.json(confirmedRuling({ version: 7, shortVerdict: 'Late confirmation.' })))
    await pending

    expect(fixture.ruling.ruling.value).toBeNull()
    expect(fixture.onApplied).not.toHaveBeenCalled()
    expect(fixture.ruling.error.value).toBe('')
    expect(fixture.ruling.saving.value).toBe(false)
  })
})
