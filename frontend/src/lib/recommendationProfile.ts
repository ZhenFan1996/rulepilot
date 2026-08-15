import type {
  RecommendationConstraintRange,
  RecommendationProfile,
} from '@/components/gameRecommendationTypes'

export function emptyRecommendationProfile(): RecommendationProfile {
  return {
    type: 'all',
    interaction: 'any',
    playerCount: null,
    durationMinutes: null,
    complexity: null,
  }
}

export function canonicalRecommendationProfile(profile: RecommendationProfile): RecommendationProfile {
  return {
    type: profile.type,
    interaction: profile.interaction,
    playerCount: copyRange(profile.playerCount) ?? legacyExact(profile.players),
    durationMinutes: copyRange(profile.durationMinutes) ?? legacyMaximum(profile.maxMinutes),
    complexity: copyRange(profile.complexity) ?? legacyMaximum(profile.maxWeight),
  }
}

function copyRange<T extends number>(
  range: RecommendationConstraintRange<T> | null | undefined,
): RecommendationConstraintRange<T> | null {
  return range ? { ...range } : null
}

function legacyExact(value: number | null | undefined): RecommendationConstraintRange | null {
  return typeof value === 'number'
    ? { minimum: value, maximum: value, strength: 'hard', sourceText: '', confirmedTurn: 0 }
    : null
}

function legacyMaximum(value: number | null | undefined): RecommendationConstraintRange | null {
  return typeof value === 'number' && value > 0
    ? { minimum: null, maximum: value, strength: 'hard', sourceText: '', confirmedTurn: 0 }
    : null
}
