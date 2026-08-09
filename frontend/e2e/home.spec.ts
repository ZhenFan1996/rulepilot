import { expect, test } from '@playwright/test'

test('presents discovery, BGG attribution, and a continuous product guide', async ({ page }, testInfo) => {
  await page.goto('/')

  await expect(page).toHaveTitle(/RulePilot/)
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(/\S+/)
  await expect(page.locator('a[href="/discover"]:visible').first()).toBeVisible()
  await expect(page.locator('a[href="/discover/catalog"]:visible').first()).toBeVisible()
  await expect(page.locator('img[src="/illustrations/tabletop-gathering-v2.webp"]')).toBeVisible()
  await expect(page.locator('img[alt="Powered by BoardGameGeek"]')).toHaveCount(2)
  await expect(page.getByText('继续这一局', { exact: false })).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('home-desktop.png'), fullPage: true })
})

test('keeps the primary journey usable on a mobile viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')

  await expect(page.locator('a[href="/discover"]:visible').first()).toBeVisible()
  await expect(page.getByRole('navigation', { name: '主要导航' })).toBeVisible()

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
