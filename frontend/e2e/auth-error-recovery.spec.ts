import { expect, test, type Page } from '@playwright/test'

test('associates invalid credentials with both fields and recovers without losing the destination', async ({ page }) => {
  let loginAttempts = 0
  let signedIn = false
  await mockAuthDependencies(page, () => signedIn)
  await page.route('**/api/auth/login', (route) => {
    loginAttempts += 1
    if (loginAttempts === 1) return route.fulfill({ status: 401 })
    signedIn = true
    return route.fulfill({ status: 204 })
  })
  await page.goto('/login?redirect=/catalog?view=ready%23collection')

  const username = page.locator('input[name="username"]')
  const password = page.locator('input[name="password"]')
  await username.fill('player')
  await password.fill('wrong-password')
  await page.getByRole('button', { name: '登录' }).click()

  await expect(page.getByRole('alert')).toContainText('用户名或密码不正确')
  await expect(username).toBeFocused()
  await expect(username).toHaveAttribute('aria-invalid', 'true')
  await expect(password).toHaveAttribute('aria-invalid', 'true')
  await expect(username).toHaveAttribute('aria-describedby', 'auth-login-error')

  await password.fill('correct-password')
  await expect(page.getByRole('alert')).toHaveCount(0)
  await page.getByRole('button', { name: '登录' }).click()

  await expect(page).toHaveURL('/catalog?view=ready#collection')
  expect(loginAttempts).toBe(2)
})

test('retries only sign-in after registration already created the account', async ({ page }) => {
  let registrationAttempts = 0
  let loginAttempts = 0
  let signedIn = false
  await mockAuthDependencies(page, () => signedIn)
  await page.route('**/api/auth/register', (route) => {
    registrationAttempts += 1
    return route.fulfill({ status: 201, json: {} })
  })
  await page.route('**/api/auth/login', (route) => {
    loginAttempts += 1
    if (loginAttempts === 1) return route.fulfill({ status: 503 })
    signedIn = true
    return route.fulfill({ status: 204 })
  })
  await page.goto('/register?redirect=/lessons?filter=pending')

  await page.locator('input[name="username"]').fill('new-player')
  await page.locator('input[name="email"]').fill('new-player@example.test')
  await page.locator('input[name="password"]').fill('test-password')
  await page.locator('input[name="confirmation"]').fill('test-password')
  await page.getByRole('button', { name: '创建并登录' }).click()

  const alert = page.getByRole('alert')
  await expect(alert).toContainText('账号已经创建')
  await expect(alert).toContainText('不会再次创建账号')
  await expect(alert).toBeFocused()
  await expect(page.locator('input[name="username"]')).toBeDisabled()
  await expect(page.locator('input[name="password"]')).toBeDisabled()
  await expect(page.getByRole('button', { name: '重试登录' })).toBeVisible()
  expect(registrationAttempts).toBe(1)
  expect(loginAttempts).toBe(1)

  await page.getByRole('button', { name: '重试登录' }).click()

  await expect(page).toHaveURL('/lessons?filter=pending')
  await expect(page).not.toHaveURL(/\/(?:login|register)(?:\?|$)/)
  expect(registrationAttempts).toBe(1)
  expect(loginAttempts).toBe(2)
})

async function mockAuthDependencies(page: Page, signedIn: () => boolean) {
  await page.route('**/api/auth/session', route => route.fulfill(signedIn()
    ? { json: { username: 'player', roles: ['USER'] } }
    : { status: 401, json: {} }))
  await page.route('**/api/auth/csrf', route => route.fulfill({
    json: { headerName: 'X-CSRF-TOKEN', token: 'test-token' },
  }))
  await page.route('**/api/v1/bgg/recommendations?*', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/**', route => route.fulfill({ json: [] }))
}
