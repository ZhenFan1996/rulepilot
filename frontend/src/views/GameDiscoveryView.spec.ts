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
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/bgg/games/42?locale=zh-CN&translate=false', { credentials: 'include' })
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/bgg/games/42?locale=zh-CN&translate=true', { credentials: 'include' })
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
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/bgg/games/42?locale=en&translate=false', { credentials: 'include' })
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
    await wrapper.findAll('button').find(button => button.text().includes('选择这款桌游'))!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('teach')
    expect(router.currentRoute.value.query).toEqual({ editionId: 'edition-1', onboarding: 'selected-game' })
    expect(fetchMock.mock.calls.some(([input, options]) =>
      String(input).includes('/api/v1/bgg/games/42/import')
        && options?.method === 'POST'
        && (options.headers as Record<string, string>)['X-CSRF-TOKEN'] === 'csrf')).toBe(true)
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
