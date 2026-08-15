import { expect, test, type Page } from '@playwright/test'

const plan = {
  id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Lantern Relay', premise: '点亮整条航线。',
  sections: [{ position: 1, title: '摆好灯塔', visualEvidenceRecommended: true }],
}

const sections = [{
  position: 1, topicKey: 'setup', coverageTags: ['setup'], title: '摆好灯塔', required: true,
  evidenceStatus: 'SUPPORTED', visualKind: 'TABLE_LAYOUT', visualCaption: '把灯塔牌放在航线起点。',
  visualSourcePages: [2], visualSourceChunkIds: ['chunk-1'],
  steps: [
    { position: 1, heading: '找到起点', kind: 'UNDERSTAND', text: '先找到航线最左侧的起点。', sourcePages: [2], visualFocus: null },
    {
      position: 2, heading: '放下灯塔牌', kind: 'VISUAL', text: '把第一张灯塔牌正面朝上放在起点。', sourcePages: [2],
      visualFocus: {
        pageNumber: 2, label: '航线起点', visibleDescription: '灯塔牌位于航线最左侧的框内。',
        x: 100, y: 180, width: 320, height: 260,
      },
    },
  ],
}]

const pagePreview = `
  <svg xmlns="http://www.w3.org/2000/svg" width="480" height="680" viewBox="0 0 480 680">
    <rect width="480" height="680" fill="#f6efdf"/>
    <rect x="48" y="122" width="154" height="177" rx="10" fill="#214761" opacity="0.24"/>
    <path d="M72 210h112" stroke="#a85d3f" stroke-width="12"/>
  </svg>`

const focusedDetail = `
  <svg xmlns="http://www.w3.org/2000/svg" width="640" height="420" viewBox="0 0 640 420">
    <rect width="640" height="420" fill="#ece2c8"/>
    <rect x="90" y="100" width="220" height="160" rx="18" fill="#214761"/>
    <path d="M330 180h190" stroke="#a85d3f" stroke-width="20"/>
  </svg>`

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
    json: { id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE', sections },
  }))
  await page.route('**/api/v1/teaching-plans/plan-1/comprehension', route => route.fulfill({ status: 404 }))
  await page.route('**/pages/2/image/preview', route => route.fulfill({ contentType: 'image/svg+xml', body: pagePreview }))
  await page.route('**/pages/2/image/crop?*', route => route.fulfill({ contentType: 'image/svg+xml', body: focusedDetail }))
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
      lesson: { id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE', sections },
    },
  }))
  await page.route('**/api/public/lessons/plan-1/cover', route => route.fulfill({ status: 404 }))

  await page.goto('/lesson/plan-1')
  await expect(page.locator('header.tabletop-hero')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Catalog Game', exact: true })).toBeVisible()
  await expect(page.getByText('Lantern Relay', { exact: true })).toBeVisible()
  await expect(page.getByText('桌游资料由 BoardGameGeek 提供')).toBeVisible()
  await expect(page.locator('[data-testid="private-rule-step"]')).toHaveCount(2)
  await expect(page.getByTestId('lesson-visual-storyboard')).toBeVisible()
  await expect(page.getByTestId('lesson-visual-context')).toBeVisible()
  await expect(page.getByTestId('lesson-visual-detail')).toBeVisible()
  await expect(page.getByText('定位框和特写只说明图上位置与外观')).toBeVisible()
  await expect(page.getByTestId('lesson-questions-entry')).toHaveAttribute('href', '/lesson/plan-1/questions')
  await expect(page.locator('#lesson-question-panel')).toHaveCount(0)
  await expect(page.getByText('图标速查表')).toHaveCount(0)
  if (process.env.RULEPILOT_VISUAL_QA) {
    await page.screenshot({ path: testInfo.outputPath('private-guide.png'), fullPage: true })
  }

  await page.goto('/read/plan-1')
  await expect(page.locator('header.tabletop-hero')).toBeVisible()
  await expect(page.getByRole('heading', { name: '摆好灯塔' })).toBeVisible()
  await expect(page.getByTestId('lesson-visual-storyboard')).toBeVisible()
  await expect(page.getByTestId('lesson-visual-context')).toBeVisible()
  await expect(page.getByTestId('lesson-visual-detail')).toBeVisible()
  await expect(page.locator('#public-question')).toHaveCount(0)
  await expect(page.getByTestId('lesson-questions-entry')).toHaveAttribute('href', '/read/plan-1/questions')
  if (process.env.RULEPILOT_VISUAL_QA) {
    await page.screenshot({ path: testInfo.outputPath('public-guide.png'), fullPage: true })
  }

  await page.getByTestId('lesson-questions-entry').click()
  await expect(page).toHaveURL('/read/plan-1/questions')
  await expect(page.getByTestId('public-questions-reader')).toBeVisible()
  await expect(page.locator('#public-question')).toBeVisible()
  await expect(page.locator('[data-testid="lesson-reading-column"]')).toHaveCount(0)
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
      answer: {
        language: 'zh-CN', status: 'ANSWERED', shortVerdict: '按规则书给出的设置顺序放置。',
        explanation: '规则书要求把灯塔牌放在航线起点。',
        citations: [{
          heading: '设置',
          excerpt: 'Place the lighthouse card at the route start.', pageFrom: 2, pageTo: 2,
        }],
        exceptions: [], confidence: 'HIGH', answerBasis: 'DIRECT_RULE', source: 'UPLOADED',
        clarification: null, recovery: null, warnings: [],
      },
      conversationTurnId: null,
      rulingReference: {
        citationIds: ['chunk-1'], confirmedRulingId: null, confirmedRulingVersion: null,
      },
    } })
  })

  await page.goto('/lesson/plan-1')
  const storyboard = page.getByTestId('lesson-visual-storyboard')
  await expect(storyboard).toBeVisible()
  await expect(page.getByTestId('lesson-visual-context')).toBeVisible()
  await expect(page.getByTestId('lesson-visual-detail')).toBeVisible()
  const storyboardBox = await storyboard.boundingBox()
  expect(storyboardBox).not.toBeNull()
  expect(storyboardBox!.width).toBeLessThanOrEqual(374)
  expect(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)).toBe(false)
  if (process.env.RULEPILOT_VISUAL_QA) {
    await storyboard.screenshot({ path: testInfo.outputPath('mobile-guide-storyboard.png') })
  }

  await page.goto('/lesson/plan-1/questions')
  await expect(page.locator('header.tabletop-hero')).toBeVisible()
  await expect(page.getByRole('heading', { name: '向《Catalog Game》规则书提问' })).toBeVisible()
  await expect(page.getByText('桌游资料由 BoardGameGeek 提供')).toBeVisible()
  await expect(page.locator('#lesson-question-panel .tabletop-panel.player-board')).toBeVisible()
  await expect(page.getByRole('textbox', { name: '向规则书提问' })).toBeVisible()
  await page.getByRole('textbox', { name: '向规则书提问' }).fill('灯塔牌放在哪里？')
  await page.getByRole('button', { name: '提交问题' }).click()
  await expect(page.getByText('按规则书给出的设置顺序放置。')).toBeVisible()
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

test('confirms browser-only Q&A reset and preserves the unsent question with stable focus', async ({ page }) => {
  await page.addInitScript(() => {
    sessionStorage.setItem('rulepilot:public-answer-thread:v2:account:player:plan-1:zh-CN', JSON.stringify([{
      question: '上一轮什么时候结束？',
      answer: {
        answer: {
          status: 'ANSWERED', shortVerdict: '完成当前行动后结束。', explanation: null,
          citations: [{ heading: '结算顺序', pageFrom: 2, pageTo: 2 }], exceptions: [], confidence: 'HIGH',
          answerBasis: 'DIRECT_RULE', clarification: null, warnings: [],
        },
        visualAids: [], examples: [],
      },
    }]))
  })
  await mockSharedApis(page)
  await page.route('**/api/public/lessons/plan-1?language=*', route => route.fulfill({
    json: {
      teachingPlanId: 'plan-1', documentVersionId: 'version-1', rulebookTitle: 'Lantern Relay Rules',
      officialSourceUrl: null, gameCover: null,
      lesson: { id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE', sections },
    },
  }))
  await page.route('**/api/public/lessons/plan-1/cover', route => route.fulfill({ status: 404 }))

  await page.goto('/read/plan-1/questions')
  await expect(page.getByText('上一轮什么时候结束？')).toBeVisible()
  const question = page.locator('#public-question')
  await question.fill('这句尚未发送')

  const reset = page.getByRole('button', { name: '清空本次答疑' })
  await reset.focus()
  await reset.click()
  const dialog = page.getByRole('alertdialog', { name: '清空这次公开答疑？' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('button', { name: '保留答疑' })).toBeFocused()
  await expect(page.getByText('上一轮什么时候结束？')).toBeVisible()
  await expect(dialog).toContainText('服务器没有可供恢复的副本')

  await page.keyboard.press('Escape')
  await expect(dialog).toHaveCount(0)
  await expect(reset).toBeFocused()
  await expect(page.getByText('上一轮什么时候结束？')).toBeVisible()

  await reset.click()
  await page.getByRole('alertdialog', { name: '清空这次公开答疑？' })
    .getByRole('button', { name: '清空答疑' })
    .click()
  await expect(dialog).toHaveCount(0)
  await expect(page.getByText('上一轮什么时候结束？')).toHaveCount(0)
  await expect(question).toHaveValue('这句尚未发送')
  await expect(question).toBeFocused()
  expect(await page.evaluate(() => sessionStorage.getItem('rulepilot:public-answer-thread:v2:account:player:plan-1:zh-CN')))
    .toBeNull()
})
