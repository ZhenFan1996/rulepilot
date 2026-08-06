import { expect, test, type Page } from '@playwright/test'

const plan = {
  id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Lantern Relay', premise: '点亮整条航线。',
  playerCount: 3, beginnerCount: 2, durationMinutes: 30,
  sections: [{ position: 1, title: '摆好灯塔', visualEvidenceRecommended: true }],
}

const sections = [{
  position: 1, topicKey: 'setup', coverageTags: ['setup'], title: '摆好灯塔', required: true,
  evidenceStatus: 'SUPPORTED', visualKind: 'TABLE_LAYOUT', visualCaption: '把灯塔牌放在航线起点。',
  visualSourcePages: [2], visualSourceChunkIds: ['chunk-1'],
  steps: [
    { position: 1, heading: '找到起点', kind: 'UNDERSTAND', text: '先找到航线最左侧的起点。', sourcePages: [2], visualFocus: null },
    { position: 2, heading: '放下灯塔牌', kind: 'DO', text: '把第一张灯塔牌正面朝上放在起点。', sourcePages: [2], visualFocus: null },
  ],
}]

const catalogPresentation = {
  editionId: 'edition-1', gameName: 'Catalog Game', editionName: 'Catalog Game edition', language: 'en',
  publicationYear: 2024, bggId: 42, thumbnailUrl: 'https://example.test/catalog-cover.jpg',
  minPlayers: 1, maxPlayers: 5, playingTimeMinutes: 60, minimumAge: 10,
  bggUrl: 'https://boardgamegeek.com/boardgame/42',
}

async function mockSharedApis(page: Page) {
  await page.route('**/api/auth/session', route => route.fulfill({ json: { username: 'player', roles: ['USER'] } }))
  await page.route('**/api/v1/assistant-runs/active?mode=TEACHING', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/assistant-runs/latest?mode=*', route => route.fulfill({ status: 404 }))
  await page.route('**/api/v1/assistant-runs/answer-run-1', route => route.fulfill({ status: 404 }))
  await page.route('**/api/v1/teaching-plans/plan-1', route => route.fulfill({ json: plan }))
  await page.route('**/api/v1/teaching-plans/plan-1/catalog-presentation', route => route.fulfill({
    json: catalogPresentation,
  }))
  await page.route('**/api/v1/teaching-plans/plan-1/illustrated-lessons/latest', route => route.fulfill({
    json: { id: 'lesson-1', status: 'COMPLETE', sections },
  }))
  await page.route('**/api/v1/teaching-plans/plan-1/comprehension', route => route.fulfill({ status: 404 }))
}

test('uses one tabletop reading language for private and public guides without retired media', async ({ page }, testInfo) => {
  const retiredRequests: string[] = []
  page.on('request', (request) => {
    if (/icon-glossary|\/narration|\/video$|media-consistency/.test(request.url())) retiredRequests.push(request.url())
  })
  await mockSharedApis(page)
  await page.route('**/api/public/lessons/plan-1?language=*', route => route.fulfill({
    json: {
      teachingPlanId: 'plan-1', documentVersionId: 'version-1', rulebookTitle: 'Lantern Relay Rules',
      officialSourceUrl: 'https://example.com/rules.pdf', gameCover: null,
      lesson: { id: 'lesson-1', status: 'COMPLETE', sections },
    },
  }))
  await page.route('**/api/public/lessons/plan-1/cover', route => route.fulfill({ status: 404 }))

  await page.goto('/lesson/plan-1')
  await expect(page.locator('header.tabletop-hero')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Catalog Game', exact: true })).toBeVisible()
  await expect(page.getByText('Lantern Relay', { exact: true })).toBeVisible()
  await expect(page.getByText('桌游资料由 BoardGameGeek 提供')).toBeVisible()
  await expect(page.locator('[data-testid="private-rule-step"]')).toHaveCount(2)
  await expect(page.getByText('图标速查表')).toHaveCount(0)
  if (process.env.RULEPILOT_VISUAL_QA) {
    await page.screenshot({ path: testInfo.outputPath('private-guide.png'), fullPage: true })
  }

  await page.goto('/read/plan-1')
  await expect(page.locator('header.tabletop-hero')).toBeVisible()
  await expect(page.locator('.agent-workspace')).toBeVisible()
  await expect(page.getByRole('heading', { name: '摆好灯塔' })).toBeVisible()
  if (process.env.RULEPILOT_VISUAL_QA) {
    await page.screenshot({ path: testInfo.outputPath('public-guide.png'), fullPage: true })
  }
  expect(retiredRequests).toEqual([])
})

test('keeps the tabletop guide and agent workspace usable on mobile', async ({ page }, testInfo) => {
  let answerRequest: Record<string, unknown> | null = null
  await page.setViewportSize({ width: 390, height: 844 })
  await mockSharedApis(page)
  await page.route('**/api/auth/csrf', route => route.fulfill({
    json: { headerName: 'X-CSRF-TOKEN', token: 'csrf' },
  }))
  await page.route('**/api/v1/document-versions/version-1/answers', (route) => {
    answerRequest = route.request().postDataJSON() as Record<string, unknown>
    return route.fulfill({ json: {
      assistantRunId: 'answer-run-1',
      answer: {
        status: 'ANSWERED', shortVerdict: 'Use the cited setup order.',
        explanation: 'The uploaded rulebook places the lighthouse card at the route start.',
        citations: [{
          chunkId: 'chunk-1', sectionType: 'SETUP', heading: 'Setup',
          excerpt: 'Place the lighthouse card at the route start.', pageFrom: 2, pageTo: 2,
        }],
        exceptions: [], confidence: 'HIGH', answerBasis: 'DIRECT_RULE', official: false,
        confirmedRulingId: null, confirmedRulingVersion: null, clarification: null, warnings: [],
      },
    } })
  })

  await page.goto('/lesson/plan-1/questions')
  await expect(page.locator('header.tabletop-hero')).toBeVisible()
  await expect(page.getByRole('heading', { name: '向《Catalog Game》规则书提问' })).toBeVisible()
  await expect(page.getByText('桌游资料由 BoardGameGeek 提供')).toBeVisible()
  await expect(page.locator('.agent-workspace')).toBeVisible()
  await expect(page.getByRole('textbox', { name: '向规则书提问' })).toBeVisible()
  await page.getByRole('textbox', { name: '向规则书提问' }).fill('灯塔牌放在哪里？')
  await page.getByRole('button', { name: '提交问题' }).click()
  await expect(page.getByText('Use the cited setup order.')).toBeVisible()
  await expect(page.getByText('第 2 页')).toBeVisible()
  expect(answerRequest).toMatchObject({
    question: '灯塔牌放在哪里？',
    language: 'zh-CN',
    learningIntent: null,
  })
  expect(answerRequest).not.toHaveProperty('bggId')
  expect(answerRequest).not.toHaveProperty('catalogPresentation')
  expect(JSON.stringify(answerRequest)).not.toContain('Catalog Game')
  if (process.env.RULEPILOT_VISUAL_QA) {
    await page.screenshot({ path: testInfo.outputPath('mobile-question.png'), fullPage: true })
  }
  const hasHorizontalOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
  )
  expect(hasHorizontalOverflow).toBe(false)
})
