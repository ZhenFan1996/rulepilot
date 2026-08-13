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

const teachingPlan = {
  id: 'plan-1', documentVersionId: 'version-1', gameTitle: '展翅翱翔', premise: '先看目标，再按回合顺序练习。',
  createdBy: 'player',
  sections: [
    { position: 1, title: '游戏目标', visualEvidenceRecommended: false },
    { position: 2, title: '回合行动', visualEvidenceRecommended: false },
  ],
}

function lessonSection(position: number, title: string, text: string) {
  return {
    position, topicKey: `TOPIC_${position}`, coverageTags: [], title, required: true, evidenceStatus: 'SUPPORTED',
    visualKind: 'FLOW_DIAGRAM', visualCaption: '', visualSourcePages: [position + 1], visualSourceChunkIds: [`chunk-${position}`],
    steps: [{ position: 1, heading: title, kind: 'DO', text, sourcePages: [position + 1], visualFocus: null }],
  }
}

const draftLesson = {
  id: 'lesson-1', teachingPlanId: 'plan-1', status: 'DRAFT_READY',
  sections: [lessonSection(1, '游戏目标', '通过鸟类、奖励牌和蛋获得分数。')],
}

const completeLesson = {
  id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE',
  sections: [
    ...draftLesson.sections,
    lessonSection(2, '回合行动', '选择一个栖息地行动并依次结算。'),
  ],
}

function assistantRun(id: string, state: string, revision: number) {
  const updatedAt = `2026-08-10T08:00:0${revision}Z`
  const preparation = id === 'preparation-run-1'
  return {
    run: {
      id, state, revision, mode: preparation ? 'TEACHING_PREPARATION' : 'TEACHING',
      subjectId: preparation ? 'version-1' : 'plan-1', ownerUsername: 'player',
      createdAt: '2026-08-10T08:00:00Z', updatedAt,
      completedAt: state === 'COMPLETED' ? updatedAt : null, lastErrorCode: null,
    },
    budget: { usedModelCalls: revision, maxModelCalls: 12 },
    activities: state === 'COMPLETED' ? [{
      sequence: 1, type: 'VALIDATION', operation: 'publishTeachingSection|1', summary: 'CITED_BASE_SECTION_PUBLISHED',
      outcome: 'SUCCEEDED', latencyMs: 12, occurredAt: updatedAt,
    }] : [],
  }
}

function officialImportJob() {
  const updatedAt = new Date().toISOString()
  return {
    id: 'import-job-1', title: '展翅翱翔', rulebookTitle: 'Wingspan Rulebook',
    sourceDomain: 'publisher.example', stage: 'COMPLETED',
    downloadedBytes: 4096, totalBytes: 4096, documentVersionId: 'version-1',
    duplicate: false, errorCode: null, reused: false,
    teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'preparation-run-1', teachingErrorCode: null,
    downloadCompletedAt: updatedAt, importCompletedAt: updatedAt, teachingHandoffUpdatedAt: updatedAt, updatedAt,
  }
}

const ruleAnswer = {
  status: 'ANSWERED',
  shortVerdict: '获得食物后，再发动该栖息地中从右到左的棕色能力。',
  explanation: '规则书把获得食物写在发动棕色能力之前，因此按这个顺序结算。',
  citations: [{
    chunkId: 'answer-chunk-1', sectionType: 'TURN', heading: '获得食物',
    excerpt: '获得食物后，依次发动栖息地中的棕色能力。', pageFrom: 7, pageTo: 7,
  }],
  exceptions: [], confidence: 'HIGH', answerBasis: 'DIRECT_RULE', official: true,
  confirmedRulingId: null, confirmedRulingVersion: null, clarification: null, warnings: [],
}

async function mockPublicDiscovery(
  page: import('@playwright/test').Page,
  authenticated = false,
  holdPreparation = false,
) {
  let teachingPoll = 0
  let lessonPoll = 0
  let journeyImported = false
  await page.route('**/api/auth/session', route => authenticated
    ? route.fulfill({ json: { username: 'player', roles: ['USER'] } })
    : route.fulfill({ status: 401 }))
  await page.route('**/api/auth/csrf', route => route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'csrf' } }))
  await page.route('**/api/v1/assistant-runs/active?*', route => {
    const mode = new URL(route.request().url()).searchParams.get('mode')
    if (!journeyImported) return route.fulfill({ json: [] })
    if (mode === 'TEACHING_PREPARATION' && holdPreparation) {
      return route.fulfill({ json: [assistantRun('preparation-run-1', 'LESSON_PLANNING', 1).run] })
    }
    if (mode === 'TEACHING' && !holdPreparation) {
      return route.fulfill({ json: [assistantRun('teaching-run-1', 'LESSON_COMPOSITION', 2).run] })
    }
    return route.fulfill({ json: [] })
  })
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
    const url = new URL(route.request().url())
    const requestedCatalog = {
      ...catalog,
      page: Number(url.searchParams.get('page')),
      sort: url.searchParams.get('sort'),
      type: url.searchParams.get('type'),
    }
    if (url.searchParams.get('enrich') === 'true') {
      await new Promise(resolve => setTimeout(resolve, 1_500))
      await route.fulfill({ json: requestedCatalog })
      return
    }
    await route.fulfill({
      json: {
        ...requestedCatalog,
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
  await page.route('**/api/v1/bgg/games/266192?*', route => route.fulfill({ json: {
    ...catalog.games[0],
    description: route.request().url().includes('translate=true') ? '在不同栖息地吸引鸟类并建立引擎。' : 'Attract birds to different habitats and build an engine.',
    imageUrl: 'https://example.test/wingspan-large.jpg', minimumAge: 10,
    designers: ['Elizabeth Hargrave'], publishers: ['Stonemaier Games'], editionImages: [],
    officialNameLocalized: route.request().url().includes('translate=true'),
    descriptionTranslated: route.request().url().includes('translate=true'),
    categoriesTranslated: route.request().url().includes('translate=true'),
    mechanicsTranslated: route.request().url().includes('translate=true'),
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
  await page.route('**/api/v1/documents/official-imports', route => {
    if (route.request().method() === 'POST') {
      journeyImported = true
      return route.fulfill({ status: 202, json: officialImportJob() })
    }
    return route.fulfill({ json: journeyImported ? [officialImportJob()] : [] })
  })
  await page.route('**/api/v1/documents/upload-teaching-handoffs', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/games', route => route.fulfill({ json: [{
    game: { id: 'game-1', name: '展翅翱翔' },
    editions: [{ id: 'edition-1', gameId: 'game-1', name: 'BGG 基础版', language: 'und', publicationYear: 2024 }],
    expansions: [],
  }] }))
  await page.route('**/api/v1/teaching-plans', route => route.fulfill({ json: journeyImported && !holdPreparation ? [{
    ...teachingPlan, createdAt: '2026-08-10T08:00:01Z',
  }] : [] }))
  await page.route('**/api/v1/model-configuration', route => route.fulfill({ json: {
    providers: [{ id: 'qwen', configured: true, visionCapable: true }],
    assignments: { teaching: 'qwen', visual: 'qwen' },
  } }))
  await page.route('**/api/v1/documents', route => route.fulfill({ json: [{
    document: {
      id: 'document-1', gameEditionId: 'edition-1', title: 'Wingspan Rulebook',
      officialSourceUrl: 'https://publisher.example/wingspan.pdf', officialCoverUrl: null, createdBy: 'player',
    },
    latestVersion: { id: 'version-1', originalFilename: 'wingspan.pdf', size: 4096, status: 'READY' },
  }] }))
  await page.route('**/api/v1/document-versions/version-1/progress/snapshot', route => route.fulfill({ json: {
    stage: 'READY', percentage: 100, processedPages: 12, totalPages: 12, complete: true,
  } }))
  await page.route('**/api/v1/document-versions/version-1/pages', route => route.fulfill({ json: [
    { pageNumber: 1, text: 'Setup', characterCount: 1200 },
    { pageNumber: 2, text: 'Goal', characterCount: 960 },
    { pageNumber: 7, text: 'Gain food, then activate brown powers.', characterCount: 1100 },
  ] }))
  await page.route('**/api/v1/assistant-runs/preparation-run-1', route => route.fulfill({
    json: assistantRun('preparation-run-1', holdPreparation ? 'LESSON_PLANNING' : 'COMPLETED', 1),
  }))
  await page.route('**/api/v1/document-versions/version-1/teaching-plans/latest', route => route.fulfill({ json: teachingPlan }))
  await page.route('**/api/v1/teaching-plans/plan-1', route => route.fulfill({ json: teachingPlan }))
  await page.route('**/api/v1/assistant-runs/latest?*', route => {
    const url = route.request().url()
    if (url.includes('mode=QUESTION_ANSWER')) return route.fulfill({ status: 404 })
    teachingPoll += 1
    const completed = teachingPoll >= 3
    return route.fulfill({ json: assistantRun('teaching-run-1', completed ? 'COMPLETED' : 'RUNNING', teachingPoll) })
  })
  await page.route('**/api/v1/assistant-runs/teaching-run-1', route => {
    teachingPoll += 1
    const completed = teachingPoll >= 3
    return route.fulfill({ json: assistantRun('teaching-run-1', completed ? 'COMPLETED' : 'RUNNING', teachingPoll) })
  })
  await page.route('**/api/v1/teaching-plans/plan-1/illustrated-lessons/latest', route => {
    lessonPoll += 1
    if (lessonPoll === 1) return route.fulfill({ status: 404 })
    return route.fulfill({ json: lessonPoll >= 3 ? completeLesson : draftLesson })
  })
  await page.route('**/api/v1/teaching-plans/plan-1/illustrated-lessons', route => route.fulfill({ status: 202, json: {
    assistantRunId: 'teaching-run-1', state: 'RUNNING', reused: true,
  } }))
  await page.route('**/api/v1/game-sessions', route => route.fulfill({ json: {
    id: 'session-1', gameId: 'game-1', editionId: 'edition-1', documentVersionId: 'version-1',
    expansionIds: [], playerCount: 1, roundNumber: 1, phase: '规则问答', activePlayer: null,
  } }))
  await page.route('**/api/v1/game-sessions/session-1', route => route.fulfill({ json: {
    id: 'session-1', gameId: 'game-1', editionId: 'edition-1', documentVersionId: 'version-1',
    expansionIds: [], playerCount: 1, roundNumber: 1, phase: '规则问答', activePlayer: null,
  } }))
  await page.route('**/api/v1/document-versions/version-1/answers/conversation?*', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/document-versions/version-1/answers', route => route.fulfill({ json: {
    assistantRunId: 'answer-run-1', answer: ruleAnswer, conversationTurnId: 'turn-1',
  } }))
  await page.route('**/api/v1/assistant-runs/answer-run-1', route => route.fulfill({ json: {
    run: { id: 'answer-run-1', subjectId: 'version-1', createdAt: new Date().toISOString() }, activities: [],
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

test('stops closed reader transport while the durable guide remains reopenable', async ({ page }) => {
  await mockPublicDiscovery(page, true)
  await page.goto('/discover')

  await page.getByLabel('和推荐 Agent 聊聊').fill('4 个人，90 分钟内，想要中等策略')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.getByText('支持 4 人游玩')).toBeVisible()
  await page.getByRole('button', { name: '选这款，找规则书' }).click()

  const journey = page.getByTestId('player-journey-surface')
  await expect(journey.getByText('Wingspan Rulebook')).toBeVisible()
  await journey.getByRole('button', { name: '选择这份' }).click()
  await journey.getByRole('checkbox', { name: /我确认该链接来自有权提供/ }).check()
  await journey.getByRole('button', { name: '下载规则书并生成讲解' }).click()
  await expect(journey.getByText('讲解已经完整生成并通过后台收尾。')).toBeVisible({ timeout: 8_000 })

  const failedReaderRequests: string[] = []
  let cancellationRequests = 0
  page.on('request', request => {
    if (request.url().includes('/cancellation')) cancellationRequests += 1
  })
  page.on('requestfailed', request => {
    const url = request.url()
    if (url.includes('/document-versions/version-1/pages')
      || url.includes('/teaching-plans/plan-1')
      || url.includes('/assistant-runs/latest') && url.includes('subjectId=plan-1')) {
      failedReaderRequests.push(url)
    }
  })

  let releasePages!: () => void
  const pagesGate = new Promise<void>(resolve => { releasePages = resolve })
  let pageRequests = 0
  let blockedPageHandlerSettled = false
  await page.route('**/api/v1/document-versions/version-1/pages', async route => {
    pageRequests += 1
    if (pageRequests === 1) {
      await pagesGate
      await route.fulfill({ json: [
        { pageNumber: 1, text: 'Setup', characterCount: 1200 },
        { pageNumber: 7, text: 'Turn order', characterCount: 1100 },
      ] }).catch(() => undefined)
      blockedPageHandlerSettled = true
      return
    }
    return route.fulfill({ json: [
      { pageNumber: 1, text: 'Setup', characterCount: 1200 },
      { pageNumber: 7, text: 'Turn order', characterCount: 1100 },
    ] })
  })

  await journey.getByRole('button', { name: '先阅读原规则书' }).click()
  let rulebook = page.getByRole('dialog', { name: '原规则书阅读器' })
  await expect(rulebook.getByText('正在打开规则书页面…')).toBeVisible()
  await rulebook.getByRole('button', { name: '关闭规则书' }).click()
  await expect(rulebook).toHaveCount(0)
  await expect.poll(() => failedReaderRequests.filter(url => url.includes('/document-versions/version-1/pages')).length).toBe(1)

  releasePages()
  await expect.poll(() => blockedPageHandlerSettled).toBe(true)
  await page.getByTestId('player-journey-progress-button').click()
  await journey.getByRole('button', { name: '先阅读原规则书' }).click()
  rulebook = page.getByRole('dialog', { name: '原规则书阅读器' })
  await expect(rulebook.getByRole('button', { name: /第 7 页/ })).toBeVisible()
  expect(pageRequests).toBe(2)
  await rulebook.getByRole('button', { name: '关闭规则书' }).click()

  type GuidePhase = 'initial-blocked' | 'fresh-active' | 'poll-blocked' | 'completed'
  let guidePhase: GuidePhase = 'initial-blocked'
  let releaseInitialGuide!: () => void
  const initialGuideGate = new Promise<void>(resolve => { releaseInitialGuide = resolve })
  let releaseGuidePoll!: () => void
  const guidePollGate = new Promise<void>(resolve => { releaseGuidePoll = resolve })
  let initialGuideStarted = 0
  let initialGuideSettled = 0
  let freshInitialReads = 0
  let pollReads = 0
  let pollSettled = 0
  let allGuideReads = 0

  const guideHandler = async (route: import('@playwright/test').Route) => {
    const url = new URL(route.request().url())
    const requestPhase = guidePhase
    allGuideReads += 1
    if (requestPhase === 'initial-blocked') {
      initialGuideStarted += 1
      await initialGuideGate
      await fulfillGuide(route, url, false).catch(() => undefined)
      initialGuideSettled += 1
      return
    }
    if (requestPhase === 'fresh-active') {
      freshInitialReads += 1
      await fulfillGuide(route, url, false)
      if (freshInitialReads === 3) guidePhase = 'poll-blocked'
      return
    }
    if (requestPhase === 'poll-blocked') {
      pollReads += 1
      await guidePollGate
      await fulfillGuide(route, url, false).catch(() => undefined)
      pollSettled += 1
      return
    }
    await fulfillGuide(route, url, true)
  }
  await page.route('**/api/v1/teaching-plans/plan-1', guideHandler)
  await page.route('**/api/v1/teaching-plans/plan-1/illustrated-lessons/latest', guideHandler)
  await page.route('**/api/v1/assistant-runs/latest?*', guideHandler)

  await page.getByTestId('player-journey-dock').click()
  let guide = page.getByRole('dialog', { name: '生成讲解阅读器' })
  await expect(guide.getByText('通过鸟类、奖励牌和蛋获得分数。')).toBeVisible()
  await expect(guide.getByText('正在打开已生成的讲解…')).toHaveCount(0)
  await expect.poll(() => initialGuideStarted).toBe(3)
  await guide.getByRole('button', { name: '关闭讲解' }).click()
  await expect(guide).toHaveCount(0)
  await expect.poll(() => failedReaderRequests.filter(isGuideRead).length).toBe(3)

  releaseInitialGuide()
  await expect.poll(() => initialGuideSettled).toBe(3)
  guidePhase = 'fresh-active'
  await page.getByTestId('player-journey-dock').click()
  guide = page.getByRole('dialog', { name: '生成讲解阅读器' })
  await expect(guide.getByText('通过鸟类、奖励牌和蛋获得分数。')).toBeVisible()
  await expect.poll(() => guidePhase).toBe('poll-blocked')
  await expect.poll(() => pollReads).toBe(2)

  await guide.getByRole('button', { name: '关闭讲解' }).click()
  await expect(guide).toHaveCount(0)
  await expect.poll(() => failedReaderRequests.filter(isGuideRead).length).toBe(5)
  releaseGuidePoll()
  await expect.poll(() => pollSettled).toBe(2)
  const readsAfterClose = allGuideReads
  await page.waitForTimeout(1_700)
  expect(allGuideReads).toBe(readsAfterClose)

  guidePhase = 'completed'
  await page.getByTestId('player-journey-dock').click()
  guide = page.getByRole('dialog', { name: '生成讲解阅读器' })
  await expect(guide.getByText('完整讲解已经生成。')).toBeVisible()
  await expect(guide.getByText('选择一个栖息地行动并依次结算。')).toBeVisible()
  expect(cancellationRequests).toBe(0)
})

test('keeps recommendation, rulebook reading, progressive teaching, and grounded Q&A in one recoverable workspace', async ({ page }) => {
  await mockPublicDiscovery(page, true)
  await page.goto('/discover')

  const recommendationComposer = page.getByLabel('和推荐 Agent 聊聊')
  await recommendationComposer.fill('4 个人，90 分钟内，想要中等策略；朋友聚会，希望热闹但不要尴尬')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.getByText('支持 4 人游玩')).toBeVisible()
  await recommendationComposer.fill('稍后还想继续找一款合作游戏')

  await page.getByRole('button', { name: '查看完整资料：展翅翱翔' }).click()
  const details = page.getByRole('dialog', { name: '桌游详细资料' })
  await expect(details.getByText('在不同栖息地吸引鸟类并建立引擎。')).toBeVisible()
  await expect(page).toHaveURL(/\/discover$/)
  await details.getByRole('button', { name: '选这款，继续找规则书' }).click()

  await expect(page.getByRole('heading', { name: '已选《展翅翱翔》' })).toBeVisible()
  const journeySurface = page.getByTestId('player-journey-surface')
  await expectOpaqueSurface(journeySurface)
  await expect(page.getByTestId('player-journey-backdrop')).toBeVisible()
  await expect(journeySurface.locator('[data-fact-confirmed="true"]')).toHaveCount(1)
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

  await expect(page.getByText('规则书已经可以阅读；讲解会继续在后台生成。')).toBeVisible({ timeout: 8_000 })
  await expect.poll(() => journeySurface.locator('[data-fact-confirmed="true"]').count()).toBeGreaterThanOrEqual(3)
  await page.getByRole('button', { name: '先阅读原规则书' }).click()
  const rulebook = page.getByRole('dialog', { name: '原规则书阅读器' })
  await expect(rulebook.getByText('你可以先阅读原规则书；讲解仍在后台生成')).toBeVisible()
  await expect(rulebook.getByRole('img', { name: '规则书第 1 页' })).toHaveAttribute('src', '/api/v1/document-versions/version-1/pages/1/image')
  await rulebook.getByRole('button', { name: /第 7 页/ }).click()
  await expect(rulebook.getByRole('img', { name: '规则书第 7 页' })).toHaveAttribute('src', '/api/v1/document-versions/version-1/pages/7/image')
  await page.waitForTimeout(1_500)
  await rulebook.getByRole('button', { name: '关闭规则书' }).click()

  const journeyDock = page.getByTestId('player-journey-dock')
  await expect(journeyDock).toContainText('讲解已经可以阅读', { timeout: 8_000 })
  await journeyDock.click()
  const lesson = page.getByRole('dialog', { name: '生成讲解阅读器' })
  await expectOpaqueSurface(page.getByTestId('recommendation-lesson-surface'))
  await expect(page.getByTestId('recommendation-lesson-backdrop')).toBeVisible()
  await expect(lesson.getByText('通过鸟类、奖励牌和蛋获得分数。')).toBeVisible()
  await expect(lesson.getByText('选择一个栖息地行动并依次结算。')).toBeVisible()
  await expect(page.getByTestId('player-journey-surface').locator('[data-fact-confirmed="true"]')).toHaveCount(5)
  await expect(page).toHaveURL(/\/discover$/)

  const sessionRequestPromise = page.waitForRequest(request =>
    request.url().endsWith('/api/v1/game-sessions') && request.method() === 'POST')
  await lesson.getByRole('button', { name: '切换到规则答疑' }).click()
  const sessionRequest = await sessionRequestPromise
  expect(sessionRequest.postDataJSON()).toMatchObject({
    editionId: 'edition-1', documentVersionId: 'version-1', expansionIds: [], playerCount: 1,
  })
  await expect(page.getByTestId('recommendation-answer-workspace')).toContainText('已绑定规则书，可以开始提问')

  const answerRequestPromise = page.waitForRequest(request =>
    request.url().endsWith('/api/v1/document-versions/version-1/answers') && request.method() === 'POST')
  await page.getByLabel('向规则书提问').fill('获得食物以后，什么时候发动棕色能力？')
  await page.getByRole('button', { name: '提交问题' }).click()
  const answerRequest = await answerRequestPromise
  expect(answerRequest.postDataJSON()).toMatchObject({
    question: '获得食物以后，什么时候发动棕色能力？',
    gameSessionId: 'session-1',
    language: 'zh-CN',
  })
  await expect(page.getByText('获得食物后，再发动该栖息地中从右到左的棕色能力。')).toBeVisible()
  await expect(page.getByText('获得食物', { exact: true })).toBeVisible()

  await page.getByTestId('agent-role-switcher').getByRole('button', { name: '继续推荐' }).click()
  await expect(recommendationComposer).toBeVisible()
  await expect(recommendationComposer).toHaveValue('稍后还想继续找一款合作游戏')
  await expect(page.getByText('支持 4 人游玩')).toBeVisible()
  await expect(page).toHaveURL(/\/discover$/)

  await page.goto('/lessons')
  await expect(page.getByRole('heading', { name: '展翅翱翔', exact: true })).toBeVisible()
  await expect(page.getByText('Wingspan Rulebook')).toHaveCount(0)
})

test('hands persisted recommendation work to global guides before the preparation run finishes', async ({ page }) => {
  let lessonLaunchRequests = 0
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().endsWith('/illustrated-lessons')) {
      lessonLaunchRequests += 1
    }
  })
  await mockPublicDiscovery(page, true, true)
  await page.goto('/discover')

  await page.getByLabel('和推荐 Agent 聊聊').fill('4 个人，90 分钟内，想要中等策略')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.getByText('支持 4 人游玩')).toBeVisible()
  await page.getByRole('button', { name: '选这款，找规则书' }).click()
  const journey = page.getByTestId('player-journey-surface')
  await journey.getByRole('button', { name: '选择这份' }).click()
  await journey.getByRole('checkbox', { name: /我确认该链接来自有权提供/ }).check()
  await journey.getByRole('button', { name: '下载规则书并生成讲解' }).click()

  const workTrigger = page.getByTestId('background-work-trigger-desktop')
  await expect(workTrigger.locator('span').filter({ hasText: '1' })).toBeVisible()
  await journey.getByRole('button', { name: '关闭小窗' }).click()
  await workTrigger.click()
  const workCenter = page.getByRole('dialog', { name: '后台任务' })
  await expect(workCenter.getByText('展翅翱翔')).toBeVisible()
  await expect(workCenter.getByText('正在读取规则并建立讲解结构')).toBeVisible()

  await workCenter.getByRole('link', { name: /打开讲解中心/ }).click()
  await expect(page).toHaveURL(/\/lessons$/)
  const pending = page.getByTestId('pending-guide-journey')
  await expect(pending.getByRole('heading', { name: '展翅翱翔' })).toBeVisible()
  await expect(pending.getByText('规则书已可用，正在建立讲解计划并启动逐章生成')).toBeVisible()
  expect(lessonLaunchRequests).toBe(0)
})

test('recovers persisted recommendation work after a full refresh without journey storage', async ({ page }) => {
  await mockPublicDiscovery(page, true, true)
  await page.goto('/discover')

  await page.getByLabel('和推荐 Agent 聊聊').fill('4 个人，90 分钟内，想要中等策略')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.getByText('支持 4 人游玩')).toBeVisible()
  await page.getByRole('button', { name: '选这款，找规则书' }).click()
  const journey = page.getByTestId('player-journey-surface')
  await journey.getByRole('button', { name: '选择这份' }).click()
  await journey.getByRole('checkbox', { name: /我确认该链接来自有权提供/ }).check()
  await journey.getByRole('button', { name: '下载规则书并生成讲解' }).click()
  await expect(page.getByTestId('background-work-trigger-desktop').locator('span').filter({ hasText: '1' })).toBeVisible()

  await page.evaluate(() => sessionStorage.clear())
  await page.reload()

  const workTrigger = page.getByTestId('background-work-trigger-desktop')
  await expect(workTrigger.locator('span').filter({ hasText: '1' })).toBeVisible()
  await workTrigger.click()
  const workCenter = page.getByRole('dialog', { name: '后台任务' })
  await expect(workCenter.getByText('展翅翱翔')).toBeVisible()
  await expect(workCenter.getByText('正在读取规则并建立讲解结构')).toBeVisible()
  await workCenter.getByRole('link', { name: /打开讲解中心/ }).click()

  await expect(page).toHaveURL(/\/lessons$/)
  const pending = page.getByTestId('pending-guide-journey')
  await expect(pending.getByRole('heading', { name: '展翅翱翔' })).toBeVisible()
  await expect(pending.getByText('规则书已可用，正在建立讲解计划并启动逐章生成')).toBeVisible()
})

test('keeps the readable-guide continuation legible and focus-safe at 320 and 390 px', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 844 })
  await mockPublicDiscovery(page, true)
  await page.goto('/discover')

  await page.getByLabel('和推荐 Agent 聊聊').fill('4 个人，90 分钟内，想要中等策略')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.getByText('支持 4 人游玩')).toBeVisible()
  await page.getByRole('button', { name: '选这款，找规则书' }).click()
  const journey = page.getByTestId('player-journey-surface')
  await journey.getByRole('button', { name: '选择这份' }).click()
  await journey.getByRole('checkbox', { name: /我确认该链接来自有权提供/ }).check()
  await journey.getByRole('button', { name: '下载规则书并生成讲解' }).click()
  await journey.getByRole('button', { name: '关闭小窗' }).click()

  const continuation = page.getByTestId('player-journey-continuation')
  const readGuide = page.getByTestId('player-journey-dock')
  const viewProgress = page.getByTestId('player-journey-progress-button')
  await expect(readGuide).toContainText('讲解已经可以阅读', { timeout: 8_000 })
  await expect(readGuide).toContainText('打开讲解')
  await expect(viewProgress).toHaveText('查看进度')

  for (const width of [320, 390]) {
    await page.setViewportSize({ width, height: 844 })
    const [containerBox, readBox, progressBox, overflow] = await Promise.all([
      continuation.boundingBox(),
      readGuide.boundingBox(),
      viewProgress.boundingBox(),
      page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth),
    ])
    expect(containerBox).not.toBeNull()
    expect(readBox).not.toBeNull()
    expect(progressBox).not.toBeNull()
    expect(overflow).toBe(false)
    expect(readBox!.height).toBeGreaterThanOrEqual(44)
    expect(progressBox!.height).toBeGreaterThanOrEqual(44)
    expect(Math.abs(readBox!.x - containerBox!.x)).toBeLessThanOrEqual(1)
    expect(Math.abs(progressBox!.x - containerBox!.x)).toBeLessThanOrEqual(1)
    expect(Math.abs(readBox!.width - containerBox!.width)).toBeLessThanOrEqual(2)
    expect(Math.abs(progressBox!.width - containerBox!.width)).toBeLessThanOrEqual(2)
    expect(progressBox!.y).toBeGreaterThanOrEqual(readBox!.y + readBox!.height - 1)
  }

  await readGuide.click()
  const lesson = page.getByRole('dialog', { name: '生成讲解阅读器' })
  await expect(lesson).toBeVisible()
  await lesson.getByRole('button', { name: '关闭讲解' }).click()
  await expect(readGuide).toBeFocused()

  await viewProgress.click()
  await expect(page.getByTestId('player-journey-backdrop')).toBeVisible()
})

async function expectOpaqueSurface(locator: import('@playwright/test').Locator) {
  await expect(locator).toBeVisible()
  const appearance = await locator.evaluate((element) => {
    const style = getComputedStyle(element)
    const match = style.backgroundColor.match(/^rgba?\(([^)]+)\)$/)
    const channels = match?.[1]?.split(',').map(value => Number(value.trim())) ?? []
    const alpha = channels.length >= 4 ? channels[3]! : 1
    const bounds = element.getBoundingClientRect()
    return {
      alpha,
      opacity: Number(style.opacity),
      width: bounds.width,
      height: bounds.height,
    }
  })
  expect(appearance.alpha).toBe(1)
  expect(appearance.opacity).toBe(1)
  expect(appearance.width).toBeGreaterThan(0)
  expect(appearance.height).toBeGreaterThan(0)
}

function isGuideRead(url: string) {
  return url.includes('/teaching-plans/plan-1')
    || url.includes('/assistant-runs/latest') && url.includes('subjectId=plan-1')
}

function fulfillGuide(route: import('@playwright/test').Route, url: URL, completed: boolean) {
  if (url.pathname === '/api/v1/teaching-plans/plan-1') return route.fulfill({ json: teachingPlan })
  if (url.pathname.endsWith('/illustrated-lessons/latest')) return route.fulfill({ json: {
    ...(completed ? completeLesson : draftLesson),
    teachingPlanId: 'plan-1',
  } })
  const progress = assistantRun('teaching-run-1', completed ? 'COMPLETED' : 'RUNNING', completed ? 4 : 2)
  return route.fulfill({ json: progress })
}
