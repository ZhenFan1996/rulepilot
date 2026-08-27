import { writeFile } from 'node:fs/promises'

import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'

type RecommendationJourneyMode = 'ready_public' | 'verified_import'
type ProductionModelRole = 'teaching' | 'visual' | 'answer' | 'recommendation'
type RecommendationHarnessSafetyCategory =
  | 'RECOMMENDATION_ONLY_NO_RULEBOOK_IMPORT'
  | 'READY_PUBLIC_TEACHING_NO_IMPORT'
  | 'VERIFIED_RULEBOOK_JOURNEY_IMPORT_OR_REUSE'

function parseRecommendationJourneyMode(value: string | undefined): RecommendationJourneyMode {
  const mode = value ?? 'ready_public'
  if (mode === 'ready_public' || mode === 'verified_import') return mode
  throw new Error(`Unsupported production recommendation journey mode: ${mode}`)
}

function recommendationHarnessSafetyCategory(
  mode: RecommendationJourneyMode,
  recommendationOnly: boolean,
): RecommendationHarnessSafetyCategory {
  if (recommendationOnly) return 'RECOMMENDATION_ONLY_NO_RULEBOOK_IMPORT'
  return mode === 'ready_public'
    ? 'READY_PUBLIC_TEACHING_NO_IMPORT'
    : 'VERIFIED_RULEBOOK_JOURNEY_IMPORT_OR_REUSE'
}

function assertImportRequirementCompatible(
  mode: RecommendationJourneyMode,
  recommendationOnly: boolean,
  requireFreshImport: boolean,
) {
  if (!recommendationOnly && mode === 'ready_public' && requireFreshImport) {
    throw new Error('Fresh import cannot be required in ready_public journey mode')
  }
}

function requiredProductionModelRoles(
  mode: RecommendationJourneyMode,
  recommendationOnly: boolean,
): ProductionModelRole[] {
  if (recommendationOnly) return ['recommendation']
  return mode === 'ready_public'
    ? ['recommendation', 'answer']
    : ['recommendation', 'teaching', 'visual', 'answer']
}

function parseRequestedRecommendationCardCount(value: string | undefined) {
  if (value === undefined || value === '') return 3
  if (!/^\d+$/.test(value)) throw new Error(`Invalid requested recommendation card count: ${value}`)
  const count = Number(value)
  if (!Number.isSafeInteger(count) || count < 1 || count > 8) {
    throw new Error(`Requested recommendation card count must be between 1 and 8: ${value}`)
  }
  return count
}

const enabled = process.env.RULEPILOT_PRODUCTION_RECOMMENDATION_JOURNEY === 'true'
const JOURNEY_MODE = parseRecommendationJourneyMode(
  process.env.RULEPILOT_RECOMMENDATION_JOURNEY_MODE,
)
const RECOMMENDATION_OPENING_PROMPT = process.env.RULEPILOT_RECOMMENDATION_OPENING_PROMPT
  ?? '嗨，今晚五个人聚会，最近合作玩得有点腻，但我还没想清楚换什么方向。你会先怎么帮我挑？'
const RECOMMENDATION_SELECTION_PROMPT = process.env.RULEPILOT_RECOMMENDATION_SELECTION_PROMPT
  ?? '我想换成能谈判、互相骗一骗的；有两个新手，90 分钟内。你直接挑三款，并把你最推荐的一款放第一吧。我们选好后想优先直接进入已有讲解，再按同一份规则书继续答疑。'
const REQUESTED_RECOMMENDATION_CARD_COUNT = parseRequestedRecommendationCardCount(
  process.env.RULEPILOT_RECOMMENDATION_EXPECTED_CARD_COUNT,
)
const MAX_OPEN_GUIDANCE_MS = 15_000
// The 15-second interaction SLO remains strict. This wider observation window matches the
// 45-second application budget plus the stream controller's five-second delivery tail so a
// slow success is not misreported as an unknown or unfinished functional failure.
const MAX_OPEN_TERMINAL_DIAGNOSTIC_MS = 50_000
const MAX_SELECTION_RECOMMENDATION_MS = 20_000
// This wider window diagnoses a persisted semantic terminal after the interaction budget expires;
// it does not relax the 20-second success budget.
const MAX_SELECTION_TERMINAL_OBSERVATION_MS = 50_000
const PRESERVED_DRAFT = '下次我还想给完全没玩过桌游的家人找一款更轻松的。'
const RULE_QUESTION = process.env.RULEPILOT_RECOMMENDATION_RULE_QUESTION
  ?? '我们现在要开第一局：所有组件分别怎么摆、每个人先拿什么？请按顺序说，并标出规则书页码。'
const RULE_FOLLOW_UP = process.env.RULEPILOT_RECOMMENDATION_RULE_FOLLOW_UP
  ?? '你刚才列出的第二个准备步骤具体需要哪些组件？仍然只根据同一本规则书回答并标页码。'
const REQUIRE_FRESH_IMPORT = process.env.RULEPILOT_RECOMMENDATION_REQUIRE_FRESH_IMPORT === 'true'
const RECOMMENDATION_ONLY = process.env.RULEPILOT_RECOMMENDATION_ONLY === 'true'
const REQUIRE_READY_TEACHING = JOURNEY_MODE === 'ready_public'
const TESTED_SHA = process.env.RULEPILOT_RECOMMENDATION_TESTED_SHA ?? ''
const ACTIVE_RELEASE_SHA = process.env.RULEPILOT_RECOMMENDATION_ACTIVE_RELEASE_SHA ?? ''
const EXPECTED_RECOMMENDATION_TITLE_TERM = (
  process.env.RULEPILOT_RECOMMENDATION_EXPECTED_TITLE_TERM ?? ''
).normalize('NFKC').trim().toLocaleLowerCase('en-US')

function bggIdFromBindingPath(pathname: string) {
  const match = /^\/api\/v1\/bgg\/games\/([1-9]\d*)\/import$/.exec(pathname)
  if (!match) throw new Error(`Unexpected BGG binding path: ${pathname}`)
  const value = Number(match[1])
  if (!Number.isSafeInteger(value)) throw new Error(`Unsafe BGG id in binding path: ${pathname}`)
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

type RecommendationOutcome =
  | 'conversation'
  | 'needs_clarification'
  | 'recommendations'
  | 'no_match'
  | 'unavailable'

interface RecommendationResultGame {
  game: { bggId: number; name: string; originalName: string }
  teachingContinuation?: {
    teachingPlanId: string
    sectionCount: number
    stepCount: number
  } | null
}

type RecommendationContinuationAvailability =
  | 'available_for_all'
  | 'available_for_some'
  | 'no_ready_candidate'
  | 'availability_unavailable'

interface RecommendationContinuationResult {
  kind: 'guide_and_rule_qa'
  learningGoal: string
  availability: RecommendationContinuationAvailability
  readyCount: number
  candidateCount: number
}

interface RecommendationSessionResponse {
  conversationId: string
  revision: number
  processing: boolean
  latestResponse: null | {
    clientTurnId: string
    outcome: RecommendationOutcome
    assistantMessage: string
    completedWork?: string[]
    modelCalls?: number
    catalogCalls?: number
    webResearchCalls?: number
    publicationRecovered?: boolean
    failureBoundary?: string | null
    continuation?: RecommendationContinuationResult | null
    games: RecommendationResultGame[]
  }
}

type RecommendationTerminalCategory =
  | 'NOT_OBSERVED'
  | 'PERSISTED_RECOMMENDATIONS'
  | 'RECOMMENDATIONS_WITHIN_INTERACTION_BUDGET'
  | 'RECOMMENDATIONS_OVER_INTERACTION_BUDGET'
  | 'PERSISTED_RECOMMENDATIONS_NOT_RENDERED'
  | 'SEMANTIC_UNAVAILABLE'
  | 'SEMANTIC_NON_RECOMMENDATION'
  | 'PERSISTED_SESSION_TIMEOUT'
  | 'PERSISTED_SESSION_READ_FAILURE'

type OpeningTerminalCategory =
  | 'GUIDANCE_RENDERED_WITHIN_BUDGET'
  | 'GUIDANCE_RENDERED_OVER_BUDGET'
  | 'GUIDANCE_NOT_RENDERED'
  | 'SEMANTIC_RECOMMENDATIONS'
  | 'UNAVAILABLE_WITH_FAILURE_BOUNDARY'
  | 'UNAVAILABLE_WITHOUT_FAILURE_BOUNDARY'
  | 'STILL_PROCESSING'
  | 'READ_FAILURE'
  | 'UNEXPECTED_TERMINAL'

interface RecommendationTerminalObservation {
  category: RecommendationTerminalCategory
  session: RecommendationSessionResponse | null
  elapsedMs: number
}

interface RecommendationSlateObservation {
  visible: boolean
  observedMs: number
  count: number
  bggIds: number[]
}

type TeachingCompletionCategory = 'FULLY_SUPPORTED' | 'READABLE_WITH_DEGRADATION'

interface OpeningTerminalRead {
  session: RecommendationSessionResponse | null
  terminal: RecommendationSessionResponse['latestResponse']
  observedMs: number
  readFailed: boolean
}

interface OpeningTerminalDiagnostic {
  outcome: RecommendationOutcome | null
  terminalCategory: OpeningTerminalCategory
  modelCalls: number | null
  catalogCalls: number | null
  failureBoundary: string | null
}

type ReadyTeachingFailureCategory =
  | 'READY_TEACHING_NOT_REQUESTED'
  | 'READY_TEACHING_NO_CANDIDATE'
  | 'READY_TEACHING_AVAILABILITY_UNAVAILABLE'
  | 'READY_TEACHING_NOT_ATTACHED'
  | 'READY_TEACHING_LINK_NOT_RENDERED'
  | 'READY_TEACHING_LINK_TARGET_MISMATCH'
  | 'PUBLIC_GUIDE_NOT_READABLE'
  | 'PUBLIC_QUESTION_ROUTE_UNAVAILABLE'
  | 'PUBLIC_ANSWER_REQUEST_FAILED'
  | 'PUBLIC_ANSWER_NON_PUBLISHING_STATUS'
  | 'PUBLIC_ANSWER_MISSING_CITATION'
  | 'PUBLIC_ANSWER_INVALID_CITATION_RANGE'
  | 'PUBLIC_ANSWER_EVIDENCE_NOT_RENDERED'
  | 'READY_TEACHING_STARTED_IMPORT'

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
    shortVerdict: string
    explanation: string
    citations: Array<{ heading: string; pageFrom: number; pageTo: number }>
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

interface ModelConfigurationResponse {
  providers: Array<{
    id: string
    configured: boolean
    model: string
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
  readable: true
  fullySupported: boolean
  category: TeachingCompletionCategory
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
  stage: string
  testedSha: string | null
  activeReleaseSha: string | null
  journeyMode: RecommendationJourneyMode
  recommendationOnly: boolean
  requireFreshImport: boolean
  recommendationHarnessSafetyCategory: RecommendationHarnessSafetyCategory
  selectedBggId: number | null
  selectedGameName: string | null
  recommendationConversationId: string | null
  openGuidanceClientTurnId: string | null
  openGuidanceOutcome: RecommendationOutcome | null
  openGuidanceTerminalCategory: OpeningTerminalCategory | null
  openGuidanceTerminalObserved: boolean
  openGuidanceTerminalObservedMs: number | null
  openGuidanceFirstSafeTextRendered: boolean
  openGuidanceFirstSafeTextMs: number | null
  openGuidanceObservationElapsedMs: number | null
  openGuidanceResultRenderedMs: number | null
  openGuidanceSloMet: boolean | null
  openGuidanceModelCalls: number | null
  openGuidanceCatalogCalls: number | null
  openGuidanceFailureBoundary: string | null
  readyTeachingRequested: boolean
  readyTeachingAvailability: string | null
  readyTeachingReadyCount: number
  readyTeachingCandidateCount: number
  renderedReadyTeachingCardCount: number
  readyTeachingGuideOpened: boolean
  readyTeachingQuestionsOpened: boolean
  readyTeachingFailureCategory: ReadyTeachingFailureCategory | null
  publicAnswerRequestSucceeded: boolean
  modelAssignments: ModelConfigurationResponse['assignments'] | null
  recommendationModelProvider: string | null
  recommendationModel: string | null
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
  recommendationMs: number | null
  recommendationStartedAt: string | null
  recommendationTerminalObservedAt: string | null
  recommendationElapsedBasis: 'NOT_OBSERVED' | 'UI_CARD_RENDER' | 'PERSISTED_FINAL_SESSION'
  recommendationRequestedCardCount: number
  recommendationPersistedCardCount: number | null
  recommendationShortfallCount: number | null
  recommendationSlateRendered: boolean
  recommendationSlateMs: number | null
  recommendationObservationElapsedMs: number | null
  recommendationSloMet: boolean | null
  recommendationPersistedTerminalObserved: boolean
  recommendationPersistedTerminalMs: number | null
  recommendationOutcome: RecommendationOutcome | null
  recommendationTerminalCategory: RecommendationTerminalCategory
  recommendationCardCount: number
  expectedRecommendationTitleTerm: string
  recommendationPublishedGames: Array<{
    bggId: number
    name: string
    originalName: string
  }>
  recommendationCompletedWork: string[]
  recommendationModelCalls: number | null
  recommendationCatalogCalls: number | null
  recommendationWebResearchCalls: number | null
  recommendationPublicationRecovered: boolean | null
  recommendationFailureBoundary: string | null
  rawModelOutputCaptured: false
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
  lessonReadable: boolean
  lessonFullySupported: boolean | null
  lessonCompletionCategory: TeachingCompletionCategory | null
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

interface OpeningRenderObservation {
  visible: boolean
  observedMs: number
}

function openingRenderMetrics(
  firstSafeText: OpeningRenderObservation,
  result: OpeningRenderObservation,
) {
  const visibleTimes = [firstSafeText, result]
    .filter(observation => observation.visible)
    .map(observation => observation.observedMs)
  const firstSafeTextMs = visibleTimes.length > 0 ? Math.min(...visibleTimes) : null
  return {
    firstSafeTextRendered: firstSafeTextMs !== null,
    firstSafeTextMs,
    observationElapsedMs: firstSafeTextMs ?? Math.max(firstSafeText.observedMs, result.observedMs),
    resultRenderedMs: result.visible ? result.observedMs : null,
    sloMet: firstSafeTextMs !== null && firstSafeTextMs <= MAX_OPEN_GUIDANCE_MS,
  }
}

function classifyRecommendationTerminal(
  session: RecommendationSessionResponse,
  baselineRevision: number,
  expectedClientTurnId: string,
  elapsedMs: number,
): RecommendationTerminalObservation | null {
  if (session.processing
    || session.revision <= baselineRevision
    || session.latestResponse === null
    || session.latestResponse.clientTurnId !== expectedClientTurnId) return null
  const outcome = session.latestResponse.outcome
  const category: RecommendationTerminalCategory = outcome === 'recommendations'
    ? 'PERSISTED_RECOMMENDATIONS'
    : outcome === 'unavailable'
      ? 'SEMANTIC_UNAVAILABLE'
      : 'SEMANTIC_NON_RECOMMENDATION'
  return { category, session, elapsedMs }
}

async function observeRecommendationSlate(
  cards: Locator,
  persistedBggIds: Promise<number[] | null>,
  startedAt: number,
  deadlineAt: number,
): Promise<RecommendationSlateObservation> {
  let expectedBggIds: number[] | null | undefined
  let targetFailure: unknown
  void persistedBggIds.then(
    ids => { expectedBggIds = ids },
    error => { targetFailure = error },
  )
  const observations: Array<{ bggIds: number[]; observedMs: number }> = []
  while (true) {
    if (targetFailure) throw targetFailure
    const bggIds = await cards.evaluateAll(entries => entries.map(card =>
      Number(card.getAttribute('data-bgg-id'))))
    const count = bggIds.length
    const observedMs = elapsed(startedAt)
    observations.push({ bggIds, observedMs })
    const persistedTarget = expectedBggIds
    if (persistedTarget !== undefined) {
      if (persistedTarget === null || persistedTarget.length === 0) {
        return { visible: false, observedMs, count, bggIds }
      }
      const completeSlate = observations.find(observation =>
        sameTypedBggSlate(observation.bggIds, persistedTarget))
      if (completeSlate) {
        return { visible: true, observedMs: completeSlate.observedMs, count, bggIds }
      }
    }
    if (Date.now() > deadlineAt) {
      return { visible: false, observedMs, count, bggIds }
    }
    await new Promise(resolve => setTimeout(resolve, 250))
  }
}

function sameTypedBggSlate(renderedBggIds: number[], persistedBggIds: number[]) {
  return renderedBggIds.length === persistedBggIds.length
    && renderedBggIds.every((id, index) => Number.isSafeInteger(id)
      && id > 0
      && id === persistedBggIds[index])
}

function diagnoseRecommendationTerminal(
  terminal: RecommendationTerminalObservation,
  slate: RecommendationSlateObservation,
): RecommendationTerminalCategory {
  if (terminal.category !== 'PERSISTED_RECOMMENDATIONS') return terminal.category
  if (!slate.visible) return 'PERSISTED_RECOMMENDATIONS_NOT_RENDERED'
  return slate.observedMs <= MAX_SELECTION_RECOMMENDATION_MS
    ? 'RECOMMENDATIONS_WITHIN_INTERACTION_BUDGET'
    : 'RECOMMENDATIONS_OVER_INTERACTION_BUDGET'
}

async function waitForPersistedRecommendationTerminal(
  request: APIRequestContext,
  conversationId: string,
  baselineRevision: number,
  expectedClientTurnId: string,
  startedAt: number,
  deadlineAt: number,
): Promise<RecommendationTerminalObservation> {
  let successfulReads = 0
  do {
    try {
      const response = await request.get(
        `/api/v1/bgg/recommendation-agent/sessions/${encodeURIComponent(conversationId)}`,
      )
      if (response.ok()) {
        successfulReads += 1
        const session = await response.json() as RecommendationSessionResponse
        const terminal = classifyRecommendationTerminal(
          session,
          baselineRevision,
          expectedClientTurnId,
          elapsed(startedAt),
        )
        if (terminal) return terminal
      }
    } catch {
      // A later successful persisted-session read remains authoritative inside the bounded observation window.
    }
    await new Promise(resolve => setTimeout(resolve, 250))
  } while (Date.now() <= deadlineAt)

  return {
    category: successfulReads > 0 ? 'PERSISTED_SESSION_TIMEOUT' : 'PERSISTED_SESSION_READ_FAILURE',
    session: null,
    elapsedMs: elapsed(startedAt),
  }
}

function publicNonNegativeInteger(value: unknown): number | null {
  return Number.isSafeInteger(value) && Number(value) >= 0 ? Number(value) : null
}

type PublicRecommendationFailureBoundary =
  | 'time_budget'
  | 'model_response'
  | 'service_configuration'
  | 'action_budget'
  | 'publication_boundary'
  | 'service_failure'

const publicRecommendationFailureBoundaries = new Set<PublicRecommendationFailureBoundary>([
  'time_budget',
  'model_response',
  'service_configuration',
  'action_budget',
  'publication_boundary',
  'service_failure',
])

function publicFailureBoundary(value: unknown): PublicRecommendationFailureBoundary | null {
  return typeof value === 'string'
    && publicRecommendationFailureBoundaries.has(value as PublicRecommendationFailureBoundary)
    ? value as PublicRecommendationFailureBoundary
    : null
}

function publicRecommendationOutcome(value: unknown): RecommendationOutcome | null {
  return typeof value === 'string'
    && ['conversation', 'needs_clarification', 'recommendations', 'no_match', 'unavailable'].includes(value)
    ? value as RecommendationOutcome
    : null
}

function readyContinuationMatchesPublishedGames(
  continuation: RecommendationContinuationResult,
  games: RecommendationResultGame[],
) {
  if (continuation.kind !== 'guide_and_rule_qa'
    || typeof continuation.learningGoal !== 'string'
    || !Number.isSafeInteger(continuation.readyCount)
    || !Number.isSafeInteger(continuation.candidateCount)
    || continuation.readyCount < 0
    || continuation.candidateCount < 1
    || continuation.candidateCount !== games.length) return false
  const readyGames = games.filter(game => game.teachingContinuation != null)
  if (!readyGames.every(validReadyTeachingAttachment)) return false
  const attachedReadyCount = readyGames.length
  if (continuation.readyCount !== attachedReadyCount) return false
  if (continuation.availability === 'available_for_all') {
    return continuation.readyCount === continuation.candidateCount
  }
  if (continuation.availability === 'available_for_some') {
    return continuation.readyCount > 0 && continuation.readyCount < continuation.candidateCount
  }
  if (continuation.availability === 'no_ready_candidate'
    || continuation.availability === 'availability_unavailable') {
    return continuation.readyCount === 0
  }
  return false
}

function validReadyTeachingAttachment(game: RecommendationResultGame) {
  const attachment = game.teachingContinuation
  return attachment != null
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
      .test(attachment.teachingPlanId)
    && Number.isSafeInteger(attachment.sectionCount)
    && attachment.sectionCount > 0
    && Number.isSafeInteger(attachment.stepCount)
    && attachment.stepCount > 0
}

function diagnoseOpeningTerminal(
  read: OpeningTerminalRead,
  resultRendered: boolean,
  renderedWithinBudget: boolean,
): OpeningTerminalDiagnostic {
  const terminal = read.terminal
  if (!terminal) {
    return {
      outcome: null,
      terminalCategory: !read.readFailed && read.session?.processing === true
        ? 'STILL_PROCESSING'
        : 'READ_FAILURE',
      modelCalls: null,
      catalogCalls: null,
      failureBoundary: null,
    }
  }
  const outcome = publicRecommendationOutcome(terminal.outcome)
  const modelCalls = publicNonNegativeInteger(terminal.modelCalls)
  const catalogCalls = publicNonNegativeInteger(terminal.catalogCalls)
  const failureBoundary = publicFailureBoundary(terminal.failureBoundary)
  if (outcome === 'recommendations') {
    return { outcome, terminalCategory: 'SEMANTIC_RECOMMENDATIONS', modelCalls, catalogCalls, failureBoundary }
  }
  if (outcome === 'unavailable') {
    return {
      outcome,
      terminalCategory: failureBoundary
        ? 'UNAVAILABLE_WITH_FAILURE_BOUNDARY'
        : 'UNAVAILABLE_WITHOUT_FAILURE_BOUNDARY',
      modelCalls,
      catalogCalls,
      failureBoundary,
    }
  }
  if (outcome === 'conversation' || outcome === 'needs_clarification') {
    return {
      outcome,
      terminalCategory: !resultRendered
        ? 'GUIDANCE_NOT_RENDERED'
        : renderedWithinBudget
          ? 'GUIDANCE_RENDERED_WITHIN_BUDGET'
          : 'GUIDANCE_RENDERED_OVER_BUDGET',
      modelCalls,
      catalogCalls,
      failureBoundary,
    }
  }
  return { outcome, terminalCategory: 'UNEXPECTED_TERMINAL', modelCalls, catalogCalls, failureBoundary }
}

async function readOpeningPersistedTerminal(
  request: APIRequestContext,
  conversationId: string,
  baselineRevision: number,
  expectedClientTurnId: string,
  startedAt: number,
  deadlineAt: number,
): Promise<OpeningTerminalRead> {
  let latestSession: RecommendationSessionResponse | null = null
  let observedReadableSession = false
  while (Date.now() < deadlineAt) {
    try {
      const response = await request.get(
        `/api/v1/bgg/recommendation-agent/sessions/${encodeURIComponent(conversationId)}`,
        { timeout: Math.max(1, Math.min(5_000, deadlineAt - Date.now())) },
      )
      if (response.ok()) {
        const session = await response.json() as RecommendationSessionResponse
        if (session.conversationId !== conversationId) {
          return { session: null, terminal: null, observedMs: elapsed(startedAt), readFailed: true }
        }
        observedReadableSession = true
        latestSession = session
        const terminal = session.latestResponse
        if (!session.processing
          && session.revision > baselineRevision
          && terminal?.clientTurnId === expectedClientTurnId) {
          return { session, terminal, observedMs: elapsed(startedAt), readFailed: false }
        }
        if (!session.processing
          && session.revision > baselineRevision
          && terminal?.clientTurnId
          && terminal.clientTurnId !== expectedClientTurnId) {
          return { session, terminal: null, observedMs: elapsed(startedAt), readFailed: true }
        }
      }
    } catch {
      // A later readable terminal or processing snapshot remains authoritative.
    }
    const remainingMs = deadlineAt - Date.now()
    if (remainingMs > 0) await new Promise(resolve => setTimeout(resolve, Math.min(250, remainingMs)))
  }
  return {
    session: latestSession,
    terminal: null,
    observedMs: elapsed(startedAt),
    // A final readable processing=true snapshot is more useful than an earlier transient GET failure.
    readFailed: !observedReadableSession || latestSession?.processing !== true,
  }
}

function hasPositiveDistinctBggIds(games: RecommendationResultGame[]) {
  const ids = games.map(entry => entry.game.bggId)
  return ids.length > 0
    && ids.every(id => Number.isSafeInteger(id) && id > 0)
    && new Set(ids).size === ids.length
}

function everyPublishedGameMatchesTitleTerm(games: RecommendationResultGame[], expectedTerm: string) {
  if (expectedTerm === '') return true
  const escapedTerm = expectedTerm.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const titleTerm = new RegExp(`(?:^|[^\\p{L}\\p{N}])${escapedTerm}(?=$|[^\\p{L}\\p{N}])`, 'u')
  return games.every(({ game }) => [game.name, game.originalName]
    .filter((title): title is string => typeof title === 'string')
    .some(title => titleTerm.test(title.normalize('NFKC').toLocaleLowerCase('en-US'))))
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
  role: ProductionModelRole,
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
  throw new Error(`The first source-cited lesson section did not become readable; latest=${JSON.stringify(progress.latest())}`)
}

function unfinishedSectionSummary(lesson: LessonMilestoneResponse) {
  const unfinished = lesson.sections.filter(section => section.evidenceStatus !== 'SUPPORTED')
  if (unfinished.length === 0) return 'no unfinished section details were returned'
  return unfinished.map((section, index) => {
    const identity = section.title?.trim()
      || (section.position ? `section ${section.position}` : `section ${index + 1}`)
    return `${identity}=${section.evidenceStatus}`
  }).join(', ')
}

function classifyReadableLessonTerminal(
  teachingRunState: string,
  lesson: LessonMilestoneResponse,
): CompletedLessonObservation | null {
  const citedDraftSectionCount = lesson.sections
    .filter(section => section.evidenceStatus === 'CITED_DRAFT').length
  const insufficientSectionCount = lesson.sections
    .filter(section => section.evidenceStatus === 'INSUFFICIENT_EVIDENCE').length
  const readableSectionCount = lesson.sections
    .filter(section => section.evidenceStatus === 'SUPPORTED'
      || section.evidenceStatus === 'CITED_DRAFT').length
  if (readableSectionCount === 0) return null
  const fullySupported = teachingRunState === 'COMPLETED'
    && lesson.status === 'COMPLETE'
    && lesson.sections.every(section => section.evidenceStatus === 'SUPPORTED')
  return {
    teachingRunState,
    lessonStatus: lesson.status,
    sectionCount: lesson.sections.length,
    citedDraftSectionCount,
    insufficientSectionCount,
    readable: true,
    fullySupported,
    category: fullySupported ? 'FULLY_SUPPORTED' : 'READABLE_WITH_DEGRADATION',
  }
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
  let terminalObservedAt: number | null = null
  let terminalGenerationKey = ''
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

    const terminalRun = ['COMPLETED', 'FAILED', 'DEGRADED', 'INSUFFICIENT_EVIDENCE']
      .includes(latestRunState)
    const nextTerminalGenerationKey = terminalRun
      ? `${latestRunDetails?.run.createdAt ?? 'unknown'}:${latestRunState}`
      : ''
    if (nextTerminalGenerationKey !== terminalGenerationKey) {
      terminalGenerationKey = nextTerminalGenerationKey
      terminalObservedAt = terminalRun ? Date.now() : null
    }

    if (latestLesson && terminalRun) {
      const readableTerminal = classifyReadableLessonTerminal(latestRunState, latestLesson)
      if (readableTerminal) return readableTerminal
    }
    if (terminalObservedAt !== null && Date.now() - terminalObservedAt >= 15_000) {
      const latestFailure = latestRunDetails?.activities
        .filter(activity => activity.outcome !== 'SUCCEEDED')
        .sort((left, right) => Date.parse(right.occurredAt) - Date.parse(left.occurredAt))[0]
      const diagnostic = latestFailure
        ? `${latestFailure.operation}: ${latestFailure.summary}`
        : latestRunError ?? 'no failure detail was recorded'
      throw new Error(
        `Teaching ended as ${latestRunState} without a readable cited section: ${diagnostic}; lesson=${latestLesson?.status ?? 'NOT_PUBLISHED'}; ${latestLesson ? unfinishedSectionSummary(latestLesson) : 'no lesson was published'}`,
      )
    }
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error(
    `Teaching lesson did not complete; run=${latestRunState}; lesson=${latestLesson?.status ?? 'NOT_PUBLISHED'}; error=${latestRunError ?? 'NONE'}; ${latestLesson ? unfinishedSectionSummary(latestLesson) : 'no lesson was published'}; latest=${JSON.stringify(progress.latest())}`,
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
    ? `${latestFailure.operation}: ${latestFailure.summary}`
    : teaching?.run.lastErrorCode ?? 'no rejected activity was recorded'
  return `attempt ${Math.min(2, job.teachingAutomaticRecoveryCount + 1)} of 2; chapters published=${published}, insufficient=${insufficient}, total=${sections.length}; latest=${latest}`
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
      console.log(`[production-teaching-progress] ${JSON.stringify(progress)}`)
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

function validPublicAnswerCitations(citations: AnswerResponse['answer']['citations']) {
  return citations.length > 0 && citations.every(citation =>
    typeof citation.heading === 'string'
    && citation.heading.trim().length > 0
    && Number.isSafeInteger(citation.pageFrom)
    && Number.isSafeInteger(citation.pageTo)
    && citation.pageFrom >= 1
    && citation.pageTo >= citation.pageFrom)
}

async function expectReadableCitationImage(
  request: APIRequestContext,
  href: string,
  description: string,
) {
  const response = await request.get(href, { timeout: 60_000 })
  expect(response.ok(), `${description} returned HTTP ${response.status()}`).toBe(true)
  expect(response.headers()['content-type'] ?? '', `${description} did not return an image`)
    .toMatch(/^image\//i)
  expect((await response.body()).byteLength, `${description} returned an empty image`).toBeGreaterThan(0)
}

function authenticatedCitationImagePath(
  documentVersionId: string,
  citation: AnswerResponse['answer']['citations'][number],
) {
  return `/api/v1/document-versions/${encodeURIComponent(documentVersionId)}/pages/${citation.pageFrom}/image`
}

async function expectRenderedPublicAnswerEvidence(
  request: APIRequestContext,
  article: Locator,
  question: string,
  planId: string,
  citations: AnswerResponse['answer']['citations'],
) {
  await expect(article).toBeVisible({ timeout: 60_000 })
  await expect(article.locator('xpath=preceding-sibling::*[1]')).toHaveText(question)
  const citationLinks = article.locator(
    `a[aria-label][href^="/api/public/lessons/${encodeURIComponent(planId)}/pages/"]`,
  )
  await expect(citationLinks).toHaveCount(citations.length)
  for (const [index, citation] of citations.entries()) {
    const link = citationLinks.nth(index)
    const expectedHref = `/api/public/lessons/${encodeURIComponent(planId)}/pages/${citation.pageFrom}/image`
    await expect(link).toHaveAttribute('href', expectedHref)
    const renderedHref = await link.getAttribute('href')
    expect(renderedHref, `Public citation ${index + 1} did not expose an href`).not.toBeNull()
    const pageLabel = citation.pageFrom === citation.pageTo
      ? String(citation.pageFrom)
      : `${citation.pageFrom}–${citation.pageTo}`
    expect(
      await link.getAttribute('aria-label'),
      `Citation ${index + 1} did not render its typed page range`,
    ).toContain(pageLabel)
    await expectReadableCitationImage(request, renderedHref!, `Public citation ${index + 1}`)
  }
}

async function renderedRecommendationAnswerTurnCount(answerWorkspace: Locator) {
  const historicalTurns = answerWorkspace.locator('ol[aria-label="本次答疑记录"] > li')
  const currentTurn = answerWorkspace.locator('article[aria-live="polite"]')
  return await historicalTurns.count() + await currentTurn.count()
}

function authenticatedCitationPageLabel(citation: AnswerResponse['answer']['citations'][number]) {
  return citation.pageFrom === citation.pageTo
    ? `第 ${citation.pageFrom} 页`
    : `第 ${citation.pageFrom}–${citation.pageTo} 页`
}

async function expectRenderedRecommendationAnswerEvidence(
  request: APIRequestContext,
  answerWorkspace: Locator,
  question: string,
  documentVersionId: string,
  answer: AnswerResponse['answer'],
) {
  expect(answer.shortVerdict.trim(), 'The persisted answer had no player-visible verdict').not.toBe('')
  expect(validPublicAnswerCitations(answer.citations), 'The persisted answer citations were invalid').toBe(true)
  const article = answerWorkspace.locator('article[aria-live="polite"]')
  await expect(article).toBeVisible({ timeout: 60_000 })
  await expect(article.getByText(question, { exact: true })).toBeVisible()
  await expect(article.getByText(answer.shortVerdict, { exact: true })).toBeVisible()
  if (answer.explanation.trim()) await expect(article).toContainText(answer.explanation.trim())

  const evidence = article.locator('section[aria-labelledby="lesson-answer-evidence-title"]')
  await expect(evidence).toBeVisible()
  const primaryCitation = evidence.locator(':scope > article')
  const additionalCitations = evidence.locator(':scope > details ol > li')
  await expect(primaryCitation).toHaveCount(1)
  await expect(additionalCitations).toHaveCount(answer.citations.length - 1)
  const additionalCitationDetails = evidence.locator(':scope > details')
  if (answer.citations.length > 1 && await additionalCitationDetails.getAttribute('open') === null) {
    await additionalCitationDetails.locator(':scope > summary').click()
  }

  for (const [index, citation] of answer.citations.entries()) {
    const item = index === 0 ? primaryCitation : additionalCitations.nth(index - 1)
    await expect(item).toBeVisible()
    await expect(item.getByText(citation.heading, { exact: true })).toBeVisible()
    await expect(item.getByText(authenticatedCitationPageLabel(citation), { exact: true })).toBeVisible()
    await expectReadableCitationImage(
      request,
      authenticatedCitationImagePath(documentVersionId, citation),
      `Authenticated citation ${index + 1}`,
    )
  }
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

test('production journey mode makes import safety explicit and rejects contradictory preflight input', () => {
  expect(parseRecommendationJourneyMode(undefined)).toBe('ready_public')
  expect(parseRecommendationJourneyMode('ready_public')).toBe('ready_public')
  expect(parseRecommendationJourneyMode('verified_import')).toBe('verified_import')
  expect(() => parseRecommendationJourneyMode('fresh_import')).toThrow(
    'Unsupported production recommendation journey mode: fresh_import',
  )

  expect(recommendationHarnessSafetyCategory('ready_public', false))
    .toBe('READY_PUBLIC_TEACHING_NO_IMPORT')
  expect(recommendationHarnessSafetyCategory('verified_import', false))
    .toBe('VERIFIED_RULEBOOK_JOURNEY_IMPORT_OR_REUSE')
  expect(recommendationHarnessSafetyCategory('verified_import', true))
    .toBe('RECOMMENDATION_ONLY_NO_RULEBOOK_IMPORT')
  expect(() => assertImportRequirementCompatible('ready_public', false, true)).toThrow(
    'Fresh import cannot be required in ready_public journey mode',
  )
  expect(() => assertImportRequirementCompatible('ready_public', true, true)).not.toThrow()
  expect(() => assertImportRequirementCompatible('verified_import', false, true)).not.toThrow()
  expect(requiredProductionModelRoles('ready_public', false))
    .toEqual(['recommendation', 'answer'])
  expect(requiredProductionModelRoles('verified_import', false))
    .toEqual(['recommendation', 'teaching', 'visual', 'answer'])
  expect(requiredProductionModelRoles('ready_public', true))
    .toEqual(['recommendation'])
})

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

test('recommendation-only acceptance distinguishes semantic terminals from an unfinished wait', () => {
  const clientTurnId = 'f5da76a6-d94e-4a43-8dca-8336b5ba9c21'
  const result = (outcome: RecommendationOutcome, revision = 2): RecommendationSessionResponse => ({
    conversationId: '3d6fef52-521b-47ea-a5d4-ea980159c820',
    revision,
    processing: false,
    latestResponse: {
      clientTurnId,
      outcome,
      assistantMessage: outcome === 'recommendations' ? 'Here are two choices.' : 'No result.',
      games: outcome === 'recommendations'
        ? [
            { game: { bggId: 11, name: 'Dune: Harbor', originalName: 'Dune: Harbor' } },
            { game: { bggId: 22, name: '沙丘：边境', originalName: 'Dune: Frontier' } },
          ]
        : [],
    },
  })

  expect(classifyRecommendationTerminal(result('recommendations'), 1, clientTurnId, 19_999))
    .toMatchObject({ category: 'PERSISTED_RECOMMENDATIONS', elapsedMs: 19_999 })
  expect(classifyRecommendationTerminal(result('recommendations'), 1, clientTurnId, 20_001))
    .toMatchObject({ category: 'PERSISTED_RECOMMENDATIONS', elapsedMs: 20_001 })
  expect(classifyRecommendationTerminal(result('unavailable'), 1, clientTurnId, 4_000)?.category)
    .toBe('SEMANTIC_UNAVAILABLE')
  expect(classifyRecommendationTerminal(result('no_match'), 1, clientTurnId, 4_000)?.category)
    .toBe('SEMANTIC_NON_RECOMMENDATION')
  expect(classifyRecommendationTerminal(
    { ...result('recommendations'), processing: true },
    1,
    clientTurnId,
    4_000,
  ))
    .toBeNull()
  expect(classifyRecommendationTerminal(result('recommendations', 1), 1, clientTurnId, 4_000)).toBeNull()
  expect(classifyRecommendationTerminal(result('recommendations'), 1, crypto.randomUUID(), 4_000))
    .toBeNull()

  const persistedRecommendations = classifyRecommendationTerminal(
    result('recommendations'),
    1,
    clientTurnId,
    21_000,
  )!
  expect(diagnoseRecommendationTerminal(persistedRecommendations, {
    visible: true, observedMs: 19_999, count: 3, bggIds: [11, 22, 33],
  })).toBe('RECOMMENDATIONS_WITHIN_INTERACTION_BUDGET')
  expect(diagnoseRecommendationTerminal(persistedRecommendations, {
    visible: true, observedMs: 20_001, count: 3, bggIds: [11, 22, 33],
  })).toBe('RECOMMENDATIONS_OVER_INTERACTION_BUDGET')
  expect(diagnoseRecommendationTerminal(persistedRecommendations, {
    visible: false, observedMs: 50_000, count: 2, bggIds: [11, 22],
  })).toBe('PERSISTED_RECOMMENDATIONS_NOT_RENDERED')
  expect(diagnoseRecommendationTerminal(
    classifyRecommendationTerminal(result('unavailable'), 1, clientTurnId, 4_000)!,
    { visible: false, observedMs: 4_000, count: 0, bggIds: [] },
  )).toBe('SEMANTIC_UNAVAILABLE')

  expect(sameTypedBggSlate([11, 22], [11, 22])).toBe(true)
  expect(sameTypedBggSlate([11, 22], [22, 11])).toBe(false)
  expect(sameTypedBggSlate([11, 22, 33], [11, 22])).toBe(false)
  expect(sameTypedBggSlate([11, Number.NaN], [11, 22])).toBe(false)
})

test('recommendation slate waits for its delayed persisted typed target before accepting cards', async () => {
  let resolvePersistedTarget!: (bggIds: number[]) => void
  const persistedTarget = new Promise<number[]>(resolve => {
    resolvePersistedTarget = resolve
  })
  let cardReads = 0
  const cards = {
    evaluateAll: async () => {
      cardReads += 1
      return [11, 22]
    },
  } as unknown as Locator
  setTimeout(() => resolvePersistedTarget([11, 22]), 0)

  const observation = await observeRecommendationSlate(
    cards,
    persistedTarget,
    performance.now(),
    Date.now() + 1_000,
  )

  expect(cardReads).toBeGreaterThan(1)
  expect(observation).toMatchObject({
    visible: true,
    count: 2,
    bggIds: [11, 22],
  })
})

test('recommendation slate waits for a delayed typed no-slate terminal', async () => {
  let resolvePersistedTarget!: (bggIds: null) => void
  const persistedTarget = new Promise<null>(resolve => {
    resolvePersistedTarget = resolve
  })
  let cardReads = 0
  const cards = {
    evaluateAll: async () => {
      cardReads += 1
      return []
    },
  } as unknown as Locator
  setTimeout(() => resolvePersistedTarget(null), 0)

  const observation = await observeRecommendationSlate(
    cards,
    persistedTarget,
    performance.now(),
    Date.now() + 1_000,
  )

  expect(cardReads).toBeGreaterThan(1)
  expect(observation).toMatchObject({
    visible: false,
    count: 0,
    bggIds: [],
  })
})

test('recommendation slate times out an unsettled persisted target without throwing', async () => {
  const cards = {
    evaluateAll: async () => [],
  } as unknown as Locator
  const persistedTarget = new Promise<number[] | null>(() => undefined)

  await expect(observeRecommendationSlate(
    cards,
    persistedTarget,
    performance.now(),
    Date.now() - 1,
  )).resolves.toMatchObject({
    visible: false,
    count: 0,
    bggIds: [],
  })
})

test('lesson terminal classification preserves readable cited drafts without claiming full support', () => {
  const lesson = (
    status: LessonMilestoneResponse['status'],
    evidenceStatuses: Array<LessonMilestoneResponse['sections'][number]['evidenceStatus']>,
  ): LessonMilestoneResponse => ({
    id: 'lesson-1',
    teachingPlanId: 'plan-1',
    status,
    sections: evidenceStatuses.map((evidenceStatus, index) => ({
      position: index + 1,
      title: `Section ${index + 1}`,
      evidenceStatus,
    })),
  })

  expect(classifyReadableLessonTerminal('COMPLETED', lesson('COMPLETE', [
    'SUPPORTED',
    'SUPPORTED',
  ]))).toMatchObject({
    category: 'FULLY_SUPPORTED',
    readable: true,
    fullySupported: true,
    citedDraftSectionCount: 0,
    insufficientSectionCount: 0,
  })
  expect(classifyReadableLessonTerminal('DEGRADED', lesson('DRAFT_READY', [
    'SUPPORTED',
    'CITED_DRAFT',
    'INSUFFICIENT_EVIDENCE',
  ]))).toMatchObject({
    category: 'READABLE_WITH_DEGRADATION',
    readable: true,
    fullySupported: false,
    citedDraftSectionCount: 1,
    insufficientSectionCount: 1,
  })
  expect(classifyReadableLessonTerminal('FAILED', lesson('DRAFT_READY', ['CITED_DRAFT'])))
    .toMatchObject({ category: 'READABLE_WITH_DEGRADATION', readable: true, fullySupported: false })
  expect(classifyReadableLessonTerminal(
    'INSUFFICIENT_EVIDENCE',
    lesson('INCOMPLETE', ['INSUFFICIENT_EVIDENCE']),
  )).toBeNull()
})

test('opening diagnostics distinguish budget, semantic, unavailable, processing, and read boundaries', () => {
  const clientTurnId = 'f5da76a6-d94e-4a43-8dca-8336b5ba9c21'
  const terminal = (outcome: RecommendationOutcome, failureBoundary: string | null = null): OpeningTerminalRead => ({
    session: {
      conversationId: '3d6fef52-521b-47ea-a5d4-ea980159c820',
      revision: 2,
      processing: false,
      latestResponse: {
        clientTurnId,
        outcome,
        assistantMessage: '',
        modelCalls: 1,
        catalogCalls: 0,
        failureBoundary,
        games: [],
      },
    },
    terminal: {
      clientTurnId,
      outcome,
      assistantMessage: '',
      modelCalls: 1,
      catalogCalls: 0,
      failureBoundary,
      games: [],
    },
    observedMs: 1_000,
    readFailed: false,
  })

  expect(diagnoseOpeningTerminal(terminal('conversation'), true, true)).toMatchObject({
    outcome: 'conversation', terminalCategory: 'GUIDANCE_RENDERED_WITHIN_BUDGET',
  })
  expect(diagnoseOpeningTerminal(terminal('conversation'), true, false)).toMatchObject({
    terminalCategory: 'GUIDANCE_RENDERED_OVER_BUDGET',
  })
  expect(diagnoseOpeningTerminal(terminal('conversation'), false, false)).toMatchObject({
    terminalCategory: 'GUIDANCE_NOT_RENDERED',
  })
  expect(diagnoseOpeningTerminal(terminal('conversation'), false, true)).toMatchObject({
    terminalCategory: 'GUIDANCE_NOT_RENDERED',
  })
  expect(diagnoseOpeningTerminal(terminal('recommendations'), true, true)).toMatchObject({
    outcome: 'recommendations', terminalCategory: 'SEMANTIC_RECOMMENDATIONS',
  })
  expect(diagnoseOpeningTerminal(terminal('unavailable', 'model_response'), true, true)).toMatchObject({
    terminalCategory: 'UNAVAILABLE_WITH_FAILURE_BOUNDARY',
    modelCalls: 1,
    catalogCalls: 0,
    failureBoundary: 'model_response',
  })
  expect(publicFailureBoundary('time_budget')).toBe('time_budget')
  expect(publicFailureBoundary('service_configuration')).toBe('service_configuration')
  expect(publicFailureBoundary('MODEL_CALL_FAILED')).toBeNull()
  expect(publicFailureBoundary('unknown_boundary')).toBeNull()
  expect(diagnoseOpeningTerminal({
    session: { ...terminal('conversation').session!, processing: true },
    terminal: null,
    observedMs: 20_000,
    readFailed: false,
  }, false, false)).toMatchObject({ terminalCategory: 'STILL_PROCESSING' })
  expect(diagnoseOpeningTerminal({
    session: null, terminal: null, observedMs: 20_000, readFailed: true,
  }, false, false)).toMatchObject({ terminalCategory: 'READ_FAILURE' })

  expect(openingRenderMetrics(
    { visible: false, observedMs: 14_965 },
    { visible: false, observedMs: 18_226 },
  )).toEqual({
    firstSafeTextRendered: false,
    firstSafeTextMs: null,
    observationElapsedMs: 18_226,
    resultRenderedMs: null,
    sloMet: false,
  })
  expect(openingRenderMetrics(
    { visible: true, observedMs: 14_999 },
    { visible: true, observedMs: 15_150 },
  )).toEqual({
    firstSafeTextRendered: true,
    firstSafeTextMs: 14_999,
    observationElapsedMs: 14_999,
    resultRenderedMs: 15_150,
    sloMet: true,
  })
  expect(openingRenderMetrics(
    { visible: true, observedMs: 18_226 },
    { visible: true, observedMs: 18_350 },
  )).toMatchObject({
    firstSafeTextRendered: true,
    firstSafeTextMs: 18_226,
    resultRenderedMs: 18_350,
    sloMet: false,
  })
  expect(openingRenderMetrics(
    { visible: true, observedMs: 15_100 },
    { visible: true, observedMs: 14_990 },
  )).toMatchObject({
    firstSafeTextMs: 14_990,
    observationElapsedMs: 14_990,
    resultRenderedMs: 14_990,
    sloMet: true,
  })
})

test('ready continuation availability exactly matches its published typed card attachments', () => {
  const result = (bggId: number, ready = false): RecommendationResultGame => ({
    game: { bggId, name: `Game ${bggId}`, originalName: `Game ${bggId}` },
    teachingContinuation: ready
      ? { teachingPlanId: crypto.randomUUID(), sectionCount: 3, stepCount: 9 }
      : null,
  })
  const continuation = (
    availability: RecommendationContinuationAvailability,
    readyCount: number,
    candidateCount = 2,
  ): RecommendationContinuationResult => ({
    kind: 'guide_and_rule_qa',
    learningGoal: 'Explain setup, then continue with cited Q&A.',
    availability,
    readyCount,
    candidateCount,
  })

  expect(readyContinuationMatchesPublishedGames(
    continuation('available_for_all', 2),
    [result(11, true), result(22, true)],
  )).toBe(true)
  expect(readyContinuationMatchesPublishedGames(
    continuation('available_for_some', 1),
    [result(11, true), result(22)],
  )).toBe(true)
  expect(readyContinuationMatchesPublishedGames(
    continuation('no_ready_candidate', 0),
    [result(11), result(22)],
  )).toBe(true)
  expect(readyContinuationMatchesPublishedGames(
    continuation('availability_unavailable', 0),
    [result(11), result(22)],
  )).toBe(true)

  expect(readyContinuationMatchesPublishedGames(
    continuation('available_for_all', 1),
    [result(11, true), result(22)],
  )).toBe(false)
  expect(readyContinuationMatchesPublishedGames(
    continuation('available_for_some', 2),
    [result(11, true), result(22, true)],
  )).toBe(false)
  expect(readyContinuationMatchesPublishedGames(
    continuation('no_ready_candidate', 0, 3),
    [result(11), result(22)],
  )).toBe(false)
  expect(readyContinuationMatchesPublishedGames(
    { ...continuation('available_for_some', 1), learningGoal: '  ' },
    [result(11, true), result(22)],
  )).toBe(true)
  expect(readyContinuationMatchesPublishedGames(
    { ...continuation('available_for_some', 1), learningGoal: null as unknown as string },
    [result(11, true), result(22)],
  )).toBe(false)
  expect(readyContinuationMatchesPublishedGames(
    { ...continuation('available_for_some', 1), learningGoal: 42 as unknown as string },
    [result(11, true), result(22)],
  )).toBe(false)
  expect(readyContinuationMatchesPublishedGames(
    continuation('available_for_some', 1),
    [{
      ...result(11, true),
      teachingContinuation: { teachingPlanId: 'not-a-plan', sectionCount: 3, stepCount: 9 },
    }, result(22)],
  )).toBe(false)
  expect(readyContinuationMatchesPublishedGames(
    continuation('available_for_some', 1),
    [{
      ...result(11, true),
      teachingContinuation: { teachingPlanId: crypto.randomUUID(), sectionCount: 0, stepCount: 9 },
    }, result(22)],
  )).toBe(false)
  expect(readyContinuationMatchesPublishedGames(
    {
      ...continuation('no_ready_candidate', 0),
      availability: 'unknown_availability' as RecommendationContinuationAvailability,
    },
    [result(11), result(22)],
  )).toBe(false)
})

test('authenticated citation image paths stay bound to the typed document version and first cited page', () => {
  expect(authenticatedCitationImagePath('version / one', {
    heading: 'Setup', pageFrom: 4, pageTo: 6,
  })).toBe('/api/v1/document-versions/version%20%2F%20one/pages/4/image')
})

test('published recommendation cards require positive distinct typed BGG identities', () => {
  const result = (bggId: number, name: string, originalName = name): RecommendationResultGame => ({
    game: { bggId, name, originalName },
  })
  expect(hasPositiveDistinctBggIds([result(11, 'First'), result(22, 'Second')])).toBe(true)
  expect(hasPositiveDistinctBggIds([result(11, 'First')])).toBe(true)
  expect(hasPositiveDistinctBggIds([])).toBe(false)
  expect(hasPositiveDistinctBggIds([result(11, 'First'), result(11, 'First')])).toBe(false)
  expect(hasPositiveDistinctBggIds([result(11, 'First'), result(0, 'Invalid')])).toBe(false)
  expect(everyPublishedGameMatchesTitleTerm(
    [result(11, '沙丘：帝国', 'Dune: Imperium'), result(22, 'Dune: Uprising')],
    'dune',
  )).toBe(true)
  expect(everyPublishedGameMatchesTitleTerm(
    [result(11, 'Dune: Imperium'), result(22, 'Unrelated Game')],
    'dune',
  )).toBe(false)
  expect(everyPublishedGameMatchesTitleTerm([result(11, 'Dunescape')], 'dune')).toBe(false)
  expect(everyPublishedGameMatchesTitleTerm([result(11, 'Any Game')], '')).toBe(true)
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
  if (!username || !password || !reportFile) {
    throw new Error('Production recommendation credentials and report path are required')
  }
  if (!/^[0-9a-f]{40}$/.test(TESTED_SHA)
    || !/^[0-9a-f]{40}$/.test(ACTIVE_RELEASE_SHA)
    || TESTED_SHA !== ACTIVE_RELEASE_SHA) {
    throw new Error('Production recommendation verification requires one exact active tested SHA')
  }
  if (RECOMMENDATION_ONLY && EXPECTED_RECOMMENDATION_TITLE_TERM === '') {
    throw new Error('Recommendation-only production verification requires an expected title term')
  }
  assertImportRequirementCompatible(JOURNEY_MODE, RECOMMENDATION_ONLY, REQUIRE_FRESH_IMPORT)

  const pageErrors: Error[] = []
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
  page.on('pageerror', error => pageErrors.push(error))
  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/documents/official-imports' && request.method() === 'POST') {
      importRequestCount += 1
      observedImportRequest = request.postDataJSON() as typeof observedImportRequest
    }
  })

  const report: ProductionJourneyReport = {
    generatedAt: new Date().toISOString(), completed: false, stage: 'login',
    testedSha: TESTED_SHA, activeReleaseSha: ACTIVE_RELEASE_SHA,
    journeyMode: JOURNEY_MODE,
    recommendationOnly: RECOMMENDATION_ONLY,
    requireFreshImport: REQUIRE_FRESH_IMPORT,
    recommendationHarnessSafetyCategory: recommendationHarnessSafetyCategory(
      JOURNEY_MODE,
      RECOMMENDATION_ONLY,
    ),
    selectedBggId: null, selectedGameName: null,
    recommendationConversationId: null, openGuidanceClientTurnId: null,
    openGuidanceOutcome: null, openGuidanceTerminalCategory: null,
    openGuidanceTerminalObserved: false, openGuidanceTerminalObservedMs: null,
    openGuidanceFirstSafeTextRendered: false, openGuidanceFirstSafeTextMs: null,
    openGuidanceObservationElapsedMs: null, openGuidanceResultRenderedMs: null,
    openGuidanceSloMet: null, openGuidanceModelCalls: null,
    openGuidanceCatalogCalls: null, openGuidanceFailureBoundary: null,
    readyTeachingRequested: false, readyTeachingAvailability: null,
    readyTeachingReadyCount: 0, readyTeachingCandidateCount: 0,
    renderedReadyTeachingCardCount: 0,
    readyTeachingGuideOpened: false, readyTeachingQuestionsOpened: false,
    readyTeachingFailureCategory: null, publicAnswerRequestSucceeded: false,
    modelAssignments: null, recommendationModelProvider: null, recommendationModel: null,
    visualModelVisionCapable: null,
    routeStayedOnDiscover: false, journeyBackdropVisible: false, journeySurfaceOpaque: false,
    lessonBackdropVisible: false, lessonSurfaceOpaque: false,
    confirmedMilestonesAtSourceReview: 0, confirmedMilestonesFinal: 0,
    boundGameInCatalog: false, boundBggId: null, boundGameName: null, boundEditionId: null,
    documentVersionId: null, teachingPlanId: null, answerSessionId: null,
    firstAnswerTurnId: null, followUpAnswerTurnId: null,
    candidateEditionMatchesSelection: false, importEditionMatchesSelection: false,
    documentEditionMatchesSelection: false, myGuidesEntryVisibleBeforeLesson: false, myGuidesPlanListed: false,
    planGameTitleMatchesSelection: false, globalStatusVisibleAfterClosing: false, globalStatusReopened: false,
    recommendationMs: null,
    recommendationStartedAt: null, recommendationTerminalObservedAt: null,
    recommendationElapsedBasis: 'NOT_OBSERVED', recommendationOutcome: null,
    recommendationRequestedCardCount: REQUESTED_RECOMMENDATION_CARD_COUNT,
    recommendationPersistedCardCount: null, recommendationShortfallCount: null,
    recommendationSlateRendered: false, recommendationSlateMs: null,
    recommendationObservationElapsedMs: null, recommendationSloMet: null,
    recommendationPersistedTerminalObserved: false, recommendationPersistedTerminalMs: null,
    recommendationTerminalCategory: 'NOT_OBSERVED', recommendationCardCount: 0,
    expectedRecommendationTitleTerm: EXPECTED_RECOMMENDATION_TITLE_TERM,
    recommendationPublishedGames: [], recommendationCompletedWork: [], recommendationModelCalls: null,
    recommendationCatalogCalls: null, recommendationWebResearchCalls: null,
    recommendationPublicationRecovered: null, recommendationFailureBoundary: null,
    rawModelOutputCaptured: false,
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
    lessonStatus: null, lessonReadable: false, lessonFullySupported: null,
    lessonCompletionCategory: null, citedDraftSectionCount: 0,
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
    report.lessonReadable = progress.publishedSectionCount > 0
    report.citedDraftSectionCount = progress.citedDraftSectionCount
    report.insufficientSectionCount = progress.insufficientSectionCount
    report.generatedAt = new Date().toISOString()
    await retainReport(reportFile, report)
  }

  try {
    await login(page, username, password)
    report.stage = 'model-role-preflight'
    const modelConfigurationResponse = await page.request.get('/api/v1/model-configuration')
    expect(modelConfigurationResponse.ok(),
      `Model configuration returned HTTP ${modelConfigurationResponse.status()}`).toBe(true)
    const modelConfiguration = await modelConfigurationResponse.json() as ModelConfigurationResponse
    for (const role of requiredProductionModelRoles(JOURNEY_MODE, RECOMMENDATION_ONLY)) {
      const provider = configuredProductionRole(modelConfiguration, role)
      if (role === 'visual') {
        expect(provider.visionCapable,
          `Production visual provider '${provider.id}' cannot inspect rulebook page images`).toBe(true)
        report.visualModelVisionCapable = provider.visionCapable
      }
    }
    const recommendationProvider = configuredProductionRole(modelConfiguration, 'recommendation')
    expect(recommendationProvider.id,
      'Production recommendation role must use the measured low-latency provider').toBe('deepseek')
    expect(recommendationProvider.model,
      'Production recommendation role must use the paid-canary-verified model').toBe('deepseek-v4-flash')
    report.modelAssignments = modelConfiguration.assignments
    report.recommendationModelProvider = recommendationProvider.id
    report.recommendationModel = recommendationProvider.model
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
    let selectionBaselineRevision = createdConversation.revision
    if (!RECOMMENDATION_ONLY) {
      const guidanceTurnCount = await page.getByTestId('assistant-conversation-turn').count()
      const guidanceStartedAt = performance.now()
      const guidanceTerminalDeadlineAt = Date.now() + MAX_OPEN_TERMINAL_DIAGNOSTIC_MS
      const guidanceRequestPromise = page.waitForRequest(request => {
        const url = new URL(request.url())
        return url.pathname === '/api/v1/bgg/recommendation-agent/stream'
          && request.method() === 'POST'
      }, { timeout: MAX_OPEN_GUIDANCE_MS })
      await composer.fill(RECOMMENDATION_OPENING_PROMPT)
      await page.getByRole('button', { name: '发送', exact: true }).click()
      const firstSafeTextObservation = expect.poll(async () => {
        const renderedTurnCount = await page.getByTestId('assistant-conversation-turn').count()
        if (renderedTurnCount > guidanceTurnCount) return true
        const preview = page.getByTestId('recommendation-answer-preview')
        if (await preview.count() === 0) return false
        return Boolean((await preview.first().textContent())?.trim())
      }, {
        timeout: MAX_OPEN_TERMINAL_DIAGNOSTIC_MS,
        message: 'The unknown-target opening did not render validated player-safe text',
      }).toBe(true)
        .then(() => ({ visible: true, observedMs: elapsed(guidanceStartedAt) }), () => ({
          visible: false,
          observedMs: elapsed(guidanceStartedAt),
        }))
      const guidanceResultObservation = expect.poll(
        () => page.getByTestId('assistant-conversation-turn').count(),
        {
          timeout: MAX_OPEN_TERMINAL_DIAGNOSTIC_MS,
          message: 'The unknown-target opening did not render its completed result',
        },
      ).toBeGreaterThan(guidanceTurnCount)
        .then(() => ({ visible: true, observedMs: elapsed(guidanceStartedAt) }), () => ({
          visible: false,
          observedMs: elapsed(guidanceStartedAt),
        }))
      const guidanceRequest = await guidanceRequestPromise.catch(() => null)
      const guidanceRequestBody = guidanceRequest?.postDataJSON() as {
        conversationId?: unknown
        revision?: unknown
        clientTurnId?: unknown
      } | null
      const guidanceClientTurnId = typeof guidanceRequestBody?.clientTurnId === 'string'
        ? guidanceRequestBody.clientTurnId
        : null
      const guidanceExpectedRevision = Number.isSafeInteger(guidanceRequestBody?.revision)
        ? Number(guidanceRequestBody?.revision)
        : null
      report.openGuidanceClientTurnId = guidanceClientTurnId
      const guidanceIdentityValid = guidanceRequestBody?.conversationId === createdConversation.conversationId
        && guidanceExpectedRevision === createdConversation.revision
        && guidanceClientTurnId !== null
      const guidanceTerminalPromise = guidanceIdentityValid
        ? readOpeningPersistedTerminal(
            page.request,
            createdConversation.conversationId,
            guidanceExpectedRevision!,
            guidanceClientTurnId!,
            guidanceStartedAt,
            guidanceTerminalDeadlineAt,
          )
        : Promise.resolve({
            session: null,
            terminal: null,
            observedMs: elapsed(guidanceStartedAt),
            readFailed: true,
          } satisfies OpeningTerminalRead)
      const [firstSafeText, guidanceResult, guidanceTerminal] = await Promise.all([
        firstSafeTextObservation,
        guidanceResultObservation,
        guidanceTerminalPromise,
      ])
      const renderMetrics = openingRenderMetrics(firstSafeText, guidanceResult)
      report.openGuidanceFirstSafeTextRendered = renderMetrics.firstSafeTextRendered
      report.openGuidanceFirstSafeTextMs = renderMetrics.firstSafeTextMs
      report.openGuidanceObservationElapsedMs = renderMetrics.observationElapsedMs
      report.openGuidanceResultRenderedMs = renderMetrics.resultRenderedMs
      report.openGuidanceSloMet = renderMetrics.sloMet
      report.openGuidanceTerminalObserved = guidanceTerminal.terminal !== null
      report.openGuidanceTerminalObservedMs = guidanceTerminal.terminal === null
        ? null
        : guidanceTerminal.observedMs
      const guidanceDiagnostic = diagnoseOpeningTerminal(
        guidanceTerminal,
        guidanceResult.visible,
        report.openGuidanceSloMet === true,
      )
      report.openGuidanceOutcome = guidanceDiagnostic.outcome
      report.openGuidanceTerminalCategory = guidanceDiagnostic.terminalCategory
      report.openGuidanceModelCalls = guidanceDiagnostic.modelCalls
      report.openGuidanceCatalogCalls = guidanceDiagnostic.catalogCalls
      report.openGuidanceFailureBoundary = guidanceDiagnostic.failureBoundary
      await retainReport(reportFile, report)
      expect(guidanceIdentityValid,
        'Opening recommendation request did not preserve its conversation, revision, and client turn identity').toBe(true)
      expect.soft(report.openGuidanceSloMet,
        'Open recommendation guidance exceeded its interaction budget').toBe(true)
      expect([
        'GUIDANCE_RENDERED_WITHIN_BUDGET',
        'GUIDANCE_RENDERED_OVER_BUDGET',
      ],
        'The unknown-target opening did not finish as useful guidance')
        .toContain(guidanceDiagnostic.terminalCategory)
      await expect(page.getByTestId('assistant-conversation-turn').last()).toContainText(/\S/)
      await expect(recommendationCards).toHaveCount(0)
      const guidanceSession = guidanceTerminal.session
      expect(guidanceSession, 'Opening recommendation terminal was not readable from the persisted session').not.toBeNull()
      if (!guidanceSession) throw new Error('Opening recommendation terminal was not readable from the persisted session')
      expect(['conversation', 'needs_clarification'],
        'The unknown-target opening did not finish as useful guidance').toContain(report.openGuidanceOutcome)
      selectionBaselineRevision = guidanceSession.revision
    }

    await composer.fill(RECOMMENDATION_SELECTION_PROMPT)
    const recommendationStartedAt = performance.now()
    const selectionDiagnosticDeadlineAt = Date.now() + MAX_SELECTION_TERMINAL_OBSERVATION_MS
    const selectionRequestedCardCount = REQUESTED_RECOMMENDATION_CARD_COUNT
    let persistedSlateSettled = false
    let resolvePersistedBggIds!: (bggIds: number[] | null) => void
    const persistedBggIds = new Promise<number[] | null>(resolve => {
      resolvePersistedBggIds = resolve
    })
    const settlePersistedBggIds = (bggIds: number[] | null) => {
      if (persistedSlateSettled) return
      persistedSlateSettled = true
      resolvePersistedBggIds(bggIds)
    }
    report.recommendationStartedAt = new Date().toISOString()
    report.recommendationRequestedCardCount = selectionRequestedCardCount
    const selectionRequestPromise = page.waitForRequest(request => {
      const url = new URL(request.url())
      return url.pathname === '/api/v1/bgg/recommendation-agent/stream'
        && request.method() === 'POST'
    })
    await page.getByRole('button', { name: '发送', exact: true }).click()
    const recommendationCardsVisible = observeRecommendationSlate(
      recommendationCards,
      persistedBggIds,
      recommendationStartedAt,
      selectionDiagnosticDeadlineAt,
    )
    let selectionClientTurnId: string
    try {
      const selectionRequest = await selectionRequestPromise
      const selectionRequestBody = selectionRequest.postDataJSON() as { clientTurnId?: unknown }
      const candidateClientTurnId = selectionRequestBody.clientTurnId
      if (typeof candidateClientTurnId !== 'string'
        || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
          .test(candidateClientTurnId)) {
        throw new Error('The recommendation selection request has no valid v4 clientTurnId')
      }
      selectionClientTurnId = candidateClientTurnId
    } catch (failure) {
      settlePersistedBggIds(null)
      await recommendationCardsVisible.catch(() => undefined)
      throw failure
    }

    const selectionTerminalPromise = waitForPersistedRecommendationTerminal(
      page.request,
      createdConversation.conversationId,
      selectionBaselineRevision,
      selectionClientTurnId,
      recommendationStartedAt,
      selectionDiagnosticDeadlineAt,
    )
    void selectionTerminalPromise.then(
      terminal => {
        const result = terminal.session?.latestResponse
        settlePersistedBggIds(result?.outcome === 'recommendations'
          ? result.games.map(entry => entry.game.bggId)
          : null)
      },
      () => settlePersistedBggIds(null),
    )

    if (RECOMMENDATION_ONLY) {
      const [terminal, visibleCards] = await Promise.all([
        selectionTerminalPromise,
        recommendationCardsVisible,
      ])
      report.recommendationSlateRendered = visibleCards.visible
      report.recommendationSlateMs = visibleCards.visible ? visibleCards.observedMs : null
      report.recommendationObservationElapsedMs = visibleCards.observedMs
      report.recommendationSloMet = visibleCards.visible
        && visibleCards.observedMs <= MAX_SELECTION_RECOMMENDATION_MS
      report.recommendationPersistedTerminalObserved = terminal.session !== null
      report.recommendationPersistedTerminalMs = terminal.session === null ? null : terminal.elapsedMs
      report.recommendationMs = report.recommendationSlateMs ?? report.recommendationPersistedTerminalMs
      report.recommendationTerminalObservedAt = terminal.session === null ? null : new Date().toISOString()
      report.recommendationElapsedBasis = visibleCards.visible
        ? 'UI_CARD_RENDER'
        : terminal.session === null ? 'NOT_OBSERVED' : 'PERSISTED_FINAL_SESSION'
      report.recommendationTerminalCategory = diagnoseRecommendationTerminal(terminal, visibleCards)
      report.recommendationOutcome = terminal.session?.latestResponse?.outcome ?? null
      const terminalGames = Array.isArray(terminal.session?.latestResponse?.games)
        ? terminal.session.latestResponse.games
        : []
      report.recommendationPersistedCardCount = terminal.session?.latestResponse?.outcome === 'recommendations'
        ? terminalGames.length
        : null
      report.recommendationShortfallCount = report.recommendationPersistedCardCount === null
        ? null
        : Math.max(0, selectionRequestedCardCount - report.recommendationPersistedCardCount)
      const persistedResult = terminal.session?.latestResponse ?? null
      report.recommendationCardCount = visibleCards.count
      report.recommendationPublishedGames = terminalGames.map(entry => ({
        bggId: entry.game.bggId,
        name: entry.game.name,
        originalName: entry.game.originalName,
      }))
      report.recommendationCompletedWork = Array.isArray(persistedResult?.completedWork)
        ? persistedResult.completedWork
        : []
      report.recommendationModelCalls = typeof persistedResult?.modelCalls === 'number'
        ? persistedResult.modelCalls
        : null
      report.recommendationCatalogCalls = typeof persistedResult?.catalogCalls === 'number'
        ? persistedResult.catalogCalls
        : null
      report.recommendationWebResearchCalls = typeof persistedResult?.webResearchCalls === 'number'
        ? persistedResult.webResearchCalls
        : null
      report.recommendationPublicationRecovered = typeof persistedResult?.publicationRecovered === 'boolean'
        ? persistedResult.publicationRecovered
        : null
      report.recommendationFailureBoundary = persistedResult?.failureBoundary ?? null
      await retainReport(reportFile, report)

      const finalResult = terminal.session?.latestResponse
      expect(finalResult?.outcome, 'The persisted recommendation result was not recommendations')
        .toBe('recommendations')
      expect.soft(report.recommendationSloMet,
        `Recommendation cards exceeded the ${MAX_SELECTION_RECOMMENDATION_MS}ms interaction SLO`)
        .toBe(true)
      expect([
        'RECOMMENDATIONS_WITHIN_INTERACTION_BUDGET',
        'RECOMMENDATIONS_OVER_INTERACTION_BUDGET',
      ], 'Persisted recommendation cards never became player-visible in the 50-second diagnostic window')
        .toContain(report.recommendationTerminalCategory)
      expect(report.recommendationOutcome,
        'Recommendation-only verification must not accept an unavailable terminal').not.toBe('unavailable')
      expect(finalResult, 'The persisted recommendation result is missing').not.toBeNull()
      expect(finalResult?.assistantMessage.trim().length,
        'The persisted recommendation result has no complete published player reply').toBeGreaterThan(0)
      expect(Number.isSafeInteger(finalResult?.modelCalls) && Number(finalResult?.modelCalls) > 0,
        'Recommendation-only evidence requires a positive model call count').toBe(true)
      expect(Number.isSafeInteger(finalResult?.catalogCalls) && Number(finalResult?.catalogCalls) > 0,
        'Recommendation-only evidence requires a positive catalog call count').toBe(true)
      expect(Number.isSafeInteger(finalResult?.webResearchCalls) && Number(finalResult?.webResearchCalls) >= 0,
        'Recommendation-only evidence requires a non-negative web-research call count').toBe(true)
      expect(typeof finalResult?.publicationRecovered,
        'Recommendation-only evidence must say whether deterministic publication recovery ran').toBe('boolean')
      expect(finalResult?.failureBoundary,
        'A successful recommendation must not carry a failure boundary').toBeNull()
      expect(finalResult?.completedWork,
        'A successful recommendation must expose its public completion summary').toContain('recommend_games')
      expect(hasPositiveDistinctBggIds(terminalGames),
        'Every persisted recommendation needs a positive, distinct BGG identity').toBe(true)
      expect(terminalGames.every(({ game }) => [game.name, game.originalName]
        .some(title => typeof title === 'string' && title.trim().length > 0)),
      'Every persisted recommendation card needs a public title identity').toBe(true)
      expect(everyPublishedGameMatchesTitleTerm(terminalGames, EXPECTED_RECOMMENDATION_TITLE_TERM),
        `Every persisted card must match expected title term: ${EXPECTED_RECOMMENDATION_TITLE_TERM || '(none)'}`)
        .toBe(true)

      await expect.poll(() => recommendationCards.count(), {
        timeout: 1_000,
        message: 'The accepted persisted recommendation result did not render as cards',
      }).toBe(terminalGames.length)
      report.recommendationCardCount = await recommendationCards.count()
      expect(report.recommendationCardCount).toBeGreaterThan(0)
      const renderedBggIds = await recommendationCards.evaluateAll(cards => cards.map(card =>
        Number(card.getAttribute('data-bgg-id'))))
      expect(renderedBggIds.every(id => Number.isSafeInteger(id) && id > 0),
        'A rendered recommendation card has no positive typed BGG identity').toBe(true)
      expect(new Set(renderedBggIds).size,
        'Rendered recommendation cards repeated a BGG identity').toBe(renderedBggIds.length)
      expect(sameTypedBggSlate(renderedBggIds, terminalGames.map(entry => entry.game.bggId)),
        'Rendered recommendation cards diverged from the persisted typed slate').toBe(true)
      expect(importRequestCount,
        'Recommendation-only verification must not start a rulebook import').toBe(0)
      expect(pageErrors, 'The recommendation-only journey emitted uncaught browser errors').toEqual([])
      await expect(page).toHaveURL(/\/discover$/)
      report.routeStayedOnDiscover = true
      report.pageErrorCount = pageErrors.length
      report.completed = true
      report.stage = 'completed-recommendation-only'
      await retainReport(reportFile, report)
      return
    }

    const [selectionTerminal, selectionSlate] = await Promise.all([
      selectionTerminalPromise,
      recommendationCardsVisible,
    ])
    const selectionResult = selectionTerminal.session?.latestResponse ?? null
    report.recommendationSlateRendered = selectionSlate.visible
    report.recommendationSlateMs = selectionSlate.visible ? selectionSlate.observedMs : null
    report.recommendationObservationElapsedMs = selectionSlate.observedMs
    report.recommendationSloMet = selectionSlate.visible
      && selectionSlate.observedMs <= MAX_SELECTION_RECOMMENDATION_MS
    report.recommendationPersistedTerminalObserved = selectionTerminal.session !== null
    report.recommendationPersistedTerminalMs = selectionTerminal.session === null
      ? null
      : selectionTerminal.elapsedMs
    report.recommendationMs = report.recommendationSlateMs ?? report.recommendationPersistedTerminalMs
    report.recommendationTerminalObservedAt = selectionTerminal.session === null
      ? null
      : new Date().toISOString()
    report.recommendationElapsedBasis = selectionSlate.visible
      ? 'UI_CARD_RENDER'
      : selectionTerminal.session === null ? 'NOT_OBSERVED' : 'PERSISTED_FINAL_SESSION'
    report.recommendationOutcome = selectionResult?.outcome ?? null
    report.recommendationTerminalCategory = diagnoseRecommendationTerminal(
      selectionTerminal,
      selectionSlate,
    )
    report.recommendationCardCount = selectionSlate.count
    report.recommendationPersistedCardCount = selectionResult?.outcome === 'recommendations'
      ? selectionResult.games.length
      : null
    report.recommendationShortfallCount = report.recommendationPersistedCardCount === null
      ? null
      : Math.max(0, selectionRequestedCardCount - report.recommendationPersistedCardCount)
    report.recommendationPublishedGames = selectionResult?.games.map(entry => ({
      bggId: entry.game.bggId,
      name: entry.game.name,
      originalName: entry.game.originalName,
    })) ?? []
    report.recommendationCompletedWork = selectionResult?.completedWork ?? []
    report.recommendationModelCalls = publicNonNegativeInteger(selectionResult?.modelCalls)
    report.recommendationCatalogCalls = publicNonNegativeInteger(selectionResult?.catalogCalls)
    report.recommendationWebResearchCalls = publicNonNegativeInteger(selectionResult?.webResearchCalls)
    report.recommendationPublicationRecovered = typeof selectionResult?.publicationRecovered === 'boolean'
      ? selectionResult.publicationRecovered
      : null
    report.recommendationFailureBoundary = publicFailureBoundary(selectionResult?.failureBoundary)
    await retainReport(reportFile, report)

    expect.soft(
      report.recommendationSloMet,
      'The complete recommendation slate exceeded the 20-second interaction budget',
    ).toBe(true)
    if (selectionResult?.outcome !== 'recommendations') {
      throw new Error(`The persisted selection terminal was ${selectionTerminal.category}, not recommendations`)
    }
    if (!selectionSlate.visible) {
      throw new Error(
        `The persisted recommendation slate did not render its ${selectionResult.games.length} published cards within the 50-second diagnostic window`,
      )
    }
    expect(hasPositiveDistinctBggIds(selectionResult.games),
      'Every persisted recommendation needs a positive, distinct BGG identity').toBe(true)
    await expect.poll(() => recommendationCards.count(), {
      timeout: 1_000,
      message: 'The accepted persisted recommendation result did not render its exact published slate',
    }).toBe(selectionResult.games.length)
    const renderedSelectionBggIds = await recommendationCards.evaluateAll(cards => cards.map(card =>
      Number(card.getAttribute('data-bgg-id'))))
    const persistedSelectionBggIds = selectionResult.games.map(entry => entry.game.bggId)
    if (!sameTypedBggSlate(renderedSelectionBggIds, persistedSelectionBggIds)) {
      throw new Error('Rendered and persisted recommendation BGG identities diverged')
    }
    await composer.fill(PRESERVED_DRAFT)

    if (REQUIRE_READY_TEACHING) {

    const typedContinuation = selectionResult.continuation ?? null
    report.readyTeachingRequested = typedContinuation?.kind === 'guide_and_rule_qa'
    report.readyTeachingAvailability = typedContinuation?.availability ?? null
    report.readyTeachingReadyCount = typedContinuation?.readyCount ?? 0
    report.readyTeachingCandidateCount = typedContinuation?.candidateCount ?? 0
    if (!typedContinuation || !report.readyTeachingRequested) {
      report.readyTeachingFailureCategory = 'READY_TEACHING_NOT_REQUESTED'
      throw new Error('READY_TEACHING_NOT_REQUESTED: the explicit guide-and-Q&A request was not preserved')
    }
    if (!readyContinuationMatchesPublishedGames(typedContinuation, selectionResult.games)) {
      report.readyTeachingFailureCategory = 'READY_TEACHING_NOT_ATTACHED'
      throw new Error(
        `READY_TEACHING_NOT_ATTACHED: availability=${typedContinuation.availability}, ready=${typedContinuation.readyCount}, candidates=${typedContinuation.candidateCount}, games=${selectionResult.games.length}`,
      )
    }
    if (typedContinuation.availability === 'no_ready_candidate') {
      report.readyTeachingFailureCategory = 'READY_TEACHING_NO_CANDIDATE'
      throw new Error(
        'READY_TEACHING_NO_CANDIDATE: no verified public guide matched the recommendation slate',
      )
    }
    if (typedContinuation.availability === 'availability_unavailable') {
      report.readyTeachingFailureCategory = 'READY_TEACHING_AVAILABILITY_UNAVAILABLE'
      throw new Error(
        'READY_TEACHING_AVAILABILITY_UNAVAILABLE: public-guide availability could not be determined',
      )
    }

    const readyGames = selectionResult.games.filter(entry => entry.teachingContinuation != null)
    if (readyGames.length === 0 || readyGames.length !== typedContinuation.readyCount) {
      report.readyTeachingFailureCategory = 'READY_TEACHING_NOT_ATTACHED'
      throw new Error(
        `READY_TEACHING_NOT_ATTACHED: terminal declared ${typedContinuation.readyCount} ready cards but attached ${readyGames.length}`,
      )
    }
    const renderedReadyTeaching = page.getByTestId('open-ready-teaching')
    report.renderedReadyTeachingCardCount = await renderedReadyTeaching.count()
    if (report.renderedReadyTeachingCardCount !== readyGames.length) {
      report.readyTeachingFailureCategory = 'READY_TEACHING_LINK_NOT_RENDERED'
      throw new Error(
        `READY_TEACHING_LINK_NOT_RENDERED: persisted ${readyGames.length}, rendered ${report.renderedReadyTeachingCardCount}`,
      )
    }
    const renderedReadyBggIds = await renderedReadyTeaching.evaluateAll(links => links.map(link =>
      Number(link.closest('[data-testid="recommendation-game-card"]')?.getAttribute('data-bgg-id'))))
    const persistedReadyBggIds = readyGames.map(entry => entry.game.bggId)
    if (renderedReadyBggIds.some(id => !Number.isSafeInteger(id) || id < 1)
      || new Set(renderedReadyBggIds).size !== renderedReadyBggIds.length
      || [...renderedReadyBggIds].sort((left, right) => left - right)
        .some((id, index) => id !== [...persistedReadyBggIds].sort((left, right) => left - right)[index])) {
      report.readyTeachingFailureCategory = 'READY_TEACHING_LINK_NOT_RENDERED'
      throw new Error('READY_TEACHING_LINK_NOT_RENDERED: rendered and persisted ready BGG identities diverged')
    }

    report.stage = 'ready-public-guides'
    report.readyTeachingFailureCategory = 'PUBLIC_GUIDE_NOT_READABLE'
    for (const [index, candidate] of readyGames.entries()) {
      const candidateBggId = candidate.game.bggId
      const candidatePlanId = candidate.teachingContinuation!.teachingPlanId
      const candidateGuidePath = `/read/${encodeURIComponent(candidatePlanId)}`
      const candidateCard = page.locator(
        `[data-testid="recommendation-game-card"][data-bgg-id="${candidateBggId}"]`,
      )
      const candidateLink = candidateCard.getByTestId('open-ready-teaching')
      await expect(candidateLink,
        `Ready card ${index + 1} did not render exactly one typed public-guide link`).toHaveCount(1)
      const candidateHref = await candidateLink.getAttribute('href')
      if (candidateHref !== candidateGuidePath) {
        report.readyTeachingFailureCategory = 'READY_TEACHING_LINK_TARGET_MISMATCH'
        throw new Error(
          `READY_TEACHING_LINK_TARGET_MISMATCH: ready card ${index + 1} did not target its typed plan`,
        )
      }

      const verificationPage = await page.context().newPage()
      verificationPage.on('pageerror', error => pageErrors.push(error))
      try {
        const navigation = await verificationPage.goto(candidateGuidePath, {
          waitUntil: 'domcontentloaded',
          timeout: 60_000,
        })
        expect(navigation?.ok(),
          `Ready card ${index + 1} public guide returned HTTP ${navigation?.status() ?? 'no response'}`)
          .toBe(true)
        const candidateGuide = verificationPage.getByTestId('public-lesson-reader')
        await expect(candidateGuide).toBeVisible({ timeout: 60_000 })
        const candidateIdentity = candidateGuide.locator('a[href^="/discover/"]')
        await expect(candidateIdentity,
          `Ready card ${index + 1} did not expose exactly one typed BGG identity`).toHaveCount(1)
        await expect(candidateIdentity,
          `Ready card ${index + 1} public guide belongs to another BGG game`)
          .toHaveAttribute('href', `/discover/${candidateBggId}`)
        const candidateSections = candidateGuide.locator('section[id^="public-chapter-"]')
        await expect(candidateSections,
          `Ready card ${index + 1} did not render its typed chapter count`)
          .toHaveCount(candidate.teachingContinuation!.sectionCount)
        expect(await candidateGuide.locator(
          `a[href^="/api/public/lessons/${encodeURIComponent(candidatePlanId)}/pages/"]`,
        ).count(), `Ready card ${index + 1} had no readable cited chapter`).toBeGreaterThan(0)
      } finally {
        await verificationPage.close()
      }
    }
    report.readyTeachingFailureCategory = null

    const readyGame = readyGames[0]!
    const readyBggId = readyGame.game.bggId
    const readyPlanId = readyGame.teachingContinuation!.teachingPlanId
    if (!Number.isSafeInteger(readyBggId) || readyBggId < 1
      || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(readyPlanId)) {
      report.readyTeachingFailureCategory = 'READY_TEACHING_NOT_ATTACHED'
      throw new Error('READY_TEACHING_NOT_ATTACHED: the ready card identity is invalid')
    }
    const readyRank = selectionResult.games.findIndex(entry => entry.game.bggId === readyBggId) + 1
    const readyCard = page.locator(
      `[data-testid="recommendation-game-card"][data-bgg-id="${readyBggId}"]`,
    )
    const readyLink = readyCard.getByTestId('open-ready-teaching')
    const expectedGuidePath = `/read/${encodeURIComponent(readyPlanId)}`
    const actualGuidePath = await readyLink.getAttribute('href')
    if (actualGuidePath !== expectedGuidePath) {
      report.readyTeachingFailureCategory = 'READY_TEACHING_LINK_TARGET_MISMATCH'
      throw new Error('READY_TEACHING_LINK_TARGET_MISMATCH: the ready card did not target its typed plan')
    }

    report.selectedRecommendationRank = readyRank
    report.selectedBggId = readyBggId
    report.selectedGameName = readyGame.game.name
    report.teachingPlanId = readyPlanId
    report.stage = 'ready-public-guide'
    report.readyTeachingFailureCategory = 'PUBLIC_GUIDE_NOT_READABLE'
    await readyLink.click()
    await expect.poll(() => new URL(page.url()).pathname, {
      timeout: 60_000,
      message: 'The ready recommendation did not open its public guide',
    }).toBe(expectedGuidePath)
    const publicGuide = page.getByTestId('public-lesson-reader')
    await expect(publicGuide).toBeVisible({ timeout: 60_000 })
    const publicGuideGameIdentity = publicGuide.locator('a[href^="/discover/"]')
    await expect(publicGuideGameIdentity,
      'The public guide did not expose exactly one typed BGG game identity').toHaveCount(1)
    await expect(publicGuideGameIdentity,
      'The ready teaching plan belongs to a different BGG game than the recommendation card')
      .toHaveAttribute('href', `/discover/${readyBggId}`)
    report.readyTeachingGuideOpened = true
    report.lessonStatus = 'PUBLIC_READY'
    report.lessonSectionCount = await publicGuide.locator('section[id^="public-chapter-"]').count()
    expect(report.lessonSectionCount,
      'The ready public guide did not render a chapter').toBeGreaterThan(0)
    expect(report.lessonSectionCount,
      'The ready-card chapter count diverged from the public guide').toBe(readyGame.teachingContinuation!.sectionCount)
    const citedGuideLinks = publicGuide.locator(
      `a[href^="/api/public/lessons/${encodeURIComponent(readyPlanId)}/pages/"]`,
    )
    expect(await citedGuideLinks.count(),
      'The ready public guide had no player-visible rulebook page citation').toBeGreaterThan(0)
    report.citedLessonStep = true
    report.readyTeachingFailureCategory = null

    report.stage = 'ready-public-questions'
    report.readyTeachingFailureCategory = 'PUBLIC_QUESTION_ROUTE_UNAVAILABLE'
    const questionsEntry = page.getByTestId('lesson-questions-entry')
    const expectedQuestionsPath = `${expectedGuidePath}/questions`
    await expect(questionsEntry).toHaveAttribute('href', expectedQuestionsPath)
    await questionsEntry.click()
    await expect.poll(() => new URL(page.url()).pathname, {
      timeout: 60_000,
      message: 'The public guide did not open its question route',
    }).toBe(expectedQuestionsPath)
    const publicQuestions = page.getByTestId('public-questions-reader')
    await expect(publicQuestions).toBeVisible({ timeout: 60_000 })
    report.readyTeachingQuestionsOpened = true
    report.readyTeachingFailureCategory = null

    const publicAnswerPath = `/api/public/lessons/${encodeURIComponent(readyPlanId)}/answers`
    const publicAnswerArticles = page.locator('article[id^="public-answer-"]')
    await expect(publicAnswerArticles,
      'The production probe opened with a stale public answer thread').toHaveCount(0)

    report.stage = 'ready-public-answer'
    report.readyTeachingFailureCategory = 'PUBLIC_ANSWER_REQUEST_FAILED'
    const publicAnswerStartedAt = performance.now()
    const publicAnswerResponsePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      if (response.request().method() !== 'POST' || url.pathname !== publicAnswerPath) return false
      const body = response.request().postDataJSON() as { question?: unknown } | null
      return body?.question === RULE_QUESTION
    }, { timeout: 4 * 60_000 })
    const publicQuestionInput = page.locator('#public-question')
    await publicQuestionInput.fill(RULE_QUESTION)
    await publicQuestionInput.locator('xpath=ancestor::form')
      .locator('button[type="submit"]')
      .click()
    const publicAnswerResponse = await publicAnswerResponsePromise
    if (!publicAnswerResponse.ok()) {
      throw new Error(`PUBLIC_ANSWER_REQUEST_FAILED: HTTP ${publicAnswerResponse.status()}`)
    }
    const publicAnswerRequestBody = publicAnswerResponse.request().postDataJSON() as {
      question?: unknown
      previousQuestion?: unknown
      language?: unknown
      learningIntent?: unknown
    }
    expect(publicAnswerRequestBody,
      'The first public answer request did not start a fresh typed thread').toEqual({
      question: RULE_QUESTION,
      previousQuestion: null,
      language: 'zh-CN',
      learningIntent: null,
    })
    report.publicAnswerRequestSucceeded = true
    const publicAnswerResult = await publicAnswerResponse.json() as AnswerResponse
    report.answerStatus = publicAnswerResult.answer.status
    report.answerCitationCount = publicAnswerResult.answer.citations.length
    report.answerMs = elapsed(publicAnswerStartedAt)
    if (!['ANSWERED', 'ANSWERED_WITH_WARNING'].includes(report.answerStatus)) {
      report.readyTeachingFailureCategory = 'PUBLIC_ANSWER_NON_PUBLISHING_STATUS'
      throw new Error(`PUBLIC_ANSWER_NON_PUBLISHING_STATUS: ${report.answerStatus}`)
    }
    if (report.answerCitationCount < 1) {
      report.readyTeachingFailureCategory = 'PUBLIC_ANSWER_MISSING_CITATION'
      throw new Error('PUBLIC_ANSWER_MISSING_CITATION: the published answer had no citation')
    }
    if (!validPublicAnswerCitations(publicAnswerResult.answer.citations)) {
      report.readyTeachingFailureCategory = 'PUBLIC_ANSWER_INVALID_CITATION_RANGE'
      throw new Error('PUBLIC_ANSWER_INVALID_CITATION_RANGE: the published answer citation was invalid')
    }
    report.readyTeachingFailureCategory = 'PUBLIC_ANSWER_EVIDENCE_NOT_RENDERED'
    await expect(publicAnswerArticles).toHaveCount(1)
    await expectRenderedPublicAnswerEvidence(
      page.request,
      publicAnswerArticles.first(),
      RULE_QUESTION,
      readyPlanId,
      publicAnswerResult.answer.citations,
    )
    report.citedAnswer = true
    report.answerSessionTurnCount = 1
    report.readyTeachingFailureCategory = null

    report.stage = 'ready-public-follow-up'
    report.readyTeachingFailureCategory = 'PUBLIC_ANSWER_REQUEST_FAILED'
    const followUpStartedAt = performance.now()
    const followUpResponsePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      if (response.request().method() !== 'POST' || url.pathname !== publicAnswerPath) return false
      const body = response.request().postDataJSON() as { question?: unknown } | null
      return body?.question === RULE_FOLLOW_UP
    }, { timeout: 4 * 60_000 })
    await expect.poll(() => new URL(page.url()).pathname, {
      message: 'The public Q&A left the typed teaching plan before the follow-up',
    }).toBe(expectedQuestionsPath)
    await publicQuestionInput.fill(RULE_FOLLOW_UP)
    await publicQuestionInput.locator('xpath=ancestor::form')
      .locator('button[type="submit"]')
      .click()
    const followUpResponse = await followUpResponsePromise
    if (!followUpResponse.ok()) {
      throw new Error(`PUBLIC_ANSWER_REQUEST_FAILED: follow-up HTTP ${followUpResponse.status()}`)
    }
    const followUpRequestBody = followUpResponse.request().postDataJSON() as {
      question?: unknown
      previousQuestion?: unknown
      language?: unknown
      learningIntent?: unknown
    }
    expect(followUpRequestBody,
      'The follow-up did not continue the first public question as a typed thread').toEqual({
      question: RULE_FOLLOW_UP,
      previousQuestion: RULE_QUESTION,
      language: 'zh-CN',
      learningIntent: null,
    })
    expect(new URL(followUpResponse.url()).pathname,
      'The follow-up answer switched to a different teaching plan').toBe(publicAnswerPath)

    const followUpResult = await followUpResponse.json() as AnswerResponse
    report.followUpAnswerStatus = followUpResult.answer.status
    report.followUpCitationCount = followUpResult.answer.citations.length
    report.followUpAnswerMs = elapsed(followUpStartedAt)
    if (!['ANSWERED', 'ANSWERED_WITH_WARNING'].includes(report.followUpAnswerStatus)) {
      report.readyTeachingFailureCategory = 'PUBLIC_ANSWER_NON_PUBLISHING_STATUS'
      throw new Error(`PUBLIC_ANSWER_NON_PUBLISHING_STATUS: follow-up ${report.followUpAnswerStatus}`)
    }
    if (report.followUpCitationCount < 1) {
      report.readyTeachingFailureCategory = 'PUBLIC_ANSWER_MISSING_CITATION'
      throw new Error('PUBLIC_ANSWER_MISSING_CITATION: the published follow-up had no citation')
    }
    if (!validPublicAnswerCitations(followUpResult.answer.citations)) {
      report.readyTeachingFailureCategory = 'PUBLIC_ANSWER_INVALID_CITATION_RANGE'
      throw new Error('PUBLIC_ANSWER_INVALID_CITATION_RANGE: the follow-up citation was invalid')
    }
    report.readyTeachingFailureCategory = 'PUBLIC_ANSWER_EVIDENCE_NOT_RENDERED'
    await expect(publicAnswerArticles).toHaveCount(2)
    await expectRenderedPublicAnswerEvidence(
      page.request,
      publicAnswerArticles.nth(1),
      RULE_FOLLOW_UP,
      readyPlanId,
      followUpResult.answer.citations,
    )
    await expect.poll(() => new URL(page.url()).pathname, {
      message: 'The public follow-up did not stay in the same visible answer thread',
    }).toBe(expectedQuestionsPath)
    report.answerSessionTurnCount = 2
    report.answerSessionPreserved = true
    report.readyTeachingFailureCategory = null

    if (importRequestCount !== 0) {
      report.readyTeachingFailureCategory = 'READY_TEACHING_STARTED_IMPORT'
      throw new Error(`READY_TEACHING_STARTED_IMPORT: observed ${importRequestCount} import requests`)
    }
    expect(pageErrors, 'The ready public teaching journey emitted uncaught browser errors').toEqual([])
    report.completed = true
    report.stage = 'completed-ready-public-teaching'
    await retainReport(reportFile, report)
    return
    }

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
      let candidateResult: CandidateResponse | null = null
      let recovery = chooseRecommendationRecovery(attemptedBoundGame.edition.id, existingJourneys, [])
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
            existingJourneys,
            candidateResult.configured ? candidateResult.candidates : [],
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
      throw new Error(
        `None of the three Agent-ranked recommendations had a usable journey or verified official rulebook source: ${JSON.stringify(report.recommendationRecoveryOutcomes)}`,
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
    report.lessonReadable = completedLesson.readable
    report.lessonFullySupported = completedLesson.fullySupported
    report.lessonCompletionCategory = completedLesson.category
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
    await expectRenderedRecommendationAnswerEvidence(
      page.request,
      answerWorkspace,
      RULE_QUESTION,
      completedJob.documentVersionId!,
      persistedAnswer!.answer,
    )
    const visibleAnswerTurnCountBeforeFollowUp = await renderedRecommendationAnswerTurnCount(answerWorkspace)
    expect(visibleAnswerTurnCountBeforeFollowUp,
      'The first persisted answer did not have exactly one player-visible turn').toBe(1)
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
    expect(persistedFollowUp, 'The persisted follow-up turn disappeared before UI verification').toBeDefined()
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
    await expect.poll(() => renderedRecommendationAnswerTurnCount(answerWorkspace), {
      timeout: 60_000,
      message: 'The second persisted answer did not add one player-visible answer turn',
    }).toBe(visibleAnswerTurnCountBeforeFollowUp + 1)
    await expectRenderedRecommendationAnswerEvidence(
      page.request,
      answerWorkspace,
      RULE_FOLLOW_UP,
      completedJob.documentVersionId!,
      persistedFollowUp!.answer,
    )

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
    await expectRenderedRecommendationAnswerEvidence(
      page.request,
      answerWorkspace,
      RULE_FOLLOW_UP,
      completedJob.documentVersionId!,
      persistedFollowUp!.answer,
    )
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
    await expect.poll(() => renderedRecommendationAnswerTurnCount(restoredAnswerWorkspace), {
      timeout: 60_000,
      message: 'Refresh did not restore both grounded answer turns',
    }).toBe(visibleAnswerTurnCountBeforeFollowUp + 1)
    await expectRenderedRecommendationAnswerEvidence(
      page.request,
      restoredAnswerWorkspace,
      RULE_FOLLOW_UP,
      completedJob.documentVersionId!,
      persistedFollowUp!.answer,
    )
    report.answerRestored = true
    await restoredRoleSwitcher.getByRole('button', { name: '继续推荐' }).click()

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
          const supportedSectionCount = latestLesson.sections
            .filter(section => section.evidenceStatus === 'SUPPORTED').length
          report.lessonReadable = supportedSectionCount + report.citedDraftSectionCount > 0
          report.lessonFullySupported = report.teachingGenerationState === 'COMPLETED'
            && latestLesson.status === 'COMPLETE'
            && supportedSectionCount === latestLesson.sections.length
            && latestLesson.sections.length > 0
          report.lessonCompletionCategory = report.lessonReadable
            ? report.lessonFullySupported ? 'FULLY_SUPPORTED' : 'READABLE_WITH_DEGRADATION'
            : null
        }
      } catch {
        // Preserve the primary failure while retaining any earlier teaching diagnostics.
      }
    }
    await guidesPage?.close().catch(() => undefined)
    report.importRequestCount = importRequestCount
    report.pageErrorCount = pageErrors.length
    report.generatedAt = new Date().toISOString()
    await retainReport(reportFile, report)
  }
})
