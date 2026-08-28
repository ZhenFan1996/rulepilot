import { writeFile } from 'node:fs/promises'

import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'

const enabled = process.env.RULEPILOT_PRODUCTION_RECOMMENDATION_JOURNEY === 'true'
const SELECTION_PROMPT = process.env.RULEPILOT_RECOMMENDATION_SELECTION_PROMPT ?? ''
const EXPECTED_TITLE_TERM = (process.env.RULEPILOT_RECOMMENDATION_EXPECTED_TITLE_TERM ?? '')
  .normalize('NFKC')
  .trim()
  .toLocaleLowerCase('en-US')
const TESTED_SHA = process.env.RULEPILOT_RECOMMENDATION_TESTED_SHA ?? ''
const ACTIVE_RELEASE_SHA = process.env.RULEPILOT_RECOMMENDATION_ACTIVE_RELEASE_SHA ?? ''
const INTERACTION_SLO_MS = 20_000
const FIRST_PROGRESS_SLO_MS = 3_000
const TERMINAL_OBSERVATION_MS = 50_000
const PUBLIC_FAILURE_BOUNDARIES = new Set([
  'time_budget',
  'model_response',
  'service_configuration',
  'action_budget',
  'publication_boundary',
  'service_failure',
])

type RecommendationOutcome =
  | 'conversation'
  | 'needs_clarification'
  | 'recommendations'
  | 'no_match'
  | 'unavailable'

interface RecommendationGame {
  game: {
    bggId: number
    name: string
    originalName: string
  }
  replyParts?: Array<{
    text: string
  }>
}

interface RecommendationResult {
  clientTurnId: string
  outcome: RecommendationOutcome
  assistantMessage: string
  games: RecommendationGame[]
  completedWork?: string[]
  modelCalls?: number
  catalogCalls?: number
  webResearchCalls?: number
  failureBoundary?: string | null
}

interface RecommendationSession {
  conversationId: string
  revision: number
  processing: boolean
  latestResponse: RecommendationResult | null
}

type TerminalCategory =
  | 'RECOMMENDATIONS'
  | 'NON_RECOMMENDATION'
  | 'SESSION_TIMEOUT'
  | 'SESSION_READ_FAILURE'

interface TerminalObservation {
  category: TerminalCategory
  session: RecommendationSession | null
  elapsedMs: number
}

interface SlateObservation {
  rendered: boolean
  elapsedMs: number
  bggIds: number[]
}

interface ProductionRecommendationReport {
  generatedAt: string
  completed: boolean
  stage: string
  testedSha: string
  activeReleaseSha: string
  routeStayedOnDiscover: boolean
  recommendationRequestedCardCount: number
  recommendationPersistedCardCount: number | null
  recommendationShortfallCount: number | null
  recommendationOutcome: RecommendationOutcome | null
  recommendationTerminalCategory: TerminalCategory | 'NOT_OBSERVED'
  recommendationTerminalObserved: boolean
  recommendationFirstProgressMs: number | null
  recommendationPersistedTerminalMs: number | null
  recommendationRenderedSlateMs: number | null
  recommendationElapsedMs: number | null
  recommendationSloMet: boolean | null
  recommendationPublishedGames: Array<{
    bggId: number
    name: string
    originalName: string
  }>
  recommendationAssistantReplyCharacterCount: number | null
  recommendationRenderedReplyCharacterCount: number | null
  recommendationCardReplyPartCount: number | null
  recommendationCompletedWork: string[]
  recommendationModelCalls: number | null
  recommendationCatalogCalls: number | null
  recommendationWebResearchCalls: number | null
  recommendationFailureBoundary: string | null
  expectedRecommendationTitleTerm: string
  rawModelOutputCaptured: false
}

function parseRequestedCardCount(value: string | undefined) {
  if (!value || !/^\d+$/.test(value)) {
    throw new Error(`Invalid requested recommendation card count: ${value ?? '(missing)'}`)
  }
  const count = Number(value)
  if (!Number.isSafeInteger(count) || count < 1 || count > 10) {
    throw new Error(`Requested recommendation card count is outside the supported range: ${value}`)
  }
  return count
}

function elapsed(startedAt: number) {
  return Math.round(performance.now() - startedAt)
}

function sleep(milliseconds: number) {
  return new Promise(resolve => setTimeout(resolve, milliseconds))
}

function publicNonNegativeInteger(value: unknown): number | null {
  return Number.isSafeInteger(value) && Number(value) >= 0 ? Number(value) : null
}

function publicFailureBoundary(value: unknown): string | null {
  return typeof value === 'string' && PUBLIC_FAILURE_BOUNDARIES.has(value) ? value : null
}

function hasPositiveDistinctBggIds(games: RecommendationGame[]) {
  const ids = games.map(entry => entry.game.bggId)
  return ids.length > 0
    && ids.every(id => Number.isSafeInteger(id) && id > 0)
    && new Set(ids).size === ids.length
}

function everyGameMatchesExpectedTitle(games: RecommendationGame[]) {
  if (EXPECTED_TITLE_TERM === '') return true
  return games.every(({ game }) => [game.name, game.originalName]
    .filter((title): title is string => typeof title === 'string')
    .some(title => title.normalize('NFKC').toLocaleLowerCase('en-US').includes(EXPECTED_TITLE_TERM)))
}

function sameOrderedBggSlate(rendered: number[], persisted: number[]) {
  return rendered.length === persisted.length
    && rendered.every((id, index) => Number.isSafeInteger(id) && id > 0 && id === persisted[index])
}

async function waitForPersistedTerminal(
  request: APIRequestContext,
  conversationId: string,
  baselineRevision: number,
  clientTurnId: string,
  startedAt: number,
  deadlineAt: number,
): Promise<TerminalObservation> {
  let successfulReads = 0
  do {
    try {
      const response = await request.get(
        `/api/v1/bgg/recommendation-agent/sessions/${encodeURIComponent(conversationId)}`,
      )
      if (response.ok()) {
        successfulReads += 1
        const session = await response.json() as RecommendationSession
        if (!session.processing
          && session.revision > baselineRevision
          && session.latestResponse?.clientTurnId === clientTurnId) {
          return {
            category: session.latestResponse.outcome === 'recommendations'
              ? 'RECOMMENDATIONS'
              : 'NON_RECOMMENDATION',
            session,
            elapsedMs: elapsed(startedAt),
          }
        }
      }
    } catch {
      // A later successful persisted-session read remains authoritative in this bounded window.
    }
    await sleep(250)
  } while (Date.now() <= deadlineAt)

  return {
    category: successfulReads > 0 ? 'SESSION_TIMEOUT' : 'SESSION_READ_FAILURE',
    session: null,
    elapsedMs: elapsed(startedAt),
  }
}

async function waitForRenderedSlate(
  cards: Locator,
  expectedBggIds: number[],
  startedAt: number,
  deadlineAt: number,
): Promise<SlateObservation> {
  let bggIds: number[]
  do {
    bggIds = await cards.evaluateAll(entries => entries.map(card =>
      Number(card.getAttribute('data-bgg-id'))))
    if (sameOrderedBggSlate(bggIds, expectedBggIds)) {
      return { rendered: true, elapsedMs: elapsed(startedAt), bggIds }
    }
    await sleep(250)
  } while (Date.now() <= deadlineAt)
  return { rendered: false, elapsedMs: elapsed(startedAt), bggIds }
}

async function login(page: Page, username: string, password: string) {
  await page.addInitScript(() => localStorage.setItem('rulepilot:locale', 'zh-CN'))
  await page.goto('/login')
  const homeUrl = new URL('/', page.url()).toString()
  await page.locator('input[name="username"]').fill(username)
  await page.locator('input[name="password"]').fill(password)
  await page.locator('form button[type="submit"]').click()
  await expect(page).toHaveURL(homeUrl)
}

async function retainReport(path: string, report: ProductionRecommendationReport) {
  report.generatedAt = new Date().toISOString()
  await writeFile(path, `${JSON.stringify(report, null, 2)}\n`, { mode: 0o600 })
}

test('production returns one persisted player-visible recommendation slate', async ({ page }) => {
  test.skip(!enabled, 'Runs only through the credentialed production recommendation workflow')
  test.setTimeout(5 * 60_000)

  const requestedCardCount = parseRequestedCardCount(
    process.env.RULEPILOT_RECOMMENDATION_EXPECTED_CARD_COUNT,
  )
  const username = process.env.RULEPILOT_RECOMMENDATION_USER
  const password = process.env.RULEPILOT_RECOMMENDATION_PASSWORD
  const reportFile = process.env.RULEPILOT_RECOMMENDATION_REPORT
  if (!username || !password || !reportFile || SELECTION_PROMPT.trim() === '') {
    throw new Error('Production recommendation credentials, prompt, and report path are required')
  }
  if (!/^[0-9a-f]{40}$/.test(TESTED_SHA)
    || !/^[0-9a-f]{40}$/.test(ACTIVE_RELEASE_SHA)
    || TESTED_SHA !== ACTIVE_RELEASE_SHA) {
    throw new Error('Production recommendation verification requires one exact active tested SHA')
  }

  const report: ProductionRecommendationReport = {
    generatedAt: new Date().toISOString(),
    completed: false,
    stage: 'login',
    testedSha: TESTED_SHA,
    activeReleaseSha: ACTIVE_RELEASE_SHA,
    routeStayedOnDiscover: false,
    recommendationRequestedCardCount: requestedCardCount,
    recommendationPersistedCardCount: null,
    recommendationShortfallCount: null,
    recommendationOutcome: null,
    recommendationTerminalCategory: 'NOT_OBSERVED',
    recommendationTerminalObserved: false,
    recommendationFirstProgressMs: null,
    recommendationPersistedTerminalMs: null,
    recommendationRenderedSlateMs: null,
    recommendationElapsedMs: null,
    recommendationSloMet: null,
    recommendationPublishedGames: [],
    recommendationAssistantReplyCharacterCount: null,
    recommendationRenderedReplyCharacterCount: null,
    recommendationCardReplyPartCount: null,
    recommendationCompletedWork: [],
    recommendationModelCalls: null,
    recommendationCatalogCalls: null,
    recommendationWebResearchCalls: null,
    recommendationFailureBoundary: null,
    expectedRecommendationTitleTerm: EXPECTED_TITLE_TERM,
    rawModelOutputCaptured: false,
  }

  try {
    await retainReport(reportFile, report)
    await login(page, username, password)
    report.stage = 'recommendation'
    await page.goto('/discover')

    const cards = page.getByTestId('recommendation-game-card')
    const newConversation = page.getByRole('button', { name: '建立新聊天', exact: true })
    await expect(newConversation).toBeEnabled({ timeout: 60_000 })
    const createdResponsePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname === '/api/v1/bgg/recommendation-agent/sessions'
        && response.request().method() === 'POST'
    }, { timeout: 60_000 })
    await newConversation.click()
    const createdResponse = await createdResponsePromise
    expect(createdResponse.ok(), 'Production could not establish a fresh recommendation session').toBe(true)
    const created = await createdResponse.json() as RecommendationSession
    await expect(cards).toHaveCount(0)

    const composer = page.getByLabel('聊聊你想玩的游戏')
    await composer.fill(SELECTION_PROMPT)
    const requestPromise = page.waitForRequest(request => {
      const url = new URL(request.url())
      return url.pathname === '/api/v1/bgg/recommendation-agent/stream'
        && request.method() === 'POST'
    }, { timeout: 30_000 })
    const startedAt = performance.now()
    const deadlineAt = Date.now() + TERMINAL_OBSERVATION_MS
    const firstProgressPromise = page.getByTestId('recommendation-progress-steps')
      .waitFor({ state: 'visible', timeout: FIRST_PROGRESS_SLO_MS })
      .then(() => elapsed(startedAt), () => null)
    await page.getByRole('button', { name: '发送', exact: true }).click()
    const recommendationRequest = await requestPromise
    const requestBody = recommendationRequest.postDataJSON() as {
      conversationId?: unknown
      revision?: unknown
      clientTurnId?: unknown
    } | null
    expect(requestBody?.conversationId).toBe(created.conversationId)
    expect(requestBody?.revision).toBe(created.revision)
    expect(requestBody?.clientTurnId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    )
    report.recommendationFirstProgressMs = await firstProgressPromise
    expect(report.recommendationFirstProgressMs,
      'The recommendation did not expose causal server progress within 3 seconds').not.toBeNull()

    const clientTurnId = String(requestBody?.clientTurnId)
    const terminal = await waitForPersistedTerminal(
      page.request,
      created.conversationId,
      created.revision,
      clientTurnId,
      startedAt,
      deadlineAt,
    )
    report.recommendationTerminalCategory = terminal.category
    report.recommendationTerminalObserved = terminal.session !== null
    report.recommendationPersistedTerminalMs = terminal.elapsedMs
    report.recommendationElapsedMs = terminal.elapsedMs

    const result = terminal.session?.latestResponse ?? null
    report.recommendationOutcome = result?.outcome ?? null
    report.recommendationFailureBoundary = publicFailureBoundary(result?.failureBoundary)
    report.recommendationModelCalls = publicNonNegativeInteger(result?.modelCalls)
    report.recommendationCatalogCalls = publicNonNegativeInteger(result?.catalogCalls)
    report.recommendationWebResearchCalls = publicNonNegativeInteger(result?.webResearchCalls)
    report.recommendationCompletedWork = Array.isArray(result?.completedWork)
      ? result.completedWork.filter((value): value is string => typeof value === 'string')
      : []
    report.recommendationPublishedGames = result?.games.map(({ game }) => ({
      bggId: game.bggId,
      name: game.name,
      originalName: game.originalName,
    })) ?? []
    report.recommendationAssistantReplyCharacterCount = result
      ? Array.from(result.assistantMessage.trim()).length
      : null
    report.recommendationCardReplyPartCount = result
      ? result.games.reduce((count, game) => count + (game.replyParts?.length ?? 0), 0)
      : null
    report.recommendationPersistedCardCount = result?.outcome === 'recommendations'
      ? result.games.length
      : null
    report.recommendationShortfallCount = report.recommendationPersistedCardCount === null
      ? null
      : Math.max(0, requestedCardCount - report.recommendationPersistedCardCount)
    await retainReport(reportFile, report)

    expect(terminal.category, 'The recommendation did not reach a persisted recommendation terminal')
      .toBe('RECOMMENDATIONS')
    expect(result?.outcome).toBe('recommendations')
    expect(report.recommendationAssistantReplyCharacterCount,
      'The persisted recommendation reply is still only a terse status line').toBeGreaterThanOrEqual(80)
    expect(report.recommendationModelCalls,
      'The fixed fresh recommendation must use one catalog model turn and one terminal model turn')
      .toBe(2)
    expect(report.recommendationCatalogCalls,
      'The fixed fresh recommendation must verify the catalog exactly once').toBe(1)
    expect(report.recommendationWebResearchCalls,
      'The fixed fresh recommendation must not require optional web research').toBe(0)
    expect(hasPositiveDistinctBggIds(result?.games ?? []),
      'Every persisted recommendation needs a positive, distinct BGG identity').toBe(true)
    expect((result?.games ?? []).every(game => (game.replyParts ?? []).some(part =>
      typeof part.text === 'string' && Array.from(part.text.trim()).length >= 12)),
    'Every persisted card needs a substantive model-authored, evidence-bound explanation').toBe(true)
    expect((result?.games ?? []).every(({ game }) => [game.name, game.originalName]
      .some(title => typeof title === 'string' && title.trim().length > 0)),
    'Every persisted recommendation needs a public title').toBe(true)
    expect(everyGameMatchesExpectedTitle(result?.games ?? []),
      `Every persisted recommendation must match expected title term: ${EXPECTED_TITLE_TERM}`)
      .toBe(true)

    report.stage = 'player-visible-slate'
    const persistedBggIds = result!.games.map(entry => entry.game.bggId)
    const slate = await waitForRenderedSlate(cards, persistedBggIds, startedAt, deadlineAt)
    report.recommendationRenderedSlateMs = slate.elapsedMs
    report.recommendationElapsedMs = slate.elapsedMs
    report.recommendationSloMet = slate.rendered && slate.elapsedMs <= INTERACTION_SLO_MS
    await retainReport(reportFile, report)
    expect(slate.rendered,
      'The persisted recommendation did not render the exact ordered card slate').toBe(true)
    expect(report.recommendationSloMet,
      'The fixed fresh recommendation did not render within the 20-second interaction SLO').toBe(true)
    const renderedReply = await page.getByTestId('assistant-recommendation-message').last().innerText()
    report.recommendationRenderedReplyCharacterCount = Array.from(renderedReply.trim()).length
    await retainReport(reportFile, report)
    expect(report.recommendationRenderedReplyCharacterCount,
      'The player-visible recommendation was replaced by a terse summary').toBeGreaterThanOrEqual(80)

    await expect(page).toHaveURL(/\/discover$/)
    report.routeStayedOnDiscover = true
    report.completed = true
    report.stage = 'completed'
  } finally {
    await retainReport(reportFile, report)
  }
})
