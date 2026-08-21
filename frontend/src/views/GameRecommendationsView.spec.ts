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
  let router: ReturnType<typeof createRouter>

  beforeEach(() => {
    setLocale('zh-CN')
    vi.stubGlobal('scrollTo', vi.fn())
  })
  afterEach(() => {
    setLocale('zh-CN')
    vi.unstubAllGlobals()
  })

  async function mountView(initialRoute = '/discover/catalog') {
    router = createRouter({
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
    await router.push(initialRoute)
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
    expect(wrapper.text()).not.toContain('动物')
    expect(wrapper.text()).not.toContain('卡牌轮抽')
    expect(wrapper.get('a[href="/discover/266192"]').attributes('href')).toBe('/discover/266192')
    const attribution = wrapper.get('a[href="https://boardgamegeek.com"]')
    expect(attribution.get('img').attributes('src')).toBe('/powered-by-bgg-rgb.svg')
    expect(attribution.get('img').attributes('alt')).toBe('Powered by BoardGameGeek')
    expect(wrapper.text()).toContain('第 1 / 378 页')
    expect(wrapper.get('[data-testid="catalog-pagination"]').attributes('aria-label')).toBe('桌游目录分页')
    expect(wrapper.get('[data-testid="catalog-page-1"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('[data-testid="catalog-page-2"]').text()).toBe('2')
    expect(wrapper.get('[data-testid="catalog-page-378"]').text()).toBe('378')
    expect((wrapper.findAll('select')[0]!.element as HTMLSelectElement).value).toBe('rank')
  })

  it('renders stored BGG details in one non-blocking catalog request', async () => {
    const fetchMock = vi.fn(async (_input: string | URL | Request) => Response.json({ ...catalog, total: 1, totalPages: 1 }))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('展翅翱翔')
    expect(wrapper.text()).toContain('1–5 人')
    expect(wrapper.text()).not.toContain('卡牌轮抽')
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('enrich=true'))).toBe(false)
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('enrich=false'))).toBe(true)
  })

  it('paints catalog text before asynchronously filling missing covers', async () => {
    let resolveCovers!: (response: Response) => void
    const coversResponse = new Promise<Response>(resolve => { resolveCovers = resolve })
    const fetchMock = vi.fn((input: string | URL | Request) => {
      const url = String(input)
      if (url.includes('/api/v1/bgg/catalog/covers?')) return coversResponse
      return Promise.resolve(Response.json({
        ...catalog,
        total: 1,
        totalPages: 1,
        games: [{ ...catalog.games[0], thumbnailUrl: '' }],
      }))
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('展翅翱翔')
    expect(wrapper.find('img[alt="展翅翱翔 的 BGG 封面"]').exists()).toBe(false)
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('bggId=266192'))).toBe(true)

    resolveCovers(Response.json([{ bggId: 266192, thumbnailUrl: 'https://example.test/fresh-cover.jpg', imageUrl: '' }]))
    await flushPromises()

    expect(wrapper.get('img[alt="展翅翱翔 的 BGG 封面"]').attributes('src')).toBe('https://example.test/fresh-cover.jpg')
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

  it('searches the imported full catalog and navigates to a prefetched numbered page', async () => {
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

    await wrapper.get('[data-testid="catalog-page-2"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('CATAN')
    expect(wrapper.text()).not.toContain('展翅翱翔')
    expect(wrapper.text()).toContain('第 2 / 378 页')
    expect(wrapper.text()).toContain('本页 1 款')
    expect(router.currentRoute.value.query).toMatchObject({ q: 'Wingspan', page: '2' })
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('page=1') && String(input).includes('enrich=false'))).toBe(true)
  })

  it('restores filters, a Chinese title query, and the selected page from the URL', async () => {
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const request = new URL(String(input), 'http://localhost')
      return Response.json({
        ...catalog,
        page: Number(request.searchParams.get('page')),
        sort: request.searchParams.get('sort'),
        type: request.searchParams.get('type'),
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = await mountView('/discover/catalog?sort=rating&type=strategy&q=%E8%8C%B6%E5%9B%AD&page=3')
    await flushPromises()

    expect((wrapper.get('input[type="search"]').element as HTMLInputElement).value).toBe('茶园')
    expect((wrapper.findAll('select')[0]!.element as HTMLSelectElement).value).toBe('rating')
    expect((wrapper.findAll('select')[1]!.element as HTMLSelectElement).value).toBe('strategy')
    expect(wrapper.text()).toContain('第 3 / 378 页')
    expect(fetchMock.mock.calls.some(([input]) => {
      const url = String(input)
      return url.includes('q=%E8%8C%B6%E5%9B%AD') && url.includes('page=2')
    })).toBe(true)
  })

  it('lets the catalog decide whether a one-character title query is meaningful', async () => {
    const fetchMock = vi.fn(async (_input: string | URL | Request) => Response.json(catalog))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountView()
    await flushPromises()

    await wrapper.get('input[type="search"]').setValue('翼')
    await wrapper.get('form[role="search"]').trigger('submit')
    await flushPromises()

    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('q=%E7%BF%BC'))).toBe(true)
  })

  it('reuses an in-flight next-page prefetch without starting a remote enrichment request', async () => {
    let resolvePageOne!: (response: Response) => void
    const pageOneResponse = new Promise<Response>(resolve => { resolvePageOne = resolve })
    const pageOneGame = { ...catalog.games[0], bggId: 13, name: 'Page One Base', originalName: 'Page One Base', nameLocalized: false }
    const fetchMock = vi.fn((input: string | URL | Request) => {
      const url = String(input)
      if (url.includes('page=1') && url.includes('enrich=false')) return pageOneResponse
      return Promise.resolve(Response.json({ ...catalog, sort: 'rank', total: 2, totalPages: 2 }))
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = await mountView()
    await flushPromises()
    await wrapper.get('[data-testid="catalog-page-2"]').trigger('click')
    await flushPromises()

    expect(fetchMock.mock.calls.filter(([input]) =>
      String(input).includes('page=1') && String(input).includes('enrich=false'))).toHaveLength(1)
    resolvePageOne(Response.json({ ...catalog, sort: 'rank', total: 2, totalPages: 2, page: 1, games: [pageOneGame] }))
    await flushPromises()
    expect(wrapper.text()).toContain('Page One Base')
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('enrich=true'))).toBe(false)
  })

  it('aborts old query work and clears old cards when a replacement query fails', async () => {
    let oldPrefetchSignal: AbortSignal | undefined
    const fetchMock = vi.fn((input: string | URL | Request, options?: RequestInit) => {
      const url = String(input)
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
    expect(oldPrefetchSignal?.aborted).toBe(true)
    expect(wrapper.text()).toContain('桌游目录暂时打不开')
    expect(wrapper.text()).not.toContain('展翅翱翔')
  })

  it('rejects a base response whose sort or type does not match the captured query', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ ...catalog, sort: 'rating' })))

    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('桌游目录暂时打不开')
    expect(wrapper.text()).not.toContain('展翅翱翔')
  })

  it('preserves current cards and offers an explicit retry after page navigation fails', async () => {
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
    await wrapper.get('[data-testid="catalog-page-2"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('展翅翱翔')
    expect(wrapper.text()).toContain('这一页暂时没取到，当前页仍然保留')
    await wrapper.findAll('button').find(button => button.text() === '重试这一页')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('重试后的游戏')
    expect(wrapper.text()).not.toContain('展翅翱翔')
    expect(wrapper.text()).not.toContain('这一页暂时没取到')
    expect(wrapper.text()).toContain('第 2 / 2 页')
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

  it('aborts outstanding page prefetch transport when the view unmounts', async () => {
    const pending = new Promise<Response>(() => undefined)
    const requests: Array<{ url: string; signal: AbortSignal }> = []
    const fetchMock = vi.fn((input: string | URL | Request, options?: RequestInit) => {
      const url = String(input)
      const signal = options?.signal
      if (signal) requests.push({ url, signal })
      if (url.includes('enrich=false') && url.includes('page=0')) {
        return Promise.resolve(Response.json({ ...catalog, sort: 'rank', total: 2, totalPages: 2 }))
      }
      return pending
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = await mountView()
    await vi.waitFor(() => expect(requests.filter(({ url }) => url.includes('/api/v1/bgg/catalog'))).toHaveLength(2))
    wrapper.unmount()

    const baseRequest = requests.find(({ url }) => url.includes('enrich=false') && url.includes('page=0'))!
    const outstandingRequests = requests.filter(({ url }) =>
      url.includes('/api/auth/session') || url.includes('page=1'))
    expect(baseRequest.signal.aborted).toBe(false)
    expect(outstandingRequests).toHaveLength(2)
    expect(outstandingRequests.every(({ signal }) => signal.aborted)).toBe(true)
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
