/* eslint-disable vue/one-component-per-file */
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, type Component } from 'vue'
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
  const mountedAgents: Array<ReturnType<typeof mount>> = []

  beforeEach(() => {
    localStorage.setItem('rulepilot:locale', 'zh-CN')
    sessionStorage.clear()
  })
  afterEach(() => {
    for (const wrapper of mountedAgents.splice(0)) wrapper.unmount()
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  async function mountAgent(stubs: Record<string, boolean | Component> = {}) {
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
    const wrapper = mount(GameRecommendationAgent, { global: { plugins: [router], stubs } })
    mountedAgents.push(wrapper)
    return wrapper
  }

  it('asks one natural material question and then renders attributed recommendation cards', async () => {
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
        harness: { modelCalls: 3, catalogCalls: 1, webResearchCalls: 1, fallbackUsed: false, actions: ['LOOKUP_BGG_CANDIDATES', 'RESEARCH_GAME_FIT', 'RECOMMEND_GAMES'] },
        games: [{
          game, matches: ['BGG 总榜第 34 名'], tradeoffs: ['需要留意卡牌文字量'],
          reasons: [
            { kind: 'bgg_fact', text: 'BGG 总榜第 34 名', sourceIndexes: [] },
            { kind: 'preference_inference', text: '可能符合你希望全桌参与的倾向', sourceIndexes: [] },
            { kind: 'web_research', text: '发行商资料展示了分步教学流程', sourceIndexes: [1] },
          ],
        }],
      })
      if (body.message === '朋友聚会，想热闹但不要尴尬') return Response.json({
        outcome: 'needs_clarification', mode: 'model_assisted',
        assistantMessage: '听起来你更在意全桌参与感。这次大概几个人、能留多少时间？想到多少说多少就行。',
        profile: baseProfile, sourceCount: 179737, candidatesEvaluated: 0, games: [],
        clarification: { field: 'conversation', prompt: '这次大概几个人、能留多少时间？', options: [] },
        harness: { modelCalls: 1, catalogCalls: 0, webResearchCalls: 0, fallbackUsed: false, actions: ['ASK_USER'] },
      })
      return Response.json({
        outcome: 'recommendations', mode: 'model_assisted', assistantMessage: '明白，我按这组条件核对了一批。',
        profile: { ...baseProfile, players: 4, maxMinutes: 90, maxWeight: 3.2 }, clarification: null,
        sourceCount: 179737, candidatesEvaluated: 20,
        harness: { modelCalls: 4, catalogCalls: 2, webResearchCalls: 0, fallbackUsed: false, actions: ['UPDATE_PREFERENCES', 'SEARCH_BGG_CATALOG', 'LOOKUP_BGG_CANDIDATES', 'RECOMMEND_GAMES'] },
        games: [{ game, matches: ['支持 4 人游玩', '70 分钟，不超过你的时长上限'], tradeoffs: [] }],
      })
    }))
    const wrapper = await mountAgent()

    await wrapper.findAll('button').find(button => button.text() === '朋友聚会，想热闹但不要尴尬')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('这次大概几个人、能留多少时间')
    expect(wrapper.text()).not.toContain('展翅翱翔')
    await wrapper.get('textarea').setValue('4 个人，90 分钟内，想要中等策略')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(requests).toHaveLength(2)
    expect(requests[0]).toMatchObject({ message: '朋友聚会，想热闹但不要尴尬', transcript: expect.arrayContaining([{ role: 'user', text: '朋友聚会，想热闹但不要尴尬' }]) })
    expect(requests[1]).toMatchObject({
      message: '4 个人，90 分钟内，想要中等策略',
      transcript: expect.arrayContaining([
        { role: 'user', text: '朋友聚会，想热闹但不要尴尬' },
        { role: 'user', text: '4 个人，90 分钟内，想要中等策略' },
      ]),
    })
    expect(wrapper.text()).toContain('展翅翱翔')
    expect(wrapper.text()).toContain('Wingspan')
    expect(wrapper.text()).toContain('支持 4 人游玩')
    expect(wrapper.text()).toContain('完整 BGG 目录')
    expect(wrapper.get('button[aria-label="查看完整资料：展翅翱翔"]').attributes('aria-label'))
      .toBe('查看完整资料：展翅翱翔')
    const firstRecommendationTurn = wrapper.get('[data-testid="assistant-recommendation-turn"]')
    expect(firstRecommendationTurn.text()).toContain('明白，我按这组条件核对了一批')
    expect(firstRecommendationTurn.text()).toContain('展翅翱翔')

    await wrapper.findAll('button').find(button => button.text() === '介绍一下')!.trigger('click')
    await flushPromises()
    expect(requests[2]).toMatchObject({ focusedBggId: 266192, message: '介绍一下《展翅翱翔》' })
    expect(wrapper.text()).toContain('进一步了解')
    expect(wrapper.text()).toContain('发行商资料展示了分步教学流程')
    expect(wrapper.get('a[href="https://publisher.example/wingspan"]').attributes('rel')).toContain('noopener')
    expect(wrapper.text()).toContain('目前记下的偏好')
    expect(wrapper.text()).toContain('本轮 Agent 轨迹')
    expect(wrapper.text()).toContain('理解上下文并决定下一步')
    expect(wrapper.text()).toContain('体验资料查证')
    expect(wrapper.findAll('[data-testid="assistant-recommendation-turn"]')).toHaveLength(2)
    await wrapper.findAll('button').find(button => button.text() === '换一批')!.trigger('click')
    await flushPromises()
    expect(requests).toHaveLength(4)
    expect(requests[3]).toMatchObject({ message: '换一批', excludedBggIds: [266192] })
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

  it('scrolls to the start of a newly returned recommendation turn instead of its card footer', async () => {
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
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      const top = this === viewport
        ? 100
        : this.dataset.hasRecommendations === 'true'
          ? 340 - viewport.scrollTop
          : 0
      return { x: 0, y: top, top, left: 0, right: 100, bottom: top + 100, width: 100, height: 100, toJSON: () => ({}) }
    })

    await wrapper.get('textarea').setValue('我想找一款类似白塔庭院的桌游')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(viewport.scrollTop).toBe(232)
    expect(wrapper.get('[data-has-recommendations="true"]').attributes('data-conversation-message')).toBe('')
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
            ? ['LOOKUP_BGG_CANDIDATES', 'RECOMMEND_GAMES']
            : ['SEARCH_BGG_CATALOG', 'RECOMMEND_GAMES'],
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

  it('keeps cards and Agent trajectory attached to the assistant turn that produced them', async () => {
    let turn = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input) === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      turn += 1
      if (turn === 1) return Response.json({
        outcome: 'recommendations', mode: 'model_assisted', assistantMessage: '先给你一组有共同机制的候选。',
        profile: baseProfile, clarification: null, sourceCount: 179737, candidatesEvaluated: 3,
        games: [{ game, matches: ['共享已核对的机制'], tradeoffs: [] }],
        harness: { modelCalls: 4, catalogCalls: 2, webResearchCalls: 0, fallbackUsed: false, actions: ['SEARCH_BGG_BY_NAME', 'LOOKUP_BGG_CANDIDATES', 'RECOMMEND_GAMES'] },
      })
      return Response.json({
        outcome: 'conversation', mode: 'model_assisted', assistantMessage: '明白，我们可以沿着刚才的方向继续聊。',
        profile: baseProfile, clarification: null, sourceCount: 179737, candidatesEvaluated: 0, games: [],
        harness: { modelCalls: 1, catalogCalls: 0, webResearchCalls: 0, fallbackUsed: false, actions: ['REPLY_TO_USER'] },
      })
    }))
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue('想找和花砖物语机制接近的游戏')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('textarea').setValue('我更在意开放轮抽')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const recommendationTurns = wrapper.findAll('[data-testid="assistant-recommendation-turn"]')
    expect(recommendationTurns).toHaveLength(1)
    expect(recommendationTurns[0]!.text()).toContain('先给你一组有共同机制的候选')
    expect(recommendationTurns[0]!.text()).toContain('展翅翱翔')
    expect(recommendationTurns[0]!.text()).toContain('完整目录按标题找候选')
    const conversationTurns = wrapper.findAll('[data-testid="assistant-conversation-turn"]')
    expect(conversationTurns.at(-1)?.text()).toContain('沿着刚才的方向继续聊')
    expect(conversationTurns.at(-1)?.text()).not.toContain('展翅翱翔')
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
    expect(document.body.textContent).toContain('已选《展翅翱翔》')
    expect(document.body.textContent).toContain('仍可粘贴公开 PDF 链接或上传自己的规则书')
    expect(document.body.querySelector('[data-testid="player-journey-surface"]')?.getAttribute('style'))
      .toContain('opacity: 1')

    document.body.querySelector<HTMLButtonElement>('button[aria-label="关闭小窗"]')!.click()
    await flushPromises()
    expect(wrapper.get('[data-testid="player-journey-dock"]').text()).toContain('展翅翱翔')
    const bindingCallsBeforeReopen = fetchMock.mock.calls
      .filter(([input]) => String(input) === '/api/v1/bgg/games/266192/import').length
    await wrapper.get('[data-testid="player-journey-dock"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('已选《展翅翱翔》')
    expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/v1/bgg/games/266192/import'))
      .toHaveLength(bindingCallsBeforeReopen)
  })

  it('turns the same chat workspace into rules Q&A and restores the untouched recommendation draft', async () => {
    const readyStatus = {
      projection: {
        state: 'readable', phase: 'LESSON_READABLE', progress: 92, canReadRulebook: true,
        canReadLesson: true, canAskQuestions: true, retryAction: null, errorCode: null,
      },
      game,
      imported: {
        game: { id: 'game-1', name: '展翅翱翔' },
        edition: { id: 'edition-1', name: 'BGG 版本' },
        alreadyImported: false,
      },
      importJob: { id: 'job-1', state: 'COMPLETED', documentVersionId: 'document-1' },
      plan: { id: 'plan-1', documentVersionId: 'document-1', sections: [{ position: 1, title: '目标' }] },
      lesson: { id: 'lesson-1', status: 'DRAFT_READY', sections: [{ position: 1 }] },
    }
    const HandoffStub = defineComponent({
      name: 'RecommendationRulebookHandoff',
      emits: ['status', 'ask-questions'],
      setup(_props, { emit }) {
        return { ready: () => { emit('status', readyStatus); emit('ask-questions', readyStatus) } }
      },
      template: '<button data-testid="ready-for-questions" type="button" @click="ready">开始规则答疑</button>',
    })
    const AnswerWorkspaceStub = defineComponent({
      name: 'RecommendationAnswerWorkspace',
      props: {
        active: Boolean,
        documentVersionId: { type: String, required: true },
        planId: { type: String, required: true },
        editionId: { type: String, required: true },
        gameTitle: { type: String, required: true },
      },
      template: '<div data-testid="answer-workspace-stub">{{ gameTitle }} · {{ documentVersionId }} · {{ planId }}</div>',
    })
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input) === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      return Response.json({
        outcome: 'recommendations', mode: 'model_assisted', assistantMessage: '这款可以继续准备规则书。',
        profile: baseProfile, clarification: null, sourceCount: 179737, candidatesEvaluated: 1,
        games: [{ game, matches: [], tradeoffs: [] }],
      })
    }))
    const wrapper = await mountAgent({
      RecommendationRulebookHandoff: HandoffStub,
      RecommendationAnswerWorkspace: AnswerWorkspaceStub,
    })

    await wrapper.get('textarea').setValue('想找自然主题的桌游')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '选这款，找规则书')!.trigger('click')
    await wrapper.get('textarea').setValue('这个尚未发送的推荐条件要保留')
    document.body.querySelector<HTMLButtonElement>('[data-testid="ready-for-questions"]')!.click()
    await flushPromises()

    expect(wrapper.get('[data-testid="answer-workspace-stub"]').text()).toContain('展翅翱翔 · document-1 · plan-1')
    expect(wrapper.get('[data-testid="recommendation-conversation"]').isVisible()).toBe(false)
    expect(wrapper.get('[data-testid="agent-role-switcher"]').text()).toContain('继续推荐')
    expect(wrapper.get('[data-testid="agent-role-switcher"]').text()).toContain('规则答疑')

    const recommendationButton = wrapper.findAll('[data-testid="agent-role-switcher"] button').find(button => button.text() === '继续推荐')!
    await recommendationButton.trigger('click')
    await flushPromises()

    expect(recommendationButton.attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[data-testid="recommendation-conversation"]').element.parentElement?.style.display).not.toBe('none')
    expect(wrapper.get('textarea').element.value).toBe('这个尚未发送的推荐条件要保留')
    expect(wrapper.text()).toContain('这款可以继续准备规则书')
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
    expect(wrapper.get('[role="status"]').text()).toContain('收到，接着聊下去')

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
