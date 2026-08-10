import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { readPendingRulebookLessons } from '@/lib/pendingRulebookLesson'

import RecommendationRulebookHandoff from './RecommendationRulebookHandoff.vue'

const game = {
  bggId: 266192,
  name: '展翅翱翔',
  originalName: 'Wingspan',
  nameLocalized: true,
  publicationYear: 2019,
  overallRank: 34,
  geekRating: 7.79,
  averageRating: 8.09,
  usersRated: 102030,
  thumbnailUrl: 'https://example.test/wingspan.jpg',
  minPlayers: 1,
  maxPlayers: 5,
  playingTimeMinutes: 70,
  averageWeight: 2.5,
  categories: ['动物'],
  mechanics: ['卡牌轮抽'],
  bggUrl: 'https://boardgamegeek.com/boardgame/266192',
}

describe('RecommendationRulebookHandoff', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('rulepilot:locale', 'zh-CN')
  })

  afterEach(() => vi.unstubAllGlobals())

  async function mountHandoff() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/discover/:bggId', name: 'game-discovery', component: { template: '<div />' } },
        { path: '/teach', name: 'teach', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(RecommendationRulebookHandoff, {
      props: {
        game,
        profile: { players: 5, maxMinutes: 90, maxWeight: 3, type: 'all', interaction: 'any' },
      },
      global: { plugins: [router] },
    })
    return { wrapper, router }
  }

  it('keeps selection, candidate review, consent, download, and teaching recovery in one flow', async () => {
    const openSource = vi.fn()
    vi.stubGlobal('open', openSource)
    const requests: Array<{ path: string; options?: RequestInit }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      requests.push({ path, options })
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/bgg/games/266192/import') return Response.json({
        game: { id: 'game-1', name: '展翅翱翔' },
        edition: { id: 'edition-1', name: 'BGG 版本' },
        alreadyImported: false,
      })
      if (path.startsWith('/api/v1/documents/rulebook-candidates?')) return Response.json({
        configured: true,
        candidates: [{
          title: 'Wingspan Rulebook',
          url: 'https://publisher.example/files/wingspan-rulebook.pdf',
          publisher: 'Stonemaier Games',
          language: 'English',
          edition: 'Base game',
          sourceDomain: 'publisher.example',
          officialDomainVerified: true,
          sourceType: 'PUBLISHER',
          acquisitionMode: 'DIRECT_PDF',
        }, {
          title: 'BGG files',
          url: 'https://boardgamegeek.com/filepage/123/rules',
          publisher: '',
          language: 'English',
          edition: 'Base game',
          sourceDomain: 'boardgamegeek.com',
          officialDomainVerified: false,
          sourceType: 'COMMUNITY_PLATFORM',
          acquisitionMode: 'SOURCE_PAGE',
        }],
      })
      if (path === '/api/auth/session') return Response.json({ username: 'player', roles: ['USER'] })
      if (path === '/api/v1/documents/official-imports') return Response.json({
        id: 'import-1', stage: 'QUEUED', documentVersionId: null, duplicate: false, errorCode: null,
      }, { status: 202 })
      if (path === '/api/v1/documents/official-imports/import-1') return Response.json({
        id: 'import-1', stage: 'COMPLETED', documentVersionId: 'version-1', duplicate: false, errorCode: null,
      })
      return new Response(null, { status: 404 })
    }))
    const { wrapper, router } = await mountHandoff()
    await flushPromises()

    expect(wrapper.text()).toContain('已选《展翅翱翔》')
    expect(wrapper.text()).toContain('Wingspan Rulebook')
    expect(wrapper.text()).toContain('English')
    expect(wrapper.text()).toContain('出版社 / 权利方来源')
    expect(requests.find(request => request.path === '/api/v1/bgg/games/266192/import')?.options).toMatchObject({
      method: 'POST',
      headers: { 'X-CSRF-TOKEN': 'csrf' },
    })

    await wrapper.findAll('button').find(button => button.text() === '打开来源页')!.trigger('click')
    expect(openSource).toHaveBeenCalledWith(
      'https://boardgamegeek.com/filepage/123/rules', '_blank', 'noopener,noreferrer',
    )
    expect(wrapper.text()).toContain('搜索结果没有提供可验证的 PDF 直链')
    expect(wrapper.find('input[type="checkbox"]').exists()).toBe(false)

    await wrapper.get('button[aria-pressed="false"]').trigger('click')
    const importButton = wrapper.findAll('button').find(button => button.text() === '下载规则书并生成讲解')!
    expect(importButton.attributes('disabled')).toBeDefined()
    await wrapper.get('input[type="checkbox"]').setValue(true)
    expect(importButton.attributes('disabled')).toBeUndefined()
    expect(requests.some(request => request.path === '/api/v1/documents/official-imports')).toBe(false)

    await importButton.trigger('click')
    await flushPromises()

    const officialImport = requests.find(request => request.path === '/api/v1/documents/official-imports')
    expect(JSON.parse(String(officialImport?.options?.body))).toEqual({
      editionId: 'edition-1',
      title: 'Wingspan Rulebook',
      sourceType: 'BASE_RULEBOOK',
      officialSourceUrl: 'https://publisher.example/files/wingspan-rulebook.pdf',
      rightsConfirmed: true,
    })
    expect(readPendingRulebookLessons(localStorage, 'player')).toEqual([{
      versionId: 'version-1',
      editionId: 'edition-1',
      playerCount: 5,
      beginnerCount: 5,
      durationMinutes: 25,
    }])
    expect(router.currentRoute.value.name).toBe('teach')
    expect(router.currentRoute.value.query).toEqual({
      editionId: 'edition-1', onboarding: 'recommendation-agent', importJob: 'import-1',
    })
  })

  it('imports an ordered community page-image rulebook as part of the same teaching handoff', async () => {
    const requests: Array<{ path: string; options?: RequestInit }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      requests.push({ path, options })
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/bgg/games/266192/import') return Response.json({
        game: { id: 'game-1', name: '展翅翱翔' },
        edition: { id: 'edition-1', name: 'BGG 版本' },
        alreadyImported: false,
      })
      if (path.startsWith('/api/v1/documents/rulebook-candidates?')) return Response.json({
        configured: true,
        candidates: [{
          title: '官方规则书',
          url: 'https://www.gstonegames.com/game/doc-1234.html',
          publisher: '集石',
          language: '简体中文',
          edition: '基础版',
          sourceDomain: 'www.gstonegames.com',
          officialDomainVerified: false,
          sourceType: 'COMMUNITY_PLATFORM',
          acquisitionMode: 'IMAGE_GALLERY',
        }],
      })
      if (path === '/api/auth/session') return Response.json({ username: 'player', roles: ['USER'] })
      if (path === '/api/v1/documents/official-imports') return Response.json({
        id: 'gallery-import', stage: 'QUEUED', documentVersionId: null, duplicate: false, errorCode: null,
      }, { status: 202 })
      if (path === '/api/v1/documents/official-imports/gallery-import') return Response.json({
        id: 'gallery-import', stage: 'COMPLETED', documentVersionId: 'version-gallery', duplicate: false, errorCode: null,
      })
      return new Response(null, { status: 404 })
    }))
    const { wrapper, router } = await mountHandoff()
    await flushPromises()

    expect(wrapper.text()).toContain('连续规则页图片，可合成为 PDF')
    expect(wrapper.text()).toContain('社区规则书来源（如 BGG / 集石）')
    await wrapper.get('button[aria-pressed="false"]').trigger('click')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.findAll('button').find(button => button.text() === '下载规则书并生成讲解')!.trigger('click')
    await flushPromises()

    const request = requests.find(candidate => candidate.path === '/api/v1/documents/official-imports')
    expect(JSON.parse(String(request?.options?.body))).toMatchObject({
      title: '官方规则书',
      officialSourceUrl: 'https://www.gstonegames.com/game/doc-1234.html',
      rightsConfirmed: true,
    })
    expect(router.currentRoute.value.name).toBe('teach')
  })

  it('turns an account-gated exact BGG download into an actionable browser handoff', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/bgg/games/266192/import') return Response.json({
        game: { id: 'game-1', name: '展翅翱翔' },
        edition: { id: 'edition-1', name: 'BGG 版本' },
        alreadyImported: false,
      })
      if (path.startsWith('/api/v1/documents/rulebook-candidates?')) return Response.json({
        configured: true,
        candidates: [{
          title: 'Wingspan community rules',
          url: 'https://boardgamegeek.com/file/download_redirect/c66d839e5ef882cf86295abc25caef76456ef0ed43746421/wingspan-rules.pdf',
          publisher: 'Community uploader',
          language: 'English',
          edition: 'Base game',
          sourceDomain: 'boardgamegeek.com',
          officialDomainVerified: false,
          sourceType: 'COMMUNITY_PLATFORM',
          acquisitionMode: 'DIRECT_PDF',
        }],
      })
      if (path === '/api/auth/session') return Response.json({ username: 'player', roles: ['USER'] })
      if (path === '/api/v1/documents/official-imports') return Response.json({
        id: 'bgg-import', stage: 'QUEUED', documentVersionId: null, duplicate: false, errorCode: null,
      }, { status: 202 })
      if (path === '/api/v1/documents/official-imports/bgg-import') return Response.json({
        id: 'bgg-import', stage: 'FAILED', documentVersionId: null, duplicate: false,
        errorCode: 'SOURCE_BROWSER_REQUIRED',
      })
      return new Response(null, { status: 404 })
    }))
    const { wrapper } = await mountHandoff()
    await flushPromises()

    await wrapper.get('button[aria-pressed="false"]').trigger('click')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.findAll('button').find(button => button.text() === '下载规则书并生成讲解')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('已经找到这份文件')
    const bggLink = wrapper.findAll('a').find(link => link.text().includes('在来源网站继续下载'))!
    expect(bggLink.attributes('href')).toBe(
      'https://boardgamegeek.com/file/download_redirect/c66d839e5ef882cf86295abc25caef76456ef0ed43746421/wingspan-rules.pdf',
    )
    expect(wrapper.text()).toContain('本地上传')
  })

  it('does not download when discovery is unavailable and preserves a manual edition-aware fallback', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/bgg/games/266192/import') return Response.json({
        game: { id: 'game-1', name: '展翅翱翔' },
        edition: { id: 'edition-1', name: 'BGG 版本' },
        alreadyImported: true,
      })
      if (path.startsWith('/api/v1/documents/rulebook-candidates?')) return Response.json({ configured: false, candidates: [] })
      return new Response(null, { status: 500 })
    }))
    const { wrapper } = await mountHandoff()
    await flushPromises()

    expect(wrapper.text()).toContain('当前没有找到可审阅的规则书来源')
    const fallback = wrapper.get('a')
    expect(fallback.attributes('href')).toBe('/teach?editionId=edition-1&onboarding=recommendation-agent')
    expect(fallback.text()).toContain('本地上传')
    expect(wrapper.find('input[type="checkbox"]').exists()).toBe(false)
  })
})
