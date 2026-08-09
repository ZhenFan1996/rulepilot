import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import RulebookReaderView from './RulebookReaderView.vue'

describe('RulebookReaderView', () => {
  afterEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    vi.unstubAllGlobals()
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
      if (path.endsWith('/api/v1/document-versions/version-1/answers') && options?.method === 'POST') {
        return Response.json({
          assistantRunId: 'answer-run-1',
          answer: {
            status: 'ANSWERED', shortVerdict: '先放置玩家牌。', explanation: '这是开局的第一步。',
            citations: [], exceptions: [], confidence: 'HIGH', answerBasis: 'DIRECT_RULE', official: false,
            confirmedRulingId: null, confirmedRulingVersion: null, clarification: null, warnings: [],
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
        { path: '/teach', name: 'teach', component: { template: '<div />' } },
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
    expect(wrapper.get('img[alt="规则书第 1 页"]').attributes('src'))
      .toBe('/api/v1/document-versions/version-1/pages/1/image')
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/teaching-plans'))).toBe(false)

    await wrapper.findAll('button').find(button => button.text() === '基于这本规则书答疑')!.trigger('click')
    await wrapper.get('#reader-question').setValue('开局先做什么？')
    await wrapper.get('#ask').trigger('click')
    await flushPromises()

    const answerRequest = fetchMock.mock.calls.find(([input, options]) =>
      String(input).endsWith('/api/v1/document-versions/version-1/answers') && options?.method === 'POST')
    expect(JSON.parse(String(answerRequest?.[1]?.body))).toMatchObject({
      question: '开局先做什么？', language: 'zh-CN',
    })
    expect(wrapper.text()).toContain('先放置玩家牌。')
  })
})
