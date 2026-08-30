import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import RulebookReaderView from './RulebookReaderView.vue'

function answerStreamResponse(result: unknown) {
  return new Response(`event: result\ndata: ${JSON.stringify(result)}\n\n`, {
    headers: { 'Content-Type': 'text/event-stream' },
  })
}

describe('RulebookReaderView', () => {
  afterEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
  })

  it('opens an owned normalized rulebook and answers without a generated lesson', async () => {
    const fetchMock = vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path.endsWith('/api/v1/document-versions/version-1/pages')) {
        return Response.json([
          { pageNumber: 1, text: '设置游戏', characterCount: 4 },
          { pageNumber: 2, text: '玩家先放置牌。', characterCount: 8 },
        ])
      }
      if (path.endsWith('/api/v1/documents')) {
        return Response.json([{
          document: { title: '测试规则书' },
          latestVersion: { id: 'version-1', status: 'READY', originalFilename: 'rules.pdf' },
        }])
      }
      if (path.endsWith('/api/auth/session')) return Response.json({ username: 'player' })
      if (path.endsWith('/api/auth/csrf')) return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path.endsWith('/api/v1/document-versions/version-1/answers/stream') && options?.method === 'POST') {
        return answerStreamResponse({
          answer: {
            language: 'zh-CN',
            status: 'ANSWERED', shortVerdict: '先放置玩家牌。', explanation: '这是开局的第一步。',
            citations: [answerCitation()], exceptions: [], confidence: 'HIGH', answerBasis: 'DIRECT_RULE', source: 'UPLOADED',
            clarification: null, recovery: null, warnings: [],
          },
          conversationTurnId: null,
          rulingReference: {
            citationIds: ['11111111-1111-4111-8111-111111111111'],
            confirmedRulingId: null,
            confirmedRulingVersion: null,
          },
        })
      }
      if (path.includes('/api/v1/assistant-runs/')) return new Response(null, { status: 404 })
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
        { path: '/rulebooks/:versionId', name: 'rulebook-reader', component: RulebookReaderView },
      ],
    })
    await router.push('/rulebooks/version-1')
    await router.isReady()
    const wrapper = mount(RulebookReaderView, {
      global: {
        plugins: [router],
        stubs: {
          AppShell: { template: '<div><slot /></div>' },
          BackgroundWorkCenter: true,
          LessonAnswerPanel: {
            props: ['question', 'answer'],
            emits: ['update:question', 'ask'],
            template: '<div><input id="reader-question" :value="question" @input="$emit(\'update:question\', $event.target.value)"><button id="ask" @click="$emit(\'ask\')">ask</button><p v-if="answer">{{ answer.shortVerdict }}</p></div>',
          },
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('测试规则书')
    expect(wrapper.text()).toContain('2 页')
    await wrapper.get('[data-testid="rulebook-page-loader"]').trigger('load')
    expect(wrapper.get('img[alt="规则书第 1 页"]').attributes('src'))
      .toBe('/api/v1/document-versions/version-1/pages/1/image')
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/teaching-plans'))).toBe(false)

    await wrapper.findAll('button').find(button => button.text() === '基于这本规则书答疑')!.trigger('click')
    await wrapper.get('#reader-question').setValue('开局先做什么？')
    await wrapper.get('#ask').trigger('click')
    await flushPromises()

    const answerRequest = fetchMock.mock.calls.find(([input, options]) =>
      String(input).endsWith('/api/v1/document-versions/version-1/answers/stream') && options?.method === 'POST')
    expect(JSON.parse(String(answerRequest?.[1]?.body))).toMatchObject({
      question: '开局先做什么？', language: 'zh-CN',
    })
    expect(wrapper.text()).toContain('先放置玩家牌。')
  })

  it('does not announce a requested page as displayed until that exact image has loaded', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.endsWith('/api/v1/document-versions/version-1/pages')) return Response.json([
        { pageNumber: 5, text: 'Current page', characterCount: 700 },
        { pageNumber: 6, text: 'Requested page', characterCount: 810 },
      ])
      if (path.endsWith('/api/v1/documents')) return Response.json([{
        document: { title: 'Opaque Rulebook' },
        latestVersion: { id: 'version-1', status: 'READY', originalFilename: 'rules.pdf' },
      }])
      if (path.endsWith('/api/auth/session')) return Response.json({ username: 'player' })
      return new Response(null, { status: 404 })
    }))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
        { path: '/rulebooks/:versionId', name: 'rulebook-reader', component: RulebookReaderView },
      ],
    })
    await router.push('/rulebooks/version-1')
    await router.isReady()
    const wrapper = mount(RulebookReaderView, {
      global: {
        plugins: [router],
        stubs: { AppShell: { template: '<div><slot /></div>' }, BackgroundWorkCenter: true },
      },
    })
    await flushPromises()

    expect(wrapper.get('[data-testid="rulebook-page-status"]').text()).toContain('正在加载第 5 页')
    await wrapper.get('[data-testid="rulebook-page-loader"]').trigger('load')
    expect(wrapper.get('[data-testid="rulebook-page-image"]').attributes('alt')).toBe('规则书第 5 页')

    await wrapper.get('button[data-page-number="6"]').trigger('click')
    expect(wrapper.get('[data-testid="rulebook-page-image"]').attributes('alt')).toBe('规则书第 5 页')
    expect(wrapper.get('[data-testid="rulebook-page-status"]').text()).toContain('正在加载第 6 页；第 5 页继续显示')
    expect(wrapper.get('button[data-page-number="6"]').attributes('aria-busy')).toBe('true')
    expect(wrapper.find('img[alt="规则书第 6 页"]').exists()).toBe(false)

    await wrapper.get('[data-testid="rulebook-page-loader"]').trigger('load')
    expect(wrapper.get('[data-testid="rulebook-page-image"]').attributes('alt')).toBe('规则书第 6 页')
    expect(wrapper.get('button[data-page-number="6"]').attributes('aria-current')).toBe('page')
    wrapper.unmount()
  })

  it('confirms before clearing only the current browser thread and preserves the draft', async () => {
    sessionStorage.setItem('rulepilot:lesson-answer-thread:v2:player:rulebook%3Aversion-1:version-1:zh-CN', JSON.stringify([{
      question: '上一轮什么时候结束？',
      learningIntent: null,
      answer: {
        language: 'zh-CN',
        status: 'ANSWERED', shortVerdict: '完成当前行动后结束。', explanation: '',
        citations: [answerCitation()], exceptions: [], confidence: 'HIGH', answerBasis: 'DIRECT_RULE', source: 'UPLOADED',
        clarification: null, recovery: null, warnings: [],
      },
    }]))
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.endsWith('/api/v1/document-versions/version-1/pages')) return Response.json([
        { pageNumber: 1, text: '设置游戏', characterCount: 4 },
      ])
      if (path.endsWith('/api/v1/documents')) return Response.json([{
        document: { title: '测试规则书' },
        latestVersion: { id: 'version-1', status: 'READY', originalFilename: 'rules.pdf' },
      }])
      if (path.endsWith('/api/auth/session')) return Response.json({ username: 'player' })
      return new Response(null, { status: 404 })
    }))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
        { path: '/rulebooks/:versionId', name: 'rulebook-reader', component: RulebookReaderView },
      ],
    })
    await router.push('/rulebooks/version-1')
    await router.isReady()
    const wrapper = mount(RulebookReaderView, {
      attachTo: document.body,
      global: {
        plugins: [router],
        stubs: { AppShell: { template: '<div><slot /></div>' }, BackgroundWorkCenter: true },
      },
    })
    await flushPromises()

    await wrapper.findAll('button').find(button => button.text() === '基于这本规则书答疑')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('上一轮什么时候结束？')
    await wrapper.get('#lesson-question').setValue('尚未发送的问题')
    await wrapper.findAll('button').find(button => button.text() === '清空本次答疑')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('上一轮什么时候结束？')
    expect(document.body.textContent).toContain('当前浏览器会话中的 1 条问答会被移除')
    Array.from(document.body.querySelectorAll('button')).find(button => button.textContent === '清空答疑')!.click()
    await flushPromises()

    expect(wrapper.text()).not.toContain('上一轮什么时候结束？')
    expect((wrapper.get('#lesson-question').element as HTMLTextAreaElement).value).toBe('尚未发送的问题')
    expect(document.activeElement).toBe(wrapper.get('#lesson-question').element)
    expect(sessionStorage.length).toBe(0)
    wrapper.unmount()
  })
})

function answerCitation() {
  return { heading: '设置顺序', excerpt: '玩家先放置牌。', pageFrom: 2, pageTo: 2 }
}
