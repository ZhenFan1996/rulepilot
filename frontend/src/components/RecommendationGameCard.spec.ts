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
        sources: [],
        loading: false,
        responseLocale: 'en',
      },
    })

    expect(wrapper.text()).toContain('2–5 players · 45–75 min · Weight 2.2')
    expect(wrapper.get('[data-testid="recommendation-game-card"]').attributes('data-bgg-id')).toBe('901')
    expect(wrapper.find('[data-testid="candidate-fit-claims"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="recommendation-reason-unavailable"]').text())
      .toContain('did not produce a safe candidate-specific reason')
    expect(wrapper.text()).not.toContain('Constraint check')
    expect(wrapper.text()).not.toContain('Candidate player range is inside the request.')

    await wrapper.findAll('button').find(button => button.text() === 'Tell me more')!.trigger('click')
    expect(wrapper.emitted('introduce')).toEqual([[game.bggId, game.name, 'en']])
    await wrapper.findAll('button').find(button => button.text() === 'Choose and find rulebook')!.trigger('click')
    expect(wrapper.emitted('select')).toEqual([[game]])
  })

  it('lays out claim-scoped reasons and tradeoffs without exposing evidence identifiers', () => {
    const wrapper = mount(RecommendationGameCard, {
      props: {
        entry: {
          game,
          fitClaims: [],
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
    expect(wrapper.find('[data-testid="recommendation-reason-unavailable"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('evidenceId')
  })

  it('labels a recovery fact as verified material instead of claiming it explains user fit', () => {
    const wrapper = mount(RecommendationGameCard, {
      props: {
        entry: {
          game,
          fitClaims: [],
          replyParts: [{
            role: 'verified_fact',
            claimType: 'taxonomy_classification',
            subject: 'mechanics',
            text: '已核对的机制包括：牌库构筑。',
            sourceIndexes: [],
          }],
        },
        sources: [],
        loading: false,
        responseLocale: 'zh-CN',
      },
    })

    expect(wrapper.text()).toContain('已核对资料')
    expect(wrapper.text()).toContain('已核对的机制包括：牌库构筑。')
    expect(wrapper.text()).not.toContain('为什么选它')
  })

})
