import { expect, test, type Page } from '@playwright/test'

test('keeps the selected game visible from durable import through rulebook and guide publication', async ({ page }) => {
  let stage: 'IMPORT' | 'DOCUMENT' | 'GUIDE' = 'IMPORT'
  await mockMyGames(page, () => stage)
  await page.setViewportSize({ width: 390, height: 844 })

  await page.goto('/catalog')
  await expect(page.getByRole('heading', { name: 'Catalog Game', exact: true })).toBeVisible()
  await expect(page.getByText('规则书正在加入')).toBeVisible()
  await expect(page.getByText('正在组织讲解', { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: /查看准备进度/ })).toHaveAttribute('href', '/games/game-1')
  expect(await hasHorizontalOverflow(page)).toBe(false)

  await page.getByRole('link', { name: /查看准备进度/ }).click()
  await expect(page).toHaveURL('/games/game-1')
  await expect(page.getByRole('heading', { level: 1, name: 'Catalog Game' })).toBeVisible()
  await expect(page.getByText('正在下载并绑定这本规则书')).toBeVisible()
  await expect(page.getByText('讲解任务已持久化，正在后台准备')).toBeVisible()
  expect(await hasHorizontalOverflow(page)).toBe(false)

  stage = 'DOCUMENT'
  await page.reload()
  await expect(page.getByText('Official Rules')).toBeVisible()
  await expect(page.getByTestId('player-work-status').filter({ hasText: '规则书可读' })).toBeVisible()
  const preparationStatus = page.getByTestId('player-work-status').filter({ hasText: '正在组织讲解' })
  await expect(preparationStatus).toBeVisible()
  await expect(preparationStatus).toHaveAttribute('data-player-work-readiness', 'usable')
  await expect(page.getByRole('link', { name: '开始讲解' })).toHaveCount(0)

  stage = 'GUIDE'
  await page.reload()
  await expect(page.getByText('Official Rules')).toBeVisible()
  await expect(page.getByRole('link', { name: '打开讲解' })).toHaveAttribute('href', '/lesson/plan-1')
  await expect(page.getByRole('link', { name: '规则答疑' })).toHaveAttribute('href', '/lesson/plan-1/questions')
  await expect(page.locator('text=Official Rules')).toHaveCount(1)
})

test('replaces a slow game route and never publishes its optional metadata or durable bindings', async ({ page }) => {
  let gameReads = 0
  let releaseOldDetails!: () => void
  const oldDetailsGate = new Promise<void>(resolve => { releaseOldDetails = resolve })
  let oldDetailsRequested = false
  const cancelled: string[] = []
  const mutations: string[] = []

  page.on('requestfailed', request => {
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/games' || path === '/api/v1/bgg/games/42') cancelled.push(path)
  })

  await page.route('**/api/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (request.method() !== 'GET') mutations.push(`${request.method()} ${url.pathname}`)
    if (url.pathname === '/api/auth/session') return route.fulfill({ json: { username: 'player', roles: ['USER'] } })
    if (url.pathname === '/api/v1/games') {
      gameReads += 1
      if (gameReads === 1) return route.fulfill({ json: [catalogGame('game-1', 'edition-1', 'Old Game', 42)] })
      return route.fulfill({ json: [catalogGame('game-2', 'edition-2', 'Current Game', 43)] })
    }
    if (url.pathname === '/api/v1/documents') return route.fulfill({ json: gameReads <= 1
      ? [ownedDocument('document-1', 'edition-1', 'version-1', 'Old Rules')]
      : [ownedDocument('document-2', 'edition-2', 'version-2', 'Current Rules')] })
    if (url.pathname === '/api/v1/teaching-plans') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents/official-imports'
      || url.pathname === '/api/v1/documents/upload-teaching-handoffs'
      || url.pathname === '/api/v1/assistant-runs/active') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/bgg/games/42') {
      oldDetailsRequested = true
      await oldDetailsGate
      return route.fulfill({ json: richDetails(42, 'Old delayed description') }).catch(() => undefined)
    }
    if (url.pathname === '/api/v1/bgg/games/43') return route.fulfill({ json: richDetails(43, 'Current description') })
    return route.fulfill({ status: 404 })
  })

  await page.goto('/games/game-1')
  await expect(page.getByRole('heading', { level: 1, name: 'Old Game' })).toBeVisible()
  await expect(page.getByText('Old Rules')).toBeVisible()
  await expect.poll(() => oldDetailsRequested).toBe(true)
  await page.evaluate(() => {
    window.history.pushState(window.history.state, '', '/games/game-2')
    window.dispatchEvent(new PopStateEvent('popstate', { state: window.history.state }))
  })

  await expect(page).toHaveURL('/games/game-2')
  await expect(page.getByRole('heading', { level: 1, name: 'Current Game' })).toBeVisible()
  await expect(page.getByText('Current Rules')).toBeVisible()
  await expect(page.getByText('Current description')).toBeVisible()
  await expect.poll(() => cancelled.includes('/api/v1/bgg/games/42')).toBe(true)

  releaseOldDetails()
  await expect(page.getByRole('heading', { level: 1, name: 'Current Game' })).toBeVisible()
  await expect(page.getByText('Old Game')).toHaveCount(0)
  await expect(page.getByText('Old Rules')).toHaveCount(0)
  await expect(page.getByText('Old delayed description')).toHaveCount(0)
  expect(mutations).toEqual([])
})

test('refreshes only while durable work can advance and upgrades the open shelf without navigation', async ({ page }) => {
  let importReads = 0
  let planReads = 0
  let stage: 'IMPORT' | 'GUIDE' = 'IMPORT'
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/auth/session') return route.fulfill({ json: { username: 'player', roles: ['USER'] } })
    if (url.pathname === '/api/v1/games') return route.fulfill({ json: [catalogGame('game-1', 'edition-1', 'Catalog Game')] })
    if (url.pathname === '/api/v1/documents') return route.fulfill({ json: stage === 'IMPORT'
      ? []
      : [ownedDocument('document-1', 'edition-1', 'version-1', 'Official Rules')] })
    if (url.pathname === '/api/v1/documents/official-imports') {
      importReads += 1
      return route.fulfill({ json: stage === 'IMPORT' ? [pendingImport()] : [] })
    }
    if (url.pathname === '/api/v1/documents/upload-teaching-handoffs') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/teaching-plans') {
      planReads += 1
      return route.fulfill({ json: stage === 'GUIDE' ? [{
        id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Catalog Game', premise: 'Learn.', sections: [],
        createdBy: 'player', createdAt: '2026-08-13T08:01:00Z',
      }] : [] })
    }
    if (url.pathname === '/api/v1/assistant-runs/active') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/assistant-runs/preparation-1') return route.fulfill({ json: { run: preparationRun() } })
    return route.fulfill({ status: 404 })
  })

  await page.clock.install()
  await page.goto('/catalog')
  await expect(page.getByRole('heading', { name: 'Catalog Game', exact: true })).toBeVisible()
  await expect(page.getByText('规则书正在加入')).toBeVisible()
  stage = 'GUIDE'
  await page.clock.fastForward(4_100)
  await expect(page.getByText('1 本规则书', { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: /继续讲解/ })).toHaveAttribute('href', '/lesson/plan-1')

  const settledImportReads = importReads
  const settledPlanReads = planReads
  await page.clock.fastForward(12_000)
  expect(importReads).toBe(settledImportReads)
  expect(planReads).toBe(settledPlanReads)
})

test('refreshes workspace relationships without repeating optional BGG enrichment', async ({ page }) => {
  let stage: 'DOCUMENT' | 'GUIDE' = 'DOCUMENT'
  let bggReads = 0
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/auth/session') return route.fulfill({ json: { username: 'player', roles: ['USER'] } })
    if (url.pathname === '/api/v1/games') return route.fulfill({ json: [catalogGame('game-1', 'edition-1', 'Catalog Game', 42)] })
    if (url.pathname === '/api/v1/documents') return route.fulfill({ json: [ownedDocument('document-1', 'edition-1', 'version-1', 'Official Rules')] })
    if (url.pathname === '/api/v1/documents/official-imports') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents/upload-teaching-handoffs') return route.fulfill({ json: stage === 'DOCUMENT'
      ? [{
          id: 'handoff-1', documentVersionId: 'version-1', editionId: 'edition-1', rulebookTitle: 'Official Rules',
          state: 'LAUNCHED', preparationRunId: 'preparation-1', errorCode: null, updatedAt: '2026-08-13T08:00:00Z',
        }]
      : [] })
    if (url.pathname === '/api/v1/teaching-plans') return route.fulfill({ json: stage === 'GUIDE' ? [{
      id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Catalog Game', premise: 'Learn.', sections: [],
      createdBy: 'player', createdAt: '2026-08-13T08:01:00Z',
    }] : [] })
    if (url.pathname === '/api/v1/bgg/games/42') {
      bggReads += 1
      return route.fulfill({ json: richDetails(42, 'One presentation read') })
    }
    if (url.pathname === '/api/v1/assistant-runs/active') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/assistant-runs/preparation-1') return route.fulfill({ json: { run: preparationRun() } })
    return route.fulfill({ status: 404 })
  })

  await page.clock.install()
  await page.goto('/games/game-1')
  const preparationStatus = page.getByTestId('player-work-status').filter({ hasText: '正在组织讲解' })
  await expect(preparationStatus).toBeVisible()
  await expect(preparationStatus).toHaveAttribute('data-player-work-readiness', 'usable')
  await expect(page.getByText('One presentation read')).toBeVisible()
  expect(bggReads).toBe(1)
  stage = 'GUIDE'
  await page.clock.fastForward(4_100)
  await expect(page.getByRole('link', { name: '打开讲解' })).toHaveAttribute('href', '/lesson/plan-1')
  expect(bggReads).toBe(1)
})

test('does not label a catalog game as unowned while its import membership read is unavailable', async ({ page }) => {
  let importReads = 0
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/auth/session') return route.fulfill({ json: { username: 'player', roles: ['USER'] } })
    if (url.pathname === '/api/v1/games') return route.fulfill({ json: [catalogGame('game-1', 'edition-1', 'Catalog Game')] })
    if (url.pathname === '/api/v1/documents' || url.pathname === '/api/v1/teaching-plans'
      || url.pathname === '/api/v1/documents/upload-teaching-handoffs'
      || url.pathname === '/api/v1/assistant-runs/active') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents/official-imports') {
      importReads += 1
      return route.fulfill({ status: 503 })
    }
    return route.fulfill({ status: 404 })
  })

  await page.goto('/games/game-1')
  await expect.poll(() => importReads).toBeGreaterThan(0)
  await expect(page.getByRole('heading', { name: '这款桌游不在你的“我的桌游”中。' })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '暂时无法确认这款桌游是否已加入“我的桌游”。' })).toBeVisible()
  await expect(page.getByRole('button', { name: '重试' })).toBeVisible()
  await expect(page.locator('.animate-pulse')).toHaveCount(0)
})

test('shows an exact failed preparation and stops claiming or observing background work', async ({ page }) => {
  let importReads = 0
  let preparationReads = 0
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/auth/session') return route.fulfill({ json: { username: 'player', roles: ['USER'] } })
    if (url.pathname === '/api/v1/games') return route.fulfill({ json: [catalogGame('game-1', 'edition-1', 'Catalog Game')] })
    if (url.pathname === '/api/v1/documents') return route.fulfill({
      json: [ownedDocument('document-1', 'edition-1', 'version-1', 'Official Rules')],
    })
    if (url.pathname === '/api/v1/documents/official-imports') {
      importReads += 1
      return route.fulfill({ json: [] })
    }
    if (url.pathname === '/api/v1/documents/upload-teaching-handoffs') return route.fulfill({ json: [{
      id: 'handoff-1', documentVersionId: 'version-1', editionId: 'edition-1', rulebookTitle: 'Official Rules',
      state: 'LAUNCHED', preparationRunId: 'preparation-1', errorCode: null,
      createdAt: '2026-08-13T08:00:00Z', updatedAt: '2026-08-13T08:00:00Z',
    }] })
    if (url.pathname === '/api/v1/assistant-runs/preparation-1') {
      preparationReads += 1
      return route.fulfill({ json: { run: preparationRun('FAILED') } })
    }
    if (url.pathname === '/api/v1/teaching-plans' || url.pathname === '/api/v1/assistant-runs/active') {
      return route.fulfill({ json: [] })
    }
    return route.fulfill({ status: 404 })
  })

  await page.clock.install()
  await page.goto('/games/game-1')
  await expect(page.getByTestId('player-work-status').filter({ hasText: '需要处理' })).toBeVisible()
  await expect(page.getByText('讲解准备没有完成')).toBeVisible()
  await expect(page.getByText('讲解任务已持久化，正在后台准备')).toHaveCount(0)
  await expect(page.getByRole('link', { name: /去我的讲解重试/ })).toHaveAttribute('href', '/lessons')
  const settledImportReads = importReads
  const settledPreparationReads = preparationReads
  await page.clock.fastForward(12_000)
  expect(importReads).toBe(settledImportReads)
  expect(preparationReads).toBe(settledPreparationReads)
})

async function mockMyGames(page: Page, currentStage: () => 'IMPORT' | 'DOCUMENT' | 'GUIDE') {
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    const stage = currentStage()
    if (url.pathname === '/api/auth/session') return route.fulfill({ json: { username: 'player', roles: ['USER'] } })
    if (url.pathname === '/api/v1/games') return route.fulfill({ json: [catalogGame('game-1', 'edition-1', 'Catalog Game')] })
    if (url.pathname === '/api/v1/documents') return route.fulfill({ json: stage === 'IMPORT'
      ? []
      : [ownedDocument('document-1', 'edition-1', 'version-1', 'Official Rules')] })
    if (url.pathname === '/api/v1/documents/official-imports') return route.fulfill({ json: stage === 'IMPORT'
      ? [pendingImport()]
      : [] })
    if (url.pathname === '/api/v1/documents/upload-teaching-handoffs') return route.fulfill({ json: stage === 'DOCUMENT'
      ? [{
          id: 'handoff-1', documentVersionId: 'version-1', editionId: 'edition-1', rulebookTitle: 'Official Rules',
          state: 'LAUNCHED', preparationRunId: 'preparation-1', errorCode: null,
          createdAt: '2026-08-13T08:00:00Z', updatedAt: '2026-08-13T08:00:00Z',
        }]
      : [] })
    if (url.pathname === '/api/v1/teaching-plans') return route.fulfill({ json: stage === 'GUIDE'
      ? [{
          id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Catalog Game', premise: 'Learn the game.',
          sections: [], createdBy: 'player', createdAt: '2026-08-13T08:01:00Z',
        }]
      : [] })
    if (url.pathname === '/api/v1/assistant-runs/active') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/assistant-runs/preparation-1') return route.fulfill({ json: { run: preparationRun() } })
    return route.fulfill({ status: 404 })
  })
}

function catalogGame(gameId: string, editionId: string, name: string, bggId?: number) {
  return {
    game: { id: gameId, name },
    editions: [{ id: editionId, gameId, name: 'First Edition', language: 'en', publicationYear: 2024 }],
    expansions: [],
    bggMetadata: bggId ? {
      bggId, thumbnailUrl: '', bggUrl: `https://boardgamegeek.com/boardgame/${bggId}`,
      minPlayers: 1, maxPlayers: 5, playingTimeMinutes: 60, minimumAge: 10,
    } : null,
  }
}

function ownedDocument(documentId: string, editionId: string, versionId: string, title: string) {
  return {
    document: {
      id: documentId, gameEditionId: editionId, title, officialSourceUrl: null,
      officialCoverUrl: null, createdBy: 'player',
    },
    latestVersion: { id: versionId, status: 'READY' },
  }
}

function pendingImport() {
  return {
    id: 'import-1', title: 'Catalog Game', rulebookTitle: 'Official Rules', editionId: 'edition-1',
    editionName: 'First Edition', sourceDomain: 'publisher.example', stage: 'DOWNLOADING', downloadedBytes: 1024,
    totalBytes: 4096, documentVersionId: null, errorCode: null, teachingHandoffState: 'WAITING_FOR_DOCUMENT',
    teachingPreparationRunId: null, teachingErrorCode: null, updatedAt: '2026-08-13T08:00:00Z',
  }
}

function richDetails(bggId: number, description: string) {
  return {
    bggId, name: bggId === 42 ? 'Old Game' : 'Current Game', description, imageUrl: '', thumbnailUrl: '',
    averageRating: null, averageWeight: null, categories: [], mechanics: [], designers: [], publishers: [],
    bggUrl: `https://boardgamegeek.com/boardgame/${bggId}`,
  }
}

function preparationRun(state = 'LESSON_PLANNING') {
  return {
    id: 'preparation-1', mode: 'TEACHING_PREPARATION', subjectId: 'version-1', ownerUsername: 'player', state,
    updatedAt: '2026-08-13T08:00:00Z',
  }
}

async function hasHorizontalOverflow(page: Page) {
  return page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1)
}
