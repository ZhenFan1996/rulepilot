import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import AccountView from './AccountView.vue'

describe('AccountView board game nine', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('renders each identity as a cover and game name and searches Chinese aliases locally', async () => {
    vi.useFakeTimers()
    const paths: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      paths.push(path)
      if (path === '/api/auth/session') return Response.json({ username: 'alice', roles: ['USER'] })
      if (path === '/api/v1/teaching-plans') return Response.json([])
      if (path === '/api/v1/model-configuration') return Response.json({ providers: [], assignments: { recommendation: 'fake', teaching: 'fake', visual: 'fake', answer: 'fake', critic: 'fake' } })
      if (path === '/api/v1/model-configuration/usage') return Response.json({ platformAccessEnabled: true, monthlyTokenLimit: 100000, platformTokensCharged: 1000, platformTokensReserved: 0, personalTokensUsed: 0, platformTokensRemaining: 99000 })
      if (path === '/api/v1/account/board-game-grid') return Response.json([{ slot: 'FAVORITE_GAME', bggId: 13, gameName: 'Catan', chineseName: '卡坦岛', thumbnailUrl: 'https://images.example/catan-thumb.jpg', imageUrl: 'https://images.example/catan-full.jpg' }])
      if (path.startsWith('/api/v1/account/board-game-grid/search?')) return Response.json([{ bggId: 174430, name: 'Gloomhaven', chineseName: '幽港迷城', thumbnailUrl: 'https://images.example/gloomhaven-thumb.jpg', imageUrl: 'https://images.example/gloomhaven-full.jpg', publicationYear: 2017 }])
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path.endsWith('/FAVORITE_ART') && init?.method === 'PUT') return Response.json({ slot: 'FAVORITE_ART', bggId: 174430, gameName: 'Gloomhaven', chineseName: '幽港迷城', thumbnailUrl: 'https://images.example/gloomhaven.jpg' })
      return new Response(null, { status: 404 })
    }))
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/', component: AccountView }, { path: '/work', name: 'work-status', component: { template: '<div />' } }, { path: '/settings/models', name: 'model-settings', component: { template: '<div />' } },
    ] })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(AccountView, { global: { plugins: [router], stubs: { AppShell: { template: '<div><slot /></div>' } } } })
    await flushPromises()

    expect(wrapper.get('img[alt="卡坦岛"]').attributes('src')).toBe('/api/v1/bgg/catalog/covers/13/image')
    expect(wrapper.text()).toContain('最爱的桌游')
    expect(wrapper.text()).not.toContain('最舒服的人数')
    await wrapper.get('button[aria-label^="最喜欢的美术"]').trigger('click')
    await wrapper.get('input[type="search"]').setValue('幽港')
    await vi.advanceTimersByTimeAsync(60)
    await flushPromises()
    expect(paths.some(path => path.includes('q=%E5%B9%BD%E6%B8%AF'))).toBe(true)
    expect(paths.some(path => path.startsWith('/api/v1/bgg/catalog?'))).toBe(false)
    expect(wrapper.text()).toContain('幽港迷城')
    expect(wrapper.get('img[alt="幽港迷城"]').attributes('src')).toBe('/api/v1/bgg/catalog/covers/174430/image')
    vi.useRealTimers()
  })

  it('renders the account when an older usage response omits the computed remaining field', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/auth/session') return Response.json({ username: 'alice', roles: ['USER'] })
      if (path === '/api/v1/teaching-plans') return Response.json([])
      if (path === '/api/v1/model-configuration') return Response.json({ providers: [], assignments: { recommendation: 'fake', teaching: 'fake', visual: 'fake', answer: 'fake', critic: 'fake' } })
      if (path === '/api/v1/model-configuration/usage') return Response.json({ platformAccessEnabled: true, monthlyTokenLimit: 100000, platformTokensCharged: 1000, platformTokensReserved: 250, personalTokensUsed: 0, periodStart: '2026-08-01', revision: 1 })
      if (path === '/api/v1/account/board-game-grid') return Response.json([])
      return new Response(null, { status: 404 })
    }))
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/', component: AccountView }, { path: '/work', name: 'work-status', component: { template: '<div />' } }, { path: '/settings/models', name: 'model-settings', component: { template: '<div />' } },
    ] })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(AccountView, { global: { plugins: [router], stubs: { AppShell: { template: '<div><slot /></div>' } } } })
    await flushPromises()

    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).toContain('98,750')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('shows the signed-in account before slower secondary requests finish', async () => {
    const pending = new Promise<Response>(() => undefined)
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/auth/session') return Promise.resolve(Response.json({ username: 'alice', roles: ['USER'] }))
      if (path === '/api/v1/account/board-game-grid') return Promise.resolve(Response.json([]))
      return pending
    }))
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/', component: AccountView }, { path: '/work', name: 'work-status', component: { template: '<div />' } }, { path: '/settings/models', name: 'model-settings', component: { template: '<div />' } },
    ] })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(AccountView, { global: { plugins: [router], stubs: { AppShell: { template: '<div><slot /></div>' } } } })
    await flushPromises()

    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).toContain('我的桌游九宫格')
    expect(wrapper.text()).not.toContain('正在读取我的空间')
  })

  it('loads a persisted grid selection cover directly without waiting for cover metadata', async () => {
    const fetchMock = vi.fn((input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/auth/session') return Promise.resolve(Response.json({ username: 'alice', roles: ['USER'] }))
      if (path === '/api/v1/account/board-game-grid') return Promise.resolve(Response.json([{ slot: 'FAVORITE_GAME', bggId: 342942, gameName: 'Ark Nova', chineseName: '方舟动物园', thumbnailUrl: '', imageUrl: '' }]))
      if (path === '/api/v1/teaching-plans') return Promise.resolve(Response.json([]))
      if (path === '/api/v1/model-configuration') return Promise.resolve(Response.json({ providers: [], assignments: { recommendation: 'fake', teaching: 'fake', visual: 'fake', answer: 'fake', critic: 'fake' } }))
      if (path === '/api/v1/model-configuration/usage') return Promise.resolve(Response.json({ platformAccessEnabled: true, monthlyTokenLimit: 100000, platformTokensCharged: 0, platformTokensReserved: 0, personalTokensUsed: 0 }))
      return Promise.resolve(new Response(null, { status: 404 }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/', component: AccountView }, { path: '/work', name: 'work-status', component: { template: '<div />' } }, { path: '/settings/models', name: 'model-settings', component: { template: '<div />' } },
    ] })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(AccountView, { global: { plugins: [router], stubs: { AppShell: { template: '<div><slot /></div>' } } } })
    await flushPromises()

    expect(wrapper.text()).toContain('方舟动物园')
    expect(wrapper.get('img[alt="方舟动物园"]').attributes('src')).toBe('/api/v1/bgg/catalog/covers/342942/image')
    expect(fetchMock.mock.calls.some(([input]) => String(input).startsWith('/api/v1/bgg/catalog/covers?'))).toBe(false)
  })
})
