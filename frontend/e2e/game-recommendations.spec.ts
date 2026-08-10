import { expect, test } from '@playwright/test'

const catalog = {
  ready: true,
  sourceCount: 162686,
  total: 7543,
  page: 0,
  size: 20,
  totalPages: 378,
  sort: 'hot',
  type: 'all',
  importedAt: '2026-08-07T08:00:00Z',
  sourceDate: '2026-08-07',
  taxonomyTranslated: true,
  games: [{
    bggId: 266192,
    name: '展翅翱翔',
    originalName: 'Wingspan',
    nameLocalized: true,
    publicationYear: 2019,
    overallRank: 34,
    hotRank: 2,
    geekRating: 7.79,
    averageRating: 8.09,
    usersRated: 102030,
    expansion: false,
    types: ['family', 'strategy'],
    detailsAvailable: true,
    thumbnailUrl: 'https://example.test/wingspan.jpg',
    minPlayers: 1,
    maxPlayers: 5,
    playingTimeMinutes: 70,
    averageWeight: 2.5,
    categories: ['策略'],
    mechanics: ['卡牌轮抽'],
    bggUrl: 'https://boardgamegeek.com/boardgame/266192',
  }],
}

const similarToMosaicField = {
  ...catalog.games[0],
  bggId: 600061,
  name: 'Glass Orchard',
  originalName: 'Glass Orchard',
  nameLocalized: false,
  publicationYear: 2024,
  overallRank: 2190,
  thumbnailUrl: 'https://example.test/glass-orchard.jpg',
  minPlayers: 1,
  maxPlayers: 4,
  playingTimeMinutes: 45,
  averageWeight: 1.93,
  categories: ['抽象策略', '骰子'],
  mechanics: ['轮抽', '图案构筑'],
  bggUrl: 'https://boardgamegeek.com/boardgame/600061',
}

async function mockPublicDiscovery(page: import('@playwright/test').Page, authenticated = false) {
  await page.route('**/api/auth/session', route => authenticated
    ? route.fulfill({ json: { username: 'player', roles: ['USER'] } })
    : route.fulfill({ status: 401 }))
  await page.route('**/api/auth/csrf', route => route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'csrf' } }))
  await page.route('**/api/v1/assistant-runs/active?*', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/bgg/recommendation-agent**', async route => {
    const body = route.request().postDataJSON() as {
      profile: { players: number | null; maxMinutes: number | null; maxWeight: number | null }
      message: string
      focusedBggId: number | null
      transcript: { role: string; text: string }[]
    }
    const fulfill = async (payload: Record<string, unknown>) => {
      if (route.request().url().includes('/stream?')) {
        await route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          body: `event: progress\ndata: {"stage":"selecting_tools","elapsedMs":8}\n\nevent: result\ndata: ${JSON.stringify(payload)}\n\n`,
        })
        return
      }
      await route.fulfill({ json: payload })
    }
    if (body.message.includes('马赛克花园')) {
      await fulfill({
        outcome: 'needs_clarification', mode: 'model_assisted',
        assistantMessage: '我明白你想找一款机制相近的游戏。“马赛克花园”可能是译名或口头叫法，你知道它的原文名吗？只说名字就行，我会接着上一句查。',
        profile: { ...body.profile, type: 'all', interaction: 'any' },
        clarification: { field: 'conversation', prompt: '它的原文名是什么？', options: [] },
        sourceCount: 179737, candidatesEvaluated: 0,
        userModel: { summary: '想找与“马赛克花园”机制相近的游戏。', hypotheses: [] },
        harness: { modelCalls: 2, catalogCalls: 1, webResearchCalls: 0, fallbackUsed: false, actions: ['RESOLVE_BGG_REFERENCE', 'ASK_USER'] },
        games: [],
      })
      return
    }
    if (body.message.trim().toLowerCase() === 'mosaic field'
      && body.transcript.some(message => message.role === 'user' && message.text.includes('马赛克花园'))) {
      await fulfill({
        outcome: 'recommendations', mode: 'model_assisted',
        assistantMessage: '对，你说的是 Mosaic Field。我已经把它和上一句的“机制相近”连在一起，先核对了 BGG 中的参考游戏，再查候选，不会把 Mosaic Field 当成一个全新问题。',
        profile: { ...body.profile, type: 'all', interaction: 'any' }, clarification: null,
        sourceCount: 179737, candidatesEvaluated: 6,
        userModel: { summary: '以 Mosaic Field 为参照，寻找机制相近的游戏。', hypotheses: [] },
        harness: { modelCalls: 4, catalogCalls: 3, webResearchCalls: 0, fallbackUsed: false, actions: ['RESOLVE_BGG_REFERENCE', 'SEARCH_BGG_BY_NAME', 'LOOKUP_BGG_CANDIDATES', 'RECOMMEND_GAMES'] },
        games: [{ game: similarToMosaicField, matches: ['同样包含轮抽与图案构筑'], tradeoffs: ['候选使用骰子，随机性更高'] }],
      })
      return
    }
    if (body.focusedBggId === 266192) {
      await fulfill({
        outcome: 'recommendations', mode: 'model_assisted', assistantMessage: '我补查了教学和实际桌上节奏。',
        profile: { ...body.profile, type: 'all', interaction: 'any' }, clarification: null,
        sourceCount: 179737, candidatesEvaluated: 1,
        userModel: { summary: '朋友聚会，可能重视参与感', hypotheses: [{ text: '可能不喜欢等待太久', confidence: 'medium', basedOn: '想热闹一点' }] },
        researchSources: [{ index: 1, title: 'Publisher guide', url: 'https://publisher.example/wingspan', domain: 'publisher.example' }],
        harness: { modelCalls: 3, catalogCalls: 1, webResearchCalls: 1, fallbackUsed: false, actions: ['LOOKUP_BGG_CANDIDATES', 'RESEARCH_GAME_FIT', 'RECOMMEND_GAMES'] },
        games: [{ game: catalog.games[0], matches: ['BGG 总榜第 34 名'], tradeoffs: [], reasons: [
          { kind: 'bgg_fact', text: 'BGG 总榜第 34 名', sourceIndexes: [] },
          { kind: 'preference_inference', text: '可能适合希望全桌持续参与的场景', sourceIndexes: [] },
          { kind: 'web_research', text: '发行商资料提供了分步教学流程', sourceIndexes: [1] },
        ] }],
      })
      return
    }
    await fulfill({
      outcome: 'recommendations', mode: 'model_assisted', assistantMessage: '明白：4 个人、90 分钟内，想要中等策略和有参与感的聚会气氛。我先按这些条件核对一批；哪一点不对，直接告诉我就行。',
      profile: { players: 4, maxMinutes: 90, maxWeight: 3.2, type: 'all', interaction: 'any' }, clarification: null,
      sourceCount: 179737, candidatesEvaluated: 20,
      harness: { modelCalls: 4, catalogCalls: 2, webResearchCalls: 0, fallbackUsed: false, actions: ['UPDATE_PREFERENCES', 'SEARCH_BGG_CATALOG', 'LOOKUP_BGG_CANDIDATES', 'RECOMMEND_GAMES'] },
      games: [{ game: catalog.games[0], matches: ['支持 4 人游玩', '70 分钟，不超过你的时长上限'], tradeoffs: [] }],
    })
  })
  await page.route('**/api/v1/bgg/catalog?*', async route => {
    if (route.request().url().includes('enrich=true')) {
      await new Promise(resolve => setTimeout(resolve, 1_500))
      await route.fulfill({ json: catalog })
      return
    }
    await route.fulfill({
      json: {
        ...catalog,
        taxonomyTranslated: false,
        games: [{
          ...catalog.games[0],
          name: 'Wingspan',
          originalName: 'Wingspan',
          nameLocalized: false,
          detailsAvailable: false,
          thumbnailUrl: '',
          minPlayers: null,
          maxPlayers: null,
          playingTimeMinutes: null,
          averageWeight: null,
          categories: [],
          mechanics: [],
        }],
      },
    })
  })
  await page.route('**/api/v1/bgg/games/266192/import', route => route.fulfill({ json: {
    game: { id: 'game-1', name: '展翅翱翔' },
    edition: { id: 'edition-1', name: 'BGG 基础版' },
    alreadyImported: false,
  } }))
  await page.route('**/api/v1/documents/rulebook-candidates?*', route => route.fulfill({ json: {
    configured: true,
    candidates: [{
      title: 'Wingspan Rulebook', url: 'https://publisher.example/wingspan.pdf',
      publisher: 'Stonemaier Games', language: 'English', edition: 'Base game',
      sourceDomain: 'publisher.example', officialDomainVerified: true,
      sourceType: 'PUBLISHER', acquisitionMode: 'DIRECT_PDF',
    }],
  } }))
  await page.route('**/api/v1/documents/official-imports', route => route.request().method() === 'POST'
    ? route.fulfill({ status: 202, json: {
        id: 'import-job-1', title: 'Wingspan Rulebook', sourceDomain: 'publisher.example', stage: 'COMPLETED',
        downloadedBytes: 4096, totalBytes: 4096, documentVersionId: 'version-1',
        duplicate: false, errorCode: null, reused: false,
        teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'preparation-run-1', teachingErrorCode: null,
      } })
    : route.fulfill({ json: [] }))
  await page.route('**/api/v1/games', route => route.fulfill({ json: [{
    game: { id: 'game-1', name: '展翅翱翔' },
    editions: [{ id: 'edition-1', name: 'BGG 基础版', language: 'und' }],
    expansions: [],
  }] }))
  await page.route('**/api/v1/teaching-plans', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/model-configuration', route => route.fulfill({ json: {
    providers: [{ id: 'qwen', configured: true, visionCapable: true }],
    assignments: { teaching: 'qwen', visual: 'qwen' },
  } }))
  await page.route('**/api/v1/documents', route => route.fulfill({ json: [{
    document: { id: 'document-1', gameEditionId: 'edition-1', title: 'Wingspan Rulebook', officialSourceUrl: 'https://publisher.example/wingspan.pdf' },
    latestVersion: { id: 'version-1', originalFilename: 'wingspan.pdf', size: 4096, status: 'EXTRACTING' },
  }] }))
  await page.route('**/api/v1/document-versions/version-1/progress/snapshot', route => route.fulfill({ json: {
    stage: 'EXTRACTING', percentage: 45, processedPages: 0, totalPages: 0, complete: false,
  } }))
}

test('keeps full-catalog browsing separate from the conversational recommendation journey', async ({ page }) => {
  await mockPublicDiscovery(page)
  await page.goto('/discover/catalog')

  await expect(page.getByRole('heading', { level: 1 })).toContainText('按自己的节奏慢慢挑')
  await expect(page.getByText('BGG 收录 162,686 条')).toBeVisible()
  await expect(page.locator('#game-catalog').getByRole('heading', { level: 3, name: 'Wingspan' })).toBeVisible()
  await expect(page.getByText('更多封面和游戏资料正在补齐')).toBeVisible()
  await expect(page.locator('#game-catalog').getByText('展翅翱翔')).toBeVisible()
  await expect(page.locator('#game-catalog li', { hasText: '卡牌轮抽' })).toBeVisible()
  await expect(page.getByRole('link', { name: '数据由 BoardGameGeek 提供' }).locator('img')).toHaveAttribute('src', '/powered-by-bgg-rgb.svg')

  await page.getByRole('combobox', { name: '排序' }).selectOption('rating')
  const filteredRequest = page.waitForRequest(request => request.url().includes('/api/v1/bgg/catalog?')
    && request.url().includes('sort=rating') && request.url().includes('type=strategy'))
  await page.getByRole('combobox', { name: /BGG 类型榜/ }).selectOption('strategy')
  await filteredRequest

  await page.getByLabel('搜索桌游').fill('Wingspan')
  const searchRequest = page.waitForRequest(request => request.url().includes('/api/v1/bgg/catalog?')
    && request.url().includes('q=Wingspan'))
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await searchRequest
  await expect(page.locator('#game-catalog').getByRole('link', { name: /展翅翱翔/ })).toHaveAttribute('href', '/discover/266192')
  await expect(page.getByText('第 1 / 378 页')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '再看一批' })).toBeVisible()

  await page.getByRole('link', { name: /让推荐助手帮我挑/ }).click()
  await expect(page).toHaveURL('/discover')
  await expect(page.getByRole('heading', { level: 1 })).toContainText('先聊聊今晚想玩什么')
  await expect(page.locator('#game-catalog')).toHaveCount(0)

  const firstAgentRequest = page.waitForRequest(request => request.url().includes('/api/v1/bgg/recommendation-agent')
    && request.headers()['x-csrf-token'] === 'csrf')
  const composer = page.getByLabel('和推荐 Agent 聊聊')
  await composer.fill('4 个人，90 分钟内，想要中等策略；朋友聚会，希望热闹但不要尴尬')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await firstAgentRequest
  await expect(page.getByText('从完整 BGG 目录中核对了 20 款候选。')).toBeVisible()
  await expect(page.getByText('支持 4 人游玩')).toBeVisible()

  const focusedRequest = page.waitForRequest(request => {
    if (!request.url().includes('/api/v1/bgg/recommendation-agent')) return false
    return (request.postDataJSON() as { focusedBggId?: number }).focusedBggId === 266192
  })
  await page.getByRole('button', { name: '介绍一下' }).click()
  await focusedRequest
  await expect(page.getByText('发行商资料提供了分步教学流程')).toBeVisible()
  await expect(page.getByRole('link', { name: /publisher\.example/ })).toHaveAttribute('rel', /noopener/)
  await expect(page.getByText('目前记下的偏好')).toBeVisible()
})

test('keeps full-catalog discovery usable without horizontal overflow at 390 px', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockPublicDiscovery(page)
  await page.goto('/discover/catalog')

  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expect(page.getByRole('navigation', { name: '主要导航' })).toBeVisible()
  await expect(page.locator('#game-catalog').getByRole('link', { name: /展翅翱翔/ })).toBeVisible()
  const hasHorizontalOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
  )
  expect(hasHorizontalOverflow).toBe(false)
})

test('keeps a corrected reference title in conversational context on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockPublicDiscovery(page)
  await page.goto('/discover')

  const composer = page.getByLabel('和推荐 Agent 聊聊')
  await composer.fill('我想玩和马赛克花园类似机制的游戏')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.getByText(/你知道它的原文名吗/)).toBeVisible()

  const correctionRequest = page.waitForRequest(request => {
    if (!request.url().includes('/api/v1/bgg/recommendation-agent')) return false
    const body = request.postDataJSON() as { message?: string }
    return body.message === 'Mosaic Field'
  })
  await composer.fill('Mosaic Field')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  const correction = await correctionRequest
  expect(correction.postDataJSON()).toMatchObject({
    message: 'Mosaic Field',
    transcript: expect.arrayContaining([
      { role: 'user', text: '我想玩和马赛克花园类似机制的游戏' },
      { role: 'user', text: 'Mosaic Field' },
    ]),
  })

  const recommendationTurn = page.getByTestId('assistant-recommendation-turn')
  const reply = recommendationTurn.getByText(/Mosaic Field 当成一个全新问题/)
  await expect(reply).toBeVisible()
  await expect(recommendationTurn.getByText('在 BGG 核对参考游戏')).toBeVisible()
  await expect(recommendationTurn.getByRole('heading', { level: 3, name: 'Glass Orchard' })).toBeVisible()
  const replyBox = await reply.boundingBox()
  const conversationBox = await page.getByTestId('recommendation-conversation').boundingBox()
  const composerBox = await composer.boundingBox()
  expect(replyBox).not.toBeNull()
  expect(conversationBox).not.toBeNull()
  expect(composerBox).not.toBeNull()
  expect(replyBox!.y).toBeGreaterThanOrEqual(conversationBox!.y)
  expect(replyBox!.y + replyBox!.height).toBeLessThanOrEqual(conversationBox!.y + conversationBox!.height)
  expect(replyBox!.y + replyBox!.height).toBeLessThanOrEqual(composerBox!.y)
})

test('selects a recommendation, reviews an official rulebook, and starts teaching in the background', async ({ page }) => {
  await mockPublicDiscovery(page, true)
  await page.goto('/discover')

  await page.getByLabel('和推荐 Agent 聊聊').fill('4 个人，90 分钟内，想要中等策略；朋友聚会，希望热闹但不要尴尬')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await page.getByRole('button', { name: '选这款，找规则书' }).click()

  await expect(page.getByRole('heading', { name: '已选《展翅翱翔》' })).toBeVisible()
  await expect(page.getByText('Wingspan Rulebook')).toBeVisible()
  await expect(page.getByText('出版社 / 权利方来源')).toBeVisible()
  await page.getByRole('button', { name: '选择这份' }).click()
  const handoff = page.getByRole('button', { name: '下载规则书并生成讲解' })
  await expect(handoff).toBeDisabled()
  await page.getByRole('checkbox', { name: /我确认该链接来自有权提供/ }).check()

  const officialImport = page.waitForRequest(request => request.url().endsWith('/api/v1/documents/official-imports'))
  await handoff.click()
  const request = await officialImport
  expect(request.postDataJSON()).toEqual({
    editionId: 'edition-1',
    title: 'Wingspan Rulebook',
    sourceType: 'BASE_RULEBOOK',
    officialSourceUrl: 'https://publisher.example/wingspan.pdf',
    rightsConfirmed: true,
    startTeaching: true,
    learningGoal: null,
  })
  await expect(page).toHaveURL(/\/discover$/)
  await expect(page.getByText('讲解已经在后台开始')).toBeVisible()
  await expect(page.getByRole('link', { name: /打开我的桌游/ })).toHaveAttribute('href', '/catalog')
  await expect(page.getByRole('link', { name: /查看讲解进度/ })).toHaveAttribute('href', '/lessons')

  await page.getByRole('link', { name: /打开我的桌游/ }).click()
  await expect(page).toHaveURL(/\/catalog$/)
  await expect(page.getByRole('heading', { level: 1, name: '今晚想开哪一局？' })).toBeVisible()
  await expect(page.getByRole('heading', { level: 2, name: '展翅翱翔' })).toBeVisible()
  await expect(page.getByText('1 本规则书', { exact: true })).toBeVisible()
})
