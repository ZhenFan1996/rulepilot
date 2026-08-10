import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import RecommendationGameDetailsDialog from './RecommendationGameDetailsDialog.vue'
import { setLocale } from '@/lib/locale'

const game = {
  bggId: 266192, name: '展翅翱翔', originalName: 'Wingspan', nameLocalized: true, publicationYear: 2019,
  overallRank: 34, geekRating: 7.79, averageRating: 8.09, usersRated: 102030,
  thumbnailUrl: 'https://example.test/thumb.jpg', minPlayers: 1, maxPlayers: 5,
  playingTimeMinutes: 70, averageWeight: 2.5, categories: ['Animals'], mechanics: ['Card Drafting'],
  bggUrl: 'https://boardgamegeek.com/boardgame/266192',
}

describe('RecommendationGameDetailsDialog', () => {
  beforeEach(() => setLocale('zh-CN'))
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('shows source details immediately, then replaces them with localized details without leaving the page', async () => {
    let resolveLocalized!: (response: Response) => void
    const localized = new Promise<Response>(resolve => { resolveLocalized = resolve })
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
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
  })
})
