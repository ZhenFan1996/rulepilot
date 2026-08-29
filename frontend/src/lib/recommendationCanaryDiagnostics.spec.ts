import { describe, expect, it } from 'vitest'

import {
  classifyRecommendationCanaryFailure,
  classifyRecommendationStreamError,
  RECOMMENDATION_STREAM_ERROR_CODES,
} from './recommendationCanaryDiagnostics'

describe('recommendation canary diagnostics', () => {
  it.each(RECOMMENDATION_STREAM_ERROR_CODES)(
    'classifies the typed stream terminal %s without inventing a missing-evidence failure',
    (code) => {
      expect(classifyRecommendationStreamError(code)).toBe(
        code === 'invalid_stream_error' || code === 'unknown_stream_error'
          ? 'observer_failure'
          : 'product_terminal',
      )
    },
  )

  it.each([
    ['observer failure', { observerFailed: true }, 'observer_failure'],
    ['typed unavailable', { outcome: 'unavailable' }, 'product_terminal'],
    ['lifecycle deadline', { lifecycleDeadline: true }, 'lifecycle_deadline'],
    ['missing SSE result', { hasSseResult: false }, 'terminal_evidence_gap'],
    ['missing persisted terminal', { hasPersistedTerminal: false }, 'terminal_evidence_gap'],
    ['inconsistent terminal content', { ssePersistedContentConsistent: false }, 'terminal_evidence_gap'],
    ['late useful slate', { sloMet: false }, 'interaction_slo'],
    ['timely consistent result', {}, null],
  ] as const)('classifies %s', (_label, override, expected) => {
    expect(classifyRecommendationCanaryFailure({
      observerFailed: false,
      outcome: 'recommendations',
      lifecycleDeadline: false,
      hasSseResult: true,
      hasPersistedTerminal: true,
      ssePersistedContentConsistent: true,
      sloMet: true,
      ...override,
    })).toBe(expected)
  })
})
