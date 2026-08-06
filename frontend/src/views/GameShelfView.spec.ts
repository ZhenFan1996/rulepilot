import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { setLocale } from '@/lib/locale'
import GameShelfView from './GameShelfView.vue'

describe('GameShelfView', () => {
  afterEach(() => {
    setLocale('zh-CN')
    vi.unstubAllGlobals()
  })

  it('makes the signed-in players own rulebook and lesson the shelf, not the global catalog', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return response({ username: 'player' })
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/games')) return response([
        {
          game: { id: 'root', name: 'Root' },
          editions: [{ id: 'root-en', gameId: 'root', name: '基础版', language: 'en', publicationYear: 2018 }],
          expansions: [{ id: 'riverfolk', gameId: 'root', name: '河民扩展' }],
          bggMetadata: {
            thumbnailUrl: 'https://images.example/root.jpg', bggUrl: 'https://boardgamegeek.com/boardgame/root',
            minPlayers: 2, maxPlayers: 4, playingTimeMinutes: 90, minimumAge: 10,
          },
        },
        {
          game: { id: 'noise', name: '不属于我的测试游戏' },
          editions: [{ id: 'noise-en', gameId: 'noise', name: '测试版', language: 'en', publicationYear: null }],
          expansions: [], bggMetadata: null,
        },
      ])
      if (path.endsWith('/api/v1/documents')) return response([{
        document: { id: 'doc-root', gameEditionId: 'root-en', title: 'Root Rules', officialCoverUrl: null },
        latestVersion: { id: 'version-root', status: 'READY' },
      }])
      if (path.endsWith('/api/v1/teaching-plans')) return response([{
        id: 'plan-root', documentVersionId: 'version-root', gameTitle: 'Root', createdAt: '2026-07-23T12:00:00Z',
      }])
      return new Response(null, { status: 404 })
    }))

    const router = appRouter()
    await router.push('/catalog')
    await router.isReady()
    const wrapper = mount(GameShelfView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('今晚想开哪一局？')
    expect(wrapper.text()).toContain('Root')
    expect(wrapper.text()).toContain('2–4 人')
    expect(wrapper.text()).toContain('90 分钟')
    expect(wrapper.text()).toContain('继续讲解')
    expect(wrapper.text()).not.toContain('不属于我的测试游戏')
    expect(wrapper.get('img[alt="Root 的游戏封面"]').attributes('src')).toBe('https://images.example/root.jpg')
    expect(wrapper.get('a[href="/lesson/plan-root"]')).toBeTruthy()

    setLocale('en')
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('What are we playing tonight?')
    expect(wrapper.text()).toContain('2–4 players')
    expect(wrapper.text()).toContain('Continue guide')
    expect(wrapper.text()).not.toContain('今晚想开哪一局？')
  })

  it('retains the shelf route and shows a sign-in action when the session is missing', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 401 })))
    const router = appRouter()
    await router.push('/catalog')
    await router.isReady()

    const wrapper = mount(GameShelfView, { global: { plugins: [router] } })
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/catalog')
    expect(wrapper.text()).toContain('请先登录后查看你的桌游书架')
    expect(wrapper.text()).toContain('当前页面已保留')
    expect(wrapper.get('a[href="/login?redirect=/catalog"]').text()).toContain('登录')
  })
})

function appRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/library', name: 'public-library', component: { template: '<div />' } },
      { path: '/teach', name: 'teach', component: { template: '<div />' } },
      { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
      { path: '/catalog', name: 'catalog', component: GameShelfView },
      { path: '/catalog/manage', name: 'catalog-manage', component: { template: '<div />' } },
      { path: '/games/:gameId', name: 'game-workspace', component: { template: '<div />' } },
      { path: '/account', name: 'account', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
      { path: '/lesson/:planId', name: 'lesson', component: { template: '<div />' } },
    ],
  })
}

function response(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}
