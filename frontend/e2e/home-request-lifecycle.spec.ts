import { expect, test } from '@playwright/test'

const chineseGames = games('中文热门')
const englishGames = games('English Trending')

test('keeps Home discovery on the current locale without duplicating the shell session read', async ({ page }) => {
  let releaseEnglish!: () => void
  const englishGate = new Promise<void>(resolve => { releaseEnglish = resolve })
  let releaseSession!: () => void
  const sessionGate = new Promise<void>(resolve => { releaseSession = resolve })
  let sessionRequests = 0
  let chineseRequests = 0
  let englishRequestFailed = false
  let englishHandlerSettled = false

  page.on('requestfailed', request => {
    if (request.url().includes('/api/v1/bgg/recommendations') && request.url().includes('locale=en')) {
      englishRequestFailed = true
    }
  })

  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/auth/session') {
      sessionRequests += 1
      await sessionGate
      return route.fulfill({ json: { username: 'player', roles: ['USER'] } })
    }
    if (url.pathname === '/api/v1/bgg/recommendations') {
      if (url.searchParams.get('locale') === 'en') {
        await englishGate
        await route.fulfill({ json: englishGames }).catch(() => undefined)
        englishHandlerSettled = true
        return
      }
      chineseRequests += 1
      return route.fulfill({ json: chineseGames })
    }
    if (url.pathname === '/api/v1/assistant-runs/active') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents/official-imports') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents/upload-teaching-handoffs') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents') return route.fulfill({ json: [] })
    return route.fulfill({ status: 404 })
  })

  await page.goto('/')
  await expect(page.locator('.home-game-card__title').first()).toHaveText('中文热门 1')
  expect(sessionRequests).toBe(1)

  await page.getByRole('button', { name: 'EN', exact: true }).click()
  await expect(page.getByText('Checking what players have been looking at lately…')).toBeVisible()
  await expect(page.locator('.home-game-card__title')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'Three from the shelf' })).toHaveCount(0)

  await page.getByRole('button', { name: '中文', exact: true }).click()
  await expect(page.locator('.home-game-card__title').first()).toHaveText('中文热门 1')
  await expect.poll(() => englishRequestFailed).toBe(true)
  expect(chineseRequests).toBe(2)
  expect(sessionRequests).toBe(1)

  releaseEnglish()
  await expect.poll(() => englishHandlerSettled).toBe(true)
  await expect(page.locator('.home-game-card__title').first()).toHaveText('中文热门 1')
  await expect(page.getByText('English Trending 1', { exact: true })).toHaveCount(0)

  releaseSession()
  await expect(page.getByText('player，新游戏带来了吗？', { exact: true })).toBeVisible()
  expect(sessionRequests).toBe(1)
})

function games(prefix: string) {
  return Array.from({ length: 10 }, (_, index) => ({
    rank: index + 1,
    bggId: 7000 + index,
    name: `${prefix} ${index + 1}`,
    originalName: `${prefix} ${index + 1}`,
    nameLocalized: false,
    publicationYear: 2020 + index,
    thumbnailUrl: '/rulepilot-icon.svg',
    bggUrl: `https://boardgamegeek.com/boardgame/${7000 + index}`,
  }))
}
