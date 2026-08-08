import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import GameRecommendationAgent from './GameRecommendationAgent.vue'

const baseProfile = { players: null, maxMinutes: null, maxWeight: null, type: 'all', interaction: 'any' }
const game = {
  bggId: 266192, name: '展翅翱翔', originalName: 'Wingspan', nameLocalized: true, publicationYear: 2019,
  overallRank: 34, geekRating: 7.79, averageRating: 8.09, usersRated: 102030,
  thumbnailUrl: 'https://example.test/wingspan.jpg', minPlayers: 1, maxPlayers: 5,
  playingTimeMinutes: 70, averageWeight: 2.5, categories: ['动物'], mechanics: ['卡牌轮抽'],
  bggUrl: 'https://boardgamegeek.com/boardgame/266192',
}

describe('GameRecommendationAgent', () => {
  beforeEach(() => localStorage.setItem('rulepilot:locale', 'zh-CN'))
  afterEach(() => vi.unstubAllGlobals())

  async function mountAgent() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/discover/:bggId', name: 'game-discovery', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()
    return mount(GameRecommendationAgent, { global: { plugins: [router] } })
  }

  it('asks one material question at a time and renders attributed recommendation cards', async () => {
    const requests: Array<Record<string, unknown>> = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      if (String(input) === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      const body = JSON.parse(String(init?.body)) as {
        profile: typeof baseProfile
        message: string
        focusedBggId: number | null
        transcript: { role: string; text: string }[]
      }
      requests.push(body as unknown as Record<string, unknown>)
      if (body.focusedBggId === 266192) return Response.json({
        outcome: 'recommendations', mode: 'model_assisted', assistantMessage: '我补查了教学和桌上节奏。',
        profile: { ...baseProfile, players: 4, maxMinutes: 90, maxWeight: 3.2 }, clarification: null,
        sourceCount: 179737, candidatesEvaluated: 1,
        userModel: { summary: '家庭局，重视参与感', hypotheses: [{ text: '可能不喜欢长时间等待', confidence: 'medium', basedOn: '希望大家都有参与感' }] },
        researchSources: [{ index: 1, title: 'Publisher guide', url: 'https://publisher.example/wingspan', domain: 'publisher.example' }],
        harness: { modelCalls: 2, catalogCalls: 1, webResearchCalls: 1, fallbackUsed: false, actions: ['PLAN_DIALOGUE', 'SEARCH_BGG_CATALOG', 'RESEARCH_GAME_FIT', 'COMPOSE_RECOMMENDATIONS'] },
        games: [{
          game, matches: ['BGG 总榜第 34 名'], tradeoffs: ['需要留意卡牌文字量'],
          reasons: [
            { kind: 'bgg_fact', text: 'BGG 总榜第 34 名', sourceIndexes: [] },
            { kind: 'preference_inference', text: '可能符合你希望全桌参与的倾向', sourceIndexes: [] },
            { kind: 'web_research', text: '发行商资料展示了分步教学流程', sourceIndexes: [1] },
          ],
        }],
      })
      if (body.profile.maxMinutes === null) return Response.json({
        outcome: 'recommendations', mode: 'deterministic', assistantMessage: '先给你几款候选。你们愿意为一局留出多长时间？',
        profile: { ...baseProfile, players: 4 }, sourceCount: 179737, candidatesEvaluated: 20,
        games: [{ game, matches: ['支持 4 人游玩'], tradeoffs: [] }],
        clarification: { field: 'duration', prompt: '你们愿意为一局留出多长时间？', options: [{ value: '90', label: '90 分钟内' }] },
      })
      if (body.profile.maxWeight === null) return Response.json({
        outcome: 'recommendations', mode: 'deterministic', assistantMessage: '我按时长更新了候选。这次想要多复杂？',
        profile: { ...baseProfile, players: 4, maxMinutes: 90 }, sourceCount: 179737, candidatesEvaluated: 20,
        games: [{ game, matches: ['支持 4 人游玩', '70 分钟，不超过你的时长上限'], tradeoffs: [] }],
        clarification: { field: 'complexity', prompt: '这次想要多复杂？', options: [{ value: '3.2', label: '中等策略' }] },
      })
      return Response.json({
        outcome: 'recommendations', mode: 'deterministic', assistantMessage: '下面这些各有侧重。',
        profile: { ...baseProfile, players: 4, maxMinutes: 90, maxWeight: 3.2 }, clarification: null,
        sourceCount: 179737, candidatesEvaluated: 20,
        games: [{ game, matches: ['支持 4 人游玩', '70 分钟，不超过你的时长上限'], tradeoffs: [] }],
      })
    }))
    const wrapper = await mountAgent()

    await wrapper.findAll('button').find(button => button.text() === '朋友聚会想热闹一点')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('展翅翱翔')
    expect(wrapper.text()).toContain('你们愿意为一局留出多长时间')
    await wrapper.findAll('button').find(button => button.text() === '90 分钟内')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '中等策略')!.trigger('click')
    await flushPromises()

    expect(requests).toHaveLength(3)
    expect(requests[0]).toMatchObject({ message: '朋友聚会想热闹一点', transcript: expect.arrayContaining([{ role: 'user', text: '朋友聚会想热闹一点' }]) })
    expect(wrapper.text()).toContain('展翅翱翔')
    expect(wrapper.text()).toContain('Wingspan')
    expect(wrapper.text()).toContain('支持 4 人游玩')
    expect(wrapper.text()).toContain('179,737 条 BGG 快照记录')
    expect(wrapper.get('a[href="/discover/266192"]').attributes('href')).toBe('/discover/266192')

    await wrapper.findAll('button').find(button => button.text() === '介绍一下')!.trigger('click')
    await flushPromises()
    expect(requests[3]).toMatchObject({ focusedBggId: 266192, message: '介绍一下《展翅翱翔》' })
    expect(wrapper.text()).toContain('联网调查')
    expect(wrapper.text()).toContain('发行商资料展示了分步教学流程')
    expect(wrapper.get('a[href="https://publisher.example/wingspan"]').attributes('rel')).toContain('noopener')
    expect(wrapper.text()).toContain('我目前的理解')
    await wrapper.findAll('button').find(button => button.text() === '换一批')!.trigger('click')
    await flushPromises()
    expect(requests).toHaveLength(5)
    expect(requests[4]).toMatchObject({ message: '换一批', excludedBggIds: [266192] })
  })

  it('accepts a natural-language turn with CSRF while preserving a truthful no-match result', async () => {
    const fetchMock = vi.fn(async (input: string | URL | Request, _init?: RequestInit) => {
      if (String(input) === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      return Response.json({
        outcome: 'no_match', mode: 'model_assisted',
        assistantMessage: '这批高排名候选里没有同时满足这些硬条件的桌游。可以放宽时长、复杂度或人数后再试。',
        profile: { ...baseProfile, players: 8, maxMinutes: 30, maxWeight: 2.3 }, clarification: null,
        sourceCount: 179737, candidatesEvaluated: 20, games: [],
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue('8 个人，半小时，规则要简单')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const post = fetchMock.mock.calls.find(([input]) => String(input).includes('/recommendation-agent'))
    expect(post?.[1]?.headers).toMatchObject({ 'X-CSRF-TOKEN': 'csrf' })
    expect(JSON.parse(String(post?.[1]?.body))).toMatchObject({ message: '8 个人，半小时，规则要简单' })
    expect(wrapper.text()).toContain('没有同时满足这些硬条件')
    expect(wrapper.text()).toContain('8 人')
  })

  it('keeps retry and catalog-safe error copy when a turn fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) =>
      String(input) === '/api/auth/csrf'
        ? Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
        : new Response(null, { status: 503 })))
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue('想玩合作游戏')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('目录浏览不受影响')
    expect(wrapper.get('[role="alert"] button').text()).toBe('重试')
  })
})
