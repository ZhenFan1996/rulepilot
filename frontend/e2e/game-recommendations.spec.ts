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

async function mockPublicDiscovery(page: import('@playwright/test').Page) {
  await page.route('**/api/auth/session', route => route.fulfill({ status: 401 }))
  await page.route('**/api/auth/csrf', route => route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'csrf' } }))
  await page.route('**/api/v1/bgg/recommendation-agent**', async route => {
    const body = route.request().postDataJSON() as {
      profile: { players: number | null; maxMinutes: number | null; maxWeight: number | null }
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
    if (body.focusedBggId === 266192) {
      await fulfill({
        outcome: 'recommendations', mode: 'model_assisted', assistantMessage: '我补查了教学和实际桌上节奏。',
        profile: { ...body.profile, type: 'all', interaction: 'any' }, clarification: null,
        sourceCount: 179737, candidatesEvaluated: 1,
        userModel: { summary: '朋友聚会，可能重视参与感', hypotheses: [{ text: '可能不喜欢等待太久', confidence: 'medium', basedOn: '想热闹一点' }] },
        researchSources: [{ index: 1, title: 'Publisher guide', url: 'https://publisher.example/wingspan', domain: 'publisher.example' }],
        harness: { modelCalls: 2, catalogCalls: 1, webResearchCalls: 1, fallbackUsed: false, actions: ['PLAN_DIALOGUE', 'SEARCH_BGG_CATALOG', 'RESEARCH_GAME_FIT', 'COMPOSE_RECOMMENDATIONS'] },
        games: [{ game: catalog.games[0], matches: ['BGG 总榜第 34 名'], tradeoffs: [], reasons: [
          { kind: 'bgg_fact', text: 'BGG 总榜第 34 名', sourceIndexes: [] },
          { kind: 'preference_inference', text: '可能适合希望全桌持续参与的场景', sourceIndexes: [] },
          { kind: 'web_research', text: '发行商资料提供了分步教学流程', sourceIndexes: [1] },
        ] }],
      })
      return
    }
    if (body.profile.maxMinutes === null) {
      await fulfill({
        outcome: 'recommendations', mode: 'deterministic', assistantMessage: '先给你几款候选。你们愿意为一局留出多长时间？',
        profile: { ...body.profile, players: 4, type: 'all', interaction: 'any' }, sourceCount: 179737, candidatesEvaluated: 20,
        games: [{ game: catalog.games[0], matches: ['支持 4 人游玩'], tradeoffs: [] }],
        clarification: { field: 'duration', prompt: '你们愿意为一局留出多长时间？', options: [{ value: '90', label: '90 分钟内' }] },
      })
      return
    }
    if (body.profile.maxWeight === null) {
      await fulfill({
        outcome: 'recommendations', mode: 'deterministic', assistantMessage: '我按时长更新了候选。这次想要多复杂？',
        profile: { ...body.profile, type: 'all', interaction: 'any' }, sourceCount: 179737, candidatesEvaluated: 20,
        games: [{ game: catalog.games[0], matches: ['支持 4 人游玩', '70 分钟，不超过你的时长上限'], tradeoffs: [] }],
        clarification: { field: 'complexity', prompt: '这次想要多复杂？', options: [{ value: '3.2', label: '中等策略' }] },
      })
      return
    }
    await fulfill({
      outcome: 'recommendations', mode: 'deterministic', assistantMessage: '下面这些各有侧重。',
      profile: { ...body.profile, type: 'all', interaction: 'any' }, clarification: null,
      sourceCount: 179737, candidatesEvaluated: 20,
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
}

test('sorts, filters, and searches the full server-side BGG snapshot', async ({ page }) => {
  await mockPublicDiscovery(page)
  await page.goto('/discover')

  await expect(page.getByRole('heading', { level: 1 })).toContainText('整个 BGG 目录')
  await expect(page.getByText('BGG 快照共 162,686 条记录')).toBeVisible()
  await expect(page.getByRole('heading', { level: 3, name: 'Wingspan' })).toBeVisible()
  await expect(page.getByText('正在后台补齐封面、人数和中文资料…')).toBeVisible()
  await expect(page.getByText('展翅翱翔')).toBeVisible()
  await expect(page.locator('li', { hasText: '卡牌轮抽' })).toBeVisible()
  await expect(page.getByRole('link', { name: '数据由 BoardGameGeek 提供' }).locator('img')).toHaveAttribute('src', '/powered-by-bgg-rgb.svg')

  const firstAgentRequest = page.waitForRequest(request => request.url().includes('/api/v1/bgg/recommendation-agent')
    && request.headers()['x-csrf-token'] === 'csrf')
  await page.getByRole('button', { name: '朋友聚会想热闹一点', exact: true }).click()
  await firstAgentRequest
  await page.getByRole('button', { name: '90 分钟内' }).click()
  await page.getByRole('button', { name: '中等策略' }).click()
  await expect(page.getByText('从 179,737 条 BGG 快照记录中')).toBeVisible()
  await expect(page.getByText('支持 4 人游玩')).toBeVisible()

  const focusedRequest = page.waitForRequest(request => {
    if (!request.url().includes('/api/v1/bgg/recommendation-agent')) return false
    return (request.postDataJSON() as { focusedBggId?: number }).focusedBggId === 266192
  })
  await page.getByRole('button', { name: '介绍一下' }).click()
  await focusedRequest
  await expect(page.getByText('发行商资料提供了分步教学流程')).toBeVisible()
  await expect(page.getByRole('link', { name: /publisher\.example/ })).toHaveAttribute('rel', /noopener/)
  await expect(page.getByText('我目前的理解')).toBeVisible()

  await page.getByRole('combobox', { name: '排序' }).selectOption('rating')
  await page.getByRole('combobox', { name: /BGG 类型榜/ }).selectOption('strategy')
  const filteredRequest = page.waitForRequest(request => request.url().includes('/api/v1/bgg/catalog?')
    && request.url().includes('sort=rating') && request.url().includes('type=strategy'))
  await page.getByRole('button', { name: '应用' }).click()
  await filteredRequest

  await page.getByLabel('搜索整个目录').fill('Wingspan')
  const searchRequest = page.waitForRequest(request => request.url().includes('/api/v1/bgg/catalog?')
    && request.url().includes('q=Wingspan'))
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await searchRequest
  await expect(page.getByRole('link', { name: /展翅翱翔/ })).toHaveAttribute('href', '/discover/266192')
  await expect(page.getByText('第 1 / 378 页')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '再看一批' })).toBeVisible()
})

test('keeps full-catalog discovery usable without horizontal overflow at 390 px', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockPublicDiscovery(page)
  await page.goto('/discover')

  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expect(page.getByRole('navigation', { name: '主要导航' })).toBeVisible()
  await expect(page.getByRole('link', { name: /展翅翱翔/ })).toBeVisible()
  const hasHorizontalOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
  )
  expect(hasHorizontalOverflow).toBe(false)
})
