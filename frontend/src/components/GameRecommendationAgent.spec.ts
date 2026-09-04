import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { setLocale } from '@/lib/locale'
import type {
  RecommendationAgentResponse,
  RecommendationGame,
  RecommendationProfile,
} from './gameRecommendationTypes'
import GameRecommendationAgent from './GameRecommendationAgent.vue'

const profile = {
  type: 'all',
  interaction: 'any',
  playerCount: null,
  durationMinutes: null,
  complexity: null,
} satisfies RecommendationProfile

const game = {
  bggId: 266192,
  name: '展翅翱翔',
  originalName: 'Wingspan',
  nameLocalized: true,
  publicationYear: 2019,
  overallRank: 34,
  geekRating: 7.79,
  averageRating: 8.09,
  usersRated: 102030,
  thumbnailUrl: 'https://example.test/wingspan.jpg',
  minPlayers: 1,
  maxPlayers: 5,
  playingTimeMinutes: 70,
  averageWeight: 2.5,
  categories: ['动物'],
  mechanics: ['卡牌轮抽'],
  bggUrl: 'https://boardgamegeek.com/boardgame/266192',
} satisfies RecommendationGame

function conversationResult(assistantMessage: string): RecommendationAgentResponse {
  return {
    outcome: 'conversation',
    responseLocale: 'zh-CN',
    assistantMessage,
    profile,
    clarification: null,
    sourceCount: 0,
    candidatesEvaluated: 0,
    games: [],
  }
}

function recommendationStreamResult(result: RecommendationAgentResponse) {
  return new Response(`event: result\ndata: ${JSON.stringify(result)}\n\n`, {
    headers: { 'Content-Type': 'text/event-stream' },
  })
}

describe('GameRecommendationAgent', () => {
  const mountedAgents: Array<ReturnType<typeof mount>> = []

  beforeEach(() => {
    localStorage.setItem('rulepilot:locale', 'zh-CN')
    sessionStorage.clear()
    setLocale('zh-CN')
  })

  afterEach(() => {
    for (const wrapper of mountedAgents.splice(0)) wrapper.unmount()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
    setLocale('zh-CN')
  })

  async function mountAgent() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/login', name: 'login', component: { template: '<div />' } },
        { path: '/register', name: 'register', component: { template: '<div />' } },
        { path: '/settings/models', name: 'model-settings', component: { template: '<div />' } },
        { path: '/discover/:bggId', name: 'game-discovery', component: { template: '<div />' } },
        { path: '/teach', name: 'teach', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(GameRecommendationAgent, {
      attachTo: document.body,
      global: { plugins: [router] },
    })
    mountedAgents.push(wrapper)
    return wrapper
  }

  it('shows the assistant natural-language reply without rewriting it', async () => {
    const assistantMessage = '先不用补成表格；你说的安静、合作和短局，我会一起带进下一轮。'
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) =>
      String(input) === '/api/auth/csrf'
        ? Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
        : recommendationStreamResult(conversationResult(assistantMessage))))
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue('两个人，想安静合作，最好半小时左右')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[data-testid="assistant-conversation-turn"]').text())
      .toBe(assistantMessage)
  })

  it('shows recommendation cards, claim-scoped tradeoffs, and attributed evidence', async () => {
    const assistantMessage = '我会先选《展翅翱翔》；人数与时长合适，但第一次玩要预留讲解时间。'
    const result: RecommendationAgentResponse = {
      outcome: 'recommendations',
      responseLocale: 'zh-CN',
      assistantMessage,
      profile,
      clarification: null,
      sourceCount: 1,
      candidatesEvaluated: 1,
      researchSources: [{
        index: 7,
        title: '发行商游戏指南',
        url: 'https://publisher.example/wingspan-guide',
        domain: 'publisher.example',
      }],
      games: [{
        game,
        fitClaims: [],
        replyParts: [
          {
            role: 'verified_fact',
            claimType: 'publisher_description',
            subject: 'teaching',
            text: '发行商指南提供了分步教学建议。',
            sourceIndexes: [7],
          },
          {
            role: 'tradeoff',
            claimType: 'structured_fact',
            subject: 'complexity',
            text: '第一次玩要照顾卡牌文字量。',
            sourceIndexes: [],
          },
        ],
      }],
    }
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) =>
      String(input) === '/api/auth/csrf'
        ? Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
        : recommendationStreamResult(result)))
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue('四个人，想玩自然主题的中等策略游戏')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const turn = wrapper.get('[data-testid="assistant-recommendation-turn"]')
    expect(turn.get('[data-testid="assistant-recommendation-message"]').text())
      .toBe(assistantMessage)
    const card = turn.get('[data-testid="recommendation-game-card"]')
    expect(card.get('[data-testid="recommendation-game-title"]').text()).toBe('展翅翱翔')
    expect(card.get('[data-testid="recommendation-game-original-title"]').text()).toBe('Wingspan')
    expect(card.get('[data-role="verified_fact"] dd').text())
      .toBe('发行商指南提供了分步教学建议。')
    expect(card.get('[data-role="tradeoff"] dd').text())
      .toBe('第一次玩要照顾卡牌文字量。')
    expect(card.get('button[aria-label="查看完整资料：展翅翱翔"]')).toBeDefined()

    const evidence = turn.get('[data-testid="recommendation-research-sources"] a')
    expect(evidence.text()).toContain('发行商游戏指南')
    expect(evidence.attributes('href')).toBe('https://publisher.example/wingspan-guide')
    expect(evidence.attributes('rel')).toContain('noopener')
  })

  it('shows validated recommendation cards as they arrive before the terminal result', async () => {
    const encoder = new TextEncoder()
    let streamController: ReadableStreamDefaultController<Uint8Array> | null = null
    const entry = { game, fitClaims: [], replyParts: [] }
    const result: RecommendationAgentResponse = {
      outcome: 'recommendations', responseLocale: 'zh-CN', assistantMessage: '先看这款。',
      profile, clarification: null, sourceCount: 1, candidatesEvaluated: 1, games: [entry],
    }
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input) === '/api/auth/csrf') {
        return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      }
      return new Response(new ReadableStream<Uint8Array>({
        start(controller) { streamController = controller },
      }), { headers: { 'Content-Type': 'text/event-stream' } })
    }))
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue('推荐一款自然主题游戏')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(streamController).not.toBeNull()

    streamController!.enqueue(encoder.encode(
      `event: recommendation_part\ndata: ${JSON.stringify({ game: entry, researchSources: [] })}\n\n`,
    ))
    await flushPromises()

    const pending = wrapper.get('[data-testid="pending-recommendation-parts"]')
    expect(pending.text()).toContain('已核对的选择')
    expect(pending.get('[data-testid="recommendation-game-title"]').text()).toBe('展翅翱翔')
    expect(wrapper.find('[data-testid="assistant-recommendation-turn"]').exists()).toBe(false)

    streamController!.enqueue(encoder.encode(`event: result\ndata: ${JSON.stringify(result)}\n\n`))
    streamController!.close()
    await flushPromises()

    expect(wrapper.find('[data-testid="pending-recommendation-parts"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="assistant-recommendation-turn"]').text()).toContain('先看这款。')
  })

  it('shows the first model decision brief while the selected action is still running', async () => {
    const encoder = new TextEncoder()
    let streamController: ReadableStreamDefaultController<Uint8Array> | null = null
    const result: RecommendationAgentResponse = {
      outcome: 'conversation', responseLocale: 'zh-CN', assistantMessage: '目录核对完成。',
      profile, clarification: null, sourceCount: 1, candidatesEvaluated: 0, games: [],
    }
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input) === '/api/auth/csrf') {
        return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      }
      return new Response(new ReadableStream<Uint8Array>({
        start(controller) { streamController = controller },
      }), { headers: { 'Content-Type': 'text/event-stream' } })
    }))
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue('四个人想玩一小时内的轻松互动游戏')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(streamController).not.toBeNull()

    const brief = '**我对这次请求的判断**\n\n为四个人找一小时内的轻松互动游戏。\n\n'
      + '**我准备优先走的方向**\n\n先核对人数和时长，再检查互动体验。'
    streamController!.enqueue(encoder.encode(
      `event: answer_part\ndata: ${JSON.stringify({ text: brief })}\n\n`,
    ))
    await flushPromises()

    const pending = wrapper.get('[data-testid="pending-assistant-preview"]')
    expect(pending.text()).toContain('我对这次请求的判断')
    expect(pending.text()).toContain('先核对人数和时长，再检查互动体验')
    expect(wrapper.find('[data-testid="assistant-conversation-turn"]').exists()).toBe(false)

    streamController!.enqueue(encoder.encode(`event: result\ndata: ${JSON.stringify(result)}\n\n`))
    streamController!.close()
    await flushPromises()

    expect(wrapper.find('[data-testid="pending-assistant-preview"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="assistant-conversation-turn"]').text()).toContain('目录核对完成')
  })

  it('keeps a failed request visible and lets the player retry it', async () => {
    const request = '想找四个人玩的合作游戏'
    const recoveredReply = '这次已经接上，我会从四人合作游戏继续找。'
    let recommendationAttempts = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input) === '/api/auth/csrf') {
        return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      }
      recommendationAttempts += 1
      return recommendationAttempts === 1
        ? new Response(null, { status: 503 })
        : recommendationStreamResult(conversationResult(recoveredReply))
    }))
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue(request)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain(request)
    expect(wrapper.get('[data-testid="recommendation-failed-assistant-reply"]').text())
      .toContain('没有猜测或伪造候选')
    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('你写下的条件还在')
    const retryButton = alert.get('button')
    expect(retryButton.text()).toBe('重试')

    await retryButton.trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="assistant-conversation-turn"]').text())
      .toBe(recoveredReply)
  })

  it('moves focus into the reset dialog and restores it when Escape cancels', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) =>
      String(input) === '/api/auth/csrf'
        ? Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
        : recommendationStreamResult(conversationResult('这段对话现在可以重置。'))))
    const wrapper = await mountAgent()

    await wrapper.get('textarea').setValue('先建立一段对话')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const resetButton = wrapper.findAll('button')
      .find(button => button.text() === '清空这次对话')
    if (!resetButton) throw new Error('expected the reset action')
    resetButton.element.focus()
    await resetButton.trigger('click')
    await flushPromises()

    const dialog = document.body.querySelector<HTMLElement>('[role="alertdialog"]')
    if (!dialog) throw new Error('expected the reset dialog')
    const cancelButton = [...dialog.querySelectorAll<HTMLButtonElement>('button')]
      .find(button => button.textContent === '继续当前对话')
    expect(document.activeElement).toBe(cancelButton)

    document.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'Escape' }))
    await flushPromises()

    expect(document.body.querySelector('[role="alertdialog"]')).toBeNull()
    expect(document.activeElement).toBe(resetButton.element)
  })
})
