import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { setLocale } from '@/lib/locale'
import PublicLessonView from './PublicLessonView.vue'

const shellRoutes = [
  { path: '/teach', name: 'teach', component: { template: '<div />' } },
  { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
  { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
  { path: '/account', name: 'account', component: { template: '<div />' } },
  { path: '/login', name: 'login', component: { template: '<div />' } },
]

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
      if (String(input).endsWith('/icon-glossary')) return Response.json(iconGlossaryFixture())
      return Response.json({
      teachingPlanId: 'plan-1',
      documentVersionId: 'version-1',
      rulebookTitle: 'Wingspan Rules',
      officialSourceUrl: 'https://publisher.example/rules.pdf',
      gameCover: {
        gameName: 'Wingspan', imageUrl: 'https://cf.geekdo-images.com/wingspan.jpg',
        attributionUrl: 'https://boardgamegeek.com/boardgame/266192', attributionLabel: 'BoardGameGeek',
      },
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
        { path: '/read/:planId', name: 'public-lesson', component: PublicLessonView },
        ...shellRoutes,
      ],
    })
    await router.push('/read/plan-1')
    await router.isReady()

    const wrapper = mount(PublicLessonView, { global: { plugins: [router] } })
    await flushPromises()

    const sidebar = wrapper.get('aside.fixed')
    expect(sidebar.classes()).toContain('lg:flex')
    expect(wrapper.findAll('header')).toHaveLength(1)
    expect(wrapper.findAll('a[href="/library"]').some((link) => link.classes().includes('bg-ink'))).toBe(true)
    expect(wrapper.text()).toContain('Wingspan')
    expect(wrapper.get('img[alt="Wingspan 的游戏封面"]').attributes('src')).toBe('/api/public/lessons/plan-1/cover')
    expect(wrapper.text()).toContain('放置玩家板')
    expect(wrapper.text()).toContain('图中看什么')
    expect(wrapper.text()).toContain('玩家板左侧排列资源标记，右侧是行动格。')
    expect(wrapper.get('a[href="/api/public/lessons/plan-1/rulebook"]').text()).toContain('官方原规则书')
    expect(wrapper.get('img[alt*="玩家板设置"]').attributes('src'))
      .toContain('/api/public/lessons/plan-1/pages/2/image/crop?x=100&y=200&width=500&height=300')
    expect(wrapper.text()).toContain('图标速查表')
    expect(wrapper.text()).toContain('执行一次行动。')
    expect(wrapper.get('img[alt*="行动图标"]').attributes('src'))
      .toBe('/api/public/lessons/plan-1/icon-glossary/icons/icon-occurrence-1/image')
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
        { path: '/read/:planId', name: 'public-lesson', component: PublicLessonView },
        ...shellRoutes,
      ],
    })
    await router.push('/read/plan-1')
    await router.isReady()
    const wrapper = mount(PublicLessonView, { global: { plugins: [router] } })
    await flushPromises()

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
    expect(wrapper.text()).toContain('支持这段答案的规则图例')
    expect(wrapper.text()).toContain('蓝色玩家板旁放着三枚木制标记。')
    expect(wrapper.text()).toContain('照这个例子走：开局示例')
    expect(wrapper.get('a[aria-label="打开来源：设置，第 2 页"]').attributes('href'))
      .toBe('/api/public/lessons/plan-1/pages/2/image')
    expect(wrapper.get('img[alt*="玩家板设置"]').attributes('src')).toContain('/pages/2/image/crop')
    expect(wrapper.get('#public-answer-0').element.compareDocumentPosition(wrapper.get('form').element) & Node.DOCUMENT_POSITION_FOLLOWING)
      .not.toBe(0)

    wrapper.unmount()
    const restored = mount(PublicLessonView, { global: { plugins: [router] } })
    await flushPromises()

    expect(restored.text()).toContain('玩家板先放哪里？')
    expect(restored.text()).toContain('先把玩家板放到自己面前。')
    expect(restored.text()).toContain('支持这段答案的规则图例')
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
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input).includes('/api/auth/session')) return Response.json({ username, roles: ['USER'] })
      return Response.json(lesson)
    }))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/library', name: 'public-library', component: { template: '<div />' } },
        { path: '/read/:planId', name: 'public-lesson', component: PublicLessonView },
        ...shellRoutes,
      ],
    })
    await router.push('/read/plan-1')
    await router.isReady()

    const alice = mount(PublicLessonView, { global: { plugins: [router] } })
    await flushPromises()

    expect(alice.text()).toContain('Alice 的问题')
    expect(alice.text()).not.toContain('Bob 的问题')
    await alice.get('button[aria-label="清空本次答疑"]').trigger('click')
    await flushPromises()
    expect(alice.text()).not.toContain('Alice 的问题')
    expect(sessionStorage.getItem(aliceKey)).toBeNull()
    expect(sessionStorage.getItem(bobKey)).not.toBeNull()

    alice.unmount()
    username = 'bob'
    const bob = mount(PublicLessonView, { global: { plugins: [router] } })
    await flushPromises()

    expect(bob.text()).toContain('Bob 的问题')
    expect(bob.text()).not.toContain('Alice 的问题')
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
            citations: [{ heading: 'Setup', pageFrom: 2, pageTo: 2 }], exceptions: [], confidence: 'HIGH', clarification: null,
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
        { path: '/read/:planId', name: 'public-lesson', component: PublicLessonView },
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
    expect(wrapper.text()).toContain('Ask the rulebook')
    await wrapper.get('#public-question').setValue('Where does my mat go?')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const request = fetchMock.mock.calls.find(([input, init]) => String(input).endsWith('/answers') && init?.method === 'POST')
    expect(JSON.parse(String(request?.[1]?.body))).toMatchObject({
      language: 'en', question: 'Where does my mat go?',
    })
    expect(JSON.parse(String(request?.[1]?.body))).not.toHaveProperty('sectionPosition')
    expect(wrapper.text()).toContain('Place the mat in front of you.')
  })

  it('keeps the latest public guide when an earlier navigation resolves late', async () => {
    let resolveFirstLesson: ((response: Response) => void) | undefined
    const firstLesson = {
      teachingPlanId: 'plan-1', documentVersionId: 'version-1', rulebookTitle: 'First Rules', officialSourceUrl: null, gameCover: null,
      lesson: { id: 'lesson-1', status: 'COMPLETE', sections: [] },
    }
    const secondLesson = {
      teachingPlanId: 'plan-2', documentVersionId: 'version-2', rulebookTitle: 'Second Rules', officialSourceUrl: null, gameCover: null,
      lesson: { id: 'lesson-2', status: 'COMPLETE', sections: [] },
    }
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return Promise.resolve(new Response(null, { status: 401 }))
      if (path.includes('/plan-1')) return new Promise<Response>((resolve) => { resolveFirstLesson = resolve })
      return Promise.resolve(Response.json(secondLesson))
    }))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/library', name: 'public-library', component: { template: '<div />' } },
        { path: '/read/:planId', name: 'public-lesson', component: PublicLessonView },
        ...shellRoutes,
      ],
    })
    await router.push('/read/plan-1')
    await router.isReady()
    const wrapper = mount(PublicLessonView, { global: { plugins: [router] } })
    await flushPromises()

    await router.push('/read/plan-2')
    await flushPromises()
    expect(wrapper.text()).toContain('Second Rules')

    resolveFirstLesson!(Response.json(firstLesson))
    await flushPromises()
    expect(wrapper.text()).toContain('Second Rules')
    expect(wrapper.text()).not.toContain('First Rules')
  })
})

function iconGlossaryFixture() {
  return {
    status: 'READY',
    totalPages: 2,
    inspectedPages: 2,
    completePages: 2,
    warnings: [],
    icons: [{
      id: 'icon-1',
      name: '行动图标',
      visualDescription: '蓝色圆形中的白色手掌',
      explanation: '执行一次行动。',
      evidenceText: '行动：执行一次行动',
      meaningStatus: 'EXPLICIT',
      representativeOccurrenceId: 'icon-occurrence-1',
      occurrences: [{
        id: 'icon-occurrence-1',
        pageNumber: 2,
        x: 100,
        y: 100,
        width: 80,
        height: 80,
      }],
    }],
  }
}
