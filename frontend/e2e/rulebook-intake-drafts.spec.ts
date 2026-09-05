import { expect, test, type Page } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await mockRulebookIntake(page)
})

test('keeps a selected rulebook draft through cancelled navigation and discards it explicitly', async ({ page }) => {
  await page.goto('/teach')
  await page.locator('#rulebook-file').setInputFiles({
    name: 'table-night-rules.pdf',
    mimeType: 'application/pdf',
    buffer: Buffer.from('%PDF-1.7 test rulebook'),
  })
  await page.getByPlaceholder('例如：翼展规则书').fill('桌游夜规则书')
  await page.getByText('可选：关联游戏、规则书来源和讲解偏好', { exact: true }).click()
  await page.getByPlaceholder(/先让我能带大家开局/).fill('先让我能带大家开局')

  const draft = page.getByTestId('rulebook-intake-unsaved')
  await expect(draft).toContainText('PDF“table-night-rules.pdf”')
  await expect(draft).toContainText('标题与资料类型')
  await expect(draft).toContainText('讲解目标')
  const browserStorage = await page.evaluate(() => JSON.stringify({
    local: { ...localStorage },
    session: { ...sessionStorage },
  }))
  expect(browserStorage).not.toContain('table-night-rules')
  expect(browserStorage).not.toContain('先让我能带大家开局')

  const gamesLink = page.getByRole('link', { name: '我的游戏', exact: true }).first()
  await gamesLink.focus()
  await gamesLink.click()
  const dialog = page.getByRole('alertdialog', { name: '放弃这次规则书草稿并离开？' })
  await expect(dialog.getByRole('button', { name: '继续准备' })).toBeFocused()
  await page.keyboard.press('Escape')

  await expect(dialog).toHaveCount(0)
  await expect(page).toHaveURL(/\/teach$/)
  await expect(gamesLink).toBeFocused()
  await expect(page.locator('#rulebook-file')).toHaveJSProperty('files.length', 1)
  await expect(page.getByPlaceholder('例如：翼展规则书')).toHaveValue('桌游夜规则书')
  await expect(page.getByPlaceholder(/先让我能带大家开局/)).toHaveValue('先让我能带大家开局')

  await gamesLink.click()
  await page.getByRole('alertdialog').getByRole('button', { name: '放弃草稿并离开' }).click()
  await expect(page).toHaveURL(/\/catalog$/)
  await expect(page.getByRole('heading', { name: '我的游戏' })).toBeVisible()
})

async function mockRulebookIntake(page: Page) {
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/auth/session') return route.fulfill({ json: { username: 'player', roles: ['USER'] } })
    if (path === '/api/auth/csrf') return route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'csrf' } })
    if (path === '/api/v1/games') return route.fulfill({ json: [] })
    if (path === '/api/v1/model-configuration') return route.fulfill({ json: {
      providers: [], assignments: { teaching: 'fake', visual: 'fake' },
    } })
    if (path === '/api/v1/documents' && request.method() === 'GET') return route.fulfill({ json: [] })
    if (path === '/api/v1/documents/official-imports') return route.fulfill({ json: [] })
    if (path === '/api/v1/documents/upload-teaching-handoffs') return route.fulfill({ json: [] })
    if (path === '/api/v1/assistant-runs/active') return route.fulfill({ json: [] })
    if (path === '/api/v1/teaching-plans') return route.fulfill({ json: [] })
    if (path === '/api/v1/bgg/recommendations') return route.fulfill({ json: [] })
    if (path === '/api/v1/bgg/catalog') return route.fulfill({ json: {
      ready: true, sourceCount: 0, total: 0, page: 0, size: 20, totalPages: 0,
      sort: 'rank', type: 'all', sourceDate: null, taxonomyTranslated: false, games: [],
    } })
    return route.fulfill({ status: 404 })
  })
}
