import { ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { useLessonResume } from '@/composables/useLessonResume'

function createResume() {
  const planId = ref('plan-1')
  const online = ref(true)
  const request = ref(1)
  const csrfToken = vi.fn(async () => ({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
  const onStarted = vi.fn(async () => undefined)
  const resume = useLessonResume({
    planId,
    online,
    currentRequest: () => request.value,
    isCurrent: (candidate, targetPlanId) => candidate === request.value && targetPlanId === planId.value,
    csrfToken,
    onStarted,
    messages: {
      requestFailed: () => 'Could not continue this guide.',
      requestError: () => 'Could not continue this guide.',
    },
  })
  return { planId, request, csrfToken, onStarted, resume }
}

describe('useLessonResume', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('posts the existing CSRF contract and hands a successful resume to its owner', async () => {
    const fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
      expect(input).toBe('/api/v1/teaching-plans/plan-1/illustrated-lessons')
      expect(init).toMatchObject({
        method: 'POST',
        credentials: 'include',
        headers: { 'X-CSRF-TOKEN': 'csrf' },
      })
      return Response.json({})
    })
    vi.stubGlobal('fetch', fetchMock)
    const fixture = createResume()

    await fixture.resume.resume()

    expect(fixture.csrfToken).toHaveBeenCalledOnce()
    expect(fixture.onStarted).toHaveBeenCalledWith('plan-1')
    expect(fixture.resume.errorMessage.value).toBe('')
    expect(fixture.resume.resuming.value).toBe(false)
  })

  it('keeps a failed resume as a local recovery error', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 503 })))
    const fixture = createResume()

    await fixture.resume.resume()

    expect(fixture.onStarted).not.toHaveBeenCalled()
    expect(fixture.resume.errorMessage.value).toBe('Could not continue this guide.')
    expect(fixture.resume.resuming.value).toBe(false)
  })

  it('does not let an older reader response replace newer resume state', async () => {
    let resolveResponse: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => { resolveResponse = resolve })))
    const fixture = createResume()

    const pending = fixture.resume.resume()
    await Promise.resolve()
    fixture.request.value = 2
    fixture.resume.resuming.value = true
    fixture.resume.errorMessage.value = 'New reader state.'
    resolveResponse!(new Response(null, { status: 503 }))
    await pending

    expect(fixture.resume.resuming.value).toBe(true)
    expect(fixture.resume.errorMessage.value).toBe('New reader state.')
  })
})
