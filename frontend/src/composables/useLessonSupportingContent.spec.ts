import { afterEach, describe, expect, it, vi } from 'vitest'

import { useLessonSupportingContent } from './useLessonSupportingContent'

describe('useLessonSupportingContent', () => {
  afterEach(() => {
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('loads only the learning check and leaves retired media endpoints untouched', async () => {
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      if (String(input).endsWith('/comprehension')) {
        return Response.json({ lessonId: 'lesson-1', tasks: [], visualAids: [] })
      }
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const content = useLessonSupportingContent()
    const controller = new AbortController()

    await content.loadSupportingContent({
      planId: 'plan-1',
      signal: controller.signal,
      isCurrent: () => true,
      requestLogin: vi.fn(),
    })

    expect(content.comprehension.value?.lessonId).toBe('lesson-1')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain('/comprehension')
  })

  it('drops a learning check that returns after the selected lesson changed', async () => {
    let resolveComprehension: ((response: Response) => void) | undefined
    let current = true
    let readSignal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((_input: string, init?: RequestInit) => {
      readSignal = init?.signal ?? undefined
      return new Promise<Response>((resolve) => { resolveComprehension = resolve })
    }))
    const content = useLessonSupportingContent()
    const controller = new AbortController()
    const loading = content.loadSupportingContent({
      planId: 'plan-1',
      signal: controller.signal,
      isCurrent: () => current && !controller.signal.aborted,
      requestLogin: vi.fn(),
    })

    current = false
    controller.abort()
    expect(readSignal?.aborted).toBe(true)
    resolveComprehension!(Response.json({ lessonId: 'lesson-1', tasks: [], visualAids: [] }))
    await loading

    expect(content.comprehension.value).toBeNull()
    expect(content.comprehensionError.value).toBe('')
  })
})
