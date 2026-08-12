import { expect, test } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await page.route('**/api/auth/session', route => route.fulfill({ status: 401, json: {} }))
  await page.route('**/api/auth/csrf', route => route.fulfill({
    json: { headerName: 'X-CSRF-TOKEN', token: 'test-token' },
  }))
  await page.route('**/api/v1/bgg/recommendations?*', route => route.fulfill({ json: [] }))
})

test('recovers from an unknown address and keeps keyboard navigation oriented', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/a-bookmark-that-no-longer-exists')

  await expect(page).toHaveTitle('页面不存在 · RulePilot')
  await expect(page.getByRole('heading', { level: 1, name: '没找到这个页面' })).toBeVisible()
  await expect(page.getByRole('main')).toHaveCount(1)
  await expect(page.getByRole('link', { name: '回到首页' })).toBeVisible()
  await expect(page.getByRole('link', { name: '去找一款桌游' })).toBeVisible()

  await page.keyboard.press('Tab')
  const skipLink = page.getByRole('link', { name: '跳到主要内容' })
  await expect(skipLink).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('main')).toBeFocused()

  await page.getByRole('button', { name: 'EN', exact: true }).click()
  await expect(page).toHaveTitle('Page not found · RulePilot')
  await expect(page.getByRole('heading', { level: 1, name: 'We could not find this page' })).toBeVisible()

  await page.getByRole('link', { name: 'Back to home' }).click()
  await expect(page).toHaveURL('/')
  await expect(page).toHaveTitle('Home · RulePilot')
  await expect(page.getByRole('main')).toBeFocused()
})

test('keeps the recovery actions usable without horizontal overflow on a phone', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/missing-on-mobile')

  const actions = page.locator('.not-found-card a')
  await expect(actions).toHaveCount(2)
  expect(await actions.evaluateAll(links => links.every(link => link.getBoundingClientRect().height >= 44))).toBe(true)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)

  const bottomNavigation = page.getByRole('navigation', { name: '主要导航' })
  await expect(bottomNavigation).toBeVisible()
  const overlap = await page.evaluate(() => {
    const actionsBox = document.querySelector('.not-found-card')!.getBoundingClientRect()
    const navigationBox = document.querySelector('.mobile-navigation')!.getBoundingClientRect()
    return actionsBox.bottom > navigationBox.top && actionsBox.top < navigationBox.bottom
  })
  expect(overlap).toBe(false)
})

test('restores the reading position after leaving a long page and going back', async ({ page }) => {
  const games = Array.from({ length: 12 }, (_, index) => ({
    rank: index + 1,
    bggId: 9000 + index,
    name: `桌游 ${index + 1}`,
    originalName: `Game ${index + 1}`,
    nameLocalized: true,
    publicationYear: 2010 + index,
    thumbnailUrl: '/rulepilot-icon.svg',
    bggUrl: `https://boardgamegeek.com/boardgame/${9000 + index}`,
  }))
  await page.route('**/api/v1/bgg/recommendations?*', route => route.fulfill({ json: games }))
  await page.setViewportSize({ width: 390, height: 600 })
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '随机抽三盒' })).toBeVisible()

  await page.evaluate(() => window.scrollTo(0, 700))
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(600)
  const savedPosition = await page.evaluate(() => window.scrollY)

  await page.locator('.mobile-navigation a[href="/discover"]').click()
  await expect(page).toHaveURL('/discover')
  await expect(page.getByRole('main')).toBeFocused()
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0)

  await page.goBack()
  await expect(page).toHaveURL('/')
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(savedPosition)
})
