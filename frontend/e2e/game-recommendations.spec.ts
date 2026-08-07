import { expect, test } from '@playwright/test'

const discovery = {
  sourceCount: 12,
  sort: 'hot',
  categoriesTranslated: true,
  categories: [
    { value: 'Family', label: '家庭' },
    { value: 'Strategy', label: '策略' },
  ],
  games: [{
    rank: 2,
    bggId: 266192,
    name: '展翅翱翔',
    originalName: 'Wingspan',
    nameLocalized: true,
    publicationYear: 2019,
    thumbnailUrl: 'https://example.test/wingspan.jpg',
    minPlayers: 1,
    maxPlayers: 5,
    playingTimeMinutes: 70,
    averageRating: 8.1,
    averageWeight: 2.5,
    categories: ['Family', 'Strategy'],
    mechanics: ['Card Drafting'],
    bggUrl: 'https://boardgamegeek.com/boardgame/266192',
  }],
}

async function mockPublicDiscovery(page: import('@playwright/test').Page) {
  await page.route('**/api/auth/session', route => route.fulfill({ status: 401 }))
  await page.route('**/api/v1/bgg/discovery?*', route => route.fulfill({ json: discovery }))
  await page.route('**/api/v1/bgg/search?*', route => route.fulfill({ json: [
    { bggId: 266192, name: 'Wingspan', publicationYear: 2019, bggUrl: 'https://boardgamegeek.com/boardgame/266192' },
  ] }))
}

test('sorts and filters the bounded hot list before direct title search', async ({ page }) => {
  await mockPublicDiscovery(page)
  await page.goto('/discover')

  await expect(page.getByRole('heading', { level: 1 })).toContainText('适合上桌')
  await expect(page.getByText('展翅翱翔')).toBeVisible()
  await expect(page.locator('li', { hasText: '策略' })).toBeVisible()

  await page.getByRole('combobox', { name: '排序' }).selectOption('rating')
  await page.getByRole('combobox', { name: /游戏类型/ }).selectOption('Strategy')
  const filteredRequest = page.waitForRequest(request => request.url().includes('/api/v1/bgg/discovery?')
    && request.url().includes('sort=rating') && request.url().includes('category=Strategy'))
  await page.getByRole('button', { name: '应用' }).click()
  await filteredRequest

  await page.getByLabel('直接搜索桌游').fill('Wingspan')
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expect(page.getByRole('heading', { name: /Wingspan.*BGG 搜索结果/ })).toBeVisible()
  await expect(page.getByRole('link', { name: '查看详情' })).toHaveAttribute('href', '/discover/266192')
})

test('keeps discovery usable without horizontal overflow at 390 px', async ({ page }) => {
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
