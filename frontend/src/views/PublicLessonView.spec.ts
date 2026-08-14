import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { setLocale } from '@/lib/locale'
import PublicLessonView from './PublicLessonView.vue'

const shellRoutes = [
  { path: '/discover', name: 'game-recommendations', component: { template: '<div />' } },
  { path: '/teach', name: 'teach', component: { template: '<div />' } },
  { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
  { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
  { path: '/account', name: 'account', component: { template: '<div />' } },
  { path: '/login', name: 'login', component: { template: '<div />' } },
  { path: '/discover/:bggId', name: 'game-discovery', component: { template: '<div />' } },
]

function publicLessonRoutes() {
  return [
    { path: '/read/:planId', name: 'public-lesson', component: PublicLessonView },
    { path: '/read/:planId/questions', name: 'public-lesson-questions', component: PublicLessonView },
  ]
}

function createPublicLessonRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/library', name: 'public-library', component: { template: '<div />' } },
      ...publicLessonRoutes(),
      ...shellRoutes,
    ],
  })
}

function publicLessonPayload(plan: string, title: string, contentLanguage: 'zh-CN' | 'en' = 'zh-CN') {
  return {
    teachingPlanId: plan,
    documentVersionId: `version-${plan}`,
    rulebookTitle: title,
    officialSourceUrl: null,
    gameCover: null,
    contentLanguage,
    lesson: { id: `lesson-${plan}`, status: 'COMPLETE' as const, sections: [] },
  }
}

function publicAnswerPayload(verdict: string) {
  return {
    answer: {
      status: 'ANSWERED', shortVerdict: verdict, explanation: null, warnings: [],
      citations: [], exceptions: [], confidence: 'HIGH', clarification: null,
    },
    visualAids: [], examples: [],
  }
}

describe('PublicLessonView', () => {
  afterEach(() => {
    setLocale('zh-CN')
    localStorage.clear()
    sessionStorage.clear()
    vi.unstubAllGlobals()
  })

  it('renders a no-login lesson with cited visual crops and an official source link', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input).includes('/api/auth/session')) return new Response(null, { status: 401 })
      return Response.json({
      teachingPlanId: 'plan-1',
      documentVersionId: 'version-1',
      rulebookTitle: 'Wingspan Rules',
      officialSourceUrl: 'https://publisher.example/rules.pdf',
      gameCover: {
        gameName: 'Wingspan', imageUrl: 'https://cf.geekdo-images.com/wingspan.jpg',
        attributionUrl: 'https://boardgamegeek.com/boardgame/266192', attributionLabel: 'BoardGameGeek',
      },
      publicGame: { bggId: 266192, name: '翼展', bggUrl: 'https://boardgamegeek.com/boardgame/266192' },
      lesson: {
        id: 'lesson-1', status: 'DRAFT_READY', sections: [{
          position: 1, title: '摆好鸟类保护区', visualCaption: '先把玩家板放在自己面前。', steps: [{
            position: 1, heading: '放置玩家板', kind: 'VISUAL', text: '把玩家板放在自己面前。', sourcePages: [2],
            visualFocus: {
              pageNumber: 2,
              label: '玩家板设置',
              visibleDescription: '玩家板左侧排列资源标记，右侧是行动格。',
              x: 100,
              y: 200,
              width: 500,
              height: 300,
            },
          }],
        }],
      },
      })
    }))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/library', name: 'public-library', component: { template: '<div />' } },
        ...publicLessonRoutes(),
        ...shellRoutes,
      ],
    })
    await router.push('/read/plan-1')
    await router.isReady()

    const wrapper = mount(PublicLessonView, { global: { plugins: [router] } })
    await flushPromises()

    const sidebar = wrapper.get('aside.fixed')
    expect(sidebar.classes()).toContain('lg:flex')
    expect(wrapper.find('header.tabletop-hero').exists()).toBe(true)
    expect(wrapper.findAll('a[href="/library"]').some((link) => link.classes().includes('drawer-link-active'))).toBe(true)
    expect(wrapper.text()).toContain('Wingspan')
    expect(wrapper.get('img[alt="翼展 的游戏封面"]').attributes('src')).toBe('/api/public/lessons/plan-1/cover')
    expect(wrapper.text()).toContain('关联桌游')
    expect(wrapper.text()).toContain('翼展')
    expect(wrapper.get('a[href="/discover/266192"]').text()).toContain('查看桌游资料')
    expect(wrapper.get('img[alt="Powered by BoardGameGeek"]').attributes('src')).toBe('/powered-by-bgg-rgb.svg')
    expect(wrapper.text()).toContain('放置玩家板')
    expect(wrapper.text()).toContain('图中看什么')
    expect(wrapper.text()).toContain('玩家板左侧排列资源标记，右侧是行动格。')
    expect(wrapper.get('a[href="/api/public/lessons/plan-1/rulebook"]').text()).toContain('官方原规则书')
    expect(wrapper.get('[data-testid="lesson-visual-detail"] img[alt*="玩家板设置"]').attributes('src'))
      .toContain('/api/public/lessons/plan-1/pages/2/image/crop?x=100&y=200&width=500&height=300')
    expect(wrapper.text()).not.toContain('图标速查表')
    expect(wrapper.find('#public-question').exists()).toBe(false)
    expect(wrapper.get('a[href="/read/plan-1/questions"]').text()).toContain('规则答疑')
  })

  it('lets an anonymous reader ask and receive cited examples plus evidence-matched imagery', async () => {
    const lesson = {
      teachingPlanId: 'plan-1', documentVersionId: 'version-1', rulebookTitle: 'Wingspan Rules', officialSourceUrl: null, gameCover: null,
      lesson: {
        id: 'lesson-1', status: 'COMPLETE', sections: [{
          position: 1, title: '摆好鸟类保护区', visualCaption: '', steps: [
            {
              position: 1,
              heading: '放置玩家板',
              kind: 'VISUAL',
              text: '把玩家板放在自己面前。',
              sourcePages: [2],
              visualFocus: {
                pageNumber: 2,
                label: '玩家板设置',
                visibleDescription: '蓝色玩家板旁放着三枚木制标记。',
                x: 100,
                y: 200,
                width: 500,
                height: 300,
              },
            },
            { position: 2, heading: '开局示例', kind: 'EXAMPLE', text: '每位玩家从自己的玩家板开始。', sourcePages: [2], visualFocus: null },
          ],
        }],
      },
    }
    const fetchMock = vi.fn(async (_input: string | URL | Request, init?: RequestInit) => {
      if (String(_input).includes('/api/auth/session')) return new Response(null, { status: 401 })
      if (init?.method === 'POST') {
        return Response.json({
          answer: {
            status: 'ANSWERED', shortVerdict: '先把玩家板放到自己面前。', explanation: '这是开局的第一步.', warnings: [],
            citations: [{ heading: '设置', pageFrom: 2, pageTo: 2 }], exceptions: [], confidence: 'HIGH', answerBasis: 'GROUNDED_APPLICATION', clarification: null,
            calculations: [{ expression: 'floor(8 / 3) * 5', result: '10' }],
            situationChecks: [
              { requirement: '玩家板必须尚未放置', status: 'NOT_PROVIDED', playerFact: '' },
            ],
            walkthroughSteps: [
              { instruction: '取出玩家板。', explanation: '先找到自己的玩家板。', orderBasis: 'EXPLANATION_ORDER' },
            ],
            decisionBranches: [
              { condition: '玩家板尚未放置。', outcome: '把玩家板放到自己面前。', basis: 'EXPLICIT_RULE' },
            ],
          },
          visualAids: [{ visualFocus: lesson.lesson.sections[0]!.steps[0]!.visualFocus, relatedStep: '放置玩家板' }],
          examples: [{ heading: '开局示例', text: '每位玩家从自己的玩家板开始。', sourcePages: [2] }],
        })
      }
      return Response.json(lesson)
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/library', name: 'public-library', component: { template: '<div />' } },
        ...publicLessonRoutes(),
        ...shellRoutes,
      ],
    })
    await router.push('/read/plan-1/questions')
    await router.isReady()
    const wrapper = mount(PublicLessonView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('[data-testid="lesson-reading-column"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('读到哪一步卡住了？')
    await wrapper.get('#public-question').setValue('玩家板先放哪里？')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const answerRequest = fetchMock.mock.calls.find(([input, init]) => String(input).endsWith('/answers') && init?.method === 'POST')
    expect(JSON.parse(String(answerRequest?.[1]?.body))).toMatchObject({
      question: '玩家板先放哪里？',
    })
    expect(JSON.parse(String(answerRequest?.[1]?.body))).not.toHaveProperty('sectionPosition')
    expect(wrapper.find('select').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('问这一章')
    expect(wrapper.text()).toContain('先把玩家板放到自己面前。')
    expect(wrapper.text()).toContain('按规则回答当前问题')
    expect(wrapper.text()).toContain('这条答案如何得出')
    expect(wrapper.text()).toContain('floor(8 / 3) * 5 = 10')
    expect(wrapper.text()).toContain('确定性计算器复核')
    expect(wrapper.text()).toContain('当前局面条件')
    expect(wrapper.text()).toContain('尚未提供')
    expect(wrapper.text()).toContain('照这个顺序做')
    expect(wrapper.text()).toContain('讲解拆分')
    expect(wrapper.text()).toContain('不同条件会发生什么')
    expect(wrapper.text()).toContain('规则明示')
    expect(wrapper.text()).toContain('支持这段答案的规则图例')
    expect(wrapper.text()).toContain('蓝色玩家板旁放着三枚木制标记。')
    expect(wrapper.text()).toContain('照这个例子走：开局示例')
    expect(wrapper.get('a[aria-label="打开来源：设置，第 2 页"]').attributes('href'))
      .toBe('/api/public/lessons/plan-1/pages/2/image')
    expect(wrapper.get('img[alt*="玩家板设置"]').attributes('src')).toContain('/pages/2/image/crop')
    expect(wrapper.get('#public-answer-0').element.compareDocumentPosition(wrapper.get('form').element) & Node.DOCUMENT_POSITION_FOLLOWING)
      .not.toBe(0)

    await wrapper.findAll('button').find(button => button.text() === '解释关键词')!.trigger('click')
    await flushPromises()
    const learningRequest = fetchMock.mock.calls
      .filter(([input, init]) => String(input).endsWith('/answers') && init?.method === 'POST')
      .at(-1)
    expect(JSON.parse(String(learningRequest?.[1]?.body))).toMatchObject({
      question: expect.stringContaining('玩家板先放哪里？'),
      previousQuestion: '玩家板先放哪里？',
      language: 'zh-CN',
      learningIntent: 'DEFINE',
    })
    expect(String(JSON.parse(String(learningRequest?.[1]?.body)).question)).toContain('证据不足')

    wrapper.unmount()
    await router.push('/read/plan-1/questions')
    const restored = mount(PublicLessonView, { global: { plugins: [router] } })
    await flushPromises()

    expect(restored.text()).toContain('玩家板先放哪里？')
    expect(restored.text()).toContain('先把玩家板放到自己面前。')
    expect(restored.text()).toContain('支持这段答案的规则图例')
  })

  it('shows only truthful public-answer waiting state and lets the reader stop without losing the question', async () => {
    const lesson = {
      teachingPlanId: 'plan-1', documentVersionId: 'version-1', rulebookTitle: 'Wingspan Rules',
      officialSourceUrl: null, gameCover: null,
      lesson: { id: 'lesson-1', status: 'COMPLETE', sections: [] },
    }
    let answerSignal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return Promise.resolve(new Response(null, { status: 401 }))
      if (path.endsWith('/icon-glossary')) return Promise.resolve(new Response(null, { status: 404 }))
      if (path.endsWith('/answers') && init?.method === 'POST') {
        answerSignal = init.signal ?? undefined
        return new Promise<Response>((_resolve, reject) => {
          answerSignal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
        })
      }
      return Promise.resolve(Response.json(lesson))
    }))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/library', name: 'public-library', component: { template: '<div />' } },
        ...publicLessonRoutes(),
        ...shellRoutes,
      ],
    })
    await router.push('/read/plan-1/questions')
    await router.isReady()
    const wrapper = mount(PublicLessonView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.get('#public-question').setValue('这个效果何时结算？')
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(answerSignal).toBeDefined())

    expect(wrapper.text()).toContain('问题已收到，正在等待这次答疑结果')
    expect(wrapper.text()).toContain('不会展示未经服务端确认的执行步骤')
    expect(wrapper.text()).not.toContain('对齐问题')
    expect(wrapper.text()).not.toContain('附上来源与图例')

    await wrapper.findAll('button').find(button => button.text() === '停止等待')!.trigger('click')
    await vi.waitFor(() => expect(answerSignal?.aborted).toBe(true))

    expect(wrapper.text()).toContain('这次未完成的结果不会替换当前页面')
    expect((wrapper.get('#public-question').element as HTMLTextAreaElement).value).toBe('这个效果何时结算？')
    expect(wrapper.findAll('button').some(button => button.text() === '停止等待')).toBe(false)
  })

  it('turns clarification and insufficient evidence into focused, editable next steps', async () => {
    const lesson = {
      teachingPlanId: 'plan-1', documentVersionId: 'version-1', rulebookTitle: 'Wingspan Rules',
      officialSourceUrl: null, gameCover: null,
      lesson: { id: 'lesson-1', status: 'COMPLETE', sections: [] },
    }
    let answerNumber = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return new Response(null, { status: 401 })
      if (path.endsWith('/icon-glossary')) return new Response(null, { status: 404 })
      if (path.endsWith('/answers') && init?.method === 'POST') {
        answerNumber += 1
        const clarification = answerNumber === 1
        return Response.json({
          answer: {
            status: clarification ? 'CLARIFICATION_REQUIRED' : 'INSUFFICIENT_EVIDENCE',
            shortVerdict: clarification ? '需要确认你说的是哪个对象。' : '当前证据不足。',
            explanation: null, citations: [], exceptions: [], confidence: 'LOW', answerBasis: null,
            clarification: clarification ? '“这个”具体指规则书里的哪个对象？' : null, warnings: [],
          },
          visualAids: [], examples: [],
        })
      }
      return Response.json(lesson)
    }))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/library', name: 'public-library', component: { template: '<div />' } },
        ...publicLessonRoutes(),
        ...shellRoutes,
      ],
    })
    await router.push('/read/plan-1/questions')
    await router.isReady()
    const wrapper = mount(PublicLessonView, { attachTo: document.body, global: { plugins: [router] } })
    await flushPromises()

    await wrapper.get('#public-question').setValue('这个什么时候触发？')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const confidence = wrapper.get('[data-confidence="LOW"]')
    expect(confidence.classes()).toContain('bg-red-50')
    expect(wrapper.text()).toContain('“这个”具体指规则书里的哪个对象')
    await wrapper.findAll('button').find(button => button.text() === '补充这项信息')!.trigger('click')
    expect((wrapper.get('#public-question').element as HTMLTextAreaElement).value).toBe('我指的是：')
    expect(document.activeElement).toBe(wrapper.get('#public-question').element)

    await wrapper.get('#public-question').setValue('我指的是：红色行动牌')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('具体对象、触发时机或前一步')
    await wrapper.findAll('button').find(button => button.text() === '补充条件后重试')!.trigger('click')

    expect((wrapper.get('#public-question').element as HTMLTextAreaElement).value)
      .toBe('我指的是：红色行动牌\n补充条件：')
    expect(document.activeElement).toBe(wrapper.get('#public-question').element)
    wrapper.unmount()
  })

  it('isolates a public answer thread by signed-in reader and clears only that reader’s current guide', async () => {
    const lesson = {
      teachingPlanId: 'plan-1', documentVersionId: 'version-1', rulebookTitle: 'Wingspan Rules', officialSourceUrl: null, gameCover: null,
      lesson: { id: 'lesson-1', status: 'COMPLETE', sections: [] },
    }
    const storedTurn = (question: string, verdict: string) => ({
      question,
      answer: {
        answer: {
          status: 'ANSWERED', shortVerdict: verdict, explanation: null, warnings: [],
          citations: [], exceptions: [], confidence: 'HIGH', clarification: null,
        },
        visualAids: [], examples: [],
      },
    })
    const aliceKey = 'rulepilot:public-answer-thread:account:alice:plan-1:zh-CN'
    const bobKey = 'rulepilot:public-answer-thread:account:bob:plan-1:zh-CN'
    sessionStorage.setItem(aliceKey, JSON.stringify([storedTurn('Alice 的问题', 'Alice 的答复')]))
    sessionStorage.setItem(bobKey, JSON.stringify([storedTurn('Bob 的问题', 'Bob 的答复')]))

    let username = 'alice'
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      if (String(input).includes('/api/auth/session')) return Response.json({ username, roles: ['USER'] })
      return Response.json(lesson)
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/library', name: 'public-library', component: { template: '<div />' } },
        ...publicLessonRoutes(),
        ...shellRoutes,
      ],
    })
    await router.push('/read/plan-1/questions')
    await router.isReady()

    const alice = mount(PublicLessonView, { attachTo: document.body, global: { plugins: [router] } })
    await flushPromises()

    expect(alice.text()).toContain('Alice 的问题')
    expect(alice.text()).not.toContain('Bob 的问题')
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/auth/session'))).toHaveLength(1)
    await alice.get('#public-question').setValue('尚未发送的问题')
    await alice.get('button[aria-label="清空本次答疑"]').trigger('click')
    await flushPromises()
    expect(alice.text()).toContain('Alice 的问题')
    expect(document.body.textContent).toContain('服务器没有可供恢复的副本')
    await Array.from(document.body.querySelectorAll('button'))
      .find(button => button.textContent === '清空答疑')!
      .click()
    await flushPromises()
    expect(alice.text()).not.toContain('Alice 的问题')
    expect((alice.get('#public-question').element as HTMLTextAreaElement).value).toBe('尚未发送的问题')
    expect(document.activeElement).toBe(alice.get('#public-question').element)
    expect(sessionStorage.getItem(aliceKey)).toBeNull()
    expect(sessionStorage.getItem(bobKey)).not.toBeNull()

    alice.unmount()
    username = 'bob'
    await router.push('/read/plan-1/questions')
    const bob = mount(PublicLessonView, { attachTo: document.body, global: { plugins: [router] } })
    await flushPromises()

    expect(bob.text()).toContain('Bob 的问题')
    expect(bob.text()).not.toContain('Alice 的问题')
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/auth/session'))).toHaveLength(2)
    bob.unmount()
  })

  it('switches a public guide and its question request to an available English localization', async () => {
    const chinese = {
      teachingPlanId: 'plan-1', documentVersionId: 'version-1', rulebookTitle: 'Wingspan Rules', officialSourceUrl: null, gameCover: null,
      contentLanguage: 'zh-CN', localizationStatus: 'READY',
      lesson: { id: 'lesson-1', status: 'COMPLETE', sections: [{
        position: 1, title: '摆好鸟类保护区', visualCaption: '', steps: [
          { position: 1, heading: '放置玩家板', kind: 'DO', text: '把玩家板放在自己面前。', sourcePages: [2], visualFocus: null },
        ],
      }] },
    }
    const english = {
      ...chinese,
      contentLanguage: 'en',
      lesson: { ...chinese.lesson, sections: [{
        position: 1, title: 'Set up your habitat', visualCaption: '', steps: [
          { position: 1, heading: 'Place your player mat', kind: 'DO', text: 'Put your player mat in front of you.', sourcePages: [2], visualFocus: null },
        ],
      }] },
    }
    const fetchMock = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return new Response(null, { status: 401 })
      if (init?.method === 'POST') {
        return Response.json({
          answer: {
            status: 'ANSWERED', shortVerdict: 'Place the mat in front of you.', explanation: 'It starts your personal play area.', warnings: [],
            citations: [{ heading: 'Setup', pageFrom: 2, pageTo: 2 }], exceptions: [], confidence: 'MEDIUM', clarification: null,
          }, visualAids: [], examples: [],
        })
      }
      return Response.json(path.includes('language=en') ? english : chinese)
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/library', name: 'public-library', component: { template: '<div />' } },
        ...publicLessonRoutes(),
        ...shellRoutes,
      ],
    })
    await router.push('/read/plan-1')
    await router.isReady()
    const wrapper = mount(PublicLessonView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === 'EN')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Set up your habitat')
    expect(wrapper.text()).toContain('Place your player mat')
    expect(wrapper.find('#public-question').exists()).toBe(false)
    await router.push('/read/plan-1/questions')
    await flushPromises()

    expect(wrapper.text()).toContain('Rule Q&A')
    expect(wrapper.text()).toContain('Ask the Wingspan rulebook')
    await wrapper.get('#public-question').setValue('Where does my mat go?')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const request = fetchMock.mock.calls.find(([input, init]) => String(input).endsWith('/answers') && init?.method === 'POST')
    expect(JSON.parse(String(request?.[1]?.body))).toMatchObject({
      language: 'en', question: 'Where does my mat go?',
    })
    expect(JSON.parse(String(request?.[1]?.body))).not.toHaveProperty('sectionPosition')
    expect(wrapper.text()).toContain('Place the mat in front of you.')
    const mediumConfidence = wrapper.get('[data-confidence="MEDIUM"]')
    expect(mediumConfidence.classes()).toContain('bg-amber-50')
    expect(mediumConfidence.classes()).not.toContain('bg-emerald-50')

    await wrapper.get('#public-question').setValue('Keep this draft across languages')
    await wrapper.findAll('button').find((button) => button.text() === '中文')!.trigger('click')
    await flushPromises()
    expect((wrapper.get('#public-question').element as HTMLTextAreaElement).value)
      .toBe('Keep this draft across languages')
  })

  it('waits for both the shell identity and exact lesson before restoring an account thread', async () => {
    const storedTurn = {
      question: 'Alice 的已保存问题',
      answer: publicAnswerPayload('Alice 的已保存答案'),
    }
    sessionStorage.setItem(
      'rulepilot:public-answer-thread:account:alice:plan-1:zh-CN',
      JSON.stringify([storedTurn]),
    )

    let resolveSession: ((response: Response) => void) | undefined
    const resourceFirstFetch = vi.fn((input: string | URL | Request) => {
      if (String(input).includes('/api/auth/session')) {
        return new Promise<Response>((resolve) => { resolveSession = resolve })
      }
      return Promise.resolve(Response.json(publicLessonPayload('plan-1', 'Resource First Rules')))
    })
    vi.stubGlobal('fetch', resourceFirstFetch)
    const resourceFirstRouter = createPublicLessonRouter()
    await resourceFirstRouter.push('/read/plan-1/questions')
    await resourceFirstRouter.isReady()
    const resourceFirst = mount(PublicLessonView, { global: { plugins: [resourceFirstRouter] } })
    await flushPromises()

    expect(resourceFirst.text()).not.toContain('Alice 的已保存问题')
    expect(resourceFirst.get('#public-question').attributes()).toHaveProperty('disabled')
    resolveSession!(Response.json({ username: ' Alice ', roles: ['USER'] }))
    await flushPromises()
    expect(resourceFirst.text()).toContain('Alice 的已保存问题')
    expect(resourceFirst.get('#public-question').attributes()).not.toHaveProperty('disabled')
    expect(resourceFirstFetch.mock.calls.filter(([input]) => String(input).includes('/api/auth/session'))).toHaveLength(1)
    resourceFirst.unmount()

    let resolveLesson: ((response: Response) => void) | undefined
    const identityFirstFetch = vi.fn((input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) {
        return Promise.resolve(Response.json({ username: 'ALICE', roles: ['USER'] }))
      }
      if (path.includes('/api/public/lessons/plan-1?')) {
        return new Promise<Response>((resolve) => { resolveLesson = resolve })
      }
      return Promise.resolve(new Response(null, { status: 404 }))
    })
    vi.stubGlobal('fetch', identityFirstFetch)
    const identityFirstRouter = createPublicLessonRouter()
    await identityFirstRouter.push('/read/plan-1/questions')
    await identityFirstRouter.isReady()
    const identityFirst = mount(PublicLessonView, { global: { plugins: [identityFirstRouter] } })
    await flushPromises()

    expect(identityFirst.text()).not.toContain('Alice 的已保存问题')
    resolveLesson!(Response.json(publicLessonPayload('plan-1', 'Identity First Rules')))
    await flushPromises()
    expect(identityFirst.text()).toContain('Alice 的已保存问题')
    expect(identityFirstFetch.mock.calls.filter(([input]) => String(input).includes('/api/auth/session'))).toHaveLength(1)
    identityFirst.unmount()
  })

  it('rejects a mismatched public lesson identity and retries with a fresh request', async () => {
    const lessonSignals: AbortSignal[] = []
    let lessonRequest = 0
    const fetchMock = vi.fn((input: string | URL | Request, init?: RequestInit) => {
      if (String(input).includes('/api/auth/session')) return Promise.resolve(new Response(null, { status: 401 }))
      lessonRequest++
      if (init?.signal) lessonSignals.push(init.signal)
      return Promise.resolve(Response.json(lessonRequest === 1
        ? publicLessonPayload('wrong-plan', 'Wrong Rules')
        : publicLessonPayload('plan-1', 'Recovered Rules')))
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createPublicLessonRouter()
    await router.push('/read/plan-1')
    await router.isReady()
    const wrapper = mount(PublicLessonView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('[data-testid="public-lesson-reader"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Wrong Rules')
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    await wrapper.get('section button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Recovered Rules')
    expect(lessonSignals).toHaveLength(2)
    expect(lessonSignals[1]).not.toBe(lessonSignals[0])
    wrapper.unmount()
  })

  it('cancels a replaced locale read and ignores its late settlement', async () => {
    let resolveChineseLesson: ((response: Response) => void) | undefined
    let chineseSignal: AbortSignal | undefined
    const fetchMock = vi.fn((input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return Promise.resolve(new Response(null, { status: 401 }))
      if (path.includes('language=zh-CN')) {
        chineseSignal = init?.signal ?? undefined
        return new Promise<Response>((resolve) => { resolveChineseLesson = resolve })
      }
      return Promise.resolve(Response.json(publicLessonPayload('plan-1', 'English Rules', 'en')))
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createPublicLessonRouter()
    await router.push('/read/plan-1')
    await router.isReady()
    const wrapper = mount(PublicLessonView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.findAll('button').find(button => button.text() === 'EN')!.trigger('click')
    await vi.waitFor(() => expect(chineseSignal?.aborted).toBe(true))
    await flushPromises()
    expect(wrapper.text()).toContain('English Rules')

    resolveChineseLesson!(Response.json(publicLessonPayload('plan-1', '迟到的中文规则')))
    await flushPromises()
    expect(wrapper.text()).toContain('English Rules')
    expect(wrapper.text()).not.toContain('迟到的中文规则')
    wrapper.unmount()
  })

  it('preserves a completed Q&A thread across modes and cancels an unfinished answer on exit', async () => {
    let answerRequest = 0
    let resolveLateAnswer: ((response: Response) => void) | undefined
    let lateAnswerSignal: AbortSignal | undefined
    let lateAnswerUrl = ''
    let lateAnswerBody = ''
    const fetchMock = vi.fn((input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return Promise.resolve(new Response(null, { status: 401 }))
      if (path.endsWith('/answers') && init?.method === 'POST') {
        answerRequest++
        if (answerRequest === 1) return Promise.resolve(Response.json(publicAnswerPayload('第一条已保存答案')))
        lateAnswerSignal = init.signal ?? undefined
        lateAnswerUrl = path
        lateAnswerBody = String(init.body)
        return new Promise<Response>((resolve) => { resolveLateAnswer = resolve })
      }
      return Promise.resolve(Response.json(publicLessonPayload('plan-1', 'Question Mode Rules')))
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createPublicLessonRouter()
    await router.push('/read/plan-1/questions')
    await router.isReady()
    const wrapper = mount(PublicLessonView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.get('#public-question').setValue('第一条问题')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('第一条已保存答案')

    await router.push('/read/plan-1')
    await flushPromises()
    expect(wrapper.find('#public-question').exists()).toBe(false)
    await router.push('/read/plan-1/questions')
    await flushPromises()
    expect(wrapper.text()).toContain('第一条问题')
    expect(wrapper.text()).toContain('第一条已保存答案')

    await wrapper.get('#public-question').setValue('第二条尚未完成的问题')
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(lateAnswerSignal).toBeDefined())
    await router.push('/read/plan-1')
    await vi.waitFor(() => expect(lateAnswerSignal?.aborted).toBe(true))

    expect(lateAnswerUrl).toBe('/api/public/lessons/plan-1/answers')
    expect(JSON.parse(lateAnswerBody)).toMatchObject({
      question: '第二条尚未完成的问题',
      language: 'zh-CN',
      previousQuestion: '第一条问题',
    })
    resolveLateAnswer!(Response.json(publicAnswerPayload('不应出现的迟到答案')))
    await flushPromises()
    await router.push('/read/plan-1/questions')
    await flushPromises()

    expect(wrapper.text()).toContain('第一条已保存答案')
    expect(wrapper.text()).not.toContain('不应出现的迟到答案')
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/auth/session'))).toHaveLength(1)
    wrapper.unmount()
  })

  it('binds an answer request to its captured locale and ignores settlement after localization changes', async () => {
    let resolveChineseAnswer: ((response: Response) => void) | undefined
    let chineseAnswerSignal: AbortSignal | undefined
    let chineseAnswerUrl = ''
    let chineseAnswerBody = ''
    const fetchMock = vi.fn((input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return Promise.resolve(new Response(null, { status: 401 }))
      if (path.endsWith('/answers') && init?.method === 'POST') {
        chineseAnswerSignal = init.signal ?? undefined
        chineseAnswerUrl = path
        chineseAnswerBody = String(init.body)
        return new Promise<Response>((resolve) => { resolveChineseAnswer = resolve })
      }
      return Promise.resolve(Response.json(path.includes('language=en')
        ? publicLessonPayload('plan-1', 'English Question Rules', 'en')
        : publicLessonPayload('plan-1', '中文问答规则')))
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createPublicLessonRouter()
    await router.push('/read/plan-1/questions')
    await router.isReady()
    const wrapper = mount(PublicLessonView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.get('#public-question').setValue('这个动作何时发生？')
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(chineseAnswerSignal).toBeDefined())
    await wrapper.findAll('button').find(button => button.text() === 'EN')!.trigger('click')
    await vi.waitFor(() => expect(chineseAnswerSignal?.aborted).toBe(true))
    await flushPromises()

    expect(chineseAnswerUrl).toBe('/api/public/lessons/plan-1/answers')
    expect(JSON.parse(chineseAnswerBody)).toMatchObject({
      question: '这个动作何时发生？',
      language: 'zh-CN',
    })
    expect(wrapper.text()).toContain('English Question Rules')
    resolveChineseAnswer!(Response.json(publicAnswerPayload('不应写入英文线程的中文答案')))
    await flushPromises()
    expect(wrapper.text()).not.toContain('不应写入英文线程的中文答案')
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/auth/session'))).toHaveLength(1)
    wrapper.unmount()
  })

  it('aborts an in-flight public lesson read when the route instance unmounts', async () => {
    let resolveLesson: ((response: Response) => void) | undefined
    let lessonSignal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request, init?: RequestInit) => {
      if (String(input).includes('/api/auth/session')) return Promise.resolve(new Response(null, { status: 401 }))
      lessonSignal = init?.signal ?? undefined
      return new Promise<Response>((resolve) => { resolveLesson = resolve })
    }))
    const router = createPublicLessonRouter()
    await router.push('/read/plan-1')
    await router.isReady()
    const wrapper = mount(PublicLessonView, { global: { plugins: [router] } })
    await vi.waitFor(() => expect(lessonSignal).toBeDefined())

    wrapper.unmount()
    expect(lessonSignal?.aborted).toBe(true)
    resolveLesson!(Response.json(publicLessonPayload('plan-1', 'Late Unmounted Rules')))
    await flushPromises()
  })

  it('keeps the latest public guide when an aborted navigation resolves late', async () => {
    let resolveFirstLesson: ((response: Response) => void) | undefined
    let firstLessonSignal: AbortSignal | undefined
    const firstLesson = {
      teachingPlanId: 'plan-1', documentVersionId: 'version-1', rulebookTitle: 'First Rules', officialSourceUrl: null, gameCover: null,
      lesson: { id: 'lesson-1', status: 'COMPLETE', sections: [] },
    }
    const secondLesson = {
      teachingPlanId: 'plan-2', documentVersionId: 'version-2', rulebookTitle: 'Second Rules', officialSourceUrl: null, gameCover: null,
      lesson: { id: 'lesson-2', status: 'COMPLETE', sections: [] },
    }
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return Promise.resolve(new Response(null, { status: 401 }))
      if (path.includes('/plan-1')) {
        firstLessonSignal = init?.signal ?? undefined
        return new Promise<Response>((resolve) => { resolveFirstLesson = resolve })
      }
      return Promise.resolve(Response.json(secondLesson))
    }))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/library', name: 'public-library', component: { template: '<div />' } },
        ...publicLessonRoutes(),
        ...shellRoutes,
      ],
    })
    await router.push('/read/plan-1')
    await router.isReady()
    const wrapper = mount(PublicLessonView, { global: { plugins: [router] } })
    await flushPromises()

    await router.push('/read/plan-2')
    await flushPromises()
    expect(firstLessonSignal?.aborted).toBe(true)
    expect(wrapper.text()).toContain('Second Rules')

    resolveFirstLesson!(Response.json(firstLesson))
    await flushPromises()
    expect(wrapper.text()).toContain('Second Rules')
    expect(wrapper.text()).not.toContain('First Rules')
  })
})
