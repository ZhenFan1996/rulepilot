import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'

import { setLocale } from '@/lib/locale'
import RecommendationGameCard from './RecommendationGameCard.vue'

const game = {
  bggId: 901,
  name: 'Opaque Candidate',
  originalName: 'Opaque Candidate',
  nameLocalized: false,
  publicationYear: 2025,
  overallRank: null,
  geekRating: 0,
  averageRating: 0,
  usersRated: 0,
  thumbnailUrl: '',
  minPlayers: 2,
  maxPlayers: 5,
  playingTimeMinutes: 75,
  minimumPlayTimeMinutes: 45,
  maximumPlayTimeMinutes: 75,
  averageWeight: 2.2,
  categories: [],
  mechanics: [],
  bggUrl: 'https://example.test/game/901',
}

describe('RecommendationGameCard', () => {
  afterEach(() => setLocale('zh-CN'))

  it('shows satisfied, conflicting, and unknown candidate-scoped checks without upgrading uncertainty', () => {
    const wrapper = mount(RecommendationGameCard, {
      props: {
        entry: {
          game,
          matches: [],
          tradeoffs: [],
          fitClaims: [
            { subject: 'playerCount', strength: 'hard', relation: 'satisfied', text: 'Candidate player range is inside the request.' },
            { subject: 'durationMinutes', strength: 'soft', relation: 'conflict', text: 'Candidate duration falls outside the preferred range.' },
            { subject: 'interaction', strength: 'hard', relation: 'unknown', text: 'Available facts do not establish the interaction style.' },
          ],
        },
        sources: [],
        loading: false,
        responseLocale: 'en',
      },
    })

    const checks = wrapper.get('[data-testid="candidate-fit-claims"]').text()
    expect(checks).toContain('Constraint check')
    expect(checks).toContain('Hard · Satisfied')
    expect(checks).toContain('Preference · Conflict')
    expect(checks).toContain('Hard · Unknown')
    expect(checks).not.toContain('条件核对')
  })
})
