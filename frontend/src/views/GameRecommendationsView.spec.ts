import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { setLocale } from '@/lib/locale'

import GameRecommendationsView from './GameRecommendationsView.vue'

const catalog = {
  ready: true,
  sourceCount: 162686,
  total: 7543,
  page: 0,
  size: 20,
  totalPages: 378,
  sort: 'rank',
  type: 'all',
  importedAt: '2026-08-07T08:00:00Z',
  sourceDate: '2026-08-07',
  taxonomyTranslated: true,
  games: [{
    bggId: 266192,
    name: '展翅翱翔',
    originalName: 'Wingspan',
    nameLocalized: true,
    publicationYear: 2019,
    overallRank: 34,
    hotRank: 2,
    geekRating: 7.79,
    averageRating: 8.09,
    usersRated: 102030,
    expansion: false,
    types: ['family', 'strategy'],
    detailsAvailable: true,
    thumbnailUrl: 'https://example.test/wingspan.jpg',
    minPlayers: 1,
    maxPlayers: 5,
    playingTimeMinutes: 70,
    averageWeight: 2.5,
    categories: ['动物'],
    mechanics: ['卡牌轮抽'],
    bggUrl: 'https://boardgamegeek.com/boardgame/266192',
  }],
}

describe('GameRecommendationsView', () => {
  beforeEach(() => {
    setLocale('zh-CN')
    vi.stubGlobal('scrollTo', vi.fn())
  })
  afterEach(() => {
    setLocale('zh-CN')
    vi.unstubAllGlobals()
  })

  async function mountView() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/discover', name: 'game-recommendations', component: { template: '<div />' } },
        { path: '/discover/catalog', name: 'game-catalog-browse', component: GameRecommendationsView },
        { path: '/discover/:bggId', name: 'game-discovery', component: { template: '<div />' } },
        { path: '/library', name: 'public-library', component: { template: '<div />' } },
        { path: '/teach', name: 'teach', component: { template: '<div />' } },
        { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
        { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
        { path: '/account', name: 'account', component: { template: '<div />' } },
        { path: '/login', name: 'login', component: { template: '<div />' } },
      ],
    })
    await router.push('/discover/catalog')
    await router.isReady()
    return mount(GameRecommendationsView, { global: { plugins: [router] } })
  }

  it('renders a paginated full snapshot with official localized metadata and the required BGG logo', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json(catalog)))

    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('BGG 收录 162,686 条')
    expect(wrapper.text()).toContain('当前找到 7,543 条')
    expect(wrapper.text()).toContain('展翅翱翔')
    expect(wrapper.text()).toContain('Wingspan')
    expect(wrapper.text()).toContain('动物')
    expect(wrapper.text()).toContain('卡牌轮抽')
    expect(wrapper.get('a[href="/discover/266192"]').attributes('href')).toBe('/discover/266192')
    const attribution = wrapper.get('a[href="https://boardgamegeek.com"]')
    expect(attribution.get('img').attributes('src')).toBe('/powered-by-bgg-rgb.svg')
    expect(attribution.get('img').attributes('alt')).toBe('Powered by BoardGameGeek')
    expect(wrapper.text()).not.toContain('第 1 / 378 页')
    expect((wrapper.findAll('select')[0]!.element as HTMLSelectElement).value).toBe('rank')
  })

  it('paints ranked data before the slower BGG detail hydration finishes', async () => {
    let resolveRich!: (response: Response) => void
    const richResponse = new Promise<Response>(resolve => { resolveRich = resolve })
    const base = {
      ...catalog,
      total: 1,
      totalPages: 1,
      taxonomyTranslated: false,
      games: [{ ...catalog.games[0], name: 'Wingspan', nameLocalized: false, originalName: 'Wingspan', detailsAvailable: false, thumbnailUrl: '', minPlayers: null, maxPlayers: null, playingTimeMinutes: null, averageWeight: null, categories: [], mechanics: [] }],
    }
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) =>
      String(input).includes('enrich=true') ? richResponse : Response.json(base)))

    const wrapper = await mountView()
    await vi.waitFor(() => expect(wrapper.text()).toContain('Wingspan'))

    expect(wrapper.text()).toContain('更多封面和游戏资料正在补齐')
    expect(wrapper.text()).not.toContain('卡牌轮抽')

    resolveRich(Response.json({ ...catalog, total: 1, totalPages: 1 }))
    await flushPromises()
    expect(wrapper.text()).toContain('展翅翱翔')
    expect(wrapper.text()).toContain('卡牌轮抽')
  })

  it('sends rating and BGG type filters to the server-side catalog query', async () => {
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const request = new URL(String(input), 'http://localhost')
      return Response.json({
        ...catalog,
        sort: request.searchParams.get('sort'),
        type: request.searchParams.get('type'),
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountView()
    await flushPromises()

    const selects = wrapper.findAll('select')
    await selects[0]!.setValue('rating')
    await selects[1]!.setValue('strategy')
    await wrapper.get('[data-testid="catalog-filter-form"]').trigger('submit')
    await flushPromises()

    expect(fetchMock.mock.calls.some(([input]) => {
      const url = String(input)
      return url.includes('/api/v1/bgg/catalog?') && url.includes('sort=rating') && url.includes('type=strategy')
    })).toBe(true)
  })

  it('searches the imported full catalog and appends a prefetched next batch', async () => {
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const url = String(input)
      return Response.json(url.includes('page=1') ? {
        ...catalog,
        page: 1,
        games: [{ ...catalog.games[0], bggId: 13, name: 'CATAN', originalName: 'CATAN', nameLocalized: false }],
      } : catalog)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountView()
    await flushPromises()

    await wrapper.get('input[type="search"]').setValue('Wingspan')
    await wrapper.get('form[role="search"]').trigger('submit')
    await flushPromises()
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('q=Wingspan'))).toBe(true)

    await wrapper.get('nav button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('CATAN')
    expect(wrapper.text()).toContain('已展示 2 款')
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('page=1') && String(input).includes('enrich=false'))).toBe(true)
  })

  it('reuses an in-flight next-page prefetch and keeps enrichment for every visible page', async () => {
    let resolvePageOne!: (response: Response) => void
    const pageOneResponse = new Promise<Response>(resolve => { resolvePageOne = resolve })
    let resolvePageZeroRich!: (response: Response) => void
    const pageZeroRich = new Promise<Response>(resolve => { resolvePageZeroRich = resolve })
    let resolvePageOneRich!: (response: Response) => void
    const pageOneRich = new Promise<Response>(resolve => { resolvePageOneRich = resolve })
    const pageOneGame = { ...catalog.games[0], bggId: 13, name: 'Page One Base', originalName: 'Page One Base', nameLocalized: false }
    const fetchMock = vi.fn((input: string | URL | Request) => {
      const url = String(input)
      if (url.includes('page=1') && url.includes('enrich=true')) return pageOneRich
      if (url.includes('page=0') && url.includes('enrich=true')) return pageZeroRich
      if (url.includes('page=1') && url.includes('enrich=false')) return pageOneResponse
      return Promise.resolve(Response.json({ ...catalog, sort: 'rank', total: 2, totalPages: 2 }))
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = await mountView()
    await flushPromises()
    await wrapper.get('nav button').trigger('click')
    await flushPromises()

    expect(fetchMock.mock.calls.filter(([input]) =>
      String(input).includes('page=1') && String(input).includes('enrich=false'))).toHaveLength(1)
    resolvePageOne(Response.json({ ...catalog, sort: 'rank', total: 2, totalPages: 2, page: 1, games: [pageOneGame] }))
    await flushPromises()
    expect(wrapper.text()).toContain('Page One Base')

    resolvePageZeroRich(Response.json({
      ...catalog,
      sort: 'rank',
      total: 2,
      totalPages: 2,
      games: [{ ...catalog.games[0], name: '第一页已补齐' }],
    }))
    await flushPromises()
    expect(wrapper.text()).toContain('第一页已补齐')
    expect(wrapper.text()).toContain('Page One Base')

    resolvePageOneRich(Response.json({
      ...catalog,
      sort: 'rank',
      total: 2,
      totalPages: 2,
      page: 1,
      games: [{ ...pageOneGame, name: '第二页已补齐' }],
    }))
    await flushPromises()
    expect(wrapper.text()).toContain('第一页已补齐')
    expect(wrapper.text()).toContain('第二页已补齐')
  })

  it('aborts old query work and clears old cards when a replacement query fails', async () => {
    let resolveOldRich!: (response: Response) => void
    const oldRich = new Promise<Response>(resolve => { resolveOldRich = resolve })
    let oldRichSignal: AbortSignal | undefined
    let oldPrefetchSignal: AbortSignal | undefined
    const fetchMock = vi.fn((input: string | URL | Request, options?: RequestInit) => {
      const url = String(input)
      if (url.includes('enrich=true') && !url.includes('q=missing')) {
        oldRichSignal = options?.signal ?? undefined
        return oldRich
      }
      if (url.includes('page=1') && url.includes('enrich=false') && !url.includes('q=missing')) {
        oldPrefetchSignal = options?.signal ?? undefined
        return new Promise<Response>(() => undefined)
      }
      if (url.includes('q=missing') && url.includes('enrich=false')) return Promise.resolve(new Response(null, { status: 503 }))
      return Promise.resolve(Response.json({ ...catalog, sort: 'rank', total: 2, totalPages: 2 }))
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = await mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('展翅翱翔')

    await wrapper.get('input[type="search"]').setValue('missing')
    await wrapper.get('form[role="search"]').trigger('submit')
    await flushPromises()
    expect(oldRichSignal?.aborted).toBe(true)
    expect(oldPrefetchSignal?.aborted).toBe(true)
    expect(wrapper.text()).toContain('桌游目录暂时打不开')
    expect(wrapper.text()).not.toContain('展翅翱翔')

    resolveOldRich(Response.json(catalog))
    await flushPromises()
    expect(wrapper.text()).not.toContain('展翅翱翔')
  })

  it('rejects a base response whose sort or type does not match the captured query', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ ...catalog, sort: 'rating' })))

    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('桌游目录暂时打不开')
    expect(wrapper.text()).not.toContain('展翅翱翔')
  })

  it('preserves current cards and offers an explicit retry after append failure', async () => {
    let pageOneAttempts = 0
    const pageOneGame = { ...catalog.games[0], bggId: 13, name: '重试后的游戏', originalName: 'Retried Game', nameLocalized: true }
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const url = String(input)
      if (url.includes('page=1') && url.includes('enrich=false')) {
        pageOneAttempts += 1
        if (pageOneAttempts <= 2) return new Response(null, { status: 503 })
        return Response.json({ ...catalog, sort: 'rank', page: 1, total: 2, totalPages: 2, games: [pageOneGame] })
      }
      return Response.json({ ...catalog, sort: 'rank', total: 2, totalPages: 2 })
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = await mountView()
    await flushPromises()
    await wrapper.get('nav button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('展翅翱翔')
    expect(wrapper.text()).toContain('下一批暂时没取到')
    await wrapper.findAll('button').find(button => button.text() === '重试下一批')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('重试后的游戏')
    expect(wrapper.text()).not.toContain('下一批暂时没取到')
  })

  it('aborts a pending base request when the view unmounts', async () => {
    let baseSignal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((_input: string | URL | Request, options?: RequestInit) => {
      baseSignal = options?.signal ?? undefined
      return new Promise<Response>(() => undefined)
    }))

    const wrapper = await mountView()
    await flushPromises()
    wrapper.unmount()
    expect(baseSignal?.aborted).toBe(true)
  })

  it('aborts progressive enrichment and prefetch transport when the view unmounts', async () => {
    const pending = new Promise<Response>(() => undefined)
    const signals: AbortSignal[] = []
    const fetchMock = vi.fn((input: string | URL | Request, options?: RequestInit) => {
      const url = String(input)
      const signal = options?.signal
      if (signal) signals.push(signal)
      if (url.includes('enrich=false') && url.includes('page=0')) {
        return Promise.resolve(Response.json({ ...catalog, sort: 'rank', total: 2, totalPages: 2 }))
      }
      return pending
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = await mountView()
    await flushPromises()
    expect(signals).toHaveLength(3)
    wrapper.unmount()
    expect(signals[0]!.aborted).toBe(false)
    expect(signals.slice(1).every(signal => signal.aborted)).toBe(true)
  })

  it('states clearly when the official full snapshot has not been imported', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ ...catalog, ready: false, sourceCount: 0, total: 0, totalPages: 0, games: [] })))
    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('桌游目录还在准备')
    expect(wrapper.text()).toContain('推荐对话、个人游戏和规则书功能')
  })

  it('shows a retryable error without hiding full-catalog search', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 503 })))
    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('桌游目录暂时打不开')
    expect(wrapper.get('input[type="search"]').attributes('type')).toBe('search')
  })
})
