import { expect, test, type Page } from '@playwright/test'

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

async function expectWingspanRecommendationReady(page: Page) {
  const turn = page.getByTestId('assistant-recommendation-turn').last()
  await expect(turn.getByRole('heading', { level: 3, name: '展翅翱翔', exact: true })).toBeVisible()
  await expect(turn.getByRole('button', { name: '选这款，找规则书' })).toBeVisible()
}

function answerStreamResult(result: unknown) {
  return {
    contentType: 'text/event-stream',
    body: `event: result\ndata: ${JSON.stringify(result)}\n\n`,
  }
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

const recommendationReply = '我会先推荐《展翅翱翔》。它支持 4 人，标示时长约 70 分钟，复杂度 2.5，符合你的人数和时间要求。\n\n它是否符合你想要的热闹气氛，还需要核对真实互动体验；单凭这些资料，我不会把它说成聚会游戏。'
const similarGameReply = '你说的是 Mosaic Field。按你前面提到的机制相近，我会推荐 Glass Orchard：它同样有轮抽和图案构筑，能保留挑选与布局的乐趣。\n\n它使用骰子，这一点与参考游戏有所不同；如果你最喜欢的是对布局的控制感，我们还可以继续比较这一点。'

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
  const preparation = id.startsWith('preparation-run-')
  const activities = preparation
    ? [{
        sequence: 1, type: 'MODEL', operation: 'organizeTeachingOutline', summary: 'internal outline telemetry',
        outcome: state === 'COMPLETED' ? 'SUCCEEDED' : state === 'FAILED' ? 'FAILED' : 'RUNNING',
        latencyMs: 12, occurredAt: updatedAt,
      }]
    : state === 'COMPLETED'
      ? [
          {
            sequence: 1, type: 'TOOL', operation: 'readTeachingSourcePages|1', summary: 'internal page-read telemetry',
            outcome: 'SUCCEEDED', latencyMs: 8, occurredAt: updatedAt,
          },
          {
            sequence: 2, type: 'MODEL', operation: 'composeTeachingSection|1', summary: 'internal model telemetry',
            outcome: 'SUCCEEDED', latencyMs: 12, occurredAt: updatedAt,
          },
          {
            sequence: 3, type: 'VALIDATION', operation: 'validateTeachingSection|1|0', summary: 'internal validation telemetry',
            outcome: 'SUCCEEDED', latencyMs: 1, occurredAt: updatedAt,
          },
          {
            sequence: 4, type: 'VALIDATION', operation: 'publishTeachingSection|1', summary: 'CITED_BASE_SECTION_PUBLISHED',
            outcome: 'SUCCEEDED', latencyMs: 1, occurredAt: updatedAt,
          },
        ]
      : [
          {
            sequence: 1, type: 'TOOL', operation: 'readTeachingSourcePages|1', summary: 'internal page-read telemetry',
            outcome: 'SUCCEEDED', latencyMs: 8, occurredAt: updatedAt,
          },
          {
            sequence: 2, type: 'MODEL', operation: 'composeTeachingSection|1', summary: 'internal model telemetry',
            outcome: 'RUNNING', latencyMs: 12, occurredAt: updatedAt,
          },
        ]
  return {
    run: {
      id, state, revision, mode: preparation ? 'TEACHING_PREPARATION' : 'TEACHING',
      subjectId: preparation ? 'version-1' : 'plan-1', ownerUsername: 'player',
      createdAt: '2026-08-10T08:00:00Z', updatedAt,
      completedAt: state === 'COMPLETED' ? updatedAt : null, lastErrorCode: null,
    },
    budget: { usedModelCalls: revision, maxModelCalls: 12 },
    activities,
  }
}

function officialImportJob(preparationRunId = 'preparation-run-1') {
  const updatedAt = new Date().toISOString()
  return {
    id: 'import-job-1', title: '展翅翱翔', rulebookTitle: 'Wingspan Rulebook',
    sourceDomain: 'publisher.example', stage: 'COMPLETED',
    downloadedBytes: 4096, totalBytes: 4096, documentVersionId: 'version-1',
    duplicate: false, errorCode: null, reused: false,
    teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: preparationRunId, teachingErrorCode: null,
    downloadCompletedAt: updatedAt, importCompletedAt: updatedAt, teachingHandoffUpdatedAt: updatedAt, updatedAt,
  }
}

const ruleAnswer = {
  language: 'zh-CN',
  status: 'ANSWERED',
  shortVerdict: '获得食物后，再发动该栖息地中从右到左的棕色能力。',
  explanation: '规则书把获得食物写在发动棕色能力之前，因此按这个顺序结算。',
  citations: [{
    heading: '获得食物',
    excerpt: '获得食物后，依次发动栖息地中的棕色能力。', pageFrom: 7, pageTo: 7,
  }],
  exceptions: [], confidence: 'HIGH', answerBasis: 'DIRECT_RULE', source: 'OFFICIAL',
  clarification: null, recovery: null, warnings: [],
}

async function mockPublicDiscovery(
  page: import('@playwright/test').Page,
  authenticated = false,
  holdPreparation = false,
  failPreparation = false,
  streamDocumentProgress = false,
) {
  let teachingPoll = 0
  let lessonPoll = 0
  let planReads = 0
  let journeyImported = false
  let planPublished = !holdPreparation
  let firstLessonPublished = !holdPreparation
  let preparationCompleted = !holdPreparation
  let preparationRetryAccepted = false
  let guideCompleted = false
  let importStarts = 0
  let preparationRetryRequests = 0
  let documentReady = !streamDocumentProgress
  let documentSnapshotReads = 0
  let importJobReads = 0
  let releaseDocumentProgress!: () => void
  const documentProgressGate = new Promise<void>(resolve => { releaseDocumentProgress = resolve })
  const currentImportJob = () => documentReady
    ? officialImportJob(preparationRetryAccepted ? 'preparation-run-retry' : 'preparation-run-1')
    : {
        ...officialImportJob(),
        teachingHandoffState: 'WAITING_FOR_DOCUMENT',
        teachingPreparationRunId: null,
      }
  await page.route('**/api/auth/session', route => authenticated
    ? route.fulfill({ json: { username: 'player', roles: ['USER'] } })
    : route.fulfill({ status: 401 }))
  await page.route('**/api/auth/csrf', route => route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'csrf' } }))
  await page.route('**/api/v1/assistant-runs/active?*', route => {
    const mode = new URL(route.request().url()).searchParams.get('mode')
    if (!journeyImported) return route.fulfill({ json: [] })
    if (mode === 'TEACHING_PREPARATION' && holdPreparation && !preparationCompleted) {
      return route.fulfill({ json: [assistantRun('preparation-run-1', 'LESSON_PLANNING', 1).run] })
    }
    if (mode === 'TEACHING' && !holdPreparation && !guideCompleted) {
      return route.fulfill({ json: [assistantRun('teaching-run-1', 'LESSON_COMPOSITION', 2).run] })
    }
    return route.fulfill({ json: [] })
  })
  await page.route('**/api/v1/bgg/recommendation-agent**', async route => {
    const request = route.request()
    const requestUrl = new URL(request.url())
    if (request.method() === 'GET' && requestUrl.pathname.endsWith('/recommendation-agent/session')) {
      await route.fulfill({ status: 204 })
      return
    }
    const body = request.postDataJSON() as {
      profile: {
        playerCount: unknown
        durationMinutes: unknown
        complexity: unknown
        type: string
        interaction: string
      }
      message: string
      focusedBggId: number | null
      transcript: { role: string; text: string }[]
    }
    const fulfill = async (payload: Record<string, unknown>) => {
      if (route.request().url().includes('/stream?')) {
        await route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          body: `event: progress\ndata: {"stage":"understanding_request","phase":"completed","action":"understand_request","elapsedMs":4,"decisionCycle":0,"modelCalls":0,"actionCalls":0,"catalogCalls":0,"webResearchCalls":0,"observedCandidates":0,"verifiedCandidates":0,"hardRejectedCandidates":0,"sourceCount":0}\n\nevent: progress\ndata: {"stage":"selecting_tools","phase":"started","action":"choose_next_action","elapsedMs":8,"decisionCycle":1,"modelCalls":1,"actionCalls":0,"catalogCalls":0,"webResearchCalls":0,"observedCandidates":0,"verifiedCandidates":0,"hardRejectedCandidates":0,"sourceCount":0}\n\nevent: result\ndata: ${JSON.stringify(payload)}\n\n`,
        })
        return
      }
      await route.fulfill({ json: payload })
    }
    if (body.message.includes('把这两款放在一起说')) {
      await fulfill({
        outcome: 'conversation', mode: 'model_assisted',
        assistantMessage: '如果今晚更在意控制时长，我会先选 Glass Orchard：它标示约 45 分钟，而《展翅翱翔》约 70 分钟。两者真实桌上互动目前都没有有出处的资料；如果互动感比时长更重要，这个选择就需要重新核对。',
        profile: { ...body.profile, type: 'all', interaction: 'any' }, clarification: null,
        sourceCount: 179737, candidatesEvaluated: 2,
        completedWork: ['lookup_bgg_games', 'compare_candidates'],
        games: [],
        comparison: {
          candidates: [
            { game: catalog.games[0], fitClaims: [] },
            { game: similarToMosaicField, fitClaims: [] },
          ],
          axes: [{
            subject: 'durationMinutes', label: '标示时长', capability: 'structured_metadata',
            cells: [
              { bggId: 266192, status: 'observed', observationKind: 'structured_metadata', value: '70 分钟' },
              { bggId: 600061, status: 'observed', observationKind: 'structured_metadata', value: '45 分钟' },
            ],
          }],
        },
      })
      return
    }
    if (body.message.includes('马赛克花园')) {
      await fulfill({
        outcome: 'needs_clarification', mode: 'model_assisted',
        assistantMessage: '我明白你想找一款机制相近的游戏。“马赛克花园”可能是译名或口头叫法，你知道它的原文名吗？只说名字就行，我会接着上一句查。',
        profile: { ...body.profile, type: 'all', interaction: 'any' },
        clarification: { field: 'conversation', prompt: '它的原文名是什么？', options: [] },
        sourceCount: 179737, candidatesEvaluated: 0,
        userModel: { summary: '想找与“马赛克花园”机制相近的游戏。', hypotheses: [] },
        completedWork: ['resolve_bgg_game'],
        games: [],
      })
      return
    }
    if (body.message.trim().toLowerCase() === 'mosaic field'
      && body.transcript.some(message => message.role === 'user' && message.text.includes('马赛克花园'))) {
      await fulfill({
        outcome: 'recommendations', mode: 'model_assisted',
        assistantMessage: similarGameReply,
        profile: { ...body.profile, type: 'all', interaction: 'any' }, clarification: null,
        sourceCount: 179737, candidatesEvaluated: 6,
        userModel: { summary: '以 Mosaic Field 为参照，寻找机制相近的游戏。', hypotheses: [] },
        completedWork: ['resolve_bgg_game', 'inspect_candidate_titles', 'lookup_bgg_games', 'recommend_games'],
        games: [{
          game: similarToMosaicField,
          fitClaims: [],
          replyParts: [],
        }],
      })
      return
    }
    if (body.focusedBggId === 266192) {
      await fulfill({
        outcome: 'recommendations', mode: 'model_assisted', assistantMessage: '我补查了教学资料：发行商提供了分步教学流程，可以按步骤了解这款游戏。\n\n至于你关心的实际桌上节奏，这份资料还不足以判断全桌是否能持续参与，我会把这一点保留为待核实的问题。',
        profile: { ...body.profile, type: 'all', interaction: 'any' }, clarification: null,
        sourceCount: 179737, candidatesEvaluated: 1,
        userModel: { summary: '朋友聚会，可能重视参与感', hypotheses: [{ text: '可能不喜欢等待太久', confidence: 'medium', basedOn: '想热闹一点' }] },
        researchSources: [{ index: 1, title: 'Publisher guide', url: 'https://publisher.example/wingspan', domain: 'publisher.example' }],
        completedWork: ['lookup_bgg_games', 'research_game_fit', 'recommend_games'],
        games: [{
          game: catalog.games[0],
          fitClaims: [],
          replyParts: [],
        }],
      })
      return
    }
    await fulfill({
      outcome: 'recommendations', mode: 'model_assisted', assistantMessage: recommendationReply,
      profile: {
        type: 'all', interaction: 'any',
        playerCount: { minimum: 4, maximum: 4, strength: 'hard', sourceText: '4 个人', confirmedTurn: 1 },
        durationMinutes: { minimum: null, maximum: 90, strength: 'hard', sourceText: '90 分钟内', confirmedTurn: 1 },
        complexity: { minimum: null, maximum: 3.2, strength: 'soft', sourceText: '中等策略', confirmedTurn: 1 },
      }, clarification: null,
      sourceCount: 179737, candidatesEvaluated: 20,
      completedWork: ['browse_bgg_catalog', 'lookup_bgg_games', 'recommend_games'],
      games: [{
        game: catalog.games[0],
        fitClaims: [],
        replyParts: [],
      }],
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
    await route.fulfill({ json: requestedCatalog })
  })
  await page.route('**/api/v1/bgg/games/266192/import', route => route.fulfill({ json: {
    game: { id: 'game-1', name: '展翅翱翔' },
    edition: { id: 'edition-1', name: 'BGG 基础版', language: 'und' },
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
    identity: {
      editionId: 'edition-1', gameName: '展翅翱翔', editionName: 'BGG 基础版', language: 'und',
    },
    candidates: [{
      title: 'Wingspan Rulebook', url: 'https://publisher.example/wingspan.pdf',
      publisher: 'Stonemaier Games', language: 'en', languageVerified: true, edition: 'Base game',
      sourceDomain: 'publisher.example', officialDomainVerified: true,
      sourceType: 'PUBLISHER', acquisitionMode: 'DIRECT_PDF',
      capability: 'DIRECT_DOCUMENT', capabilityEvidence: ['DOCUMENT_RESPONSE_CONFIRMED'],
      capabilityCheckedAt: '2026-08-15T12:00:00Z', nextAction: 'IMPORT_DOCUMENT',
    }],
  } }))
  await page.route('**/api/v1/documents/official-imports', route => {
    if (route.request().method() === 'POST') {
      importStarts += 1
      journeyImported = true
      return route.fulfill({ status: 202, json: currentImportJob() })
    }
    return route.fulfill({ json: journeyImported
      ? [currentImportJob()]
      : [] })
  })
  await page.route('**/api/v1/documents/official-imports?*', route => route.fulfill({
    json: journeyImported ? [currentImportJob()] : [],
  }))
  await page.route('**/api/v1/documents/official-imports/import-job-1', route => {
    importJobReads += 1
    return route.fulfill({ json: currentImportJob() })
  })
  await page.route('**/api/v1/documents/official-imports/import-job-1/teaching-retry', route => {
    preparationRetryRequests += 1
    preparationRetryAccepted = true
    return route.fulfill({ status: 202, json: officialImportJob('preparation-run-retry') })
  })
  await page.route('**/api/v1/documents/upload-teaching-handoffs', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/games', route => route.fulfill({ json: [{
    game: { id: 'game-1', name: '展翅翱翔' },
    editions: [{ id: 'edition-1', gameId: 'game-1', name: 'BGG 基础版', language: 'und', publicationYear: 2024 }],
    expansions: [],
  }] }))
  await page.route('**/api/v1/teaching-plans', route => {
    planReads += 1
    return route.fulfill({ json: journeyImported && planPublished ? [{
      ...teachingPlan, createdAt: '2026-08-10T08:00:01Z',
      lesson: firstLessonPublished ? lessonProgress(guideCompleted ? completeLesson : draftLesson) : null,
    }] : [] })
  })
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
  await page.route('**/api/v1/document-versions/version-1/progress/snapshot', route => {
    documentSnapshotReads += 1
    return route.fulfill({ json: documentReady
      ? { stage: 'READY', percentage: 100, processedPages: 12, totalPages: 12, complete: true }
      : { stage: 'RENDERING', percentage: 55, processedPages: 4, totalPages: 12, complete: false } })
  })
  if (streamDocumentProgress) {
    await page.route('**/api/v1/document-versions/version-1/progress', async route => {
      await documentProgressGate
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: 'event: progress\ndata: {"stage":"READY","percentage":100,"processedPages":12,"totalPages":12,"complete":true}\n\n',
      })
    })
  }
  await page.route('**/api/v1/document-versions/version-1/pages/summaries', route => route.fulfill({ json: [
    { pageNumber: 1, characterCount: 1200 },
    { pageNumber: 2, characterCount: 960 },
    { pageNumber: 7, characterCount: 1100 },
  ] }))
  await page.route('**/api/v1/document-versions/version-1/pages/*/image', route => route.fulfill({
    status: 200,
    contentType: 'image/svg+xml',
    body: '<svg xmlns="http://www.w3.org/2000/svg" width="800" height="1100"/>',
  }))
  await page.route('**/api/v1/assistant-runs/preparation-run-1', route => {
    const snapshot = assistantRun(
      'preparation-run-1',
      failPreparation ? 'FAILED' : preparationCompleted ? 'COMPLETED' : 'LESSON_PLANNING',
      1,
    )
    if (failPreparation) snapshot.run.lastErrorCode = 'TEACHING_PREPARATION_FAILED'
    return route.fulfill({ json: snapshot })
  })
  await page.route('**/api/v1/assistant-runs/preparation-run-retry', route => route.fulfill({
    json: assistantRun('preparation-run-retry', 'COMPLETED', 2),
  }))
  await page.route('**/api/v1/document-versions/version-1/teaching-plans/latest', route => route.fulfill({ json: teachingPlan }))
  await page.route('**/api/v1/teaching-plans/plan-1', route => route.fulfill({ json: teachingPlan }))
  await page.route('**/api/v1/assistant-runs/latest?*', route => {
    const url = route.request().url()
    if (url.includes('mode=QUESTION_ANSWER')) return route.fulfill({ status: 404 })
    if (!firstLessonPublished) return route.fulfill({ status: 404 })
    teachingPoll += 1
    const completed = guideCompleted || (!holdPreparation && teachingPoll >= 3)
    return route.fulfill({ json: assistantRun('teaching-run-1', completed ? 'COMPLETED' : 'LESSON_COMPOSITION', teachingPoll) })
  })
  await page.route('**/api/v1/assistant-runs/teaching-run-1', route => {
    teachingPoll += 1
    const completed = guideCompleted || (!holdPreparation && teachingPoll >= 3)
    return route.fulfill({ json: assistantRun('teaching-run-1', completed ? 'COMPLETED' : 'LESSON_COMPOSITION', teachingPoll) })
  })
  await page.route('**/api/v1/teaching-plans/plan-1/illustrated-lessons/latest', route => {
    if (!firstLessonPublished) return route.fulfill({ status: 404 })
    if (guideCompleted) return route.fulfill({ json: completeLesson })
    lessonPoll += 1
    if (lessonPoll === 1) return route.fulfill({ status: 404 })
    return route.fulfill({ json: lessonPoll >= 3 ? completeLesson : draftLesson })
  })
  await page.route('**/api/v1/teaching-plans/plan-1/illustrated-lessons/latest/summary', route => {
    if (!firstLessonPublished) return route.fulfill({ status: 404 })
    if (guideCompleted) return route.fulfill({ json: lessonProgress(completeLesson) })
    lessonPoll += 1
    if (lessonPoll === 1) return route.fulfill({ status: 404 })
    return route.fulfill({ json: lessonProgress(lessonPoll >= 3 ? completeLesson : draftLesson) })
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
  await page.route('**/api/v1/document-versions/version-1/answers/stream', route => route.fulfill(answerStreamResult({
    answer: ruleAnswer, conversationTurnId: 'turn-1', rulingReference: {
      citationIds: ['answer-chunk-1'], confirmedRulingId: null, confirmedRulingVersion: null,
    },
  })))
  return {
    publishPlan: () => { planPublished = true },
    publishFirstLesson: () => { firstLessonPublished = true },
    completePreparation: () => { preparationCompleted = true },
    completeGuide: () => { guideCompleted = true },
    planReads: () => planReads,
    importStarts: () => importStarts,
    importJobReads: () => importJobReads,
    documentSnapshotReads: () => documentSnapshotReads,
    publishDocumentReady: () => {
      documentReady = true
      releaseDocumentProgress()
    },
    preparationRetryRequests: () => preparationRetryRequests,
  }
}

function lessonProgress(lesson: typeof draftLesson | typeof completeLesson) {
  return {
    id: lesson.id,
    teachingPlanId: lesson.teachingPlanId,
    status: lesson.status,
    sections: lesson.sections.map(section => ({ evidenceStatus: section.evidenceStatus })),
  }
}

test('keeps full-catalog browsing separate from the conversational recommendation journey', async ({ page }) => {
  const catalogRequests: string[] = []
  page.on('request', request => {
    if (request.url().includes('/api/v1/bgg/catalog?')) catalogRequests.push(request.url())
  })
  await mockPublicDiscovery(page, true)
  await page.goto('/discover/catalog')

  await expect(page.getByRole('heading', { level: 1 })).toContainText('桌游目录')
  await expect(page.getByText('BGG 收录 162,686 条')).toBeVisible()
  await expect(page.locator('#game-catalog').getByRole('heading', { level: 3, name: '展翅翱翔' })).toBeVisible()
  await expect(page.locator('#game-catalog').getByText('Wingspan')).toBeVisible()
  await expect(page.locator('#game-catalog').getByText('卡牌轮抽')).toHaveCount(0)
  expect(catalogRequests.some(url => url.includes('enrich=true'))).toBe(false)
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
  await expect(page.getByText('第 1 / 378 页')).toBeVisible()
  await expect(page.getByRole('button', { name: '前往第 2 页' })).toBeVisible()

  await page.getByRole('link', { name: /让推荐助手帮我挑/ }).click()
  await expect(page).toHaveURL('/discover')
  await expect(page.getByRole('heading', { level: 1 })).toContainText('找桌游')
  await expect(page.locator('#game-catalog')).toHaveCount(0)

  const firstAgentRequest = page.waitForRequest(request => request.url().includes('/api/v1/bgg/recommendation-agent')
    && request.headers()['x-csrf-token'] === 'csrf')
  const composer = page.getByLabel('聊聊你想玩的游戏')
  await composer.fill('4 个人，90 分钟内，想要中等策略；朋友聚会，希望热闹但不要尴尬')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await firstAgentRequest
  await expectWingspanRecommendationReady(page)
  const firstRecommendation = page.getByTestId('assistant-recommendation-turn').last()
  await expect(firstRecommendation.getByTestId('assistant-recommendation-message')).toHaveText(recommendationReply)
  await expect(firstRecommendation.getByTestId('recommendation-game-card').getByText(/为什么选它|需要留意|没有形成可安全发布/)).toHaveCount(0)
  await firstRecommendation.getByText('资料与核对记录', { exact: true }).click()
  await expect(firstRecommendation.locator('.recommendation-source-summary')).toContainText('20')

  const focusedRequest = page.waitForRequest(request => {
    if (!request.url().includes('/api/v1/bgg/recommendation-agent')) return false
    return (request.postDataJSON() as { focusedBggId?: number }).focusedBggId === 266192
  })
  await page.getByRole('button', { name: '介绍一下' }).click()
  await focusedRequest
  const focusedRecommendation = page.getByTestId('assistant-recommendation-turn').last()
  await expect(focusedRecommendation.getByTestId('assistant-recommendation-message')).toContainText('我补查了教学资料')
  await focusedRecommendation.getByText('资料与核对记录', { exact: true }).click()
  await expect(page.getByRole('link', { name: /Publisher guide/ })).toHaveAttribute('rel', /noopener/)
  await expect(page.getByText('目前记下的偏好')).toBeVisible()
})

test('restores the server conversation and unsent draft after sign-in and browser Back', async ({ page }) => {
  let authenticated = false
  let serverSession: Record<string, unknown> | null = null
  await mockPublicDiscovery(page)
  await page.route('**/api/auth/session', route => authenticated
    ? route.fulfill({ json: { username: 'player', roles: ['USER'] } })
    : route.fulfill({ status: 401 }))
  await page.route('**/api/auth/login', route => {
    authenticated = true
    return route.fulfill({ status: 204 })
  })
  await page.route('**/api/v1/bgg/recommendation-agent/session', route => serverSession
    ? route.fulfill({ json: serverSession })
    : route.fulfill({ status: 204 }))
  await page.route('**/api/v1/bgg/recommendation-agent/stream?*', async route => {
    const request = route.request().postDataJSON() as {
      clientTurnId: string
      message: string
    }
    const conversationId = 'f0b0d56b-50aa-4e4c-a720-935c13ecda7c'
    const profile = {
      type: 'strategy', interaction: 'any',
      playerCount: { minimum: 3, maximum: 4, strength: 'hard', sourceText: '3–4 人', confirmedTurn: 1 },
      durationMinutes: { minimum: 120, maximum: 180, strength: 'hard', sourceText: '120–180 分钟', confirmedTurn: 1 },
      complexity: null,
    }
    const latestResponse = {
      conversationId, revision: 1, clientTurnId: request.clientTurnId, replayed: false, responseLocale: 'zh-CN',
      outcome: 'recommendations', mode: 'model_assisted', assistantMessage: '服务端保存了完整对话和候选。',
      profile, clarification: null, sourceCount: 179737, candidatesEvaluated: 1,
      games: [{
        game: catalog.games[0],
        fitClaims: [
          { subject: 'playerCount', strength: 'hard', relation: 'satisfied', text: '支持 3–4 人' },
          { subject: 'durationMinutes', strength: 'hard', relation: 'satisfied', text: '符合 120–180 分钟区间' },
        ],
        replyParts: [],
      }],
    }
    serverSession = {
      conversationId,
      revision: 1,
      profile,
      transcript: [
        { role: 'user', text: request.message },
        { role: 'assistant', text: latestResponse.assistantMessage },
      ],
      knownGames: [{ bggId: 266192, name: '展翅翱翔', originalName: 'Wingspan' }],
      shownBggIds: [266192],
      processing: false,
      processingSince: null,
      latestResponse: { ...latestResponse, replayed: true },
    }
    await route.fulfill({
      contentType: 'text/event-stream',
      body: `event: result\ndata: ${JSON.stringify(latestResponse)}\n\n`,
    })
  })

  await page.goto('/discover')
  const composer = page.getByLabel('聊聊你想玩的游戏')
  await composer.fill('登录前写好的 3–4 人条件')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await page.getByRole('link', { name: '登录并继续' }).click()
  await expect(page).toHaveURL('/login?redirect=/discover')
  await page.getByLabel('用户名').fill('Player')
  await page.getByLabel('密码').fill('correct horse battery staple')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL('/discover')
  await expect(composer).toHaveValue('登录前写好的 3–4 人条件')

  await composer.fill('想找 3–4 人、120–180 分钟的策略游戏')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.getByText('服务端保存了完整对话和候选。')).toBeVisible()
  await expectWingspanRecommendationReady(page)
  await composer.fill('这句草稿尚未发送')
  await page.getByRole('link', { name: /浏览目录/ }).click()
  await expect(page).toHaveURL('/discover/catalog')
  await page.evaluate(() => {
    for (const key of Object.keys(sessionStorage)) {
      if (key.startsWith('rulepilot:recommendation-conversation:')) sessionStorage.removeItem(key)
    }
  })

  await page.goBack()
  await expect(page).toHaveURL('/discover')
  await expect(page.getByText('想找 3–4 人、120–180 分钟的策略游戏')).toHaveCount(1)
  await expect(page.getByText('服务端保存了完整对话和候选。')).toHaveCount(1)
  await expectWingspanRecommendationReady(page)
  await expect(page.getByText('3–4 人', { exact: true })).toBeVisible()
  await expect(page.getByText('120–180 分钟', { exact: true })).toBeVisible()
  await expect(page.getByLabel('聊聊你想玩的游戏')).toHaveValue('这句草稿尚未发送')
})

test('keeps the recommendation workspace visibly alive while the server is still working', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockPublicDiscovery(page, true)
  let releaseResponse!: () => void
  const responseGate = new Promise<void>(resolve => { releaseResponse = resolve })
  await page.route('**/api/v1/bgg/recommendation-agent/stream?*', async route => {
    await responseGate
    await route.fulfill({
      contentType: 'text/event-stream',
      body: `event: result\ndata: ${JSON.stringify({
        outcome: 'conversation', mode: 'model_assisted', responseLocale: 'zh-CN',
        assistantMessage: '已经接着你的条件继续处理。',
        profile: { playerCount: null, durationMinutes: null, complexity: null, type: 'all', interaction: 'any' },
        clarification: null, sourceCount: 0, candidatesEvaluated: 0, games: [],
      })}\n\n`,
    })
  })

  await page.goto('/discover')
  await page.getByLabel('聊聊你想玩的游戏').fill('四个人，想玩一小时内的轻松互动游戏')
  await page.getByRole('button', { name: '发送', exact: true }).click()

  const liveWork = page.getByTestId('recommendation-live-work')
  await expect(liveWork).toBeVisible()
  await expect(page.getByTestId('player-work-status')).toContainText('正在回复')
  await expect(page.getByTestId('recommendation-elapsed')).toContainText('已用 1 秒', { timeout: 3_000 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)

  releaseResponse()
  await expect(page.getByText('已经接着你的条件继续处理。')).toBeVisible()
  await expect(liveWork).toHaveCount(0)
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

test('shows streamed rulebook readiness without waiting for the next recommendation poll', async ({ page }) => {
  const progress = await mockPublicDiscovery(page, true, true, false, true)
  await page.goto('/discover')

  await page.getByLabel('聊聊你想玩的游戏').fill('4 个人，90 分钟内，想要中等策略')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await page.getByRole('button', { name: '选这款，找规则书' }).click()
  const journey = page.getByTestId('player-journey-surface')
  await journey.getByRole('button', { name: '选择这份' }).click()
  await journey.getByRole('checkbox', { name: /我已比较以上游戏、版本和语言/ }).check()
  await journey.getByRole('checkbox', { name: /确认该链接来自有权提供/ }).check()
  await journey.getByRole('button', { name: '下载规则书并生成讲解' }).click()

  await expect(journey.getByText('第 4 / 12 页')).toBeVisible()
  expect(progress.importJobReads()).toBe(1)
  expect(progress.documentSnapshotReads()).toBe(1)

  progress.publishDocumentReady()
  await expect(journey.getByText('规则书已经可以阅读；讲解会继续在后台生成。')).toBeVisible()
  await expect.poll(() => progress.importJobReads()).toBe(2)
  expect(progress.documentSnapshotReads()).toBe(1)
})

test('keeps a corrected reference title in conversational context on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockPublicDiscovery(page, true)
  await page.goto('/discover')

  const composer = page.getByLabel('聊聊你想玩的游戏')
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
  const reply = recommendationTurn.getByTestId('assistant-recommendation-message')
  await expect(reply).toBeVisible()
  await expect(reply).toHaveText(similarGameReply)
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
  const progress = await mockPublicDiscovery(page, true)
  await page.goto('/discover')

  await page.getByLabel('聊聊你想玩的游戏').fill('4 个人，90 分钟内，想要中等策略')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expectWingspanRecommendationReady(page)
  await page.getByRole('button', { name: '选这款，找规则书' }).click()

  const journey = page.getByTestId('player-journey-surface')
  await expect(journey.getByText('Wingspan Rulebook')).toBeVisible()
  await journey.getByRole('button', { name: '选择这份' }).click()
  await journey.getByRole('checkbox', { name: /我已比较以上游戏、版本和语言/ }).check()
  await journey.getByRole('checkbox', { name: /确认该链接来自有权提供/ }).check()
  await journey.getByRole('button', { name: '下载规则书并生成讲解' }).click()
  progress.completeGuide()
  await expect(journey.getByText('讲解已经完整生成并通过后台收尾。')).toBeVisible({ timeout: 8_000 })

  const failedReaderRequests: string[] = []
  let cancellationRequests = 0
  page.on('request', request => {
    if (request.url().includes('/cancellation')) cancellationRequests += 1
  })
  page.on('requestfailed', request => {
    const url = request.url()
    if (url.includes('/document-versions/version-1/pages/summaries')
      || url.includes('/teaching-plans/plan-1')
      || url.includes('/assistant-runs/latest') && url.includes('subjectId=plan-1')) {
      failedReaderRequests.push(url)
    }
  })

  let releasePages!: () => void
  const pagesGate = new Promise<void>(resolve => { releasePages = resolve })
  let pageRequests = 0
  let blockedPageHandlerSettled = false
  await page.route('**/api/v1/document-versions/version-1/pages/summaries', async route => {
    pageRequests += 1
    if (pageRequests === 1) {
      await pagesGate
      await route.fulfill({ json: [
        { pageNumber: 1, characterCount: 1200 },
        { pageNumber: 7, characterCount: 1100 },
      ] }).catch(() => undefined)
      blockedPageHandlerSettled = true
      return
    }
    return route.fulfill({ json: [
      { pageNumber: 1, characterCount: 1200 },
      { pageNumber: 7, characterCount: 1100 },
    ] })
  })

  await journey.getByRole('button', { name: '先阅读原规则书' }).click()
  let rulebook = page.getByRole('dialog', { name: '原规则书阅读器' })
  await expect(rulebook.getByText('正在打开规则书页面…')).toBeVisible()
  await rulebook.getByRole('button', { name: '关闭规则书' }).click()
  await expect(rulebook).toHaveCount(0)
  await expect.poll(() => failedReaderRequests.filter(url => url.includes('/document-versions/version-1/pages/summaries')).length).toBe(1)

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

test('hands persisted recommendation work to global guides before the preparation run finishes', async ({ page }) => {
  let lessonLaunchRequests = 0
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().endsWith('/illustrated-lessons')) {
      lessonLaunchRequests += 1
    }
  })
  await mockPublicDiscovery(page, true, true)
  await page.goto('/discover')

  await page.getByLabel('聊聊你想玩的游戏').fill('4 个人，90 分钟内，想要中等策略')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expectWingspanRecommendationReady(page)
  await page.getByRole('button', { name: '选这款，找规则书' }).click()
  const journey = page.getByTestId('player-journey-surface')
  await journey.getByRole('button', { name: '选择这份' }).click()
  await journey.getByRole('checkbox', { name: /我已比较以上游戏、版本和语言/ }).check()
  await journey.getByRole('checkbox', { name: /确认该链接来自有权提供/ }).check()
  await journey.getByRole('button', { name: '下载规则书并生成讲解' }).click()

  const generationSteps = journey.getByTestId('recommendation-teaching-generation-steps')
  await expect(generationSteps).toContainText('正在通读规则书，先形成整局认识再规划讲解章节')
  await expect(generationSteps).not.toContainText('organizeTeachingOutline')
  await expect(generationSteps).not.toContainText('internal outline telemetry')

  const workTrigger = page.getByTestId('background-work-trigger-desktop')
  await expect(workTrigger.locator('span').filter({ hasText: '1' })).toBeVisible()
  await journey.getByRole('button', { name: '关闭小窗' }).click()
  await workTrigger.click()
  const workCenter = page.getByRole('dialog', { name: '后台任务' })
  await expect(workCenter.getByText('展翅翱翔')).toBeVisible()
  await expect(workCenter.getByText('正在组织讲解')).toBeVisible()
  await expect(workCenter.getByText('建立讲解结构')).toHaveCount(0)

  await workCenter.getByRole('link', { name: /打开讲解中心/ }).click()
  await expect(page).toHaveURL(/\/lessons$/)
  const pending = page.getByTestId('pending-guide-journey')
  await expect(pending.getByRole('heading', { name: '展翅翱翔' })).toBeVisible()
  await expect(pending.getByText('正在组织讲解')).toBeVisible()
  await expect(pending.getByText('规则书已可读，正在准备讲解')).toBeVisible()
  expect(lessonLaunchRequests).toBe(0)
})

test('recovers persisted recommendation work after a full refresh without journey storage', async ({ page }) => {
  await mockPublicDiscovery(page, true, true)
  await page.goto('/discover')

  await page.getByLabel('聊聊你想玩的游戏').fill('4 个人，90 分钟内，想要中等策略')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expectWingspanRecommendationReady(page)
  await page.getByRole('button', { name: '选这款，找规则书' }).click()
  const journey = page.getByTestId('player-journey-surface')
  await journey.getByRole('button', { name: '选择这份' }).click()
  await journey.getByRole('checkbox', { name: /我已比较以上游戏、版本和语言/ }).check()
  await journey.getByRole('checkbox', { name: /确认该链接来自有权提供/ }).check()
  await journey.getByRole('button', { name: '下载规则书并生成讲解' }).click()
  await expect(page.getByTestId('background-work-trigger-desktop').locator('span').filter({ hasText: '1' })).toBeVisible()

  await page.evaluate(() => sessionStorage.clear())
  await page.reload()

  const workTrigger = page.getByTestId('background-work-trigger-desktop')
  await expect(workTrigger.locator('span').filter({ hasText: '1' })).toBeVisible()
  await workTrigger.click()
  const workCenter = page.getByRole('dialog', { name: '后台任务' })
  await expect(workCenter.getByText('展翅翱翔')).toBeVisible()
  await expect(workCenter.getByText('正在组织讲解')).toBeVisible()
  await expect(workCenter.getByText('建立讲解结构')).toHaveCount(0)
  await workCenter.getByRole('link', { name: /打开讲解中心/ }).click()

  await expect(page).toHaveURL(/\/lessons$/)
  const pending = page.getByTestId('pending-guide-journey')
  await expect(pending.getByRole('heading', { name: '展翅翱翔' })).toBeVisible()
  await expect(pending.getByText('正在组织讲解')).toBeVisible()
  await expect(pending.getByText('规则书已可读，正在准备讲解')).toBeVisible()
})

test('advances My Guides from plan startup to the first readable chapter without a manual refresh', async ({ page }) => {
  const preparation = await mockPublicDiscovery(page, true, true)
  await page.goto('/discover')

  await page.getByLabel('聊聊你想玩的游戏').fill('4 个人，90 分钟内，想要中等策略')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expectWingspanRecommendationReady(page)
  await page.getByRole('button', { name: '选这款，找规则书' }).click()
  const journey = page.getByTestId('player-journey-surface')
  await journey.getByRole('button', { name: '选择这份' }).click()
  await journey.getByRole('checkbox', { name: /我已比较以上游戏、版本和语言/ }).check()
  await journey.getByRole('checkbox', { name: /确认该链接来自有权提供/ }).check()
  await journey.getByRole('button', { name: '下载规则书并生成讲解' }).click()
  await journey.getByRole('button', { name: '关闭小窗' }).click()
  await page.goto('/lessons')

  const pending = page.getByTestId('pending-guide-journey')
  await expect(pending.getByRole('heading', { name: '展翅翱翔' })).toBeVisible()
  await expect(pending.getByText('正在组织讲解')).toBeVisible()
  await expect(pending.getByText('规则书已可读，正在准备讲解')).toBeVisible()

  const previousPlanReads = preparation.planReads()
  preparation.publishPlan()
  await expect.poll(preparation.planReads, { timeout: 6_000 }).toBeGreaterThan(previousPlanReads)
  await expect(pending).toBeVisible()
  await expect(page.getByText('等待开始')).toHaveCount(0)
  await expect(page.getByText('还没有可读的讲解')).toHaveCount(0)
  await expect(page.getByRole('link', { name: '阅读已完成章节' })).toHaveCount(0)

  preparation.completePreparation()
  preparation.publishFirstLesson()
  await expect(page.getByText('已有章节可读', { exact: true })).toBeVisible({ timeout: 12_000 })
  await expect(page.getByRole('link', { name: '阅读已完成章节' })).toBeVisible()
  await expect(pending).toHaveCount(0)
})

test('keeps one global task while completed preparation hands off to a Teaching run', async ({ page }) => {
  const preparation = await mockPublicDiscovery(page, true, true)
  await page.goto('/discover')

  await page.getByLabel('聊聊你想玩的游戏').fill('4 个人，90 分钟内，想要中等策略')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expectWingspanRecommendationReady(page)
  await page.getByRole('button', { name: '选这款，找规则书' }).click()
  const journey = page.getByTestId('player-journey-surface')
  await journey.getByRole('button', { name: '选择这份' }).click()
  await journey.getByRole('checkbox', { name: /我已比较以上游戏、版本和语言/ }).check()
  await journey.getByRole('checkbox', { name: /确认该链接来自有权提供/ }).check()
  await journey.getByRole('button', { name: '下载规则书并生成讲解' }).click()
  await journey.getByRole('button', { name: '关闭小窗' }).click()

  const workTrigger = page.getByTestId('background-work-trigger-desktop')
  await expect(workTrigger.locator('span').filter({ hasText: '1' })).toBeVisible()
  await workTrigger.click()
  const workCenter = page.getByRole('dialog', { name: '后台任务' })
  await expect(workCenter.getByText('正在组织讲解')).toBeVisible()

  preparation.publishPlan()
  preparation.completePreparation()
  preparation.publishFirstLesson()

  await expect(workCenter.getByText('正在组织讲解')).toBeVisible({ timeout: 6_000 })
  await expect(workCenter.getByText('展翅翱翔')).toHaveCount(1)
  await expect(workCenter.getByText('当前没有后台任务')).toHaveCount(0)
  await expect(workTrigger.locator('span').filter({ hasText: '1' })).toBeVisible()
})

test('recovers the preparation-to-Teaching bridge after a storage-free browser refresh', async ({ page }) => {
  const preparation = await mockPublicDiscovery(page, true, true)
  await page.goto('/discover')

  await page.getByLabel('聊聊你想玩的游戏').fill('4 个人，90 分钟内，想要中等策略')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expectWingspanRecommendationReady(page)
  await page.getByRole('button', { name: '选这款，找规则书' }).click()
  const journey = page.getByTestId('player-journey-surface')
  await journey.getByRole('button', { name: '选择这份' }).click()
  await journey.getByRole('checkbox', { name: /我已比较以上游戏、版本和语言/ }).check()
  await journey.getByRole('checkbox', { name: /确认该链接来自有权提供/ }).check()
  await journey.getByRole('button', { name: '下载规则书并生成讲解' }).click()
  await journey.getByRole('button', { name: '关闭小窗' }).click()

  preparation.publishPlan()
  preparation.completePreparation()
  await page.evaluate(() => sessionStorage.clear())
  await page.reload()

  const workTrigger = page.getByTestId('background-work-trigger-desktop')
  await expect(workTrigger.locator('span').filter({ hasText: '1' })).toBeVisible()
  await workTrigger.click()
  const workCenter = page.getByRole('dialog', { name: '后台任务' })
  await expect(workCenter.getByText('正在组织讲解')).toBeVisible()
  await expect(workCenter.getByText('展翅翱翔')).toHaveCount(1)
  await expect(workCenter.getByText('当前没有后台任务')).toHaveCount(0)

  preparation.publishFirstLesson()

  await expect(workCenter.getByText('正在组织讲解')).toBeVisible({ timeout: 6_000 })
  await expect(workCenter.getByText('展翅翱翔')).toHaveCount(1)
  await expect(workTrigger.locator('span').filter({ hasText: '1' })).toBeVisible()
})

test('retries failed preparation through the original import without downloading again', async ({ page }) => {
  const recovery = await mockPublicDiscovery(page, true, true, true)
  await page.goto('/discover')

  await page.getByLabel('聊聊你想玩的游戏').fill('4 个人，90 分钟内，想要中等策略')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expectWingspanRecommendationReady(page)
  await page.getByRole('button', { name: '选这款，找规则书' }).click()
  const journey = page.getByTestId('player-journey-surface')
  await journey.getByRole('button', { name: '选择这份' }).click()
  await journey.getByRole('checkbox', { name: /我已比较以上游戏、版本和语言/ }).check()
  await journey.getByRole('checkbox', { name: /确认该链接来自有权提供/ }).check()
  await journey.getByRole('button', { name: '下载规则书并生成讲解' }).click()
  await expect(journey.getByTestId('player-work-status')).toHaveText('需要处理')
  await expect(journey.getByTestId('player-failure-details')).toHaveAttribute(
    'data-failure-classification',
    'retry-preserved',
  )
  await expect(journey.getByText('TEACHING_PREPARATION_FAILED')).toHaveCount(1)

  recovery.publishPlan()
  recovery.publishFirstLesson()
  const retryRequest = page.waitForRequest(request =>
    request.url().endsWith('/api/v1/documents/official-imports/import-job-1/teaching-retry'))
  await journey.getByRole('button', { name: '重试当前步骤' }).click()
  const retry = await retryRequest

  expect(retry.postDataJSON()).toEqual({ expectedPreparationRunId: 'preparation-run-1' })
  await expect(journey.getByText('讲解已有可读内容；后台仍可能继续核对和补全。')).toBeVisible({ timeout: 6_000 })
  expect(recovery.importStarts()).toBe(1)
  expect(recovery.preparationRetryRequests()).toBe(1)
})

test('keeps the readable-guide continuation legible and focus-safe at 320 and 390 px', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 844 })
  await mockPublicDiscovery(page, true)
  await page.goto('/discover')

  await page.getByLabel('聊聊你想玩的游戏').fill('4 个人，90 分钟内，想要中等策略')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expectWingspanRecommendationReady(page)
  await page.getByRole('button', { name: '选这款，找规则书' }).click()
  const journey = page.getByTestId('player-journey-surface')
  await journey.getByRole('button', { name: '选择这份' }).click()
  await journey.getByRole('checkbox', { name: /我已比较以上游戏、版本和语言/ }).check()
  await journey.getByRole('checkbox', { name: /确认该链接来自有权提供/ }).check()
  await journey.getByRole('button', { name: '下载规则书并生成讲解' }).click()
  await journey.getByRole('button', { name: '关闭小窗' }).click()

  const continuation = page.getByTestId('player-journey-continuation')
  const readGuide = page.getByTestId('player-journey-dock')
  const viewProgress = page.getByTestId('player-journey-progress-button')
  await expect(continuation).toBeVisible()
  expect(await continuation.evaluate(element =>
    element.closest('[data-testid="recommendation-chat-workspace"]') !== null)).toBe(true)
  await expect(readGuide).toContainText('已有章节可读', { timeout: 8_000 })
  await expect(readGuide).toContainText('打开讲解')
  await expect(viewProgress).toHaveText('查看详细进度')

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
