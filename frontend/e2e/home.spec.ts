import { expect, test } from '@playwright/test'

test('presents rulebook teaching before follow-up questions', async ({ page }) => {
  await page.goto('/')

  await expect(page).toHaveTitle(/RulePilot/)
  await expect(page.getByRole('heading', { level: 1 })).toContainText('读完规则书')
  await expect(page.getByRole('link', { name: '导入规则书' }).last()).toBeVisible()
  await expect(page.getByText('结束条件与计分', { exact: true })).toBeVisible()

  const teachingPosition = await page.getByText('组织完整讲解', { exact: true }).evaluate((element) => element.getBoundingClientRect().top)
  const questionsPosition = await page.getByRole('link', { name: /^讲解后继续答疑/ }).evaluate((element) => element.getBoundingClientRect().top)

  expect(teachingPosition).toBeLessThan(questionsPosition)
})

test('keeps the primary journey usable on a mobile viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')

  await expect(page.getByRole('link', { name: '导入规则书' })).toBeVisible()
  await expect(page.getByRole('navigation', { name: '主要导航' })).toBeVisible()

  const hasHorizontalOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
  )
  expect(hasHorizontalOverflow).toBe(false)
})
