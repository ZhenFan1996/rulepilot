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

  it('shows only compact source facts and keeps its follow-up actions in the response language', async () => {
    const wrapper = mount(RecommendationGameCard, {
      props: {
        entry: {
          game,
          fitClaims: [
            { subject: 'playerCount', strength: 'hard', relation: 'satisfied', text: 'Candidate player range is inside the request.' },
            { subject: 'durationMinutes', strength: 'soft', relation: 'conflict', text: 'Candidate duration falls outside the preferred range.' },
            { subject: 'interaction', strength: 'hard', relation: 'unknown', text: 'Available facts do not establish the interaction style.' },
          ],
          replyParts: [],
        },
        loading: false,
        responseLocale: 'en',
      },
    })

    expect(wrapper.text()).toContain('2–5 players · 45–75 min · Weight 2.2')
    expect(wrapper.get('[data-testid="recommendation-game-card"]').attributes('data-bgg-id')).toBe('901')
    expect(wrapper.find('[data-testid="candidate-fit-claims"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="recommendation-reason-unavailable"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Constraint check')
    expect(wrapper.text()).not.toContain('Candidate player range is inside the request.')

    await wrapper.findAll('button').find(button => button.text() === 'Tell me more')!.trigger('click')
    expect(wrapper.emitted('introduce')).toEqual([[game.bggId, game.name, 'en']])
    await wrapper.findAll('button').find(button => button.text() === 'Choose and find rulebook')!.trigger('click')
    expect(wrapper.emitted('select')).toEqual([[game]])
  })

})
