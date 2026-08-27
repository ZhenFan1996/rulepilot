import type {
  RecommendationAgentResponse,
  RecommendationProgressFocus,
  RecommendationProgressUpdate,
} from '@/components/gameRecommendationTypes'

type StreamEvent = { event: string; data: string }

export class RecommendationRequestError extends Error {
  constructor(readonly status: number) {
    super('recommendation unavailable')
    this.name = 'RecommendationRequestError'
  }
}

export class RecommendationStreamError extends Error {
  constructor(readonly code: string) {
    super('recommendation stream did not complete')
    this.name = 'RecommendationStreamError'
  }
}

export async function streamGameRecommendation(
  url: string,
  init: RequestInit,
  onProgress: (update: RecommendationProgressUpdate) => void,
) {
  const response = await fetch(url, init)
  if (!response.ok) throw new RecommendationRequestError(response.status)
  if (!response.body) throw new Error('recommendation stream unavailable')

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let result: RecommendationAgentResponse | null = null
  let latestProgressElapsed = -1

  const consume = (event: StreamEvent) => {
    if (event.event === 'progress') {
      const update = parseProgress(JSON.parse(event.data) as unknown)
      if (update && update.elapsedMs >= latestProgressElapsed) {
        latestProgressElapsed = update.elapsedMs
        onProgress(update)
      }
    } else if (event.event === 'result') {
      result = JSON.parse(event.data) as RecommendationAgentResponse
    } else if (event.event === 'error') {
      const payload = JSON.parse(event.data) as { code?: unknown }
      throw new RecommendationStreamError(typeof payload.code === 'string' ? payload.code : 'recommendation_unavailable')
    }
  }

  while (true) {
    const chunk = await reader.read()
    buffer = `${buffer}${decoder.decode(chunk.value, { stream: !chunk.done })}`.replaceAll('\r\n', '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const raw = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const event = parseEvent(raw)
      if (event) consume(event)
      if (result !== null) {
        void reader.cancel()
        return result as RecommendationAgentResponse
      }
      boundary = buffer.indexOf('\n\n')
    }
    if (chunk.done) break
  }
  if (buffer.trim()) {
    const event = parseEvent(buffer)
    if (event) consume(event)
  }
  if (!result) throw new Error('recommendation stream ended without a result')
  return result
}

function parseProgress(value: unknown): RecommendationProgressUpdate | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const candidate = value as Record<string, unknown>
  const stage = candidate.stage
  const stages = new Set([
    'understanding_request', 'selecting_tools', 'searching_bgg_catalog', 'reading_game_details',
    'discovering_candidates', 'verifying_bgg_candidates', 'researching_game_fit', 'composing_response',
  ])
  const phases = new Set(['started', 'completed', 'retrying', 'failed'])
  const actions = new Set([
    'understand_request', 'choose_next_action', 'reply_to_user', 'ask_user',
    'resolve_bgg_game', 'inspect_candidate_titles', 'browse_bgg_catalog', 'discover_public_candidates',
    'lookup_bgg_games', 'research_game_fit', 'compare_candidates', 'report_no_match', 'recommend_games',
  ])
  if (typeof stage !== 'string' || !stages.has(stage)) return null
  const phase = candidate.phase === undefined ? 'started' : candidate.phase
  if (typeof phase !== 'string' || !phases.has(phase)) return null
  const action = candidate.action === undefined || candidate.action === null ? null : candidate.action
  if (action !== null && (typeof action !== 'string' || !actions.has(action))) return null
  if (!Number.isSafeInteger(candidate.elapsedMs) || (candidate.elapsedMs as number) < 0) return null
  const count = (key: string) => Number.isSafeInteger(candidate[key]) && (candidate[key] as number) >= 0
    ? candidate[key] as number
    : 0
  const observedCandidates = count('observedCandidates')
  const verifiedCandidates = count('verifiedCandidates')
  const hardRejectedCandidates = count('hardRejectedCandidates')
  if (verifiedCandidates > observedCandidates || hardRejectedCandidates > verifiedCandidates) return null
  return {
    stage: stage as RecommendationProgressUpdate['stage'],
    phase: phase as RecommendationProgressUpdate['phase'],
    action: action as RecommendationProgressUpdate['action'],
    focus: parseProgressFocus(candidate.focus),
    elapsedMs: candidate.elapsedMs as number,
    observedCandidates,
    verifiedCandidates,
    hardRejectedCandidates,
    sourceCount: count('sourceCount'),
  }
}

function parseProgressFocus(value: unknown): RecommendationProgressFocus | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const candidate = value as Record<string, unknown>
  const kinds = new Set([
    'catalog_mechanics', 'catalog_categories', 'catalog_families', 'catalog_designers',
    'catalog_publishers', 'candidate_title_count', 'verified_game_count',
    'research_games',
  ])
  if (typeof candidate.kind !== 'string' || !kinds.has(candidate.kind)) return null
  if (!Array.isArray(candidate.values) || candidate.values.length < 1 || candidate.values.length > 3) return null
  if (candidate.values.some(item => typeof item !== 'string'
    || item.trim().length === 0
    || [...item].length > 120)) return null
  const values = candidate.values.map(item => (item as string).trim())
  if (new Set(values).size !== values.length) return null
  return {
    kind: candidate.kind as RecommendationProgressFocus['kind'],
    values,
  }
}

function parseEvent(raw: string): StreamEvent | null {
  let event = 'message'
  const data: string[] = []
  for (const line of raw.split('\n')) {
    if (line.startsWith(':')) continue
    if (line.startsWith('event:')) event = line.slice(6).trim()
    if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  }
  return data.length ? { event, data: data.join('\n') } : null
}
