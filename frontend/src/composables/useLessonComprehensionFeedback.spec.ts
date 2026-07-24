import { ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { useLessonComprehensionFeedback } from '@/composables/useLessonComprehensionFeedback'

function report(lessonId = 'lesson-1') {
  return {
    lessonId,
    readyTaskCount: 1,
    taskCount: 1,
    canDoCount: 0,
    needsHelpCount: 0,
    readyVisualTaskCount: 1,
    visualAidRatedCount: 0,
    visualAidHelpfulCount: 0,
    visualAidHelpfulPercent: null,
    visualAids: [{
      key: 's1-v2', label: 'Action row', chapterPosition: 1, sourcePages: [2], result: 'NOT_RATED' as const,
      visualFocus: { pageNumber: 2, label: 'Action row', x: 100, y: 100, width: 200, height: 200 },
    }],
    tasks: [{
      type: 'PREPARE_TABLE' as const, label: 'Prepare', prompt: 'Prepare the table.', readiness: 'READY' as const,
      result: 'NOT_TRIED' as const, chapterPositions: [1], sourcePages: [2], visualFocus: null,
      visualAidResult: 'NOT_RATED' as const,
    }],
  }
}

function createFeedback() {
  const planId = ref('plan-1')
  const online = ref(true)
  const request = ref(1)
  const comprehension = ref(report())
  const saving = ref<string | null>(null)
  const errorMessage = ref('')
  const csrfToken = vi.fn(async () => ({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
  const feedback = useLessonComprehensionFeedback({
    planId,
    online,
    currentRequest: () => request.value,
    isCurrent: (candidate, targetPlanId) => candidate === request.value && targetPlanId === planId.value,
    comprehension,
    saving,
    errorMessage,
    csrfToken,
    messages: {
      saveTaskRetry: () => 'Could not save task.',
      saveTask: () => 'Could not save task.',
      saveVisualRetry: () => 'Could not save visual feedback.',
      saveVisual: () => 'Could not save visual feedback.',
    },
  })
  return { planId, request, comprehension, saving, errorMessage, csrfToken, feedback }
}

describe('useLessonComprehensionFeedback', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('saves a task rating with the existing CSRF contract and replaces the report', async () => {
    const updated = report('lesson-2')
    const fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
      expect(input).toBe('/api/v1/teaching-plans/plan-1/comprehension/PREPARE_TABLE')
      expect(init).toMatchObject({
        method: 'PUT',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': 'csrf' },
      })
      expect(JSON.parse(String(init?.body))).toEqual({ result: 'CAN_DO' })
      return Response.json(updated)
    })
    vi.stubGlobal('fetch', fetchMock)
    const fixture = createFeedback()

    await fixture.feedback.recordComprehension('PREPARE_TABLE', 'CAN_DO')

    expect(fixture.csrfToken).toHaveBeenCalledOnce()
    expect(fixture.comprehension.value).toEqual(updated)
    expect(fixture.saving.value).toBeNull()
  })

  it('uses the visual-aid path and exposes the matching visual feedback state', async () => {
    const fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
      expect(input).toBe('/api/v1/teaching-plans/plan-1/comprehension/visual-aids/s1-v2')
      expect(JSON.parse(String(init?.body))).toEqual({ result: 'HELPFUL' })
      return Response.json(report())
    })
    vi.stubGlobal('fetch', fetchMock)
    const fixture = createFeedback()

    await fixture.feedback.recordVisualAid('s1-v2', 'HELPFUL')

    expect(fixture.feedback.hasVisualAid(1, 2)).toBe(true)
    expect(fixture.feedback.visualAidResult(1, 2)).toBe('NOT_RATED')
  })

  it('does not let an older reader load replace or clear the new state', async () => {
    let resolveResponse: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => { resolveResponse = resolve })))
    const fixture = createFeedback()
    const original = fixture.comprehension.value

    const pending = fixture.feedback.recordComprehension('PREPARE_TABLE', 'CAN_DO')
    await Promise.resolve()
    fixture.request.value = 2
    fixture.saving.value = 'new-plan-request'
    resolveResponse!(Response.json(report('old-lesson')))
    await pending

    expect(fixture.comprehension.value).toEqual(original)
    expect(fixture.saving.value).toBe('new-plan-request')
    expect(fixture.errorMessage.value).toBe('')
  })
})
