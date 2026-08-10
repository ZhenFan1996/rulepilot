import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { setLocale } from '@/lib/locale'
import HomeView from './HomeView.vue'

const hotGames = Array.from({ length: 10 }, (_, index) => ({
  rank: index + 1,
  bggId: 100 + index,
  name: index === 0 ? '展翅翱翔' : `Game ${index + 1}`,
  originalName: index === 0 ? 'Wingspan' : `Game ${index + 1}`,
  nameLocalized: index === 0,
  publicationYear: 2020 + index,
  thumbnailUrl: `https://example.test/game-${index + 1}.jpg`,
  bggUrl: `https://boardgamegeek.com/boardgame/${100 + index}`,
}))

describe('HomeView', () => {
  async function mountHome() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: HomeView },
        { path: '/library', name: 'public-library', component: { template: '<div />' } },
        { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
        { path: '/discover', name: 'game-recommendations', component: { template: '<div />' } },
        { path: '/discover/catalog', name: 'game-catalog-browse', component: { template: '<div />' } },
        { path: '/discover/:bggId', name: 'game-discovery', component: { template: '<div />' } },
        { path: '/teach', name: 'teach', component: { template: '<div />' } },
        { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
        { path: '/account', name: 'account', component: { template: '<div />' } },
        { path: '/login', name: 'login', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()
    return mount(HomeView, { global: { plugins: [router], stubs: { BackgroundWorkCenter: true } } })
  }

  afterEach(() => {
    vi.unstubAllGlobals()
    document.documentElement.classList.remove('dark', 'light')
  })

  beforeEach(() => setLocale('zh-CN'))

  it('keeps the screen-print illustration supporting the two concrete first actions', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input).includes('/api/v1/bgg/recommendations')) return Response.json(hotGames)
      return new Response(null, { status: 401 })
    }))
    const wrapper = await mountHome()
    await flushPromises()

    expect(wrapper.text()).toContain('规则书递过来，咱们开桌')
    expect(wrapper.find('img[src="/illustrations/home-screenprint-friends.webp"]').exists()).toBe(true)
    expect(wrapper.find('.home-start').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('今晚不必先做完功课')
    expect(wrapper.text()).not.toContain('所有入口，最后汇成同一条路')
    expect(wrapper.text()).not.toContain('Agent')
  })

  it('makes attributed BGG hot games and three random picks prominent without displacing the rulebook action', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input).includes('/api/v1/bgg/recommendations')) return Response.json(hotGames)
      return new Response(null, { status: 401 })
    }))
    const wrapper = await mountHome()
    await flushPromises()

    expect(wrapper.findAll('img[alt="Powered by BoardGameGeek"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('BGG 热门桌游')
    expect(wrapper.text()).toContain('随机抽三盒')
    expect(wrapper.findAll('.home-game-grid > li')).toHaveLength(4)
    expect(wrapper.findAll('.home-random__games > li')).toHaveLength(3)
    expect(new Set(wrapper.findAll('.home-random__games a').map(link => link.attributes('href'))).size).toBe(3)
    expect(wrapper.get('a[href="/discover/100"]').text()).toContain('展翅翱翔')
    expect(wrapper.get('a[href="/discover/100"]').text()).toContain('Wingspan')
    expect(wrapper.get('a[href="/teach"].home-primary-action').text()).toContain('我有规则书')

    await wrapper.get('.home-random__shuffle').trigger('click')
    expect(wrapper.findAll('.home-random__games > li')).toHaveLength(3)
    expect(new Set(wrapper.findAll('.home-random__games a').map(link => link.attributes('href'))).size).toBe(3)
  })

  it('uses complete natural English copy after switching locale', async () => {
    setLocale('en')
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input).includes('/api/v1/bgg/recommendations')) return Response.json(hotGames)
      return new Response(null, { status: 401 })
    }))

    const wrapper = await mountHome()
    await flushPromises()

    expect(wrapper.text()).toContain('Hand me the rulebook. Let’s get this game to the table.')
    expect(wrapper.text()).toContain('Trending on BGG')
    expect(wrapper.text()).toContain('Three from the shelf')
    expect(wrapper.text()).not.toContain('规则书递过来')
  })

  it('updates the accessible theme toggle label', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 503 })))
    const wrapper = await mountHome()
    const toggle = wrapper.get('button[aria-label="切换到深色模式"]')
    await toggle.trigger('click')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(wrapper.find('button[aria-label="切换到浅色模式"]').exists()).toBe(true)
  })
})
