import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import HomeView from './HomeView.vue'

describe('HomeView', () => {
  async function mountHome() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: HomeView },
        { path: '/library', name: 'public-library', component: { template: '<div />' } },
        { path: '/read/:planId', name: 'public-lesson', component: { template: '<div />' } },
        { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
        { path: '/discover', name: 'game-recommendations', component: { template: '<div />' } },
        { path: '/discover/:bggId', name: 'game-discovery', component: { template: '<div />' } },
        { path: '/teach', name: 'teach', component: { template: '<div />' } },
        { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
        { path: '/lessons/:planId', name: 'lesson', component: { template: '<div />' } },
        { path: '/settings/models', name: 'model-settings', component: { template: '<div />' } },
        { path: '/account', name: 'account', component: { template: '<div />' } },
        { path: '/login', name: 'login', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()
    return mount(HomeView, { global: { plugins: [router] } })
  }

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('presents the real tabletop tasks without implementation copy', async () => {
    const wrapper = await mountHome()

    expect(wrapper.text()).toContain('上传规则书')
    expect(wrapper.text()).toContain('读公开讲解')
    expect(wrapper.text()).toContain('不用先创建游戏')
    expect(wrapper.text()).not.toContain('Agent')
    expect(wrapper.text()).not.toContain('FROM RULEBOOK')
    const attribution = wrapper.get('a[href="https://boardgamegeek.com/hotness"]')
    expect(attribution.get('img').attributes('src')).toBe('/powered-by-bgg-rgb.svg')
    expect(attribution.get('img').attributes('alt')).toBe('Powered by BoardGameGeek')
  })

  it('updates the accessible theme toggle label', async () => {
    document.documentElement.classList.remove('dark')
    const wrapper = await mountHome()

    const toggle = wrapper.get('button[aria-label="切换到深色模式"]')
    await toggle.trigger('click')

    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(wrapper.find('button[aria-label="切换到浅色模式"]').exists()).toBe(true)
    document.documentElement.classList.remove('dark')
  })

  it('lets a signed-in player continue the latest teaching plan from home', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/bgg/recommendations')) {
        return new Response(JSON.stringify([{
          rank: 1,
          bggId: 266192,
          name: '展翅翱翔',
          originalName: 'Wingspan',
          nameLocalized: true,
          publicationYear: 2019,
          thumbnailUrl: 'https://cf.geekdo-images.com/wingspan.jpg',
          bggUrl: 'https://boardgamegeek.com/boardgame/266192',
          minPlayers: 1,
          maxPlayers: 5,
          playingTimeMinutes: 70,
          averageRating: 8.1,
          averageWeight: 2.5,
          categories: ['Animals'],
          mechanics: ['Card Drafting'],
        }]), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      if (path.includes('/api/v1/teaching-plans')) {
        return new Response(JSON.stringify([{
          id: 'plan-1',
          gameTitle: 'CATAN Base Game Rules Corpus Replay',
          premise: '你要在四轮中建立得分最高的鸟类保护区。',
          playerCount: 4,
          durationMinutes: 25,
          createdAt: '2026-07-19T12:00:00Z',
        }]), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      if (path.includes('/api/auth/session')) {
        return new Response(JSON.stringify({ username: 'player', roles: ['USER'] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      return new Response(null, { status: 404 })
    }))

    const wrapper = await mountHome()
    await flushPromises()

    expect(wrapper.text()).toContain('player 的规则桌')
    expect(wrapper.text()).toContain('继续这一局')
    expect(wrapper.text()).toContain('CATAN')
    expect(wrapper.text()).not.toContain('Corpus Replay')
    expect(wrapper.get('a[href="/lessons/plan-1"]').text()).toBe('继续阅读')
    expect(wrapper.get('img[alt="展翅翱翔 封面"]').attributes('src')).toContain('wingspan.jpg')
    expect(wrapper.get('a[href="/discover/266192"]').attributes('aria-label')).toContain('展翅翱翔')
    expect(wrapper.text()).toContain('Wingspan')
    expect(wrapper.get('a[href="https://boardgamegeek.com/boardgame/266192"]').text()).toContain('BGG 资料')
    expect(wrapper.text()).toContain('1–5 人')
    expect(wrapper.text()).toContain('复杂度 2.5 / 5')
  })

  it('requests bounded player-fit filters and explains an empty hot set', async () => {
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      if (String(input).includes('/api/v1/bgg/recommendations')) return Response.json([])
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = await mountHome()
    await flushPromises()
    const filters = wrapper.findAll('select')
    await filters[0]!.setValue('4')
    await filters[1]!.setValue('90')
    await filters[2]!.setValue('3')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('players=4&maxMinutes=90&maxWeight=3'))).toBe(true)
    expect(wrapper.text()).toContain('没有同时满足条件的结果')
  })

  it('shows a recovery action when BGG and the personal catalog both return errors', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 503 })))

    const wrapper = await mountHome()
    await flushPromises()

    expect(wrapper.text()).toContain('暂时没有热门桌游资料')
    expect(wrapper.findAll('a[href="/catalog"]').some(link => link.text().includes('搜索桌游'))).toBe(true)
  })

  it('puts a public lesson alongside the first upload action', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/public/lessons')) {
        return Response.json([{
          teachingPlanId: 'public-plan-1', rulebookTitle: 'Wingspan Rules', sectionCount: 8, stepCount: 51,
          gameCover: { gameName: 'Wingspan', imageUrl: 'https://cf.geekdo-images.com/wingspan.jpg' },
        }])
      }
      return new Response(null, { status: 404 })
    }))

    const wrapper = await mountHome()
    await flushPromises()

    expect(wrapper.text()).toContain('公开讲解')
    expect(wrapper.get('a[href="/read/public-plan-1"]').text()).toContain('Wingspan')
  })

  it('groups repeated continuation names on the compact home list without deleting plans', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input).includes('/api/v1/teaching-plans')) return Response.json([
        { id: 'plan-1', gameTitle: 'CATAN Base Game Rules Corpus Replay', playerCount: 4, durationMinutes: 25, createdAt: '2026-07-20T12:00:00Z' },
        { id: 'plan-2', gameTitle: 'Catan', playerCount: 4, durationMinutes: 35, createdAt: '2026-07-19T12:00:00Z' },
      ])
      if (String(input).includes('/api/auth/session')) return Response.json({ username: 'player' })
      return new Response(null, { status: 404 })
    }))

    const wrapper = await mountHome()
    await flushPromises()

    expect(wrapper.text()).toContain('2 份讲解')
    expect(wrapper.text()).not.toContain('Corpus Replay')
  })
})
