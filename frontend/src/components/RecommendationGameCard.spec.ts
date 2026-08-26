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
    expect(wrapper.get('[data-testid="recommendation-game-card"]').attributes('data-bgg-id')).toBe('901')
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

  it('labels a recovery fact as verified material instead of claiming it explains user fit', () => {
    const wrapper = mount(RecommendationGameCard, {
      props: {
        entry: {
          game,
          matches: [],
          tradeoffs: [],
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

  it('opens an already published guide directly instead of starting another rulebook search', () => {
    const wrapper = mount(RecommendationGameCard, {
      props: {
        entry: {
          game,
          matches: [],
          tradeoffs: [],
          teachingContinuation: {
            teachingPlanId: 'plan / ready',
            sectionCount: 6,
            stepCount: 18,
          },
        },
        sources: [],
        loading: false,
        responseLocale: 'zh-CN',
      },
    })

    expect(wrapper.get('[data-testid="ready-teaching-continuation"]').text())
      .toContain('现成讲解可读 · 6 章 · 18 步')
    expect(wrapper.get('[data-testid="ready-teaching-continuation"]').text())
      .toContain('实时答疑仍取决于当时服务和可引用证据')
    const readyLink = wrapper.get('[data-testid="open-ready-teaching"]')
    expect(readyLink.attributes('href')).toBe('/read/plan%20%2F%20ready')
    expect(readyLink.attributes('aria-label')).toBe('直接进入讲解：Opaque Candidate')
    expect(wrapper.text()).not.toContain('选这款，找规则书')
    expect(wrapper.emitted('select')).toBeUndefined()
  })
})
