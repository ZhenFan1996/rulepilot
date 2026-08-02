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

function createRuling() {
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
    messages: {
      createFailed: () => 'Could not confirm ruling.',
      createRequestFailed: () => 'Could not confirm ruling.',
      updateFailed: () => 'Could not update ruling.',
      updateRequestFailed: () => 'Could not update ruling.',
      reloadFailed: () => 'Could not reload ruling.',
      reloadRequestFailed: () => 'Could not reload ruling.',
    },
  })

  return { ruling, csrfToken, onApplied }
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
})
