import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { readPendingRulebookLessons } from '@/lib/pendingRulebookLesson'
import { BACKGROUND_WORK_CHANGED_EVENT } from '@/lib/backgroundWorkRefresh'

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

const discoveryIdentity = {
  editionId: 'edition-1',
  gameName: '展翅翱翔',
  editionName: 'BGG 版本',
  language: 'und',
}

const confirmedDocumentCapability = {
  capability: 'DIRECT_DOCUMENT',
  capabilityEvidence: ['DOCUMENT_RESPONSE_CONFIRMED'],
  capabilityCheckedAt: '2026-08-15T12:00:00Z',
  nextAction: 'IMPORT_DOCUMENT',
} as const

const confirmedGalleryCapability = {
  capability: 'CONTIGUOUS_RULE_PAGES',
  capabilityEvidence: ['ORDERED_PAGE_SEQUENCE_CONFIRMED'],
  capabilityCheckedAt: '2026-08-15T12:00:00Z',
  nextAction: 'IMPORT_PAGE_SEQUENCE',
} as const

const documentListingCapability = {
  capability: 'DOCUMENT_LISTING',
  capabilityEvidence: ['KNOWN_DOCUMENT_LISTING_ROUTE'],
  capabilityCheckedAt: '2026-08-15T12:00:00Z',
  nextAction: 'CONTINUE_ON_SOURCE',
} as const

function runSnapshot(id: string, state: string) {
  return {
    run: {
      id, state, revision: 4,
      updatedAt: '2026-08-10T10:00:04Z', lastErrorCode: null,
    },
    activities: [],
  }
}

function planFixture(id: string, documentVersionId: string) {
  return {
    id, documentVersionId, gameTitle: 'Wingspan', premise: 'Learn the complete game',
    sections: [{ position: 1, title: 'Setup' }],
  }
}

function lessonFixture(id: string, teachingPlanId = 'plan-1') {
  return {
    id, teachingPlanId, status: 'COMPLETE', sections: [{ position: 1, title: 'Setup' }],
  }
}

describe('RecommendationRulebookHandoff', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    localStorage.setItem('rulepilot:locale', 'zh-CN')
  })

  afterEach(() => vi.unstubAllGlobals())

  class FakeProgressEventSource {
    static instances: FakeProgressEventSource[] = []
    onerror: ((event: Event) => void) | null = null
    closed = false
    private progressListener: ((event: MessageEvent<string>) => void) | null = null

    constructor(public readonly url: string, public readonly options?: EventSourceInit) {
      FakeProgressEventSource.instances.push(this)
    }

    addEventListener(name: string, listener: EventListenerOrEventListenerObject) {
      if (name === 'progress') this.progressListener = listener as (event: MessageEvent<string>) => void
    }

    emitProgress(snapshot: unknown) {
      this.progressListener?.(new MessageEvent('progress', { data: JSON.stringify(snapshot) }))
    }

    close() { this.closed = true }
  }

  async function mountHandoff() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/discover/:bggId', name: 'game-discovery', component: { template: '<div />' } },
        { path: '/teach', name: 'teach', component: { template: '<div />' } },
        { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
        { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(RecommendationRulebookHandoff, {
      props: {
        game,
        profile: {
          type: 'all',
          interaction: 'any',
          playerCount: { minimum: 5, maximum: 5, strength: 'hard', sourceText: 'five players', confirmedTurn: 1 },
          durationMinutes: { minimum: null, maximum: 90, strength: 'hard', sourceText: 'up to 90 minutes', confirmedTurn: 1 },
          complexity: { minimum: null, maximum: 3, strength: 'hard', sourceText: 'complexity at most 3', confirmedTurn: 1 },
        },
      },
      global: { plugins: [router] },
    })
    return { wrapper, router }
  }

  async function confirmIdentityAndRights(wrapper: Awaited<ReturnType<typeof mountHandoff>>['wrapper']) {
    const confirmations = wrapper.findAll('input[type="checkbox"]')
    expect(confirmations).toHaveLength(2)
    await confirmations[0]!.setValue(true)
    await confirmations[1]!.setValue(true)
  }

  it('derives source-search elapsed time from a monotonic clock after timer throttling', async () => {
    let now = 1_000
    let findingTick: (() => void) | undefined
    let releaseDiscovery!: (value: Response) => void
    const discoveryResponse = new Promise<Response>((resolve) => { releaseDiscovery = resolve })
    const nowSpy = vi.spyOn(performance, 'now').mockImplementation(() => now)
    vi.stubGlobal('setInterval', vi.fn((callback: TimerHandler) => {
      findingTick = callback as () => void
      return 41
    }))
    vi.stubGlobal('clearInterval', vi.fn())
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/auth/csrf') {
        return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      }
      if (path === '/api/v1/bgg/games/266192/import') return Response.json({
        game: { id: 'game-1', name: '展翅翱翔' },
        edition: { id: 'edition-1', name: 'BGG 版本', language: 'zh-CN' },
        alreadyImported: false,
      })
      if (path.startsWith('/api/v1/documents/rulebook-candidates?')) return discoveryResponse
      return new Response(null, { status: 404 })
    }))

    const { wrapper } = await mountHandoff()
    try {
      await vi.waitFor(() => expect(findingTick).toBeDefined())
      expect(wrapper.text()).toContain('已等待 0 秒')

      now = 62_000
      findingTick!()
      await wrapper.vm.$nextTick()

      expect(wrapper.text()).toContain('已等待 61 秒')
    } finally {
      releaseDiscovery(Response.json({ configured: true, identity: discoveryIdentity, candidates: [] }))
      await flushPromises()
      wrapper.unmount()
      nowSpy.mockRestore()
    }
  })

  it('keeps selection, candidate review, consent, download, and teaching recovery in one flow', async () => {
    const openSource = vi.fn()
    const backgroundWorkChanged = vi.fn()
    window.addEventListener(BACKGROUND_WORK_CHANGED_EVENT, backgroundWorkChanged)
    vi.stubGlobal('open', openSource)
    const requests: Array<{ path: string; options?: RequestInit }> = []
    let importAttempts = 0
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
        identity: discoveryIdentity,
        candidates: [{
          title: 'Wingspan Rulebook',
          url: 'https://publisher.example/files/wingspan-rulebook.pdf',
          publisher: 'Stonemaier Games',
          language: 'en',
          languageVerified: true,
          edition: 'Base game',
          sourceDomain: 'publisher.example',
          officialDomainVerified: true,
          sourceType: 'PUBLISHER',
          acquisitionMode: 'DIRECT_PDF',
          ...confirmedDocumentCapability,
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
          ...documentListingCapability,
        }],
      })
      if (path === '/api/auth/session') return Response.json({ username: 'player', roles: ['USER'] })
      if (path === '/api/v1/documents/official-imports') {
        importAttempts += 1
        if (importAttempts === 1) {
          return Response.json({ code: 'RULEBOOK_CONFIRMATION_REQUIRED' }, { status: 409 })
        }
        return Response.json({
          id: 'import-1', stage: 'QUEUED', documentVersionId: null, duplicate: false, errorCode: null,
          teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null,
        }, { status: 202 })
      }
      if (path === '/api/v1/documents/official-imports/import-1') return Response.json({
        id: 'import-1', stage: 'COMPLETED', documentVersionId: 'version-1', duplicate: false, errorCode: null,
        teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'preparation-run-1',
      })
      if (path === '/api/v1/assistant-runs/preparation-run-1') {
        return Response.json(runSnapshot('preparation-run-1', 'COMPLETED'))
      }
      if (path === '/api/v1/document-versions/version-1/teaching-plans/latest') {
        return Response.json(planFixture('plan-1', 'version-1'))
      }
      if (path === '/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=plan-1') {
        return Response.json(runSnapshot('teaching-run-1', 'COMPLETED'))
      }
      if (path === '/api/v1/teaching-plans/plan-1/illustrated-lessons/latest') {
        return Response.json(lessonFixture('lesson-1'))
      }
      return new Response(null, { status: 404 })
    }))
    const { wrapper, router } = await mountHandoff()
    await flushPromises()

    expect(wrapper.text()).toContain('已选《展翅翱翔》')
    expect(wrapper.text()).toContain('Wingspan Rulebook')
    expect(wrapper.text()).toContain('英文（来源已明确标注）')
    expect(wrapper.text()).toContain('出版社 / 权利方来源')
    const reviewStatus = wrapper.get('[data-testid="player-work-status"]')
    expect(reviewStatus.text()).toBe('等待你继续')
    expect(reviewStatus.attributes('data-player-work-terminality')).toBe('waiting')
    expect(requests.find(request => request.path === '/api/v1/bgg/games/266192/import')?.options).toMatchObject({
      method: 'POST',
      headers: { 'X-CSRF-TOKEN': 'csrf' },
    })

    await wrapper.findAll('button').find(button => button.text() === '继续查找文件')!.trigger('click')
    expect(openSource).toHaveBeenCalledWith(
      'https://boardgamegeek.com/filepage/123/rules', '_blank', 'noopener,noreferrer',
    )
    expect(wrapper.text()).toContain('这个结果不是可直接导入的规则书文档')
    expect(wrapper.find('input[type="checkbox"]').exists()).toBe(false)

    await wrapper.get('button[aria-pressed="false"]').trigger('click')
    const importButton = wrapper.findAll('button').find(button => button.text() === '下载规则书并生成讲解')!
    expect(importButton.attributes('disabled')).toBeDefined()
    await confirmIdentityAndRights(wrapper)
    expect(importButton.attributes('disabled')).toBeUndefined()
    expect(requests.some(request => request.path === '/api/v1/documents/official-imports')).toBe(false)

    await importButton.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('提交前目录或来源身份发生了变化')
    expect(wrapper.text()).toContain('展翅翱翔')
    const reconfirmations = wrapper.findAll('input[type="checkbox"]')
    expect(reconfirmations).toHaveLength(2)
    expect((reconfirmations[0]!.element as HTMLInputElement).checked).toBe(false)
    expect((reconfirmations[1]!.element as HTMLInputElement).checked).toBe(true)
    await reconfirmations[0]!.setValue(true)
    await importButton.trigger('click')
    await flushPromises()

    const officialImport = requests.find(request => request.path === '/api/v1/documents/official-imports')
    expect(JSON.parse(String(officialImport?.options?.body))).toEqual({
      editionId: 'edition-1',
      title: 'Wingspan Rulebook',
      sourceType: 'BASE_RULEBOOK',
      officialSourceUrl: 'https://publisher.example/files/wingspan-rulebook.pdf',
      rightsConfirmed: true,
      startTeaching: true,
      learningGoal: null,
      discoveredForEditionId: 'edition-1',
      sourceEdition: 'Base game',
      sourceLanguage: 'en',
      sourceLanguageVerified: true,
      identityConfirmed: true,
    })
    expect(readPendingRulebookLessons(localStorage, 'player')).toEqual([])
    expect(backgroundWorkChanged).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.name).toBe('home')
    await vi.waitFor(() => expect(wrapper.text()).toContain('完整讲解已经生成'))
    expect(wrapper.text()).toContain('打开已生成的讲解')
    expect(wrapper.text()).toContain('切换为规则答疑')
    expect(wrapper.get('a[href="/catalog"]').text()).toContain('我的桌游')
    window.removeEventListener(BACKGROUND_WORK_CHANGED_EVENT, backgroundWorkChanged)
  })

  it('keeps listings and unverified pages actionable without presenting game information as a rulebook', async () => {
    const openSource = vi.fn()
    const requests: string[] = []
    vi.stubGlobal('open', openSource)
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      requests.push(path)
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/bgg/games/266192/import') return Response.json({
        game: { id: 'game-1', name: '展翅翱翔' },
        edition: { id: 'edition-1', name: 'BGG 版本' },
        alreadyImported: false,
      })
      if (path.startsWith('/api/v1/documents/rulebook-candidates?')) return Response.json({
        configured: true,
        identity: discoveryIdentity,
        discovery: {
          completion: 'PARTIAL', elapsedMs: 18_025, totalBudgetMs: 30_000,
          providers: [
            { provider: 'CATALOG', state: 'FINISHED', elapsedMs: 25 },
            { provider: 'SOURCE_INSPECTION', state: 'FINISHED', elapsedMs: 80 },
            { provider: 'WEB_SEARCH', state: 'TIMED_OUT', elapsedMs: 18_000 },
          ],
        },
        candidates: [{
          title: 'Opaque file listing', url: 'https://listing.example/files', publisher: 'Opaque Studio',
          language: 'en', edition: 'First', sourceDomain: 'listing.example', officialDomainVerified: true,
          sourceType: 'PUBLISHER', acquisitionMode: 'SOURCE_PAGE', ...documentListingCapability,
        }, {
          title: 'Opaque catalog entry', url: 'https://catalog.example/game', publisher: 'Opaque Studio',
          language: 'en', edition: 'First', sourceDomain: 'catalog.example', officialDomainVerified: true,
          sourceType: 'PUBLISHER', acquisitionMode: 'SOURCE_PAGE',
          capability: 'GAME_INFO_ONLY', capabilityEvidence: ['EXPLICIT_EMPTY_DOCUMENT_COLLECTION'],
          capabilityCheckedAt: '2026-08-15T12:00:00Z', nextAction: 'USE_FOR_IDENTITY_ONLY',
        }, {
          title: 'Opaque protected page', url: 'https://review.example/login', publisher: 'Opaque Studio',
          language: 'en', edition: 'First', sourceDomain: 'review.example', officialDomainVerified: true,
          sourceType: 'PUBLISHER', acquisitionMode: 'SOURCE_PAGE',
          capability: 'UNVERIFIED_PAGE', capabilityEvidence: ['ACCESS_REQUIRES_LOGIN'],
          capabilityCheckedAt: '2026-08-15T12:00:00Z', nextAction: 'REVIEW_OR_UPLOAD',
        }],
      })
      return new Response(null, { status: 404 })
    }))

    const { wrapper } = await mountHandoff()
    await flushPromises()

    expect(wrapper.text()).toContain('暂未找到可直接导入的规则书')
    expect(wrapper.get('[data-testid="rulebook-discovery-summary"]').text()).toContain('联网搜索：已超时')
    expect(wrapper.findAll('button').some(button => button.text().includes('继续查找'))).toBe(true)
    expect(wrapper.get('[data-capability="GAME_INFO_ONLY"]').find('button').exists()).toBe(false)
    expect(wrapper.get('section[aria-label="仅用于核对桌游身份"]').text()).toContain('Opaque catalog entry')
    expect(wrapper.find('input[type="checkbox"]').exists()).toBe(false)
    const manualFallback = wrapper.findAll('a').find(link => link.text().includes('本地上传'))
    expect(manualFallback?.attributes('href')).toBe('/teach?editionId=edition-1&onboarding=recommendation-agent')

    await wrapper.get('[data-capability="DOCUMENT_LISTING"] button').trigger('click')
    await wrapper.get('[data-capability="UNVERIFIED_PAGE"] button').trigger('click')
    expect(openSource).toHaveBeenNthCalledWith(
      1, 'https://listing.example/files', '_blank', 'noopener,noreferrer',
    )
    expect(openSource).toHaveBeenNthCalledWith(
      2, 'https://review.example/login', '_blank', 'noopener,noreferrer',
    )
    expect(requests).not.toContain('/api/v1/documents/official-imports')
  })

  it('fails old session candidates closed when capability evidence is missing', async () => {
    const openSource = vi.fn()
    vi.stubGlobal('open', openSource)
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 500 })))
    const legacyCandidate = {
      title: 'Legacy PDF candidate', url: 'https://legacy.example/rules.pdf', publisher: 'Legacy Studio',
      language: 'en', edition: 'First', sourceDomain: 'legacy.example', officialDomainVerified: true,
      sourceType: 'PUBLISHER', acquisitionMode: 'DIRECT_PDF',
    }
    sessionStorage.setItem('rulepilot:recommendation-journey:266192', JSON.stringify({
      imported: {
        game: { id: 'game-1', name: '展翅翱翔' },
        edition: { id: 'edition-1', name: 'BGG 版本' },
        alreadyImported: false,
      },
      candidates: [legacyCandidate],
      selected: legacyCandidate,
    }))

    const { wrapper } = await mountHandoff()
    await flushPromises()

    expect(wrapper.text()).toContain('暂未找到可直接导入的规则书')
    expect(wrapper.get('[data-capability="UNVERIFIED_PAGE"] button').text()).toBe('审阅来源页')
    expect(wrapper.find('input[type="checkbox"]').exists()).toBe(false)
    await wrapper.get('[data-capability="UNVERIFIED_PAGE"] button').trigger('click')
    expect(openSource).toHaveBeenCalledWith(
      'https://legacy.example/rules.pdf', '_blank', 'noopener,noreferrer',
    )
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
        identity: discoveryIdentity,
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
          ...confirmedGalleryCapability,
        }],
      })
      if (path === '/api/auth/session') return Response.json({ username: 'player', roles: ['USER'] })
      if (path === '/api/v1/documents/official-imports') return Response.json({
        id: 'gallery-import', stage: 'QUEUED', documentVersionId: null, duplicate: false, errorCode: null,
        teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null,
      }, { status: 202 })
      if (path === '/api/v1/documents/official-imports/gallery-import') return Response.json({
        id: 'gallery-import', stage: 'COMPLETED', documentVersionId: 'version-gallery', duplicate: false, errorCode: null,
        teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'preparation-run-gallery',
      })
      if (path === '/api/v1/assistant-runs/preparation-run-gallery') {
        return Response.json(runSnapshot('preparation-run-gallery', 'COMPLETED'))
      }
      if (path === '/api/v1/document-versions/version-gallery/teaching-plans/latest') {
        return Response.json(planFixture('plan-gallery', 'version-gallery'))
      }
      if (path === '/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=plan-gallery') {
        return Response.json(runSnapshot('teaching-run-gallery', 'COMPLETED'))
      }
      if (path === '/api/v1/teaching-plans/plan-gallery/illustrated-lessons/latest') {
        return Response.json(lessonFixture('lesson-gallery', 'plan-gallery'))
      }
      return new Response(null, { status: 404 })
    }))
    const { wrapper } = await mountHandoff()
    await flushPromises()

    expect(wrapper.text()).toContain('连续规则页图片，可合成为 PDF')
    expect(wrapper.text()).toContain('社区规则书来源（如 BGG / 集石）')
    await wrapper.get('button[aria-pressed="false"]').trigger('click')
    await confirmIdentityAndRights(wrapper)
    await wrapper.findAll('button').find(button => button.text() === '下载规则书并生成讲解')!.trigger('click')
    await flushPromises()

    const request = requests.find(candidate => candidate.path === '/api/v1/documents/official-imports')
    const requestBody = JSON.parse(String(request?.options?.body)) as Record<string, unknown>
    expect(requestBody).toMatchObject({
      title: '官方规则书',
      officialSourceUrl: 'https://www.gstonegames.com/game/doc-1234.html',
      rightsConfirmed: true,
      startTeaching: true,
    })
    expect(requestBody).toMatchObject({
      discoveredForEditionId: 'edition-1',
      sourceEdition: '基础版',
      sourceLanguage: null,
      sourceLanguageVerified: false,
      identityConfirmed: true,
    })
    await vi.waitFor(() => expect(wrapper.text()).toContain('完整讲解已经生成'))
  })

  it('keeps a reused ready rulebook readable when its generated lesson is already complete', async () => {
    const requests: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      requests.push(path)
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/bgg/games/266192/import') return Response.json({
        game: { id: 'game-1', name: '展翅翱翔' }, edition: { id: 'edition-1', name: 'BGG 版本' }, alreadyImported: true,
      })
      if (path.startsWith('/api/v1/documents/rulebook-candidates?')) return Response.json({
        configured: true,
        identity: discoveryIdentity,
        candidates: [{
          title: 'Wingspan Rulebook', url: 'https://publisher.example/wingspan.pdf', publisher: 'Stonemaier Games',
          language: 'English', edition: 'Base game', sourceDomain: 'publisher.example', officialDomainVerified: true,
          sourceType: 'PUBLISHER', acquisitionMode: 'DIRECT_PDF',
          ...confirmedDocumentCapability,
        }],
      })
      if (path === '/api/v1/documents/official-imports') return Response.json({
        id: 'reused-import', stage: 'COMPLETED', documentVersionId: 'version-ready', duplicate: false,
        downloadedBytes: 3_800_293, totalBytes: 3_800_293, errorCode: null, reused: true,
        teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'preparation-complete',
      }, { status: 202 })
      if (path === '/api/v1/document-versions/version-ready/progress/snapshot') return Response.json({
        stage: 'READY', percentage: 100, processedPages: 12, totalPages: 12, complete: true,
      })
      if (path === '/api/v1/assistant-runs/preparation-complete') {
        return Response.json(runSnapshot('preparation-complete', 'COMPLETED'))
      }
      if (path === '/api/v1/document-versions/version-ready/teaching-plans/latest') {
        return Response.json(planFixture('plan-complete', 'version-ready'))
      }
      if (path === '/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=plan-complete') {
        return Response.json(runSnapshot('teaching-complete', 'COMPLETED'))
      }
      if (path === '/api/v1/teaching-plans/plan-complete/illustrated-lessons/latest') {
        return Response.json(lessonFixture('lesson-complete', 'plan-complete'))
      }
      return new Response(null, { status: 404 })
    }))
    const { wrapper } = await mountHandoff()
    await flushPromises()

    await wrapper.get('button[aria-pressed="false"]').trigger('click')
    await confirmIdentityAndRights(wrapper)
    await wrapper.findAll('button').find(button => button.text() === '下载规则书并生成讲解')!.trigger('click')

    await vi.waitFor(
      () => expect(wrapper.text()).toContain('完整讲解已经生成'),
      { timeout: 3_000 },
    )
    expect(wrapper.text()).toContain('原规则书已就绪，可随时与讲解对照阅读。')
    const openRulebook = wrapper.findAll('button').find(button => button.text() === '先阅读原规则书')
    expect(openRulebook).toBeDefined()
    await openRulebook!.trigger('click')
    expect(wrapper.emitted('open-rulebook')).toHaveLength(1)
    expect(requests.filter(path => path === '/api/v1/documents/official-imports')).toHaveLength(1)
    expect(requests).toContain('/api/v1/document-versions/version-ready/progress/snapshot')
  })

  it('retries failed teaching preparation without downloading or binding the game twice', async () => {
    const requests: Array<{ path: string; options?: RequestInit }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      requests.push({ path, options })
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/bgg/games/266192/import') return Response.json({
        game: { id: 'game-1', name: '展翅翱翔' }, edition: { id: 'edition-1', name: 'BGG 版本' }, alreadyImported: false,
      })
      if (path.startsWith('/api/v1/documents/rulebook-candidates?')) return Response.json({
        configured: true,
        identity: discoveryIdentity,
        candidates: [{
          title: 'Wingspan Rulebook', url: 'https://publisher.example/wingspan.pdf', publisher: 'Stonemaier Games',
          language: 'English', edition: 'Base game', sourceDomain: 'publisher.example', officialDomainVerified: true,
          sourceType: 'PUBLISHER', acquisitionMode: 'DIRECT_PDF',
          ...confirmedDocumentCapability,
        }],
      })
      if (path === '/api/v1/documents/official-imports') return Response.json({
        id: 'import-1', stage: 'COMPLETED', documentVersionId: 'version-1', duplicate: false, errorCode: null,
        teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'preparation-failed',
      }, { status: 202 })
      if (path === '/api/v1/assistant-runs/preparation-failed') {
        const snapshot = runSnapshot('preparation-failed', 'FAILED')
        const failed = { ...snapshot, run: { ...snapshot.run, lastErrorCode: 'TEACHING_PREPARATION_FAILED' } }
        return Response.json(failed)
      }
      if (path === '/api/v1/documents/official-imports/import-1/teaching-retry' && options?.method === 'POST') {
        return Response.json({
          id: 'import-1', stage: 'COMPLETED', documentVersionId: 'version-1', duplicate: false, errorCode: null,
          teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'preparation-retry',
        }, { status: 202 })
      }
      if (path === '/api/v1/assistant-runs/preparation-retry') return Response.json(runSnapshot('preparation-retry', 'COMPLETED'))
      if (path === '/api/v1/document-versions/version-1/teaching-plans/latest') return Response.json(planFixture('plan-1', 'version-1'))
      if (path === '/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=plan-1') return Response.json(runSnapshot('teaching-run-1', 'COMPLETED'))
      if (path === '/api/v1/teaching-plans/plan-1/illustrated-lessons/latest') return Response.json(lessonFixture('lesson-1'))
      return new Response(null, { status: 404 })
    }))
    const { wrapper } = await mountHandoff()
    await flushPromises()
    await wrapper.get('button[aria-pressed="false"]').trigger('click')
    await confirmIdentityAndRights(wrapper)
    await wrapper.findAll('button').find(button => button.text() === '下载规则书并生成讲解')!.trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('需要处理'))
    expect(wrapper.text()).not.toContain('TEACHING_PREPARATION_FAILED')
    expect(wrapper.get('[data-testid="player-work-status"]').text()).toBe('需要处理')

    await wrapper.findAll('button').find(button => button.text() === '重试当前步骤')!.trigger('click')
    await vi.waitFor(
      () => expect(wrapper.text()).toContain('完整讲解已经生成'),
      { timeout: 3_000 },
    )

    expect(requests.filter(request => request.path === '/api/v1/bgg/games/266192/import')).toHaveLength(1)
    expect(requests.filter(request => request.path === '/api/v1/documents/official-imports')).toHaveLength(1)
    const retry = requests.find(request => request.path === '/api/v1/documents/official-imports/import-1/teaching-retry')
    expect(JSON.parse(String(retry?.options?.body))).toEqual({ expectedPreparationRunId: 'preparation-failed' })
    expect(requests.filter(request => request.path === '/api/v1/document-versions/version-1/teaching-plans')).toHaveLength(0)
  })

  it('keeps a published draft readable and exposes a safe retry when later teaching review degrades', async () => {
    const requests: Array<{ path: string; options?: RequestInit }> = []
    let lessonRequest = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      requests.push({ path, options })
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/bgg/games/266192/import') return Response.json({
        game: { id: 'game-1', name: '展翅翱翔' }, edition: { id: 'edition-1', name: 'BGG 版本' }, alreadyImported: false,
      })
      if (path.startsWith('/api/v1/documents/rulebook-candidates?')) return Response.json({
        configured: true,
        identity: discoveryIdentity,
        candidates: [{
          title: 'Wingspan Rulebook', url: 'https://publisher.example/wingspan.pdf', publisher: 'Stonemaier Games',
          language: 'English', edition: 'Base game', sourceDomain: 'publisher.example', officialDomainVerified: true,
          sourceType: 'PUBLISHER', acquisitionMode: 'DIRECT_PDF',
          ...confirmedDocumentCapability,
        }],
      })
      if (path === '/api/v1/documents/official-imports') return Response.json({
        id: 'import-1', stage: 'COMPLETED', documentVersionId: 'version-1', duplicate: false, errorCode: null,
        teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'preparation-run-1',
      }, { status: 202 })
      if (path === '/api/v1/assistant-runs/preparation-run-1') return Response.json(runSnapshot('preparation-run-1', 'COMPLETED'))
      if (path === '/api/v1/document-versions/version-1/teaching-plans/latest') return Response.json(planFixture('plan-1', 'version-1'))
      if (path === '/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=plan-1') {
        const snapshot = runSnapshot('teaching-degraded', 'DEGRADED')
        const degraded = { ...snapshot, run: { ...snapshot.run, lastErrorCode: 'REVIEW_UNAVAILABLE' } }
        return Response.json(degraded)
      }
      if (path === '/api/v1/teaching-plans/plan-1/illustrated-lessons/latest') {
        lessonRequest += 1
        return Response.json(lessonRequest === 1
          ? { ...lessonFixture('lesson-1'), status: 'DRAFT_READY' }
          : lessonFixture('lesson-1'))
      }
      if (path === '/api/v1/teaching-plans/plan-1/illustrated-lessons' && options?.method === 'POST') {
        return Response.json({ assistantRunId: 'teaching-retry', state: 'QUEUED', reused: false }, { status: 202 })
      }
      if (path === '/api/v1/assistant-runs/teaching-retry') return Response.json(runSnapshot('teaching-retry', 'COMPLETED'))
      return new Response(null, { status: 404 })
    }))
    const { wrapper } = await mountHandoff()
    await flushPromises()
    await wrapper.get('button[aria-pressed="false"]').trigger('click')
    await confirmIdentityAndRights(wrapper)
    await wrapper.findAll('button').find(button => button.text() === '下载规则书并生成讲解')!.trigger('click')

    await vi.waitFor(() => expect(wrapper.text()).toContain('已生成的章节仍可阅读'))
    const status = wrapper.get('[data-testid="player-work-status"]')
    expect(status.text()).toBe('基础讲解可读')
    expect(status.attributes('data-player-work-readiness')).toBe('usable')
    expect(status.attributes('data-player-work-outcome')).toBe('needs-action')
    expect(wrapper.text()).not.toContain('REVIEW_UNAVAILABLE')
    expect(wrapper.text()).toContain('打开已生成的讲解')
    await wrapper.findAll('button').find(button => button.text() === '重试当前步骤')!.trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('完整讲解已经生成'))

    expect(requests.filter(request => request.path === '/api/v1/documents/official-imports')).toHaveLength(1)
    expect(requests.filter(request => request.path === '/api/v1/teaching-plans/plan-1/illustrated-lessons')).toHaveLength(1)
  })

  it('polls quickly only until the first published chapter becomes readable', async () => {
    vi.useFakeTimers()
    let wrapper: Awaited<ReturnType<typeof mountHandoff>>['wrapper'] | undefined
    try {
      sessionStorage.setItem('rulepilot:recommendation-journey:266192', JSON.stringify({
        imported: {
          game: { id: 'game-1', name: '展翅翱翔' },
          edition: { id: 'edition-1', name: 'BGG 版本' },
          alreadyImported: false,
        },
        importJob: {
          id: 'import-1', stage: 'COMPLETED', documentVersionId: 'version-1', duplicate: false, errorCode: null,
          teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'preparation-run-1',
        },
        preparationRunId: 'preparation-run-1',
      }))
      let lessonRequests = 0
      vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
        const path = String(input)
        if (path === '/api/v1/document-versions/version-1/progress/snapshot') {
          return Response.json({ stage: 'READY', percentage: 100, processedPages: 12, totalPages: 12, complete: true })
        }
        if (path === '/api/v1/assistant-runs/preparation-run-1') {
          return Response.json({
            ...runSnapshot('preparation-run-1', 'COMPLETED'),
            activities: [{
              sequence: 1,
              operation: 'organizeTeachingOutline',
              summary: 'internal outline prompt',
              outcome: 'SUCCEEDED',
            }],
          })
        }
        if (path === '/api/v1/document-versions/version-1/teaching-plans/latest') {
          return Response.json(planFixture('plan-1', 'version-1'))
        }
        if (path === '/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=plan-1') {
          return Response.json({
            ...runSnapshot('teaching-run-1', 'LESSON_COMPOSITION'),
            activities: [
              {
                sequence: 1,
                operation: 'readTeachingSourcePages|1',
                summary: 'internal page read',
                outcome: 'SUCCEEDED',
              },
              {
                sequence: 2,
                operation: 'composeTeachingSection|1',
                summary: 'internal model operation',
                outcome: 'RUNNING',
              },
            ],
          })
        }
        if (path === '/api/v1/teaching-plans/plan-1/illustrated-lessons/latest') {
          lessonRequests += 1
          return lessonRequests === 1
            ? new Response(null, { status: 404 })
            : Response.json({ ...lessonFixture('lesson-1'), status: 'DRAFT_READY' })
        }
        return new Response(null, { status: 404 })
      }))

      const mounted = await mountHandoff()
      wrapper = mounted.wrapper
      await vi.advanceTimersByTimeAsync(0)
      await flushPromises()
      expect(lessonRequests).toBe(1)
      expect(wrapper.text()).not.toContain('讲解已有可读内容')
      const generationSteps = wrapper.get('[data-testid="recommendation-teaching-generation-steps"]')
      expect(generationSteps.text()).toContain('已形成整局认识，并把规则书整理成可讲解的章节')
      expect(generationSteps.text()).toContain('正在读取第 1 章“Setup”引用的规则书页面')
      expect(generationSteps.text()).toContain('正在依据规则书编写第 1 章“Setup”')
      expect(generationSteps.text()).not.toContain('readTeachingSourcePages')
      expect(generationSteps.text()).not.toContain('internal model operation')
      expect(generationSteps.text()).not.toContain('internal outline prompt')
      expect(generationSteps.text().indexOf('正在依据规则书编写第 1 章“Setup”'))
        .toBeLessThan(generationSteps.text().indexOf('已形成整局认识，并把规则书整理成可讲解的章节'))

      await vi.advanceTimersByTimeAsync(499)
      await flushPromises()
      expect(lessonRequests).toBe(1)

      await vi.advanceTimersByTimeAsync(1)
      await flushPromises()
      expect(lessonRequests).toBe(2)
      expect(wrapper.text()).toContain('讲解已有可读内容')
      expect(vi.getTimerCount()).toBe(1)

      await vi.runOnlyPendingTimersAsync()
      await flushPromises()
      expect(lessonRequests).toBe(3)
    } finally {
      wrapper?.unmount()
      vi.useRealTimers()
    }
  })

  it('uses the owned cross-runtime progress stream instead of waiting for the next journey poll', async () => {
    vi.useFakeTimers()
    let wrapper: Awaited<ReturnType<typeof mountHandoff>>['wrapper'] | undefined
    try {
      FakeProgressEventSource.instances = []
      vi.stubGlobal('EventSource', FakeProgressEventSource)
      sessionStorage.setItem('rulepilot:recommendation-journey:266192', JSON.stringify({
        imported: {
          game: { id: 'game-1', name: '展翅翱翔' },
          edition: { id: 'edition-1', name: 'BGG 版本' },
          alreadyImported: false,
        },
        importJob: {
          id: 'import-1', stage: 'COMPLETED', documentVersionId: 'version-stream', duplicate: false, errorCode: null,
          teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null,
        },
      }))
      let importReads = 0
      const requests: string[] = []
      vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
        const path = String(input)
        requests.push(path)
        if (path === '/api/v1/documents/official-imports/import-1') {
          importReads += 1
          return Response.json(importReads === 1 ? {
            id: 'import-1', stage: 'COMPLETED', documentVersionId: 'version-stream', duplicate: false, errorCode: null,
            teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null,
          } : {
            id: 'import-1', stage: 'COMPLETED', documentVersionId: 'version-stream', duplicate: false, errorCode: null,
            teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'preparation-stream',
          })
        }
        if (path === '/api/v1/document-versions/version-stream/progress/snapshot') {
          return Response.json({
            stage: 'RENDERING', percentage: 55, processedPages: 4, totalPages: 12, complete: false,
          })
        }
        if (path === '/api/v1/assistant-runs/preparation-stream') {
          return Response.json(runSnapshot('preparation-stream', 'LESSON_PLANNING'))
        }
        return new Response(null, { status: 404 })
      }))

      const mounted = await mountHandoff()
      wrapper = mounted.wrapper
      await vi.advanceTimersByTimeAsync(0)
      await flushPromises()
      expect(FakeProgressEventSource.instances).toHaveLength(1)
      const source = FakeProgressEventSource.instances[0]!
      expect(source.url).toBe('/api/v1/document-versions/version-stream/progress')
      expect(source.options).toEqual({ withCredentials: true })
      expect(wrapper.text()).toContain('第 4 / 12 页')
      expect(importReads).toBe(1)

      source.emitProgress({
        stage: 'READY', percentage: 100, processedPages: 12, totalPages: 12, complete: true,
      })
      await flushPromises()

      expect(source.closed).toBe(true)
      expect(wrapper.text()).toContain('规则书已经可以阅读')
      expect(importReads).toBe(1)
      expect(requests.filter(path => path.endsWith('/progress/snapshot'))).toHaveLength(1)

      await vi.advanceTimersByTimeAsync(0)
      await flushPromises()
      expect(importReads).toBe(2)
      expect(wrapper.text()).toContain('正在通读规则书并组织讲解章节')
      const status = wrapper.get('[data-testid="player-work-status"]')
      expect(status.text()).toBe('正在组织讲解')
      expect(status.attributes('data-player-work-capability')).toBe('rulebook')
      const generationSteps = wrapper.get('[data-testid="recommendation-teaching-generation-steps"]')
      expect(generationSteps.text()).toContain('通读整本规则书，形成整局认识并规划章节')
      expect(generationSteps.text()).toContain('图片规则书先逐页识别可见文字')
      expect(generationSteps.text()).toContain('按页面整理规则组')
      expect(generationSteps.text()).toContain('读取当前章节绑定的规则页与引用')
      expect(generationSteps.text()).toContain('依据原文生成玩家可以直接照做的讲解步骤')
      expect(generationSteps.text()).toContain('校验引用归属、规则书版本与章节结构')
      expect(generationSteps.text()).toContain('通过后立即发布当前章节')
      expect(generationSteps.text()).toContain('正在通读整本规则书，形成整局认识并规划讲解章节')
      expect(wrapper.text()).toContain('规划中')
      expect(wrapper.text()).not.toContain('84%')
      expect(wrapper.find('[role="progressbar"]').exists()).toBe(false)
    } finally {
      wrapper?.unmount()
      vi.useRealTimers()
    }
  })

  it('closes the recommendation progress stream on unmount and ignores buffered events', async () => {
    FakeProgressEventSource.instances = []
    vi.stubGlobal('EventSource', FakeProgressEventSource)
    sessionStorage.setItem('rulepilot:recommendation-journey:266192', JSON.stringify({
      imported: {
        game: { id: 'game-1', name: '展翅翱翔' },
        edition: { id: 'edition-1', name: 'BGG 版本' },
        alreadyImported: false,
      },
      importJob: {
        id: 'import-1', stage: 'COMPLETED', documentVersionId: 'version-stream', duplicate: false, errorCode: null,
        teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null,
      },
    }))
    const requests: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      requests.push(path)
      if (path === '/api/v1/documents/official-imports/import-1') return Response.json({
        id: 'import-1', stage: 'COMPLETED', documentVersionId: 'version-stream', duplicate: false, errorCode: null,
        teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null,
      })
      if (path === '/api/v1/document-versions/version-stream/progress/snapshot') return Response.json({
        stage: 'RENDERING', percentage: 55, processedPages: 4, totalPages: 12, complete: false,
      })
      return new Response(null, { status: 404 })
    }))

    const { wrapper } = await mountHandoff()
    await vi.waitFor(() => expect(FakeProgressEventSource.instances).toHaveLength(1))
    const source = FakeProgressEventSource.instances[0]!
    const callsBeforeUnmount = requests.length

    wrapper.unmount()
    source.emitProgress({
      stage: 'READY', percentage: 100, processedPages: 12, totalPages: 12, complete: true,
    })
    await flushPromises()

    expect(source.closed).toBe(true)
    expect(requests).toHaveLength(callsBeforeUnmount)
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
        identity: discoveryIdentity,
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
          ...confirmedDocumentCapability,
        }],
      })
      if (path === '/api/auth/session') return Response.json({ username: 'player', roles: ['USER'] })
      if (path === '/api/v1/documents/official-imports') return Response.json({
        id: 'bgg-import', stage: 'QUEUED', documentVersionId: null, duplicate: false, errorCode: null,
        teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null,
      }, { status: 202 })
      if (path === '/api/v1/documents/official-imports/bgg-import') return Response.json({
        id: 'bgg-import', stage: 'FAILED', documentVersionId: null, duplicate: false,
        errorCode: 'SOURCE_BROWSER_REQUIRED',
        teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null,
      })
      return new Response(null, { status: 404 })
    }))
    const { wrapper } = await mountHandoff()
    await flushPromises()

    await wrapper.get('button[aria-pressed="false"]').trigger('click')
    await confirmIdentityAndRights(wrapper)
    await wrapper.findAll('button').find(button => button.text() === '下载规则书并生成讲解')!.trigger('click')
    await flushPromises()

    await vi.waitFor(() => expect(wrapper.text()).toContain('已经找到这份文件'))
    const bggLink = wrapper.findAll('a').find(link => link.text().includes('在来源网站继续下载'))!
    expect(bggLink.attributes('href')).toBe(
      'https://boardgamegeek.com/file/download_redirect/c66d839e5ef882cf86295abc25caef76456ef0ed43746421/wingspan-rules.pdf',
    )
    expect(wrapper.text()).toContain('本地上传')
    const chooseAnother = wrapper.findAll('button').find(button => button.text() === '重新选择来源')
    expect(chooseAnother).toBeDefined()
    await chooseAnother!.trigger('click')
    expect(wrapper.text()).toContain('选择并核对来源')
    expect(wrapper.text()).toContain('展翅翱翔')
    expect(wrapper.findAll('input[type="checkbox"]')
      .every(input => !(input.element as HTMLInputElement).checked)).toBe(true)
  })

  it('retries a temporary import through the owned failed job instead of submitting the source again', async () => {
    const requests: Array<{ path: string; options?: RequestInit }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      requests.push({ path, options })
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/bgg/games/266192/import') return Response.json({
        game: { id: 'game-1', name: '展翅翱翔' },
        edition: { id: 'edition-1', name: 'BGG 版本', language: 'zh-CN' },
        alreadyImported: false,
      })
      if (path.startsWith('/api/v1/documents/rulebook-candidates?')) return Response.json({
        configured: true,
        identity: discoveryIdentity,
        candidates: [{
          title: 'Publisher rules', url: 'https://publisher.example/rules.pdf', publisher: 'Publisher',
          language: 'zh-CN', languageVerified: true, edition: 'Base game',
          sourceDomain: 'publisher.example', officialDomainVerified: true,
          sourceType: 'PUBLISHER', acquisitionMode: 'DIRECT_PDF', ...confirmedDocumentCapability,
        }],
      })
      if (path === '/api/v1/documents/official-imports') return Response.json({
        id: 'failed-import', stage: 'QUEUED', downloadedBytes: 0, totalBytes: null,
        documentVersionId: null, duplicate: false, errorCode: null,
        teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null,
      }, { status: 202 })
      if (path === '/api/v1/documents/official-imports/failed-import') return Response.json({
        id: 'failed-import', stage: 'FAILED', downloadedBytes: 0, totalBytes: null,
        documentVersionId: null, duplicate: false, errorCode: 'SOURCE_UNAVAILABLE',
        teachingHandoffState: 'FAILED', teachingPreparationRunId: null,
        recovery: {
          state: 'FAILED', failureKind: 'TEMPORARY_SOURCE', busy: false,
          canChooseAnotherSource: true, canUseLocalUpload: true,
          canRetryOriginalSource: true, canOpenSourceInBrowser: false,
        },
      })
      if (path === '/api/v1/documents/official-imports/failed-import/retry' && options?.method === 'POST') {
        return Response.json({
          id: 'retried-import', stage: 'QUEUED', downloadedBytes: 0, totalBytes: null,
          documentVersionId: null, duplicate: false, errorCode: null,
          teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null,
        }, { status: 202 })
      }
      if (path === '/api/v1/documents/official-imports/retried-import') return Response.json({
        id: 'retried-import', stage: 'QUEUED', downloadedBytes: 0, totalBytes: null,
        documentVersionId: null, duplicate: false, errorCode: null,
        teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null,
      })
      return new Response(null, { status: 404 })
    }))
    const { wrapper } = await mountHandoff()
    await flushPromises()
    await wrapper.get('button[aria-pressed="false"]').trigger('click')
    await confirmIdentityAndRights(wrapper)
    await wrapper.findAll('button').find(button => button.text() === '下载规则书并生成讲解')!.trigger('click')

    await vi.waitFor(() => expect(wrapper.text()).toContain('规则书来源暂时无法连接'))
    await wrapper.findAll('button').find(button => button.text() === '重试原来源')!.trigger('click')
    await flushPromises()

    expect(requests.filter(request => request.path === '/api/v1/documents/official-imports')).toHaveLength(1)
    expect(requests.find(request => request.path === '/api/v1/documents/official-imports/failed-import/retry')?.options)
      .toMatchObject({ method: 'POST', headers: { 'X-CSRF-TOKEN': 'csrf' } })
    expect(wrapper.text()).toContain('规则书下载已排队')
    wrapper.unmount()
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
      if (path.startsWith('/api/v1/documents/rulebook-candidates?')) {
        return Response.json({ configured: false, identity: discoveryIdentity, candidates: [] })
      }
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
