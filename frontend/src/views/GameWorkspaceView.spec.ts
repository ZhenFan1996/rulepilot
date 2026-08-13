import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import GameWorkspaceView from './GameWorkspaceView.vue'

describe('GameWorkspaceView', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('keeps rich game details, rulebooks, teaching, and grounded Q&A in one workspace', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/bgg/games/42')) return Response.json({
        bggId: 42, name: 'Catalog Game', description: 'A rich catalog description.',
        imageUrl: 'https://example.test/cover.jpg', thumbnailUrl: '', averageRating: 7.8, averageWeight: 2.4,
        designers: ['Designer'], publishers: ['Publisher'], mechanics: ['Drafting'], categories: ['Strategy'],
        bggUrl: 'https://boardgamegeek.com/boardgame/42',
      })
      if (path.endsWith('/api/v1/games')) return Response.json([{
        game: { id: 'game-1', name: 'Catalog Game' },
        editions: [{ id: 'edition-1', gameId: 'game-1', name: 'First Edition', language: 'en', publicationYear: 2024 }],
        expansions: [],
        bggMetadata: { bggId: 42, thumbnailUrl: 'https://example.test/thumb.jpg', bggUrl: 'https://boardgamegeek.com/boardgame/42', minPlayers: 1, maxPlayers: 5, playingTimeMinutes: 60, minimumAge: 10 },
      }])
      if (path.endsWith('/api/v1/documents')) return Response.json([{
        document: {
          id: 'document-1', gameEditionId: 'edition-1', title: 'Official Rules',
          officialSourceUrl: 'https://publisher.example/rules.pdf', officialCoverUrl: null, createdBy: 'player',
        },
        latestVersion: { id: 'version-1', status: 'READY' },
      }])
      if (path.endsWith('/api/v1/documents/official-imports')) return Response.json([])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return Response.json([])
      if (path.endsWith('/api/v1/teaching-plans')) return Response.json([{
        id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Catalog Game', createdBy: 'player',
        createdAt: '2026-08-06T00:00:00Z',
      }])
      if (path.includes('/api/auth/session')) return Response.json({ username: 'player' })
      return new Response(null, { status: 404 })
    }))

    const { wrapper } = await mountWorkspace()
    await flushPromises()

    expect(wrapper.text()).toContain('Catalog Game')
    expect(wrapper.text()).toContain('A rich catalog description.')
    expect(wrapper.text()).toContain('Designer')
    expect(wrapper.text()).toContain('First Edition')
    expect(wrapper.text()).toContain('Official Rules')
    expect(wrapper.findAll('section.player-board')).toHaveLength(1)
    expect(wrapper.findAll('article.player-board')).toHaveLength(1)
    expect(wrapper.get('a[href="/lesson/plan-1"]').text()).toContain('打开讲解')
    expect(wrapper.get('a[href="/lesson/plan-1/questions"]').text()).toContain('规则答疑')
    const discoveryLink = wrapper.findAll('a').find(link => link.text().includes('找规则书'))!
    expect(discoveryLink.attributes('href')).toBe('/teach?editionId=edition-1&onboarding=selected-game')
  })

  it('shows exact rulebook and guide bindings without waiting for optional BGG details', async () => {
    const delayedDetails = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return Response.json({ username: 'player' })
      if (path.includes('/api/v1/bgg/games/42')) return delayedDetails.promise
      if (path.endsWith('/api/v1/games')) return Response.json([catalogGame('game-1', 'edition-1', 'Catalog Game', 42)])
      if (path.endsWith('/api/v1/documents')) return Response.json([ownedDocument('document-1', 'edition-1', 'version-1', 'Official Rules')])
      if (path.endsWith('/api/v1/teaching-plans')) return Response.json([{
        id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Catalog Game', createdBy: 'player',
        createdAt: '2026-08-13T08:00:00Z',
      }])
      if (path.endsWith('/api/v1/documents/official-imports')) return Response.json([])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return Response.json([])
      return new Response(null, { status: 404 })
    }))

    const { wrapper } = await mountWorkspace()
    await flushPromises()

    expect(wrapper.text()).toContain('Catalog Game')
    expect(wrapper.text()).toContain('Official Rules')
    expect(wrapper.get('a[href="/lesson/plan-1"]')).toBeTruthy()
    expect(wrapper.text()).not.toContain('A delayed description')

    delayedDetails.resolve(Response.json(richDetails(42, 'A delayed description')))
    await flushPromises()
    expect(wrapper.text()).toContain('A delayed description')
  })

  it('replaces a reused game route without accepting the old route response', async () => {
    const oldCatalog = deferred<Response>()
    let gameReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return Response.json({ username: 'player' })
      if (path.endsWith('/api/v1/games')) {
        gameReads++
        if (gameReads === 1) return oldCatalog.promise
        return Response.json([catalogGame('game-2', 'edition-2', 'Current Game')])
      }
      if (path.endsWith('/api/v1/documents')) return Response.json(gameReads <= 1
        ? [ownedDocument('document-1', 'edition-1', 'version-1', 'Old Rules')]
        : [ownedDocument('document-2', 'edition-2', 'version-2', 'Current Rules')])
      if (path.endsWith('/api/v1/teaching-plans')) return Response.json([])
      if (path.endsWith('/api/v1/documents/official-imports')) return Response.json([])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return Response.json([])
      return new Response(null, { status: 404 })
    }))

    const { wrapper, router } = await mountWorkspace()
    await flushPromises()
    await router.push('/games/game-2')
    await flushPromises()

    expect(wrapper.text()).toContain('Current Game')
    expect(wrapper.text()).toContain('Current Rules')
    oldCatalog.resolve(Response.json([catalogGame('game-1', 'edition-1', 'Old Game')]))
    await flushPromises()
    expect(wrapper.text()).toContain('Current Game')
    expect(wrapper.text()).not.toContain('Old Game')
  })

  it('opens a workspace directly from the persisted import before the PDF document exists', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return Response.json({ username: 'player' })
      if (path.endsWith('/api/v1/games')) return Response.json([catalogGame('game-1', 'edition-1', 'Catalog Game')])
      if (path.endsWith('/api/v1/documents')) return Response.json([])
      if (path.endsWith('/api/v1/teaching-plans')) return Response.json([])
      if (path.endsWith('/api/v1/documents/official-imports')) return Response.json([pendingImport()])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return Response.json([])
      return new Response(null, { status: 404 })
    }))

    const { wrapper } = await mountWorkspace()
    await flushPromises()
    expect(wrapper.get('h1').text()).toContain('Catalog Game')
    expect(wrapper.text()).toContain('正在下载并绑定这本规则书')
    expect(wrapper.text()).toContain('讲解任务已持久化')
    expect(wrapper.text()).not.toContain('这款桌游不在你的“我的桌游”中')
  })

  it('offers recovery without falsely labeling membership when the import binding is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return Response.json({ username: 'player' })
      if (path.endsWith('/api/v1/games')) return Response.json([catalogGame('game-1', 'edition-1', 'Catalog Game')])
      if (path.endsWith('/api/v1/documents') || path.endsWith('/api/v1/teaching-plans')
        || path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return Response.json([])
      if (path.endsWith('/api/v1/documents/official-imports')) return new Response(null, { status: 503 })
      return new Response(null, { status: 404 })
    }))

    const { wrapper } = await mountWorkspace()
    await flushPromises()

    expect(wrapper.text()).toContain('暂时无法确认这款桌游是否已加入“我的桌游”')
    expect(wrapper.text()).toContain('重试')
    expect(wrapper.text()).not.toContain('这款桌游不在你的“我的桌游”中')
    expect(wrapper.find('.animate-pulse').exists()).toBe(false)
  })

  it('shows a failed exact preparation run instead of claiming it still runs in the background', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return Response.json({ username: 'player' })
      if (path.endsWith('/api/v1/games')) return Response.json([catalogGame('game-1', 'edition-1', 'Catalog Game')])
      if (path.endsWith('/api/v1/documents')) return Response.json([ownedDocument('document-1', 'edition-1', 'version-1', 'Official Rules')])
      if (path.endsWith('/api/v1/documents/official-imports')) return Response.json([])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return Response.json([{
        id: 'handoff-1', documentVersionId: 'version-1', editionId: 'edition-1', rulebookTitle: 'Official Rules',
        state: 'LAUNCHED', preparationRunId: 'preparation-1', errorCode: null, updatedAt: '2026-08-13T08:00:00Z',
      }])
      if (path.endsWith('/api/v1/assistant-runs/preparation-1')) return Response.json({ run: {
        id: 'preparation-1', mode: 'TEACHING_PREPARATION', subjectId: 'version-1', ownerUsername: 'player',
        state: 'FAILED', updatedAt: '2026-08-13T08:01:00Z',
      } })
      if (path.endsWith('/api/v1/teaching-plans')) return Response.json([])
      return new Response(null, { status: 404 })
    }))

    const { wrapper } = await mountWorkspace()
    await flushPromises()

    expect(wrapper.text()).toContain('讲解准备需要处理')
    expect(wrapper.text()).not.toContain('讲解任务已持久化，正在后台准备')
    expect(wrapper.find('a[href="/teach?editionId=edition-1"]').exists()).toBe(false)
    expect(wrapper.findAll('a[href="/lessons"]').some(link => link.text().includes('去我的讲解重试'))).toBe(true)
  })
})

async function mountWorkspace() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
      { path: '/games/:gameId', name: 'game-workspace', component: GameWorkspaceView },
      { path: '/teach', name: 'teach', component: { template: '<div />' } },
      { path: '/lesson/:planId', name: 'lesson', component: { template: '<div />' } },
      { path: '/lesson/:planId/questions', name: 'lesson-questions', component: { template: '<div />' } },
      { path: '/library', name: 'public-library', component: { template: '<div />' } },
      { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
      { path: '/account', name: 'account', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.push('/games/game-1')
  await router.isReady()
  return { wrapper: mount(GameWorkspaceView, { global: { plugins: [router] } }), router }
}

function catalogGame(gameId: string, editionId: string, name: string, bggId?: number) {
  return {
    game: { id: gameId, name },
    editions: [{ id: editionId, gameId, name: 'First Edition', language: 'en', publicationYear: 2024 }],
    expansions: [],
    bggMetadata: bggId ? {
      bggId, thumbnailUrl: '', bggUrl: `https://boardgamegeek.com/boardgame/${bggId}`,
      minPlayers: 1, maxPlayers: 5, playingTimeMinutes: 60, minimumAge: 10,
    } : null,
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

function richDetails(bggId: number, description: string) {
  return {
    bggId, name: 'Catalog Game', description, imageUrl: '', thumbnailUrl: '', averageRating: null,
    averageWeight: null, designers: [], publishers: [], mechanics: [], categories: [],
    bggUrl: `https://boardgamegeek.com/boardgame/${bggId}`,
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

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((nextResolve) => { resolve = nextResolve })
  return { promise, resolve }
}
