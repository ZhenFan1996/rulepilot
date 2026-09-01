import { writeFile } from 'node:fs/promises'

import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'

const enabled = process.env.RULEPILOT_PRODUCTION_RECOMMENDATION_JOURNEY === 'true'
const RECOMMENDATION_OPENING_PROMPT = process.env.RULEPILOT_RECOMMENDATION_OPENING_PROMPT
  ?? '这周末想和朋友开一局，但我完全不知道该玩什么。你会先问我什么，帮我一起缩小范围？'
const RECOMMENDATION_SELECTION_PROMPT = process.env.RULEPILOT_RECOMMENDATION_SELECTION_PROMPT
  ?? '想清楚了：我们三个人，想玩两小时左右的德式重策，最好是 DBG 为主。请直接给我三款，把你最推荐的一款放第一；选好后我们还想找规则书、听讲解。'
const EXPECTED_CANONICAL_MECHANIC = process.env.RULEPILOT_RECOMMENDATION_EXPECTED_MECHANIC
  ?? 'Deck, Bag, and Pool Building'
const MAX_OPEN_GUIDANCE_MS = 15_000
const MAX_SELECTION_RECOMMENDATION_MS = 20_000
const MAX_SELECTION_TERMINAL_MS = 45_000
const EXPECTED_RECOMMENDATION_COUNT = 3
const PRESERVED_DRAFT = '下次我还想给完全没玩过桌游的家人找一款更轻松的。'
const RULE_QUESTION = process.env.RULEPILOT_RECOMMENDATION_RULE_QUESTION
  ?? '我们现在要开第一局：所有组件分别怎么摆、每个人先拿什么？请按顺序说，并标出规则书页码。'
const RULE_FOLLOW_UP = process.env.RULEPILOT_RECOMMENDATION_RULE_FOLLOW_UP
  ?? '你刚才列出的第二个准备步骤具体需要哪些组件？仍然只根据同一本规则书回答并标页码。'
const REQUIRE_FRESH_IMPORT = process.env.RULEPILOT_RECOMMENDATION_REQUIRE_FRESH_IMPORT === 'true'

function bggIdFromBindingPath(pathname: string) {
  const match = /^\/api\/v1\/bgg\/games\/([1-9]\d*)\/import$/.exec(pathname)
  if (!match) throw new Error('Unexpected BGG binding path')
  const value = Number(match[1])
  if (!Number.isSafeInteger(value)) throw new Error('Unsafe BGG identity in binding path')
  return value
}

interface RulebookCandidate {
  title: string
  url: string
  publisher: string
  sourceDomain: string
  language: string
  edition: string
  officialDomainVerified: boolean
  languageVerified: boolean
  sourceType: 'PUBLISHER' | 'TRUSTED_REPOSITORY' | 'COMMUNITY_PLATFORM' | 'PUBLIC_WEB'
  acquisitionMode: 'DIRECT_PDF' | 'IMAGE_GALLERY' | 'SOURCE_PAGE'
  capability: 'DIRECT_DOCUMENT' | 'CONTIGUOUS_RULE_PAGES' | 'DOCUMENT_LISTING' | 'GAME_INFO_ONLY' | 'UNVERIFIED_PAGE'
  capabilityEvidence: string[]
}

interface CandidateResponse {
  configured: boolean
  identity: { editionId: string; gameName: string; editionName: string; language: string }
  candidates: RulebookCandidate[]
}

type RecommendationRecoveryOutcome =
  | 'REUSED_EXISTING_JOURNEY'
  | 'SELECTED_VERIFIED_OFFICIAL_SOURCE'
  | 'SKIPPED_NO_VERIFIED_OFFICIAL_SOURCE'

interface RecommendationRecoveryObservation {
  recommendationRank: number
  bggId: number
  gameName: string
  boundEditionId: string
  outcome: RecommendationRecoveryOutcome
  existingJourneyId: string | null
  discoveryConfigured: boolean | null
  discoveredCandidateCount: number | null
  verifiedCandidateCount: number
  elapsedMs: number
}

interface RecommendationShortfallResponse {
  requestedCount: number
  availableCount: number
}

interface RecommendationResponseGame {
  game: {
    bggId: number
    name: string
    originalName: string
    mechanics?: string[]
  }
  matches: string[]
  tradeoffs: string[]
  fitClaims?: Array<{
    subject: string
    strength: 'hard' | 'soft'
    relation: 'satisfied' | 'conflict' | 'unknown'
    text: string
  }>
  replyParts?: Array<{
    role: string
    subject: string
    text: string
  }>
}

interface RecommendationAgentResponse {
  conversationId: string | null
  revision: number | null
  clientTurnId: string | null
  replayed: boolean
  responseLocale: string
  outcome: string
  assistantMessage: string
  recommendationLead: string | null
  shortfall: RecommendationShortfallResponse | null
  sourceCount: number
  candidatesEvaluated: number
  completedWork: string[]
  games: RecommendationResponseGame[]
  [key: string]: unknown
}

interface RecommendationSessionResponse {
  conversationId: string
  revision: number
  transcript: Array<{ role: 'user' | 'assistant'; text: string }>
  processing: boolean
  processingSince: string | null
  latestResponse: RecommendationAgentResponse | null
}

interface RecommendationTurnRequestIdentity {
  conversationId: string | null
  revision: number | null
  clientTurnId: string | null
}

interface RecommendationCardIdentity {
  rank: number
  bggId: number
  name: string
}

interface RecommendationDomCardIdentity {
  rank: number
  bggId: number | null
  name: string
}

interface RecommendationAcceptanceObservation {
  failedChecks: string[]
  selectionSloMet: boolean
  selectionContractMet: boolean
  continuationSafe: boolean
  projectionMatches: boolean
  hardMechanicFitVerified: boolean
}

interface ImportJob {
  id: string
  title?: string
  rulebookTitle?: string
  editionId: string | null
  sourceDomain?: string
  officialSourceUrl?: string
  stage: 'QUEUED' | 'CONNECTING' | 'DOWNLOADING' | 'COMPRESSING' | 'VERIFYING_FILE' | 'SAVING' | 'COMPLETED' | 'FAILED'
  downloadedBytes: number
  totalBytes: number | null
  documentVersionId: string | null
  duplicate: boolean
  errorCode: string | null
  teachingHandoffState: 'NOT_REQUESTED' | 'WAITING_FOR_DOCUMENT' | 'LAUNCHING' | 'LAUNCHED' | 'FAILED'
  teachingPreparationRunId: string | null
  teachingErrorCode: string | null
  teachingAutomaticRecoveryCount: number
  downloadCompletedAt: string | null
  importCompletedAt: string | null
  teachingHandoffUpdatedAt: string | null
  createdAt: string
  reused: boolean
}

interface BoundGameResponse {
  game: { id: string; name: string }
  edition: { id: string; name: string; language: string }
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

interface ConversationTurnResponse extends AnswerResponse {
  id: string
  question: string
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
    createdAt: string
    lastErrorCode: string | null
  }
  activities: Array<{
    operation: string
    outcome: string
    summary: string
    occurredAt: string
  }>
}

interface LessonMilestoneResponse {
  id: string
  teachingPlanId: string
  status: 'COMPLETE' | 'DRAFT_READY' | 'INCOMPLETE'
  sections: Array<{
    position?: number
    title?: string
    evidenceStatus: 'SUPPORTED' | 'CITED_DRAFT' | 'INSUFFICIENT_EVIDENCE'
  }>
}

interface ImportMilestoneObservation {
  job: ImportJob
  pdfDownloadCompleteMs: number | null
  documentReadyMs: number | null
  teachingHandoffLaunchedMs: number | null
}

interface CsrfToken {
  headerName: string
  token: string
}

interface PrivateAgentTraceStatus {
  state: 'ACTIVE' | 'SEALED'
  integrity: 'COMPLETE' | 'INCOMPLETE'
  incompleteReason: string
  eventCount: number
  storedBytes: number
}

interface ModelConfigurationResponse {
  providers: Array<{
    id: string
    configured: boolean
    visionCapable: boolean
  }>
  assignments: {
    teaching: string
    visual: string
    answer: string
    critic: string
    recommendation: string
  }
}

interface FirstCitedLessonObservation {
  planId: string
  teachingPreparationStartedMs: number
  firstCitedLessonMs: number
  preparationRunCreatedAt: string | null
  firstCitedPublicationActivityAt: string | null
}

interface CompletedLessonObservation {
  teachingRunState: string
  lessonStatus: LessonMilestoneResponse['status']
  sectionCount: number
  citedDraftSectionCount: number
  insufficientSectionCount: number
}

interface TeachingWaitProgress {
  phase: 'FIRST_CITED_SECTION' | 'COMPLETE_LESSON'
  observedAt: string
  preparationState: string | null
  preparationOperation: string | null
  preparationErrorCode: string | null
  planId: string | null
  teachingState: string | null
  teachingOperation: string | null
  teachingErrorCode: string | null
  lessonStatus: LessonMilestoneResponse['status'] | null
  sectionCount: number
  publishedSectionCount: number
  citedDraftSectionCount: number
  insufficientSectionCount: number
  teachingHandoffState: ImportJob['teachingHandoffState'] | null
  teachingHandoffErrorCode: string | null
  teachingAutomaticRecoveryCount: number | null
}

interface ProductionJourneyReport {
  generatedAt: string
  completed: boolean
  journeyReachedEnd: boolean
  stage: string
  failedChecks: string[]
  privateAgentTraceStarted: boolean
  privateAgentTraceState: PrivateAgentTraceStatus['state'] | null
  privateAgentTraceIntegrity: PrivateAgentTraceStatus['integrity'] | null
  privateAgentTraceIncompleteReason: string | null
  privateAgentTraceEventCount: number
  privateAgentTraceStoredBytes: number
  privateAgentTraceExported: boolean
  privateAgentTraceDeletedAfterExport: boolean
  selectedBggId: number | null
  selectedGameName: string | null
  recommendationConversationId: string | null
  recommendationOpeningPrompt: string
  recommendationSelectionPrompt: string
  recommendationExpectedMechanic: string
  recommendationTranscript: Array<{ role: 'user' | 'assistant'; text: string }>
  openGuidanceOutcome: string | null
  recommendationExpectedRevision: number | null
  recommendationPersistedRevision: number | null
  recommendationClientTurnId: string | null
  recommendationTerminalObserved: boolean
  recommendationTerminalSource: 'PERSISTED_SESSION' | null
  recommendationTerminalObservedMs: number | null
  recommendationProcessingSince: string | null
  recommendationSelectionRequestCount: number
  recommendationSloMs: number
  recommendationHardDeadlineMs: number
  recommendationSloMet: boolean | null
  recommendationExactCardinalityWithinSlo: boolean | null
  recommendationCompletedAfterSlo: boolean | null
  recommendationContractMet: boolean
  recommendationResultUsable: boolean
  recommendationHardMechanicFitVerified: boolean
  recommendationTextReviewRequired: boolean
  recommendationRequestedCount: number
  recommendationStructuredCardCount: number
  recommendationOrderedCards: RecommendationCardIdentity[]
  recommendationDomCards: RecommendationDomCardIdentity[]
  recommendationDomProjectionMs: number | null
  recommendationAssistantMessage: string | null
  recommendationLead: string | null
  recommendationShortfall: RecommendationShortfallResponse | null
  recommendationStructuredResponse: RecommendationAgentResponse | null
  modelAssignments: ModelConfigurationResponse['assignments'] | null
  visualModelVisionCapable: boolean | null
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
  documentVersionId: string | null
  teachingPlanId: string | null
  answerSessionId: string | null
  firstAnswerTurnId: string | null
  followUpAnswerTurnId: string | null
  candidateEditionMatchesSelection: boolean
  importEditionMatchesSelection: boolean
  documentEditionMatchesSelection: boolean
  myGuidesEntryVisibleBeforeLesson: boolean
  myGuidesPlanListed: boolean
  planGameTitleMatchesSelection: boolean
  globalStatusVisibleAfterClosing: boolean
  globalStatusReopened: boolean
  openGuidanceMs: number | null
  recommendationMs: number | null
  recommendationCardCount: number
  attemptedBggIds: number[]
  selectedRecommendationRank: number | null
  recommendationRecoveryOutcomes: RecommendationRecoveryObservation[]
  detailsDialogOpenedAndClosed: boolean
  discoveryMs: number | null
  sourceDomain: string | null
  sourceUrl: string | null
  sourceMode: string | null
  sourcePublisher: string | null
  sourceEdition: string | null
  sourceLanguage: string | null
  sourceLanguageVerified: boolean | null
  sourceOfficialDomainVerified: boolean | null
  sourceCapabilityEvidence: string[]
  importRequestCount: number
  importReused: boolean | null
  teachingEvidenceRefreshRequested: boolean
  importDuplicate: boolean | null
  downloadedBytes: number | null
  importMs: number | null
  downloadCompletedAt: string | null
  importCompletedAt: string | null
  teachingHandoffUpdatedAt: string | null
  persistedDownloadToImportCompleteMs: number | null
  persistedImportCompleteToHandoffMs: number | null
  persistedDownloadToHandoffMs: number | null
  preparationRunCreatedAt: string | null
  firstCitedPublicationActivityAt: string | null
  persistedHandoffToPreparationRunMs: number | null
  persistedPreparationToFirstCitedActivityMs: number | null
  persistedDownloadToFirstCitedActivityMs: number | null
  pdfDownloadCompleteMs: number | null
  documentReadyMs: number | null
  teachingHandoffLaunchedMs: number | null
  teachingPreparationStartedMs: number | null
  firstCitedLessonMs: number | null
  pdfDownloadToTeachingStartMs: number | null
  pdfDownloadToFirstCitedLessonMs: number | null
  documentProgressStage: string | null
  documentProgressComplete: boolean | null
  teachingHandoffState: string | null
  teachingHandoffErrorCode: string | null
  teachingAutomaticRecoveryCount: number | null
  teachingPreparationState: string | null
  teachingPreparationErrorCode: string | null
  teachingGenerationState: string | null
  teachingProgressObservedAt: string | null
  teachingObservedPlanId: string | null
  teachingLatestPreparationOperation: string | null
  teachingLatestGenerationOperation: string | null
  teachingPublishedSectionCount: number
  lessonStatus: string | null
  citedDraftSectionCount: number
  insufficientSectionCount: number
  lessonCompletionMs: number | null
  rulebookReadableMs: number | null
  renderedRulebookPage: boolean
  lessonReadableMs: number | null
  lessonDockText: string | null
  lessonSectionCount: number
  citedLessonStep: boolean
  answerMs: number | null
  answerStatus: string | null
  answerCitationCount: number
  citedAnswer: boolean
  followUpAnswerMs: number | null
  followUpAnswerStatus: string | null
  followUpCitationCount: number
  answerSessionTurnCount: number
  answerSessionPreserved: boolean
  recommendationRestored: boolean
  answerRestored: boolean
  pageErrorCount: number
}

function elapsed(startedAt: number) {
  return Math.round(performance.now() - startedAt)
}

function recordFailedCheck(report: ProductionJourneyReport, check: string) {
  if (!report.failedChecks.includes(check)) report.failedChecks.push(check)
}

function orderedRecommendationCards(response: RecommendationAgentResponse): RecommendationCardIdentity[] {
  return response.games.map((entry, index) => ({
    rank: index + 1,
    bggId: entry.game.bggId,
    name: entry.game.name.trim(),
  }))
}

async function recommendationDomCards(cards: Locator): Promise<RecommendationDomCardIdentity[]> {
  return await cards.evaluateAll(elements => elements.map((element, index) => {
    const rawBggId = element.getAttribute('data-bgg-id')
    const bggId = rawBggId === null ? Number.NaN : Number(rawBggId)
    return {
      rank: index + 1,
      bggId: Number.isSafeInteger(bggId) && bggId > 0 ? bggId : null,
      name: element.querySelector('h3')?.textContent?.trim() ?? '',
    }
  }))
}

function recommendationProjectionMatches(
  structuredCards: RecommendationCardIdentity[],
  domCards: RecommendationDomCardIdentity[],
) {
  return structuredCards.length === domCards.length
    && structuredCards.every((card, index) => {
      const projected = domCards[index]
      return projected?.rank === card.rank
        && projected.bggId === card.bggId
        && projected.name === card.name
    })
}

function recommendationAcceptance(
  response: RecommendationAgentResponse,
  domCards: RecommendationDomCardIdentity[],
  domProjectionMs: number,
  selectionRequestCount: number,
): RecommendationAcceptanceObservation {
  const structuredCards = orderedRecommendationCards(response)
  const bggIds = structuredCards.map(card => card.bggId)
  const uniqueBggIds = bggIds.every(id => Number.isSafeInteger(id) && id > 0)
    && new Set(bggIds).size === bggIds.length
  const exactCardinality = structuredCards.length === EXPECTED_RECOMMENDATION_COUNT
  const projectionMatches = recommendationProjectionMatches(structuredCards, domCards)
  const hardMechanicFitVerified = response.games.length > 0
    && response.games.every(entry =>
      entry.game.mechanics?.includes(EXPECTED_CANONICAL_MECHANIC)
      && entry.fitClaims?.some(claim =>
        claim.subject === 'mechanics'
        && claim.strength === 'hard'
        && claim.relation === 'satisfied'))
  const shortfallMatches = exactCardinality
    ? response.shortfall === null
    : Boolean(structuredCards.length > 0
      && structuredCards.length < EXPECTED_RECOMMENDATION_COUNT
      && response.shortfall?.requestedCount === EXPECTED_RECOMMENDATION_COUNT
      && response.shortfall.availableCount === structuredCards.length)
  const recommendationOutcome = response.outcome === 'recommendations'
  const selectionSloMet = domProjectionMs <= MAX_SELECTION_RECOMMENDATION_MS
  const failedChecks: string[] = []
  if (!selectionSloMet) failedChecks.push('SELECTION_SLO_EXCEEDED')
  if (!recommendationOutcome) failedChecks.push('RECOMMENDATION_TERMINAL_OUTCOME_INVALID')
  if (!exactCardinality) failedChecks.push('RECOMMENDATION_CARDINALITY_MISMATCH')
  if (!uniqueBggIds) failedChecks.push('RECOMMENDATION_DUPLICATE_BGG_IDENTITY')
  if (!projectionMatches) failedChecks.push('RECOMMENDATION_UI_PROJECTION_MISMATCH')
  if (!hardMechanicFitVerified) failedChecks.push('RECOMMENDATION_HARD_MECHANIC_FIT_UNVERIFIED')
  if (!shortfallMatches) failedChecks.push('RECOMMENDATION_SHORTFALL_MISMATCH')
  if (selectionRequestCount !== 1) failedChecks.push('RECOMMENDATION_TURN_REPOSTED')
  return {
    failedChecks,
    selectionSloMet,
    selectionContractMet:
      recommendationOutcome && exactCardinality && uniqueBggIds && projectionMatches
        && hardMechanicFitVerified && shortfallMatches,
    continuationSafe:
      recommendationOutcome && structuredCards.length > 0 && uniqueBggIds && projectionMatches
        && hardMechanicFitVerified && shortfallMatches,
    projectionMatches,
    hardMechanicFitVerified,
  }
}

function recommendationResponseFixture(
  bggIds: number[],
  shortfall: RecommendationShortfallResponse | null = null,
): RecommendationAgentResponse {
  return {
    conversationId: '00000000-0000-4000-8000-000000000001',
    revision: 2,
    clientTurnId: '00000000-0000-4000-8000-000000000002',
    replayed: true,
    responseLocale: 'zh-CN',
    outcome: 'recommendations',
    assistantMessage: '已按当前条件整理候选。',
    recommendationLead: '按顺序查看这些候选。',
    shortfall,
    sourceCount: bggIds.length,
    candidatesEvaluated: bggIds.length,
    completedWork: ['recommend_games'],
    games: bggIds.map(bggId => ({
      game: {
        bggId,
        name: `Game ${bggId}`,
        originalName: `Game ${bggId}`,
        mechanics: [EXPECTED_CANONICAL_MECHANIC],
      },
      matches: [],
      tradeoffs: [],
      fitClaims: [{
        subject: 'mechanics',
        strength: 'hard',
        relation: 'satisfied',
        text: 'The canonical mechanic criterion is satisfied.',
      }],
    })),
  }
}

async function waitForPersistedRecommendationTerminal(
  request: APIRequestContext,
  conversationId: string,
  clientTurnId: string,
  expectedRevision: number,
  startedAt: number,
  deadlineAt: number,
) {
  let latestSession: RecommendationSessionResponse | null = null
  let latestStatus: number | null = null
  let latestRequestFailed = false
  while (Date.now() < deadlineAt) {
    const response = await request.get(
      `/api/v1/bgg/recommendation-agent/sessions/${encodeURIComponent(conversationId)}`,
      { timeout: Math.max(1, Math.min(5_000, deadlineAt - Date.now())) },
    ).catch(() => {
      latestRequestFailed = true
      return null
    })
    if (response) {
      latestStatus = response.status()
      latestRequestFailed = false
      if (response.ok()) {
        const session = await response.json() as RecommendationSessionResponse
        if (session.conversationId !== conversationId) {
          throw new Error('Recommendation reconciliation returned another conversation identity')
        }
        latestSession = session
        const terminal = session.latestResponse
        if (!session.processing
          && session.revision > expectedRevision
          && terminal?.clientTurnId === clientTurnId) {
          return { session, observedMs: elapsed(startedAt) }
        }
        if (!session.processing
          && session.revision > expectedRevision
          && terminal?.clientTurnId
          && terminal.clientTurnId !== clientTurnId) {
          throw new Error('Recommendation reconciliation advanced to another client turn')
        }
      }
    }
    const remainingMs = deadlineAt - Date.now()
    if (remainingMs > 0) {
      await new Promise(resolve => setTimeout(resolve, Math.min(500, remainingMs)))
    }
  }
  throw new Error(
    `Recommendation turn did not reach its persisted terminal state within ${MAX_SELECTION_TERMINAL_MS} ms; `
    + `latestStatus=${latestStatus ?? 'none'}, latestSession=${JSON.stringify(latestSession && {
      revision: latestSession.revision,
      processing: latestSession.processing,
      latestOutcome: latestSession.latestResponse?.outcome ?? null,
    })}, latestRequestFailed=${latestRequestFailed}`,
  )
}

async function waitForRecommendationDomProjection(
  cards: Locator,
  structuredCards: RecommendationCardIdentity[],
  startedAt: number,
  deadlineAt: number,
) {
  let latest = await recommendationDomCards(cards)
  while (Date.now() < deadlineAt && !recommendationProjectionMatches(structuredCards, latest)) {
    await new Promise(resolve => setTimeout(resolve, 100))
    latest = await recommendationDomCards(cards)
  }
  return { cards: latest, observedMs: elapsed(startedAt) }
}

function persistedDuration(from: string | null, to: string | null) {
  if (!from || !to) return null
  const startedAt = Date.parse(from)
  const reachedAt = Date.parse(to)
  if (!Number.isFinite(startedAt) || !Number.isFinite(reachedAt) || reachedAt < startedAt) return null
  return Math.round(reachedAt - startedAt)
}

function configuredProductionRole(
  configuration: ModelConfigurationResponse,
  role: 'teaching' | 'visual' | 'answer' | 'recommendation',
) {
  const assignment = configuration.assignments[role]
  expect(assignment, `Production ${role} role has no model assignment`).not.toBe('fake')
  const provider = configuration.providers.find(candidate => candidate.id === assignment)
  expect(provider?.configured, `Production ${role} provider '${assignment}' is not configured`).toBe(true)
  return provider!
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

async function csrfToken(request: APIRequestContext) {
  const response = await request.get('/api/auth/csrf')
  expect(response.ok(), `CSRF token returned HTTP ${response.status()}`).toBe(true)
  return await response.json() as CsrfToken
}

async function finalizePrivateAgentTrace(
  request: APIRequestContext,
  traceExportFile: string,
  report: ProductionJourneyReport,
) {
  const traceCsrf = await csrfToken(request)
  const sealResponse = await request.post('/api/v1/private-agent-trace/seal', {
    headers: { [traceCsrf.headerName]: traceCsrf.token },
  })
  if (!sealResponse.ok()) {
    throw new Error(`Private Agent trace seal returned HTTP ${sealResponse.status()}`)
  }
  const sealed = await sealResponse.json() as PrivateAgentTraceStatus
  report.privateAgentTraceState = sealed.state
  report.privateAgentTraceIntegrity = sealed.integrity
  report.privateAgentTraceIncompleteReason = sealed.incompleteReason || null
  report.privateAgentTraceEventCount = sealed.eventCount
  report.privateAgentTraceStoredBytes = sealed.storedBytes
  if (sealed.state !== 'SEALED' || sealed.integrity !== 'COMPLETE') {
    throw new Error(
      `Private Agent trace sealed incompletely: state=${sealed.state}; integrity=${sealed.integrity}; reason=${sealed.incompleteReason ?? 'NONE'}`,
    )
  }
  const exportResponse = await request.get('/api/v1/private-agent-trace/export')
  if (!exportResponse.ok()) {
    throw new Error(`Private Agent trace export returned HTTP ${exportResponse.status()}`)
  }
  const traceBytes = await exportResponse.body()
  if (traceBytes.length === 0) throw new Error('Private Agent trace export was empty')
  await writeFile(traceExportFile, traceBytes, { mode: 0o600 })
  report.privateAgentTraceExported = true
  const deleteResponse = await request.delete('/api/v1/private-agent-trace', {
    headers: { [traceCsrf.headerName]: traceCsrf.token },
  })
  if (!deleteResponse.ok()) {
    throw new Error(`Private Agent trace cleanup returned HTTP ${deleteResponse.status()}`)
  }
  report.privateAgentTraceDeletedAfterExport = true
}

function observedDownloadComplete(job: ImportJob) {
  if (job.downloadedBytes <= 0) return false
  if (job.totalBytes !== null) return job.downloadedBytes >= job.totalBytes
  return job.stage !== 'QUEUED' && job.stage !== 'CONNECTING' && job.stage !== 'DOWNLOADING'
}

async function waitForCompletedImport(
  request: APIRequestContext,
  jobId: string,
  importStartedAt: number,
): Promise<ImportMilestoneObservation> {
  const deadline = Date.now() + 8 * 60_000
  let latest: ImportJob | null = null
  let pdfDownloadCompleteMs: number | null = null
  let documentReadyMs: number | null = null
  let teachingHandoffLaunchedMs: number | null = null
  while (Date.now() < deadline) {
    const response = await request.get(`/api/v1/documents/official-imports/${encodeURIComponent(jobId)}`)
    expect(response.ok(), `Import progress returned HTTP ${response.status()}`).toBe(true)
    latest = await response.json() as ImportJob
    if (pdfDownloadCompleteMs === null && observedDownloadComplete(latest)) {
      pdfDownloadCompleteMs = elapsed(importStartedAt)
    }
    if (latest.documentVersionId && documentReadyMs === null) {
      const progressResponse = await request.get(
        `/api/v1/document-versions/${encodeURIComponent(latest.documentVersionId)}/progress/snapshot`,
      )
      expect([200, 404], `Document progress returned HTTP ${progressResponse.status()}`)
        .toContain(progressResponse.status())
      if (progressResponse.ok()) {
        const progress = await progressResponse.json() as DocumentProgressResponse
        if (progress.stage === 'READY' && progress.complete) documentReadyMs = elapsed(importStartedAt)
      }
    }
    if (teachingHandoffLaunchedMs === null && latest.teachingHandoffState === 'LAUNCHED') {
      teachingHandoffLaunchedMs = elapsed(importStartedAt)
    }
    if (latest.stage === 'COMPLETED' && latest.teachingHandoffState === 'LAUNCHED') {
      return { job: latest, pdfDownloadCompleteMs, documentReadyMs, teachingHandoffLaunchedMs }
    }
    if (latest.teachingHandoffState === 'FAILED') {
      throw new Error(`Teaching handoff failed with ${latest.teachingErrorCode ?? 'UNKNOWN_TEACHING_HANDOFF_ERROR'}`)
    }
    if (latest.stage === 'FAILED') {
      throw new Error(`Official import failed with ${latest.errorCode ?? 'UNKNOWN_IMPORT_ERROR'}`)
    }
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error(`Official import did not complete; latest stage was ${latest?.stage ?? 'UNKNOWN'}`)
}

async function retryFailedReusedTeaching(
  request: APIRequestContext,
  job: ImportJob,
): Promise<ImportJob> {
  if (!job.reused || !job.teachingPreparationRunId) return job

  const runResponse = await request.get(
    `/api/v1/assistant-runs/${encodeURIComponent(job.teachingPreparationRunId)}`,
  )
  expect([200, 404], `Existing teaching preparation returned HTTP ${runResponse.status()}`)
    .toContain(runResponse.status())
  if (!runResponse.ok()) return job

  const existing = await runResponse.json() as RunDetailsResponse
  if (existing.run.state !== 'FAILED') return job

  const csrfResponse = await request.get('/api/auth/csrf')
  expect(csrfResponse.ok(), `CSRF token returned HTTP ${csrfResponse.status()}`).toBe(true)
  const csrf = await csrfResponse.json() as CsrfToken
  const retryResponse = await request.post(
    `/api/v1/documents/official-imports/${encodeURIComponent(job.id)}/teaching-retry`,
    {
      headers: { [csrf.headerName]: csrf.token },
      data: { expectedPreparationRunId: job.teachingPreparationRunId },
    },
  )
  expect(retryResponse.status(), 'The failed reused teaching preparation was not accepted for retry')
    .toBe(202)
  const retried = await retryResponse.json() as ImportJob
  expect(retried.id, 'Teaching retry changed the persisted import identity').toBe(job.id)
  return retried
}

async function waitForFirstCitedLesson(
  request: APIRequestContext,
  importJobId: string,
  versionId: string,
  initialPreparationRunId: string,
  importStartedAt: number,
  requireCurrentPublicationActivity: boolean,
  currentHandoffAt: string | null,
  onProgress?: (progress: TeachingWaitProgress) => Promise<void> | void,
): Promise<FirstCitedLessonObservation> {
  const deadline = Date.now() + 20 * 60_000
  let plan: TeachingPlanResponse | null = null
  let teachingPreparationStartedMs: number | null = null
  let preparationRunCreatedAt: string | null = null
  let firstCitedPublicationActivityAt: string | null = null
  let preparationDetails: RunDetailsResponse | null = null
  let teachingDetails: RunDetailsResponse | null = null
  let latestLesson: LessonMilestoneResponse | null = null
  let preparationRunId = initialPreparationRunId
  let terminalObservedAt: number | null = null
  let terminalGenerationKey = ''
  const progress = teachingProgressReporter(onProgress)
  while (Date.now() < deadline) {
    const importResponse = await request.get(
      `/api/v1/documents/official-imports/${encodeURIComponent(importJobId)}`,
    )
    expect(importResponse.ok(), `Import reconciliation returned HTTP ${importResponse.status()}`).toBe(true)
    const currentJob = await importResponse.json() as ImportJob
    if (currentJob.teachingPreparationRunId) preparationRunId = currentJob.teachingPreparationRunId
    const runResponse = await request.get(`/api/v1/assistant-runs/${encodeURIComponent(preparationRunId)}`)
    expect([200, 404], `Teaching preparation returned HTTP ${runResponse.status()}`)
      .toContain(runResponse.status())
    if (runResponse.ok()) {
      const details = await runResponse.json() as RunDetailsResponse
      preparationDetails = details
      preparationRunCreatedAt = details.run.createdAt
      if (details.run.state !== 'RECEIVED' && teachingPreparationStartedMs === null) {
        teachingPreparationStartedMs = elapsed(importStartedAt)
      }
    }
    if (!plan) {
      const planResponse = await request.get(
        `/api/v1/document-versions/${encodeURIComponent(versionId)}/teaching-plans/latest`,
      )
      expect([200, 404], `Teaching plan returned HTTP ${planResponse.status()}`)
        .toContain(planResponse.status())
      if (planResponse.ok()) {
        const receivedPlan = await planResponse.json() as TeachingPlanResponse
        expect(receivedPlan.documentVersionId,
          'The latest teaching plan changed the imported document version identity').toBe(versionId)
        plan = receivedPlan
      }
    }
    if (plan) {
      const teachingRunResponse = await request.get(
        `/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(plan.id)}`,
      )
      expect([200, 404], `Teaching run returned HTTP ${teachingRunResponse.status()}`)
        .toContain(teachingRunResponse.status())
      if (teachingRunResponse.ok()) {
        teachingDetails = await teachingRunResponse.json() as RunDetailsResponse
        const handoffTimestamp = currentHandoffAt ? Date.parse(currentHandoffAt) : Number.NEGATIVE_INFINITY
        const firstPublication = teachingDetails.activities
          .filter(activity =>
            activity.operation.startsWith('publishTeachingSection|')
            && activity.outcome === 'SUCCEEDED'
            && (activity.summary.includes('CITED_BASE_SECTION_PUBLISHED')
              || activity.summary.includes('CITED_DRAFT_PUBLISHED')
              || activity.summary.includes('REUSED_VERIFIED_SECTION'))
            && (!requireCurrentPublicationActivity
              || Date.parse(activity.occurredAt) >= handoffTimestamp))
          .sort((left, right) => Date.parse(left.occurredAt) - Date.parse(right.occurredAt))[0]
        if (firstPublication) firstCitedPublicationActivityAt = firstPublication.occurredAt
      }
      const lessonResponse = await request.get(
        `/api/v1/teaching-plans/${encodeURIComponent(plan.id)}/illustrated-lessons/latest`,
      )
      expect([200, 404], `Illustrated lesson returned HTTP ${lessonResponse.status()}`)
        .toContain(lessonResponse.status())
      if (lessonResponse.ok()) {
        const lesson = await lessonResponse.json() as LessonMilestoneResponse
        expect(lesson.teachingPlanId,
          'The illustrated lesson changed the teaching plan identity').toBe(plan.id)
        latestLesson = lesson
        if (lesson.sections.some(section =>
          section.evidenceStatus === 'SUPPORTED' || section.evidenceStatus === 'CITED_DRAFT')
          && (!requireCurrentPublicationActivity || firstCitedPublicationActivityAt !== null)) {
          await progress.emit(teachingWaitProgress(
            'FIRST_CITED_SECTION', preparationDetails, plan.id, teachingDetails, latestLesson, currentJob,
          ))
          return {
            planId: plan.id,
            teachingPreparationStartedMs: teachingPreparationStartedMs ?? elapsed(importStartedAt),
            firstCitedLessonMs: elapsed(importStartedAt),
            preparationRunCreatedAt,
            firstCitedPublicationActivityAt,
          }
        }
      }
    }
    const terminalTeaching = teachingDetails
      && ['FAILED', 'DEGRADED', 'INSUFFICIENT_EVIDENCE'].includes(teachingDetails.run.state)
    const nextTerminalGenerationKey = terminalTeaching
      ? `${teachingDetails?.run.createdAt}:${teachingDetails?.run.state}`
      : ''
    if (nextTerminalGenerationKey !== terminalGenerationKey) {
      terminalGenerationKey = nextTerminalGenerationKey
      terminalObservedAt = terminalTeaching ? Date.now() : null
    }
    await progress.emit(teachingWaitProgress(
      'FIRST_CITED_SECTION', preparationDetails, plan?.id ?? null, teachingDetails, latestLesson, currentJob,
    ))
    if (currentJob.teachingHandoffState === 'FAILED') {
      throw new Error(
        `Teaching recovery ended with ${currentJob.teachingErrorCode ?? 'UNKNOWN_TEACHING_HANDOFF_ERROR'}; ${teachingFailureDiagnostic(currentJob, teachingDetails, latestLesson)}`,
      )
    }
    if (terminalObservedAt !== null && Date.now() - terminalObservedAt >= 15_000) {
      throw new Error(
        `Teaching reached a terminal state but its persisted recovery did not advance within 15 seconds; ${teachingFailureDiagnostic(currentJob, teachingDetails, latestLesson)}`,
      )
    }
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error(
    `The first source-cited lesson section did not become readable; ${teachingProgressSummary(progress.latest())}`,
  )
}

function unfinishedSectionSummary(lesson: LessonMilestoneResponse) {
  const unfinished = lesson.sections.filter(section => section.evidenceStatus !== 'SUPPORTED')
  if (unfinished.length === 0) return 'no unfinished section details were returned'
  const counts = unfinished.reduce<Map<string, number>>((statuses, section) => {
    statuses.set(section.evidenceStatus, (statuses.get(section.evidenceStatus) ?? 0) + 1)
    return statuses
  }, new Map())
  return [...counts.entries()]
    .map(([status, count]) => `${status}=${count}`)
    .join(', ')
}

async function waitForCompletedLesson(
  request: APIRequestContext,
  planId: string,
  onProgress?: (progress: TeachingWaitProgress) => Promise<void> | void,
): Promise<CompletedLessonObservation> {
  const deadline = Date.now() + 20 * 60_000
  let latestRunState = 'NOT_STARTED'
  let latestRunError: string | null = null
  let latestRunDetails: RunDetailsResponse | null = null
  let latestLesson: LessonMilestoneResponse | null = null
  const progress = teachingProgressReporter(onProgress)
  while (Date.now() < deadline) {
    const runResponse = await request.get(
      `/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(planId)}`,
    )
    expect([200, 404], `Teaching run returned HTTP ${runResponse.status()}`)
      .toContain(runResponse.status())
    if (runResponse.ok()) {
      const details = await runResponse.json() as RunDetailsResponse
      latestRunDetails = details
      latestRunState = details.run.state
      latestRunError = details.run.lastErrorCode
      if (['FAILED', 'DEGRADED', 'INSUFFICIENT_EVIDENCE'].includes(latestRunState)) {
        const latestFailure = details.activities
          .filter(activity => activity.outcome !== 'SUCCEEDED')
          .sort((left, right) => Date.parse(right.occurredAt) - Date.parse(left.occurredAt))[0]
        const diagnostic = latestFailure
          ? `${latestFailure.operation}: ${latestFailure.summary}`
          : latestRunError ?? 'no failure detail was recorded'
        throw new Error(`Teaching generation ended as ${latestRunState}: ${diagnostic}`)
      }
    }

    const lessonResponse = await request.get(
      `/api/v1/teaching-plans/${encodeURIComponent(planId)}/illustrated-lessons/latest`,
    )
    expect([200, 404], `Illustrated lesson returned HTTP ${lessonResponse.status()}`)
      .toContain(lessonResponse.status())
    if (lessonResponse.ok()) {
      latestLesson = await lessonResponse.json() as LessonMilestoneResponse
      expect(latestLesson.teachingPlanId,
        'The completed illustrated lesson changed the teaching plan identity').toBe(planId)
    }

    await progress.emit(teachingWaitProgress(
      'COMPLETE_LESSON', null, planId, latestRunDetails, latestLesson,
    ))

    if (latestLesson && latestRunState === 'COMPLETED') {
      const citedDraftSectionCount = latestLesson.sections
        .filter(section => section.evidenceStatus === 'CITED_DRAFT').length
      const insufficientSectionCount = latestLesson.sections
        .filter(section => section.evidenceStatus === 'INSUFFICIENT_EVIDENCE').length
      const everySectionSupported = latestLesson.sections.length > 0
        && latestLesson.sections.every(section => section.evidenceStatus === 'SUPPORTED')
      if (latestLesson.status === 'COMPLETE' && everySectionSupported) {
        return {
          teachingRunState: latestRunState,
          lessonStatus: latestLesson.status,
          sectionCount: latestLesson.sections.length,
          citedDraftSectionCount,
          insufficientSectionCount,
        }
      }
      throw new Error(
        `Teaching run completed without a complete lesson: lesson=${latestLesson.status}; ${unfinishedSectionSummary(latestLesson)}`,
      )
    }
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error(
    `Teaching lesson did not complete; run=${latestRunState}; lesson=${latestLesson?.status ?? 'NOT_PUBLISHED'}; error=${latestRunError ?? 'NONE'}; ${latestLesson ? unfinishedSectionSummary(latestLesson) : 'no lesson was published'}; ${teachingProgressSummary(progress.latest())}`,
  )
}

function teachingWaitProgress(
  phase: TeachingWaitProgress['phase'],
  preparation: RunDetailsResponse | null,
  planId: string | null,
  teaching: RunDetailsResponse | null,
  lesson: LessonMilestoneResponse | null,
  handoff: ImportJob | null = null,
): TeachingWaitProgress {
  const sections = lesson?.sections ?? []
  return {
    phase,
    observedAt: new Date().toISOString(),
    preparationState: preparation?.run.state ?? null,
    preparationOperation: preparation?.activities.at(-1)?.operation ?? null,
    preparationErrorCode: preparation?.run.lastErrorCode ?? null,
    planId,
    teachingState: teaching?.run.state ?? null,
    teachingOperation: teaching?.activities.at(-1)?.operation ?? null,
    teachingErrorCode: teaching?.run.lastErrorCode ?? null,
    lessonStatus: lesson?.status ?? null,
    sectionCount: sections.length,
    publishedSectionCount: sections.filter(section =>
      section.evidenceStatus === 'SUPPORTED' || section.evidenceStatus === 'CITED_DRAFT').length,
    citedDraftSectionCount: sections.filter(section => section.evidenceStatus === 'CITED_DRAFT').length,
    insufficientSectionCount: sections.filter(section => section.evidenceStatus === 'INSUFFICIENT_EVIDENCE').length,
    teachingHandoffState: handoff?.teachingHandoffState ?? null,
    teachingHandoffErrorCode: handoff?.teachingErrorCode ?? null,
    teachingAutomaticRecoveryCount: handoff?.teachingAutomaticRecoveryCount ?? null,
  }
}

function teachingFailureDiagnostic(
  job: ImportJob,
  teaching: RunDetailsResponse | null,
  lesson: LessonMilestoneResponse | null,
) {
  const sections = lesson?.sections ?? []
  const published = sections.filter(section =>
    section.evidenceStatus === 'SUPPORTED' || section.evidenceStatus === 'CITED_DRAFT').length
  const insufficient = sections.filter(section => section.evidenceStatus === 'INSUFFICIENT_EVIDENCE').length
  const latestFailure = teaching?.activities
    .filter(activity => activity.outcome !== 'SUCCEEDED')
    .sort((left, right) => Date.parse(right.occurredAt) - Date.parse(left.occurredAt))[0]
  const latest = latestFailure
    ? `${latestFailure.operation}: ${teaching?.run.lastErrorCode ?? 'ACTIVITY_REJECTED'}`
    : teaching?.run.lastErrorCode ?? 'no rejected activity was recorded'
  return `attempt ${Math.min(2, job.teachingAutomaticRecoveryCount + 1)} of 2; chapters published=${published}, insufficient=${insufficient}, total=${sections.length}; latest=${latest}`
}

function teachingProgressSummary(progress: TeachingWaitProgress | null) {
  if (!progress) return 'progress=UNAVAILABLE'
  return [
    `phase=${progress.phase}`,
    `preparation=${progress.preparationState ?? 'NONE'}`,
    `teaching=${progress.teachingState ?? 'NONE'}`,
    `lesson=${progress.lessonStatus ?? 'NONE'}`,
    `sections=${progress.sectionCount}`,
    `published=${progress.publishedSectionCount}`,
    `insufficient=${progress.insufficientSectionCount}`,
    `handoff=${progress.teachingHandoffState ?? 'NONE'}`,
  ].join(', ')
}

function teachingProgressReporter(
  onProgress?: (progress: TeachingWaitProgress) => Promise<void> | void,
) {
  let fingerprint = ''
  let loggedAt = 0
  let last: TeachingWaitProgress | null = null
  return {
    async emit(progress: TeachingWaitProgress) {
      const nextFingerprint = JSON.stringify({ ...progress, observedAt: undefined })
      const now = Date.now()
      if (nextFingerprint === fingerprint && now - loggedAt < 30_000) return
      fingerprint = nextFingerprint
      loggedAt = now
      last = progress
      console.log(`[production-teaching-progress] ${teachingProgressSummary(progress)}`)
      await onProgress?.(progress)
    },
    latest() {
      return last
    },
  }
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

function requiresPersistedPublicationActivity(
  report: Pick<ProductionJourneyReport, 'importReused' | 'teachingEvidenceRefreshRequested'>,
) {
  return !report.importReused || report.teachingEvidenceRefreshRequested
}

function teachingEvidenceWasRefreshed(initial: ImportJob, current: ImportJob) {
  if (!initial.reused) return false
  if (initial.teachingPreparationRunId === null) {
    return initial.teachingHandoffState === 'WAITING_FOR_DOCUMENT'
      || initial.teachingHandoffState === 'LAUNCHING'
  }
  return current.teachingPreparationRunId !== initial.teachingPreparationRunId
}

function isImportableCandidate(candidate: RulebookCandidate) {
  const capabilityVerified = candidate.capability === 'DIRECT_DOCUMENT'
    && candidate.acquisitionMode === 'DIRECT_PDF'
    && candidate.capabilityEvidence.includes('DOCUMENT_RESPONSE_CONFIRMED')
    || candidate.capability === 'CONTIGUOUS_RULE_PAGES'
      && candidate.acquisitionMode === 'IMAGE_GALLERY'
      && candidate.capabilityEvidence.includes('ORDERED_PAGE_SEQUENCE_CONFIRMED')
  return capabilityVerified
    && candidate.officialDomainVerified === true
    && candidate.languageVerified === true
    && candidate.publisher.trim().length > 0
    && candidate.edition.trim().length > 0
}

function isUsableExistingJourney(job: ImportJob, editionId: string) {
  if (job.editionId !== editionId || job.stage === 'FAILED' || job.teachingHandoffState === 'NOT_REQUESTED') {
    return false
  }
  if (job.teachingHandoffState !== 'FAILED') return true
  return job.stage === 'COMPLETED'
    && job.documentVersionId !== null
    && job.teachingPreparationRunId !== null
}

type RecommendationRecoveryDecision =
  | { outcome: 'REUSED_EXISTING_JOURNEY'; existingJourney: ImportJob; candidate: null }
  | { outcome: 'SELECTED_VERIFIED_OFFICIAL_SOURCE'; existingJourney: null; candidate: RulebookCandidate }
  | { outcome: 'SKIPPED_NO_VERIFIED_OFFICIAL_SOURCE'; existingJourney: null; candidate: null }

function chooseRecommendationRecovery(
  editionId: string,
  existingJourneys: ImportJob[],
  candidates: RulebookCandidate[],
): RecommendationRecoveryDecision {
  const restoredJourney = existingJourneys.find(job =>
    job.editionId === editionId && job.teachingHandoffState !== 'NOT_REQUESTED')
  if (restoredJourney && isUsableExistingJourney(restoredJourney, editionId)) {
    return { outcome: 'REUSED_EXISTING_JOURNEY', existingJourney: restoredJourney, candidate: null }
  }
  const candidate = candidates.find(isImportableCandidate)
  if (candidate) {
    return { outcome: 'SELECTED_VERIFIED_OFFICIAL_SOURCE', existingJourney: null, candidate }
  }
  return { outcome: 'SKIPPED_NO_VERIFIED_OFFICIAL_SOURCE', existingJourney: null, candidate: null }
}

test('requires current publication telemetry for fresh imports and stale-evidence refreshes', () => {
  expect(requiresPersistedPublicationActivity({
    importReused: false,
    teachingEvidenceRefreshRequested: false,
  })).toBe(true)
  expect(requiresPersistedPublicationActivity({
    importReused: true,
    teachingEvidenceRefreshRequested: true,
  })).toBe(true)
  expect(requiresPersistedPublicationActivity({
    importReused: true,
    teachingEvidenceRefreshRequested: false,
  })).toBe(false)

  const initial = {
    reused: true,
    teachingHandoffState: 'LAUNCHED',
    teachingPreparationRunId: 'old-run',
  } as ImportJob
  expect(teachingEvidenceWasRefreshed(initial, {
    ...initial,
    teachingPreparationRunId: 'current-run',
  })).toBe(true)
  expect(teachingEvidenceWasRefreshed(initial, initial)).toBe(false)
})

test('dynamic recommendation binding accepts only the exact BGG import route', () => {
  expect(bggIdFromBindingPath('/api/v1/bgg/games/4174/import')).toBe(4174)
  expect(() => bggIdFromBindingPath('/api/v1/bgg/games/4174/details')).toThrow('Unexpected BGG binding path')
  expect(() => bggIdFromBindingPath('/api/v1/bgg/games/not-a-number/import'))
    .toThrow('Unexpected BGG binding path')
})

test('recommendation acceptance records a late terminal without discarding its safe cards', () => {
  const response = recommendationResponseFixture([101, 102, 103])
  const cards = response.games.map((entry, index) => ({
    rank: index + 1,
    bggId: entry.game.bggId,
    name: entry.game.name,
  }))

  expect(recommendationAcceptance(response, cards, 22_125, 1)).toEqual({
    failedChecks: ['SELECTION_SLO_EXCEEDED'],
    selectionSloMet: false,
    selectionContractMet: true,
    continuationSafe: true,
    projectionMatches: true,
    hardMechanicFitVerified: true,
  })
})

test('recommendation acceptance continues an honest shortfall but cannot report the three-card contract as met', () => {
  const response = recommendationResponseFixture(
    [201, 202],
    { requestedCount: EXPECTED_RECOMMENDATION_COUNT, availableCount: 2 },
  )
  const cards = response.games.map((entry, index) => ({
    rank: index + 1,
    bggId: entry.game.bggId,
    name: entry.game.name,
  }))

  expect(recommendationAcceptance(response, cards, 12_000, 1)).toEqual({
    failedChecks: ['RECOMMENDATION_CARDINALITY_MISMATCH'],
    selectionSloMet: true,
    selectionContractMet: false,
    continuationSafe: true,
    projectionMatches: true,
    hardMechanicFitVerified: true,
  })
})

test('recommendation acceptance rejects a partial slate without matching structured shortfall', () => {
  const response = recommendationResponseFixture([251, 252])
  const cards = response.games.map((entry, index) => ({
    rank: index + 1,
    bggId: entry.game.bggId,
    name: entry.game.name,
  }))

  expect(recommendationAcceptance(response, cards, 12_000, 1)).toMatchObject({
    failedChecks: [
      'RECOMMENDATION_CARDINALITY_MISMATCH',
      'RECOMMENDATION_SHORTFALL_MISMATCH',
    ],
    selectionContractMet: false,
    continuationSafe: false,
  })
})

test('recommendation acceptance stops before binding when the DOM changes structured card identity', () => {
  const response = recommendationResponseFixture([301, 302, 303])
  const cards = response.games.map((entry, index) => ({
    rank: index + 1,
    bggId: index === 1 ? 999 : entry.game.bggId,
    name: entry.game.name,
  }))

  expect(recommendationAcceptance(response, cards, 8_000, 1)).toMatchObject({
    failedChecks: ['RECOMMENDATION_UI_PROJECTION_MISMATCH'],
    selectionContractMet: false,
    continuationSafe: false,
    projectionMatches: false,
    hardMechanicFitVerified: true,
  })
})

test('recommendation acceptance rejects cards without a structured satisfied hard mechanic claim', () => {
  const response = recommendationResponseFixture([401, 402, 403])
  response.games[1]!.fitClaims = []
  const cards = response.games.map((entry, index) => ({
    rank: index + 1,
    bggId: entry.game.bggId,
    name: entry.game.name,
  }))

  expect(recommendationAcceptance(response, cards, 8_000, 1)).toMatchObject({
    failedChecks: ['RECOMMENDATION_HARD_MECHANIC_FIT_UNVERIFIED'],
    selectionContractMet: false,
    continuationSafe: false,
    hardMechanicFitVerified: false,
  })
})

test('recommendation acceptance rejects a satisfied mechanic claim for the wrong canonical BGG value', () => {
  const response = recommendationResponseFixture([411, 412, 413])
  response.games[1]!.game.mechanics = ['Worker Placement']
  const cards = response.games.map((entry, index) => ({
    rank: index + 1,
    bggId: entry.game.bggId,
    name: entry.game.name,
  }))

  expect(recommendationAcceptance(response, cards, 8_000, 1)).toMatchObject({
    failedChecks: ['RECOMMENDATION_HARD_MECHANIC_FIT_UNVERIFIED'],
    selectionContractMet: false,
    continuationSafe: false,
    hardMechanicFitVerified: false,
  })
})

test('automation confirms only a verified official source with explicit edition and language identity', () => {
  const verified: RulebookCandidate = {
    title: 'Rules',
    url: 'https://publisher.example/rules.pdf',
    publisher: 'Publisher',
    sourceDomain: 'publisher.example',
    language: 'en',
    edition: 'First edition',
    officialDomainVerified: true,
    languageVerified: true,
    sourceType: 'PUBLISHER',
    acquisitionMode: 'DIRECT_PDF',
    capability: 'DIRECT_DOCUMENT',
    capabilityEvidence: ['DOCUMENT_RESPONSE_CONFIRMED'],
  }
  expect(isImportableCandidate(verified)).toBe(true)
  expect(isImportableCandidate({ ...verified, officialDomainVerified: false })).toBe(false)
  expect(isImportableCandidate({ ...verified, languageVerified: false })).toBe(false)
  expect(isImportableCandidate({ ...verified, edition: '' })).toBe(false)
  expect(isImportableCandidate({ ...verified, capabilityEvidence: ['CANDIDATE_ONLY'] })).toBe(false)
})

test('ranked recovery prefers a usable journey, accepts a verified source, and rejects unsafe dead ends', () => {
  const verified: RulebookCandidate = {
    title: 'Rules',
    url: 'https://publisher.example/rules.pdf',
    publisher: 'Publisher',
    sourceDomain: 'publisher.example',
    language: 'en',
    edition: 'First edition',
    officialDomainVerified: true,
    languageVerified: true,
    sourceType: 'PUBLISHER',
    acquisitionMode: 'DIRECT_PDF',
    capability: 'DIRECT_DOCUMENT',
    capabilityEvidence: ['DOCUMENT_RESPONSE_CONFIRMED'],
  }
  const existing = {
    id: 'existing-journey',
    editionId: 'edition-1',
    stage: 'COMPLETED',
    teachingHandoffState: 'LAUNCHED',
  } as ImportJob

  expect(chooseRecommendationRecovery('edition-1', [existing], [verified])).toMatchObject({
    outcome: 'REUSED_EXISTING_JOURNEY',
    existingJourney: { id: 'existing-journey' },
    candidate: null,
  })
  expect(chooseRecommendationRecovery('edition-1', [], [verified])).toMatchObject({
    outcome: 'SELECTED_VERIFIED_OFFICIAL_SOURCE',
    existingJourney: null,
    candidate: { url: verified.url },
  })

  const retryableTeaching = {
    ...existing,
    documentVersionId: 'document-1',
    teachingHandoffState: 'FAILED',
    teachingPreparationRunId: 'failed-run',
  } as ImportJob
  expect(chooseRecommendationRecovery('edition-1', [retryableTeaching], [])).toMatchObject({
    outcome: 'REUSED_EXISTING_JOURNEY',
    existingJourney: { id: 'existing-journey' },
  })

  const failedExisting = { ...existing, stage: 'FAILED' } as ImportJob
  const unverified = { ...verified, officialDomainVerified: false }
  expect(chooseRecommendationRecovery('edition-1', [failedExisting], [unverified])).toEqual({
    outcome: 'SKIPPED_NO_VERIFIED_OFFICIAL_SOURCE',
    existingJourney: null,
    candidate: null,
  })
  expect(chooseRecommendationRecovery('edition-1', [failedExisting, existing], [verified])).toMatchObject({
    outcome: 'SELECTED_VERIFIED_OFFICIAL_SOURCE',
    existingJourney: null,
    candidate: { url: verified.url },
  })
  expect(chooseRecommendationRecovery('edition-2', [existing], [])).toEqual({
    outcome: 'SKIPPED_NO_VERIFIED_OFFICIAL_SOURCE',
    existingJourney: null,
    candidate: null,
  })
})

test('recommendation becomes one readable, taught, and answerable production journey', async ({ page }) => {
  test.skip(!enabled, 'Runs only through the credentialed production recommendation workflow')
  test.setTimeout(40 * 60_000)
  const username = process.env.RULEPILOT_RECOMMENDATION_USER
  const password = process.env.RULEPILOT_RECOMMENDATION_PASSWORD
  const reportFile = process.env.RULEPILOT_RECOMMENDATION_REPORT
  const traceExportFile = process.env.RULEPILOT_AGENT_TRACE_EXPORT
  if (!username || !password || !reportFile || !traceExportFile) {
    throw new Error('Production recommendation credentials, report path, and trace export path are required')
  }

  const pageErrors: Error[] = []
  const recommendationStreamRequests: RecommendationTurnRequestIdentity[] = []
  let guidesPage: Page | null = null
  let importRequestCount = 0
  let observedDocumentVersionId: string | null = null
  let observedPreparationRunId: string | null = null
  let observedTeachingPlanId: string | null = null
  let observedImportRequest: {
    editionId?: string
    discoveredForEditionId?: string
    officialSourceUrl?: string
    identityConfirmed?: boolean
  } | null = null
  let privateTraceStarted = false
  let privateTraceFinalizeError: Error | null = null
  page.on('pageerror', error => pageErrors.push(error))
  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/bgg/recommendation-agent/stream' && request.method() === 'POST') {
      const body = request.postDataJSON() as Partial<RecommendationTurnRequestIdentity> | null
      recommendationStreamRequests.push({
        conversationId: typeof body?.conversationId === 'string' ? body.conversationId : null,
        revision: Number.isSafeInteger(body?.revision) ? Number(body?.revision) : null,
        clientTurnId: typeof body?.clientTurnId === 'string' ? body.clientTurnId : null,
      })
    }
    if (path === '/api/v1/documents/official-imports' && request.method() === 'POST') {
      importRequestCount += 1
      observedImportRequest = request.postDataJSON() as typeof observedImportRequest
    }
  })

  const report: ProductionJourneyReport = {
    generatedAt: new Date().toISOString(), completed: false, journeyReachedEnd: false,
    stage: 'login', failedChecks: [],
    privateAgentTraceStarted: false, privateAgentTraceState: null,
    privateAgentTraceIntegrity: null, privateAgentTraceIncompleteReason: null,
    privateAgentTraceEventCount: 0, privateAgentTraceStoredBytes: 0,
    privateAgentTraceExported: false, privateAgentTraceDeletedAfterExport: false,
    selectedBggId: null, selectedGameName: null,
    recommendationConversationId: null,
    recommendationOpeningPrompt: RECOMMENDATION_OPENING_PROMPT,
    recommendationSelectionPrompt: RECOMMENDATION_SELECTION_PROMPT,
    recommendationExpectedMechanic: EXPECTED_CANONICAL_MECHANIC,
    recommendationTranscript: [], openGuidanceOutcome: null,
    recommendationExpectedRevision: null, recommendationPersistedRevision: null,
    recommendationClientTurnId: null, recommendationTerminalObserved: false,
    recommendationTerminalSource: null, recommendationTerminalObservedMs: null,
    recommendationProcessingSince: null, recommendationSelectionRequestCount: 0,
    recommendationSloMs: MAX_SELECTION_RECOMMENDATION_MS,
    recommendationHardDeadlineMs: MAX_SELECTION_TERMINAL_MS,
    recommendationSloMet: null, recommendationExactCardinalityWithinSlo: null,
    recommendationCompletedAfterSlo: null,
    recommendationContractMet: false, recommendationResultUsable: false,
    recommendationHardMechanicFitVerified: false,
    recommendationTextReviewRequired: false,
    recommendationRequestedCount: EXPECTED_RECOMMENDATION_COUNT,
    recommendationStructuredCardCount: 0, recommendationOrderedCards: [],
    recommendationDomCards: [], recommendationDomProjectionMs: null,
    recommendationAssistantMessage: null, recommendationLead: null,
    recommendationShortfall: null, recommendationStructuredResponse: null,
    modelAssignments: null, visualModelVisionCapable: null,
    routeStayedOnDiscover: false, journeyBackdropVisible: false, journeySurfaceOpaque: false,
    lessonBackdropVisible: false, lessonSurfaceOpaque: false,
    confirmedMilestonesAtSourceReview: 0, confirmedMilestonesFinal: 0,
    boundGameInCatalog: false, boundBggId: null, boundGameName: null, boundEditionId: null,
    documentVersionId: null, teachingPlanId: null, answerSessionId: null,
    firstAnswerTurnId: null, followUpAnswerTurnId: null,
    candidateEditionMatchesSelection: false, importEditionMatchesSelection: false,
    documentEditionMatchesSelection: false, myGuidesEntryVisibleBeforeLesson: false, myGuidesPlanListed: false,
    planGameTitleMatchesSelection: false, globalStatusVisibleAfterClosing: false, globalStatusReopened: false,
    openGuidanceMs: null, recommendationMs: null, recommendationCardCount: 0,
    attemptedBggIds: [], selectedRecommendationRank: null, recommendationRecoveryOutcomes: [],
    detailsDialogOpenedAndClosed: false,
    discoveryMs: null, sourceDomain: null, sourceUrl: null, sourceMode: null,
    sourcePublisher: null, sourceEdition: null, sourceLanguage: null,
    sourceLanguageVerified: null, sourceOfficialDomainVerified: null, sourceCapabilityEvidence: [],
    importRequestCount: 0,
    importReused: null, teachingEvidenceRefreshRequested: false,
    importDuplicate: null, downloadedBytes: null, importMs: null,
    downloadCompletedAt: null, importCompletedAt: null, teachingHandoffUpdatedAt: null,
    persistedDownloadToImportCompleteMs: null, persistedImportCompleteToHandoffMs: null,
    persistedDownloadToHandoffMs: null,
    preparationRunCreatedAt: null, firstCitedPublicationActivityAt: null,
    persistedHandoffToPreparationRunMs: null, persistedPreparationToFirstCitedActivityMs: null,
    persistedDownloadToFirstCitedActivityMs: null,
    pdfDownloadCompleteMs: null, documentReadyMs: null, teachingHandoffLaunchedMs: null,
    teachingPreparationStartedMs: null, firstCitedLessonMs: null,
    pdfDownloadToTeachingStartMs: null, pdfDownloadToFirstCitedLessonMs: null,
    documentProgressStage: null, documentProgressComplete: null, teachingHandoffState: null,
    teachingHandoffErrorCode: null,
    teachingAutomaticRecoveryCount: null,
    teachingPreparationState: null, teachingPreparationErrorCode: null,
    teachingGenerationState: null, teachingProgressObservedAt: null, teachingObservedPlanId: null,
    teachingLatestPreparationOperation: null, teachingLatestGenerationOperation: null,
    teachingPublishedSectionCount: 0,
    lessonStatus: null, citedDraftSectionCount: 0,
    insufficientSectionCount: 0, lessonCompletionMs: null,
    rulebookReadableMs: null, renderedRulebookPage: false, lessonReadableMs: null,
    lessonDockText: null,
    lessonSectionCount: 0, citedLessonStep: false, answerMs: null, answerStatus: null,
    answerCitationCount: 0, citedAnswer: false, followUpAnswerMs: null, followUpAnswerStatus: null,
    followUpCitationCount: 0, answerSessionTurnCount: 0, answerSessionPreserved: false,
    recommendationRestored: false, answerRestored: false, pageErrorCount: 0,
  }

  const recordTeachingProgress = async (progress: TeachingWaitProgress) => {
    report.stage = progress.phase === 'FIRST_CITED_SECTION'
      ? 'teaching-first-cited-section'
      : 'teaching-complete-lesson'
    report.teachingProgressObservedAt = progress.observedAt
    report.teachingObservedPlanId = progress.planId
    if (progress.preparationState) report.teachingPreparationState = progress.preparationState
    if (progress.preparationErrorCode) report.teachingPreparationErrorCode = progress.preparationErrorCode
    if (progress.preparationOperation) {
      report.teachingLatestPreparationOperation = progress.preparationOperation
    }
    if (progress.teachingState) report.teachingGenerationState = progress.teachingState
    if (progress.teachingHandoffState) report.teachingHandoffState = progress.teachingHandoffState
    if (progress.teachingHandoffErrorCode) report.teachingHandoffErrorCode = progress.teachingHandoffErrorCode
    if (progress.teachingAutomaticRecoveryCount !== null) {
      report.teachingAutomaticRecoveryCount = progress.teachingAutomaticRecoveryCount
    }
    if (progress.teachingOperation) report.teachingLatestGenerationOperation = progress.teachingOperation
    if (progress.lessonStatus) report.lessonStatus = progress.lessonStatus
    report.lessonSectionCount = progress.sectionCount
    report.teachingPublishedSectionCount = progress.publishedSectionCount
    report.citedDraftSectionCount = progress.citedDraftSectionCount
    report.insufficientSectionCount = progress.insufficientSectionCount
    report.generatedAt = new Date().toISOString()
    await retainReport(reportFile, report)
  }

  try {
    await login(page, username, password)
    report.stage = 'private-trace-start'
    const traceCsrf = await csrfToken(page.request)
    const traceStartResponse = await page.request.post('/api/v1/private-agent-trace/start', {
      headers: { [traceCsrf.headerName]: traceCsrf.token },
    })
    expect(traceStartResponse.status(), 'Private Agent trace could not start').toBe(201)
    const traceStart = await traceStartResponse.json() as PrivateAgentTraceStatus
    expect(traceStart.state).toBe('ACTIVE')
    expect(traceStart.integrity).toBe('COMPLETE')
    privateTraceStarted = true
    report.privateAgentTraceStarted = true
    report.privateAgentTraceState = traceStart.state
    report.privateAgentTraceIntegrity = traceStart.integrity
    report.privateAgentTraceIncompleteReason = traceStart.incompleteReason || null
    report.privateAgentTraceEventCount = traceStart.eventCount
    report.privateAgentTraceStoredBytes = traceStart.storedBytes
    report.stage = 'model-role-preflight'
    const modelConfigurationResponse = await page.request.get('/api/v1/model-configuration')
    expect(modelConfigurationResponse.ok(),
      `Model configuration returned HTTP ${modelConfigurationResponse.status()}`).toBe(true)
    const modelConfiguration = await modelConfigurationResponse.json() as ModelConfigurationResponse
    configuredProductionRole(modelConfiguration, 'recommendation')
    configuredProductionRole(modelConfiguration, 'teaching')
    const visualProvider = configuredProductionRole(modelConfiguration, 'visual')
    configuredProductionRole(modelConfiguration, 'answer')
    expect(visualProvider.visionCapable,
      `Production visual provider '${visualProvider.id}' cannot inspect rulebook page images`).toBe(true)
    report.modelAssignments = modelConfiguration.assignments
    report.visualModelVisionCapable = visualProvider.visionCapable
    await retainReport(reportFile, report)
    report.stage = 'recommendation'
    await page.goto('/discover')
    const recommendationCards = page.getByTestId('recommendation-game-card')
    const newConversation = page.getByRole('button', { name: '建立新聊天', exact: true })
    await expect(newConversation).toBeEnabled({ timeout: 60_000 })
    const newConversationResponse = page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname === '/api/v1/bgg/recommendation-agent/sessions'
        && response.request().method() === 'POST'
    }, { timeout: 60_000 })
    await newConversation.click()
    const createdConversationResponse = await newConversationResponse
    expect(createdConversationResponse.ok(), 'Production could not establish a fresh recommendation session')
      .toBe(true)
    const createdConversation = await createdConversationResponse.json() as RecommendationSessionResponse
    report.recommendationConversationId = createdConversation.conversationId
    await expect(recommendationCards).toHaveCount(0)
    const composer = page.getByLabel('聊聊你想玩的游戏')
    const guidanceTurnCount = await page.getByTestId('assistant-conversation-turn').count()
    const guidanceStartedAt = performance.now()
    await composer.fill(RECOMMENDATION_OPENING_PROMPT)
    await page.getByRole('button', { name: '发送', exact: true }).click()
    await expect.poll(() => page.getByTestId('assistant-conversation-turn').count(), {
      timeout: MAX_OPEN_GUIDANCE_MS,
      message: 'The unknown-target opening did not produce a natural guidance turn',
    }).toBeGreaterThan(guidanceTurnCount)
    report.openGuidanceMs = elapsed(guidanceStartedAt)
    expect(report.openGuidanceMs, 'Open recommendation guidance exceeded its interaction budget')
      .toBeLessThanOrEqual(MAX_OPEN_GUIDANCE_MS)
    await expect(page.getByTestId('assistant-conversation-turn').last()).toContainText(/\S/)
    await expect(recommendationCards).toHaveCount(0)
    const guidanceSessionResponse = await page.request.get(
      `/api/v1/bgg/recommendation-agent/sessions/${encodeURIComponent(createdConversation.conversationId)}`,
    )
    expect(guidanceSessionResponse.ok(),
      `Recommendation session returned HTTP ${guidanceSessionResponse.status()}`).toBe(true)
    const guidanceSession = await guidanceSessionResponse.json() as RecommendationSessionResponse
    report.openGuidanceOutcome = guidanceSession.latestResponse?.outcome ?? null
    expect(['conversation', 'needs_clarification'],
      'The unknown-target opening did not finish as useful guidance').toContain(report.openGuidanceOutcome)

    const recommendationStartedAt = performance.now()
    const recommendationDeadlineAt = Date.now() + MAX_SELECTION_TERMINAL_MS
    const recommendationRequestPromise = page.waitForRequest(request => {
      const url = new URL(request.url())
      return url.pathname === '/api/v1/bgg/recommendation-agent/stream'
        && request.method() === 'POST'
    }, { timeout: MAX_SELECTION_TERMINAL_MS })
    await composer.fill(RECOMMENDATION_SELECTION_PROMPT)
    await page.getByRole('button', { name: '发送', exact: true }).click()
    const recommendationThreeCardSloObservation
      = expect(recommendationCards).toHaveCount(3, { timeout: MAX_SELECTION_RECOMMENDATION_MS })
        .then(() => true, () => false)
    const recommendationRequest = await recommendationRequestPromise.catch(error => {
      const requestFailedMs = elapsed(recommendationStartedAt)
      report.recommendationMs = requestFailedMs
      if (requestFailedMs > MAX_SELECTION_RECOMMENDATION_MS) {
        report.recommendationSloMet = false
        report.recommendationCompletedAfterSlo = true
        recordFailedCheck(report, 'SELECTION_SLO_EXCEEDED')
      }
      recordFailedCheck(report, 'RECOMMENDATION_REQUEST_NOT_OBSERVED')
      throw error
    })
    const recommendationRequestBody
      = recommendationRequest.postDataJSON() as Partial<RecommendationTurnRequestIdentity> | null
    const recommendationClientTurnId = typeof recommendationRequestBody?.clientTurnId === 'string'
      ? recommendationRequestBody.clientTurnId
      : null
    const recommendationExpectedRevision = Number.isSafeInteger(recommendationRequestBody?.revision)
      ? Number(recommendationRequestBody?.revision)
      : null
    report.recommendationClientTurnId = recommendationClientTurnId
    report.recommendationExpectedRevision = recommendationExpectedRevision
    if (recommendationRequestBody?.conversationId !== createdConversation.conversationId
      || recommendationExpectedRevision !== guidanceSession.revision
      || !recommendationClientTurnId) {
      recordFailedCheck(report, 'RECOMMENDATION_REQUEST_IDENTITY_INVALID')
      throw new Error('Recommendation selection request did not preserve its conversation, revision, and client turn identity')
    }

    let recommendationTerminal: Awaited<ReturnType<typeof waitForPersistedRecommendationTerminal>>
    try {
      recommendationTerminal = await waitForPersistedRecommendationTerminal(
        page.request,
        createdConversation.conversationId,
        recommendationClientTurnId,
        recommendationExpectedRevision,
        recommendationStartedAt,
        recommendationDeadlineAt,
      )
    } catch (error) {
      const reconciliationFailedMs = elapsed(recommendationStartedAt)
      report.recommendationMs = reconciliationFailedMs
      if (reconciliationFailedMs > MAX_SELECTION_RECOMMENDATION_MS) {
        report.recommendationSloMet = false
        report.recommendationCompletedAfterSlo = true
        recordFailedCheck(report, 'SELECTION_SLO_EXCEEDED')
      }
      recordFailedCheck(report, 'RECOMMENDATION_TERMINAL_RECONCILIATION_FAILED')
      throw error
    }

    const recommendationSession = recommendationTerminal.session
    const recommendationResponse = recommendationSession.latestResponse
    if (!recommendationResponse
      || recommendationResponse.conversationId !== createdConversation.conversationId
      || recommendationResponse.revision !== recommendationSession.revision
      || recommendationResponse.clientTurnId !== recommendationClientTurnId) {
      recordFailedCheck(report, 'RECOMMENDATION_TERMINAL_IDENTITY_INVALID')
      throw new Error('Persisted recommendation terminal response changed the conversation, revision, or client turn identity')
    }
    report.recommendationTerminalObserved = true
    report.recommendationTerminalSource = 'PERSISTED_SESSION'
    report.recommendationTerminalObservedMs = recommendationTerminal.observedMs
    report.recommendationPersistedRevision = recommendationSession.revision
    report.recommendationProcessingSince = recommendationSession.processingSince
    report.recommendationTranscript = recommendationSession.transcript.map(turn => ({ ...turn }))
    report.recommendationStructuredResponse = recommendationResponse
    report.recommendationAssistantMessage = recommendationResponse.assistantMessage
    report.recommendationLead = recommendationResponse.recommendationLead
    report.recommendationShortfall = recommendationResponse.shortfall
    report.recommendationOrderedCards = orderedRecommendationCards(recommendationResponse)
    report.recommendationStructuredCardCount = report.recommendationOrderedCards.length
    report.recommendationTextReviewRequired
      = report.recommendationStructuredCardCount !== EXPECTED_RECOMMENDATION_COUNT
        || recommendationResponse.shortfall !== null

    const projection = await waitForRecommendationDomProjection(
      recommendationCards,
      report.recommendationOrderedCards,
      recommendationStartedAt,
      recommendationDeadlineAt,
    )
    report.recommendationDomCards = projection.cards
    report.recommendationDomProjectionMs = projection.observedMs
    report.recommendationMs = projection.observedMs
    report.recommendationCardCount = projection.cards.length
    report.recommendationExactCardinalityWithinSlo
      = report.recommendationStructuredCardCount === EXPECTED_RECOMMENDATION_COUNT
        ? await recommendationThreeCardSloObservation
        : false
    report.recommendationSelectionRequestCount = recommendationStreamRequests
      .filter(request => request.clientTurnId === recommendationClientTurnId).length
    const recommendationAssessment = recommendationAcceptance(
      recommendationResponse,
      projection.cards,
      projection.observedMs,
      report.recommendationSelectionRequestCount,
    )
    recommendationAssessment.failedChecks.forEach(check => recordFailedCheck(report, check))
    report.recommendationSloMet = recommendationAssessment.selectionSloMet
    report.recommendationCompletedAfterSlo = !recommendationAssessment.selectionSloMet
    report.recommendationContractMet = recommendationAssessment.selectionContractMet
    report.recommendationResultUsable = recommendationAssessment.continuationSafe
    report.recommendationHardMechanicFitVerified = recommendationAssessment.hardMechanicFitVerified
    if (report.recommendationSloMet) {
      expect(
        report.recommendationMs,
        'A recommendation reported within the SLO must retain its measured projection latency',
      ).toBeLessThanOrEqual(MAX_SELECTION_RECOMMENDATION_MS)
    }
    await retainReport(reportFile, report)
    if (!recommendationAssessment.continuationSafe) {
      throw new Error(
        `Persisted recommendation result is unsafe to continue: ${JSON.stringify(recommendationAssessment.failedChecks)}`,
      )
    }
    await composer.fill(PRESERVED_DRAFT)
    type SelectedRecommendationJourney = {
      recommendationRank: number
      selectedCardBggId: number
      selectedGameName: string
      selectedDetailsButton: Locator
      boundGame: BoundGameResponse
      existingJourney: ImportJob | null
      importableCandidate: RulebookCandidate | null
      discoveryMs: number
    }
    let selectedJourney: SelectedRecommendationJourney | null = null

    const recommendationAttemptLimit = Math.min(report.recommendationCardCount, 3)
    for (let cardIndex = 0; cardIndex < recommendationAttemptLimit; cardIndex += 1) {
      const recommendationRank = cardIndex + 1
      const attemptStartedAt = performance.now()
      const recommendedCard = recommendationCards.nth(cardIndex)
      const attemptedGameName = (await recommendedCard.locator('h3').innerText()).trim()
      expect(attemptedGameName,
        `Agent recommendation rank ${recommendationRank} has no player-visible identity`).not.toBe('')
      const attemptedBggId = Number(await recommendedCard.getAttribute('data-bgg-id'))
      expect(Number.isSafeInteger(attemptedBggId) && attemptedBggId > 0,
        `Agent recommendation rank ${recommendationRank} has no typed BGG identity`).toBe(true)
      expect(report.attemptedBggIds,
        'The Agent recommendation slate repeated a BGG identity').not.toContain(attemptedBggId)
      report.attemptedBggIds.push(attemptedBggId)
      const attemptedDetailsButton = recommendedCard.getByRole('button', {
        name: `查看完整资料：${attemptedGameName}`,
        exact: true,
      })

      if (recommendationRank === 1) {
        report.stage = 'details-dialog'
        await attemptedDetailsButton.click()
        const preview = page.getByRole('dialog', { name: '桌游详细资料' })
        await expect(preview.getByRole('heading', { name: attemptedGameName, exact: true }))
          .toBeVisible({ timeout: 60_000 })
        await expect(page).toHaveURL(/\/discover$/)
        await preview.getByRole('button', { name: '关闭桌游资料' }).click()
        await expect(preview).toBeHidden()
        report.detailsDialogOpenedAndClosed = true
      }

      report.stage = `rulebook-recovery-rank-${recommendationRank}`
      const discoveryStartedAt = performance.now()
      const candidatesResponsePromise = page.waitForResponse(response => {
        const url = new URL(response.url())
        return url.pathname === '/api/v1/documents/rulebook-candidates' && response.ok()
      }, { timeout: 90_000 }).catch(() => null)
      const existingJourneyResponsePromise = page.waitForResponse(response => {
        const url = new URL(response.url())
        return url.pathname === '/api/v1/documents/official-imports'
          && response.request().method() === 'GET'
          && response.ok()
      }, { timeout: 90_000 })
      const bindingResponsePromise = page.waitForResponse(response => {
        const url = new URL(response.url())
        return url.pathname === `/api/v1/bgg/games/${attemptedBggId}/import`
          && response.request().method() === 'POST'
          && response.ok()
      }, { timeout: 90_000 })
      await attemptedDetailsButton.click()
      const details = page.getByRole('dialog', { name: '桌游详细资料' })
      await expect(details.getByRole('heading', { name: attemptedGameName, exact: true }))
        .toBeVisible({ timeout: 60_000 })
      await details.getByRole('button', { name: '选这款，继续找规则书' }).click()
      const [existingJourneyResponse, bindingResponse] = await Promise.all([
        existingJourneyResponsePromise,
        bindingResponsePromise,
      ])
      const attemptedBoundGame = await bindingResponse.json() as BoundGameResponse
      const boundBggId = bggIdFromBindingPath(new URL(bindingResponse.url()).pathname)
      expect(boundBggId, 'The binding route changed the attempted Agent-ranked BGG identity')
        .toBe(attemptedBggId)
      expect(attemptedBoundGame.bggId,
        'The binding response did not preserve the attempted Agent-ranked BGG identity').toBe(attemptedBggId)
      expect(attemptedBoundGame.game.name.trim(), 'The bound catalog game has no stable title').not.toBe('')

      const existingJourneys = await existingJourneyResponse.json() as ImportJob[]
      const restorableJourney = existingJourneys.find(job =>
        job.editionId === attemptedBoundGame.edition.id && job.teachingHandoffState !== 'NOT_REQUESTED')
      const reusableJourneys = REQUIRE_FRESH_IMPORT ? [] : existingJourneys
      let candidateResult: CandidateResponse | null = null
      let recovery = chooseRecommendationRecovery(attemptedBoundGame.edition.id, reusableJourneys, [])
      if (recovery.outcome !== 'REUSED_EXISTING_JOURNEY') {
        if (restorableJourney) {
          const chooseAnotherSource = page.getByTestId('player-journey-surface')
            .getByRole('button', { name: '重新选择来源', exact: true })
          await expect(chooseAnotherSource,
            'An unusable existing journey did not expose its real UI source-recovery action')
            .toBeVisible({ timeout: 60_000 })
          await chooseAnotherSource.click()
        }
        const candidatesResponse = await candidatesResponsePromise
        if (candidatesResponse) {
          candidateResult = await candidatesResponse.json() as CandidateResponse
          expect(new URL(candidatesResponse.url()).searchParams.get('editionId'),
            'Rulebook discovery used a different edition from the attempted recommendation')
            .toBe(attemptedBoundGame.edition.id)
          expect(candidateResult.identity.editionId,
            'Rulebook discovery response lost the attempted edition identity')
            .toBe(attemptedBoundGame.edition.id)
          recovery = chooseRecommendationRecovery(
            attemptedBoundGame.edition.id,
            reusableJourneys,
            candidateResult.configured
              ? candidateResult.candidates.filter(candidate =>
                  !REQUIRE_FRESH_IMPORT
                  || existingJourneys.every(job => job.officialSourceUrl !== candidate.url))
              : [],
          )
        }
      }

      const verifiedCandidateCount = candidateResult?.configured
        ? candidateResult.candidates.filter(isImportableCandidate).length
        : 0
      report.recommendationRecoveryOutcomes.push({
        recommendationRank,
        bggId: attemptedBggId,
        gameName: attemptedGameName,
        boundEditionId: attemptedBoundGame.edition.id,
        outcome: recovery.outcome,
        existingJourneyId: restorableJourney?.id ?? null,
        discoveryConfigured: candidateResult?.configured ?? null,
        discoveredCandidateCount: candidateResult?.candidates.length ?? null,
        verifiedCandidateCount,
        elapsedMs: elapsed(attemptStartedAt),
      })

      if (recovery.outcome === 'SKIPPED_NO_VERIFIED_OFFICIAL_SOURCE') {
        const journeySurface = page.getByTestId('player-journey-surface')
        await journeySurface.getByRole('button', { name: '关闭小窗', exact: true }).click()
        await expect(journeySurface).toBeHidden()
        continue
      }

      selectedJourney = {
        recommendationRank,
        selectedCardBggId: attemptedBggId,
        selectedGameName: attemptedGameName,
        selectedDetailsButton: attemptedDetailsButton,
        boundGame: attemptedBoundGame,
        existingJourney: recovery.existingJourney,
        importableCandidate: recovery.candidate,
        discoveryMs: elapsed(discoveryStartedAt),
      }
      break
    }

    if (!selectedJourney) {
      const recoverySummary = report.recommendationRecoveryOutcomes
        .map(({ recommendationRank, outcome }) => ({ recommendationRank, outcome }))
      throw new Error(
        `None of the three Agent-ranked recommendations had a usable journey or verified official rulebook source: ${JSON.stringify(recoverySummary)}`,
      )
    }

    const {
      recommendationRank: selectedRecommendationRank,
      selectedCardBggId,
      selectedGameName,
      selectedDetailsButton,
      boundGame,
      existingJourney,
      importableCandidate,
      discoveryMs,
    } = selectedJourney
    report.selectedRecommendationRank = selectedRecommendationRank
    report.selectedBggId = selectedCardBggId
    report.selectedGameName = selectedGameName
    report.boundBggId = boundGame.bggId
    report.boundGameName = boundGame.game.name
    report.boundEditionId = boundGame.edition.id
    report.discoveryMs = discoveryMs
    const selectedJourneyContinuation = page.locator(
      `[data-testid="player-journey-continuation"][data-bgg-id="${boundGame.bggId}"]`,
    )
    const restoredExistingJourney = existingJourney !== null
    let launchedJob: ImportJob
    let importStartedAt = performance.now()
    if (existingJourney) {
      launchedJob = await retryFailedReusedTeaching(page.request, { ...existingJourney, reused: true })
      report.candidateEditionMatchesSelection = true
      report.sourceDomain = launchedJob.sourceDomain ?? null
      report.sourceUrl = launchedJob.officialSourceUrl ?? null
      report.importReused = true
    } else {
      report.candidateEditionMatchesSelection = true
      report.sourceDomain = importableCandidate!.sourceDomain
      report.sourceUrl = importableCandidate!.url
      report.sourceMode = importableCandidate!.acquisitionMode
      report.sourcePublisher = importableCandidate!.publisher
      report.sourceEdition = importableCandidate!.edition
      report.sourceLanguage = importableCandidate!.language
      report.sourceLanguageVerified = importableCandidate!.languageVerified
      report.sourceOfficialDomainVerified = importableCandidate!.officialDomainVerified
      report.sourceCapabilityEvidence = [...importableCandidate!.capabilityEvidence]
    }

    const catalogResponse = await page.request.get('/api/v1/games')
    expect(catalogResponse.ok(), `Catalog returned HTTP ${catalogResponse.status()}`).toBe(true)
    const catalogGames = await catalogResponse.json() as CatalogGameResponse[]
    report.boundGameInCatalog = catalogGames.some(entry =>
      entry.game.id === boundGame.game.id
      && entry.game.name === boundGame.game.name
      && entry.editions.some(edition => edition.id === boundGame.edition.id))
    expect(report.boundGameInCatalog, 'The selected recommendation was not bound in My Games').toBe(true)

    report.stage = restoredExistingJourney ? 'import' : 'source-review'
    const journeyBackdrop = page.getByTestId('player-journey-backdrop')
    const journeySurface = page.getByTestId('player-journey-surface')
    report.journeyBackdropVisible = await journeyBackdrop.isVisible()
    report.journeySurfaceOpaque = await opaqueSurface(journeySurface)
    expect(report.journeyBackdropVisible).toBe(true)
    expect(report.journeySurfaceOpaque).toBe(true)
    if (restoredExistingJourney) {
      await expect(page.getByTestId('player-journey-progress')).toBeVisible({ timeout: 60_000 })
      report.confirmedMilestonesAtSourceReview = await journeySurface
        .locator('[data-fact-confirmed="true"]').count()
      expect(report.confirmedMilestonesAtSourceReview).toBeGreaterThanOrEqual(1)
    } else {
      report.confirmedMilestonesAtSourceReview = await journeySurface
        .locator('[data-fact-confirmed="true"]').count()
      expect(report.confirmedMilestonesAtSourceReview).toBe(1)
      const candidateCard = journeySurface.locator('li', {
        has: page.locator(`a[href="${importableCandidate!.url}"]`),
      }).first()
      await expect(candidateCard.getByRole('link')).toBeVisible()
      await candidateCard.getByRole('button', { name: '选择这份' }).click()
      const importButton = page.getByRole('button', { name: '下载规则书并生成讲解' })
      await expect(importButton).toBeDisabled()
      await page.getByRole('checkbox', { name: /我已比较以上游戏、版本和语言/ }).check()
      await expect(importButton).toBeDisabled()
      await page.getByRole('checkbox', { name: /我确认该链接来自有权提供/ }).check()
      await expect(importButton).toBeEnabled()

      report.stage = 'import'
      importStartedAt = performance.now()
      const importResponsePromise = page.waitForResponse(response => {
        const url = new URL(response.url())
        return url.pathname === '/api/v1/documents/official-imports' && response.request().method() === 'POST'
      }, { timeout: 30_000 })
      await importButton.click()
      const importResponse = await importResponsePromise
      expect(importResponse.status()).toBe(202)
      launchedJob = await importResponse.json() as ImportJob
      launchedJob = await retryFailedReusedTeaching(page.request, launchedJob)
    }
    report.importReused = launchedJob.reused
    if (REQUIRE_FRESH_IMPORT) {
      expect(launchedJob.reused, 'The requested fresh-import journey reused an existing rulebook').toBe(false)
    }
    report.importEditionMatchesSelection = launchedJob.editionId === boundGame.edition.id
      && (restoredExistingJourney
        || observedImportRequest?.editionId === boundGame.edition.id
          && observedImportRequest?.discoveredForEditionId === boundGame.edition.id
          && observedImportRequest?.officialSourceUrl === importableCandidate!.url
          && observedImportRequest?.identityConfirmed === true)
    expect(report.importEditionMatchesSelection,
      'The official import request or persisted job changed the selected edition/source identity').toBe(true)
    expect(launchedJob.title, 'The official import response did not retain the selected game title')
      .toBe(boundGame.game.name)
    if (!restoredExistingJourney) {
      expect(launchedJob.sourceDomain, 'The official import response changed the selected source domain')
        .toBe(importableCandidate!.sourceDomain)
    }

    report.stage = 'close-and-recover-background-status'
    await page.getByTestId('player-journey-surface')
      .getByRole('button', { name: '关闭小窗' })
      .click()
    await expect(selectedJourneyContinuation).toHaveCount(1)
    await expect(selectedJourneyContinuation).toBeVisible()
    const globalStatusShortcut = page.getByTestId('background-work-persistent-shortcut')
    await expect(globalStatusShortcut).toBeVisible({ timeout: 60_000 })
    await expect(globalStatusShortcut).toContainText('讲解状态')
    report.globalStatusVisibleAfterClosing = true
    await globalStatusShortcut.click()
    const backgroundWork = page.getByRole('dialog', { name: '后台任务' })
    await expect(backgroundWork).toBeVisible()
    await expect(backgroundWork).toContainText(boundGame.game.name)
    report.globalStatusReopened = true
    await backgroundWork.getByRole('button', { name: '关闭后台任务' }).click()
    const journeyProgressButton = selectedJourneyContinuation.getByTestId('player-journey-progress-button')
    if (await journeyProgressButton.isVisible()) {
      await journeyProgressButton.click()
    } else {
      await selectedJourneyContinuation.getByTestId('player-journey-dock').click()
    }
    await expect(page.getByTestId('player-journey-surface')).toBeVisible()
    report.stage = 'import'

    const importObservation = await waitForCompletedImport(page.request, launchedJob.id, importStartedAt)
    const completedJob = importObservation.job
    expect(completedJob.downloadedBytes).toBeGreaterThan(0)
    expect(completedJob.documentVersionId).not.toBeNull()
    expect(completedJob.teachingHandoffState).toBe('LAUNCHED')
    expect(completedJob.teachingPreparationRunId).not.toBeNull()
    expect(completedJob.editionId).toBe(boundGame.edition.id)
    observedDocumentVersionId = completedJob.documentVersionId
    report.documentVersionId = completedJob.documentVersionId
    observedPreparationRunId = completedJob.teachingPreparationRunId
    report.importDuplicate = completedJob.duplicate
    report.downloadedBytes = completedJob.downloadedBytes
    report.importMs = elapsed(importStartedAt)
    report.downloadCompletedAt = completedJob.downloadCompletedAt
    report.importCompletedAt = completedJob.importCompletedAt
    report.teachingHandoffUpdatedAt = completedJob.teachingHandoffUpdatedAt
    if (!report.importReused) {
      report.persistedDownloadToImportCompleteMs = persistedDuration(
        completedJob.downloadCompletedAt,
        completedJob.importCompletedAt,
      )
      report.persistedImportCompleteToHandoffMs = persistedDuration(
        completedJob.importCompletedAt,
        completedJob.teachingHandoffUpdatedAt,
      )
      report.persistedDownloadToHandoffMs = persistedDuration(
        completedJob.downloadCompletedAt,
        completedJob.teachingHandoffUpdatedAt,
      )
    }
    report.pdfDownloadCompleteMs = importObservation.pdfDownloadCompleteMs
    report.documentReadyMs = importObservation.documentReadyMs
    report.teachingHandoffLaunchedMs = importObservation.teachingHandoffLaunchedMs
    expect(report.pdfDownloadCompleteMs, 'The production probe did not observe PDF download completion')
      .not.toBeNull()
    expect(report.documentReadyMs, 'The production probe did not observe the document READY milestone')
      .not.toBeNull()
    expect(report.teachingHandoffLaunchedMs, 'The production probe did not observe teaching handoff launch')
      .not.toBeNull()
    if (!report.importReused) {
      expect(report.downloadCompletedAt, 'A fresh import did not persist PDF byte completion').not.toBeNull()
      expect(report.importCompletedAt, 'A fresh import did not persist import completion').not.toBeNull()
      expect(report.teachingHandoffUpdatedAt, 'A fresh import did not persist handoff launch').not.toBeNull()
      expect(report.persistedDownloadToHandoffMs,
        'Fresh import milestones could not be ordered from PDF completion to handoff').not.toBeNull()
    }
    report.teachingHandoffState = completedJob.teachingHandoffState
    report.teachingAutomaticRecoveryCount = completedJob.teachingAutomaticRecoveryCount
    report.teachingEvidenceRefreshRequested = teachingEvidenceWasRefreshed(launchedJob, completedJob)
    const progressResponse = await page.request.get(
      `/api/v1/document-versions/${encodeURIComponent(completedJob.documentVersionId!)}/progress/snapshot`,
    )
    expect(progressResponse.ok(), `Document progress returned HTTP ${progressResponse.status()}`).toBe(true)
    const progressPayload = await progressResponse.json() as DocumentProgressResponse
    report.documentProgressStage = progressPayload.stage
    report.documentProgressComplete = progressPayload.complete
    expect(progressPayload).toMatchObject({ stage: 'READY', complete: true })
    expect(importRequestCount).toBe(restoredExistingJourney ? 0 : 1)
    const documentsResponse = await page.request.get('/api/v1/documents')
    expect(documentsResponse.ok(), `Documents returned HTTP ${documentsResponse.status()}`).toBe(true)
    const documents = await documentsResponse.json() as RuleDocumentResponse[]
    const importedDocument = documents.find(document => document.latestVersion.id === completedJob.documentVersionId)
    report.documentEditionMatchesSelection = importedDocument?.document.gameEditionId === boundGame.edition.id
    expect(report.documentEditionMatchesSelection,
      'The readable document was not persisted against the selected game edition').toBe(true)

    const firstCitedLesson = await waitForFirstCitedLesson(
      page.request,
      completedJob.id,
      completedJob.documentVersionId!,
      completedJob.teachingPreparationRunId!,
      importStartedAt,
      requiresPersistedPublicationActivity(report),
      completedJob.teachingHandoffUpdatedAt,
      recordTeachingProgress,
    )
    observedTeachingPlanId = firstCitedLesson.planId
    report.teachingPlanId = firstCitedLesson.planId
    report.teachingPreparationStartedMs = firstCitedLesson.teachingPreparationStartedMs
    report.firstCitedLessonMs = firstCitedLesson.firstCitedLessonMs
    report.preparationRunCreatedAt = firstCitedLesson.preparationRunCreatedAt
    report.firstCitedPublicationActivityAt = firstCitedLesson.firstCitedPublicationActivityAt
    report.persistedHandoffToPreparationRunMs = persistedDuration(
      report.teachingHandoffUpdatedAt,
      report.preparationRunCreatedAt,
    )
    report.persistedPreparationToFirstCitedActivityMs = persistedDuration(
      report.preparationRunCreatedAt,
      report.firstCitedPublicationActivityAt,
    )
    if (!report.importReused) {
      report.persistedDownloadToFirstCitedActivityMs = persistedDuration(
        report.downloadCompletedAt,
        report.firstCitedPublicationActivityAt,
      )
    }
    expect(report.preparationRunCreatedAt, 'The production probe did not observe the real preparation Run')
      .not.toBeNull()
    if (requiresPersistedPublicationActivity(report)) {
      expect(report.firstCitedPublicationActivityAt,
        'A fresh production import did not persist a source-cited publication activity').not.toBeNull()
      expect(report.persistedPreparationToFirstCitedActivityMs,
        'The fresh preparation-to-first-cited activity duration could not be computed').not.toBeNull()
    }
    if (report.pdfDownloadCompleteMs !== null) {
      report.pdfDownloadToTeachingStartMs = Math.max(
        0,
        firstCitedLesson.teachingPreparationStartedMs - report.pdfDownloadCompleteMs,
      )
      report.pdfDownloadToFirstCitedLessonMs = Math.max(
        0,
        firstCitedLesson.firstCitedLessonMs - report.pdfDownloadCompleteMs,
      )
    }

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
    const completedLesson = await waitForCompletedLesson(
      page.request,
      firstCitedLesson.planId,
      recordTeachingProgress,
    )
    report.teachingGenerationState = completedLesson.teachingRunState
    report.lessonStatus = completedLesson.lessonStatus
    report.lessonSectionCount = completedLesson.sectionCount
    report.citedDraftSectionCount = completedLesson.citedDraftSectionCount
    report.insufficientSectionCount = completedLesson.insufficientSectionCount
    report.lessonCompletionMs = elapsed(lessonStartedAt)
    const journeyDock = selectedJourneyContinuation.getByTestId('player-journey-dock')
    await expect(journeyDock).toBeVisible()
    await expect(journeyDock).toContainText('打开讲解', { timeout: 60_000 })
    await expect(journeyDock).toContainText('基础讲解可读')
    await expect(journeyDock).not.toContainText('准备流程需要处理')
    report.lessonDockText = (await journeyDock.innerText()).trim()
    report.lessonReadableMs = elapsed(lessonStartedAt)
    await journeyDock.click()
    const lesson = page.getByRole('dialog', { name: '生成讲解阅读器' })
    report.lessonBackdropVisible = await page.getByTestId('recommendation-lesson-backdrop').isVisible()
    report.lessonSurfaceOpaque = await opaqueSurface(page.getByTestId('recommendation-lesson-surface'))
    expect(report.lessonBackdropVisible).toBe(true)
    expect(report.lessonSurfaceOpaque).toBe(true)
    await expect(lesson.getByText('每个步骤都保留原规则书页码；答疑只使用同一份规则书。')).toBeVisible({ timeout: 60_000 })
    const lessonSections = lesson.getByTestId('lesson-reading-column').locator('section')
    await expect.poll(async () => lessonSections.count(), {
      timeout: 60_000,
      message: 'The reopened guide reader did not reconcile to the completed lesson snapshot',
    }).toBe(report.lessonSectionCount)
    await expect(lesson.getByRole('link', { name: /来源：第 \d+(?:、\d+)* 页/ }).first()).toBeVisible()
    report.citedLessonStep = true
    report.confirmedMilestonesFinal = await page.getByTestId('player-journey-surface')
      .locator('[data-fact-confirmed="true"]').count()
    expect(report.confirmedMilestonesFinal).toBe(5)

    const plansResponse = await page.request.get('/api/v1/teaching-plans')
    expect(plansResponse.ok(), `My Guides returned HTTP ${plansResponse.status()}`).toBe(true)
    const plans = await plansResponse.json() as TeachingPlanResponse[]
    const persistedPlan = plans.find(plan => plan.id === firstCitedLesson.planId)
    expect(persistedPlan, 'The generated plan was not listed in My Guides').toBeDefined()
    report.myGuidesPlanListed = persistedPlan != null
    expect(persistedPlan!.documentVersionId,
      'My Guides changed the imported document version identity').toBe(completedJob.documentVersionId)
    report.planGameTitleMatchesSelection = persistedPlan!.gameTitle === boundGame.game.name
    expect(report.planGameTitleMatchesSelection,
      'My Guides changed the selected game title').toBe(true)
    await guidesPage.reload()
    await expect(guidesPage.getByRole('heading', { name: boundGame.game.name, exact: true })).toBeVisible({ timeout: 60_000 })

    report.stage = 'grounded-answer'
    await lesson.getByRole('button', { name: '切换到规则答疑' }).click()
    const answerWorkspace = page.getByTestId('recommendation-answer-workspace')
    await expect(answerWorkspace).toContainText('已绑定规则书，可以开始提问', { timeout: 60_000 })
    const answerStartedAt = performance.now()
    const answerResponsePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname === `/api/v1/document-versions/${completedJob.documentVersionId}/answers/stream`
        && response.request().method() === 'POST'
    }, { timeout: 4 * 60_000 })
    await page.getByLabel('向规则书提问').fill(RULE_QUESTION)
    await page.getByRole('button', { name: '提交问题' }).click()
    const answerResponse = await answerResponsePromise
    expect(answerResponse.ok(), `Answer endpoint returned HTTP ${answerResponse.status()}`).toBe(true)
    await expect(answerWorkspace.locator('#lesson-answer-evidence-title')).toBeVisible({ timeout: 4 * 60_000 })
    await expect(answerWorkspace.getByText(/第 \d+(?:\s*[–-]\s*\d+)? 页/).first()).toBeVisible()
    const answerSessionId = await page.evaluate(
      versionId => localStorage.getItem(`rulepilot:recommendation-answer-session:${versionId}`),
      completedJob.documentVersionId,
    )
    expect(answerSessionId, 'The answer workspace did not persist its server conversation identity').toBeTruthy()
    report.answerSessionId = answerSessionId
    const answerSessionResponse = await page.request.get(
      `/api/v1/game-sessions/${encodeURIComponent(answerSessionId!)}`,
    )
    expect(answerSessionResponse.ok(),
      `Answer session returned HTTP ${answerSessionResponse.status()}`).toBe(true)
    expect(await answerSessionResponse.json()).toMatchObject({
      id: answerSessionId,
      editionId: boundGame.edition.id,
      documentVersionId: completedJob.documentVersionId,
    })
    const conversationUrl
      = `/api/v1/document-versions/${encodeURIComponent(completedJob.documentVersionId)}/answers/conversation?${new URLSearchParams({
        gameSessionId: answerSessionId!, language: 'zh-CN',
      })}`
    const conversationResponse = await page.request.get(conversationUrl)
    expect(conversationResponse.ok(), `Answer conversation returned HTTP ${conversationResponse.status()}`).toBe(true)
    const conversation = await conversationResponse.json() as ConversationTurnResponse[]
    const persistedAnswer = conversation.findLast(turn => turn.question === RULE_QUESTION)
    expect(persistedAnswer, 'The visible answer was not persisted in the server conversation').toBeDefined()
    report.firstAnswerTurnId = persistedAnswer!.id
    report.answerStatus = persistedAnswer!.answer.status
    report.answerCitationCount = persistedAnswer!.answer.citations.length
    expect(['ANSWERED', 'ANSWERED_WITH_WARNING']).toContain(report.answerStatus)
    expect(report.answerCitationCount).toBeGreaterThan(0)
    report.answerMs = elapsed(answerStartedAt)
    report.citedAnswer = true

    report.stage = 'grounded-follow-up'
    const followUpStartedAt = performance.now()
    const followUpResponsePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname === `/api/v1/document-versions/${completedJob.documentVersionId}/answers/stream`
        && response.request().method() === 'POST'
        && (response.request().postDataJSON() as { question?: unknown } | null)?.question === RULE_FOLLOW_UP
    }, { timeout: 4 * 60_000 })
    await page.getByLabel('向规则书提问').fill(RULE_FOLLOW_UP)
    await page.getByRole('button', { name: '提交问题' }).click()
    const followUpResponse = await followUpResponsePromise
    expect(followUpResponse.ok(), `Follow-up endpoint returned HTTP ${followUpResponse.status()}`).toBe(true)
    const followUpRequest = followUpResponse.request().postDataJSON() as {
      question?: string
      gameSessionId?: string
      language?: string
      previousQuestion?: string
    }
    expect(followUpRequest).toMatchObject({
      question: RULE_FOLLOW_UP,
      gameSessionId: answerSessionId,
      language: 'zh-CN',
      previousQuestion: RULE_QUESTION,
    })
    let followUpConversation: ConversationTurnResponse[] = []
    await expect.poll(async () => {
      const response = await page.request.get(conversationUrl)
      if (!response.ok()) return 0
      followUpConversation = await response.json() as ConversationTurnResponse[]
      return followUpConversation.filter(turn => turn.question === RULE_FOLLOW_UP).length
    }, {
      timeout: 4 * 60_000,
      message: 'The natural follow-up was not persisted in the same grounded answer conversation',
    }).toBe(1)
    const initialAnswerIndex = followUpConversation.findIndex(turn => turn.question === RULE_QUESTION)
    const followUpAnswerIndex = followUpConversation.findIndex(turn => turn.question === RULE_FOLLOW_UP)
    const persistedFollowUp = followUpConversation[followUpAnswerIndex]
    report.followUpAnswerTurnId = persistedFollowUp!.id
    report.answerSessionTurnCount = followUpConversation.length
    report.answerSessionPreserved = followUpRequest.gameSessionId === answerSessionId
      && initialAnswerIndex >= 0
      && followUpAnswerIndex > initialAnswerIndex
    expect(report.answerSessionPreserved,
      'The follow-up did not continue after the first answer in the same document-bound session').toBe(true)
    report.followUpAnswerStatus = persistedFollowUp!.answer.status
    report.followUpCitationCount = persistedFollowUp!.answer.citations.length
    report.followUpAnswerMs = elapsed(followUpStartedAt)
    expect(['ANSWERED', 'ANSWERED_WITH_WARNING']).toContain(report.followUpAnswerStatus)
    expect(report.followUpCitationCount).toBeGreaterThan(0)
    await expect(answerWorkspace.getByText(RULE_FOLLOW_UP, { exact: true })).toBeVisible()

    report.stage = 'role-switching'
    const roleSwitcher = page.getByTestId('agent-role-switcher')
    await roleSwitcher.getByRole('button', { name: '继续推荐' }).click()
    await expect(composer).toBeVisible()
    await expect(composer).toHaveValue(PRESERVED_DRAFT)
    await expect.poll(() => selectedDetailsButton.count(), {
      message: 'The recommendation workspace did not restore a matching verified game card',
    }).toBeGreaterThan(0)
    await expect(selectedDetailsButton.first()).toBeVisible()
    await roleSwitcher.getByRole('button', { name: '规则答疑' }).click()
    await expect(answerWorkspace.locator('#lesson-answer-evidence-title')).toBeVisible()
    await roleSwitcher.getByRole('button', { name: '继续推荐' }).click()

    report.stage = 'refresh-restoration'
    await page.reload()
    const restoredComposer = page.getByLabel('聊聊你想玩的游戏')
    await expect(restoredComposer).toBeVisible({ timeout: 60_000 })
    await expect(restoredComposer).toHaveValue(PRESERVED_DRAFT)
    await expect(page.getByRole('button', {
      name: `查看完整资料：${selectedGameName}`,
      exact: true,
    }).first()).toBeVisible({ timeout: 60_000 })
    report.recommendationRestored = true
    const restoredRoleSwitcher = page.getByTestId('agent-role-switcher')
    await restoredRoleSwitcher.getByRole('button', { name: '规则答疑' }).click()
    const restoredAnswerWorkspace = page.getByTestId('recommendation-answer-workspace')
    await expect(restoredAnswerWorkspace.locator('#lesson-answer-evidence-title')).toBeVisible({ timeout: 60_000 })
    report.answerRestored = true
    await restoredRoleSwitcher.getByRole('button', { name: '继续推荐' }).click()

    await expect(page).toHaveURL(/\/discover$/)
    if (pageErrors.length > 0) recordFailedCheck(report, 'BROWSER_PAGE_ERRORS')
    report.routeStayedOnDiscover = true
    report.journeyReachedEnd = true
    report.completed = report.failedChecks.length === 0
    report.stage = report.completed ? 'completed' : 'acceptance-failed'
    expect(
      report.failedChecks,
      `The production journey reached the end with failed acceptance facts: ${JSON.stringify(report.failedChecks)}`,
    ).toEqual([])
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
    if (observedTeachingPlanId) {
      try {
        const [runResponse, lessonResponse] = await Promise.all([
          page.request.get(
            `/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(observedTeachingPlanId)}`,
          ),
          page.request.get(
            `/api/v1/teaching-plans/${encodeURIComponent(observedTeachingPlanId)}/illustrated-lessons/latest`,
          ),
        ])
        if (runResponse.ok()) {
          const details = await runResponse.json() as RunDetailsResponse
          report.teachingGenerationState = details.run.state
        }
        if (lessonResponse.ok()) {
          const latestLesson = await lessonResponse.json() as LessonMilestoneResponse
          report.lessonStatus = latestLesson.status
          report.lessonSectionCount = latestLesson.sections.length
          report.citedDraftSectionCount = latestLesson.sections
            .filter(section => section.evidenceStatus === 'CITED_DRAFT').length
          report.insufficientSectionCount = latestLesson.sections
            .filter(section => section.evidenceStatus === 'INSUFFICIENT_EVIDENCE').length
        }
      } catch {
        // Preserve the primary failure while retaining any earlier teaching diagnostics.
      }
    }
    if (privateTraceStarted) {
      try {
        await finalizePrivateAgentTrace(page.request, traceExportFile, report)
      } catch (error) {
        privateTraceFinalizeError = error instanceof Error ? error : new Error(String(error))
        recordFailedCheck(report, 'PRIVATE_AGENT_TRACE_EXPORT_FAILED')
      }
    }
    await guidesPage?.close().catch(() => undefined)
    report.importRequestCount = importRequestCount
    report.pageErrorCount = pageErrors.length
    if (report.recommendationClientTurnId) {
      report.recommendationSelectionRequestCount = recommendationStreamRequests
        .filter(request => request.clientTurnId === report.recommendationClientTurnId).length
      if (report.recommendationSelectionRequestCount > 1) {
        recordFailedCheck(report, 'RECOMMENDATION_TURN_REPOSTED')
      }
    }
    report.completed = report.journeyReachedEnd && report.failedChecks.length === 0
    if (report.journeyReachedEnd && !report.completed) report.stage = 'acceptance-failed'
    report.generatedAt = new Date().toISOString()
    await retainReport(reportFile, report)
    if (privateTraceFinalizeError && report.journeyReachedEnd) {
      expect(privateTraceFinalizeError, 'Private Agent trace finalization failed').toBeNull()
    }
  }
})
