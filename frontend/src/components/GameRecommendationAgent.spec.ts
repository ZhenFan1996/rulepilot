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
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  async function mountAgent() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/discover/:bggId', name: 'game-discovery', component: { template: '<div />' } },
        { path: '/teach', name: 'teach', component: { template: '<div />' } },
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
    expect(wrapper.text()).toContain('完整 BGG 目录')
    expect(wrapper.get('a[href="/discover/266192"]').attributes('href')).toBe('/discover/266192')

    await wrapper.findAll('button').find(button => button.text() === '介绍一下')!.trigger('click')
    await flushPromises()
    expect(requests[3]).toMatchObject({ focusedBggId: 266192, message: '介绍一下《展翅翱翔》' })
    expect(wrapper.text()).toContain('进一步了解')
    expect(wrapper.text()).toContain('发行商资料展示了分步教学流程')
    expect(wrapper.get('a[href="https://publisher.example/wingspan"]').attributes('rel')).toContain('noopener')
    expect(wrapper.text()).toContain('目前记下的偏好')
    expect(wrapper.text()).toContain('本轮实际调用')
    expect(wrapper.text()).toContain('完整目录条件筛选')
    expect(wrapper.text()).toContain('体验资料查证')
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

  it('keeps the newest reply above the composer by scrolling the conversation viewport', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input) === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      return Response.json({
        outcome: 'recommendations', mode: 'model_assisted',
        assistantMessage: '我先核对参考游戏，再给出有具体共同机制的候选。',
        profile: baseProfile, clarification: null, sourceCount: 179737, candidatesEvaluated: 1,
        games: [{ game, matches: ['共享已核对的机制'], tradeoffs: [] }],
        harness: { modelCalls: 1, catalogCalls: 1, webResearchCalls: 0, fallbackUsed: false, actions: ['RESOLVE_BGG_REFERENCE'] },
      })
    }))
    const wrapper = await mountAgent()
    const viewport = wrapper.get('[data-testid="recommendation-conversation"]').element as HTMLElement
    Object.defineProperty(viewport, 'scrollHeight', { configurable: true, get: () => 640 })

    await wrapper.get('textarea').setValue('我想找一款类似白塔庭院的桌游')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(viewport.scrollTop).toBe(640)
    expect(wrapper.text()).toContain('在 BGG 核对参考游戏')
    expect(wrapper.text()).toContain('我先核对参考游戏')
  })

  it('leaves natural references to the Agent and supplies verified conversation games as context', async () => {
    const requests: Array<Record<string, unknown>> = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      if (String(input) === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      const body = JSON.parse(String(init?.body)) as Record<string, unknown>
      requests.push(body)
      const knownGames = body.knownGames as Array<{ bggId: number }> | undefined
      const focused = knownGames?.some(entry => entry.bggId === game.bggId) === true
      return Response.json({
        outcome: 'recommendations', mode: 'model_assisted',
        assistantMessage: focused ? '这是刚才那款游戏的详细介绍。' : '先看这款是否接近你的想法。',
        profile: baseProfile, clarification: null, sourceCount: 179737, candidatesEvaluated: 1,
        harness: {
          modelCalls: 2, catalogCalls: 1, webResearchCalls: 0, fallbackUsed: false,
          actions: focused
            ? ['PLAN_DIALOGUE', 'LOOKUP_BGG_GAME', 'COMPOSE_GAME_RESPONSE']
            : ['PLAN_DIALOGUE', 'SEARCH_BGG_CATALOG', 'COMPOSE_RECOMMENDATIONS'],
        },
        games: [{ game, matches: [], tradeoffs: [] }],
      })
    }))
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue('想找一款自然主题的游戏')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('textarea').setValue('能介绍一下《展翅翱翔》吗？')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    await wrapper.get('textarea').setValue('它是什么机制，属于什么类型，具体怎么玩？')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(requests).toHaveLength(3)
    expect(requests[1]).toMatchObject({
      message: '能介绍一下《展翅翱翔》吗？',
      focusedBggId: null,
      excludedBggIds: [],
      knownGames: [{ bggId: 266192, name: '展翅翱翔', originalName: 'Wingspan' }],
      shownBggIds: [266192],
    })
    expect(requests[2]).toMatchObject({
      message: '它是什么机制，属于什么类型，具体怎么玩？',
      focusedBggId: null,
      excludedBggIds: [],
      knownGames: [{ bggId: 266192, name: '展翅翱翔', originalName: 'Wingspan' }],
      shownBggIds: [266192],
    })
    expect(wrapper.text()).toContain('这是刚才那款游戏的详细介绍')
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

    expect(wrapper.text()).toContain('你写下的条件还在')
    expect(wrapper.get('[role="alert"] button').text()).toBe('重试')
  })

  it('opens the rulebook handoff directly from a recommendation card', async () => {
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/bgg/games/266192/import') return Response.json({
        game: { id: 'game-1', name: '展翅翱翔' },
        edition: { id: 'edition-1', name: 'BGG 版本' },
        alreadyImported: false,
      })
      if (path.startsWith('/api/v1/documents/rulebook-candidates?')) return Response.json({
        configured: true,
        candidates: [],
      })
      return Response.json({
        outcome: 'recommendations', mode: 'model_assisted', assistantMessage: '这款可以先看看。',
        profile: { ...baseProfile, players: 4 }, clarification: null,
        sourceCount: 179737, candidatesEvaluated: 1, games: [{ game, matches: [], tradeoffs: [] }],
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue('想找自然主题的桌游')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '选这款，找规则书')!.trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/bgg/games/266192/import', expect.objectContaining({ method: 'POST' }))
    expect(wrapper.text()).toContain('已选《展翅翱翔》')
    expect(wrapper.text()).toContain('仍可粘贴公开 PDF 链接或上传自己的规则书')
  })

  it('shows only progress stages actually reported by the recommendation stream', async () => {
    vi.useFakeTimers()
    const encoder = new TextEncoder()
    let streamController: ReadableStreamDefaultController<Uint8Array> | undefined
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input) === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      return new Response(new ReadableStream<Uint8Array>({
        start(controller) { streamController = controller },
      }), { headers: { 'Content-Type': 'text/event-stream' } })
    }))
    const wrapper = await mountAgent()
    await flushPromises()

    await wrapper.get('textarea').setValue('想找有探索感的桌游')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toContain('收到，正在看看')

    streamController?.enqueue(encoder.encode('event: progress\ndata: {"stage":"searching_bgg_catalog","elapsedMs":120}\n\n'))
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toContain('正在桌游目录里查找')

    await vi.advanceTimersByTimeAsync(1300)
    expect(wrapper.get('[role="status"]').text()).toContain('正在桌游目录里查找')

    streamController?.enqueue(encoder.encode(`event: result\ndata: ${JSON.stringify({
      outcome: 'no_match', mode: 'model_assisted', assistantMessage: '还需要再确认一个偏好。',
      profile: baseProfile, clarification: null, sourceCount: 179737, candidatesEvaluated: 20, games: [],
    })}\n\n`))
    streamController?.close()
    await flushPromises()
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
  })
})
