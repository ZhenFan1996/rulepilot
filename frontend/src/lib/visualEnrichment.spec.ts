import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  fetchVisualStatusWithDeadline,
  visualEnrichmentResult,
  visualRunIsTerminal,
  VisualRequestTimeoutError,
} from './visualEnrichment'

function responseWithOpenBody() {
  return new Response(new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new TextEncoder().encode('{"state":"COMPLETED"}'))
    },
  }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('fetchVisualStatusWithDeadline', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('keeps the deadline active until a successful response body finishes', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn(async () => responseWithOpenBody()))

    const request = fetchVisualStatusWithDeadline('/visual-status', {}, 25)
    const rejection = expect(request).rejects.toBeInstanceOf(VisualRequestTimeoutError)

    await vi.advanceTimersByTimeAsync(25)

    await rejection
  })

  it('keeps upstream abort active after successful response headers arrive', async () => {
    let releaseHeaders!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => {
      releaseHeaders = resolve
    })))
    const upstreamController = new AbortController()
    const request = fetchVisualStatusWithDeadline(
      '/visual-status',
      { signal: upstreamController.signal },
      1_000,
    )

    releaseHeaders(responseWithOpenBody())
    await Promise.resolve()
    await Promise.resolve()
    upstreamController.abort()

    await expect(request).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('returns non-success responses without consuming their bodies', async () => {
    const response = new Response('not found', { status: 404 })
    vi.stubGlobal('fetch', vi.fn(async () => response))

    const result = await fetchVisualStatusWithDeadline('/visual-status')

    expect(result).toBe(response)
    expect(result.status).toBe(404)
    await expect(result.text()).resolves.toBe('not found')
  })

  it('returns a consumable buffered response for successful JSON', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json(
      { state: 'COMPLETED' },
      { headers: { 'X-Visual-Revision': '7' } },
    )))

    const response = await fetchVisualStatusWithDeadline('/visual-status')

    expect(response.headers.get('X-Visual-Revision')).toBe('7')
    await expect(response.json()).resolves.toEqual({ state: 'COMPLETED' })
  })
})

describe('visual enrichment terminal state', () => {
  it('keeps active work active but treats cancellation as an unfinished terminal result', () => {
    expect(visualRunIsTerminal('RETRIEVING')).toBe(false)
    expect(visualEnrichmentResult({ run: { state: 'RETRIEVING' } }, true))
      .toEqual({ outcome: 'ACTIVE', addedSectionCount: 0 })

    expect(visualRunIsTerminal('CANCELLED')).toBe(true)
    expect(visualEnrichmentResult({ run: { state: 'CANCELLED' } }, false))
      .toEqual({ outcome: 'FAILED', addedSectionCount: 0 })
    expect(visualEnrichmentResult({
      run: { state: 'CANCELLED' },
      activities: [{ operation: 'visualSection|2', outcome: 'SUCCEEDED' }],
    }, false)).toEqual({ outcome: 'PARTIAL', addedSectionCount: 1 })
  })
})
