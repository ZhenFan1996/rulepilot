import { expect, test } from '@playwright/test'

const coverPixel = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  'base64',
)
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

test('lays out twenty cover frames before bytes arrive and starts the eager row without a request waterfall', async ({ page }) => {
  const firstViewGames = Array.from({ length: 20 }, (_, index) => game(index + 1, `首屏桌游 ${index + 1}`))
  let releaseCoverResponses!: () => void
  const coverGate = new Promise<void>(resolve => { releaseCoverResponses = resolve })
  const thumbnailRequests: string[] = []
  let completedThumbnailResponses = 0
  let catalogRequests = 0
  let coverMetadataRequests = 0
  let displayImageRequests = 0

  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/auth/session') return route.fulfill({ status: 401, json: {} })
    if (url.pathname === '/api/v1/bgg/catalog') {
      catalogRequests += 1
      return route.fulfill({
        json: {
          ...catalogPage(0, firstViewGames[0]!, false),
          sourceCount: firstViewGames.length,
          total: firstViewGames.length,
          totalPages: 1,
          games: firstViewGames,
        },
      })
    }
    if (url.pathname === '/api/v1/bgg/catalog/covers') {
      coverMetadataRequests += 1
      return route.fulfill({ json: [] })
    }
    if (/\/api\/v1\/bgg\/catalog\/covers\/\d+\/image$/.test(url.pathname)) {
      displayImageRequests += 1
      return route.fulfill({ contentType: 'image/png', body: coverPixel })
    }
    if (/\/api\/v1\/bgg\/catalog\/covers\/\d+\/thumbnail$/.test(url.pathname)) {
      thumbnailRequests.push(`${url.pathname}${url.search}`)
      await coverGate
      await route.fulfill({ contentType: 'image/png', body: coverPixel }).catch(() => undefined)
      completedThumbnailResponses += 1
      return
    }
    if (url.pathname === '/api/v1/assistant-runs/active') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents/official-imports') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents/upload-teaching-handoffs') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents') return route.fulfill({ json: [] })
    return route.fulfill({ status: 404 })
  })

  try {
    await page.goto('/discover/catalog', { waitUntil: 'domcontentloaded' })
    const thumbnails = page.locator('[data-cover-kind="thumbnail"]')
    await expect(thumbnails).toHaveCount(20)
    await expect(page.getByRole('heading', { name: '首屏桌游 1', exact: true })).toBeVisible()

    const pendingFrames = await thumbnails.evaluateAll(images => images.map(image => {
      const element = image as HTMLImageElement
      const bounds = element.getBoundingClientRect()
      return {
        loading: element.loading,
        fetchPriority: element.fetchPriority,
        naturalWidth: element.naturalWidth,
        width: bounds.width,
        height: bounds.height,
      }
    }))
    expect(pendingFrames).toHaveLength(20)
    expect(pendingFrames.every(frame => frame.naturalWidth === 0)).toBe(true)
    expect(pendingFrames.every(frame => frame.width > 0 && frame.height > 0)).toBe(true)
    expect(pendingFrames.slice(0, 4).map(frame => [frame.loading, frame.fetchPriority]))
      .toEqual(Array.from({ length: 4 }, () => ['eager', 'high']))
    expect(pendingFrames.slice(4).map(frame => [frame.loading, frame.fetchPriority]))
      .toEqual(Array.from({ length: 16 }, () => ['lazy', 'auto']))

    await expect.poll(() => thumbnailRequests.length).toBeGreaterThanOrEqual(4)
    expect(completedThumbnailResponses).toBe(0)
    expect(catalogRequests).toBe(1)
    expect(coverMetadataRequests).toBe(0)
    expect(displayImageRequests).toBe(0)

    releaseCoverResponses()
    await expect.poll(() => completedThumbnailResponses).toBeGreaterThanOrEqual(4)
    await expect.poll(async () => thumbnails.evaluateAll(images => images
      .slice(0, 4)
      .every(image => (image as HTMLImageElement).naturalWidth > 0))).toBe(true)

    expect(thumbnailRequests.length).toBeLessThanOrEqual(20)
    expect(new Set(thumbnailRequests).size).toBe(thumbnailRequests.length)
    expect(coverMetadataRequests).toBe(0)
    expect(displayImageRequests).toBe(0)
  } finally {
    releaseCoverResponses()
  }
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
