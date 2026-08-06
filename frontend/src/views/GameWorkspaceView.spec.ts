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
        document: { id: 'document-1', gameEditionId: 'edition-1', title: 'Official Rules', officialSourceUrl: 'https://publisher.example/rules.pdf' },
        latestVersion: { id: 'version-1', status: 'READY' },
      }])
      if (path.endsWith('/api/v1/teaching-plans')) return Response.json([{
        id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Catalog Game', createdAt: '2026-08-06T00:00:00Z',
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
    expect(wrapper.get('a[href="/lesson/plan-1"]').text()).toContain('打开讲解')
    expect(wrapper.get('a[href="/lesson/plan-1/questions"]').text()).toContain('规则答疑')
    const discoveryLink = wrapper.findAll('a').find(link => link.text().includes('Agent 找规则书'))!
    expect(discoveryLink.attributes('href')).toBe('/teach?editionId=edition-1&onboarding=selected-game')
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
