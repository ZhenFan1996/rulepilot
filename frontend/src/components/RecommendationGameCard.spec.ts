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

  it('shows candidate-scoped checks and keeps its follow-up action in the response language', async () => {
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

    await wrapper.get('button:nth-of-type(2)').trigger('click')
    expect(wrapper.emitted('introduce')).toEqual([[game.bggId, game.name, 'en']])
  })

  it('renders grounded reasons and tradeoffs as safe readable markdown', () => {
    const wrapper = mount(RecommendationGameCard, {
      props: {
        entry: {
          game,
          matches: [],
          reasons: [
            { kind: 'bgg_fact', text: '**2–5 players** are listed.', sourceIndexes: [] },
            { kind: 'preference_inference', text: 'Fits your *short setup* preference.', sourceIndexes: [] },
          ],
          tradeoffs: ['Read the [language note](https://example.test/language).', '[unsafe](javascript:alert(1))'],
        },
        sources: [],
        loading: false,
        responseLocale: 'en',
      },
    })

    expect(wrapper.get('strong').text()).toBe('2–5 players')
    expect(wrapper.get('em').text()).toBe('short setup')
    expect(wrapper.get('a').attributes('href')).toBe('https://example.test/language')
    expect(wrapper.find('a[href^="javascript:"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('**2–5 players**')
  })
})
