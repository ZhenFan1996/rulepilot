import { afterEach, describe, expect, it, vi } from 'vitest'

import { RecommendationRequestError, RecommendationStreamError, streamGameRecommendation } from './gameRecommendationStream'

describe('streamGameRecommendation', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('parses named SSE events across transport chunk boundaries', async () => {
    const encoder = new TextEncoder()
    const payload = {
      outcome: 'no_match', mode: 'model_assisted', assistantMessage: '没有匹配。',
      profile: { players: 4, maxMinutes: 120, maxWeight: null, type: 'all', interaction: 'any' },
      clarification: null, sourceCount: 179737, candidatesEvaluated: 20, games: [],
    }
    vi.stubGlobal('fetch', vi.fn(async () => new Response(new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('event: progress\r\nda'))
        controller.enqueue(encoder.encode('ta: {"stage":"discovering_candidates","elapsedMs":42}\r\n\r\n'))
        controller.enqueue(encoder.encode(`event: result\ndata: ${JSON.stringify(payload)}\n\n`))
        controller.close()
      },
    }), { headers: { 'Content-Type': 'text/event-stream' } })))
    const progress: string[] = []

    const result = await streamGameRecommendation('/stream', { method: 'POST' }, update => progress.push(update.stage))

    expect(progress).toEqual(['discovering_candidates'])
    expect(result).toMatchObject(payload)
  })

  it('publishes the terminal result without waiting for the proxy to close the SSE connection', async () => {
    const encoder = new TextEncoder()
    const payload = {
      outcome: 'recommendations', mode: 'model_fast_path', assistantMessage: '已找到《花砖物语》。',
      profile: { players: null, maxMinutes: null, maxWeight: null, type: 'all', interaction: 'any' },
      clarification: null, sourceCount: 0, candidatesEvaluated: 1, games: [],
    }
    let transportCancelled = false
    vi.stubGlobal('fetch', vi.fn(async () => new Response(new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(`event: result\ndata: ${JSON.stringify(payload)}\n\n`))
      },
      cancel() {
        transportCancelled = true
      },
    }), { headers: { 'Content-Type': 'text/event-stream' } })))

    await expect(streamGameRecommendation('/stream', { method: 'POST' }, () => undefined))
      .resolves.toMatchObject(payload)
    expect(transportCancelled).toBe(true)
  })

  it('preserves measured candidate and hard-constraint counts from progress events', async () => {
    const payload = {
      outcome: 'no_match', mode: 'model_assisted', assistantMessage: '没有匹配。',
      profile: { players: 4, maxMinutes: 120, maxWeight: null, type: 'all', interaction: 'any' },
      clarification: null, sourceCount: 20, candidatesEvaluated: 6, games: [],
    }
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      `event: progress\ndata: {"stage":"verifying_bgg_candidates","phase":"completed","action":"lookup_bgg_games","elapsedMs":120,"observedCandidates":8,"verifiedCandidates":6,"hardRejectedCandidates":3,"sourceCount":20}\n\nevent: result\ndata: ${JSON.stringify(payload)}\n\n`,
      { headers: { 'Content-Type': 'text/event-stream' } },
    )))
    const progress: unknown[] = []

    await streamGameRecommendation('/stream', { method: 'POST' }, update => progress.push(update))

    expect(progress).toEqual([expect.objectContaining({
      phase: 'completed',
      action: 'lookup_bgg_games',
      observedCandidates: 8,
      verifiedCandidates: 6,
      hardRejectedCandidates: 3,
      sourceCount: 20,
    })])
  })

  it('publishes only an explicitly validated message field as an answer preview', async () => {
    const payload = {
      outcome: 'conversation', mode: 'model_assisted', assistantMessage: '我会选左边这款。',
      profile: { players: null, maxMinutes: null, maxWeight: null, type: 'all', interaction: 'any' },
      clarification: null, sourceCount: 1, candidatesEvaluated: 1, games: [],
    }
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      `event: answer_part\ndata: {"field":"message","text":"我会选左边这款。"}\n\nevent: result\ndata: ${JSON.stringify(payload)}\n\n`,
      { headers: { 'Content-Type': 'text/event-stream' } },
    )))
    const previews: string[] = []

    await streamGameRecommendation('/stream', { method: 'POST' }, () => undefined, text => previews.push(text))

    expect(previews).toEqual(['我会选左边这款。'])
  })

  it('keeps JSON response compatibility during a rolling deployment', async () => {
    const payload = {
      outcome: 'unavailable', mode: 'model_assisted', assistantMessage: '暂时不可用。',
      profile: { players: null, maxMinutes: null, maxWeight: null, type: 'all', interaction: 'any' },
      clarification: null, sourceCount: 0, candidatesEvaluated: 0, games: [],
    }
    vi.stubGlobal('fetch', vi.fn(async () => Response.json(payload)))

    await expect(streamGameRecommendation('/stream', { method: 'POST' }, () => undefined))
      .resolves.toMatchObject(payload)
  })

  it('preserves the HTTP status so authentication failures are not shown as network failures', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 401 })))

    await expect(streamGameRecommendation('/stream', { method: 'POST' }, () => undefined))
      .rejects.toEqual(new RecommendationRequestError(401))
  })

  it('decodes a result correctly when transport chunks split a multibyte player message', async () => {
    const encoder = new TextEncoder()
    const payload = {
      outcome: 'conversation', mode: 'model_assisted', assistantMessage: '这是一条完整回复。',
      profile: { players: null, maxMinutes: null, maxWeight: null, type: 'all', interaction: 'any' },
      clarification: null, sourceCount: 0, candidatesEvaluated: 0, games: [],
    }
    const bytes = encoder.encode(`event: result\ndata: ${JSON.stringify(payload)}\n\n`)
    const multibyteStart = bytes.findIndex(value => value >= 0x80)
    expect(multibyteStart).toBeGreaterThan(0)
    vi.stubGlobal('fetch', vi.fn(async () => new Response(new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(bytes.slice(0, multibyteStart + 1))
        controller.enqueue(bytes.slice(multibyteStart + 1))
        controller.close()
      },
    }), { headers: { 'Content-Type': 'text/event-stream' } })))

    await expect(streamGameRecommendation('/stream', { method: 'POST' }, () => undefined))
      .resolves.toMatchObject(payload)
  })

  it('does not turn progress or trailing bytes into success when the stream has no result event', async () => {
    const encoder = new TextEncoder()
    vi.stubGlobal('fetch', vi.fn(async () => new Response(new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('event: progress\ndata: {"stage":"composing_response","elapsedMs":500}\n\n'))
        controller.enqueue(encoder.encode(': transport ended after progress'))
        controller.close()
      },
    }), { headers: { 'Content-Type': 'text/event-stream' } })))

    await expect(streamGameRecommendation('/stream', { method: 'POST' }, () => undefined))
      .rejects.toThrow('ended without a result')
  })

  it('treats an explicit error after partial progress as failure', async () => {
    const encoder = new TextEncoder()
    vi.stubGlobal('fetch', vi.fn(async () => new Response(new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('event: progress\ndata: {"stage":"reading_game_details","elapsedMs":90}\n\n'))
        controller.enqueue(encoder.encode('event: error\ndata: {"code":"MODEL_OUTPUT_TRUNCATED"}\n\n'))
        controller.close()
      },
    }), { headers: { 'Content-Type': 'text/event-stream' } })))

    await expect(streamGameRecommendation('/stream', { method: 'POST' }, () => undefined))
      .rejects.toEqual(new RecommendationStreamError('MODEL_OUTPUT_TRUNCATED'))
  })
})
