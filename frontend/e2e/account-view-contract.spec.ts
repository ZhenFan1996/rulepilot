import { expect, test } from '@playwright/test'

const coverPixel = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64')

async function mockCoverImages(page: import('@playwright/test').Page) {
  await page.route('**/api/v1/bgg/catalog/covers/*/image', route => route.fulfill({
    contentType: 'image/png',
    body: coverPixel,
  }))
}

test('renders an authenticated account from the backend usage record contract', async ({ page }) => {
  await mockCoverImages(page)
  const pageErrors: string[] = []
  page.on('pageerror', error => pageErrors.push(error.message))

  await page.route('**/api/auth/session', route => route.fulfill({
    json: { username: 'alice', roles: ['USER'] },
  }))
  await page.route('**/api/v1/teaching-plans', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/model-configuration', route => route.fulfill({
    json: {
      providers: [],
      assignments: { recommendation: 'fake', teaching: 'fake', visual: 'fake', answer: 'fake', critic: 'fake' },
      revision: 1,
      volatileSecrets: false,
      managedStartupAccess: false,
    },
  }))
  await page.route('**/api/v1/model-configuration/usage', route => route.fulfill({
    json: {
      username: 'alice',
      platformAccessEnabled: true,
      monthlyTokenLimit: 100_000,
      platformTokensCharged: 1_000,
      platformTokensReserved: 250,
      personalTokensUsed: 0,
      periodStart: '2026-08-01',
      revision: 1,
    },
  }))
  await page.route('**/api/v1/account/board-game-grid', route => route.fulfill({ json: [] }))

  await page.goto('/account')

  await expect(page.getByRole('heading', { level: 1, name: 'alice' })).toBeVisible()
  await expect(page.getByText('98,750', { exact: true })).toBeVisible()
  await expect(page.getByText('正在读取我的空间…')).toHaveCount(0)
  expect(pageErrors).toEqual([])
})

test('renders the signed-in identity grid while secondary account summaries are still pending', async ({ page }) => {
  await mockCoverImages(page)
  let releaseSecondary!: () => void
  const secondaryGate = new Promise<void>(resolve => { releaseSecondary = resolve })
  await page.route('**/api/auth/session', route => route.fulfill({ json: { username: 'alice', roles: ['USER'] } }))
  await page.route('**/api/v1/account/board-game-grid', route => route.fulfill({
    json: [{ slot: 'FAVORITE_GAME', bggId: 342942, gameName: 'Ark Nova', chineseName: '方舟动物园', thumbnailUrl: '', imageUrl: '' }],
  }))
  await page.route('**/api/v1/bgg/catalog/covers?*', async route => {
    await route.fulfill({ json: [{ bggId: 342942, thumbnailUrl: 'https://images.example/ark-nova.jpg', imageUrl: '' }] })
  })
  await page.route('**/api/v1/teaching-plans', async route => {
    await secondaryGate
    await route.fulfill({ json: [] })
  })
  await page.route('**/api/v1/model-configuration', async route => {
    await secondaryGate
    await route.fulfill({ json: { providers: [], assignments: { recommendation: 'fake', teaching: 'fake', visual: 'fake', answer: 'fake', critic: 'fake' } } })
  })
  await page.route('**/api/v1/model-configuration/usage', async route => {
    await secondaryGate
    await route.fulfill({ json: { platformAccessEnabled: true, monthlyTokenLimit: 100_000, platformTokensCharged: 0, platformTokensReserved: 0, personalTokensUsed: 0 } })
  })

  await page.goto('/account')

  await expect(page.getByRole('heading', { level: 1, name: 'alice' })).toBeVisible()
  await expect(page.getByRole('button', { name: /最爱的桌游：方舟动物园/ })).toBeVisible()
  await expect(page.getByRole('img', { name: '方舟动物园' })).toHaveAttribute('src', '/api/v1/bgg/catalog/covers/342942/image')
  await expect(page.getByText('正在读取我的空间…')).toHaveCount(0)
  releaseSecondary()
})

test('uses a high-resolution responsive grid and finds a game from a partial Chinese title', async ({ page }) => {
  await mockCoverImages(page)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.route('**/api/auth/session', route => route.fulfill({ json: { username: 'alice', roles: ['USER'] } }))
  await page.route('**/api/v1/teaching-plans', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/model-configuration', route => route.fulfill({
    json: { providers: [], assignments: { recommendation: 'fake', teaching: 'fake', visual: 'fake', answer: 'fake', critic: 'fake' } },
  }))
  await page.route('**/api/v1/model-configuration/usage', route => route.fulfill({
    json: { platformAccessEnabled: true, monthlyTokenLimit: 100_000, platformTokensCharged: 0, platformTokensReserved: 0, personalTokensUsed: 0 },
  }))
  await page.route('**/api/v1/account/board-game-grid', route => route.fulfill({
    json: [{ slot: 'FAVORITE_GAME', bggId: 13, gameName: 'Catan', chineseName: '卡坦岛', thumbnailUrl: 'https://images.example/catan-thumb.jpg', imageUrl: 'https://images.example/catan-full.jpg' }],
  }))
  let searchedPath = ''
  await page.route('**/api/v1/account/board-game-grid/search?*', route => {
    searchedPath = new URL(route.request().url()).pathname + new URL(route.request().url()).search
    return route.fulfill({ json: [{ bggId: 174430, name: 'Gloomhaven', chineseName: '幽港迷城', publicationYear: 2017, thumbnailUrl: 'https://images.example/gloomhaven-thumb.jpg', imageUrl: 'https://images.example/gloomhaven-full.jpg' }] })
  })

  await page.goto('/account')

  const filledCover = page.getByRole('img', { name: '卡坦岛' })
  await expect(filledCover).toHaveAttribute('src', '/api/v1/bgg/catalog/covers/13/image')
  const gridCards = page.locator('button[aria-label*="："]')
  await expect(gridCards).toHaveCount(9)
  const first = await gridCards.nth(0).boundingBox()
  const fourth = await gridCards.nth(3).boundingBox()
  expect(first).not.toBeNull()
  expect(fourth).not.toBeNull()
  expect(Math.abs(first!.width / first!.height - 4 / 3)).toBeLessThan(0.06)
  expect(Math.abs(first!.x - fourth!.x)).toBeLessThan(2)

  await gridCards.nth(1).click()
  await page.getByRole('searchbox', { name: '搜索桌游' }).fill('幽港')
  await expect(page.getByRole('button', { name: /幽港迷城/ })).toBeVisible()
  expect(searchedPath).toContain('q=%E5%B9%BD%E6%B8%AF')
  await expect(page.getByRole('img', { name: '幽港迷城' })).toHaveAttribute('src', '/api/v1/bgg/catalog/covers/174430/image')
})
