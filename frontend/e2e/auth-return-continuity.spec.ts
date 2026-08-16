import { expect, test, type Page } from '@playwright/test'

test('returns a newly registered player to the exact page without leaving auth forms in browser history', async ({ page }) => {
  await mockAuthenticationJourney(page)
  await page.goto('/catalog?view=ready#collection')

  const loginLink = page.locator('a[href^="/login"]:visible').first()
  await expect(loginLink).toBeVisible()
  await loginLink.click()
  await expect.poll(() => new URL(page.url()).searchParams.get('redirect'))
    .toBe('/catalog?view=ready#collection')
  await expect(page.getByTestId('auth-return-context')).toContainText('回到刚才的页面')

  await page.getByRole('link', { name: '创建账号' }).click()
  await expect(page).toHaveURL(/\/register/)
  await expect.poll(() => new URL(page.url()).searchParams.get('redirect'))
    .toBe('/catalog?view=ready#collection')
  await expect(page.getByTestId('auth-return-context')).toContainText('创建并登录后')

  await page.getByLabel('用户名').fill('new-player')
  await page.locator('input[name="password"]').fill('test-password')
  await page.locator('input[name="confirmation"]').fill('test-password')
  await page.getByRole('button', { name: '创建并登录' }).click()

  await expect(page).toHaveURL('/catalog?view=ready#collection')
  await expect(page.getByRole('heading', { level: 1, name: '今晚想开哪一局？' })).toBeVisible()
  await page.goBack()
  await expect(page).toHaveURL('/catalog?view=ready#collection')
  await expect(page).not.toHaveURL(/\/(?:login|register)(?:\?|$)/)
})

test('keeps a retryable registration on the same retained destination', async ({ page }) => {
  await mockAuthenticationJourney(page, { registrationStatus: 409 })
  await page.goto('/register?redirect=/lessons?filter=pending')

  await page.getByLabel('用户名').fill('already-used')
  await page.locator('input[name="password"]').fill('retry-password')
  await page.locator('input[name="confirmation"]').fill('retry-password')
  await page.getByRole('button', { name: '创建并登录' }).click()

  await expect(page.getByRole('alert')).toContainText('这个用户名已经有人使用')
  await expect(page).toHaveURL('/register?redirect=/lessons?filter=pending')
  await expect(page.getByLabel('用户名')).toHaveValue('already-used')
  await expect(page.locator('input[name="password"]')).toHaveValue('retry-password')
  const signInInstead = page.getByRole('link', { name: '直接登录' })
  await expect(signInInstead).toHaveAttribute('href', '/login?redirect=/lessons?filter=pending')
})

test('falls back to the root journey after login when a local return route has retired', async ({ page }) => {
  await mockAuthenticationJourney(page)
  await page.goto('/login?redirect=/retired-player-route?view=ready')

  await expect(page.getByTestId('auth-return-context')).toHaveCount(0)
  await page.getByLabel('用户名').fill('returning-player')
  await page.locator('input[name="password"]').fill('test-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()

  await expect(page).toHaveURL('/')
  await expect(page.getByRole('heading', { level: 1 })).toContainText('规则书递过来')
  await expect(page).not.toHaveURL('/account')
})

async function mockAuthenticationJourney(page: Page, options: { registrationStatus?: number } = {}) {
  let signedIn = false
  await page.route('**/api/auth/session', route => route.fulfill(signedIn
    ? { json: { username: 'new-player', roles: ['USER'] } }
    : { status: 401, json: {} }))
  await page.route('**/api/auth/csrf', route => route.fulfill({
    json: { headerName: 'X-CSRF-TOKEN', token: 'test-token' },
  }))
  await page.route('**/api/auth/register', route => route.fulfill({
    status: options.registrationStatus ?? 201,
    json: {},
  }))
  await page.route('**/api/auth/login', (route) => {
    signedIn = true
    return route.fulfill({ status: 204 })
  })
  await page.route('**/api/v1/bgg/recommendations?*', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/**', route => route.fulfill({ json: [] }))
}
