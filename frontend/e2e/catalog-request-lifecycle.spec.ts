import { expect, test } from '@playwright/test'

const firstBase = game(42, '第一页基础资料')
const secondBase = game(43, '第二页基础资料')

test('promotes the next-page prefetch and never starts a second enrichment request', async ({ page }) => {
  let releasePageOne!: () => void
  const pageOneGate = new Promise<void>(resolve => { releasePageOne = resolve })
  let pageOneBaseRequests = 0
  let enrichmentRequests = 0

  await page.route('**/api/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/auth/session') return route.fulfill({ status: 401, json: {} })
    if (url.pathname === '/api/v1/bgg/catalog') {
      const requestedPage = Number(url.searchParams.get('page'))
      const enriched = url.searchParams.get('enrich') === 'true'
      if (enriched) {
        enrichmentRequests += 1
        return route.fulfill({ status: 500 })
      }
      if (requestedPage === 0 && !enriched) return route.fulfill({ json: catalogPage(0, firstBase, false) })
      if (requestedPage === 1 && !enriched) {
        pageOneBaseRequests += 1
        await pageOneGate
        return route.fulfill({ json: catalogPage(1, secondBase, false) })
      }
    }
    if (url.pathname === '/api/v1/assistant-runs/active') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents/official-imports') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents/upload-teaching-handoffs') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents') return route.fulfill({ json: [] })
    return route.fulfill({ status: 404 })
  })

  await page.goto('/discover/catalog')
  await expect(page.getByRole('heading', { name: '第一页基础资料' })).toBeVisible()
  await page.getByRole('button', { name: '前往第 2 页' }).click()
  expect(pageOneBaseRequests).toBe(1)

  releasePageOne()
  await expect(page.getByRole('heading', { name: '第二页基础资料' })).toBeVisible()
  expect(pageOneBaseRequests).toBe(1)
  expect(enrichmentRequests).toBe(0)
  await expect(page.getByText('第 2 / 2 页')).toBeVisible()
  await expect(page.getByText('本页 1 款')).toBeVisible()
})

function catalogPage(page: number, entry: ReturnType<typeof game>, taxonomyTranslated: boolean) {
  return {
    ready: true,
    sourceCount: 2,
    total: 2,
    page,
    size: 20,
    totalPages: 2,
    sort: 'rank',
    type: 'all',
    sourceDate: '2026-08-13',
    taxonomyTranslated,
    games: [entry],
  }
}

function game(bggId: number, name: string) {
  return {
    bggId,
    name,
    originalName: name,
    nameLocalized: false,
    publicationYear: 2024,
    overallRank: bggId,
    hotRank: null,
    geekRating: 7.2,
    averageRating: 7.6,
    usersRated: 1200,
    expansion: false,
    types: ['strategy'],
    detailsAvailable: true,
    thumbnailUrl: '',
    minPlayers: 2,
    maxPlayers: 4,
    playingTimeMinutes: 45,
    averageWeight: 2.1,
    categories: ['策略'],
    mechanics: ['轮抽'],
    bggUrl: `https://boardgamegeek.com/boardgame/${bggId}`,
  }
}
