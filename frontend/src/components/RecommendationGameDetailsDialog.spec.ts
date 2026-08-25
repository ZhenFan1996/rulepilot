import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import RecommendationGameDetailsDialog from './RecommendationGameDetailsDialog.vue'
import { setLocale } from '@/lib/locale'

const game = {
  bggId: 266192, name: '展翅翱翔', originalName: 'Wingspan', nameLocalized: true, publicationYear: 2019,
  overallRank: 34, geekRating: 7.79, averageRating: 8.09, usersRated: 102030,
  thumbnailUrl: 'https://example.test/thumb.jpg', minPlayers: 1, maxPlayers: 5,
  playingTimeMinutes: 70, minimumPlayTimeMinutes: 40, maximumPlayTimeMinutes: 90,
  minimumAge: 10, suggestedMinimumAge: 12, bestWith: 'Best with 3 players', recommendedWith: 'Recommended with 2–4 players',
  languageDependenceLevel: 2, averageWeight: 2.5, weightVotes: 987,
  categories: ['Animals'], mechanics: ['Card Drafting'], families: ['Animals: Birds'],
  designers: ['Elizabeth Hargrave'], publishers: ['Stonemaier Games'],
  bggUrl: 'https://boardgamegeek.com/boardgame/266192',
}

describe('RecommendationGameDetailsDialog', () => {
  beforeEach(() => setLocale('zh-CN'))
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('shows prop identity and starts its cover while source details are pending', async () => {
    let resolveSource!: (response: Response) => void
    const source = new Promise<Response>(resolve => { resolveSource = resolve })
    vi.stubGlobal('fetch', vi.fn(() => source))

    const wrapper = mount(RecommendationGameDetailsDialog, { props: { game, open: true } })
    await flushPromises()

    expect(wrapper.get('h2').text()).toBe('展翅翱翔')
    expect(wrapper.get('[data-testid="recommendation-details-loading"]').text()).toContain('正在读取详细资料')
    expect(wrapper.get('img[alt="展翅翱翔"]').attributes('src'))
      .toBe('/api/v1/bgg/catalog/covers/266192/thumbnail')
    expect(wrapper.find('[data-cover-kind="display"]').exists()).toBe(false)

    wrapper.unmount()
    resolveSource(Response.json(game))
  })

  it('shows source details immediately, then replaces them with localized details without leaving the page', async () => {
    let resolveLocalized!: (response: Response) => void
    const localized = new Promise<Response>(resolve => { resolveLocalized = resolve })
    const fetchMock = vi.fn(async (input: string | URL | Request, _options?: RequestInit) => {
      const path = String(input)
      if (path.includes('translate=false')) return Response.json({
        ...game,
        description: 'Build a habitat for birds.', imageUrl: 'https://example.test/full.jpg', minimumAge: 10,
        designers: ['Elizabeth Hargrave'], publishers: ['Stonemaier Games'],
        categories: ['Animals'], mechanics: ['Card Drafting'], officialNameLocalized: false,
        descriptionTranslated: false, categoriesTranslated: false, mechanicsTranslated: false,
        editionImages: [{ versionId: 1, name: 'English edition', imageUrl: 'https://example.test/edition.jpg', publicationYear: 2019, languages: ['English'] }],
      })
      return localized
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RecommendationGameDetailsDialog, { props: { game, open: true } })
    await flushPromises()

    expect(wrapper.text()).toContain('Build a habitat for birds.')
    expect(wrapper.text()).toContain('原文已显示，中文资料正在补齐')
    expect(wrapper.text()).toContain('40–90 分钟')
    expect(wrapper.text()).toContain('玩家建议 12 岁以上')
    expect(wrapper.text()).toContain('语言依赖 2 / 5')
    expect(wrapper.text()).toContain('复杂度 2.5 / 5（987 票）')
    expect(wrapper.text()).toContain('Animals: Birds')
    expect(wrapper.text()).toContain('Best with 3 players')
    expect(wrapper.get('img[alt="展翅翱翔"]').attributes('src'))
      .toBe('/api/v1/bgg/catalog/covers/266192/thumbnail')
    expect(wrapper.find('img[aria-hidden="true"]').exists()).toBe(false)
    await wrapper.get('img[alt="展翅翱翔"]').trigger('load')
    expect(wrapper.get('img[aria-hidden="true"]').attributes('src'))
      .toBe('/api/v1/bgg/catalog/covers/266192/image')
    await wrapper.get('img[aria-hidden="true"]').trigger('error')
    expect(wrapper.find('img[aria-hidden="true"]').exists()).toBe(false)
    expect(wrapper.get('img[alt="展翅翱翔"]').attributes('src'))
      .toBe('/api/v1/bgg/catalog/covers/266192/thumbnail')
    expect(wrapper.get('a[href="https://boardgamegeek.com/boardgame/266192"]').attributes('target')).toBe('_blank')

    resolveLocalized(Response.json({
      ...game,
      name: '展翅翱翔', description: '为鸟类营造栖息地。', imageUrl: 'https://example.test/full.jpg', minimumAge: 10,
      designers: ['Elizabeth Hargrave'], publishers: ['Stonemaier Games'], categories: ['动物'], mechanics: ['卡牌轮抽'],
      officialNameLocalized: true, descriptionTranslated: true, categoriesTranslated: true, mechanicsTranslated: true,
      editionImages: [],
    }))
    await flushPromises()

    expect(wrapper.text()).toContain('为鸟类营造栖息地。')
    expect(wrapper.text()).toContain('译自 BGG 原文')
    expect(wrapper.text()).toContain('卡牌轮抽')
    await wrapper.findAll('button').find(button => button.text() === '选这款，继续找规则书')!.trigger('click')
    expect(wrapper.emitted('select')?.[0]?.[0]).toEqual(game)
    expect(fetchMock.mock.calls.map(([input]) => String(input))).toEqual([
      '/api/v1/bgg/games/266192?locale=zh-CN&translate=false',
      '/api/v1/bgg/games/266192?locale=zh-CN&translate=true',
    ])
    expect(fetchMock.mock.calls.every(([, options]) => options?.signal instanceof AbortSignal)).toBe(true)
  })

  it('aborts a previous card request and never commits its delayed result', async () => {
    let resolveOld!: (response: Response) => void
    const oldResponse = new Promise<Response>(resolve => { resolveOld = resolve })
    let oldSignal: AbortSignal | undefined
    const nextGame = { ...game, bggId: 13, name: '璀璨宝石', originalName: 'Splendor', bggUrl: 'https://boardgamegeek.com/boardgame/13' }
    const fetchMock = vi.fn((input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path.includes('/266192')) {
        oldSignal = options?.signal ?? undefined
        return oldResponse
      }
      return Promise.resolve(Response.json({ ...nextGame, description: '收集宝石发展卡。', imageUrl: '' }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RecommendationGameDetailsDialog, { props: { game, open: true } })
    await flushPromises()

    await wrapper.setProps({ game: nextGame })
    await flushPromises()
    expect(oldSignal?.aborted).toBe(true)
    expect(wrapper.text()).toContain('收集宝石发展卡。')

    resolveOld(Response.json({ ...game, description: '迟到的旧资料。', imageUrl: '' }))
    await flushPromises()
    expect(wrapper.text()).toContain('收集宝石发展卡。')
    expect(wrapper.text()).not.toContain('迟到的旧资料。')
    await wrapper.findAll('button').find(button => button.text() === '选这款，继续找规则书')!.trigger('click')
    expect(wrapper.emitted('select')?.[0]?.[0]).toEqual(nextGame)
  })

  it('cancels work on close and does not start localization from a delayed source response', async () => {
    let resolveSource!: (response: Response) => void
    const source = new Promise<Response>(resolve => { resolveSource = resolve })
    let sourceSignal: AbortSignal | undefined
    const fetchMock = vi.fn((_input: string | URL | Request, options?: RequestInit) => {
      sourceSignal = options?.signal ?? undefined
      return source
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RecommendationGameDetailsDialog, { props: { game, open: true } })
    await flushPromises()

    await wrapper.setProps({ open: false })
    expect(sourceSignal?.aborted).toBe(true)
    resolveSource(Response.json({ ...game, description: '已经关闭。', imageUrl: '' }))
    await flushPromises()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('reopens with a new request generation that a delayed closed response cannot replace', async () => {
    let resolveClosed!: (response: Response) => void
    const closedResponse = new Promise<Response>(resolve => { resolveClosed = resolve })
    let sourceRequests = 0
    let closedSignal: AbortSignal | undefined
    const fetchMock = vi.fn((input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path.includes('translate=false')) {
        sourceRequests += 1
        if (sourceRequests === 1) {
          closedSignal = options?.signal ?? undefined
          return closedResponse
        }
      }
      return Promise.resolve(Response.json({ ...game, description: '重新打开后的资料。', imageUrl: '' }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RecommendationGameDetailsDialog, { props: { game, open: true } })
    await flushPromises()

    await wrapper.setProps({ open: false })
    await wrapper.setProps({ open: true })
    await flushPromises()
    expect(closedSignal?.aborted).toBe(true)
    expect(wrapper.text()).toContain('重新打开后的资料。')

    resolveClosed(Response.json({ ...game, description: '关闭前的迟到资料。', imageUrl: '' }))
    await flushPromises()
    expect(wrapper.text()).toContain('重新打开后的资料。')
    expect(wrapper.text()).not.toContain('关闭前的迟到资料。')
  })

  it('aborts localization on locale change and keeps the new language response', async () => {
    let resolveLocalized!: (response: Response) => void
    const localized = new Promise<Response>(resolve => { resolveLocalized = resolve })
    let localizedSignal: AbortSignal | undefined
    const fetchMock = vi.fn((input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path.includes('locale=zh-CN') && path.includes('translate=true')) {
        localizedSignal = options?.signal ?? undefined
        return localized
      }
      if (path.includes('locale=en')) return Promise.resolve(Response.json({
        ...game, name: 'Wingspan', originalName: 'Wingspan', description: 'Current English details.', imageUrl: '',
      }))
      return Promise.resolve(Response.json({ ...game, description: 'Source details.', imageUrl: '' }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RecommendationGameDetailsDialog, { props: { game, open: true } })
    await flushPromises()

    setLocale('en')
    await flushPromises()
    expect(localizedSignal?.aborted).toBe(true)
    expect(wrapper.text()).toContain('Current English details.')

    resolveLocalized(Response.json({ ...game, description: '迟到的中文资料。', imageUrl: '' }))
    await flushPromises()
    expect(wrapper.text()).toContain('Current English details.')
    expect(wrapper.text()).not.toContain('迟到的中文资料。')
  })

  it('rejects mismatched response identity instead of pairing it with the card selection', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({
      ...game,
      bggId: 13,
      name: 'Wrong Game',
      description: 'Wrong identity.',
      imageUrl: '',
    })))
    const wrapper = mount(RecommendationGameDetailsDialog, { props: { game, open: true } })
    await flushPromises()

    expect(wrapper.text()).toContain('暂时无法读取详细资料。')
    expect(wrapper.text()).not.toContain('Wrong identity.')
    expect(wrapper.get('img[alt="展翅翱翔"]').attributes('src'))
      .toBe('/api/v1/bgg/catalog/covers/266192/thumbnail')
    expect(wrapper.findAll('button').some(button => button.text() === '选这款，继续找规则书')).toBe(false)
  })

  it('aborts a pending request when the dialog component unmounts', async () => {
    let resolveSource!: (response: Response) => void
    const source = new Promise<Response>(resolve => { resolveSource = resolve })
    let signal: AbortSignal | undefined
    const fetchMock = vi.fn((_input: string | URL | Request, options?: RequestInit) => {
      signal = options?.signal ?? undefined
      return source
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RecommendationGameDetailsDialog, { props: { game, open: true } })
    await flushPromises()

    wrapper.unmount()
    expect(signal?.aborted).toBe(true)
    resolveSource(Response.json({ ...game, description: 'After unmount.', imageUrl: '' }))
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
