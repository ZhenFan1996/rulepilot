import { writeFile } from 'node:fs/promises'

import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

const enabled = process.env.RULEPILOT_PRODUCTION_RECOMMENDATION_JOURNEY === 'true'
const TARGET_BGG_ID = 230802
const TARGET_NAME = /花砖物语|Azul/i
const RECOMMENDATION_PROMPT = '我今晚已经决定玩花砖物语（Azul），第一次开桌。请直接帮我找到这款，不要换成相似游戏；找到后我想接着读规则书、听讲解，再问几个问题。'
const PRESERVED_DRAFT = '下次我还想给完全没玩过桌游的家人找一款更轻松的。'
const RULE_QUESTION = '我是第一次玩，我的回合里通常要做什么？请用日常的话简短说明，并引用规则书页码。'

interface RulebookCandidate {
  url: string
  sourceDomain: string
  language: string
  acquisitionMode: 'DIRECT_PDF' | 'IMAGE_GALLERY' | 'SOURCE_PAGE'
}

interface CandidateResponse {
  configured: boolean
  candidates: RulebookCandidate[]
}

interface ImportJob {
  id: string
  stage: 'QUEUED' | 'CONNECTING' | 'DOWNLOADING' | 'COMPRESSING' | 'VERIFYING_FILE' | 'SAVING' | 'COMPLETED' | 'FAILED'
  downloadedBytes: number
  documentVersionId: string | null
  duplicate: boolean
  errorCode: string | null
  teachingHandoffState: 'NOT_REQUESTED' | 'WAITING_FOR_DOCUMENT' | 'LAUNCHING' | 'LAUNCHED' | 'FAILED'
  teachingPreparationRunId: string | null
  teachingErrorCode: string | null
  reused: boolean
}

interface ProductionJourneyReport {
  generatedAt: string
  completed: boolean
  stage: string
  targetBggId: number
  routeStayedOnDiscover: boolean
  recommendationMs: number | null
  detailsDialogOpenedAndClosed: boolean
  discoveryMs: number | null
  sourceDomain: string | null
  sourceMode: string | null
  importRequestCount: number
  importReused: boolean | null
  importDuplicate: boolean | null
  downloadedBytes: number | null
  importMs: number | null
  rulebookReadableMs: number | null
  renderedRulebookPage: boolean
  lessonReadableMs: number | null
  lessonSectionCount: number
  citedLessonStep: boolean
  answerMs: number | null
  citedAnswer: boolean
  recommendationRestored: boolean
  answerRestored: boolean
  pageErrorCount: number
}

function elapsed(startedAt: number) {
  return Math.round(performance.now() - startedAt)
}

async function login(page: Page, username: string, password: string) {
  await page.addInitScript(() => localStorage.setItem('rulepilot:locale', 'zh-CN'))
  await page.goto('/login')
  await page.locator('input[name="username"]').fill(username)
  await page.locator('input[name="password"]').fill(password)
  await page.locator('form button[type="submit"]').click()
  await expect(page).toHaveURL(/\/account$/)
}

async function waitForCompletedImport(request: APIRequestContext, jobId: string): Promise<ImportJob> {
  const deadline = Date.now() + 8 * 60_000
  let latest: ImportJob | null = null
  while (Date.now() < deadline) {
    const response = await request.get(`/api/v1/documents/official-imports/${encodeURIComponent(jobId)}`)
    expect(response.ok(), `Import progress returned HTTP ${response.status()}`).toBe(true)
    latest = await response.json() as ImportJob
    if (latest.stage === 'COMPLETED' && latest.teachingHandoffState === 'LAUNCHED') return latest
    if (latest.teachingHandoffState === 'FAILED') {
      throw new Error(`Teaching handoff failed with ${latest.teachingErrorCode ?? 'UNKNOWN_TEACHING_HANDOFF_ERROR'}`)
    }
    if (latest.stage === 'FAILED') {
      throw new Error(`Official import failed with ${latest.errorCode ?? 'UNKNOWN_IMPORT_ERROR'}`)
    }
    await new Promise(resolve => setTimeout(resolve, 1_250))
  }
  throw new Error(`Official import did not complete; latest stage was ${latest?.stage ?? 'UNKNOWN'}`)
}

async function retainReport(path: string, report: ProductionJourneyReport) {
  await writeFile(path, `${JSON.stringify(report, null, 2)}\n`, { mode: 0o600 })
}

test.skip(!enabled, 'Runs only through the credentialed production recommendation workflow')

test('recommendation becomes one readable, taught, and answerable production journey', async ({ page }) => {
  test.setTimeout(40 * 60_000)
  const username = process.env.RULEPILOT_RECOMMENDATION_USER
  const password = process.env.RULEPILOT_RECOMMENDATION_PASSWORD
  const reportFile = process.env.RULEPILOT_RECOMMENDATION_REPORT
  if (!username || !password || !reportFile) {
    throw new Error('Production recommendation credentials and report path are required')
  }

  const pageErrors: Error[] = []
  let importRequestCount = 0
  page.on('pageerror', error => pageErrors.push(error))
  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/documents/official-imports' && request.method() === 'POST') importRequestCount += 1
  })

  const report: ProductionJourneyReport = {
    generatedAt: new Date().toISOString(), completed: false, stage: 'login', targetBggId: TARGET_BGG_ID,
    routeStayedOnDiscover: false, recommendationMs: null, detailsDialogOpenedAndClosed: false,
    discoveryMs: null, sourceDomain: null, sourceMode: null, importRequestCount: 0,
    importReused: null, importDuplicate: null, downloadedBytes: null, importMs: null,
    rulebookReadableMs: null, renderedRulebookPage: false, lessonReadableMs: null,
    lessonSectionCount: 0, citedLessonStep: false, answerMs: null, citedAnswer: false,
    recommendationRestored: false, answerRestored: false, pageErrorCount: 0,
  }

  try {
    await login(page, username, password)
    report.stage = 'recommendation'
    await page.goto('/discover')
    const recommendationStartedAt = performance.now()
    const composer = page.getByLabel('和推荐 Agent 聊聊')
    await composer.fill(RECOMMENDATION_PROMPT)
    await page.getByRole('button', { name: '发送', exact: true }).click()

    const targetDetailsButton = page.getByRole('button', { name: new RegExp(`查看完整资料：(?:${TARGET_NAME.source})`, 'i') })
    await expect(targetDetailsButton).toBeVisible({ timeout: 2 * 60_000 })
    report.recommendationMs = elapsed(recommendationStartedAt)
    await composer.fill(PRESERVED_DRAFT)

    report.stage = 'details-dialog'
    await targetDetailsButton.click()
    let details = page.getByRole('dialog', { name: '桌游详细资料' })
    await expect(details.getByRole('heading', { name: TARGET_NAME })).toBeVisible({ timeout: 60_000 })
    await expect(page).toHaveURL(/\/discover$/)
    await details.getByRole('button', { name: '关闭桌游资料' }).click()
    await expect(details).toBeHidden()
    report.detailsDialogOpenedAndClosed = true

    const discoveryStartedAt = performance.now()
    const candidatesResponsePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname === '/api/v1/documents/rulebook-candidates' && response.ok()
    }, { timeout: 90_000 })
    await targetDetailsButton.click()
    details = page.getByRole('dialog', { name: '桌游详细资料' })
    await details.getByRole('button', { name: '选这款，继续找规则书' }).click()
    const candidatesResponse = await candidatesResponsePromise
    const candidateResult = await candidatesResponse.json() as CandidateResponse
    report.discoveryMs = elapsed(discoveryStartedAt)
    expect(candidateResult.configured).toBe(true)
    const gstoneCandidate = candidateResult.candidates.find(candidate =>
      candidate.sourceDomain.endsWith('gstonegames.com')
      && candidate.language.toLowerCase().startsWith('zh')
      && candidate.acquisitionMode !== 'SOURCE_PAGE')
    expect(gstoneCandidate, 'No importable Chinese Gstone rulebook was discovered').toBeDefined()
    report.sourceDomain = gstoneCandidate!.sourceDomain
    report.sourceMode = gstoneCandidate!.acquisitionMode

    report.stage = 'source-review'
    const candidateCard = page.locator('li', {
      has: page.locator(`a[href="${gstoneCandidate!.url}"]`),
    }).first()
    await expect(candidateCard).toContainText('社区规则书来源')
    await candidateCard.getByRole('button', { name: '选择这份' }).click()
    const importButton = page.getByRole('button', { name: '下载规则书并生成讲解' })
    await expect(importButton).toBeDisabled()
    await page.getByRole('checkbox', { name: /我确认该链接来自有权提供/ }).check()
    await expect(importButton).toBeEnabled()

    report.stage = 'import'
    const importStartedAt = performance.now()
    const importResponsePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname === '/api/v1/documents/official-imports' && response.request().method() === 'POST'
    }, { timeout: 30_000 })
    await importButton.click()
    const importResponse = await importResponsePromise
    expect(importResponse.status()).toBe(202)
    const launchedJob = await importResponse.json() as ImportJob
    expect(launchedJob.reused, 'The production check requires a fresh source acquisition').toBe(false)
    report.importReused = launchedJob.reused

    const completedJob = await waitForCompletedImport(page.request, launchedJob.id)
    expect(completedJob.downloadedBytes).toBeGreaterThan(0)
    expect(completedJob.documentVersionId).not.toBeNull()
    expect(completedJob.teachingHandoffState).toBe('LAUNCHED')
    expect(completedJob.teachingPreparationRunId).not.toBeNull()
    report.importDuplicate = completedJob.duplicate
    report.downloadedBytes = completedJob.downloadedBytes
    report.importMs = elapsed(importStartedAt)
    expect(importRequestCount).toBe(1)

    report.stage = 'read-rulebook-while-teaching'
    const rulebookReadableStartedAt = performance.now()
    await expect(page.getByText('规则书已经可以阅读；讲解会继续在后台生成。')).toBeVisible({ timeout: 8 * 60_000 })
    report.rulebookReadableMs = elapsed(rulebookReadableStartedAt)
    await page.getByRole('button', { name: '先阅读原规则书' }).click()
    const rulebook = page.getByRole('dialog', { name: '原规则书阅读器' })
    await expect(rulebook.getByText('你可以先阅读原规则书；讲解仍在后台生成')).toBeVisible()
    const firstPage = rulebook.getByRole('img', { name: '规则书第 1 页' })
    await expect(firstPage).toBeVisible({ timeout: 90_000 })
    await expect.poll(async () => firstPage.evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0), {
      timeout: 90_000,
      message: 'The first production rulebook page never rendered',
    }).toBe(true)
    report.renderedRulebookPage = true
    await rulebook.getByRole('button', { name: '关闭规则书' }).click()

    report.stage = 'lesson'
    const lessonStartedAt = performance.now()
    const journeyDock = page.getByTestId('player-journey-dock')
    await expect(journeyDock).toBeVisible()
    await journeyDock.click()
    const openLesson = page.getByRole('button', { name: '打开已生成的讲解' })
    await expect(openLesson).toBeVisible({ timeout: 20 * 60_000 })
    report.lessonReadableMs = elapsed(lessonStartedAt)
    await openLesson.click()
    const lesson = page.getByRole('dialog', { name: '生成讲解阅读器' })
    await expect(lesson.getByText('每个步骤都保留原规则书页码；答疑只使用同一份规则书。')).toBeVisible({ timeout: 60_000 })
    const lessonSections = lesson.getByTestId('lesson-reading-column').locator('section')
    report.lessonSectionCount = await lessonSections.count()
    expect(report.lessonSectionCount).toBeGreaterThan(0)
    await expect(lesson.getByRole('link', { name: /来源：第 \d+(?:、\d+)* 页/ }).first()).toBeVisible()
    report.citedLessonStep = true

    report.stage = 'grounded-answer'
    await lesson.getByRole('button', { name: '切换到规则答疑' }).click()
    const answerWorkspace = page.getByTestId('recommendation-answer-workspace')
    await expect(answerWorkspace).toContainText('已绑定规则书，可以开始提问', { timeout: 60_000 })
    const answerStartedAt = performance.now()
    const answerResponsePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname === `/api/v1/document-versions/${completedJob.documentVersionId}/answers`
        && response.request().method() === 'POST'
    }, { timeout: 4 * 60_000 })
    await page.getByLabel('向规则书提问').fill(RULE_QUESTION)
    await page.getByRole('button', { name: '提交问题' }).click()
    const answerResponse = await answerResponsePromise
    expect(answerResponse.ok(), `Answer endpoint returned HTTP ${answerResponse.status()}`).toBe(true)
    await expect(answerWorkspace.getByText('直接核对规则依据')).toBeVisible({ timeout: 4 * 60_000 })
    await expect(answerWorkspace.getByText(/第 \d+(?:\s*[–-]\s*\d+)? 页/).first()).toBeVisible()
    report.answerMs = elapsed(answerStartedAt)
    report.citedAnswer = true

    report.stage = 'role-switching'
    const roleSwitcher = page.getByTestId('agent-role-switcher')
    await roleSwitcher.getByRole('button', { name: '继续推荐' }).click()
    await expect(composer).toBeVisible()
    await expect(composer).toHaveValue(PRESERVED_DRAFT)
    await expect(targetDetailsButton).toBeVisible()
    report.recommendationRestored = true
    await roleSwitcher.getByRole('button', { name: '规则答疑' }).click()
    await expect(answerWorkspace.getByText('直接核对规则依据')).toBeVisible()
    report.answerRestored = true
    await roleSwitcher.getByRole('button', { name: '继续推荐' }).click()

    await expect(page).toHaveURL(/\/discover$/)
    expect(pageErrors, 'The production journey emitted uncaught browser errors').toEqual([])
    report.routeStayedOnDiscover = true
    report.completed = true
    report.stage = 'completed'
  } finally {
    report.importRequestCount = importRequestCount
    report.pageErrorCount = pageErrors.length
    report.generatedAt = new Date().toISOString()
    await retainReport(reportFile, report)
  }
})
