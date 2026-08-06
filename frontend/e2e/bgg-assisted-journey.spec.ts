import { expect, test, type Page } from '@playwright/test'

const readyDocument = {
  document: { id: 'document-1', gameEditionId: null, title: 'Example Rulebook' },
  latestVersion: {
    id: 'version-1', originalFilename: 'example-rules.pdf', size: 4096, status: 'READY',
  },
}

const hotGame = {
  rank: 1, bggId: 42, name: 'Catalog Game', publicationYear: 2024,
  thumbnailUrl: 'https://example.test/catalog-cover.jpg',
  bggUrl: 'https://boardgamegeek.com/boardgame/42', minPlayers: 1, maxPlayers: 5,
  playingTimeMinutes: 60, averageRating: 7.8, averageWeight: 2.4,
  categories: ['Strategy'], mechanics: ['Drafting'],
}

const candidate = {
  bggId: 42, name: 'Catalog Game', publicationYear: 2024,
  coverUrl: 'https://example.test/catalog-cover.jpg', minPlayers: 1, maxPlayers: 5,
  playingTimeMinutes: 60, minimumAge: 10, normalizedTitleMatch: true,
  bggUrl: 'https://boardgamegeek.com/boardgame/42',
}

test('covers attributed discovery, official PDF intake, and explicit metadata confirmation on desktop', async ({ page }) => {
  let officialImport: Record<string, unknown> | null = null
  let bggLink: Record<string, unknown> | null = null
  await mockOnboardingApis(page, {
    recommendations: [hotGame],
    suggestions: [candidate],
    onOfficialImport: body => { officialImport = body },
    onBggLink: body => { bggLink = body },
  })
  await page.setViewportSize({ width: 1440, height: 900 })

  await page.goto('/')
  await expect(page.getByRole('heading', { name: '看看热门桌游' })).toBeVisible()
  await expect(page.getByText('Catalog Game', { exact: true })).toBeVisible()
  await expect(page.getByText('1–5 人 · 约 60 分钟')).toBeVisible()
  await expect(page.getByRole('link', { name: /Powered by BGG/ })).toHaveAttribute(
    'href', 'https://boardgamegeek.com/hotness',
  )

  await page.locator('a[href="/teach"]:visible').first().click()
  await expect(page).toHaveURL('/teach')
  await expect(page.getByText('已有 PDF', { exact: true })).toBeVisible()

  await page.getByText('可选：关联游戏、官方链接和讲解偏好').click()
  const officialButton = page.getByRole('button', { name: '下载并生成讲解' })
  await expect(officialButton).toBeDisabled()
  await page.getByRole('textbox', { name: /官方原文链接/ }).fill('https://publisher.example/rules.pdf')
  await page.getByRole('checkbox', { name: /我确认这是官方来源/ }).check()
  await expect(officialButton).toBeEnabled()
  await officialButton.click()
  await expect.poll(() => officialImport).toEqual({
    editionId: null,
    title: 'rules',
    sourceType: 'BASE_RULEBOOK',
    officialSourceUrl: 'https://publisher.example/rules.pdf',
    rightsConfirmed: true,
  })
  await expect(page.getByText(/上传完成，正在读取页面和图片/)).toBeVisible()
  await expect(page.getByText('已有 PDF', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: '补全桌游资料' }).click()
  await expect(page.getByText('BGG 资料只用于封面和目录展示，不会作为规则问答证据。')).toBeVisible()
  await page.getByRole('button', { name: '选择此项' }).click()
  await page.getByRole('button', { name: '确认关联这款桌游' }).click()
  await expect.poll(() => bggLink).toEqual({ bggId: 42 })
  await expect(page.getByText('已关联桌游资料，并保留原规则书作为唯一规则证据。')).toBeVisible()
  await expect(page.getByRole('button', { name: '开始讲解' })).toBeVisible()
  expect(await hasHorizontalOverflow(page)).toBe(false)
})

test('keeps manual onboarding and the ready guide usable when BGG fails on mobile', async ({ page }) => {
  await mockOnboardingApis(page, { recommendations: null, suggestions: null })
  await page.setViewportSize({ width: 390, height: 844 })

  await page.goto('/')
  await expect(page.getByText('暂时没有热门桌游资料。你仍然可以直接上传规则书，或从 BGG 搜索游戏。')).toBeVisible()
  await expect(page.locator('a[href="/teach"]:visible').first()).toBeVisible()
  expect(await hasHorizontalOverflow(page)).toBe(false)

  await page.goto('/teach')
  await page.getByRole('button', { name: '补全桌游资料' }).click()
  await expect(page.getByText('暂时无法连接 BGG。规则书和讲解不受影响，你可以稍后重试。')).toBeVisible()
  await expect(page.getByText('已有 PDF', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '开始讲解' })).toBeVisible()
  expect(await hasHorizontalOverflow(page)).toBe(false)
})

async function mockOnboardingApis(page: Page, options: {
  recommendations: Array<typeof hotGame> | null
  suggestions: Array<typeof candidate> | null
  onOfficialImport?: (body: Record<string, unknown>) => void
  onBggLink?: (body: Record<string, unknown>) => void
}) {
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (path === '/api/auth/session') return route.fulfill({ json: { username: 'player', roles: ['USER'] } })
    if (path === '/api/auth/csrf') return route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'csrf' } })
    if (path === '/api/v1/assistant-runs/active') return route.fulfill({ json: [] })
    if (path === '/api/v1/teaching-plans') return route.fulfill({ json: [] })
    if (path === '/api/public/lessons') return route.fulfill({ json: [] })
    if (path === '/api/v1/bgg/recommendations') {
      return options.recommendations === null
        ? route.fulfill({ status: 503 })
        : route.fulfill({ json: options.recommendations })
    }
    if (path === '/api/v1/games') return route.fulfill({ json: [] })
    if (path === '/api/v1/model-configuration') {
      return route.fulfill({ json: {
        providers: [{ id: 'qwen', configured: true, visionCapable: true }],
        assignments: { teaching: 'qwen', visual: 'qwen' },
      } })
    }
    if (path === '/api/v1/documents' && request.method() === 'GET') {
      return route.fulfill({ json: [readyDocument] })
    }
    if (path === '/api/v1/documents/official-imports' && request.method() === 'POST') {
      options.onOfficialImport?.(request.postDataJSON() as Record<string, unknown>)
      return route.fulfill({ status: 201, json: {
        duplicate: false, version: { id: 'imported-version', status: 'EXTRACTING' },
      } })
    }
    if (path === '/api/v1/documents/document-1/bgg-suggestions') {
      return options.suggestions === null
        ? route.fulfill({ status: 503 })
        : route.fulfill({ json: options.suggestions })
    }
    if (path === '/api/v1/documents/document-1/bgg-link' && request.method() === 'POST') {
      options.onBggLink?.(request.postDataJSON() as Record<string, unknown>)
      return route.fulfill({ json: { alreadyImported: false } })
    }
    return route.fulfill({ status: 404 })
  })
}

async function hasHorizontalOverflow(page: Page) {
  return page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)
}
