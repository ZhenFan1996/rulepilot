import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import TableModeView from './TableModeView.vue'

describe('TableModeView answer status', () => {
  afterEach(() => {
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('uses the shared checking status and explains low confidence in player-facing language', async () => {
    let finishAnswer: ((response: Response) => void) | undefined
    const fetchMock = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json({
        id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Opaque Game',
      })
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/game-sessions' && init?.method === 'POST') return Response.json({ id: 'session-1' })
      if (path.includes('/answers/conversation?')) return Response.json([])
      if (path.includes('/answers/stream') && init?.method === 'POST') {
        return await new Promise<Response>((resolve) => { finishAnswer = resolve })
      }
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/table/:planId', name: 'table-mode', component: TableModeView },
        { path: '/lesson/:planId', name: 'lesson', component: { template: '<div />' } },
      ],
    })
    await router.push('/table/plan-1')
    await router.isReady()
    const wrapper = mount(TableModeView, {
      global: { plugins: [router], stubs: { AppShell: { template: '<div><slot /></div>' } } },
    })
    await flushPromises()

    await wrapper.get('#table-question').setValue('这个效果什么时候结算？')
    await wrapper.get('form').trigger('submit')
    await wrapper.vm.$nextTick()

    const status = wrapper.get('[data-testid="player-work-status"]')
    expect(status.text()).toBe('正在核对回答')
    expect(status.attributes('data-player-work-terminality')).toBe('active')
    expect(wrapper.text()).toContain('正在提交问题并建立答疑任务')

    await vi.waitFor(() => expect(fetchMock.mock.calls.map(([input]) => String(input)).join('\n'))
      .toContain('/api/v1/document-versions/version-1/answers/stream'))
    await vi.waitFor(() => expect(finishAnswer).toBeTypeOf('function'))
    finishAnswer!(Response.json({
      conversationTurnId: 'turn-1',
      answer: {
        status: 'ANSWERED_WITH_WARNING', shortVerdict: '先结算这个效果。', explanation: '引用的条目要求先结算。',
        citations: [{ heading: '结算顺序', excerpt: '先结算当前效果，再继续行动。', pageFrom: 8, pageTo: 8 }],
        exceptions: [], confidence: 'LOW', answerBasis: 'DIRECT_RULE', language: 'zh-CN', source: 'UPLOADED',
        clarification: null, recovery: null, warnings: [{ type: 'LOW_CONFIDENCE' }],
        timingResolutions: [{
          timingContext: '两个效果同时触发',
          resolutionOrder: '先处理当前玩家的效果，再按顺时针继续',
          orderSource: '当前玩家选择规则',
          basis: 'CURRENT_PLAYER_CHOOSES',
        }],
        conceptComparisons: [{
          leftConcept: '触发', leftDefinition: '条件满足时进入待结算状态',
          rightConcept: '结算', rightDefinition: '实际执行效果',
          commonGround: '都属于同一次效果处理', keyDifference: '触发先发生，结算随后执行',
          practicalBoundary: '触发时只登记效果，结算时才改变桌面状态', basis: 'ACTION_WINDOW',
        }],
      },
    }))
    await flushPromises()

    expect(wrapper.text()).toContain('这条结论的依据还不够稳妥，请结合规则原文确认。')
    expect(wrapper.get('[data-testid="player-facing-structured-answer-details"]').text()).toContain('先处理当前玩家的效果，再按顺时针继续')
    expect(wrapper.get('[data-testid="player-facing-structured-answer-details"]').text()).toContain('都属于同一次效果处理')
    expect(wrapper.text()).not.toContain('模型')
    wrapper.unmount()
  })
})
