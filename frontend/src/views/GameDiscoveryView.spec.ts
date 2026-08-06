import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { LOGIN_REQUIRED_EVENT } from '@/lib/authSession'

import GameDiscoveryView from './GameDiscoveryView.vue'

const details = {
  bggId: 42,
  name: 'Catalog Game',
  description: 'A game selected from recommendations.',
  thumbnailUrl: 'https://example.test/catalog-cover.jpg',
  publicationYear: 2024,
  minPlayers: 1,
  maxPlayers: 5,
  playingTimeMinutes: 60,
  minimumAge: 10,
  bggUrl: 'https://boardgamegeek.com/boardgame/42',
}

describe('GameDiscoveryView', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('inspects attributed BGG details without mutating the catalog', async () => {
    const fetchMock = vi.fn(async (input: string | URL | Request, _options?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/v1/bgg/games/42')) return Response.json(details)
      return new Response(null, { status: 401 })
    })
    vi.stubGlobal('fetch', fetchMock)

    const { wrapper } = await mountDiscovery()
    await flushPromises()

    expect(wrapper.text()).toContain('Catalog Game')
    expect(wrapper.text()).toContain('BGG 资料仅用于推荐、识别游戏和展示封面')
    expect(wrapper.get('a[href="https://boardgamegeek.com/boardgame/42"]').attributes('target')).toBe('_blank')
    expect(fetchMock.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false)
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
