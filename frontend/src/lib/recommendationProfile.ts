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
    playerCount: copyRange(profile.playerCount),
    durationMinutes: copyRange(profile.durationMinutes),
    complexity: copyRange(profile.complexity),
  }
}

function copyRange<T extends number>(
  range: RecommendationConstraintRange<T> | null | undefined,
): RecommendationConstraintRange<T> | null {
  return range ? { ...range } : null
}
