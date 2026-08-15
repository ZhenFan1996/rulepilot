import { expect, test, type Page } from '@playwright/test'

const readyDocument = {
  document: {
    id: 'document-1', gameEditionId: 'edition-1', title: 'Example Rulebook', officialSourceUrl: null,
    officialCoverUrl: null, createdBy: 'player',
  },
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

const directCapability = {
  capability: 'DIRECT_DOCUMENT', capabilityEvidence: ['DOCUMENT_RESPONSE_CONFIRMED'],
  capabilityCheckedAt: '2026-08-15T12:00:00Z', nextAction: 'IMPORT_DOCUMENT',
}

test('covers attributed discovery, official PDF intake, and explicit metadata confirmation on desktop', async ({ page }) => {
  let officialImport: Record<string, unknown> | null = null
  let bggImportCount = 0
  let bggLink: Record<string, unknown> | null = null
  await mockOnboardingApis(page, {
    recommendations: [hotGame],
    suggestions: [candidate],
    onOfficialImport: body => { officialImport = body },
    onBggImport: () => { bggImportCount++ },
    onBggLink: body => { bggLink = body },
  })
  await page.setViewportSize({ width: 1440, height: 900 })

  await page.goto('/')
  await page.getByRole('link', { name: '找桌游', exact: true }).click()
  await page.getByRole('link', { name: /打开完整桌游目录/ }).click()
  await expect(page).toHaveURL('/discover/catalog')
  await expect(page.getByRole('heading', { name: '浏览全部桌游' })).toBeVisible()
  await expect(page.getByRole('link', { name: /Catalog Game/ })).toBeVisible()
  await expect(page.getByText('1–5 人 · 约 60 分钟')).toBeVisible()
  const bggAttribution = page.locator('a[href="https://boardgamegeek.com"]')
  await expect(bggAttribution.getByRole('img', { name: 'Powered by BoardGameGeek' })).toBeVisible()

  await page.getByRole('link', { name: /Catalog Game/ }).click()
  await expect(page).toHaveURL('/discover/42')
  await expect(page.getByRole('heading', { name: 'Catalog Game' })).toBeVisible()
  await expect(page.getByText(/BGG 资料仅用于推荐、识别游戏和展示封面/)).toBeVisible()
  await expect(page.getByRole('heading', { name: 'BGG 版本图片' })).toBeVisible()
  await expect(page.getByText('Simplified Chinese edition')).toBeVisible()
  await expect(page.getByRole('link', { name: /BGG 社区文件（用户上传，非官方）/ })).toHaveAttribute(
    'href', 'https://boardgamegeek.com/boardgame/42/files',
  )
  const coverColumn = page.getByTestId('game-cover-column')
  const longDetails = page.getByTestId('game-long-details')
  await page.getByText(/BGG 资料仅用于推荐、识别游戏和展示封面/).scrollIntoViewIfNeeded()
  await page.evaluate(() => window.scrollTo(
    0,
    Math.min(900, document.documentElement.scrollHeight - window.innerHeight),
  ))
  const [coverBox, detailsBox] = await Promise.all([coverColumn.boundingBox(), longDetails.boundingBox()])
  expect(coverBox).not.toBeNull()
  expect(detailsBox).not.toBeNull()
  expect(rectanglesOverlap(coverBox!, detailsBox!)).toBe(false)
  await page.getByRole('button', { name: '选择这款桌游并找规则书' }).click()
  await expect(page).toHaveURL(/\/teach\?editionId=edition-1&onboarding=selected-game/)
  expect(bggImportCount).toBe(1)
  await expect(page.getByText('正在为这款桌游找规则书')).toBeVisible()
  await expect(page.getByText('已选择版本：BGG 基础版')).toBeVisible()
  await expect(page.getByText('已有 PDF', { exact: true })).toBeVisible()
  await expect(page.getByRole('spinbutton')).toHaveCount(0)
  await page.getByText('可选：关联游戏、规则书来源和讲解偏好').click()
  await expect(page.getByLabel('这是什么资料？')).toBeVisible()

  await page.getByRole('button', { name: '帮我找规则书' }).click()
  await expect(page.getByText('publisher.example')).toBeVisible()
  await page.getByRole('button', { name: '选择并继续核对' }).click()
  const officialButton = page.getByRole('button', { name: '下载规则书并生成讲解' })
  await expect(officialButton).toBeDisabled()
  await expect(page.getByRole('textbox', { name: /规则书来源链接/ })).toHaveValue('https://publisher.example/rules.pdf')
  await expect(page.getByText('目录语言未知，不能用来源语言静默替换')).toBeVisible()
  await page.getByRole('checkbox', { name: /我已比较以上游戏、版本和语言/ }).check()
  await expect(officialButton).toBeDisabled()
  await page.getByRole('checkbox', { name: /我确认该来源有权提供这份规则书/ }).check()
  await expect(officialButton).toBeEnabled()
  await officialButton.click()
  await expect.poll(() => officialImport).toEqual({
    editionId: 'edition-1',
    title: 'Catalog Game Rules',
    sourceType: 'BASE_RULEBOOK',
    officialSourceUrl: 'https://publisher.example/rules.pdf',
    rightsConfirmed: true,
    startTeaching: true,
    learningGoal: null,
    discoveredForEditionId: 'edition-1',
    sourceEdition: 'First',
    sourceLanguage: 'zh-CN',
    sourceLanguageVerified: true,
    identityConfirmed: true,
  })
  await expect(page.getByText('规则书与讲解正在后台准备')).toBeVisible()
  await expect(page.getByText('正在下载规则书内容')).toBeVisible()
  await expect(page.getByText(/可以离开这一页/)).toBeVisible()
  await expect(page.getByText('已有 PDF', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: '补全桌游资料' }).click()
  await expect(page.getByText('BGG 资料只用于封面和目录展示，不会作为规则问答证据。')).toBeVisible()
  await page.getByRole('button', { name: '选择此项' }).click()
  await page.getByRole('button', { name: '确认关联这款桌游' }).click()
  await expect.poll(() => bggLink).toEqual({ bggId: 42 })
  await expect(page.getByText('已关联桌游资料，并保留原规则书作为唯一规则证据。')).toBeVisible()
  await expect(page.getByRole('link', { name: '阅读规则书并答疑' })).toHaveAttribute('href', '/rulebooks/version-1')
  await expect(page.getByRole('button', { name: '后台生成讲解' })).toBeVisible()
  await page.getByRole('link', { name: '阅读规则书并答疑' }).click()
  await expect(page).toHaveURL('/rulebooks/version-1')
  await expect(page.getByRole('heading', { name: 'Example Rulebook' })).toBeVisible()
  await expect(page.getByRole('button', { name: '基于这本规则书答疑' }).first()).toBeVisible()
  expect(await hasHorizontalOverflow(page)).toBe(false)

  await page.goto('/games/game-1')
  await expect(page.getByRole('heading', { name: 'Catalog Game' })).toBeVisible()
  await expect(page.getByText('Example Rulebook')).toBeVisible()
  await expect(page.getByRole('link', { name: '打开讲解' })).toHaveAttribute('href', '/lesson/plan-1')
  await page.getByRole('link', { name: '规则答疑' }).click()
  await expect(page).toHaveURL('/lesson/plan-1/questions')
})

test('keeps the game identity and primary action in proportion on mobile', async ({ page }) => {
  await mockOnboardingApis(page, { recommendations: [hotGame], suggestions: [candidate] })
  await page.setViewportSize({ width: 390, height: 844 })

  await page.goto('/discover/42')
  const coverColumn = page.getByTestId('game-cover-column')
  const heading = page.getByRole('heading', { name: 'Catalog Game' })
  const primaryAction = page.getByRole('button', { name: '选择这款桌游并找规则书' })
  await expect(coverColumn).toBeVisible()
  await expect(heading).toBeVisible()
  await expect(primaryAction).toBeVisible()

  const proportions = await page.evaluate(() => {
    const cover = document.querySelector<HTMLElement>('[data-testid="game-cover-column"]')!
    const panel = cover.closest<HTMLElement>('section')!
    const title = panel.querySelector<HTMLElement>('h1')!
    const primary = panel.querySelector<HTMLElement>('button')!
    const coverRect = cover.getBoundingClientRect()
    const panelRect = panel.getBoundingClientRect()
    const titleRect = title.getBoundingClientRect()
    const primaryRect = primary.getBoundingClientRect()
    return {
      hasHorizontalOverflow: document.documentElement.scrollWidth > window.innerWidth + 1,
      coverShare: coverRect.width / panelRect.width,
      coverHeight: coverRect.height,
      coverEndsBeforeTitle: coverRect.right < titleRect.left,
      titleTop: titleRect.top,
      primaryBottom: primaryRect.bottom,
      viewportHeight: window.innerHeight,
    }
  })

  expect(proportions.hasHorizontalOverflow).toBe(false)
  expect(proportions.coverShare).toBeLessThanOrEqual(0.42)
  expect(proportions.coverHeight).toBeLessThanOrEqual(210)
  expect(proportions.coverEndsBeforeTitle).toBe(true)
  expect(proportions.titleTop).toBeLessThan(420)
  expect(proportions.primaryBottom).toBeLessThan(proportions.viewportHeight)
})

test('uses verified source capability for every desktop source-selection action', async ({ page }) => {
  await mockOnboardingApis(page, {
    recommendations: [hotGame],
    suggestions: [candidate],
    rulebookCandidates: [{
      title: 'Opaque confirmed response', url: 'https://publisher.example/asset/42', publisher: 'Opaque Studio',
      language: 'en', edition: 'First', sourceDomain: 'publisher.example', officialDomainVerified: true,
      languageVerified: true, sourceType: 'PUBLISHER', acquisitionMode: 'DIRECT_PDF', ...directCapability,
    }, {
      title: 'Opaque page sequence', url: 'https://pages.example/viewer/42', publisher: 'Opaque Studio',
      language: 'en', edition: 'First', sourceDomain: 'pages.example', officialDomainVerified: true,
      languageVerified: true, sourceType: 'PUBLISHER', acquisitionMode: 'IMAGE_GALLERY',
      capability: 'CONTIGUOUS_RULE_PAGES', capabilityEvidence: ['ORDERED_PAGE_SEQUENCE_CONFIRMED'],
      capabilityCheckedAt: '2026-08-15T12:00:00Z', nextAction: 'IMPORT_PAGE_SEQUENCE',
    }, {
      title: 'Opaque document collection', url: 'https://listing.example/files', publisher: 'Opaque Studio',
      language: 'en', edition: 'First', sourceDomain: 'listing.example', officialDomainVerified: true,
      sourceType: 'PUBLISHER', acquisitionMode: 'SOURCE_PAGE', capability: 'DOCUMENT_LISTING',
      capabilityEvidence: ['DOWNLOADABLE_DOCUMENT_LINKS_OBSERVED'],
      capabilityCheckedAt: '2026-08-15T12:00:00Z', nextAction: 'CONTINUE_ON_SOURCE',
    }, {
      title: 'Rules PDF download', url: 'https://catalog.example/not-a-document.pdf', publisher: 'Opaque Studio',
      language: 'en', edition: 'First', sourceDomain: 'catalog.example', officialDomainVerified: true,
      sourceType: 'PUBLISHER', acquisitionMode: 'SOURCE_PAGE', capability: 'GAME_INFO_ONLY',
      capabilityEvidence: ['EXPLICIT_EMPTY_DOCUMENT_COLLECTION'],
      capabilityCheckedAt: '2026-08-15T12:00:00Z', nextAction: 'USE_FOR_IDENTITY_ONLY',
    }, {
      title: 'Opaque protected page', url: 'https://review.example/login', publisher: 'Opaque Studio',
      language: 'en', edition: 'First', sourceDomain: 'review.example', officialDomainVerified: true,
      sourceType: 'PUBLISHER', acquisitionMode: 'SOURCE_PAGE', capability: 'UNVERIFIED_PAGE',
      capabilityEvidence: ['ACCESS_REQUIRES_LOGIN'], capabilityCheckedAt: '2026-08-15T12:00:00Z',
      nextAction: 'REVIEW_OR_UPLOAD',
    }],
  })
  await page.addInitScript(() => {
    Object.defineProperty(window, '__openedRulebookSources', { value: [], writable: true })
    window.open = ((url?: string | URL) => {
      ;(window as Window & { __openedRulebookSources: string[] }).__openedRulebookSources.push(String(url))
      return null
    }) as typeof window.open
  })
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/teach?editionId=edition-1&onboarding=selected-game')

  await page.getByRole('button', { name: '帮我找规则书' }).click()
  await expect(page.locator('[data-capability="DIRECT_DOCUMENT"] button')).toHaveText('选择并继续核对')
  await expect(page.locator('[data-capability="CONTIGUOUS_RULE_PAGES"] button')).toHaveText('选择并继续核对')
  await expect(page.locator('[data-capability="DOCUMENT_LISTING"] button')).toHaveText('继续查找文件')
  await expect(page.locator('[data-capability="UNVERIFIED_PAGE"] button')).toHaveText('审阅来源页')
  await expect(page.locator('[data-capability="GAME_INFO_ONLY"] button')).toHaveCount(0)

  await page.locator('[data-capability="DOCUMENT_LISTING"] button').click()
  await page.locator('[data-capability="UNVERIFIED_PAGE"] button').click()
  await expect.poll(() => page.evaluate(() =>
    (window as Window & { __openedRulebookSources: string[] }).__openedRulebookSources,
  )).toEqual(['https://listing.example/files', 'https://review.example/login'])

  await page.locator('[data-capability="DIRECT_DOCUMENT"] button').click()
  await expect(page.getByRole('textbox', { name: /规则书来源链接/ }))
    .toHaveValue('https://publisher.example/asset/42')
})

test('keeps manual onboarding and the ready guide usable when BGG fails on mobile', async ({ page }) => {
  await mockOnboardingApis(page, { recommendations: null, suggestions: null })
  await page.setViewportSize({ width: 390, height: 844 })

  await page.goto('/')
  await expect(page.getByRole('heading', { name: '规则书递过来，咱们开桌。' })).toBeVisible()
  await expect(page.getByRole('link', { name: '我有规则书' }).first()).toBeVisible()
  expect(await hasHorizontalOverflow(page)).toBe(false)

  await page.getByRole('link', { name: '慢慢逛完整目录' }).click()
  await expect(page.getByText('桌游目录暂时打不开')).toBeVisible()
  await expect(page.getByText('筛选条件已经保留，可以稍后重试。')).toBeVisible()
  expect(await hasHorizontalOverflow(page)).toBe(false)

  await page.goto('/teach')
  await page.getByRole('button', { name: '补全桌游资料' }).click()
  await expect(page.getByText('暂时无法连接 BGG。规则书和讲解不受影响，你可以稍后重试。')).toBeVisible()
  await expect(page.getByText('已有 PDF', { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: '阅读规则书并答疑' })).toBeVisible()
  await expect(page.getByRole('button', { name: '后台生成讲解' })).toBeVisible()
  expect(await hasHorizontalOverflow(page)).toBe(false)
})

async function mockOnboardingApis(page: Page, options: {
  recommendations: Array<typeof hotGame> | null
  suggestions: Array<typeof candidate> | null
  onOfficialImport?: (body: Record<string, unknown>) => void
  onBggImport?: () => void
  onBggLink?: (body: Record<string, unknown>) => void
  rulebookCandidates?: Array<Record<string, unknown>>
}) {
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (path === '/api/auth/session') return route.fulfill({ json: { username: 'player', roles: ['USER'] } })
    if (path === '/api/auth/csrf') return route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'csrf' } })
    if (path === '/api/v1/assistant-runs/active') return route.fulfill({ json: [] })
    if (path === '/api/v1/teaching-plans') return route.fulfill({ json: options.recommendations === null ? [] : [{
      id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Catalog Game', createdBy: 'player', createdAt: '2026-08-06T00:00:00Z',
    }] })
    if (path === '/api/public/lessons') return route.fulfill({ json: [] })
    if (path === '/api/v1/bgg/recommendations') {
      return options.recommendations === null
        ? route.fulfill({ status: 503 })
        : route.fulfill({ json: options.recommendations })
    }
    if (path === '/api/v1/bgg/catalog') {
      if (options.recommendations === null) return route.fulfill({ status: 503 })
      const enriched = url.searchParams.get('enrich') === 'true'
      return route.fulfill({ json: {
        ready: true,
        sourceCount: 1,
        total: 1,
        page: 0,
        size: 20,
        totalPages: 1,
        sort: 'rank',
        type: 'all',
        sourceDate: '2026-08-09',
        taxonomyTranslated: enriched,
        games: [{
          ...hotGame,
          originalName: hotGame.name,
          nameLocalized: false,
          overallRank: 1,
          hotRank: 1,
          geekRating: 7.5,
          usersRated: 1000,
          expansion: false,
          types: ['strategy'],
          detailsAvailable: enriched,
          thumbnailUrl: hotGame.thumbnailUrl,
        }],
      } })
    }
    if (path === '/api/v1/bgg/games/42' && request.method() === 'GET') {
      return route.fulfill({ json: {
        ...hotGame,
        description: 'A game selected from recommendations.',
        minimumAge: 10,
        editionImages: [{
          versionId: 7,
          name: 'Simplified Chinese edition',
          imageUrl: 'https://example.test/chinese-edition.jpg',
          publicationYear: 2024,
          languages: ['Chinese'],
        }],
      } })
    }
    if (path === '/api/v1/bgg/games/42/import' && request.method() === 'POST') {
      options.onBggImport?.()
      return route.fulfill({ json: {
        game: { id: 'game-1', name: 'Catalog Game' },
        edition: { id: 'edition-1', gameId: 'game-1', name: 'BGG 基础版', language: 'und', publicationYear: 2024 },
        bggId: 42,
        alreadyImported: false,
      } })
    }
    if (path === '/api/v1/games') return route.fulfill({ json: options.recommendations === null ? [] : [{
      game: { id: 'game-1', name: 'Catalog Game' },
      editions: [{ id: 'edition-1', gameId: 'game-1', name: 'BGG 基础版', language: 'und', publicationYear: 2024 }],
      expansions: [],
      bggMetadata: {
        bggId: 42,
        thumbnailUrl: hotGame.thumbnailUrl,
        bggUrl: hotGame.bggUrl,
        minPlayers: 1,
        maxPlayers: 5,
        playingTimeMinutes: 60,
        minimumAge: 10,
      },
    }] })
    if (path === '/api/v1/model-configuration') {
      return route.fulfill({ json: {
        providers: [{ id: 'qwen', configured: true, visionCapable: true }],
        assignments: { teaching: 'qwen', visual: 'qwen' },
      } })
    }
    if (path === '/api/v1/documents/rulebook-candidates') {
      return route.fulfill({ json: {
        configured: true,
        identity: {
          editionId: 'edition-1', gameName: 'Catalog Game', editionName: 'BGG 基础版', language: 'und',
        },
        candidates: options.rulebookCandidates ?? [{
          title: 'Catalog Game Rules', url: 'https://publisher.example/rules.pdf', publisher: 'Publisher',
          language: 'zh-CN', languageVerified: true, edition: 'First', sourceDomain: 'publisher.example', officialDomainVerified: true,
          sourceType: 'PUBLISHER', acquisitionMode: 'DIRECT_PDF',
          ...directCapability,
        }],
      } })
    }
    if (path === '/api/v1/documents' && request.method() === 'GET') {
      return route.fulfill({ json: [readyDocument] })
    }
    if (path === '/api/v1/documents/upload-teaching-handoffs' && request.method() === 'GET') {
      return route.fulfill({ json: [] })
    }
    if (path === '/api/v1/document-versions/version-1/pages' && request.method() === 'GET') {
      return route.fulfill({ json: [
        { pageNumber: 1, text: 'Set up the game.', characterCount: 16 },
        { pageNumber: 2, text: 'Take a turn.', characterCount: 12 },
      ] })
    }
    if (/^\/api\/v1\/document-versions\/version-1\/pages\/\d+\/image$/.test(path)) {
      return route.fulfill({
        status: 200,
        contentType: 'image/svg+xml',
        body: '<svg xmlns="http://www.w3.org/2000/svg" width="800" height="1100"><rect width="100%" height="100%" fill="#fffaf2"/></svg>',
      })
    }
    if (path === '/api/v1/documents/official-imports' && request.method() === 'POST') {
      options.onOfficialImport?.(request.postDataJSON() as Record<string, unknown>)
      return route.fulfill({ status: 202, json: {
        id: 'import-job-1', title: 'Catalog Game Rules', sourceDomain: 'publisher.example', stage: 'QUEUED',
        downloadedBytes: 0, totalBytes: 4096, documentVersionId: null, duplicate: false, errorCode: null, reused: false,
        teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null, teachingErrorCode: null,
      } })
    }
    if (path === '/api/v1/documents/official-imports/import-job-1' && request.method() === 'GET') {
      return route.fulfill({ json: {
        id: 'import-job-1', title: 'Catalog Game Rules', sourceDomain: 'publisher.example', stage: 'DOWNLOADING',
        downloadedBytes: 2048, totalBytes: 4096, documentVersionId: null, duplicate: false, errorCode: null, reused: false,
        teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null, teachingErrorCode: null,
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

function rectanglesOverlap(
  left: { x: number; y: number; width: number; height: number },
  right: { x: number; y: number; width: number; height: number },
) {
  return left.x < right.x + right.width
    && left.x + left.width > right.x
    && left.y < right.y + right.height
    && left.y + left.height > right.y
}

async function hasHorizontalOverflow(page: Page) {
  return page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)
}
