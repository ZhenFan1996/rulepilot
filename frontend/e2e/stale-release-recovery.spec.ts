import { expect, test } from '@playwright/test'

test.use({ serviceWorkers: 'block' })

test('reloads the current release when an old tab requests a removed account chunk', async ({ page }) => {
  let accountChunkRequests = 0
  await page.route(/\/assets\/AccountView-.*\.js$/, async (route) => {
    accountChunkRequests += 1
    if (accountChunkRequests === 1) {
      await route.abort('failed')
      return
    }
    await route.continue()
  })
  await page.route('**/api/**', route => route.fulfill({ status: 401, json: {} }))

  await page.goto('/')
  await page.getByRole('link', { name: '我的', exact: true }).click()

  await expect(page).toHaveURL('/account')
  await expect(page.getByText('我的账户', { exact: true })).toBeVisible()
  await expect(page.getByText('请登录后查看账户信息。')).toBeVisible()
  expect(accountChunkRequests).toBe(2)
})
