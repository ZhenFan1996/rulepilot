import { afterEach, describe, expect, it, vi } from 'vitest'

import { RecommendationRequestError, RecommendationStreamError, streamGameRecommendation } from './gameRecommendationStream'

const emptyProfile = {
  type: 'all',
  interaction: 'any',
  playerCount: null,
  durationMinutes: null,
  complexity: null,
}
const fourPlayersInTwoHours = {
  ...emptyProfile,
  playerCount: { minimum: 4, maximum: 4, strength: 'hard', sourceText: '4', confirmedTurn: 1 },
  durationMinutes: { minimum: null, maximum: 120, strength: 'hard', sourceText: '120', confirmedTurn: 1 },
}

describe('streamGameRecommendation', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('parses named SSE events across transport chunk boundaries', async () => {
    const encoder = new TextEncoder()
    const payload = {
      outcome: 'no_match', mode: 'model_assisted', assistantMessage: '没有匹配。',
      profile: fourPlayersInTwoHours,
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

  it('publishes cumulative answer snapshots, including an explicit preview clear', async () => {
    const payload = {
      outcome: 'conversation', mode: 'model_assisted', assistantMessage: '最终回答。',
      profile: emptyProfile,
      clarification: null, sourceCount: 0, candidatesEvaluated: 0, games: [],
    }
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      `event: answer_part\ndata: {"text":"先给你一个方向。"}\n\n`
      + 'event: tool_activity\ndata: {"query":"internal adapter payload"}\n\n'
      + 'event: answer_part\ndata: {"text":""}\n\n'
      + `event: answer_part\ndata: {"text":"最终回答。"}\n\n`
      + `event: result\ndata: ${JSON.stringify(payload)}\n\n`,
      { headers: { 'Content-Type': 'text/event-stream' } },
    )))
    const answerSnapshots: string[] = []

    const result = await streamGameRecommendation(
      '/stream',
      { method: 'POST' },
      () => undefined,
      text => answerSnapshots.push(text),
    )

    expect(answerSnapshots).toEqual(['先给你一个方向。', '', '最终回答。'])
    expect(result).toMatchObject(payload)
  })

  it('publishes each validated recommendation part before the terminal result', async () => {
    const game = {
      bggId: 451, name: 'First Signal', originalName: 'First Signal', nameLocalized: false,
      publicationYear: 2024, overallRank: 10, geekRating: 7.1, averageRating: 7.4,
      usersRated: 1000, thumbnailUrl: '', minPlayers: 2, maxPlayers: 4,
      playingTimeMinutes: 60, averageWeight: 2.2, categories: [], mechanics: [],
      bggUrl: 'https://boardgamegeek.com/boardgame/451',
    }
    const part = { game: { game, fitClaims: [], replyParts: [] }, researchSources: [] }
    const payload = {
      outcome: 'recommendations', assistantMessage: '第一款已经核对。', profile: emptyProfile,
      clarification: null, sourceCount: 1, candidatesEvaluated: 1, games: [part.game],
    }
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      `event: recommendation_part\ndata: ${JSON.stringify(part)}\n\n`
      + `event: result\ndata: ${JSON.stringify(payload)}\n\n`,
      { headers: { 'Content-Type': 'text/event-stream' } },
    )))
    const streamed: number[] = []

    const result = await streamGameRecommendation(
      '/stream',
      { method: 'POST' },
      () => undefined,
      () => undefined,
      value => streamed.push(value.game.game.bggId),
    )

    expect(streamed).toEqual([451])
    expect(result.games[0]?.game.bggId).toBe(451)
  })

  it('publishes the terminal result without waiting for the proxy to close the SSE connection', async () => {
    const encoder = new TextEncoder()
    const payload = {
      outcome: 'recommendations', mode: 'model_fast_path', assistantMessage: '已找到《花砖物语》。',
      profile: emptyProfile,
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
      profile: fourPlayersInTwoHours,
      clarification: null, sourceCount: 20, candidatesEvaluated: 6, games: [],
    }
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      `event: progress\ndata: {"stage":"verifying_bgg_candidates","phase":"completed","action":"search_bgg_catalog","focus":{"kind":"verified_game_count","values":["6"]},"elapsedMs":120,"observedCandidates":8,"verifiedCandidates":6,"hardRejectedCandidates":3,"sourceCount":20}\n\nevent: result\ndata: ${JSON.stringify(payload)}\n\n`,
      { headers: { 'Content-Type': 'text/event-stream' } },
    )))
    const progress: unknown[] = []

    await streamGameRecommendation('/stream', { method: 'POST' }, update => progress.push(update))

    expect(progress).toEqual([expect.objectContaining({
      phase: 'completed',
      action: 'search_bgg_catalog',
      observedCandidates: 8,
      verifiedCandidates: 6,
      hardRejectedCandidates: 3,
      sourceCount: 20,
      focus: { kind: 'verified_game_count', values: ['6'] },
    })])
  })

  it('drops an unknown or unbounded focus without dropping honest progress', async () => {
    const payload = {
      outcome: 'conversation', mode: 'model_assisted', assistantMessage: '我会继续核对。',
      profile: emptyProfile,
      clarification: null, sourceCount: 0, candidatesEvaluated: 0, games: [],
    }
    const tooManyValues = ['one', 'two', 'three', 'four']
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      `event: progress\ndata: ${JSON.stringify({
        stage: 'searching_bgg_catalog', phase: 'started', action: 'search_bgg_catalog',
        focus: { kind: 'raw_tool_query', values: tooManyValues }, elapsedMs: 12,
      })}\n\nevent: result\ndata: ${JSON.stringify(payload)}\n\n`,
      { headers: { 'Content-Type': 'text/event-stream' } },
    )))
    const progress: unknown[] = []

    await streamGameRecommendation('/stream', { method: 'POST' }, update => progress.push(update))

    expect(progress).toEqual([expect.objectContaining({
      stage: 'searching_bgg_catalog',
      action: 'search_bgg_catalog',
      focus: null,
    })])
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
      profile: emptyProfile,
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
