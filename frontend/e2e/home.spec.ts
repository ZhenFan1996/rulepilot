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

test('keeps rulebook and game discovery actions reachable alongside BGG games', async ({ page }, testInfo) => {
  await mockHomeGames(page)
  await page.goto('/')

  await expect(page).toHaveURL('/')
  await expect(page).toHaveTitle(/RulePilot/)
  await expect(page.getByRole('heading', { level: 1 })).toContainText('今天玩什么')
  await expect(page.getByRole('link', { name: '登录', exact: true })).toHaveCount(1)
  await expect(page.locator('a[href="/teach"]:visible').first()).toBeVisible()
  await expect(page.locator('a[href="/discover"]:visible').first()).toBeVisible()
  await expect(page.getByRole('heading', { name: 'BGG 热门桌游' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '再看看这几款' })).toBeVisible()
  await expect(page.locator('.home-game-grid a[href="/discover/9000"]')).toBeVisible()
  await expect(page.getByRole('link', { name: '家庭游戏', exact: true })).toHaveAttribute('href', '/discover/catalog?type=family')
  await expect(page.locator('.home-random__games > li')).toHaveCount(3)
  const randomLinks = page.locator('.home-random__games a')
  const initialRandomHrefs = await randomLinks.evaluateAll(links => links.map(link => link.getAttribute('href')))
  expect(new Set(initialRandomHrefs).size).toBe(3)
  await page.getByRole('button', { name: '再换三盒' }).click()
  await expect.poll(() => randomLinks.evaluateAll(links => links.map(link => link.getAttribute('href'))))
    .not.toEqual(initialRandomHrefs)
  await expect(page.locator('img[alt="Powered by BoardGameGeek"]')).toHaveCount(1)

  await page.screenshot({ path: testInfo.outputPath('home-desktop.png'), fullPage: true })
})

test('keeps hot games, random picks, and the primary journey usable on a mobile viewport', async ({ page }) => {
  await mockHomeGames(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')

  await expect(page).toHaveURL('/')
  await expect(page.getByRole('link', { name: '登录', exact: true })).toHaveCount(1)
  await expect(page.locator('a[href="/teach"]:visible').first()).toBeVisible()
  await expect(page.getByRole('heading', { name: 'BGG 热门桌游' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '再看看这几款' })).toBeVisible()
  expect(new Set(await page.locator('.home-random__games a').evaluateAll(links => links.map(link => link.getAttribute('href')))).size).toBe(3)
  await expect(page.getByRole('navigation', { name: '主要导航' })).toBeVisible()

  const primaryAction = page.locator('.home-primary-action')
  await expect(primaryAction).toBeInViewport()
  await expect(page.locator('.home-intro__actions a[href="/discover"]')).toBeInViewport()

  await page.getByRole('button', { name: 'EN', exact: true }).click()
  await expect(page.getByRole('heading', { level: 1 })).toContainText('What are we playing')
  const englishActionFit = await page.locator('.home-intro__actions').evaluate((element) => {
    const actions = [...element.querySelectorAll('a')].map((action) => action.getBoundingClientRect())
    const mobileNavigation = document.querySelector('.mobile-navigation')!.getBoundingClientRect()
    return {
      actionHeights: actions.map(action => action.height),
      lastActionBottom: actions.at(-1)!.bottom,
      safeNavigationTop: Math.min(mobileNavigation.top, window.innerHeight - 80),
      hasHorizontalOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
    }
  })
  expect(englishActionFit.actionHeights.every(height => height >= 44)).toBe(true)
  expect(englishActionFit.lastActionBottom).toBeLessThanOrEqual(englishActionFit.safeNavigationTop - 8)
  expect(englishActionFit.hasHorizontalOverflow).toBe(false)
  await page.screenshot({ path: test.info().outputPath('home-mobile.png'), fullPage: true })
})

test('loads the rulebook workflow through its lazy route boundary', async ({ page }) => {
  await page.route('**/api/auth/session', route => route.fulfill({ status: 401, json: {} }))
  await page.route('**/api/v1/**', route => route.fulfill({ status: 503, json: {} }))
  await page.goto('/')

  const teachLink = page.locator('a[href="/teach"]:visible').first()
  await expect(teachLink).toBeVisible()
  await teachLink.click()

  await expect(page).toHaveURL('/teach')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(/\S+/)
})

test('gives a signed-out direct guides visit one return-aware recovery action', async ({ page }, testInfo) => {
  await page.route('**/api/auth/session', route => route.fulfill({ status: 401, json: {} }))
  await page.route('**/api/v1/**', route => route.fulfill({ status: 401, json: {} }))
  await page.goto('/lessons?filter=pending')

  await expect(page).toHaveURL('/lessons?filter=pending')
  const gate = page.getByTestId('signed-out-guides-gate')
  await expect(gate.getByRole('heading', { name: '登录后查看你的讲解' })).toBeVisible()
  await expect(page.locator('a[href^="/login"]:visible')).toHaveCount(1)
  await expect(gate.getByRole('link', { name: '登录后继续' }))
    .toHaveAttribute('href', '/login?redirect=/lessons?filter=pending')
  await expect(page.locator('main a[href="/teach"]')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '重新加载' })).toHaveCount(0)
  await expect(page.getByText('当前页面已保留')).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('signed-out-guides.png'), fullPage: true })
})

test('keeps the signed-in root journey stable across a history round trip', async ({ page }) => {
  await page.route('**/api/auth/session', route => route.fulfill({ json: { username: 'player', roles: ['USER'] } }))
  await page.route('**/api/v1/**', route => route.fulfill({ status: 503, json: {} }))
  await page.route('**/api/v1/bgg/recommendations?*', route => route.fulfill({ json: homeGames }))
  await page.goto('/')

  await expect(page).toHaveURL('/')
  await expect(page.locator('a[aria-label="player"]:visible')).toBeVisible()
  await expect(page.getByRole('link', { name: '登录', exact: true })).toHaveCount(0)

  const discover = page.locator('a[href="/discover"]:visible').first()
  await discover.focus()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL('/discover')
  await page.goBack()

  await expect(page).toHaveURL('/')
  await expect(page.getByRole('heading', { name: 'BGG 热门桌游' })).toBeVisible()
  await expect(page.getByRole('heading', { level: 1 })).toContainText('今天玩什么')
})
