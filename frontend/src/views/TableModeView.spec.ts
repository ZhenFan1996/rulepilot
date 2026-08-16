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
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json({
        id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Opaque Game',
      })
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/game-sessions' && init?.method === 'POST') return Response.json({ id: 'session-1' })
      if (path.includes('/answers/conversation?')) return Response.json([])
      if (path === '/api/v1/document-versions/version-1/answers' && init?.method === 'POST') {
        return await new Promise<Response>((resolve) => { finishAnswer = resolve })
      }
      return new Response(null, { status: 404 })
    }))
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
    expect(wrapper.text()).toContain('正在理解问题')

    await vi.waitFor(() => expect(finishAnswer).toBeTypeOf('function'))
    finishAnswer!(Response.json({
      conversationTurnId: 'turn-1',
      answer: {
        status: 'ANSWERED_WITH_WARNING', shortVerdict: '先结算这个效果。', explanation: '引用的条目要求先结算。', citations: [], exceptions: [],
        confidence: 'LOW', clarification: null, warnings: [{ type: 'LOW_CONFIDENCE' }],
      },
    }))
    await flushPromises()

    expect(wrapper.text()).toContain('这条结论的依据还不够稳妥，请结合规则原文确认。')
    expect(wrapper.text()).not.toContain('模型')
    wrapper.unmount()
  })
})
