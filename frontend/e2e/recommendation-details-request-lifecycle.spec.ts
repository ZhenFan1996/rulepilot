import { expect, test } from '@playwright/test'

const recommendationGame = {
  bggId: 42,
  name: '候选游戏',
  originalName: 'Candidate Game',
  nameLocalized: true,
  publicationYear: 2024,
  overallRank: 42,
  geekRating: 7.4,
  averageRating: 7.8,
  usersRated: 1200,
  thumbnailUrl: '/rulepilot-icon.svg',
  minPlayers: 2,
  maxPlayers: 4,
  playingTimeMinutes: 45,
  averageWeight: 2.1,
  categories: ['策略'],
  mechanics: ['轮抽'],
  bggUrl: 'https://boardgamegeek.com/boardgame/42',
}

test('cancels a delayed recommendation-detail request when the modal closes', async ({ page }) => {
  let releaseDetails!: () => void
  const detailsGate = new Promise<void>(resolve => { releaseDetails = resolve })
  let detailRequestFailed = false
  let detailHandlerSettled = false
  let localizationRequests = 0
  page.on('requestfailed', request => {
    if (request.url().includes('/api/v1/bgg/games/42') && request.url().includes('translate=false')) {
      detailRequestFailed = true
    }
  })

  await page.route('**/api/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/auth/session') {
      return route.fulfill({ json: { username: 'player', roles: ['USER'] } })
    }
    if (url.pathname === '/api/auth/csrf') return route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'csrf' } })
    if (url.pathname === '/api/v1/bgg/recommendation-agent/stream') {
      const payload = {
        outcome: 'recommendations',
        mode: 'model_assisted',
        assistantMessage: '这里有一款候选。',
        profile: { players: null, maxMinutes: null, maxWeight: null, type: 'all', interaction: 'any' },
        clarification: null,
        sourceCount: 100,
        candidatesEvaluated: 1,
        games: [{ game: recommendationGame, matches: ['支持 2–4 人'], tradeoffs: [] }],
      }
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: `event: result\ndata: ${JSON.stringify(payload)}\n\n`,
      })
    }
    if (url.pathname === '/api/v1/bgg/games/42' && url.searchParams.get('translate') === 'false') {
      await detailsGate
      await route.fulfill({ json: { ...recommendationGame, description: 'Delayed details.', imageUrl: '' } }).catch(() => undefined)
      detailHandlerSettled = true
      return
    }
    if (url.pathname === '/api/v1/bgg/games/42' && url.searchParams.get('translate') === 'true') {
      localizationRequests += 1
      return route.fulfill({ json: recommendationGame })
    }
    if (url.pathname === '/api/v1/assistant-runs/active') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents/official-imports') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents/upload-teaching-handoffs') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents') return route.fulfill({ json: [] })
    return route.fulfill({ status: 404 })
  })

  await page.goto('/discover')
  await page.getByLabel('聊聊你想玩的游戏').fill('给我一个候选')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.getByRole('button', { name: '查看完整资料：候选游戏' })).toBeVisible()
  await page.getByRole('button', { name: '查看完整资料：候选游戏' }).click()

  const dialog = page.getByRole('dialog', { name: '桌游详细资料' })
  await expect(dialog.getByText('正在读取详细资料…')).toBeVisible()
  await dialog.getByRole('button', { name: '关闭桌游资料' }).click()
  await expect(dialog).toHaveCount(0)
  await expect.poll(() => detailRequestFailed).toBe(true)

  releaseDetails()
  await expect.poll(() => detailHandlerSettled).toBe(true)
  expect(localizationRequests).toBe(0)
})

test('keeps source details actionable while localization is pending or unavailable and cancels it on close', async ({ page }) => {
  let releaseUnavailableLocalization!: () => void
  const unavailableLocalizationGate = new Promise<void>(resolve => { releaseUnavailableLocalization = resolve })
  let releaseCancelledLocalization!: () => void
  const cancelledLocalizationGate = new Promise<void>(resolve => { releaseCancelledLocalization = resolve })
  let sourceRequests = 0
  let localizationRequests = 0
  let localizationHandlersSettled = 0
  let localizationRequestFailed = false
  page.on('requestfailed', request => {
    if (request.url().includes('/api/v1/bgg/games/42') && request.url().includes('translate=true')) {
      localizationRequestFailed = true
    }
  })

  await page.route('**/api/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/auth/session') {
      return route.fulfill({ json: { username: 'player', roles: ['USER'] } })
    }
    if (url.pathname === '/api/auth/csrf') return route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'csrf' } })
    if (url.pathname === '/api/v1/bgg/recommendation-agent/stream') {
      const payload = {
        outcome: 'recommendations',
        mode: 'model_assisted',
        assistantMessage: '这里有一款候选。',
        profile: { players: null, maxMinutes: null, maxWeight: null, type: 'all', interaction: 'any' },
        clarification: null,
        sourceCount: 100,
        candidatesEvaluated: 1,
        games: [{ game: recommendationGame, matches: ['支持 2–4 人'], tradeoffs: [] }],
      }
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: `event: result\ndata: ${JSON.stringify(payload)}\n\n`,
      })
    }
    if (url.pathname === '/api/v1/bgg/games/42' && url.searchParams.get('translate') === 'false') {
      sourceRequests += 1
      return route.fulfill({
        json: {
          ...recommendationGame,
          description: 'Source details stay visible.',
          imageUrl: '',
          officialNameLocalized: false,
          descriptionTranslated: false,
          categoriesTranslated: false,
          mechanicsTranslated: false,
          designers: ['Source Designer'],
          publishers: [],
          families: [],
          editionImages: [],
        },
      })
    }
    if (url.pathname === '/api/v1/bgg/games/42' && url.searchParams.get('translate') === 'true') {
      localizationRequests += 1
      const gate = localizationRequests === 1 ? unavailableLocalizationGate : cancelledLocalizationGate
      await gate
      await route.fulfill({ status: 503 }).catch(() => undefined)
      localizationHandlersSettled += 1
      return
    }
    if (url.pathname === '/api/v1/assistant-runs/active') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents/official-imports') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents/upload-teaching-handoffs') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents') return route.fulfill({ json: [] })
    return route.fulfill({ status: 404 })
  })

  try {
    await page.goto('/discover')
    await page.getByLabel('聊聊你想玩的游戏').fill('给我一个候选')
    await page.getByRole('button', { name: '发送', exact: true }).click()
    const openDetails = page.getByRole('button', { name: '查看完整资料：候选游戏' })
    await expect(openDetails).toBeVisible()
    await openDetails.click()

    let dialog = page.getByRole('dialog', { name: '桌游详细资料' })
    await expect(dialog.getByText('Source details stay visible.')).toBeVisible()
    await expect(dialog.getByText('原文已显示，中文资料正在补齐…')).toBeVisible()
    await expect(dialog.getByRole('button', { name: '选这款，继续找规则书' })).toBeEnabled()
    expect(sourceRequests).toBe(1)
    await expect.poll(() => localizationRequests).toBe(1)

    releaseUnavailableLocalization()
    await expect.poll(() => localizationHandlersSettled).toBe(1)
    await expect(dialog.getByText('原文已显示，中文资料正在补齐…')).toHaveCount(0)
    await expect(dialog.getByText('Source details stay visible.')).toBeVisible()
    await expect(dialog.getByRole('button', { name: '选这款，继续找规则书' })).toBeEnabled()

    await dialog.getByRole('button', { name: '关闭桌游资料' }).click()
    await expect(dialog).toHaveCount(0)
    await openDetails.click()
    dialog = page.getByRole('dialog', { name: '桌游详细资料' })
    await expect(dialog.getByText('Source details stay visible.')).toBeVisible()
    await expect.poll(() => localizationRequests).toBe(2)
    expect(sourceRequests).toBe(2)

    await dialog.getByRole('button', { name: '关闭桌游资料' }).click()
    await expect(dialog).toHaveCount(0)
    await expect.poll(() => localizationRequestFailed).toBe(true)

    releaseCancelledLocalization()
    await expect.poll(() => localizationHandlersSettled).toBe(2)
  } finally {
    releaseUnavailableLocalization()
    releaseCancelledLocalization()
  }
})
