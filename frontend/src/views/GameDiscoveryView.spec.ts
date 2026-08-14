import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { LOGIN_REQUIRED_EVENT } from '@/lib/authSession'
import { setLocale } from '@/lib/locale'

import GameDiscoveryView from './GameDiscoveryView.vue'

const details = {
  bggId: 42,
  name: '目录游戏',
  originalName: 'Catalog Game',
  officialNameLocalized: true,
  description: '一款从推荐中选出的游戏。',
  descriptionTranslated: true,
  thumbnailUrl: 'https://example.test/catalog-cover.jpg',
  publicationYear: 2024,
  minPlayers: 1,
  maxPlayers: 5,
  playingTimeMinutes: 60,
  minimumAge: 10,
  imageUrl: 'https://example.test/catalog-cover-large.jpg',
  averageRating: 7.8,
  averageWeight: 2.4,
  categories: ['策略'],
  categoriesTranslated: true,
  mechanics: ['轮抽'],
  mechanicsTranslated: true,
  designers: ['Designer Name'],
  publishers: ['Publisher Name'],
  editionImages: [{
    versionId: 7,
    name: 'Simplified Chinese edition',
    imageUrl: 'https://example.test/chinese-edition.jpg',
    publicationYear: 2025,
    languages: ['Chinese'],
  }],
  bggUrl: 'https://boardgamegeek.com/boardgame/42',
}

describe('GameDiscoveryView', () => {
  afterEach(() => {
    setLocale('zh-CN')
    vi.unstubAllGlobals()
  })

  it('inspects attributed BGG details without mutating the catalog', async () => {
    const fetchMock = vi.fn(async (input: string | URL | Request, _options?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/v1/bgg/games/42')) return Response.json(details)
      return new Response(null, { status: 401 })
    })
    vi.stubGlobal('fetch', fetchMock)

    const { wrapper } = await mountDiscovery()
    await flushPromises()

    expect(wrapper.text()).toContain('目录游戏')
    expect(wrapper.text()).toContain('Catalog Game · BGG 版本资料收录的官方中文名')
    expect(wrapper.text()).toContain('译自 BGG 原文')
    expect(wrapper.text()).toContain('机制 · 中文对照')
    expect(wrapper.text()).toContain('类别 · 中文对照')
    expect(wrapper.text()).toContain('轮抽')
    expect(wrapper.text()).toContain('策略')
    expect(wrapper.text()).toContain('BGG 资料仅用于推荐、识别游戏和展示封面')
    expect(wrapper.text()).toContain('Designer Name')
    expect(wrapper.text()).toContain('Publisher Name')
    expect(wrapper.text()).toContain('BGG 版本图片')
    expect(wrapper.text()).toContain('Simplified Chinese edition')
    expect(wrapper.text()).toContain('BGG 社区文件（用户上传，非官方）')
    expect(wrapper.text()).toContain('BGG 评分 7.8')
    expect(wrapper.get('a[href="https://boardgamegeek.com/boardgame/42"]').attributes('target')).toBe('_blank')
    expect(wrapper.get('a[href="https://boardgamegeek.com/boardgame/42/images"]').attributes('target')).toBe('_blank')
    expect(wrapper.get('a[href="https://boardgamegeek.com/boardgame/42/files"]').attributes('target')).toBe('_blank')
    expect(fetchMock.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false)
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/bgg/games/42?locale=zh-CN&translate=false', {
      credentials: 'include', signal: expect.any(AbortSignal),
    })
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/bgg/games/42?locale=zh-CN&translate=true', {
      credentials: 'include', signal: expect.any(AbortSignal),
    })
    expect(wrapper.get('p.whitespace-pre-line').classes()).not.toContain('line-clamp-6')
    expect(wrapper.get('[data-testid="game-cover-column"]').classes()).toContain('self-start')
    expect(wrapper.get('[data-testid="game-cover-column"]').classes()).toContain('game-detail-cover')
    expect(wrapper.get('[data-testid="game-cover-column"]').classes()).not.toContain('lg:sticky')
    expect(wrapper.get('[data-testid="game-cover-column"]').classes()).not.toContain('lg:top-24')
    expect(wrapper.get('[data-testid="game-detail-hero"]').classes()).toContain('game-detail-hero')
    expect(wrapper.get('[data-testid="game-detail-identity"]').classes()).toContain('game-detail-identity')
    expect(wrapper.get('[data-testid="game-detail-stats"]').classes()).toContain('game-detail-stats')
    expect(wrapper.get('[data-testid="game-detail-actions"]').classes()).toContain('game-detail-actions')
    expect(wrapper.get('[data-testid="game-long-details"]').classes()).toContain('min-w-0')
    expect(wrapper.get('[data-testid="game-long-details"]').classes()).not.toContain('lg:col-span-2')
    expect(wrapper.findAll('img[alt="Powered by BoardGameGeek"]').length).toBeGreaterThanOrEqual(1)
  })

  it('requests and displays the original description for the English locale', async () => {
    setLocale('en')
    const fetchMock = vi.fn(async () => Response.json({
      ...details,
      name: 'Catalog Game',
      originalName: 'Catalog Game',
      officialNameLocalized: false,
      description: 'A game selected from recommendations.',
      descriptionTranslated: false,
      categories: ['Strategy'],
      categoriesTranslated: false,
      mechanics: ['Drafting'],
      mechanicsTranslated: false,
    }))
    vi.stubGlobal('fetch', fetchMock)

    const { wrapper } = await mountDiscovery()
    await flushPromises()

    expect(wrapper.text()).toContain('A game selected from recommendations.')
    expect(wrapper.text()).toContain('Strategy')
    expect(wrapper.text()).toContain('Drafting')
    expect(wrapper.text()).not.toContain('Official Chinese name')
    expect(wrapper.text()).not.toContain('中文对照')
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/bgg/games/42?locale=en&translate=false', {
      credentials: 'include', signal: expect.any(AbortSignal),
    })
  })

  it('reloads a reused discovery route and ignores a delayed response from the previous game', async () => {
    let resolveOldDetails!: (response: Response) => void
    const oldDetails = new Promise<Response>(resolve => { resolveOldDetails = resolve })
    let oldSignal: AbortSignal | undefined
    const nextDetails = {
      ...details,
      bggId: 43,
      name: '新路由游戏',
      originalName: 'Next Route Game',
      description: '新路由的资料。',
      bggUrl: 'https://boardgamegeek.com/boardgame/43',
    }
    const fetchMock = vi.fn((input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path.includes('/games/42') && path.includes('translate=false')) {
        oldSignal = options?.signal ?? undefined
        return oldDetails
      }
      if (path.includes('/games/43')) return Promise.resolve(Response.json(nextDetails))
      return Promise.resolve(new Response(null, { status: 404 }))
    })
    vi.stubGlobal('fetch', fetchMock)

    const { wrapper, router } = await mountDiscovery()
    await flushPromises()
    await router.push('/discover/43')
    await flushPromises()

    expect(oldSignal?.aborted).toBe(true)
    expect(wrapper.text()).toContain('新路由游戏')
    expect(wrapper.text()).not.toContain('目录游戏')

    resolveOldDetails(Response.json(details))
    await flushPromises()

    expect(wrapper.text()).toContain('新路由游戏')
    expect(wrapper.text()).not.toContain('目录游戏')
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/games/43?locale=zh-CN&translate=false'))).toBe(true)
  })

  it('keeps the current game usable when an aborted previous request fails late', async () => {
    let rejectOldDetails!: (error: Error) => void
    const oldDetails = new Promise<Response>((_resolve, reject) => { rejectOldDetails = reject })
    const nextDetails = {
      ...details,
      bggId: 43,
      name: '当前游戏',
      originalName: 'Current Game',
      bggUrl: 'https://boardgamegeek.com/boardgame/43',
    }
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/games/42') && path.includes('translate=false')) return oldDetails
      if (path.includes('/games/43')) return Promise.resolve(Response.json(nextDetails))
      return Promise.resolve(new Response(null, { status: 404 }))
    }))

    const { wrapper, router } = await mountDiscovery()
    await flushPromises()
    await router.push('/discover/43')
    await flushPromises()
    rejectOldDetails(new Error('late old failure'))
    await flushPromises()

    expect(wrapper.text()).toContain('当前游戏')
    expect(wrapper.text()).not.toContain('late old failure')
    expect(wrapper.get('[data-testid="game-detail-hero"]').isVisible()).toBe(true)
  })

  it('does not let delayed Chinese localization replace a newer English request', async () => {
    let resolveLocalized!: (response: Response) => void
    const localized = new Promise<Response>(resolve => { resolveLocalized = resolve })
    let localizedSignal: AbortSignal | undefined
    const englishDetails = {
      ...details,
      name: 'Current English Game',
      originalName: 'Current English Game',
      officialNameLocalized: false,
      description: 'Current English details.',
      descriptionTranslated: false,
    }
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path.includes('locale=zh-CN') && path.includes('translate=true')) {
        localizedSignal = options?.signal ?? undefined
        return localized
      }
      if (path.includes('locale=en')) return Promise.resolve(Response.json(englishDetails))
      return Promise.resolve(Response.json({ ...details, name: 'Source Game', description: 'Source details.' }))
    }))

    const { wrapper } = await mountDiscovery()
    await flushPromises()
    expect(wrapper.text()).toContain('Source Game')

    setLocale('en')
    await flushPromises()
    expect(localizedSignal?.aborted).toBe(true)
    expect(wrapper.text()).toContain('Current English Game')

    resolveLocalized(Response.json(details))
    await flushPromises()
    expect(wrapper.text()).toContain('Current English Game')
    expect(wrapper.text()).not.toContain('目录游戏')
  })

  it('idempotently selects the game and hands its edition to rulebook acquisition', async () => {
    const fetchMock = vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/v1/bgg/games/42/import') && options?.method === 'POST') {
        return Response.json({
          game: { id: 'game-1', name: 'Catalog Game' },
          edition: { id: 'edition-1', name: 'BGG 基础版' },
          alreadyImported: true,
        })
      }
      if (path.includes('/api/v1/bgg/games/42')) return Response.json(details)
      if (path.includes('/api/auth/csrf')) return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      return new Response(null, { status: 401 })
    })
    vi.stubGlobal('fetch', fetchMock)

    const { wrapper, router } = await mountDiscovery()
    await flushPromises()
    const selectButton = wrapper.findAll('button').find(button => button.text().includes('选择这款桌游'))!
    await selectButton.trigger('click')
    await selectButton.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('teach')
    expect(router.currentRoute.value.query).toEqual({ editionId: 'edition-1', onboarding: 'selected-game' })
    expect(fetchMock.mock.calls.filter(([input, options]) =>
      String(input).includes('/api/v1/bgg/games/42/import')
        && options?.method === 'POST'
        && (options.headers as Record<string, string>)['X-CSRF-TOKEN'] === 'csrf')).toHaveLength(1)
  })

  it('keeps the selected route and asks for login when selection is anonymous', async () => {
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      if (String(input).includes('/api/v1/bgg/games/42')) return Response.json(details)
      return new Response(null, { status: 401 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const loginRequired = vi.fn()
    window.addEventListener(LOGIN_REQUIRED_EVENT, loginRequired, { once: true })

    const { wrapper, router } = await mountDiscovery()
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text().includes('选择这款桌游'))!.trigger('click')
    await flushPromises()

    expect(loginRequired).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.fullPath).toBe('/discover/42')
    expect(wrapper.text()).toContain('当前选择不会丢失')
  })

  it('cancels a pending selection when the route changes before CSRF resolves', async () => {
    let resolveCsrf!: (response: Response) => void
    const csrf = new Promise<Response>(resolve => { resolveCsrf = resolve })
    let csrfSignal: AbortSignal | undefined
    const nextDetails = {
      ...details,
      bggId: 43,
      name: '另一款游戏',
      originalName: 'Another Game',
      bggUrl: 'https://boardgamegeek.com/boardgame/43',
    }
    const fetchMock = vi.fn((input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path === '/api/auth/csrf') {
        csrfSignal = options?.signal ?? undefined
        return csrf
      }
      if (path.includes('/games/43')) return Promise.resolve(Response.json(nextDetails))
      if (path.includes('/games/42')) return Promise.resolve(Response.json(details))
      return Promise.resolve(new Response(null, { status: 404 }))
    })
    vi.stubGlobal('fetch', fetchMock)

    const { wrapper, router } = await mountDiscovery()
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text().includes('选择这款桌游'))!.trigger('click')
    await flushPromises()
    await router.push('/discover/43')
    await flushPromises()

    expect(csrfSignal?.aborted).toBe(true)
    resolveCsrf(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/discover/43')
    expect(wrapper.text()).toContain('另一款游戏')
    expect(fetchMock.mock.calls.some(([input, options]) =>
      String(input).includes('/import') && options?.method === 'POST')).toBe(false)
  })

  it('aborts pending localization and selection work when the view unmounts', async () => {
    let resolveLocalized!: (response: Response) => void
    const localized = new Promise<Response>(resolve => { resolveLocalized = resolve })
    let resolveCsrf!: (response: Response) => void
    const csrf = new Promise<Response>(resolve => { resolveCsrf = resolve })
    let localizedSignal: AbortSignal | undefined
    let csrfSignal: AbortSignal | undefined
    const fetchMock = vi.fn((input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path.includes('translate=true')) {
        localizedSignal = options?.signal ?? undefined
        return localized
      }
      if (path.includes('translate=false')) return Promise.resolve(Response.json(details))
      if (path === '/api/auth/csrf') {
        csrfSignal = options?.signal ?? undefined
        return csrf
      }
      return Promise.resolve(new Response(null, { status: 404 }))
    })
    vi.stubGlobal('fetch', fetchMock)

    const { wrapper } = await mountDiscovery()
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text().includes('选择这款桌游'))!.trigger('click')
    await flushPromises()
    wrapper.unmount()

    expect(localizedSignal?.aborted).toBe(true)
    expect(csrfSignal?.aborted).toBe(true)

    resolveLocalized(Response.json(details))
    resolveCsrf(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
    await flushPromises()
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/import'))).toBe(false)
  })
})

async function mountDiscovery() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/discover/:bggId', name: 'game-discovery', component: GameDiscoveryView },
      { path: '/teach', name: 'teach', component: { template: '<div />' } },
      { path: '/library', name: 'public-library', component: { template: '<div />' } },
      { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
      { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
      { path: '/account', name: 'account', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.push('/discover/42')
  await router.isReady()
  return { wrapper: mount(GameDiscoveryView, { global: { plugins: [router] } }), router }
}
