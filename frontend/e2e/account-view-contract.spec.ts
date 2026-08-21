import { expect, test } from '@playwright/test'

test('renders an authenticated account from the backend usage record contract', async ({ page }) => {
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
