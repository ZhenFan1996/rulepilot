export const RECOMMENDATION_STREAM_ERROR_CODES = [
  'recommendation_unavailable',
  'not_found',
  'revision_conflict',
  'turn_id_reused',
  'turn_in_progress',
  'concurrent_turn',
  'invalid_stream_error',
  'unknown_stream_error',
] as const

export type RecommendationCanaryFailureClass =
  | 'product_terminal'
  | 'terminal_evidence_gap'
  | 'lifecycle_deadline'
  | 'observer_failure'

export function classifyRecommendationStreamError(
  code: string,
): RecommendationCanaryFailureClass {
  return code === 'invalid_stream_error' || code === 'unknown_stream_error'
    ? 'observer_failure'
    : 'product_terminal'
}

export function classifyRecommendationCanaryFailure(observation: {
  observerFailed: boolean
  outcome: string | null
  lifecycleDeadline: boolean
  hasSseResult: boolean
  hasPersistedTerminal: boolean
  ssePersistedContentConsistent: boolean | null
}): RecommendationCanaryFailureClass | null {
  if (observation.observerFailed) return 'observer_failure'
  if (observation.outcome !== null && observation.outcome !== 'recommendations') {
    return 'product_terminal'
  }
  if (observation.lifecycleDeadline) return 'lifecycle_deadline'
  if (!observation.hasSseResult
    || !observation.hasPersistedTerminal
    || observation.ssePersistedContentConsistent === false) {
    return 'terminal_evidence_gap'
  }
  return null
}
