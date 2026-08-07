import { expect, test } from '@playwright/test'

const catalog = {
  ready: true,
  sourceCount: 162686,
  total: 7543,
  page: 0,
  size: 20,
  totalPages: 378,
  sort: 'hot',
  type: 'all',
  importedAt: '2026-08-07T08:00:00Z',
  sourceDate: '2026-08-07',
  taxonomyTranslated: true,
  games: [{
    bggId: 266192,
    name: '展翅翱翔',
    originalName: 'Wingspan',
    nameLocalized: true,
    publicationYear: 2019,
    overallRank: 34,
    hotRank: 2,
    geekRating: 7.79,
    averageRating: 8.09,
    usersRated: 102030,
    expansion: false,
    types: ['family', 'strategy'],
    detailsAvailable: true,
    thumbnailUrl: 'https://example.test/wingspan.jpg',
    minPlayers: 1,
    maxPlayers: 5,
    playingTimeMinutes: 70,
    averageWeight: 2.5,
    categories: ['策略'],
    mechanics: ['卡牌轮抽'],
    bggUrl: 'https://boardgamegeek.com/boardgame/266192',
  }],
}

async function mockPublicDiscovery(page: import('@playwright/test').Page) {
  await page.route('**/api/auth/session', route => route.fulfill({ status: 401 }))
  await page.route('**/api/v1/bgg/catalog?*', async route => {
    if (route.request().url().includes('enrich=true')) {
      await new Promise(resolve => setTimeout(resolve, 1_500))
      await route.fulfill({ json: catalog })
      return
    }
    await route.fulfill({
      json: {
        ...catalog,
        taxonomyTranslated: false,
        games: [{
          ...catalog.games[0],
          name: 'Wingspan',
          originalName: 'Wingspan',
          nameLocalized: false,
          detailsAvailable: false,
          thumbnailUrl: '',
          minPlayers: null,
          maxPlayers: null,
          playingTimeMinutes: null,
          averageWeight: null,
          categories: [],
          mechanics: [],
        }],
      },
    })
  })
}

test('sorts, filters, and searches the full server-side BGG snapshot', async ({ page }) => {
  await mockPublicDiscovery(page)
  await page.goto('/discover')

  await expect(page.getByRole('heading', { level: 1 })).toContainText('整个 BGG 目录')
  await expect(page.getByText('BGG 快照共 162,686 条记录')).toBeVisible()
  await expect(page.getByRole('heading', { level: 3, name: 'Wingspan' })).toBeVisible()
  await expect(page.getByText('正在后台补齐封面、人数和中文资料…')).toBeVisible()
  await expect(page.getByText('展翅翱翔')).toBeVisible()
  await expect(page.locator('li', { hasText: '卡牌轮抽' })).toBeVisible()
  await expect(page.getByRole('link', { name: '数据由 BoardGameGeek 提供' }).locator('img')).toHaveAttribute('src', '/powered-by-bgg-rgb.svg')

  await page.getByRole('combobox', { name: '排序' }).selectOption('rating')
  await page.getByRole('combobox', { name: /BGG 类型榜/ }).selectOption('strategy')
  const filteredRequest = page.waitForRequest(request => request.url().includes('/api/v1/bgg/catalog?')
    && request.url().includes('sort=rating') && request.url().includes('type=strategy'))
  await page.getByRole('button', { name: '应用' }).click()
  await filteredRequest

  await page.getByLabel('搜索整个目录').fill('Wingspan')
  const searchRequest = page.waitForRequest(request => request.url().includes('/api/v1/bgg/catalog?')
    && request.url().includes('q=Wingspan'))
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await searchRequest
  await expect(page.getByRole('link', { name: /展翅翱翔/ })).toHaveAttribute('href', '/discover/266192')
  await expect(page.getByText('第 1 / 378 页')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '再看一批' })).toBeVisible()
})

test('keeps full-catalog discovery usable without horizontal overflow at 390 px', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockPublicDiscovery(page)
  await page.goto('/discover')

  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expect(page.getByRole('navigation', { name: '主要导航' })).toBeVisible()
  await expect(page.getByRole('link', { name: /展翅翱翔/ })).toBeVisible()
  const hasHorizontalOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
  )
  expect(hasHorizontalOverflow).toBe(false)
})
