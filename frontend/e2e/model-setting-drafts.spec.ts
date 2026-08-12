import { expect, test, type Page } from '@playwright/test'

const snapshot = {
  providers: [
    { id: 'gemini', configured: true, baseUrl: '', model: 'gemini-2.5-flash', apiKeyConfigured: true, visionCapable: true },
    { id: 'openai', configured: true, baseUrl: 'https://api.openai.com', model: 'gpt-5-mini', apiKeyConfigured: true, visionCapable: true },
  ],
  assignments: { recommendation: 'openai', teaching: 'openai', visual: 'gemini', answer: 'gemini', critic: 'gemini' },
  revision: 1,
  volatileSecrets: true,
  managedStartupAccess: false,
}

test.beforeEach(async ({ page }) => {
  await mockShell(page)
  await page.route('**/api/v1/model-configuration', route => route.fulfill({ json: snapshot }))
})

test('retains secret drafts across providers and requires explicit discard before route navigation', async ({ page }) => {
  await page.goto('/settings/models')
  const geminiKey = page.getByLabel('API Key')
  await geminiKey.fill('gemini-memory-only-secret')
  await expect(page.getByTestId('model-settings-unsaved')).toContainText('Gemini 连接')

  await page.getByRole('tab', { name: /OpenAI/ }).click()
  await page.getByLabel('API Key').fill('openai-memory-only-secret')
  await page.getByRole('tab', { name: /Gemini/ }).click()
  await expect(geminiKey).toHaveValue('gemini-memory-only-secret')
  await expect(page.getByText('未保存', { exact: true })).toHaveCount(3)
  const browserStorage = await page.evaluate(() => JSON.stringify({
    local: { ...localStorage },
    session: { ...sessionStorage },
  }))
  expect(browserStorage).not.toContain('memory-only-secret')

  const gamesLink = page.getByRole('link', { name: '我的游戏', exact: true }).first()
  await gamesLink.focus()
  await gamesLink.click()
  const dialog = page.getByRole('alertdialog', { name: '放弃未保存的设置并离开？' })
  await expect(dialog).toContainText('Gemini 连接、OpenAI 连接仍未保存')
  await expect(dialog.getByRole('button', { name: '继续编辑' })).toBeFocused()
  await dialog.getByRole('button', { name: '继续编辑' }).click()
  await expect(page).toHaveURL(/\/settings\/models$/)
  await expect(gamesLink).toBeFocused()
  await expect(geminiKey).toHaveValue('gemini-memory-only-secret')

  await gamesLink.click()
  await page.getByRole('alertdialog').getByRole('button', { name: '放弃更改并离开' }).click()
  await expect(page).toHaveURL(/\/catalog$/)
  await expect(page.getByRole('heading', { name: '今晚想开哪一局？' })).toBeVisible()
})

test('blocks navigation during a save and continues automatically only after success', async ({ page }) => {
  let releaseSave!: () => void
  const saveGate = new Promise<void>(resolve => { releaseSave = resolve })
  await page.route('**/api/v1/model-configuration/providers/gemini', async (route) => {
    await saveGate
    await route.fulfill({ json: { ...snapshot, revision: 2 } })
  })

  await page.goto('/settings/models')
  await page.getByLabel('API Key').fill('pending-save-secret')
  const saveRequest = page.waitForRequest(request => request.url().endsWith('/providers/gemini') && request.method() === 'PUT')
  await page.getByRole('button', { name: '保存连接' }).click()
  await saveRequest
  const gamesLink = page.getByRole('link', { name: '我的游戏', exact: true }).first()
  await gamesLink.focus()
  await gamesLink.evaluate(link => (link as HTMLElement).click())

  const dialog = page.getByRole('alertdialog', { name: '正在完成保存' })
  await expect(dialog).toContainText('保存请求完成前暂时不能离开')
  await expect(dialog.getByRole('button', { name: '继续编辑' })).toBeDisabled()
  await expect(dialog.getByRole('button', { name: '正在保存…' })).toBeDisabled()
  await expect(page).toHaveURL(/\/settings\/models$/)

  releaseSave()
  await expect(dialog).toHaveCount(0)
  await expect(page).toHaveURL(/\/catalog$/)
})

test('returns to editing when an in-flight save fails instead of trapping navigation', async ({ page }) => {
  let releaseSave!: () => void
  const saveGate = new Promise<void>(resolve => { releaseSave = resolve })
  await page.route('**/api/v1/model-configuration/providers/gemini', async (route) => {
    await saveGate
    await route.fulfill({ status: 503, json: { detail: '连接暂时保存失败。' } })
  })

  await page.goto('/settings/models')
  await page.getByLabel('API Key').fill('failed-save-secret')
  const saveRequest = page.waitForRequest(request => request.url().endsWith('/providers/gemini') && request.method() === 'PUT')
  await page.getByRole('button', { name: '保存连接' }).click()
  await saveRequest
  const gamesLink = page.getByRole('link', { name: '我的游戏', exact: true }).first()
  await gamesLink.focus()
  await gamesLink.evaluate(link => (link as HTMLElement).click())
  await expect(page.getByRole('alertdialog', { name: '正在完成保存' })).toBeVisible()

  releaseSave()
  await expect(page.getByRole('alertdialog')).toHaveCount(0)
  await expect(page).toHaveURL(/\/settings\/models$/)
  await expect(page.getByRole('alert')).toContainText('连接暂时保存失败')
  await expect(page.getByLabel('API Key')).toHaveValue('failed-save-secret')
  await expect(gamesLink).toBeFocused()
})

async function mockShell(page: Page) {
  await page.route('**/api/auth/session', route => route.fulfill({ json: { username: 'player', roles: ['USER'] } }))
  await page.route('**/api/auth/csrf', route => route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'csrf' } }))
  await page.route('**/api/v1/games', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/bgg/recommendations?*', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/assistant-runs/active?*', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/documents/official-imports', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/documents/upload-teaching-handoffs', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/documents', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/teaching-plans', route => route.fulfill({ json: [] }))
}
