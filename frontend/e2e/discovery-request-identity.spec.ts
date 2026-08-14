import { expect, test, type Page } from '@playwright/test'

const oldGame = gameDetails(42, '旧路由游戏', 'Old Route Game')
const currentGame = gameDetails(43, '当前路由游戏', 'Current Route Game')

test('keeps discovery bound to the current route while the previous response is delayed', async ({ page }) => {
  let releaseOldRequest!: () => void
  const oldRequestGate = new Promise<void>(resolve => { releaseOldRequest = resolve })
  let oldRequestFailed = false
  let oldHandlerSettled = false
  page.on('requestfailed', request => {
    if (request.url().includes('/api/v1/bgg/games/42')) oldRequestFailed = true
  })

  await mockShellAndDiscovery(page, oldRequestGate, () => { oldHandlerSettled = true })
  await page.goto('/discover/42')
  await expect(page.getByLabel('正在读取桌游资料')).toBeVisible()

  await page.evaluate(() => {
    window.history.pushState(window.history.state, '', '/discover/43')
    window.dispatchEvent(new PopStateEvent('popstate', { state: window.history.state }))
  })

  await expect(page).toHaveURL('/discover/43')
  await expect(page.getByRole('heading', { name: '当前路由游戏' })).toBeVisible()
  await expect.poll(() => oldRequestFailed).toBe(true)

  releaseOldRequest()
  await expect.poll(() => oldHandlerSettled).toBe(true)
  await expect(page.getByRole('heading', { name: '当前路由游戏' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '旧路由游戏' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: /查看 BGG 原始资料/ })).toHaveAttribute(
    'href',
    'https://boardgamegeek.com/boardgame/43',
  )
})

async function mockShellAndDiscovery(page: Page, oldRequestGate: Promise<void>, markOldHandlerSettled: () => void) {
  await page.route('**/api/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/auth/session') return route.fulfill({ status: 401, json: {} })
    if (url.pathname === '/api/v1/bgg/games/42') {
      await oldRequestGate
      await route.fulfill({ json: oldGame }).catch(() => undefined)
      markOldHandlerSettled()
      return
    }
    if (url.pathname === '/api/v1/bgg/games/43') return route.fulfill({ json: currentGame })
    if (url.pathname === '/api/v1/assistant-runs/active') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents/official-imports') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents/upload-teaching-handoffs') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents') return route.fulfill({ json: [] })
    return route.fulfill({ status: 404 })
  })
}

function gameDetails(bggId: number, name: string, originalName: string) {
  return {
    bggId,
    name,
    originalName,
    officialNameLocalized: true,
    description: `${name}的详情。`,
    descriptionTranslated: true,
    thumbnailUrl: '/rulepilot-icon.svg',
    imageUrl: '/rulepilot-icon.svg',
    publicationYear: 2024,
    minPlayers: 2,
    maxPlayers: 4,
    playingTimeMinutes: 45,
    minimumAge: 10,
    averageRating: 7.5,
    averageWeight: 2.2,
    categories: ['策略'],
    categoriesTranslated: true,
    mechanics: ['轮抽'],
    mechanicsTranslated: true,
    designers: [],
    publishers: [],
    editionImages: [],
    bggUrl: `https://boardgamegeek.com/boardgame/${bggId}`,
  }
}
