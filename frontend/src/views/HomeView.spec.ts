import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { setLocale } from '@/lib/locale'
import HomeView from './HomeView.vue'

enableAutoUnmount(afterEach)

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
    setLocale('zh-CN')
    vi.unstubAllGlobals()
    document.documentElement.classList.remove('dark', 'light')
  })

  beforeEach(() => setLocale('zh-CN'))

  it('keeps the concrete first actions available before optional discovery data resolves', async () => {
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return Promise.resolve(new Response(null, { status: 401 }))
      if (path.includes('/api/v1/bgg/recommendations')) return new Promise<Response>(() => undefined)
      return Promise.resolve(new Response(null, { status: 404 }))
    }))

    const wrapper = await mountHome()

    expect(wrapper.get('a[href="/teach"].home-primary-action')).toBeTruthy()
    expect(wrapper.get('a[href="/discover"]')).toBeTruthy()
    expect(wrapper.text()).not.toContain('Agent')
  })

  it('paints BGG cards before session resolves and never reloads session for locale', async () => {
    let resolveSession!: (response: Response) => void
    const sessionResponse = new Promise<Response>(resolve => { resolveSession = resolve })
    const englishGames = hotGames.map((game, index) => ({
      ...game,
      name: `English Game ${index + 1}`,
      originalName: `English Game ${index + 1}`,
      nameLocalized: false,
    }))
    const fetchMock = vi.fn((input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return sessionResponse
      if (path.includes('locale=en')) return Promise.resolve(Response.json(englishGames))
      if (path.includes('/api/v1/bgg/recommendations')) return Promise.resolve(Response.json(hotGames))
      return Promise.resolve(new Response(null, { status: 404 }))
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = await mountHome()
    await vi.waitFor(() => expect(wrapper.text()).toContain('展翅翱翔'))
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/auth/session'))).toHaveLength(1)

    setLocale('en')
    await vi.waitFor(() => expect(wrapper.text()).toContain('English Game 1'))
    expect(wrapper.text()).not.toContain('展翅翱翔')
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/auth/session'))).toHaveLength(1)

    resolveSession(Response.json({ username: ' player ', roles: ['USER'] }))
    await vi.waitFor(() => expect(wrapper.find('a[aria-label="player"]').exists()).toBe(true))
  })

  it('aborts the shell session request when Home unmounts', async () => {
    let sessionSignal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) {
        sessionSignal = options?.signal ?? undefined
        return new Promise<Response>(() => undefined)
      }
      if (path.includes('/api/v1/bgg/recommendations')) return Promise.resolve(Response.json(hotGames))
      return Promise.resolve(new Response(null, { status: 404 }))
    }))

    const wrapper = await mountHome()
    await vi.waitFor(() => expect(sessionSignal).toBeDefined())
    wrapper.unmount()

    expect(sessionSignal?.aborted).toBe(true)
  })

  it('aborts an old locale request and ignores its delayed success', async () => {
    let resolveChinese!: (response: Response) => void
    const chineseResponse = new Promise<Response>(resolve => { resolveChinese = resolve })
    let chineseSignal: AbortSignal | undefined
    const englishGames = hotGames.map(game => ({ ...game, name: `Current ${game.name}`, nameLocalized: false }))
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return Promise.resolve(new Response(null, { status: 401 }))
      if (path.includes('locale=zh-CN')) {
        chineseSignal = options?.signal ?? undefined
        return chineseResponse
      }
      if (path.includes('locale=en')) return Promise.resolve(Response.json(englishGames))
      return Promise.resolve(new Response(null, { status: 404 }))
    }))

    const wrapper = await mountHome()
    await vi.waitFor(() => expect(chineseSignal).toBeDefined())
    setLocale('en')
    await vi.waitFor(() => expect(wrapper.text()).toContain('Current 展翅翱翔'))
    expect(chineseSignal?.aborted).toBe(true)

    resolveChinese(Response.json(hotGames))
    await flushPromises()
    expect(wrapper.findAll('.home-game-card__title')[0]!.text()).toBe('Current 展翅翱翔')
  })

  it('keeps a delayed old-locale failure from replacing current cards', async () => {
    let resolveChinese!: (response: Response) => void
    const chineseResponse = new Promise<Response>(resolve => { resolveChinese = resolve })
    const englishGames = hotGames.map(game => ({ ...game, name: `Current ${game.name}`, nameLocalized: false }))
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return Promise.resolve(new Response(null, { status: 401 }))
      if (path.includes('locale=zh-CN')) return chineseResponse
      if (path.includes('locale=en')) return Promise.resolve(Response.json(englishGames))
      return Promise.resolve(new Response(null, { status: 404 }))
    }))

    const wrapper = await mountHome()
    await flushPromises()
    setLocale('en')
    await vi.waitFor(() => expect(wrapper.text()).toContain('Current 展翅翱翔'))

    resolveChinese(new Response(null, { status: 503 }))
    await flushPromises()
    expect(wrapper.findAll('.home-game-card__title')[0]!.text()).toBe('Current 展翅翱翔')
    expect(wrapper.text()).not.toContain('Reload trending games')
  })

  it('retries only failed BGG discovery while preserving the shell session', async () => {
    let gameAttempts = 0
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return new Response(null, { status: 401 })
      if (path.includes('/api/v1/bgg/recommendations')) {
        gameAttempts += 1
        return gameAttempts === 1 ? new Response(null, { status: 503 }) : Response.json(hotGames)
      }
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = await mountHome()
    await vi.waitFor(() => expect(wrapper.text()).toContain('重试'))
    await wrapper.findAll('button').find(button => button.text() === '重试')!.trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('展翅翱翔'))

    expect(gameAttempts).toBe(2)
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/auth/session'))).toHaveLength(1)
  })

  it('distinguishes a valid empty BGG result from a retryable failure', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) =>
      String(input).includes('/api/v1/bgg/recommendations')
        ? Response.json([])
        : new Response(null, { status: 401 })))

    const wrapper = await mountHome()
    await vi.waitFor(() => expect(wrapper.text()).toContain('热门榜暂时没有可展示封面的桌游'))

    expect(wrapper.findAll('button').some(button => button.text() === '重试')).toBe(false)
  })

  it('aborts a pending BGG request when Home unmounts', async () => {
    let gamesSignal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return Promise.resolve(new Response(null, { status: 401 }))
      if (path.includes('/api/v1/bgg/recommendations')) {
        gamesSignal = options?.signal ?? undefined
        return new Promise<Response>(() => undefined)
      }
      return Promise.resolve(new Response(null, { status: 404 }))
    }))

    const wrapper = await mountHome()
    await vi.waitFor(() => expect(gamesSignal).toBeDefined())
    wrapper.unmount()

    expect(gamesSignal?.aborted).toBe(true)
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
