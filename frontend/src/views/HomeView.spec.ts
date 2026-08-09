import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

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

  it('leads with discovery, the generated social illustration, and a continuous feature guide', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input).includes('/api/v1/bgg/recommendations')) return Response.json(hotGames)
      return new Response(null, { status: 401 })
    }))
    const wrapper = await mountHome()
    await flushPromises()

    expect(wrapper.text()).toContain('把想玩的，变成今晚真的能开桌')
    expect(wrapper.text()).toContain('热门桌游')
    expect(wrapper.text()).toContain('随机碰三款')
    expect(wrapper.text()).toContain('所有入口，最后汇成同一条路')
    expect(wrapper.text()).toContain('让讲解在后台完成')
    expect(wrapper.find('img[src="/illustrations/tabletop-gathering-v2.webp"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('继续这一局')
    expect(wrapper.text()).not.toContain('Agent')
  })

  it('renders attributed BGG hot and random game links and can shuffle the random set', async () => {
    vi.spyOn(Math, 'random').mockReturnValueOnce(0).mockReturnValueOnce(0.8)
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input).includes('/api/v1/bgg/recommendations')) return Response.json(hotGames)
      return new Response(null, { status: 401 })
    }))
    const wrapper = await mountHome()
    await flushPromises()

    expect(wrapper.findAll('img[alt="Powered by BoardGameGeek"]')).toHaveLength(2)
    expect(wrapper.get('a[href="/discover/100"]').text()).toContain('展翅翱翔')
    expect(wrapper.get('a[href="/discover/100"]').text()).toContain('Wingspan')
    const before = wrapper.findAll('.random-board a').map(link => link.attributes('href'))
    await wrapper.get('.random-board button').trigger('click')
    const after = wrapper.findAll('.random-board a').map(link => link.attributes('href'))
    expect(after).not.toEqual(before)
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
