import { expect, test } from '@playwright/test'

test('presents a direct rulebook journey without a product roadmap', async ({ page }) => {
  await page.goto('/')

  await expect(page).toHaveTitle(/RulePilot/)
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(/\S+/)
  await expect(page.locator('a[href="/teach"]:visible').first()).toBeVisible()
  await expect(page.getByText('FROM RULEBOOK', { exact: false })).toHaveCount(0)
})

test('keeps the primary journey usable on a mobile viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')

  await expect(page.locator('a[href="/teach"]:visible').first()).toBeVisible()
  await expect(page.getByRole('navigation', { name: '主要导航' })).toBeVisible()

  const hasHorizontalOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
  )
  expect(hasHorizontalOverflow).toBe(false)
})
