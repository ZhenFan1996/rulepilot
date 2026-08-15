import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { setLocale } from '@/lib/locale'
import { rememberLessonAnswerThread } from '@/lib/lessonAnswerThread'
import LessonQuestionsView from './LessonQuestionsView.vue'

describe('LessonQuestionsView', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    setLocale('zh-CN')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    setLocale('zh-CN')
  })

  it('ignores legacy chapter scope and lets the Agent retrieve across the whole rulebook', async () => {
    let answerRequest: Record<string, unknown> | null = null
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(planFixture('plan-1', '星际探索'))
      if (path === '/api/v1/teaching-plans/plan-1/catalog-presentation') {
        return Response.json(catalogPresentationFixture('目录桌游'))
      }
      if (path.endsWith('/illustrated-lessons/latest')) return Response.json(lessonFixture())
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path.endsWith('/answers') && init?.method === 'POST') {
        answerRequest = JSON.parse(String(init.body)) as Record<string, unknown>
        return new Response(null, { status: 503 })
      }
      return new Response(null, { status: 404 })
    }))
    const { wrapper } = await mountQuestions('/lesson/plan-1/questions?section=2')

    expect(wrapper.text()).toContain('向《目录桌游》规则书提问')
    expect(wrapper.text()).toContain('星际探索')
    expect(wrapper.text()).not.toContain('1–5 人')
    expect(wrapper.text()).toContain('桌游资料由 BoardGameGeek 提供')
    expect(wrapper.text()).toContain('独立答疑')
    expect(wrapper.findAll('h2').some((heading) => heading.text() === '问规则书')).toBe(false)
    expect(wrapper.text()).not.toContain('优先参考')
    expect(wrapper.text()).not.toContain('第 2 章 · 结算分数')
    expect(wrapper.get('a[href="/lesson/plan-1"]').text()).toContain('讲解')
    expect(wrapper.get('[data-testid="lesson-questions-entry"]').attributes('aria-current')).toBe('page')

    await wrapper.get('#lesson-question').setValue('这一步何时结算？')
    await wrapper.get('#lesson-question-panel form').trigger('submit')
    await flushPromises()

    expect(answerRequest).toMatchObject({
      question: '这一步何时结算？',
      learningIntent: null,
      language: 'zh-CN',
    })
    expect(answerRequest).not.toHaveProperty('currentLessonSection')
    expect(answerRequest).not.toHaveProperty('catalogPresentation')
    expect(answerRequest).not.toHaveProperty('bggId')
    expect(JSON.stringify(answerRequest)).not.toContain('目录桌游')
    wrapper.unmount()
  })

  it('drops a pending answer when the player opens another guide', async () => {
    let resolveAnswer: ((response: Response) => void) | undefined
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Promise.resolve(Response.json(planFixture('plan-1', '第一份规则')))
      if (path === '/api/v1/teaching-plans/plan-2') return Promise.resolve(Response.json(planFixture('plan-2', '第二份规则')))
      if (path.includes('/plan-1/illustrated-lessons/latest')) return Promise.resolve(Response.json(lessonFixture()))
      if (path.includes('/plan-2/illustrated-lessons/latest')) return Promise.resolve(Response.json(lessonFixture('第二份准备', '第二份结算', 'plan-2')))
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
      answer: {
        language: 'zh-CN',
        status: 'ANSWERED', shortVerdict: '第一份规则的旧答案', explanation: '不应出现。',
        citations: [answerCitation()], exceptions: [], confidence: 'HIGH', answerBasis: 'DIRECT_RULE',
        source: 'UPLOADED', clarification: null, recovery: null, warnings: [],
      },
      conversationTurnId: null,
      rulingReference: answerRulingReference(),
    }))
    await flushPromises()

    expect(wrapper.text()).toContain('第二份规则')
    expect(wrapper.text()).not.toContain('第一份规则的旧答案')
    wrapper.unmount()
  })

  it('sends a natural re-explanation as conversation context for the Answer Agent to interpret', async () => {
    const answerRequests: Array<Record<string, unknown>> = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(planFixture('plan-1', '星际探索'))
      if (path.endsWith('/illustrated-lessons/latest')) return Response.json(lessonFixture())
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path.endsWith('/answers') && init?.method === 'POST') {
        answerRequests.push(JSON.parse(String(init.body)) as Record<string, unknown>)
        return Response.json({
          answer: {
            language: 'zh-CN',
            status: 'ANSWERED', shortVerdict: '完成放置后结算。', explanation: '规则书给出了这个顺序。',
            citations: [answerCitation()], exceptions: [], confidence: 'HIGH', answerBasis: 'DIRECT_RULE', source: 'UPLOADED',
            clarification: null, recovery: null, warnings: [],
          },
          conversationTurnId: null,
          rulingReference: answerRulingReference(),
        })
      }
      return new Response(null, { status: 404 })
    }))
    const { wrapper } = await mountQuestions('/lesson/plan-1/questions', 'alice')

    expect(wrapper.get('#lesson-question').attributes('placeholder')).toContain('还是没懂，换个例子')
    await wrapper.get('#lesson-question').setValue('这个行动什么时候结算？')
    await wrapper.get('#lesson-question-panel form').trigger('submit')
    await flushPromises()
    await wrapper.get('#lesson-question').setValue('还是没懂，换个例子。')
    await wrapper.get('#lesson-question-panel form').trigger('submit')
    await flushPromises()

    expect(answerRequests.at(-1)).toMatchObject({
      question: '还是没懂，换个例子。',
      previousQuestion: '这个行动什么时候结算？',
      learningIntent: null,
      language: 'zh-CN',
    })

    await wrapper.get('#lesson-question').setValue('这句尚未发送')
    setLocale('en')
    await flushPromises()
    expect((wrapper.get('#lesson-question').element as HTMLTextAreaElement).value).toBe('这句尚未发送')
    wrapper.unmount()
  })

  it('keeps the complete Q&A workspace localized in English', async () => {
    setLocale('en')
    const answerRequests: Array<Record<string, unknown>> = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(planFixture('plan-1', 'Deep Space'))
      if (path.endsWith('/illustrated-lessons/latest')) return Response.json(lessonFixture('Setup', 'Scoring'))
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path.endsWith('/answers') && init?.method === 'POST') {
        answerRequests.push(JSON.parse(String(init.body)) as Record<string, unknown>)
        return Response.json({
          answer: {
            language: 'en',
            status: 'ANSWERED', shortVerdict: 'Score after resolving the objective.',
            explanation: 'The cited sequence resolves the objective before scoring.',
            citations: [answerCitation()], exceptions: [],
            confidence: 'HIGH', answerBasis: 'DIRECT_RULE', source: 'UPLOADED',
            clarification: null, recovery: null, warnings: [],
          },
          conversationTurnId: null,
          rulingReference: answerRulingReference(),
        })
      }
      return new Response(null, { status: 404 })
    }))
    const { wrapper } = await mountQuestions('/lesson/plan-1/questions')

    expect(wrapper.text()).toContain('Ask the Deep Space rulebook')
    expect(wrapper.text()).toContain('Focused Q&A')
    expect(wrapper.text()).toContain('Ask the rulebook')
    expect(wrapper.text()).toContain('Read a card from a photo')
    expect(wrapper.text()).not.toMatch(/[\u3400-\u9fff]/)
    expect(wrapper.findAll('button').some((button) => button.text() === 'Walk through an example')).toBe(false)
    await wrapper.get('#lesson-question').setValue('When do I score the objective?')
    await wrapper.get('#lesson-question-panel form').trigger('submit')
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === 'Walk through an example')!.trigger('click')
    await flushPromises()
    expect(answerRequests.at(-1)).toMatchObject({
      question: expect.stringContaining('For the question “When do I score the objective?”'),
      previousQuestion: 'When do I score the objective?',
      learningIntent: 'EXAMPLE',
      language: 'en',
    })
    expect(answerRequests.at(-1)).not.toHaveProperty('currentLessonSection')

    await wrapper.findAll('button').find((button) => button.text() === 'Explain the key term')!.trigger('click')
    await flushPromises()
    expect(answerRequests.at(-1)).toMatchObject({
      question: expect.stringContaining('For the question “When do I score the objective?”'),
      learningIntent: 'DEFINE',
      language: 'en',
    })
    expect(String(answerRequests.at(-1)?.question)).not.toContain('one concrete, legal table example”')
    wrapper.unmount()
  })

  it('restores and clears only the signed-in players matching lesson thread', async () => {
    rememberLessonAnswerThread(sessionStorage, {
      username: 'alice', planId: 'plan-1', documentVersionId: 'version-plan-1', locale: 'zh-CN',
    }, [{
      question: '刚才什么时候结算？',
      learningIntent: null,
      answer: {
        language: 'zh-CN',
        status: 'ANSWERED', shortVerdict: '完成计分后结算。', explanation: '规则书给出了这个顺序。',
        citations: [answerCitation()], exceptions: [], confidence: 'HIGH', answerBasis: 'DIRECT_RULE', source: 'UPLOADED',
        clarification: null, recovery: null, warnings: [],
      },
    }])
    rememberLessonAnswerThread(sessionStorage, {
      username: 'bob', planId: 'plan-1', documentVersionId: 'version-plan-1', locale: 'zh-CN',
    }, [{
      question: 'Bob 的私有问题',
      learningIntent: null,
      answer: {
        language: 'zh-CN',
        status: 'ANSWERED', shortVerdict: 'Bob 的私有答案', explanation: '',
        citations: [answerCitation()], exceptions: [], confidence: 'HIGH', answerBasis: 'DIRECT_RULE', source: 'UPLOADED',
        clarification: null, recovery: null, warnings: [],
      },
    }])
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(planFixture('plan-1', '星际探索'))
      if (path.endsWith('/illustrated-lessons/latest')) return Response.json(lessonFixture())
      if (path === '/api/auth/session') return Response.json({ username: 'alice', roles: ['USER'] })
      return new Response(null, { status: 404 })
    }))

    const { wrapper } = await mountQuestions('/lesson/plan-1/questions')

    expect(wrapper.text()).toContain('刚才什么时候结算？')
    expect(wrapper.text()).toContain('完成计分后结算。')
    expect(wrapper.text()).toContain('仅保留在当前账号的浏览器会话')
    expect(wrapper.text()).not.toContain('Bob 的私有问题')

    await wrapper.findAll('button').find(button => button.text() === '清空本次答疑')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('刚才什么时候结算？')
    expect(document.body.textContent).toContain('当前浏览器会话中的 1 条问答会被移除')
    await Array.from(document.body.querySelectorAll('button'))
      .find(button => button.textContent === '清空答疑')!
      .click()
    await flushPromises()

    expect(wrapper.text()).not.toContain('刚才什么时候结算？')
    expect(sessionStorage.length).toBe(1)
    expect(Array.from({ length: sessionStorage.length }, (_, index) => sessionStorage.key(index)))
      .toContain('rulepilot:lesson-answer-thread:v2:bob:plan-1:version-plan-1:zh-CN')
    wrapper.unmount()
  })

  it('cancels the three-read route bundle, ignores late settlement, and never duplicates the shell session read', async () => {
    const pending: Array<{ path: string; resolve: (response: Response) => void }> = []
    const firstSignals: AbortSignal[] = []
    const fetchMock = vi.fn((input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path.includes('plan-1')) {
        firstSignals.push(init!.signal!)
        return new Promise<Response>((resolve) => { pending.push({ path, resolve }) })
      }
      if (path === '/api/v1/teaching-plans/plan-2') {
        return Promise.resolve(Response.json(planFixture('plan-2', '当前规则')))
      }
      if (path === '/api/v1/teaching-plans/plan-2/illustrated-lessons/latest') {
        return Promise.resolve(Response.json(lessonFixture('当前准备', '当前计分', 'plan-2')))
      }
      return Promise.resolve(new Response(null, { status: 404 }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const { wrapper, router } = await mountQuestions('/lesson/plan-1/questions')

    expect(pending).toHaveLength(3)
    await router.push('/lesson/plan-2/questions')
    await flushPromises()

    expect(firstSignals.every(signal => signal.aborted)).toBe(true)
    expect(wrapper.text()).toContain('当前规则')
    expect(fetchMock.mock.calls.some(([input]) => String(input) === '/api/auth/session')).toBe(false)

    for (const request of pending) request.resolve(staleWorkspaceResponse(request.path))
    await flushPromises()

    expect(wrapper.text()).toContain('当前规则')
    expect(wrapper.text()).not.toContain('过期规则')
    wrapper.unmount()
  })

  it.each(['plan', 'lesson'] as const)('rejects a mismatched %s identity before enabling Q&A', async (mismatch) => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json(planFixture(mismatch === 'plan' ? 'plan-2' : 'plan-1', '不应启用'))
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        return Response.json(lessonFixture('不应显示', '不应显示', mismatch === 'lesson' ? 'plan-2' : 'plan-1'))
      }
      return new Response(null, { status: 404 })
    }))

    const { wrapper } = await mountQuestions('/lesson/plan-1/questions')

    await vi.waitFor(() => expect(wrapper.text()).toContain('答疑页面暂时无法打开'))
    expect(wrapper.find('#lesson-question').exists()).toBe(false)
    wrapper.unmount()
  })

  it('aborts a failed bundle and retries with a fresh controller', async () => {
    let planReads = 0
    const signals: AbortSignal[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      signals.push(init!.signal!)
      if (path === '/api/v1/teaching-plans/plan-1') {
        planReads += 1
        return planReads === 1
          ? new Response(null, { status: 503 })
          : Response.json(planFixture('plan-1', '重试成功的规则'))
      }
      if (path.endsWith('/illustrated-lessons/latest')) return Response.json(lessonFixture('重试准备'))
      return new Response(null, { status: 404 })
    }))
    const { wrapper } = await mountQuestions('/lesson/plan-1/questions')

    await vi.waitFor(() => expect(wrapper.text()).toContain('答疑页面暂时无法打开'))
    expect(signals.slice(0, 3).every(signal => signal.aborted)).toBe(true)
    const firstSignal = signals[0]

    await wrapper.findAll('button').find(button => button.text() === '重新加载')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('重试成功的规则')
    expect(signals[3]).not.toBe(firstSignal)
    expect(signals.slice(3, 6).every(signal => !signal.aborted)).toBe(true)
    wrapper.unmount()
  })

  it('aborts the current bundle offline and reconnects with fresh transport', async () => {
    const firstSignals: AbortSignal[] = []
    const pending: Array<(response: Response) => void> = []
    let reads = 0
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      reads += 1
      if (reads <= 3) {
        firstSignals.push(init!.signal!)
        return new Promise<Response>((resolve) => { pending.push(resolve) })
      }
      if (path === '/api/v1/teaching-plans/plan-1') return Promise.resolve(Response.json(planFixture('plan-1', '重连规则')))
      if (path.endsWith('/illustrated-lessons/latest')) return Promise.resolve(Response.json(lessonFixture('重连准备')))
      return Promise.resolve(new Response(null, { status: 404 }))
    }))
    const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
    const { wrapper } = await mountQuestions('/lesson/plan-1/questions')

    online.mockReturnValue(false)
    window.dispatchEvent(new Event('offline'))
    await flushPromises()
    expect(firstSignals.every(signal => signal.aborted)).toBe(true)

    for (const resolve of pending) resolve(new Response(null, { status: 503 }))
    await flushPromises()
    expect(wrapper.text()).not.toContain('重连规则')

    online.mockReturnValue(true)
    window.dispatchEvent(new Event('online'))
    await flushPromises()

    expect(wrapper.text()).toContain('重连规则')
    wrapper.unmount()
  })

  it('aborts all initial reads when the Q&A route unmounts', async () => {
    const signals: AbortSignal[] = []
    vi.stubGlobal('fetch', vi.fn((_input: string | URL | Request, init?: RequestInit) => {
      signals.push(init!.signal!)
      return new Promise<Response>(() => undefined)
    }))
    const { wrapper } = await mountQuestions('/lesson/plan-1/questions')
    expect(signals).toHaveLength(3)

    wrapper.unmount()

    expect(signals.every(signal => signal.aborted)).toBe(true)
  })
})

async function mountQuestions(path: string, username = 'alice') {
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
    attachTo: document.body,
    global: {
      plugins: [router],
      stubs: {
        AppShell: {
          emits: ['sessionIdentity'],
          template: '<div><slot /></div>',
          mounted() {
            queueMicrotask(() => { this.$emit('sessionIdentity', username) })
          },
        },
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

function lessonFixture(first = '准备游戏', second = '结算分数', teachingPlanId = 'plan-1') {
  return {
    id: 'lesson-1',
    teachingPlanId,
    sections: [
      { position: 1, topicKey: 'setup', coverageTags: ['start'], title: first },
      { position: 2, topicKey: 'scoring', coverageTags: ['score', 'end'], title: second },
    ],
  }
}

function catalogPresentationFixture(gameName: string) {
  return {
    editionId: 'edition-1', gameName, editionName: `${gameName} edition`, language: 'zh-CN',
    publicationYear: 2024, bggId: 42, thumbnailUrl: 'https://example.test/catalog-cover.jpg',
    minPlayers: 1, maxPlayers: 5, playingTimeMinutes: 60, minimumAge: 10,
    bggUrl: 'https://boardgamegeek.com/boardgame/42',
  }
}

function answerCitation() {
  return { heading: '结算顺序', excerpt: '完成当前步骤后结算。', pageFrom: 2, pageTo: 2 }
}

function answerRulingReference() {
  return {
    citationIds: ['11111111-1111-4111-8111-111111111111'],
    confirmedRulingId: null,
    confirmedRulingVersion: null,
  }
}

function staleWorkspaceResponse(path: string) {
  if (path === '/api/v1/teaching-plans/plan-1') return Response.json(planFixture('plan-1', '过期规则'))
  if (path.endsWith('/illustrated-lessons/latest')) return Response.json(lessonFixture('过期准备'))
  return new Response(null, { status: 404 })
}
