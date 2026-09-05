import { expect, test, type Page } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await mockRulebookReader(page)
})

test('contains keyboard focus, closes with Escape, and restores the rulebook action', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/rulebooks/version-1')
  await expect(page.getByRole('heading', { name: '测试规则书' })).toBeVisible()

  const opener = page.getByRole('button', { name: '基于这本规则书答疑' }).first()
  await opener.focus()
  await opener.click()
  const dialog = page.getByRole('dialog', { name: '基于这本规则书答疑' })
  const close = dialog.getByRole('button', { name: '关闭答疑' })

  await expect(dialog).toBeVisible()
  await expect(close).toBeFocused()
  await expect(page.locator('html')).toHaveClass(/modal-scroll-locked/)
  expect(await page.evaluate(() => ({ root: document.documentElement.style.overflow, body: document.body.style.overflow })))
    .toEqual({ root: 'hidden', body: 'hidden' })

  await close.press('Shift+Tab')
  const last = dialog.locator('button:not([disabled]), textarea:not([disabled]), input:not([disabled]), a[href]').last()
  await expect(last).toBeFocused()
  await last.press('Tab')
  await expect(close).toBeFocused()

  await page.keyboard.press('Escape')
  await expect(dialog).toHaveCount(0)
  await expect(opener).toBeFocused()
  await expect(page.locator('html')).not.toHaveClass(/modal-scroll-locked/)
  expect(await page.evaluate(() => ({ root: document.documentElement.style.overflow, body: document.body.style.overflow })))
    .toEqual({ root: '', body: '' })
})

test('keeps the rulebook usable alongside questions and restores focus after card recognition', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/rulebooks/version-1')
  const opener = page.getByRole('button', { name: '基于这本规则书答疑' }).first()
  await opener.click()
  const answerDialog = page.getByRole('complementary', { name: '基于这本规则书答疑' })
  await page.locator('button[data-page-number="2"]').click({ timeout: 1500 })
  await expect(page.getByTestId('rulebook-page-image')).toHaveAttribute('data-page-number', '2')
  const cardOpener = answerDialog.getByRole('button', { name: '拍照识别卡牌文字' })
  await cardOpener.click()

  const cardDialog = page.getByRole('dialog', { name: '拍照识别卡牌文字' })
  await expect(cardDialog).toBeVisible()
  await expect(cardDialog.getByRole('button', { name: '关闭卡牌识别' })).toBeFocused()

  await page.keyboard.press('Escape')
  await expect(cardDialog).toHaveCount(0)
  await expect(answerDialog).toBeVisible()
  await expect(cardOpener).toBeFocused()
  await expect(page.locator('html')).not.toHaveClass(/modal-scroll-locked/)

  await answerDialog.getByRole('button', { name: '关闭答疑' }).click()
  await expect(answerDialog).toHaveCount(0)
  await expect(opener).toBeFocused()
  await expect(page.locator('html')).not.toHaveClass(/modal-scroll-locked/)
})

async function mockRulebookReader(page: Page) {
  await page.route('**/api/auth/session', route => route.fulfill({ json: { username: 'player', roles: ['USER'] } }))
  await page.route('**/api/v1/bgg/recommendations?*', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/assistant-runs/active?*', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/documents/official-imports', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/documents/upload-teaching-handoffs', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/documents', route => route.fulfill({ json: [{
    document: { id: 'document-1', title: '测试规则书' },
    latestVersion: { id: 'version-1', status: 'READY', originalFilename: 'rules.pdf' },
  }] }))
  await page.route('**/api/v1/document-versions/version-1/pages', route => route.fulfill({ json: [
    { pageNumber: 1, text: '设置游戏', characterCount: 4 },
    { pageNumber: 2, text: '玩家先放置牌。', characterCount: 8 },
  ] }))
  await page.route('**/api/v1/document-versions/version-1/pages/*/image', route => route.fulfill({
    status: 200,
    contentType: 'image/svg+xml',
    body: '<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"/>',
  }))
}
