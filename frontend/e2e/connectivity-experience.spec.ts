import { expect, test, type Page } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await mockAnonymousShell(page)
})

test('keeps offline and reconnection feedback clear of mobile app content', async ({ context, page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await expect(page.getByTestId('connectivity-status')).toHaveCount(0)

  await context.setOffline(true)
  const status = page.getByTestId('connectivity-status')
  await expect(status).toContainText('当前离线')
  await expect(status).toContainText('已缓存内容可能仍可查看')

  const offlineGeometry = await page.evaluate(() => {
    const statusBox = document.querySelector('[data-testid="connectivity-status"]')!.getBoundingClientRect()
    const headerBox = document.querySelector('.mobile-app-header')!.getBoundingClientRect()
    const mainBox = document.querySelector('#main-content')!.getBoundingClientRect()
    return {
      statusTop: statusBox.top,
      statusBottom: statusBox.bottom,
      headerTop: headerBox.top,
      mainTop: mainBox.top,
      statusPosition: getComputedStyle(document.querySelector('[data-testid="connectivity-status"]')!).position,
      horizontalOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
    }
  })
  expect(offlineGeometry).toMatchObject({ statusTop: 0, statusPosition: 'fixed', horizontalOverflow: false })
  expect(offlineGeometry.headerTop).toBeGreaterThanOrEqual(offlineGeometry.statusBottom - 1)
  expect(offlineGeometry.mainTop).toBeGreaterThanOrEqual(offlineGeometry.statusBottom - 1)

  await context.setOffline(false)
  await expect(status).toContainText('设备已重新联网')
  await expect(status).toContainText('不会自动重试')
  await expect(status).toHaveCount(0, { timeout: 7000 })
  await expect(page.locator('html')).not.toHaveClass(/connectivity-status-visible/)
})

test('covers authentication and desktop navigation without hiding their first actions', async ({ context, page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/login')
  await context.setOffline(true)

  const status = page.getByTestId('connectivity-status')
  await expect(status).toContainText('登录、搜索、上传和生成需联网')
  const authGeometry = await page.evaluate(() => {
    const statusBox = document.querySelector('[data-testid="connectivity-status"]')!.getBoundingClientRect()
    const mainBox = document.querySelector('#main-content')!.getBoundingClientRect()
    const formBox = document.querySelector('form')!.getBoundingClientRect()
    return { statusBottom: statusBox.bottom, mainTop: mainBox.top, formTop: formBox.top }
  })
  expect(authGeometry.mainTop).toBeGreaterThanOrEqual(authGeometry.statusBottom - 1)
  expect(authGeometry.formTop).toBeGreaterThan(authGeometry.statusBottom)

  await context.setOffline(false)
  await page.goto('/')
  await context.setOffline(true)
  const shellGeometry = await page.evaluate(() => {
    const statusBox = document.querySelector('[data-testid="connectivity-status"]')!.getBoundingClientRect()
    const shelfBox = document.querySelector('.drawer-shelf')!.getBoundingClientRect()
    const headingBox = document.querySelector('h1')!.getBoundingClientRect()
    return { statusBottom: statusBox.bottom, shelfTop: shelfBox.top, headingTop: headingBox.top }
  })
  expect(shellGeometry.shelfTop).toBeGreaterThanOrEqual(shellGeometry.statusBottom - 1)
  expect(shellGeometry.headingTop).toBeGreaterThan(shellGeometry.statusBottom)
})

test('fits the English offline explanation at the supported 320px minimum', async ({ context, page }) => {
  await page.setViewportSize({ width: 320, height: 700 })
  await page.goto('/')
  await page.getByRole('button', { name: 'EN', exact: true }).click()
  await context.setOffline(true)

  const status = page.getByTestId('connectivity-status')
  await expect(status).toContainText("You're offline")
  await expect(status).toContainText('Cached content may still be available')
  const geometry = await page.evaluate(() => {
    const element = document.querySelector('[data-testid="connectivity-status"]')!
    const box = element.getBoundingClientRect()
    return {
      scrollHeight: element.scrollHeight,
      clientHeight: element.clientHeight,
      bottom: box.bottom,
      headerTop: document.querySelector('.mobile-app-header')!.getBoundingClientRect().top,
      horizontalOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
    }
  })
  expect(geometry.scrollHeight).toBeLessThanOrEqual(geometry.clientHeight)
  expect(geometry.headerTop).toBeGreaterThanOrEqual(geometry.bottom - 1)
  expect(geometry.horizontalOverflow).toBe(false)
})

async function mockAnonymousShell(page: Page) {
  await page.route('**/api/auth/session', route => route.fulfill({ status: 401, json: {} }))
  await page.route('**/api/auth/csrf', route => route.fulfill({
    json: { headerName: 'X-CSRF-TOKEN', token: 'test-token' },
  }))
  await page.route('**/api/v1/bgg/recommendations?*', route => route.fulfill({ json: [] }))
}
