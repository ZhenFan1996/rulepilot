/* eslint-disable vue/one-component-per-file */
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, type Component } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { setLocale } from '@/lib/locale'
import { LOGIN_REQUIRED_EVENT } from '@/lib/authSession'
import GameRecommendationAgent from './GameRecommendationAgent.vue'

const baseProfile = { players: null, maxMinutes: null, maxWeight: null, type: 'all', interaction: 'any' }
const game = {
  bggId: 266192, name: '展翅翱翔', originalName: 'Wingspan', nameLocalized: true, publicationYear: 2019,
  overallRank: 34, geekRating: 7.79, averageRating: 8.09, usersRated: 102030,
  thumbnailUrl: 'https://example.test/wingspan.jpg', minPlayers: 1, maxPlayers: 5,
  playingTimeMinutes: 70, averageWeight: 2.5, categories: ['动物'], mechanics: ['卡牌轮抽'],
  bggUrl: 'https://boardgamegeek.com/boardgame/266192',
}
const secondGame = {
  ...game,
  bggId: 342942,
  name: '方舟动物园',
  originalName: 'Ark Nova',
  thumbnailUrl: 'https://example.test/ark-nova.jpg',
  bggUrl: 'https://boardgamegeek.com/boardgame/342942',
}

describe('GameRecommendationAgent', () => {
  const mountedAgents: Array<ReturnType<typeof mount>> = []

  beforeEach(() => {
    localStorage.setItem('rulepilot:locale', 'zh-CN')
    setLocale('zh-CN')
    sessionStorage.clear()
  })
  afterEach(() => {
    for (const wrapper of mountedAgents.splice(0)) wrapper.unmount()
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    setLocale('zh-CN')
  })

  async function mountAgent(
    stubs: Record<string, boolean | Component> = {},
    props: Record<string, unknown> = {},
  ) {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/login', name: 'login', component: { template: '<div />' } },
        { path: '/register', name: 'register', component: { template: '<div />' } },
        { path: '/discover/:bggId', name: 'game-discovery', component: { template: '<div />' } },
        { path: '/teach', name: 'teach', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(GameRecommendationAgent, {
      attachTo: document.body,
      props: props as never,
      global: { plugins: [router], stubs },
    })
    mountedAgents.push(wrapper)
    return wrapper
  }

  it('keeps a guest draft and offers explicit sign-in without starting a recommendation request', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountAgent({}, { sessionIdentity: '' })
    const detailedDraft = '4 个人，想玩 60 分钟内、全程都有参与感的游戏；还要保留这一条完整偏好。'.repeat(700)

    await wrapper.get('textarea').setValue(detailedDraft)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(fetchMock).not.toHaveBeenCalled()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value)
      .toBe(detailedDraft)
    expect(wrapper.text()).toContain('推荐需要登录')
    expect(wrapper.text()).toContain('条件已保留在这个浏览器会话中')
    expect(wrapper.get('a[href^="/login"]').attributes('href')).toContain('redirect=/discover')
    expect(wrapper.get('a[href^="/register"]').attributes('href')).toContain('redirect=/discover')
    expect(sessionStorage.getItem('rulepilot:recommendation-draft:v1'))
      .toContain(detailedDraft)

    wrapper.unmount()
    fetchMock.mockClear()
    const returned = await mountAgent({}, { sessionIdentity: 'player' })
    expect((returned.get('textarea').element as HTMLTextAreaElement).value)
      .toBe(detailedDraft)
    expect(fetchMock).toHaveBeenCalledOnce()
    expect(String(fetchMock.mock.calls[0]?.[0])).toBe('/api/v1/bgg/recommendation-agent/session')
  })

  it('turns an expired-session 401 into the same recoverable sign-in gate', async () => {
    const loginRequired = vi.fn()
    window.addEventListener(LOGIN_REQUIRED_EVENT, loginRequired, { once: true })
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input) === '/api/auth/csrf') {
        return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      }
      return new Response(null, { status: 401 })
    }))
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue('两个人，想玩半小时的对抗游戏')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(loginRequired).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('推荐需要登录')
    expect(wrapper.text()).not.toContain('刚才没有接上')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value)
      .toBe('两个人，想玩半小时的对抗游戏')
  })

  it('renders the assistant narrative as safe markdown without activating model supplied HTML', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input) === '/api/auth/csrf') {
        return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      }
      return Response.json({
        outcome: 'conversation',
        mode: 'model_assisted',
        assistantMessage: '**Short answer**\n\n- [Useful source](https://example.test/source)\n- [unsafe](javascript:alert(1))\n\n<script>alert(1)</script>',
        profile: baseProfile,
        clarification: null,
        sourceCount: 0,
        candidatesEvaluated: 0,
        games: [],
      })
    }))
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue('Give me the short answer')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const turn = wrapper.findAll('[data-testid="assistant-conversation-turn"]').at(-1)!
    expect(turn.get('strong').text()).toBe('Short answer')
    expect(turn.get('a').attributes('href')).toBe('https://example.test/source')
    expect(turn.find('script').exists()).toBe(false)
    expect(turn.find('a[href^="javascript:"]').exists()).toBe(false)
    expect(turn.text()).toContain('<script>alert(1)</script>')
  })

  it('完整显示带推荐卡片的助手回答，而不是用短 recommendationLead 覆盖它', async () => {
    const assistantMessage = '如果你们四个人今晚想玩一局，我会先选《展翅翱翔》：人数和约 70 分钟时长都在你给出的范围内；它的卡牌组合会让每个人都有规划空间。取舍是卡牌文字较多，第一次讲解最好预留一点时间。'
    const recommendationLead = '先选它。'
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input) === '/api/auth/csrf') {
        return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      }
      return Response.json({
        outcome: 'recommendations',
        mode: 'model_assisted',
        assistantMessage,
        recommendationLead,
        profile: { ...baseProfile, players: 4, maxMinutes: 90 },
        clarification: null,
        sourceCount: 1,
        candidatesEvaluated: 1,
        games: [{
          game,
          matches: [],
          tradeoffs: [],
          replyParts: [
            {
              role: 'why_fit',
              claimType: 'constraint_fit',
              subject: 'durationMinutes',
              text: '约 70 分钟的时长适合今晚的安排。',
              sourceIndexes: [],
            },
            {
              role: 'tradeoff',
              claimType: 'structured_fact',
              subject: 'complexity',
              text: '第一次讲解时要照顾卡牌文字量。',
              sourceIndexes: [],
            },
          ],
        }],
      })
    }))
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue('四个人，90 分钟内，想要大家都有参与感')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const turn = wrapper.get('[data-testid="assistant-recommendation-turn"]')
    expect(turn.text()).toContain(assistantMessage)
    expect(turn.text()).not.toContain(recommendationLead)
    expect(turn.findAll('dt').filter(label => label.text() === '为什么选它')).toHaveLength(1)
    expect(turn.findAll('dt').filter(label => label.text() === '需要留意')).toHaveLength(1)
  })

  it('restores an account-scoped transcript, preferences, and verified candidate names after route remount', async () => {
    const fetchMock = vi.fn(async (input: string | URL | Request, _init?: RequestInit) => {
      if (String(input) === '/api/auth/csrf') {
        return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      }
      return Response.json({
        outcome: 'recommendations', mode: 'model_assisted', assistantMessage: '我核对了这款候选，可以继续比较。',
        profile: { ...baseProfile, players: 4, maxMinutes: 90 }, clarification: null,
        sourceCount: 179737, candidatesEvaluated: 1,
        games: [{ game, matches: ['支持 4 人游玩'], tradeoffs: [] }],
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountAgent({}, { sessionIdentity: 'alice' })

    await wrapper.get('textarea').setValue('想找 4 人、90 分钟内的自然主题游戏')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    wrapper.unmount()
    fetchMock.mockClear()

    const returned = await mountAgent({}, { sessionIdentity: 'alice' })
    expect(returned.text()).toContain('想找 4 人、90 分钟内的自然主题游戏')
    expect(returned.text()).toContain('我核对了这款候选，可以继续比较。')
    expect(returned.text()).toContain('上次已核对候选：展翅翱翔')
    expect(returned.text()).toContain('4 人')
    expect(fetchMock).toHaveBeenCalledOnce()
    expect(String(fetchMock.mock.calls[0]?.[0])).toBe('/api/v1/bgg/recommendation-agent/session')

    await returned.get('textarea').setValue('那它和我前面说的自然主题偏好怎么取舍？')
    await returned.get('form').trigger('submit')
    await flushPromises()
    const continuedRequest = fetchMock.mock.calls.find(([input]) => String(input).includes('/recommendation-agent/stream'))
    const continuedBody = JSON.parse(String(continuedRequest?.[1]?.body)) as {
      knownGames: Array<{ bggId: number; name: string; originalName: string }>
      transcript: Array<{ role: string; text: string }>
    }
    expect(continuedBody.knownGames).toEqual([{ bggId: 266192, name: '展翅翱翔', originalName: 'Wingspan' }])
    expect(continuedBody.transcript).toEqual(expect.arrayContaining([
      { role: 'user', text: '想找 4 人、90 分钟内的自然主题游戏' },
      { role: 'assistant', text: '我核对了这款候选，可以继续比较。' },
      { role: 'user', text: '那它和我前面说的自然主题偏好怎么取舍？' },
    ]))
    expect(continuedBody.transcript.map(turn => turn.text).join('\n')).not.toContain('上次已核对候选')

    returned.unmount()
    const otherAccount = await mountAgent({}, { sessionIdentity: 'bob' })
    expect(otherAccount.text()).not.toContain('自然主题游戏')
    expect(otherAccount.text()).not.toContain('上次已核对候选')
  })

  it('restores the selected verified game identity so its guide and Q&A can be found after refresh', async () => {
    const conversationId = '2efc8376-883b-4ec0-b310-e1fc39a75473'
    const recommendation = {
      conversationId,
      revision: 1,
      outcome: 'recommendations' as const,
      mode: 'model_assisted' as const,
      assistantMessage: '这款已经核对，可以继续准备规则书。',
      profile: baseProfile,
      clarification: null,
      sourceCount: 1,
      candidatesEvaluated: 1,
      games: [{ game, matches: [], tradeoffs: [] }],
    }
    let restoring = false
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/bgg/recommendation-agent/session') {
        if (!restoring) return new Response(null, { status: 204 })
        return Response.json({
          conversationId,
          revision: 1,
          profile: baseProfile,
          transcript: [
            { role: 'user', text: '找一款自然主题游戏' },
            { role: 'assistant', text: recommendation.assistantMessage },
          ],
          knownGames: [{ bggId: game.bggId, name: game.name, originalName: game.originalName }],
          shownBggIds: [game.bggId],
          processing: false,
          latestResponse: recommendation,
        })
      }
      return Response.json(recommendation)
    }))
    const wrapper = await mountAgent({ RecommendationRulebookHandoff: true }, { sessionIdentity: 'alice' })
    await flushPromises()
    await wrapper.get('textarea').setValue('找一款自然主题游戏')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '选这款，找规则书')!.trigger('click')
    await flushPromises()
    wrapper.unmount()

    restoring = true
    const restored = await mountAgent({ RecommendationRulebookHandoff: true }, { sessionIdentity: 'alice' })
    await flushPromises()

    expect(restored.get('[data-testid="player-journey-continuation"]').text()).toContain('展翅翱翔')
    expect(restored.get('[data-testid="player-journey-dock"]').text()).toContain('打开进度')
  })

  it('keeps independent journey cards in the chat when the player starts another game', async () => {
    sessionStorage.setItem(
      'rulepilot:recommendation-journeys:v1:alice',
      JSON.stringify([game, secondGame]),
    )
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input) === '/api/v1/bgg/recommendation-agent/session') {
        return new Response(null, { status: 204 })
      }
      return new Response(null, { status: 404 })
    }))

    const wrapper = await mountAgent({ RecommendationRulebookHandoff: true }, { sessionIdentity: 'alice' })
    await flushPromises()

    const cards = wrapper.findAll('[data-testid="player-journey-continuation"]')
    expect(cards).toHaveLength(2)
    expect(cards[0]!.text()).toContain('展翅翱翔')
    expect(cards[1]!.text()).toContain('方舟动物园')

    await cards[0]!.get('[data-testid="player-journey-dock"]').trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[data-testid="player-journey-backdrop"]')?.getAttribute('style') ?? '')
      .not.toContain('display: none')

    wrapper.unmount()
    const restored = await mountAgent({ RecommendationRulebookHandoff: true }, { sessionIdentity: 'alice' })
    expect(restored.findAll('[data-testid="player-journey-continuation"]')).toHaveLength(2)
  })

  it('restores the owner-scoped server conversation as authoritative and continues at its revision', async () => {
    const conversationId = '2efc8376-883b-4ec0-b310-e1fc39a75473'
    const englishGame = { ...game, name: 'Wingspan', nameLocalized: false }
    const requests: Array<Record<string, unknown>> = []
    const restoredProfile = {
      ...baseProfile,
      players: 4,
      maxMinutes: 180,
      maxWeight: 3.2,
      playerCount: { minimum: 3, maximum: 4, strength: 'hard', sourceText: '3–4 players', confirmedTurn: 1 },
      durationMinutes: { minimum: 120, maximum: 180, strength: 'hard', sourceText: '120–180 minutes', confirmedTurn: 1 },
      complexity: { minimum: 2.4, maximum: 3.2, strength: 'soft', sourceText: 'prefer 2.4–3.2', confirmedTurn: 1 },
    }
    const latestResponse = {
      conversationId, revision: 4, clientTurnId: null, replayed: true, responseLocale: 'en',
      outcome: 'recommendations', mode: 'model_assisted', assistantMessage: 'These are the verified options so far.',
      profile: restoredProfile, clarification: null,
      sourceCount: 179737, candidatesEvaluated: 1,
      games: [{ game: englishGame, matches: ['Supports 4 players'], tradeoffs: [] }],
    }
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/bgg/recommendation-agent/session') {
        return Response.json({
          conversationId,
          revision: 4,
          profile: latestResponse.profile,
          transcript: [
            { role: 'user', text: 'Four players, around 90 minutes.' },
            { role: 'assistant', text: latestResponse.assistantMessage },
          ],
          knownGames: [{ bggId: game.bggId, name: 'Wingspan', originalName: 'Wingspan' }],
          shownBggIds: [game.bggId],
          processing: false,
          processingSince: null,
          latestResponse,
        })
      }
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      const request = JSON.parse(String(init?.body)) as Record<string, unknown>
      requests.push(request)
      return Response.json({
        ...latestResponse,
        revision: 5,
        clientTurnId: request.clientTurnId,
        replayed: false,
        assistantMessage: 'I kept the restored candidates and checked the next question.',
        outcome: 'conversation',
        games: [],
      })
    }))

    const wrapper = await mountAgent({}, { sessionIdentity: 'alice' })
    await flushPromises()

    expect(wrapper.text()).toContain('Four players, around 90 minutes.')
    expect(wrapper.text()).toContain('These are the verified options so far.')
    expect(wrapper.text()).toContain('Wingspan')
    expect(wrapper.findAll('[data-testid="recommendation-game-card"]')).toHaveLength(1)
    expect(wrapper.findAll('button').some(button => button.text() === 'Tell me more')).toBe(true)

    await wrapper.get('textarea').setValue('Keep those candidates and compare the uncertainty.')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(requests).toHaveLength(1)
    expect(requests[0]).toMatchObject({
      conversationId,
      revision: 4,
      message: 'Keep those candidates and compare the uncertainty.',
      shownBggIds: [game.bggId],
    })
    expect(requests[0]?.profile).toEqual({
      type: 'all',
      interaction: 'any',
      playerCount: restoredProfile.playerCount,
      durationMinutes: restoredProfile.durationMinutes,
      complexity: restoredProfile.complexity,
    })
    expect(String(requests[0]?.clientTurnId)).toHaveLength(36)
    expect(wrapper.text()).toContain('I kept the restored candidates and checked the next question.')
  })

  it('reconciles a broken transport with the completed server turn without losing the next draft', async () => {
    const conversationId = 'fd6fa932-b4c8-4896-8136-259129502f69'
    const completedProfile = {
      ...baseProfile,
      playerCount: { minimum: 3, maximum: 4, strength: 'hard', sourceText: '3–4 人', confirmedTurn: 1 },
      durationMinutes: { minimum: 120, maximum: 180, strength: 'hard', sourceText: '120–180 分钟', confirmedTurn: 1 },
      complexity: null,
    }
    let sessionReads = 0
    let submitted: Record<string, unknown> | null = null
    let rejectStream: (() => void) | null = null
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/bgg/recommendation-agent/session') {
        sessionReads += 1
        if (!submitted) return new Response(null, { status: 204 })
        const clientTurnId = String(submitted.clientTurnId)
        const latestResponse = {
          conversationId, revision: 1, clientTurnId, replayed: true, responseLocale: 'zh-CN',
          outcome: 'recommendations', mode: 'model_assisted', assistantMessage: '服务器已经完成并保存了这一轮：候选的标注范围覆盖 3–4 人，这次仍按 120–180 分钟来选。',
          profile: completedProfile, clarification: null, sourceCount: 179737, candidatesEvaluated: 1,
          games: [{ game, matches: [], tradeoffs: [] }],
        }
        return Response.json({
          conversationId,
          revision: 1,
          profile: completedProfile,
          transcript: [
            { role: 'user', text: '想找 3–4 人、120–180 分钟的策略游戏' },
            { role: 'assistant', text: latestResponse.assistantMessage },
          ],
          knownGames: [{ bggId: game.bggId, name: game.name, originalName: game.originalName }],
          shownBggIds: [game.bggId],
          processing: false,
          processingSince: null,
          latestResponse,
        })
      }
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      submitted = JSON.parse(String(init?.body)) as Record<string, unknown>
      return await new Promise<Response>((_resolve, reject) => {
        rejectStream = () => reject(new TypeError('connection closed after request upload'))
      })
    }))
    const wrapper = await mountAgent({}, { sessionIdentity: 'alice' })
    await flushPromises()

    await wrapper.get('textarea').setValue('想找 3–4 人、120–180 分钟的策略游戏')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(rejectStream).not.toBeNull()
    await wrapper.get('textarea').setValue('这一句还没有发送')
    rejectStream!()
    await flushPromises()

    expect(sessionReads).toBe(2)
    expect(wrapper.text()).toContain('服务器已经完成并保存了这一轮')
    expect(wrapper.text()).toContain('3–4 人')
    expect(wrapper.text()).toContain('120–180 分钟')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('textarea').element).toHaveProperty('value', '这一句还没有发送')
    const turns = wrapper.findAll('[data-conversation-message]').map(turn => turn.text())
    expect(turns.filter(turn => turn.includes('想找 3–4 人、120–180 分钟的策略游戏'))).toHaveLength(1)
    expect(turns.filter(turn => turn.includes('服务器已经完成并保存了这一轮'))).toHaveLength(1)

    wrapper.unmount()
    sessionStorage.removeItem('rulepilot:recommendation-conversation:v1:alice')
    const refreshed = await mountAgent({}, { sessionIdentity: 'alice' })
    await flushPromises()
    expect(sessionReads).toBe(3)
    expect(refreshed.text()).toContain('服务器已经完成并保存了这一轮')
    expect(refreshed.text()).toContain('候选的标注范围覆盖 3–4 人')
    expect(refreshed.get('textarea').element).toHaveProperty('value', '这一句还没有发送')
  })

  it('keeps an unaccepted turn after server reconciliation and retries the exact request identity', async () => {
    const conversationId = '6b97841c-2a4d-49e9-a451-a58ee02f4583'
    const previousClientTurnId = '41d3b9ea-5d58-468c-9528-a6459725294a'
    const previousResponse = {
      conversationId, revision: 3, clientTurnId: previousClientTurnId, replayed: true, responseLocale: 'zh-CN',
      outcome: 'recommendations', mode: 'model_assisted', assistantMessage: '上一轮已经核对了一款候选。',
      profile: baseProfile, clarification: null, sourceCount: 179737, candidatesEvaluated: 1,
      games: [{ game, matches: ['支持 4 人'], tradeoffs: [] }],
    }
    const requestBodies: Array<Record<string, unknown>> = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/bgg/recommendation-agent/session') {
        return Response.json({
          conversationId,
          revision: 3,
          profile: baseProfile,
          transcript: [
            { role: 'user', text: '先给我一款已核对候选' },
            { role: 'assistant', text: previousResponse.assistantMessage },
          ],
          knownGames: [{ bggId: game.bggId, name: game.name, originalName: game.originalName }],
          shownBggIds: [game.bggId],
          processing: false,
          processingSince: null,
          latestResponse: previousResponse,
        })
      }
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      const body = JSON.parse(String(init?.body)) as Record<string, unknown>
      requestBodies.push(body)
      if (requestBodies.length === 1) {
        return new Response('event: error\ndata: {"code":"recommendation_unavailable"}\n\n', {
          headers: { 'Content-Type': 'text/event-stream' },
        })
      }
      return Response.json({
        ...previousResponse,
        revision: 4,
        clientTurnId: body.clientTurnId,
        replayed: false,
        outcome: 'conversation',
        assistantMessage: '重试只执行了原来的玩家回合。',
        games: [],
      })
    }))
    const wrapper = await mountAgent({}, { sessionIdentity: 'alice' })
    await flushPromises()

    await wrapper.get('textarea').setValue('保留上一款，再比较未知项')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('保留上一款，再比较未知项')
    expect(wrapper.text()).toContain('刚才没有接上')
    const retryButton = wrapper.findAll('button').find(button => button.text() === '重试')
    expect(retryButton).toBeDefined()
    await retryButton!.trigger('click')
    await flushPromises()

    expect(requestBodies).toHaveLength(2)
    expect(requestBodies[1]).toEqual(requestBodies[0])
    expect(wrapper.text()).toContain('重试只执行了原来的玩家回合。')
  })

  it('keeps an unavailable result retryable and shows its terminal player-facing explanation', async () => {
    const conversationId = 'cb9e5386-415f-48ea-8991-0cad2fc67d02'
    const originalRequest = '找一些适合四个人的科幻主题桌游'
    const provisionalAnswer = '我已经找到几款可核对的候选，正在确认它们与主题的关系。'
    const unavailableMessage = '推荐服务暂时不可用。'
    const requestBodies: Array<Record<string, unknown>> = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      if (String(input) === '/api/auth/csrf') {
        return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      }
      const body = JSON.parse(String(init?.body)) as Record<string, unknown>
      requestBodies.push(body)
      if (requestBodies.length === 1) {
        return new Response([
          `event: answer_part\ndata: ${JSON.stringify({ field: 'message', text: provisionalAnswer })}\n\n`,
          `event: result\ndata: ${JSON.stringify({
            conversationId,
            revision: 1,
            clientTurnId: body.clientTurnId,
            replayed: false,
            responseLocale: 'zh-CN',
            outcome: 'unavailable',
            mode: 'model_assisted',
            assistantMessage: unavailableMessage,
            failureBoundary: 'model_response',
            profile: baseProfile,
            clarification: null,
            sourceCount: 179737,
            candidatesEvaluated: 0,
            games: [],
          })}\n\n`,
        ].join(''), { headers: { 'Content-Type': 'text/event-stream' } })
      }
      return Response.json({
        conversationId,
        revision: 2,
        clientTurnId: body.clientTurnId,
        replayed: false,
        responseLocale: 'zh-CN',
        outcome: 'conversation',
        mode: 'model_assisted',
        assistantMessage: '这次请求已经重新核对完成。',
        profile: baseProfile,
        clarification: null,
        sourceCount: 179737,
        candidatesEvaluated: 0,
        games: [],
      })
    }))
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue(originalRequest)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const failedTurn = wrapper.get('[role="alert"]')
    expect(failedTurn.text()).toContain('这次推荐没有完成，也没有写入对话结果')
    expect(failedTurn.text()).toContain('模型这次没有返回完整、可执行的结构')
    expect(failedTurn.text()).not.toContain(unavailableMessage)
    expect(wrapper.get('[data-testid="recommendation-failed-assistant-reply"]').text())
      .toBe(unavailableMessage)
    expect(wrapper.find('[data-testid="recommendation-provisional-failure"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain(provisionalAnswer)
    expect(requestBodies[0]).toMatchObject({ revision: 0, message: originalRequest })
    expect(wrapper.findAll('[data-conversation-message]').filter(turn => turn.text().includes(originalRequest)))
      .toHaveLength(1)

    await failedTurn.get('button').trigger('click')
    await flushPromises()

    expect(requestBodies).toHaveLength(2)
    expect(requestBodies[1]).toMatchObject({
      conversationId,
      revision: 1,
      message: originalRequest,
    })
    expect(requestBodies[1]?.clientTurnId).not.toBe(requestBodies[0]?.clientTurnId)
    expect(requestBodies[1]?.transcript).toEqual(requestBodies[0]?.transcript)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('这次请求已经重新核对完成。')
    expect(wrapper.findAll('[data-conversation-message]').filter(turn => turn.text().includes(originalRequest)))
      .toHaveLength(1)
  })

  it('removes stale clarification choices when the submitted choice becomes unavailable', async () => {
    const conversationId = 'ddf8cd74-3d16-4302-b804-74a2d1edb264'
    let recommendationCalls = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      if (String(input) === '/api/auth/csrf') {
        return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      }
      recommendationCalls += 1
      const body = JSON.parse(String(init?.body)) as { clientTurnId: string }
      if (recommendationCalls === 1) {
        return Response.json({
          conversationId,
          revision: 1,
          clientTurnId: body.clientTurnId,
          replayed: false,
          responseLocale: 'zh-CN',
          outcome: 'needs_clarification',
          mode: 'model_assisted',
          assistantMessage: '你更想先看合作还是对抗？',
          profile: baseProfile,
          clarification: {
            field: 'conversation',
            prompt: '请选择一个方向。',
            options: [{ value: '先看合作游戏', label: '先看合作' }],
          },
          sourceCount: 0,
          candidatesEvaluated: 0,
          games: [],
        })
      }
      return Response.json({
        conversationId,
        revision: 2,
        clientTurnId: body.clientTurnId,
        replayed: false,
        responseLocale: 'zh-CN',
        outcome: 'unavailable',
        mode: 'model_assisted',
        assistantMessage: '推荐服务暂时不可用。',
        failureBoundary: 'time_budget',
        profile: baseProfile,
        clarification: null,
        sourceCount: 0,
        candidatesEvaluated: 0,
        games: [],
      })
    }))
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue('帮我找一款适合今晚的桌游')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const choice = wrapper.findAll('button').find(button => button.text() === '先看合作')
    expect(choice).toBeDefined()

    await choice!.trigger('click')
    await flushPromises()

    expect(wrapper.findAll('button').some(button => button.text() === '先看合作')).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('这次推荐没有完成')
    expect(wrapper.get('[role="alert"]').text()).toContain('没有在本轮时间上限内完成')
    expect(wrapper.findAll('button').filter(button => button.text() === '重试')).toHaveLength(1)
  })

  it('does not restore stale clarification choices after a transport failure and retries the same turn', async () => {
    const conversationId = '38a33554-7ae5-4620-a767-17dcf8521b23'
    const clarificationClientTurnId = 'cbe48ee4-dfdb-4411-a5df-904fb51e5708'
    const clarificationResponse = {
      conversationId,
      revision: 1,
      clientTurnId: clarificationClientTurnId,
      replayed: true,
      responseLocale: 'zh-CN',
      outcome: 'needs_clarification',
      mode: 'model_assisted',
      assistantMessage: '你更想先看合作还是对抗？',
      profile: baseProfile,
      clarification: {
        field: 'conversation',
        prompt: '请选择一个方向。',
        options: [{ value: '先看合作游戏', label: '先看合作' }],
      },
      sourceCount: 0,
      candidatesEvaluated: 0,
      games: [],
    }
    const requestBodies: Array<Record<string, unknown>> = []
    let sessionReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/bgg/recommendation-agent/session') {
        sessionReads += 1
        return Response.json({
          conversationId,
          revision: 1,
          profile: baseProfile,
          transcript: [
            { role: 'user', text: '帮我找一款适合今晚的桌游' },
            { role: 'assistant', text: clarificationResponse.assistantMessage },
          ],
          knownGames: [],
          shownBggIds: [],
          processing: false,
          processingSince: null,
          latestResponse: clarificationResponse,
        })
      }
      if (path === '/api/auth/csrf') {
        return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      }
      const body = JSON.parse(String(init?.body)) as Record<string, unknown>
      requestBodies.push(body)
      if (requestBodies.length === 1) {
        return new Response('event: error\ndata: {"code":"recommendation_unavailable"}\n\n', {
          headers: { 'Content-Type': 'text/event-stream' },
        })
      }
      return Response.json({
        ...clarificationResponse,
        revision: 2,
        clientTurnId: body.clientTurnId,
        replayed: false,
        outcome: 'conversation',
        assistantMessage: '已按原来的选择继续。',
        clarification: null,
      })
    }))
    const wrapper = await mountAgent({}, { sessionIdentity: 'alice' })
    await flushPromises()

    const choice = wrapper.findAll('button').find(button => button.text() === '先看合作')
    expect(choice).toBeDefined()
    await choice!.trigger('click')
    await flushPromises()

    expect(sessionReads).toBe(2)
    expect(wrapper.findAll('button').some(button => button.text() === '先看合作')).toBe(false)
    const retryButton = wrapper.findAll('button').find(button => button.text() === '重试')
    expect(retryButton).toBeDefined()
    expect(requestBodies).toHaveLength(1)

    await retryButton!.trigger('click')
    await flushPromises()

    expect(requestBodies).toHaveLength(2)
    expect(requestBodies[1]).toEqual(requestBodies[0])
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('已按原来的选择继续。')
  })

  it('retries a reused client turn with a new identity without restoring the old authoritative response', async () => {
    const conversationId = '7903fcfe-a884-43b4-811a-b6ebf65b8ad9'
    const conflictingClientTurnId = '0e1779cc-f121-4762-8cf4-3d0e22987b84'
    const retryClientTurnId = '0415d75f-19fb-456b-8fae-b4dcd2b27c96'
    vi.spyOn(crypto, 'randomUUID')
      .mockReturnValueOnce(conflictingClientTurnId)
      .mockReturnValueOnce(retryClientTurnId)
    const oldResponse = {
      conversationId,
      revision: 1,
      clientTurnId: conflictingClientTurnId,
      replayed: true,
      responseLocale: 'zh-CN',
      outcome: 'conversation',
      mode: 'model_assisted',
      assistantMessage: '这是这个 turn identity 之前绑定的旧响应。',
      profile: baseProfile,
      clarification: null,
      sourceCount: 0,
      candidatesEvaluated: 0,
      games: [],
    }
    const requestBodies: Array<Record<string, unknown>> = []
    let sessionReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/bgg/recommendation-agent/session') {
        sessionReads += 1
        return Response.json({
          conversationId,
          revision: 1,
          profile: baseProfile,
          transcript: [
            { role: 'user', text: '旧请求' },
            { role: 'assistant', text: oldResponse.assistantMessage },
          ],
          knownGames: [],
          shownBggIds: [],
          processing: false,
          processingSince: null,
          latestResponse: oldResponse,
        })
      }
      if (path === '/api/auth/csrf') {
        return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      }
      const body = JSON.parse(String(init?.body)) as Record<string, unknown>
      requestBodies.push(body)
      if (requestBodies.length === 1) {
        return new Response('event: error\ndata: {"code":"turn_id_reused"}\n\n', {
          headers: { 'Content-Type': 'text/event-stream' },
        })
      }
      return Response.json({
        ...oldResponse,
        revision: 2,
        clientTurnId: body.clientTurnId,
        replayed: false,
        assistantMessage: '新 identity 已完成原来的玩家请求。',
      })
    }))
    let wrapper = await mountAgent({}, { sessionIdentity: 'alice' })
    await flushPromises()

    await wrapper.get('textarea').setValue('保留这条新请求并安全重试')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(sessionReads).toBe(1)
    expect(requestBodies).toHaveLength(1)
    expect(requestBodies[0]?.clientTurnId).toBe(conflictingClientTurnId)
    expect(wrapper.get('[role="alert"]').text()).toContain('可以直接重试')
    expect(wrapper.findAll('[data-conversation-message]')
      .filter(turn => turn.text().includes('保留这条新请求并安全重试'))).toHaveLength(1)

    wrapper.unmount()
    wrapper = await mountAgent({}, { sessionIdentity: 'alice' })
    await flushPromises()

    expect(sessionReads).toBe(2)
    expect(wrapper.get('[role="alert"]').text()).toContain('可以直接重试')
    expect(wrapper.findAll('[data-conversation-message]')
      .filter(turn => turn.text().includes('保留这条新请求并安全重试'))).toHaveLength(1)
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()

    expect(sessionReads).toBe(2)
    expect(requestBodies).toHaveLength(2)
    const { clientTurnId: firstClientTurnId, ...firstPayload } = requestBodies[0]!
    const { clientTurnId: secondClientTurnId, ...secondPayload } = requestBodies[1]!
    expect(firstClientTurnId).toBe(conflictingClientTurnId)
    expect(secondClientTurnId).toBe(retryClientTurnId)
    expect(secondPayload).toEqual(firstPayload)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('新 identity 已完成原来的玩家请求。')
  })

  it('falls back to the generic retry message for an unknown future failure boundary', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      if (String(input) === '/api/auth/csrf') {
        return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      }
      const body = JSON.parse(String(init?.body)) as { clientTurnId: string }
      return Response.json({
        conversationId: '2c17aa9e-4895-4e5b-868f-0c2615c0dd6b',
        revision: 1,
        clientTurnId: body.clientTurnId,
        replayed: false,
        responseLocale: 'zh-CN',
        outcome: 'unavailable',
        mode: 'model_assisted',
        assistantMessage: '推荐服务暂时不可用。',
        failureBoundary: 'INTERNAL_FUTURE_CODE',
        profile: baseProfile,
        clarification: null,
        sourceCount: 0,
        candidatesEvaluated: 0,
        games: [],
      })
    }))
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue('帮我找一款桌游')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('这次推荐没有完成，也没有写入对话结果')
    expect(alert.text()).not.toContain('失败原因：')
    expect(alert.findAll('button').filter(button => button.text() === '重试')).toHaveLength(1)
  })

  it('recovers one revision conflict with the authoritative revision and the same client turn', async () => {
    const conversationId = '57d274df-43eb-47bd-a30d-55823fc63350'
    const previousClientTurnId = 'e714fd20-ab50-45d5-9506-17d1db2c1ee9'
    const previousResponse = {
      conversationId, revision: 2, clientTurnId: previousClientTurnId, replayed: true, responseLocale: 'zh-CN',
      outcome: 'conversation', mode: 'model_assisted', assistantMessage: '上一轮已经保存。',
      profile: baseProfile, clarification: null, sourceCount: 179737, candidatesEvaluated: 0, games: [],
    }
    const requestBodies: Array<Record<string, unknown>> = []
    let sessionReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/bgg/recommendation-agent/session') {
        sessionReads += 1
        const revision = sessionReads === 1 ? 2 : 3
        return Response.json({
          conversationId,
          revision,
          profile: baseProfile,
          transcript: [
            { role: 'user', text: '先保存这段对话' },
            { role: 'assistant', text: previousResponse.assistantMessage },
          ],
          knownGames: [],
          shownBggIds: [],
          processing: false,
          processingSince: null,
          latestResponse: { ...previousResponse, revision },
        })
      }
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      const body = JSON.parse(String(init?.body)) as Record<string, unknown>
      requestBodies.push(body)
      if (requestBodies.length === 1) {
        return new Response('event: error\ndata: {"code":"revision_conflict"}\n\n', {
          headers: { 'Content-Type': 'text/event-stream' },
        })
      }
      return Response.json({
        ...previousResponse,
        revision: 4,
        clientTurnId: body.clientTurnId,
        replayed: false,
        assistantMessage: '已接上最新会话，继续回答这一轮。',
      })
    }))
    const wrapper = await mountAgent({}, { sessionIdentity: 'alice' })
    await flushPromises()

    await wrapper.get('textarea').setValue('沿用上一轮，再回答这个新问题')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(sessionReads).toBe(2)
    expect(requestBodies).toHaveLength(2)
    expect(requestBodies[0]).toMatchObject({ conversationId, revision: 2 })
    expect(requestBodies[1]).toMatchObject({ conversationId, revision: 3 })
    expect(requestBodies[1]?.clientTurnId).toBe(requestBodies[0]?.clientTurnId)
    expect(requestBodies[1]?.message).toBe(requestBodies[0]?.message)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('已接上最新会话，继续回答这一轮。')
    const turns = wrapper.findAll('[data-conversation-message]').map(turn => turn.text())
    expect(turns.filter(turn => turn.includes('沿用上一轮，再回答这个新问题'))).toHaveLength(1)
  })

  it('stops server recovery polling when browser navigation unmounts the conversation', async () => {
    vi.useFakeTimers()
    let sessionReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input) !== '/api/v1/bgg/recommendation-agent/session') {
        throw new Error('only session recovery is expected')
      }
      sessionReads += 1
      return Response.json({
        conversationId: '2011a4fa-da7c-4a1a-a12b-19d1d36a8758',
        revision: 0,
        profile: baseProfile,
        transcript: [],
        knownGames: [],
        shownBggIds: [],
        processing: true,
        processingSince: '2026-08-15T08:00:00Z',
        latestResponse: null,
      })
    }))
    const wrapper = await mountAgent({}, { sessionIdentity: 'alice' })
    await flushPromises()
    expect(sessionReads).toBe(1)

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(5_000)

    expect(sessionReads).toBe(1)
  })

  it('does not reconcile an intentionally aborted turn into a newly selected account', async () => {
    const bobConversationId = '380a9c35-6746-40d5-8f6c-d10a833011de'
    let visibleOwner = 'alice'
    let sessionReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/bgg/recommendation-agent/session') {
        sessionReads += 1
        if (visibleOwner === 'alice') return new Response(null, { status: 204 })
        const latestResponse = {
          conversationId: bobConversationId, revision: 2,
          clientTurnId: '8aa067f8-8d48-4ed3-a205-901ae01826d5', replayed: true, responseLocale: 'en',
          outcome: 'conversation', mode: 'model_assisted', assistantMessage: 'Bob server conversation.',
          profile: baseProfile, clarification: null, sourceCount: 0, candidatesEvaluated: 0, games: [],
        }
        return Response.json({
          conversationId: bobConversationId,
          revision: 2,
          profile: baseProfile,
          transcript: [
            { role: 'user', text: 'Bob prior turn.' },
            { role: 'assistant', text: latestResponse.assistantMessage },
          ],
          knownGames: [],
          shownBggIds: [],
          processing: false,
          processingSince: null,
          latestResponse,
        })
      }
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      return await new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new DOMException('account changed', 'AbortError')))
      })
    }))
    const wrapper = await mountAgent({}, { sessionIdentity: 'alice' })
    await flushPromises()
    await wrapper.get('textarea').setValue('Alice turn still in flight')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    visibleOwner = 'bob'
    await wrapper.setProps({ sessionIdentity: 'bob' })
    await flushPromises()

    expect(sessionReads).toBe(2)
    expect(wrapper.text()).toContain('Bob server conversation.')
    expect(wrapper.text()).not.toContain('Alice turn still in flight')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('restores a failed turn and its one-click retry without replaying it on mount', async () => {
    const boundaryMessage = `${'😀'.repeat(495)}  A\n中`
    expect(Array.from(boundaryMessage)).toHaveLength(500)
    const requestBodies: Array<Record<string, unknown>> = []
    const fetchMock = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      if (String(input) === '/api/auth/csrf') {
        return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      }
      if (String(input).includes('/recommendation-agent/stream')) {
        requestBodies.push(JSON.parse(String(init?.body)) as Record<string, unknown>)
      }
      throw new Error('offline')
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountAgent({}, { sessionIdentity: 'alice' })

    await wrapper.get('textarea').setValue(boundaryMessage)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('刚才没有接上')
    expect(requestBodies[0]?.message).toBe(boundaryMessage)
    const stored = JSON.parse(sessionStorage.getItem('rulepilot:recommendation-conversation:v1:alice') ?? 'null') as {
      transcript: Array<{ role: string; text: string }>
      pending: { message: string }
    }
    expect(stored.transcript.at(-1)).toEqual({ role: 'user', text: boundaryMessage })
    expect(stored.pending.message).toBe(boundaryMessage)
    wrapper.unmount()
    fetchMock.mockClear()

    const returned = await mountAgent({}, { sessionIdentity: 'alice' })
    expect(returned.text()).toContain('刚才没有接上')
    expect(returned.get('[data-testid="recommendation-failed-assistant-reply"]').text())
      .toContain('我没有猜测或伪造候选')
    expect(returned.findAll('button').some(button => button.text() === '重试')).toBe(true)
    expect(fetchMock).toHaveBeenCalledOnce()
    expect(String(fetchMock.mock.calls[0]?.[0])).toBe('/api/v1/bgg/recommendation-agent/session')

    await returned.findAll('button').find(button => button.text() === '重试')!.trigger('click')
    await flushPromises()
    expect(requestBodies).toHaveLength(2)
    expect(requestBodies[1]).toMatchObject({
      message: boundaryMessage,
      clientTurnId: requestBodies[0]?.clientTurnId,
    })
  })

  it('clears one account immediately when the same mounted page switches owners, then restores it safely', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input) === '/api/auth/csrf') {
        return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      }
      return Response.json({
        outcome: 'conversation', mode: 'model_assisted', assistantMessage: '记住了 Alice 的聚会条件。',
        profile: { ...baseProfile, players: 5 }, clarification: null,
        sourceCount: 179737, candidatesEvaluated: 0, games: [],
      })
    }))
    const wrapper = await mountAgent({}, { sessionIdentity: 'alice' })

    await wrapper.get('textarea').setValue('Alice 想找五个人玩的聚会游戏')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('Alice 想找五个人玩的聚会游戏')

    await wrapper.setProps({ sessionIdentity: 'bob' })
    await flushPromises()
    expect(wrapper.text()).not.toContain('Alice 想找五个人玩的聚会游戏')
    expect(wrapper.text()).not.toContain('记住了 Alice')
    expect(wrapper.text()).not.toContain('5 人')

    await wrapper.setProps({ sessionIdentity: ' ALICE ' })
    await flushPromises()
    expect(wrapper.text()).toContain('Alice 想找五个人玩的聚会游戏')
    expect(wrapper.text()).toContain('记住了 Alice 的聚会条件')
    expect(wrapper.text()).toContain('5 人')
  })

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
        outcome: 'recommendations', mode: 'model_assisted', assistantMessage: '发行商资料展示了分步教学流程；但现有资料还不能证明实际互动感或节奏。',
        profile: { ...baseProfile, players: 4, maxMinutes: 90, maxWeight: 3.2 }, clarification: null,
        sourceCount: 179737, candidatesEvaluated: 1,
        userModel: { summary: '家庭局，重视参与感', hypotheses: [{ text: '可能不喜欢长时间等待', confidence: 'medium', basedOn: '希望大家都有参与感' }] },
        researchSources: [{ index: 1, title: 'Publisher guide', url: 'https://publisher.example/wingspan', domain: 'publisher.example' }],
        completedWork: ['lookup_bgg_games', 'research_game_fit', 'recommend_games'],
        games: [{
          game, matches: ['BGG 总榜第 34 名'], tradeoffs: ['需要留意卡牌文字量'],
          reasons: [
            { kind: 'bgg_fact', text: 'BGG 总榜第 34 名', sourceIndexes: [] },
            {
              kind: 'preference_inference',
              text: '你说“朋友聚会，想热闹但不要尴尬”；这款的 BGG 标签中有 Pattern Building。这是可核对的匹配线索，不能证明实际互动感或节奏。',
              sourceIndexes: [],
            },
            { kind: 'web_research', text: '发行商资料展示了分步教学流程', sourceIndexes: [1] },
          ],
        }],
      })
      if (body.message === '朋友聚会，想热闹但不要尴尬') return Response.json({
        outcome: 'needs_clarification', mode: 'model_assisted',
        assistantMessage: '听起来你更在意全桌参与感。这次大概几个人、能留多少时间？想到多少说多少就行。',
        profile: baseProfile, sourceCount: 179737, candidatesEvaluated: 0, games: [],
        clarification: { field: 'conversation', prompt: '这次大概几个人、能留多少时间？', options: [] },
        completedWork: [],
      })
      return Response.json({
        outcome: 'recommendations', mode: 'model_assisted', assistantMessage: '4 人、90 分钟内，我会先看《展翅翱翔》：BGG 标注的 1–5 人和约 70 分钟都落在这局范围里。',
        profile: { ...baseProfile, players: 4, maxMinutes: 90, maxWeight: 3.2 }, clarification: null,
        sourceCount: 179737, candidatesEvaluated: 20,
        completedWork: ['browse_bgg_catalog', 'lookup_bgg_games', 'recommend_games'],
        games: [{ game, matches: [], tradeoffs: [] }],
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
    expect(wrapper.text()).toContain('1–5 人 · 约 70 分钟 · 复杂度 2.5')
    expect(wrapper.text()).not.toContain('支持 4 人游玩')
    expect(wrapper.text()).not.toContain('条件核对')
    expect(wrapper.text()).not.toContain('为什么适合')
    expect(wrapper.text()).toContain('完整 BGG 目录')
    expect(wrapper.get('button[aria-label="查看完整资料：展翅翱翔"]').attributes('aria-label'))
      .toBe('查看完整资料：展翅翱翔')
    const firstRecommendationTurn = wrapper.get('[data-testid="assistant-recommendation-turn"]')
    expect(firstRecommendationTurn.text()).toContain('4 人、90 分钟内，我会先看《展翅翱翔》')
    expect(firstRecommendationTurn.text()).toContain('展翅翱翔')

    await wrapper.findAll('button').find(button => button.text() === '介绍一下')!.trigger('click')
    await flushPromises()
    expect(requests[2]).toMatchObject({ focusedBggId: 266192, message: '介绍一下《展翅翱翔》' })
    expect(wrapper.text()).toContain('发行商资料展示了分步教学流程')
    expect(wrapper.text()).toContain('不能证明实际互动感或节奏')
    expect(wrapper.text()).not.toContain('BGG 标签中有 Pattern Building')
    expect(wrapper.text()).not.toContain('需要留意卡牌文字量')
    expect(wrapper.get('a[href="https://publisher.example/wingspan"]').attributes('rel')).toContain('noopener')
    expect(wrapper.text()).toContain('目前记下的偏好')
    expect(wrapper.text()).toContain('本轮查找与核对')
    expect(wrapper.text()).toContain('理解你的条件')
    expect(wrapper.text()).toContain('体验资料查证')
    expect(wrapper.text()).not.toContain('本轮 Agent 轨迹')
    expect(wrapper.findAll('[data-testid="assistant-recommendation-turn"]')).toHaveLength(2)

    await wrapper.get('textarea').setValue('设计师 Reiner Knizia 还有哪些作品？')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(requests[3]).toMatchObject({
      message: '设计师 Reiner Knizia 还有哪些作品？',
      focusedBggId: null,
    })

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
        assistantMessage: '20 个已核对候选中没有同时满足全部硬条件的桌游。最小可行调整是只临时放宽时长；不会自动更改你的条件。',
        profile: { ...baseProfile, players: 8, maxMinutes: 30, maxWeight: 2.3 },
        clarification: {
          field: 'conversation',
          prompt: '要只临时放宽时长，保留人数和复杂度硬条件吗？',
          options: [{
            value: '只把“30 分钟以内”临时改为软偏好，其他硬条件不变',
            label: '仅放宽时长',
          }],
        },
        sourceCount: 179737, candidatesEvaluated: 20, games: [],
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue('8 个人，半小时，规则要简单')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const post = fetchMock.mock.calls.find(([input]) => String(input).includes('/recommendation-agent/stream'))
    expect(post?.[1]?.headers).toMatchObject({ 'X-CSRF-TOKEN': 'csrf' })
    expect(JSON.parse(String(post?.[1]?.body))).toMatchObject({ message: '8 个人，半小时，规则要简单' })
    expect(wrapper.text()).toContain('没有同时满足全部硬条件')
    expect(wrapper.text()).toContain('不会自动更改你的条件')
    expect(wrapper.text()).toContain('8 人')
    expect(wrapper.findAll('[data-testid="recommendation-game-card"]')).toHaveLength(0)

    const relaxationOptions = wrapper.findAll('button')
      .filter(button => button.text() === '仅放宽时长')
    expect(relaxationOptions).toHaveLength(1)
    await relaxationOptions[0]!.trigger('click')
    await flushPromises()

    const posts = fetchMock.mock.calls.filter(([input]) => String(input).includes('/recommendation-agent/stream'))
    expect(posts).toHaveLength(2)
    expect(JSON.parse(String(posts[1]?.[1]?.body))).toMatchObject({
      message: '只把“30 分钟以内”临时改为软偏好，其他硬条件不变',
    })
  })

  it('scrolls to the start of a newly returned recommendation turn instead of its card footer', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input) === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      return Response.json({
        outcome: 'recommendations', mode: 'model_assisted',
        assistantMessage: '我先核对参考游戏，再给出有具体共同机制的候选。',
        profile: baseProfile, clarification: null, sourceCount: 179737, candidatesEvaluated: 1,
        games: [{ game, matches: ['共享已核对的机制'], tradeoffs: [] }],
        completedWork: ['resolve_bgg_game'],
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

  it('keeps natural user language and supplies verified conversation games as context', async () => {
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
        completedWork: focused
          ? ['lookup_bgg_games', 'recommend_games']
          : ['browse_bgg_catalog', 'recommend_games'],
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

  it('keeps cards and player-facing verification progress attached to the assistant turn that produced them', async () => {
    let turn = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input) === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      turn += 1
      if (turn === 1) return Response.json({
        outcome: 'recommendations', mode: 'model_assisted', assistantMessage: '先给你一组有共同机制的候选。',
        profile: baseProfile, clarification: null, sourceCount: 179737, candidatesEvaluated: 3,
        games: [{ game, matches: ['共享已核对的机制'], tradeoffs: [] }],
        completedWork: ['inspect_candidate_titles', 'lookup_bgg_games', 'recommend_games'],
      })
      return Response.json({
        outcome: 'conversation', mode: 'model_assisted', assistantMessage: '明白，我们可以沿着刚才的方向继续聊。',
        profile: baseProfile, clarification: null, sourceCount: 179737, candidatesEvaluated: 0, games: [],
        completedWork: [],
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
    expect(recommendationTurns[0]!.text()).toContain('我的选择与取舍')
    expect(recommendationTurns[0]!.text()).toContain('可核对的 BGG 资料')
    expect(recommendationTurns[0]!.text()).toContain('完整目录按标题找候选')
    expect(recommendationTurns[0]!.find('[data-testid="recommendation-execution-audit"]').exists()).toBe(false)
    expect(recommendationTurns[0]!.text()).not.toContain('模型')
    expect(recommendationTurns[0]!.text()).not.toContain('判断 4 轮')
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
    expect(wrapper.get('[data-testid="recommendation-failed-assistant-reply"]').text())
      .toContain('我没有猜测或伪造候选')
    expect(wrapper.get('[role="alert"] button').text()).toBe('重试')
  })

  it('sends a detailed natural-language turn without an arbitrary browser-side character gate', async () => {
    const sentBodies: string[] = []
    const fetchMock = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      if (String(input) === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      sentBodies.push(String(init?.body))
      return Response.json({
        outcome: 'conversation', mode: 'model_assisted', assistantMessage: '我会结合这些细节一起判断。',
        profile: baseProfile, clarification: null, sourceCount: 0, candidatesEvaluated: 0, games: [],
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountAgent()
    const detailedTurn = '我们这次想把过往喜欢和不喜欢的体验都说清楚：'.repeat(20)

    await wrapper.get('textarea').setValue(detailedTurn)
    await flushPromises()

    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe(detailedTurn)
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(sentBodies).toHaveLength(1)
    expect(JSON.parse(sentBodies[0]!)).toMatchObject({ message: detailedTurn })
    expect(wrapper.text()).toContain('我会结合这些细节一起判断')
  })

  it('requests the response language from the current turn while leaving the UI locale unchanged', async () => {
    const requestedUrls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      requestedUrls.push(String(input))
      if (String(input) === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      return Response.json({
        outcome: 'conversation', mode: 'model_assisted', assistantMessage: 'Here is the direct comparison.',
        profile: baseProfile, clarification: null, sourceCount: 0, candidatesEvaluated: 0, games: [],
      })
    }))
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue('Which one works better for exactly three players?')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(requestedUrls).toContain('/api/v1/bgg/recommendation-agent/stream?locale=en')
    expect(wrapper.text()).toContain('Here is the direct comparison.')
    expect(wrapper.text()).toContain('今晚想玩什么？')
  })

  it('uses a Chinese current turn after English for the whole new recommendation surface', async () => {
    setLocale('en')
    const requestedUrls: string[] = []
    let turn = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      requestedUrls.push(path)
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      turn += 1
      if (turn === 1) {
        return Response.json({
          responseLocale: 'en', outcome: 'conversation', mode: 'model_assisted',
          assistantMessage: 'I kept the first turn in English.', profile: baseProfile,
          clarification: null, sourceCount: 0, candidatesEvaluated: 0, games: [],
        })
      }
      return Response.json({
        responseLocale: 'zh-CN', outcome: 'recommendations', mode: 'model_assisted',
        assistantMessage: '这轮按你当前的中文问题回答。', profile: baseProfile,
        clarification: null, sourceCount: 179737, candidatesEvaluated: 1,
        completedWork: ['inspect_candidate_titles', 'lookup_bgg_games', 'recommend_games'],
        games: [{
          game,
          matches: [],
          tradeoffs: [],
          fitClaims: [{
            subject: 'playerCount', strength: 'hard', relation: 'satisfied',
            text: '候选人数范围满足当前硬条件。',
          }],
        }],
      })
    }))
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue('Which option is better for exactly three players?')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('textarea').setValue('现在请用中文比较，并保留刚才的三人条件。')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(requestedUrls).toContain('/api/v1/bgg/recommendation-agent/stream?locale=en')
    expect(requestedUrls).toContain('/api/v1/bgg/recommendation-agent/stream?locale=zh-CN')
    const currentTurn = wrapper.get('[data-testid="assistant-recommendation-turn"]')
    expect(currentTurn.text()).toContain('这轮按你当前的中文问题回答。')
    expect(currentTurn.text()).toContain('本轮查找与核对')
    expect(currentTurn.text()).toContain('完整目录按标题找候选')
    expect(currentTurn.text()).toContain('从完整 BGG 目录中核对了 1 款候选。')
    expect(currentTurn.text()).toContain('换一批')
    expect(currentTurn.text()).toContain('1–5 人 · 约 70 分钟 · 复杂度 2.5')
    expect(currentTurn.text()).not.toContain('条件核对')
    expect(currentTurn.text()).not.toContain('Agent trajectory this turn')
    expect(currentTurn.text()).not.toContain('Find titles in the full catalog')
    expect(currentTurn.text()).not.toContain('Checked 1 candidates')
    expect(currentTurn.text()).not.toContain('Try another batch')
  })

  it('keeps a failed English turn and its retry action in the attempted turn language', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input) === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      return new Response(null, { status: 500 })
    }))
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue('Could you compare the remaining options?')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('That reply did not come through. Your preferences are still here.')
    expect(alert.get('button').text()).toBe('Retry')
    expect(alert.text()).not.toContain('刚才没有接上')
    expect(alert.text()).not.toContain('重试')
    expect(wrapper.get('[data-testid="recommendation-failed-assistant-reply"]').text())
      .toContain('I did not guess or invent candidates')
  })

  it('keeps the browser fallback recovery summary in the last successful response language', async () => {
    setLocale('en')
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/bgg/recommendation-agent/session') return new Response(null, { status: 204 })
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      return Response.json({
        responseLocale: 'en', outcome: 'recommendations', mode: 'model_assisted',
        assistantMessage: 'I verified this candidate and kept it available.', profile: baseProfile,
        clarification: null, sourceCount: 179737, candidatesEvaluated: 1,
        games: [{ game: { ...game, name: 'Wingspan', nameLocalized: false }, matches: [], tradeoffs: [] }],
      })
    }))
    const wrapper = await mountAgent({}, { sessionIdentity: 'alice' })
    await flushPromises()

    await wrapper.get('textarea').setValue('Which one works for three players?')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    wrapper.unmount()

    setLocale('zh-CN')
    const restored = await mountAgent({}, { sessionIdentity: 'alice' })
    await flushPromises()

    expect(restored.text()).toContain('Previously verified candidates: Wingspan. You can continue comparing them here.')
    expect(restored.text()).not.toContain('上次已核对候选：Wingspan')
  })

  it('shows a natural comparison without rendering the evidence table and keeps candidates in context', async () => {
    const requests: Array<Record<string, unknown>> = []
    let turn = 0
    const secondGame = { ...game, bggId: 77, name: 'Loom City', originalName: 'Loom City', nameLocalized: false }
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      if (String(input) === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      requests.push(JSON.parse(String(init?.body)) as Record<string, unknown>)
      turn += 1
      if (turn === 1) {
        return Response.json({
          outcome: 'conversation', mode: 'model_assisted',
          assistantMessage: 'For the tighter time window, I would pick Loom City: its listed range is 45–60 minutes, while Wingspan is 40–70. Actual table feel is still unknown for both, so that could reverse the choice once we have attributed play reports.',
          profile: baseProfile, clarification: null, sourceCount: 0, candidatesEvaluated: 2, games: [],
          comparison: {
            candidates: [
              { game: { ...game, name: 'Wingspan', originalName: 'Wingspan', nameLocalized: false }, fitClaims: [] },
              { game: secondGame, fitClaims: [] },
            ],
            axes: [
              {
                subject: 'durationMinutes', label: 'Listed duration', capability: 'structured_metadata',
                cells: [
                  { bggId: game.bggId, status: 'observed', observationKind: 'structured_metadata', value: '40–70 min' },
                  { bggId: secondGame.bggId, status: 'observed', observationKind: 'structured_metadata', value: '45–60 min' },
                ],
              },
              {
                subject: 'reportedExperience', label: 'Sourced player experience', capability: 'attributed_report',
                cells: [
                  { bggId: game.bggId, status: 'unknown', observationKind: '', value: '' },
                  { bggId: secondGame.bggId, status: 'unknown', observationKind: '', value: '' },
                ],
              },
            ],
          },
        })
      }
      return Response.json({
        outcome: 'conversation', mode: 'model_assisted', assistantMessage: 'I kept both candidates in context.',
        profile: baseProfile, clarification: null, sourceCount: 0, candidatesEvaluated: 2, games: [],
      })
    }))
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue('Compare their listed time and actual table feel; keep unknowns explicit.')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[data-testid="candidate-comparison"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('I would pick Loom City')
    expect(wrapper.text()).toContain('Wingspan is 40–70')
    expect(wrapper.text()).toContain('Actual table feel is still unknown for both')
    expect(wrapper.text()).toContain('今晚想玩什么？')

    await wrapper.get('textarea').setValue('Keep those two and tell me what evidence would resolve the unknown.')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const nextRequest = requests[1]
    if (!nextRequest) throw new Error('expected a second recommendation turn')
    expect(nextRequest.knownGames).toEqual(expect.arrayContaining([
      expect.objectContaining({ bggId: game.bggId, originalName: 'Wingspan' }),
      expect.objectContaining({ bggId: secondGame.bggId, originalName: 'Loom City' }),
    ]))
    expect(nextRequest.shownBggIds).toEqual(expect.arrayContaining([game.bggId, secondGame.bggId]))
    expect(wrapper.text()).toContain('I kept both candidates in context.')
  })

  it('confirms reset, preserves unsent text, and does not silently clear on locale changes', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input) === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      return Response.json({
        outcome: 'recommendations', mode: 'model_assisted', assistantMessage: '这款可以作为候选。',
        profile: { ...baseProfile, players: 4 }, clarification: null, sourceCount: 179737,
        candidatesEvaluated: 1, games: [{ game, matches: [], tradeoffs: [] }],
      })
    }))
    const wrapper = await mountAgent()
    await wrapper.get('textarea').setValue('想找自然主题的桌游')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('textarea').setValue('这句还没有发送')

    setLocale('en')
    await flushPromises()
    expect(wrapper.text()).toContain('这款可以作为候选。')
    expect(wrapper.text()).toContain('Wingspan')
    expect(wrapper.get('textarea').element).toHaveProperty('value', '这句还没有发送')

    const resetButton = wrapper.findAll('button').find(button => button.text() === 'Clear this conversation')!
    await resetButton.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('server-side Q&A history')
    expect(document.body.textContent).toContain('rulebook or guide work already running in the background will remain')
    expect(wrapper.text()).toContain('这款可以作为候选。')

    Array.from(document.body.querySelectorAll('button'))
      .find(button => button.textContent === 'Start over')!
      .click()
    await flushPromises()

    expect(wrapper.text()).not.toContain('这款可以作为候选。')
    expect(wrapper.text()).not.toContain('Wingspan')
    expect(wrapper.get('textarea').element).toHaveProperty('value', '这句还没有发送')
    expect(document.activeElement).toBe(wrapper.get('textarea').element)
    expect(wrapper.findAll('button').some(button => button.text() === 'Clear this conversation')).toBe(false)
  })

  it('keeps the visible conversation until the server confirms an owner-scoped reset', async () => {
    const conversationId = '2efc8376-883b-4ec0-b310-e1fc39a75473'
    let deleteAttempts = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path.includes('/recommendation-agent/sessions/')) {
        deleteAttempts += 1
        return new Response(null, { status: deleteAttempts === 1 ? 503 : 204 })
      }
      const body = JSON.parse(String(init?.body)) as Record<string, unknown>
      return Response.json({
        conversationId,
        revision: 1,
        clientTurnId: body.clientTurnId,
        replayed: false,
        responseLocale: 'zh-CN',
        outcome: 'conversation', mode: 'model_assisted', assistantMessage: '这段对话已经保存在服务器。',
        profile: { ...baseProfile, players: 4 }, clarification: null,
        sourceCount: 179737, candidatesEvaluated: 0, games: [],
      })
    }))
    const wrapper = await mountAgent()
    await wrapper.get('textarea').setValue('四个人继续聊')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    await wrapper.findAll('button').find(button => button.text() === '清空这次对话')!.trigger('click')
    await flushPromises()
    Array.from(document.body.querySelectorAll('button'))
      .find(button => button.textContent === '重新开始')!
      .click()
    await flushPromises()

    expect(deleteAttempts).toBe(1)
    expect(document.body.textContent).toContain('服务器没有确认删除')
    expect(wrapper.text()).toContain('四个人继续聊')
    expect(wrapper.text()).toContain('这段对话已经保存在服务器。')

    Array.from(document.body.querySelectorAll('button'))
      .find(button => button.textContent === '重新尝试')!
      .click()
    await flushPromises()

    expect(deleteAttempts).toBe(2)
    expect(wrapper.text()).not.toContain('四个人继续聊')
    expect(wrapper.text()).not.toContain('这段对话已经保存在服务器。')
  })

  it('starts a separate server conversation and keeps the previous chat available in history', async () => {
    const oldId = '2efc8376-883b-4ec0-b310-e1fc39a75473'
    const newId = '6b97841c-2a4d-49e9-a451-a58ee02f4583'
    const session = (conversationId: string, transcript: Array<{ role: 'user' | 'assistant'; text: string }>) => ({
      conversationId, revision: transcript.length ? 2 : 0, profile: baseProfile,
      transcript, knownGames: [], shownBggIds: [], processing: false, processingSince: null,
      latestResponse: null,
    })
    const oldSession = session(oldId, [
      { role: 'user', text: '想找适合四个人的合作游戏' },
      { role: 'assistant', text: '我们可以从合作强度聊起。' },
    ])
    const newSession = session(newId, [])
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/bgg/recommendation-agent/session') return Response.json(oldSession)
      if (path === '/api/v1/bgg/recommendation-agent/sessions' && init?.method === 'POST') {
        return Response.json(newSession)
      }
      if (path === '/api/v1/bgg/recommendation-agent/sessions') return Response.json([newSession, oldSession])
      if (path.endsWith(`/sessions/${oldId}`)) return Response.json(oldSession)
      return new Response(null, { status: 404 })
    }))
    const wrapper = await mountAgent({}, { sessionIdentity: 'alice' })
    await flushPromises()

    expect(wrapper.text()).toContain('想找适合四个人的合作游戏')
    await wrapper.findAll('button').find(button => button.text() === '建立新聊天')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('晚上好')
    expect(wrapper.text()).not.toContain('我们可以从合作强度聊起')

    await wrapper.findAll('button').find(button => button.text() === '聊天记录')!.trigger('click')
    await flushPromises()
    const previous = wrapper.findAll('button').find(button => button.text().includes('想找适合四个人'))!
    expect(previous.exists()).toBe(true)
    await previous.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('我们可以从合作强度聊起')
  })

  it('opens the rulebook handoff directly from a recommendation card', async () => {
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/bgg/games/266192/import') return Response.json({
        game: { id: 'game-1', name: '展翅翱翔' },
        edition: { id: 'edition-1', name: 'BGG 版本', language: 'und' },
        alreadyImported: false,
      })
      if (path.startsWith('/api/v1/documents/rulebook-candidates?')) return Response.json({
        configured: true,
        identity: {
          editionId: 'edition-1', gameName: '展翅翱翔', editionName: 'BGG 版本', language: 'und',
        },
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
    const selectButton = wrapper.findAll('button').find(button => button.text() === '选这款，找规则书')!
    selectButton.element.focus()
    await selectButton.trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/bgg/games/266192/import', expect.objectContaining({ method: 'POST' }))
    expect(document.body.textContent).toContain('已选《展翅翱翔》')
    expect(document.body.textContent).toContain('仍可粘贴公开 PDF 链接或上传自己的规则书')
    expect(document.body.querySelector('[data-testid="player-journey-surface"]')?.getAttribute('style'))
      .toContain('opacity: 1')

    const journeyClose = document.body.querySelector<HTMLButtonElement>('button[aria-label="关闭小窗"]')!
    expect(document.activeElement).toBe(journeyClose)
    journeyClose.click()
    await flushPromises()
    const chatWorkspace = wrapper.get('[data-testid="recommendation-chat-workspace"]')
    const continuation = wrapper.get('[data-testid="player-journey-continuation"]')
    expect(continuation.element.closest('[data-testid="recommendation-chat-workspace"]'))
      .toBe(chatWorkspace.element)
    const journeyDock = wrapper.get('[data-testid="player-journey-dock"]')
    expect(journeyDock.text()).toContain('展翅翱翔')
    expect(document.activeElement).toBe(selectButton.element)
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
      template: '<div data-testid="recommendation-answer-workspace" tabindex="-1"><div data-testid="answer-workspace-stub">{{ gameTitle }} · {{ documentVersionId }} · {{ planId }}</div></div>',
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
    expect(document.activeElement).toBe(wrapper.get('[data-testid="recommendation-answer-workspace"]').element)

    const recommendationButton = wrapper.findAll('[data-testid="agent-role-switcher"] button').find(button => button.text() === '继续推荐')!
    await recommendationButton.trigger('click')
    await flushPromises()

    expect(recommendationButton.attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[data-testid="recommendation-conversation"]').element.parentElement?.style.display).not.toBe('none')
    expect(wrapper.get('textarea').element.value).toBe('这个尚未发送的推荐条件要保留')
    expect(wrapper.text()).toContain('这款可以继续准备规则书')
  })

  it('opens a newly readable guide from the background dock without reopening progress first', async () => {
    const readyStatus = {
      projection: {
        state: 'ready', phase: 'LESSON_READABLE', progress: 94, canReadRulebook: true,
        canReadLesson: true, canAskQuestions: true, retryAction: null, errorCode: null,
      },
      game,
      imported: {
        game: { id: 'game-1', name: '展翅翱翔' },
        edition: { id: 'edition-1', name: 'BGG 版本' },
        alreadyImported: false,
      },
      importJob: { id: 'job-1', stage: 'COMPLETED', documentVersionId: 'document-1' },
      plan: { id: 'plan-1', documentVersionId: 'document-1', sections: [{ position: 1, title: '目标' }] },
      lesson: { id: 'lesson-1', status: 'DRAFT_READY', sections: [{ position: 1 }] },
    }
    const HandoffStub = defineComponent({
      name: 'RecommendationRulebookHandoff',
      emits: ['status', 'close'],
      setup(_props, { emit }) {
        return {
          publish: () => emit('status', readyStatus),
          close: () => emit('close'),
        }
      },
      template: '<div><button data-testid="publish-first-chapter" type="button" @click="publish">发布首章</button><button data-testid="close-journey" type="button" @click="close">关闭进度</button></div>',
    })
    const LessonDialogStub = defineComponent({
      name: 'RecommendationLessonDialog',
      props: { open: Boolean, planId: { type: String, required: true } },
      template: '<div v-if="open" data-testid="lesson-dialog-stub">{{ planId }}</div>',
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
      RecommendationLessonDialog: LessonDialogStub,
    })

    await wrapper.get('textarea').setValue('想找自然主题的桌游')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '选这款，找规则书')!.trigger('click')
    document.body.querySelector<HTMLButtonElement>('[data-testid="publish-first-chapter"]')!.click()
    await flushPromises()

    expect(document.body.querySelector('[data-testid="lesson-dialog-stub"]')).toBeNull()
    document.body.querySelector<HTMLButtonElement>('[data-testid="close-journey"]')!.click()
    await flushPromises()
    const dock = wrapper.get('[data-testid="player-journey-dock"]')
    expect(dock.text()).toContain('基础讲解可读')
    expect(dock.text()).toContain('打开讲解')
    const progressLink = wrapper.get('[data-testid="player-journey-progress-button"]')
    expect(progressLink.text()).toBe('查看详细进度')
    expect(wrapper.get('[data-testid="player-journey-all-work-link"]').attributes('href')).toBe('/work?started=plan-1')

    await progressLink.trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[data-testid="player-journey-backdrop"]')?.getAttribute('style')).not.toContain('display: none')
    document.body.querySelector<HTMLButtonElement>('[data-testid="close-journey"]')!.click()
    await flushPromises()

    await dock.trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[data-testid="player-journey-backdrop"]')?.getAttribute('style')).toContain('display: none')
    expect(document.body.querySelector('[data-testid="lesson-dialog-stub"]')?.textContent).toBe('plan-1')
  })

  it('shows real external-work phases without exposing decision-cycle internals', async () => {
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
    expect(wrapper.get('[data-testid="player-work-status"]').text()).toBe('正在回复')
    expect(wrapper.get('[role="status"]').text()).toContain('正在生成回复')

    streamController?.enqueue(encoder.encode('event: progress\ndata: {"stage":"searching_bgg_catalog","phase":"started","action":"browse_bgg_catalog","focus":{"kind":"catalog_mechanics","values":["牌组构筑"]},"elapsedMs":120}\n\n'))
    await flushPromises()
    expect(wrapper.get('[data-testid="player-work-status"]').text()).toBe('正在查找桌游')
    expect(wrapper.get('[role="status"]').text()).toContain('正在桌游目录里查找')
    expect(wrapper.get('[data-testid="recommendation-progress-steps"]').text()).toContain('开始：按“牌组构筑”机制筛选 BGG 候选')

    streamController?.enqueue(encoder.encode('event: progress\ndata: {"stage":"searching_bgg_catalog","phase":"completed","action":"browse_bgg_catalog","focus":{"kind":"catalog_mechanics","values":["牌组构筑"]},"elapsedMs":170,"observedCandidates":8,"verifiedCandidates":5,"hardRejectedCandidates":2}\n\n'))
    streamController?.enqueue(encoder.encode('event: progress\ndata: {"stage":"verifying_bgg_candidates","phase":"started","action":"lookup_bgg_games","elapsedMs":180,"observedCandidates":8,"verifiedCandidates":5,"hardRejectedCandidates":2}\n\n'))
    await flushPromises()
    const reportedSteps = wrapper.get('[data-testid="recommendation-progress-steps"]')
    expect(reportedSteps.text()).toContain('完成：按“牌组构筑”机制筛选 BGG 候选')
    expect(reportedSteps.text()).toContain('开始：读取候选的 BGG 人数、时长与机制详情')
    expect(reportedSteps.text()).not.toContain('第 2 轮')
    expect(reportedSteps.text()).not.toContain('BGG 2 次 / 公开资料 0 次')
    expect(reportedSteps.findAll('li')).toHaveLength(3)

    streamController?.enqueue(encoder.encode('event: progress\ndata: {"stage":"selecting_tools","phase":"started","action":"choose_next_action","elapsedMs":220}\n\n'))
    streamController?.enqueue(encoder.encode('event: progress\ndata: {"stage":"selecting_tools","phase":"completed","action":"choose_next_action","elapsedMs":230}\n\n'))
    await flushPromises()
    expect(reportedSteps.findAll('li')).toHaveLength(3)
    expect(reportedSteps.text()).not.toContain('判断下一步')
    expect(wrapper.get('[data-testid="player-work-status"]').text()).toBe('正在整理推荐')
    expect(wrapper.get('[role="status"]').text()).toContain('正在整理已经核对的结果')

    await vi.advanceTimersByTimeAsync(8_000)
    expect(wrapper.get('[role="status"]').text()).not.toContain('正在确认下一步该核对什么')
    expect(wrapper.get('[data-testid="recommendation-soft-budget"]').text())
      .toContain('目前还没有足以展示的新候选')
    expect(wrapper.get('[data-testid="recommendation-soft-budget"]').text())
      .toContain('还需核对目录事实与匹配取舍')

    streamController?.enqueue(encoder.encode(`event: result\ndata: ${JSON.stringify({
      outcome: 'no_match', mode: 'model_assisted', assistantMessage: '还需要再确认一个偏好。',
      profile: baseProfile, clarification: null, sourceCount: 179737, candidatesEvaluated: 20, games: [],
    })}\n\n`))
    streamController?.close()
    await flushPromises()
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="recommendation-soft-budget"]').exists()).toBe(false)
  })

  it('shows streamed conversational text without exposing routing or model internals', async () => {
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

    await wrapper.get('textarea').setValue('你好')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    streamController?.enqueue(encoder.encode('event: progress\ndata: {"stage":"understanding_request","phase":"completed","action":"understand_request","elapsedMs":2}\n\n'))
    streamController?.enqueue(encoder.encode('event: progress\ndata: {"stage":"composing_response","phase":"started","action":null,"elapsedMs":3}\n\n'))
    streamController?.enqueue(encoder.encode('event: answer_part\ndata: {"field":"message","text":"嗨，"}\n\n'))
    await flushPromises()

    expect(wrapper.get('[data-testid="player-work-status"]').text()).toBe('正在回复')
    expect(wrapper.get('[data-testid="recommendation-answer-preview"]').text()).toBe('嗨，')
    expect(wrapper.find('[data-testid="recommendation-progress-steps"]').exists()).toBe(false)
    expect(wrapper.get('[role="status"]').text()).not.toContain('第 1 轮')
    expect(wrapper.get('[role="status"]').text()).not.toContain('轻量模型')
    expect(wrapper.get('[role="status"]').text()).not.toContain('工具流程')

    streamController?.enqueue(encoder.encode('event: answer_part\ndata: {"field":"message","text":"嗨，今天想聊哪款桌游？"}\n\n'))
    streamController?.enqueue(encoder.encode(`event: result\ndata: ${JSON.stringify({
      outcome: 'conversation', mode: 'model_fast_path', assistantMessage: '嗨，今天想聊哪款桌游？',
      profile: baseProfile, clarification: null, sourceCount: 0, candidatesEvaluated: 0, games: [],
      completedWork: [],
    })}\n\n`))
    streamController?.close()
    await flushPromises()

    expect(wrapper.text()).toContain('嗨，今天想聊哪款桌游？')
    expect(wrapper.find('[data-testid="recommendation-execution-audit"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('STREAM_NATURAL_REPLY')
    expect(wrapper.text()).not.toContain('轻量模型')
  })
})
