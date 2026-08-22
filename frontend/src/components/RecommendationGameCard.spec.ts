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

    expect(wrapper.text()).toContain('2–5 players · 45–75 min · Weight 2.2')
    expect(wrapper.find('[data-testid="candidate-fit-claims"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Constraint check')
    expect(wrapper.text()).not.toContain('Candidate player range is inside the request.')

    await wrapper.findAll('button').find(button => button.text() === 'Tell me more')!.trigger('click')
    expect(wrapper.emitted('introduce')).toEqual([[game.bggId, game.name, 'en']])
    await wrapper.findAll('button').find(button => button.text() === 'Choose and find rulebook')!.trigger('click')
    expect(wrapper.emitted('select')).toEqual([[game]])
  })

  it('does not turn legacy reasons, tradeoffs, or links into a tag wall', () => {
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

    expect(wrapper.text()).toContain('2–5 players · 45–75 min · Weight 2.2')
    expect(wrapper.text()).not.toContain('2–5 players are listed.')
    expect(wrapper.text()).not.toContain('short setup')
    expect(wrapper.text()).not.toContain('language note')
    expect(wrapper.find('a').exists()).toBe(false)
    expect(wrapper.find('strong').exists()).toBe(false)
    expect(wrapper.find('em').exists()).toBe(false)
  })

  it('lays out claim-scoped reasons and tradeoffs without exposing evidence identifiers', () => {
    const wrapper = mount(RecommendationGameCard, {
      props: {
        entry: {
          game,
          matches: [],
          tradeoffs: [],
          replyParts: [
            {
              role: 'why_fit',
              claimType: 'constraint_fit',
              subject: 'durationMinutes',
              text: '候选时长 45–75 分钟与当前时长条件相符。',
              sourceIndexes: [],
            },
            {
              role: 'tradeoff',
              claimType: 'structured_fact',
              subject: 'complexity',
              text: 'BGG 标注复杂度：2.2 / 5。',
              sourceIndexes: [],
            },
          ],
        },
        sources: [],
        loading: false,
        responseLocale: 'zh-CN',
      },
    })

    expect(wrapper.text()).toContain('为什么选它')
    expect(wrapper.text()).toContain('候选时长 45–75 分钟')
    expect(wrapper.text()).toContain('需要留意')
    expect(wrapper.text()).toContain('BGG 标注复杂度：2.2 / 5')
    expect(wrapper.text()).not.toContain('evidenceId')
  })
})
