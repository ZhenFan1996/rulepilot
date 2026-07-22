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
      if (path.includes('/api/v1/bgg/hot')) {
        return new Response(JSON.stringify([{
          rank: 1,
          bggId: 266192,
          name: 'Wingspan',
          publicationYear: 2019,
          thumbnailUrl: 'https://cf.geekdo-images.com/wingspan.jpg',
          bggUrl: 'https://boardgamegeek.com/boardgame/266192',
        }]), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      if (path.includes('/api/v1/teaching-plans')) {
        return new Response(JSON.stringify([{
          id: 'plan-1',
          gameTitle: '翼展翅膀',
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
    expect(wrapper.text()).toContain('继续玩翼展翅膀')
    expect(wrapper.get('a[href="/lessons/plan-1"]').text()).toBe('继续阅读')
    expect(wrapper.get('img[alt="Wingspan 封面"]').attributes('src')).toContain('wingspan.jpg')
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
})
