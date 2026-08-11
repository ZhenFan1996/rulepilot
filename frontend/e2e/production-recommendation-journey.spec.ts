import { writeFile } from 'node:fs/promises'

import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'

const enabled = process.env.RULEPILOT_PRODUCTION_RECOMMENDATION_JOURNEY === 'true'
const TARGET_BGG_ID = 230802
const TARGET_NAME = /花砖物语|Azul/i
const RECOMMENDATION_PROMPT = '我今晚已经决定玩花砖物语（Azul），第一次开桌。请直接帮我找到这款，不要换成相似游戏；找到后我想接着读规则书、听讲解，再问几个问题。'
const PRESERVED_DRAFT = '下次我还想给完全没玩过桌游的家人找一款更轻松的。'
const RULE_QUESTION = '我从一个工厂展示板拿走同色砖以后，剩下的砖要放到哪里？请用日常的话简短回答，并引用规则书页码。'

interface RulebookCandidate {
  title: string
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
  title?: string
  rulebookTitle?: string
  editionId: string | null
  sourceDomain?: string
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

interface BoundGameResponse {
  game: { id: string; name: string }
  edition: { id: string; name: string }
  bggId: number
}

interface CatalogGameResponse {
  game: { id: string; name: string }
  editions: Array<{ id: string; name: string }>
}

interface TeachingPlanResponse {
  id: string
  documentVersionId: string
  gameTitle: string
}

interface AnswerResponse {
  answer: {
    status: string
    citations: Array<{ pageFrom: number; pageTo: number }>
  }
}

interface DocumentProgressResponse {
  stage: string
  complete: boolean
}

interface RuleDocumentResponse {
  document: { gameEditionId: string | null; title: string }
  latestVersion: { id: string }
}

interface RunDetailsResponse {
  run: {
    state: string
    lastErrorCode: string | null
  }
}

interface ProductionJourneyReport {
  generatedAt: string
  completed: boolean
  stage: string
  targetBggId: number
  routeStayedOnDiscover: boolean
  journeyBackdropVisible: boolean
  journeySurfaceOpaque: boolean
  lessonBackdropVisible: boolean
  lessonSurfaceOpaque: boolean
  confirmedMilestonesAtSourceReview: number
  confirmedMilestonesFinal: number
  boundGameInCatalog: boolean
  boundBggId: number | null
  boundGameName: string | null
  boundEditionId: string | null
  candidateEditionMatchesSelection: boolean
  importEditionMatchesSelection: boolean
  documentEditionMatchesSelection: boolean
  myGuidesEntryVisibleBeforeLesson: boolean
  myGuidesPlanListed: boolean
  planGameTitleMatchesSelection: boolean
  recommendationMs: number | null
  detailsDialogOpenedAndClosed: boolean
  discoveryMs: number | null
  sourceDomain: string | null
  sourceUrl: string | null
  sourceMode: string | null
  importRequestCount: number
  importReused: boolean | null
  importDuplicate: boolean | null
  downloadedBytes: number | null
  importMs: number | null
  documentProgressStage: string | null
  documentProgressComplete: boolean | null
  teachingHandoffState: string | null
  teachingPreparationState: string | null
  teachingPreparationErrorCode: string | null
  rulebookReadableMs: number | null
  renderedRulebookPage: boolean
  lessonReadableMs: number | null
  lessonSectionCount: number
  citedLessonStep: boolean
  answerMs: number | null
  answerStatus: string | null
  answerCitationCount: number
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

async function opaqueSurface(locator: Locator) {
  await expect(locator).toBeVisible()
  const appearance = await locator.evaluate((element) => {
    const style = getComputedStyle(element)
    const match = style.backgroundColor.match(/^rgba?\(([^)]+)\)$/)
    const channels = match?.[1]?.split(',').map(value => Number(value.trim())) ?? []
    const alpha = channels.length >= 4 ? channels[3]! : 1
    const bounds = element.getBoundingClientRect()
    return {
      alpha,
      hasOpaqueColor: style.backgroundColor !== 'transparent' && match !== null,
      opacity: Number(style.opacity),
      width: bounds.width,
      height: bounds.height,
    }
  })
  return appearance.hasOpaqueColor
    && appearance.alpha === 1
    && appearance.opacity === 1
    && appearance.width > 0
    && appearance.height > 0
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
  let guidesPage: Page | null = null
  let importRequestCount = 0
  let observedDocumentVersionId: string | null = null
  let observedPreparationRunId: string | null = null
  let observedImportRequest: { editionId?: string; officialSourceUrl?: string } | null = null
  page.on('pageerror', error => pageErrors.push(error))
  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/documents/official-imports' && request.method() === 'POST') {
      importRequestCount += 1
      observedImportRequest = request.postDataJSON() as { editionId?: string; officialSourceUrl?: string }
    }
  })

  const report: ProductionJourneyReport = {
    generatedAt: new Date().toISOString(), completed: false, stage: 'login', targetBggId: TARGET_BGG_ID,
    routeStayedOnDiscover: false, journeyBackdropVisible: false, journeySurfaceOpaque: false,
    lessonBackdropVisible: false, lessonSurfaceOpaque: false,
    confirmedMilestonesAtSourceReview: 0, confirmedMilestonesFinal: 0,
    boundGameInCatalog: false, boundBggId: null, boundGameName: null, boundEditionId: null,
    candidateEditionMatchesSelection: false, importEditionMatchesSelection: false,
    documentEditionMatchesSelection: false, myGuidesEntryVisibleBeforeLesson: false, myGuidesPlanListed: false,
    planGameTitleMatchesSelection: false, recommendationMs: null, detailsDialogOpenedAndClosed: false,
    discoveryMs: null, sourceDomain: null, sourceUrl: null, sourceMode: null, importRequestCount: 0,
    importReused: null, importDuplicate: null, downloadedBytes: null, importMs: null,
    documentProgressStage: null, documentProgressComplete: null, teachingHandoffState: null,
    teachingPreparationState: null, teachingPreparationErrorCode: null,
    rulebookReadableMs: null, renderedRulebookPage: false, lessonReadableMs: null,
    lessonSectionCount: 0, citedLessonStep: false, answerMs: null, answerStatus: null,
    answerCitationCount: 0, citedAnswer: false,
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
    const bindingResponsePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname === `/api/v1/bgg/games/${TARGET_BGG_ID}/import`
        && response.request().method() === 'POST'
        && response.ok()
    }, { timeout: 90_000 })
    await targetDetailsButton.click()
    details = page.getByRole('dialog', { name: '桌游详细资料' })
    await details.getByRole('button', { name: '选这款，继续找规则书' }).click()
    const [candidatesResponse, bindingResponse] = await Promise.all([
      candidatesResponsePromise,
      bindingResponsePromise,
    ])
    const boundGame = await bindingResponse.json() as BoundGameResponse
    const candidateResult = await candidatesResponse.json() as CandidateResponse
    report.boundBggId = boundGame.bggId
    report.boundGameName = boundGame.game.name
    report.boundEditionId = boundGame.edition.id
    expect(boundGame.bggId, 'The binding response did not preserve the selected BGG identity').toBe(TARGET_BGG_ID)
    expect(boundGame.game.name, 'The binding response used an unexpected game title').toMatch(TARGET_NAME)
    report.candidateEditionMatchesSelection = new URL(candidatesResponse.url()).searchParams.get('editionId')
      === boundGame.edition.id
    expect(report.candidateEditionMatchesSelection,
      'Rulebook discovery used a different edition from the selected recommendation').toBe(true)
    report.discoveryMs = elapsed(discoveryStartedAt)
    expect(candidateResult.configured).toBe(true)
    const gstoneCandidate = candidateResult.candidates.find(candidate =>
      candidate.sourceDomain.endsWith('gstonegames.com')
      && candidate.language.toLowerCase().startsWith('zh')
      && candidate.acquisitionMode !== 'SOURCE_PAGE')
    expect(gstoneCandidate, 'No importable Chinese Gstone rulebook was discovered').toBeDefined()
    report.sourceDomain = gstoneCandidate!.sourceDomain
    report.sourceUrl = gstoneCandidate!.url
    report.sourceMode = gstoneCandidate!.acquisitionMode

    const catalogResponse = await page.request.get('/api/v1/games')
    expect(catalogResponse.ok(), `Catalog returned HTTP ${catalogResponse.status()}`).toBe(true)
    const catalogGames = await catalogResponse.json() as CatalogGameResponse[]
    report.boundGameInCatalog = catalogGames.some(entry =>
      entry.game.id === boundGame.game.id
      && entry.game.name === boundGame.game.name
      && entry.editions.some(edition => edition.id === boundGame.edition.id))
    expect(report.boundGameInCatalog, 'The selected recommendation was not bound in My Games').toBe(true)

    report.stage = 'source-review'
    const journeyBackdrop = page.getByTestId('player-journey-backdrop')
    const journeySurface = page.getByTestId('player-journey-surface')
    report.journeyBackdropVisible = await journeyBackdrop.isVisible()
    report.journeySurfaceOpaque = await opaqueSurface(journeySurface)
    expect(report.journeyBackdropVisible).toBe(true)
    expect(report.journeySurfaceOpaque).toBe(true)
    report.confirmedMilestonesAtSourceReview = await journeySurface
      .locator('[data-fact-confirmed="true"]').count()
    expect(report.confirmedMilestonesAtSourceReview).toBe(1)
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
    report.importReused = launchedJob.reused
    report.importEditionMatchesSelection = launchedJob.editionId === boundGame.edition.id
      && observedImportRequest?.editionId === boundGame.edition.id
      && observedImportRequest?.officialSourceUrl === gstoneCandidate!.url
    expect(report.importEditionMatchesSelection,
      'The official import request or persisted job changed the selected edition/source identity').toBe(true)
    expect(launchedJob.title, 'The official import response did not retain the selected game title')
      .toBe(boundGame.game.name)
    expect(launchedJob.sourceDomain, 'The official import response changed the selected source domain')
      .toBe(gstoneCandidate!.sourceDomain)

    const completedJob = await waitForCompletedImport(page.request, launchedJob.id)
    expect(completedJob.downloadedBytes).toBeGreaterThan(0)
    expect(completedJob.documentVersionId).not.toBeNull()
    expect(completedJob.teachingHandoffState).toBe('LAUNCHED')
    expect(completedJob.teachingPreparationRunId).not.toBeNull()
    expect(completedJob.editionId).toBe(boundGame.edition.id)
    observedDocumentVersionId = completedJob.documentVersionId
    observedPreparationRunId = completedJob.teachingPreparationRunId
    report.importDuplicate = completedJob.duplicate
    report.downloadedBytes = completedJob.downloadedBytes
    report.importMs = elapsed(importStartedAt)
    report.teachingHandoffState = completedJob.teachingHandoffState
    const progressResponse = await page.request.get(
      `/api/v1/document-versions/${encodeURIComponent(completedJob.documentVersionId!)}/progress/snapshot`,
    )
    expect(progressResponse.ok(), `Document progress returned HTTP ${progressResponse.status()}`).toBe(true)
    const progressPayload = await progressResponse.json() as DocumentProgressResponse
    report.documentProgressStage = progressPayload.stage
    report.documentProgressComplete = progressPayload.complete
    expect(progressPayload).toMatchObject({ stage: 'READY', complete: true })
    expect(importRequestCount).toBe(1)
    const documentsResponse = await page.request.get('/api/v1/documents')
    expect(documentsResponse.ok(), `Documents returned HTTP ${documentsResponse.status()}`).toBe(true)
    const documents = await documentsResponse.json() as RuleDocumentResponse[]
    const importedDocument = documents.find(document => document.latestVersion.id === completedJob.documentVersionId)
    report.documentEditionMatchesSelection = importedDocument?.document.gameEditionId === boundGame.edition.id
    expect(report.documentEditionMatchesSelection,
      'The readable document was not persisted against the selected game edition').toBe(true)

    guidesPage = await page.context().newPage()
    guidesPage.on('pageerror', error => pageErrors.push(error))
    await guidesPage.goto('/lessons')
    const pendingGuideEntry = guidesPage.getByTestId('pending-guide-journey')
      .filter({ hasText: boundGame.game.name })
    const persistedGuideHeading = guidesPage.locator('h2').filter({ hasText: boundGame.game.name })
    await expect(pendingGuideEntry.or(persistedGuideHeading).first()).toBeVisible({ timeout: 60_000 })
    report.myGuidesEntryVisibleBeforeLesson = true

    report.stage = 'read-rulebook-while-teaching'
    const rulebookReadableStartedAt = performance.now()
    const openRulebook = page.getByRole('button', { name: '先阅读原规则书' })
    await expect(openRulebook).toBeVisible({ timeout: 8 * 60_000 })
    await expect.poll(() => journeySurface.locator('[data-fact-confirmed="true"]').count(), {
      timeout: 60_000,
      message: 'Persisted rulebook facts never confirmed the first three journey milestones',
    }).toBeGreaterThanOrEqual(3)
    report.rulebookReadableMs = elapsed(rulebookReadableStartedAt)
    await openRulebook.click()
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
    report.lessonBackdropVisible = await page.getByTestId('recommendation-lesson-backdrop').isVisible()
    report.lessonSurfaceOpaque = await opaqueSurface(page.getByTestId('recommendation-lesson-surface'))
    expect(report.lessonBackdropVisible).toBe(true)
    expect(report.lessonSurfaceOpaque).toBe(true)
    await expect(lesson.getByText('每个步骤都保留原规则书页码；答疑只使用同一份规则书。')).toBeVisible({ timeout: 60_000 })
    const lessonSections = lesson.getByTestId('lesson-reading-column').locator('section')
    report.lessonSectionCount = await lessonSections.count()
    expect(report.lessonSectionCount).toBeGreaterThan(0)
    await expect(lesson.getByRole('link', { name: /来源：第 \d+(?:、\d+)* 页/ }).first()).toBeVisible()
    report.citedLessonStep = true
    report.confirmedMilestonesFinal = await page.getByTestId('player-journey-surface')
      .locator('[data-fact-confirmed="true"]').count()
    expect(report.confirmedMilestonesFinal).toBe(5)

    const plansResponse = await page.request.get('/api/v1/teaching-plans')
    expect(plansResponse.ok(), `My Guides returned HTTP ${plansResponse.status()}`).toBe(true)
    const plans = await plansResponse.json() as TeachingPlanResponse[]
    const persistedPlan = plans.find(plan => plan.documentVersionId === completedJob.documentVersionId)
    expect(persistedPlan, 'The generated plan was not listed in My Guides').toBeDefined()
    report.myGuidesPlanListed = persistedPlan != null
    report.planGameTitleMatchesSelection = persistedPlan?.gameTitle === boundGame.game.name
    expect(report.planGameTitleMatchesSelection,
      `My Guides used ${persistedPlan?.gameTitle ?? 'no title'} instead of ${boundGame.game.name}`).toBe(true)
    await guidesPage.reload()
    await expect(guidesPage.getByRole('heading', { name: boundGame.game.name, exact: true })).toBeVisible({ timeout: 60_000 })

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
    const answerPayload = await answerResponse.json() as AnswerResponse
    report.answerStatus = answerPayload.answer.status
    report.answerCitationCount = answerPayload.answer.citations.length
    expect(['ANSWERED', 'ANSWERED_WITH_WARNING']).toContain(report.answerStatus)
    expect(report.answerCitationCount).toBeGreaterThan(0)
    await expect(answerWorkspace.locator('#lesson-answer-evidence-title')).toBeVisible({ timeout: 4 * 60_000 })
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
    await expect(answerWorkspace.locator('#lesson-answer-evidence-title')).toBeVisible()
    report.answerRestored = true
    await roleSwitcher.getByRole('button', { name: '继续推荐' }).click()

    await expect(page).toHaveURL(/\/discover$/)
    expect(pageErrors, 'The production journey emitted uncaught browser errors').toEqual([])
    report.routeStayedOnDiscover = true
    report.completed = true
    report.stage = 'completed'
  } finally {
    if (observedDocumentVersionId) {
      try {
        const response = await page.request.get(
          `/api/v1/document-versions/${encodeURIComponent(observedDocumentVersionId)}/progress/snapshot`,
        )
        if (response.ok()) {
          const progress = await response.json() as DocumentProgressResponse
          report.documentProgressStage = progress.stage
          report.documentProgressComplete = progress.complete
        }
      } catch {
        // The primary assertion remains authoritative when diagnostic refresh is unavailable.
      }
    }
    if (observedPreparationRunId) {
      try {
        const response = await page.request.get(
          `/api/v1/assistant-runs/${encodeURIComponent(observedPreparationRunId)}`,
        )
        if (response.ok()) {
          const details = await response.json() as RunDetailsResponse
          report.teachingPreparationState = details.run.state
          report.teachingPreparationErrorCode = details.run.lastErrorCode
        }
      } catch {
        // The browser report must still be retained if its final diagnostic read is unavailable.
      }
    }
    await guidesPage?.close().catch(() => undefined)
    report.importRequestCount = importRequestCount
    report.pageErrorCount = pageErrors.length
    report.generatedAt = new Date().toISOString()
    await retainReport(reportFile, report)
  }
})
