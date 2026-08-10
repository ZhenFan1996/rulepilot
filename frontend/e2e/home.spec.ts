import { expect, test, type Page } from '@playwright/test'

const homeGames = Array.from({ length: 10 }, (_, index) => ({
  rank: index + 1,
  bggId: 9000 + index,
  name: index === 0 ? '展翅翱翔' : `桌游 ${index + 1}`,
  originalName: index === 0 ? 'Wingspan' : `Game ${index + 1}`,
  nameLocalized: index === 0,
  publicationYear: 2015 + index,
  thumbnailUrl: '/rulepilot-icon.svg',
  bggUrl: `https://boardgamegeek.com/boardgame/${9000 + index}`,
}))

async function mockHomeGames(page: Page) {
  await page.route('**/api/v1/bgg/recommendations?*', route => route.fulfill({ json: homeGames }))
  await page.route('**/api/auth/session', route => route.fulfill({ status: 401, json: {} }))
}

test('uses the full illustrated-hero rhythm while BGG hot and random games lead discovery', async ({ page }, testInfo) => {
  await mockHomeGames(page)
  await page.goto('/')

  await expect(page).toHaveTitle(/RulePilot/)
  await expect(page.getByRole('heading', { level: 1 })).toContainText('规则书递过来')
  await expect(page.locator('a[href="/teach"]:visible').first()).toBeVisible()
  await expect(page.locator('a[href="/discover"]:visible').first()).toBeVisible()
  await expect(page.locator('img[src="/illustrations/home-screenprint-friends.webp"]')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'BGG 热门桌游' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '随机抽三盒' })).toBeVisible()
  await expect(page.locator('.home-game-grid > li')).toHaveCount(4)
  await expect(page.locator('.home-random__games > li')).toHaveCount(3)
  expect(new Set(await page.locator('.home-random__games a').evaluateAll(links => links.map(link => link.getAttribute('href')))).size).toBe(3)
  await expect(page.locator('img[alt="Powered by BoardGameGeek"]')).toHaveCount(1)

  const heroLayout = await page.locator('.home-intro__art').evaluate((element) => {
    const intro = element.closest('.home-intro')!
    const introBox = intro.getBoundingClientRect()
    const artBox = element.getBoundingClientRect()
    return {
      float: getComputedStyle(element).float,
      widthRatio: artBox.width / introBox.width,
      heightRatio: artBox.height / introBox.height,
    }
  })
  expect(heroLayout.float).toBe('none')
  expect(heroLayout.widthRatio).toBeGreaterThan(0.44)
  expect(heroLayout.widthRatio).toBeLessThan(0.54)
  expect(heroLayout.heightRatio).toBeGreaterThan(0.98)
  await page.screenshot({ path: testInfo.outputPath('home-desktop.png'), fullPage: true })
})

test('keeps hot games, random picks, and the primary journey usable on a mobile viewport', async ({ page }) => {
  await mockHomeGames(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')

  await expect(page.locator('a[href="/teach"]:visible').first()).toBeVisible()
  await expect(page.locator('img[src="/illustrations/home-screenprint-friends.webp"]')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'BGG 热门桌游' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '随机抽三盒' })).toBeVisible()
  expect(new Set(await page.locator('.home-random__games a').evaluateAll(links => links.map(link => link.getAttribute('href')))).size).toBe(3)
  await expect(page.getByRole('navigation', { name: '主要导航' })).toBeVisible()

  const stackedArt = await page.locator('.home-intro__art').evaluate((element) => {
    const style = getComputedStyle(element)
    const intro = element.closest('.home-intro')!
    const introBox = intro.getBoundingClientRect()
    const artBox = element.getBoundingClientRect()
    const actionsBox = intro.querySelector('.home-intro__actions')!.getBoundingClientRect()
    return {
      float: style.float,
      shapeOutside: style.shapeOutside,
      widthRatio: artBox.width / introBox.width,
      height: artBox.height,
      followsActions: artBox.top >= actionsBox.bottom,
      sideInset: Math.min(artBox.left - introBox.left, introBox.right - artBox.right),
      bottomInset: introBox.bottom - artBox.bottom,
      borderRadius: Number.parseFloat(style.borderTopLeftRadius),
      borderLeftWidth: Number.parseFloat(style.borderLeftWidth),
      borderBottomWidth: Number.parseFloat(style.borderBottomWidth),
    }
  })
  expect(stackedArt.float).toBe('none')
  expect(stackedArt.shapeOutside).toBe('none')
  expect(stackedArt.widthRatio).toBeGreaterThan(0.84)
  expect(stackedArt.widthRatio).toBeLessThan(0.94)
  expect(stackedArt.height).toBeGreaterThanOrEqual(224)
  expect(stackedArt.followsActions).toBe(true)
  expect(stackedArt.sideInset).toBeGreaterThanOrEqual(16)
  expect(stackedArt.bottomInset).toBeGreaterThanOrEqual(16)
  expect(stackedArt.borderRadius).toBeGreaterThanOrEqual(16)
  expect(stackedArt.borderLeftWidth).toBeGreaterThan(0)
  expect(stackedArt.borderBottomWidth).toBeGreaterThan(0)

  const hasHorizontalOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
  )
  expect(hasHorizontalOverflow).toBe(false)
  await page.screenshot({ path: test.info().outputPath('home-mobile.png'), fullPage: true })
})

test('loads the rulebook workflow through its lazy route boundary', async ({ page }) => {
  await page.goto('/')

  const teachLink = page.locator('a[href="/teach"]:visible').first()
  await expect(teachLink).toBeVisible()
  await teachLink.click()

  await expect(page).toHaveURL('/teach')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(/\S+/)
})
