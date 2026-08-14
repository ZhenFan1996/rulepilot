import { expect, test, type Page } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await mockShell(page)
})

test('keeps rulebook deletion safe by default and retryable after a server failure', async ({ page }) => {
  let deleteAttempts = 0
  await page.route('**/api/v1/games', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/model-configuration', route => route.fulfill({ json: {
    providers: [], assignments: { teaching: 'fake', visual: 'fake' },
  } }))
  await page.route('**/api/v1/documents/document-1', async (route) => {
    if (route.request().method() !== 'DELETE') return route.fallback()
    deleteAttempts += 1
    await route.fulfill({ status: deleteAttempts === 1 ? 503 : 204 })
  })
  await page.route('**/api/v1/documents', route => route.fulfill({ json: [{
    document: { id: 'document-1', gameEditionId: null, title: '桌面上的测试规则书' },
    latestVersion: { id: 'version-1', originalFilename: 'rules.pdf', size: 2048, status: 'READY' },
  }] }))

  await page.goto('/teach')
  const opener = page.getByRole('button', { name: '删除', exact: true })
  await opener.click()

  const dialog = page.getByRole('alertdialog', { name: '删除这本规则书？' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('button', { name: '保留规则书' })).toBeFocused()
  expect(deleteAttempts).toBe(0)

  await dialog.getByRole('button', { name: '删除规则书' }).click()
  await expect(dialog.getByRole('alert')).toContainText('暂时无法处理规则书')
  await expect(page.getByText('桌面上的测试规则书', { exact: true })).toBeVisible()
  expect(deleteAttempts).toBe(1)

  await dialog.getByRole('button', { name: '重新尝试删除' }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page.getByText('桌面上的测试规则书', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '已上传的规则书' })).toBeFocused()
  expect(deleteAttempts).toBe(2)
})

test('Escape cancels model disablement and restores the exact control without a request', async ({ page }) => {
  let deleteRequests = 0
  const snapshot = {
    providers: [{
      id: 'gemini', configured: true, baseUrl: '', model: 'gemini-2.5-flash',
      apiKeyConfigured: true, visionCapable: true,
    }],
    assignments: { recommendation: 'gemini', teaching: 'gemini', visual: 'gemini', answer: 'gemini', critic: 'gemini' },
    revision: 1, volatileSecrets: true, managedStartupAccess: false,
  }
  await page.route('**/api/v1/model-configuration', route => route.fulfill({ json: snapshot }))
  await page.route('**/api/v1/model-configuration/providers/gemini', async (route) => {
    if (route.request().method() === 'DELETE') deleteRequests += 1
    await route.fulfill({ json: snapshot })
  })

  await page.goto('/settings/models')
  const opener = page.getByRole('button', { name: '停用', exact: true })
  await opener.focus()
  await opener.click()
  const dialog = page.getByRole('alertdialog', { name: '停用 Gemini？' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('button', { name: '保留连接' })).toBeFocused()

  await page.keyboard.press('Escape')
  await expect(dialog).toHaveCount(0)
  await expect(opener).toBeFocused()
  expect(deleteRequests).toBe(0)
})

async function mockShell(page: Page) {
  await page.route('**/api/auth/session', route => route.fulfill({ json: { username: 'player', roles: ['USER'] } }))
  await page.route('**/api/auth/csrf', route => route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'csrf' } }))
  await page.route('**/api/v1/bgg/recommendations?*', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/assistant-runs/active?*', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/documents/official-imports', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/documents/upload-teaching-handoffs', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/documents', route => route.fulfill({ json: [] }))
}
