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
            bggId: 237182, thumbnailUrl: 'https://images.example/root.jpg', bggUrl: 'https://boardgamegeek.com/boardgame/237182',
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
        document: {
          id: 'doc-root', gameEditionId: 'root-en', title: 'Root Rules', officialSourceUrl: null,
          officialCoverUrl: null, createdBy: 'player',
        },
        latestVersion: { id: 'version-root', status: 'READY' },
      }])
      if (path.endsWith('/api/v1/documents/official-imports')) return response([])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return response([])
      if (path.endsWith('/api/v1/teaching-plans')) return response([{
        id: 'plan-root', documentVersionId: 'version-root', gameTitle: 'Root', createdBy: 'player',
        createdAt: '2026-07-23T12:00:00Z',
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
    expect(wrapper.get('img[src="/illustrations/game-library.webp"]').attributes('alt')).toBe('')
    expect(wrapper.get('img[alt="Root 的游戏封面"]').attributes('src')).toBe('https://images.example/root.jpg')
    expect(wrapper.get('a[href="/lesson/plan-root"]')).toBeTruthy()

    setLocale('en')
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('What are we playing tonight?')
    expect(wrapper.text()).toContain('2–4 players')
    expect(wrapper.text()).toContain('Continue guide')
    expect(wrapper.text()).not.toContain('今晚想开哪一局？')
  })

  it('presents an unspecified edition language honestly instead of exposing the und code', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return response({ username: 'player' })
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/games')) return response([{
        ...catalogGame('game-1', 'edition-1', 'Concordia'),
        editions: [{ id: 'edition-1', gameId: 'game-1', name: 'BGG 基础版', language: 'und', publicationYear: 2013 }],
      }])
      if (path.endsWith('/api/v1/documents')) {
        return response([ownedDocument('document-1', 'edition-1', 'version-1', 'Official Rulebook')])
      }
      if (path.endsWith('/api/v1/documents/official-imports')) return response([])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return response([])
      if (path.endsWith('/api/v1/teaching-plans')) return response([])
      return new Response(null, { status: 404 })
    }))

    const router = appRouter()
    await router.push('/catalog')
    await router.isReady()
    const wrapper = mount(GameShelfView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('BGG 基础版 · 语言未标注 · 2013')
    expect(wrapper.text()).not.toMatch(/\bund\b/)

    setLocale('en')
    await flushPromises()
    expect(wrapper.text()).toContain('BGG 基础版 · Language not stated · 2013')
    expect(wrapper.text()).not.toMatch(/\bund\b/)
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

  it('publishes the durable game and rulebook before a slow guide list settles', async () => {
    const delayedPlans = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return response({ username: 'player' })
      if (path.endsWith('/api/v1/games')) return response([catalogGame('game-1', 'edition-1', 'Catalog Game')])
      if (path.endsWith('/api/v1/documents')) return response([ownedDocument('document-1', 'edition-1', 'version-1', 'Official Rules')])
      if (path.endsWith('/api/v1/documents/official-imports')) return response([])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return response([{
        id: 'handoff-1', documentVersionId: 'version-1', editionId: 'edition-1', rulebookTitle: 'Official Rules',
        state: 'LAUNCHED', preparationRunId: 'preparation-1', errorCode: null, updatedAt: '2026-08-13T08:00:00Z',
      }])
      if (path.endsWith('/api/v1/assistant-runs/preparation-1')) return response({ run: preparationRun() })
      if (path.endsWith('/api/v1/teaching-plans')) return delayedPlans.promise
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      return new Response(null, { status: 404 })
    }))

    const router = appRouter()
    await router.push('/catalog')
    await router.isReady()
    const wrapper = mount(GameShelfView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('Catalog Game')
    expect(wrapper.text()).toContain('1 本规则书')
    expect(wrapper.text()).toContain('讲解正在准备')
    expect(wrapper.text()).not.toContain('还没生成讲解')

    delayedPlans.resolve(response([{
      id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Catalog Game', createdBy: 'player',
      createdAt: '2026-08-13T08:01:00Z',
    }]))
    await flushPromises()
    expect(wrapper.get('a[href="/lesson/plan-1"]').text()).toContain('继续讲解')
  })

  it('shows the exact failed preparation instead of a false active background state', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return response({ username: 'player' })
      if (path.endsWith('/api/v1/games')) return response([catalogGame('game-1', 'edition-1', 'Catalog Game')])
      if (path.endsWith('/api/v1/documents')) return response([ownedDocument('document-1', 'edition-1', 'version-1', 'Official Rules')])
      if (path.endsWith('/api/v1/documents/official-imports')) return response([])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return response([{
        id: 'handoff-1', documentVersionId: 'version-1', editionId: 'edition-1', rulebookTitle: 'Official Rules',
        state: 'LAUNCHED', preparationRunId: 'preparation-1', errorCode: null, updatedAt: '2026-08-13T08:00:00Z',
      }])
      if (path.endsWith('/api/v1/assistant-runs/preparation-1')) return response({ run: preparationRun('FAILED') })
      if (path.endsWith('/api/v1/teaching-plans')) return response([])
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      return new Response(null, { status: 404 })
    }))

    const router = appRouter()
    await router.push('/catalog')
    await router.isReady()
    const wrapper = mount(GameShelfView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('讲解需要处理')
    expect(wrapper.text()).not.toContain('讲解正在准备')
    expect(wrapper.findAll('a[href="/lessons"]').some(link => link.text().includes('去我的讲解重试'))).toBe(true)
  })

  it('shows the selected game from a persisted import before a document exists', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return response({ username: 'player' })
      if (path.endsWith('/api/v1/games')) return response([catalogGame('game-1', 'edition-1', 'Catalog Game')])
      if (path.endsWith('/api/v1/documents')) return response([])
      if (path.endsWith('/api/v1/teaching-plans')) return response([])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return response([])
      if (path.endsWith('/api/v1/documents/official-imports')) return response([pendingImport()])
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      return new Response(null, { status: 404 })
    }))

    const router = appRouter()
    await router.push('/catalog')
    await router.isReady()
    const wrapper = mount(GameShelfView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('Catalog Game')
    expect(wrapper.text()).toContain('正在下载规则书')
    expect(wrapper.text()).toContain('规则书正在加入')
    expect(wrapper.findAll('a[href="/games/game-1"]').some(link => link.text().includes('查看准备进度'))).toBe(true)
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

function catalogGame(gameId: string, editionId: string, name: string) {
  return {
    game: { id: gameId, name },
    editions: [{ id: editionId, gameId, name: 'First Edition', language: 'en', publicationYear: 2024 }],
    expansions: [], bggMetadata: null,
  }
}

function ownedDocument(documentId: string, editionId: string, versionId: string, title: string) {
  return {
    document: {
      id: documentId, gameEditionId: editionId, title, officialSourceUrl: null,
      officialCoverUrl: null, createdBy: 'player',
    },
    latestVersion: { id: versionId, status: 'READY' },
  }
}

function pendingImport() {
  return {
    id: 'import-1', title: 'Catalog Game', rulebookTitle: 'Official Rules', editionId: 'edition-1',
    editionName: 'First Edition', sourceDomain: 'publisher.example', stage: 'DOWNLOADING', downloadedBytes: 1024,
    totalBytes: 4096, documentVersionId: null, errorCode: null, teachingHandoffState: 'WAITING_FOR_DOCUMENT',
    teachingPreparationRunId: null, teachingErrorCode: null, updatedAt: '2026-08-13T08:00:00Z',
  }
}

function preparationRun(state = 'LESSON_PLANNING') {
  return {
    id: 'preparation-1', mode: 'TEACHING_PREPARATION', subjectId: 'version-1', ownerUsername: 'player', state,
    updatedAt: '2026-08-13T08:00:00Z',
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((nextResolve) => { resolve = nextResolve })
  return { promise, resolve }
}
