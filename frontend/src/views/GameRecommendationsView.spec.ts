import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import GameRecommendationsView from './GameRecommendationsView.vue'

const discovery = {
  sourceCount: 12,
  sort: 'hot',
  categoriesTranslated: true,
  categories: [
    { value: 'Family', label: '家庭' },
    { value: 'Strategy', label: '策略' },
  ],
  games: [{
    rank: 2,
    bggId: 266192,
    name: '展翅翱翔',
    originalName: 'Wingspan',
    nameLocalized: true,
    publicationYear: 2019,
    thumbnailUrl: 'https://example.test/wingspan.jpg',
    minPlayers: 1,
    maxPlayers: 5,
    playingTimeMinutes: 70,
    averageRating: 8.1,
    averageWeight: 2.5,
    categories: ['Family', 'Strategy'],
    mechanics: ['Card Drafting'],
    bggUrl: 'https://boardgamegeek.com/boardgame/266192',
  }],
}

describe('GameRecommendationsView', () => {
  beforeEach(() => localStorage.setItem('rulepilot:locale', 'zh-CN'))
  afterEach(() => vi.unstubAllGlobals())

  async function mountView() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/discover', name: 'game-recommendations', component: GameRecommendationsView },
        { path: '/discover/:bggId', name: 'game-discovery', component: { template: '<div />' } },
        { path: '/library', name: 'public-library', component: { template: '<div />' } },
        { path: '/teach', name: 'teach', component: { template: '<div />' } },
        { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
        { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
        { path: '/account', name: 'account', component: { template: '<div />' } },
        { path: '/login', name: 'login', component: { template: '<div />' } },
      ],
    })
    await router.push('/discover')
    await router.isReady()
    return mount(GameRecommendationsView, { global: { plugins: [router] } })
  }

  it('renders localized BGG categories and applies rating, category, and table filters', async () => {
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      if (String(input).includes('/api/v1/bgg/discovery')) return Response.json(discovery)
      return new Response(null, { status: 401 })
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('当前 12 款 BGG 热门桌游')
    expect(wrapper.text()).toContain('展翅翱翔')
    expect(wrapper.text()).toContain('Wingspan')
    expect(wrapper.text()).toContain('家庭')
    expect(wrapper.get('a[href="/discover/266192"]').attributes('href')).toBe('/discover/266192')

    const selects = wrapper.findAll('select')
    await selects[0]!.setValue('rating')
    await selects[1]!.setValue('Strategy')
    await selects[2]!.setValue('4')
    await wrapper.findAll('form')[1]!.trigger('submit')
    await flushPromises()

    expect(fetchMock.mock.calls.some(([input]) => {
      const url = String(input)
      return url.includes('sort=rating') && url.includes('category=Strategy') && url.includes('players=4')
    })).toBe(true)
  })

  it('searches BGG directly and links each result to the existing detail journey', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/bgg/discovery')) return Response.json(discovery)
      if (path.includes('/api/v1/bgg/search')) return Response.json([
        { bggId: 266192, name: 'Wingspan', publicationYear: 2019, bggUrl: 'https://boardgamegeek.com/boardgame/266192' },
      ])
      return new Response(null, { status: 401 })
    }))
    const wrapper = await mountView()
    await flushPromises()

    await wrapper.get('input[type="search"]').setValue('Wingspan')
    await wrapper.findAll('form')[0]!.trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('“Wingspan”的 BGG 搜索结果')
    expect(wrapper.text()).toContain('BGG #266192')
    expect(wrapper.get('a[href="/discover/266192"]').text()).toContain('查看详情')
    expect(wrapper.text()).toContain('返回热门推荐')
  })

  it('keeps a useful empty state and can clear restrictive filters', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input).includes('/api/v1/bgg/discovery')) return Response.json({ ...discovery, games: [] })
      return new Response(null, { status: 401 })
    }))
    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('这一批暂时没有合适的结果')
    expect(wrapper.text()).toContain('清空')
  })

  it('shows a retryable error without hiding direct title search', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 503 })))
    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('暂时读不到 BGG 热门资料')
    expect(wrapper.get('input[type="search"]').attributes('type')).toBe('search')
    expect(wrapper.get('button').text()).toBeTruthy()
  })
})
