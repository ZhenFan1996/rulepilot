import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { setLocale } from '@/lib/locale'
import LessonQuestionsView from './LessonQuestionsView.vue'

describe('LessonQuestionsView', () => {
  beforeEach(() => {
    localStorage.clear()
    setLocale('zh-CN')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    setLocale('zh-CN')
  })

  it('ignores legacy chapter scope and lets the Agent retrieve across the whole rulebook', async () => {
    let answerRequest: Record<string, unknown> | null = null
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(planFixture('plan-1', '星际探索'))
      if (path.endsWith('/illustrated-lessons/latest')) return Response.json(lessonFixture())
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path.endsWith('/answers') && init?.method === 'POST') {
        answerRequest = JSON.parse(String(init.body)) as Record<string, unknown>
        return new Response(null, { status: 503 })
      }
      return new Response(null, { status: 404 })
    }))
    const { wrapper } = await mountQuestions('/lesson/plan-1/questions?section=2')

    expect(wrapper.text()).toContain('向《星际探索》规则书提问')
    expect(wrapper.text()).toContain('独立答疑')
    expect(wrapper.text()).not.toContain('优先参考')
    expect(wrapper.text()).not.toContain('第 2 章 · 结算分数')
    expect(wrapper.get('a[href="/lesson/plan-1"]').text()).toContain('返回讲解')

    await wrapper.get('#lesson-question').setValue('这一步何时结算？')
    await wrapper.get('#lesson-question-panel form').trigger('submit')
    await flushPromises()

    expect(answerRequest).toMatchObject({
      question: '这一步何时结算？',
      learningIntent: null,
      language: 'zh-CN',
    })
    expect(answerRequest).not.toHaveProperty('currentLessonSection')
    wrapper.unmount()
  })

  it('drops a pending answer when the player opens another guide', async () => {
    let resolveAnswer: ((response: Response) => void) | undefined
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Promise.resolve(Response.json(planFixture('plan-1', '第一份规则')))
      if (path === '/api/v1/teaching-plans/plan-2') return Promise.resolve(Response.json(planFixture('plan-2', '第二份规则')))
      if (path.includes('/plan-1/illustrated-lessons/latest')) return Promise.resolve(Response.json(lessonFixture()))
      if (path.includes('/plan-2/illustrated-lessons/latest')) return Promise.resolve(Response.json(lessonFixture('第二份准备', '第二份结算')))
      if (path === '/api/auth/csrf') return Promise.resolve(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
      if (path.endsWith('/answers') && init?.method === 'POST') {
        return new Promise<Response>((resolve) => { resolveAnswer = resolve })
      }
      return Promise.resolve(new Response(null, { status: 404 }))
    }))
    const { wrapper, router } = await mountQuestions('/lesson/plan-1/questions')

    await wrapper.get('#lesson-question').setValue('第一份规则的问题')
    await wrapper.get('#lesson-question-panel form').trigger('submit')
    await flushPromises()
    await router.push('/lesson/plan-2/questions')
    await flushPromises()
    resolveAnswer!(Response.json({
      assistantRunId: 'answer-run-1',
      answer: {
        status: 'ANSWERED', shortVerdict: '第一份规则的旧答案', explanation: '不应出现。', citations: [], exceptions: [],
        confidence: 'HIGH', official: false, confirmedRulingId: null, confirmedRulingVersion: null,
        clarification: null, warnings: [],
      },
    }))
    await flushPromises()

    expect(wrapper.text()).toContain('第二份规则')
    expect(wrapper.text()).not.toContain('第一份规则的旧答案')
    wrapper.unmount()
  })

  it('keeps the complete Q&A workspace localized in English', async () => {
    setLocale('en')
    let answerRequest: Record<string, unknown> | null = null
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(planFixture('plan-1', 'Deep Space'))
      if (path.endsWith('/illustrated-lessons/latest')) return Response.json(lessonFixture('Setup', 'Scoring'))
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path.endsWith('/answers') && init?.method === 'POST') {
        answerRequest = JSON.parse(String(init.body)) as Record<string, unknown>
        return new Response(null, { status: 503 })
      }
      return new Response(null, { status: 404 })
    }))
    const { wrapper } = await mountQuestions('/lesson/plan-1/questions')

    expect(wrapper.text()).toContain('Ask the Deep Space rulebook')
    expect(wrapper.text()).toContain('Focused Q&A')
    expect(wrapper.text()).toContain('Ask the rulebook')
    expect(wrapper.text()).toContain('Read a card from a photo')
    expect(wrapper.text()).not.toMatch(/[\u3400-\u9fff]/)
    await wrapper.findAll('button').find((button) => button.text() === 'Walk through an example')!.trigger('click')
    await flushPromises()
    expect(answerRequest).toMatchObject({
      question: 'Using the rulebook, walk through one concrete, legal table example.',
      learningIntent: 'EXAMPLE',
      language: 'en',
    })
    expect(answerRequest).not.toHaveProperty('currentLessonSection')
    wrapper.unmount()
  })
})

async function mountQuestions(path: string) {
  const Empty = { template: '<div />' }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/lesson/:planId', name: 'lesson', component: Empty },
      { path: '/lesson/:planId/questions', name: 'lesson-questions', component: LessonQuestionsView },
      { path: '/lessons', name: 'lessons', component: Empty },
      { path: '/login', name: 'login', component: Empty },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(LessonQuestionsView, {
    global: {
      plugins: [router],
      stubs: {
        AppShell: { template: '<div><slot /></div>' },
        CardOcrCapture: true,
        VoiceQuestionCapture: true,
      },
    },
  })
  await flushPromises()
  return { wrapper, router }
}

function planFixture(id: string, gameTitle: string) {
  return { id, documentVersionId: `version-${id}`, gameTitle }
}

function lessonFixture(first = '准备游戏', second = '结算分数') {
  return {
    id: 'lesson-1',
    sections: [
      { position: 1, topicKey: 'setup', coverageTags: ['start'], title: first },
      { position: 2, topicKey: 'scoring', coverageTags: ['score', 'end'], title: second },
    ],
  }
}
