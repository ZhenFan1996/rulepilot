import { createHash } from 'node:crypto'
import { writeFile } from 'node:fs/promises'

import {
  expect,
  test,
  type APIRequestContext,
  type Locator,
  type Page,
  type Request,
  type Response,
} from '@playwright/test'
import MarkdownIt from 'markdown-it'

const enabled = process.env.RULEPILOT_PRODUCTION_RECOMMENDATION_JOURNEY === 'true'
const SELECTION_PROMPT = process.env.RULEPILOT_RECOMMENDATION_SELECTION_PROMPT ?? ''
const EXPECTED_TITLE_TERM = (process.env.RULEPILOT_RECOMMENDATION_EXPECTED_TITLE_TERM ?? '')
  .normalize('NFKC')
  .trim()
  .toLocaleLowerCase('en-US')
const EXPECTED_MODEL_PROVIDER = (process.env.RULEPILOT_RECOMMENDATION_EXPECTED_PROVIDER ?? '').trim()
const EXPECTED_MODEL_NAME = (process.env.RULEPILOT_RECOMMENDATION_EXPECTED_MODEL ?? '').trim()
const TESTED_SHA = process.env.RULEPILOT_RECOMMENDATION_TESTED_SHA ?? ''
const ACTIVE_RELEASE_ID = process.env.RULEPILOT_RECOMMENDATION_ACTIVE_RELEASE_ID ?? ''
const INTERACTION_SLO_MS = 20_000
const FIRST_PROGRESS_SLO_MS = 3_000
const TERMINAL_OBSERVATION_MS = 50_000
const MAX_RECOMMENDATION_MODEL_CALLS = 6
const PUBLIC_FAILURE_BOUNDARIES = new Set([
  'time_budget',
  'model_response',
  'service_configuration',
  'action_budget',
  'publication_boundary',
  'service_failure',
])
const PLAYER_MARKDOWN = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true,
  typographer: false,
})
PLAYER_MARKDOWN.renderer.rules.image = (tokens, index) =>
  PLAYER_MARKDOWN.utils.escapeHtml(tokens[index]?.content ?? '')

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
    overallRank: number | null
    bggTypes: string[]
    minPlayers: number | null
    maxPlayers: number | null
    playingTimeMinutes: number | null
    minimumPlayTimeMinutes?: number | null
    maximumPlayTimeMinutes?: number | null
    averageWeight: number | null
  }
  fitClaims?: Array<{
    subject: string
    strength: 'hard' | 'soft'
    relation: 'satisfied' | 'conflict' | 'unknown'
    text: string
  }>
  replyParts?: Array<{
    role: 'why_fit' | 'verified_fact' | 'tradeoff'
    claimType: 'constraint_fit' | 'structured_fact' | 'taxonomy_classification' | 'attributed_experience' | 'rule_procedure' | 'publisher_description' | 'preference_inference'
    subject: string
    text: string
    sourceIndexes: number[]
  }>
}

interface RecommendationConstraintRange {
  minimum: number | null
  maximum: number | null
  strength: 'hard' | 'soft'
  sourceText: string
  confirmedTurn: number
}

interface RecommendationProfile {
  type: string
  interaction: string
  playerCount: RecommendationConstraintRange | null
  durationMinutes: RecommendationConstraintRange | null
  complexity: RecommendationConstraintRange | null
}

interface RecommendationPublishedContent {
  assistantMessage: string
  games: Array<{
    bggId: number
    name: string
    originalName: string
    minPlayers: number | null
    maxPlayers: number | null
    minimumPlayTimeMinutes: number | null
    maximumPlayTimeMinutes: number | null
    replyParts: Array<{
      role: string
      claimType: string
      subject: string
      text: string
      sourceIndexes: number[]
    }>
  }>
}

interface RecommendationResult {
  clientTurnId: string
  outcome: RecommendationOutcome
  assistantMessage: string
  profile: RecommendationProfile
  games: RecommendationGame[]
  completedWork?: string[]
  modelCalls?: number
  modelCallElapsedMs?: number[]
  agentElapsedMs?: number
  catalogCalls?: number
  webResearchCalls?: number
  failureBoundary?: string | null
}

interface EffectiveModelAssignment {
  provider: string
  model: string
}

interface ModelConfigurationSnapshot {
  recommendationModel: EffectiveModelAssignment
}

interface PublicReleaseIdentity {
  releaseId: string
  commitSha: string
}

interface RecommendationSession {
  conversationId: string
  revision: number
  profile: RecommendationProfile
  processing: boolean
  latestResponse: RecommendationResult | null
}

type TerminalCategory =
  | 'RECOMMENDATIONS'
  | 'NON_RECOMMENDATION'
  | 'SESSION_TIMEOUT'
  | 'SESSION_READ_FAILURE'
  | 'STREAM_ERROR'

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

type RulebookHandoffTerminalCategory =
  | 'NOT_OBSERVED'
  | 'RESTORED_EXISTING'
  | 'REVIEW'
  | 'NO_IMPORTABLE_SOURCE'
  | 'UNAVAILABLE'
  | 'ERROR'
  | 'LOGIN_REQUIRED'

interface ImportedGameIdentity {
  bggId: number
  game: {
    id: string
  }
  edition: {
    id: string
    gameId: string
  }
}

interface RulebookDiscoveryIdentityResponse {
  configured: boolean
  identity: {
    editionId: string
  }
  candidates: RulebookDiscoveryCandidateIdentity[]
}

interface RulebookDiscoveryCandidateIdentity {
  url: string
  capability: string
  acquisitionMode: string
}

interface RestorableOfficialImportIdentity {
  id: string
  editionId: string
  stage: string
  documentVersionId: string | null
  teachingHandoffState: string
  teachingPreparationRunId: string | null
  freshnessEligible: boolean
}

interface RecommendationContentDigest {
  assistantMessageSha256: string
  assistantMessageCharacterCount: number
  cardReplyPartsSha256: string
  cardReplyPartsCharacterCount: number
  cardReplyPartCount: number
}

interface RecommendationProgressEvidence {
  stage: string
  phase: string
  action: string | null
  serverElapsedMs: number
  browserReceivedMs: number
  observedCandidates: number | null
  verifiedCandidates: number | null
  hardRejectedCandidates: number | null
  sourceCount: number | null
}

interface BrowserProgressObservation extends Omit<RecommendationProgressEvidence, 'browserReceivedMs'> {
  browserReceivedAtEpochMs: number
}

interface BrowserSseResultObservation {
  kind: 'result'
  browserReceivedAtEpochMs: number
  clientTurnId: string | null
  outcome: string | null
  failureBoundary: string | null
  bggIds: number[]
  content: RecommendationPublishedContent
}

interface BrowserSseErrorObservation {
  kind: 'error'
  browserReceivedAtEpochMs: number
  code: string
  failureBoundary: string | null
}

type BrowserSseTerminalObservation = BrowserSseResultObservation | BrowserSseErrorObservation

interface ProductionRecommendationReport {
  generatedAt: string
  completed: boolean
  stage: string
  testedSha: string
  activeReleaseSha: string
  activeReleaseId: string
  publicReleaseId: string | null
  publicReleaseSha: string | null
  publicReleaseNoStore: boolean | null
  routeStayedOnDiscover: boolean
  recommendationRequestedCardCount: number
  recommendationExpectedPlayerCount: number
  recommendationMaximumDurationMinutes: number
  recommendationMaximumComplexity: number
  recommendationExpectedGameType: string
  recommendationRequestMessageMatched: boolean
  recommendationProfileHardConstraintsMatched: boolean | null
  recommendationCardsHardConstraintsMatched: boolean | null
  recommendationFitClaimsHardConstraintsMatched: boolean | null
  recommendationComplexityHardConstraintsMatched: boolean | null
  recommendationGameTypeHardConstraintsMatched: boolean | null
  recommendationEvidenceBoundReplyParts: boolean | null
  recommendationPersistedCardCount: number | null
  recommendationShortfallCount: number | null
  recommendationOutcome: RecommendationOutcome | null
  recommendationTerminalCategory: TerminalCategory | 'NOT_OBSERVED'
  recommendationTerminalObserved: boolean
  recommendationClickCaptured: boolean
  recommendationFirstProgressMs: number | null
  recommendationSseTerminalCategory: 'NOT_OBSERVED' | 'RESULT' | 'ERROR'
  recommendationSseTerminalMs: number | null
  recommendationSseResultMs: number | null
  recommendationSseErrorCode: string | null
  recommendationSseFailureBoundary: string | null
  recommendationPersistedTerminalMs: number | null
  recommendationRenderedSlateMs: number | null
  recommendationElapsedMs: number | null
  recommendationSloMet: boolean | null
  recommendationProgressEvents: RecommendationProgressEvidence[]
  recommendationStreamProbeFailed: boolean
  recommendationPublishedBggIds: number[]
  recommendationAssistantReplyCharacterCount: number | null
  recommendationRenderedReplyCharacterCount: number | null
  recommendationCardReplyPartCount: number | null
  recommendationUsableCardCount: number
  recommendationUsableReplyPartCount: number
  recommendationAssistantReplyUsable: boolean
  recommendationAllCardsUsable: boolean
  recommendationAllReplyPartsUsable: boolean
  recommendationSseContentDigest: RecommendationContentDigest | null
  recommendationPersistedContentDigest: RecommendationContentDigest | null
  recommendationRenderedContentDigest: RecommendationContentDigest | null
  recommendationSsePersistedContentConsistent: boolean | null
  recommendationPersistedDomContentConsistent: boolean | null
  recommendationCompletedWork: string[]
  recommendationExpectedModel: EffectiveModelAssignment
  recommendationModelBeforeRequest: EffectiveModelAssignment | null
  recommendationModelAfterRequest: EffectiveModelAssignment | null
  recommendationModelProvider: string | null
  recommendationModelName: string | null
  recommendationModelCalls: number | null
  recommendationModelCallElapsedMs: number[]
  recommendationAgentElapsedMs: number | null
  recommendationModelElapsedShare: number | null
  recommendationCatalogCalls: number | null
  recommendationWebResearchCalls: number | null
  recommendationFailureBoundary: string | null
  expectedRecommendationTitleTermSha256: string
  handoffSelectedBggId: number | null
  handoffActionClicked: boolean
  handoffSurfaceVisible: boolean
  handoffImportRequestedBggId: number | null
  handoffImportResponseStatus: number | null
  handoffImportResponseOk: boolean | null
  handoffImportResponseBggId: number | null
  handoffImportedGameId: string | null
  handoffImportedEditionId: string | null
  handoffEditionBelongsToImportedGame: boolean | null
  handoffImportElapsedMs: number | null
  handoffDiscoveryRequestedEditionId: string | null
  handoffDiscoveryResponseStatus: number | null
  handoffDiscoveryResponseOk: boolean | null
  handoffDiscoveryIdentityEditionId: string | null
  handoffDiscoveryIdentityMatched: boolean | null
  handoffDiscoveryConfigured: boolean | null
  handoffDiscoveryCandidateCount: number | null
  handoffImportableCandidateCount: number | null
  handoffImportableCandidateFound: boolean | null
  handoffDiscoveryCandidateIdentitySha256: string | null
  handoffRenderedCandidateIdentitySha256: string | null
  handoffCandidateIdentityOrderConsistent: boolean | null
  handoffDiscoveryElapsedMs: number | null
  handoffTerminalCategory: RulebookHandoffTerminalCategory
  handoffTerminalVisible: boolean
  handoffElapsedMs: number | null
  handoffRulebookImportStarted: boolean
  handoffRestoredExistingJourney: boolean
  handoffRestoredImportJobId: string | null
  handoffRestoredDocumentVersionId: string | null
  handoffRestoredPreparationRunId: string | null
  handoffFreshnessRequestPreparationRunMatched: boolean | null
  handoffFreshnessResponseStatus: number | null
  handoffFreshnessResponseIdentityMatched: boolean | null
  handoffFreshnessResponseEligible: boolean | null
  handoffOfficialMutationAttemptedPaths: string[]
  handoffOfficialMutationBlocked: boolean
  handoffStoppedAtDiscoveryBoundary: boolean
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

function parseExpectedPositiveInteger(value: string | undefined, label: string, maximum: number) {
  if (!value || !/^\d+$/.test(value)) throw new Error(`Invalid ${label}: ${value ?? '(missing)'}`)
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed) || parsed < 1 || parsed > maximum) {
    throw new Error(`${label} is outside the supported range: ${value}`)
  }
  return parsed
}

function parseExpectedDecimal(value: string | undefined, label: string, maximum: number) {
  if (!value || !/^(?:0|[1-9]\d*)(?:\.\d+)?$/.test(value)) {
    throw new Error(`Invalid ${label}: ${value ?? '(missing)'}`)
  }
  const parsed = Number(value)
  if (!Number.isFinite(parsed) || parsed <= 0 || parsed > maximum) {
    throw new Error(`${label} is outside the supported range: ${value}`)
  }
  return parsed
}

function parseExpectedGameType(value: string | undefined) {
  const normalized = (value ?? '').trim().toLocaleLowerCase('en-US')
  const allowed = new Set([
    'abstract', 'customizable', 'children', 'family', 'party',
    'strategy', 'thematic', 'war', 'expansion',
  ])
  if (!allowed.has(normalized)) throw new Error(`Invalid expected BGG game type: ${value ?? '(missing)'}`)
  return normalized
}

function nodeElapsed(startedAtEpochMs: number) {
  return Math.max(0, Math.round(Date.now() - startedAtEpochMs))
}

function sleep(milliseconds: number, signal?: AbortSignal) {
  return new Promise<void>(resolve => {
    if (signal?.aborted) {
      resolve()
      return
    }
    const timeout = setTimeout(done, milliseconds)
    signal?.addEventListener('abort', done, { once: true })

    function done() {
      clearTimeout(timeout)
      signal?.removeEventListener('abort', done)
      resolve()
    }
  })
}

function publicNonNegativeInteger(value: unknown): number | null {
  return Number.isSafeInteger(value) && Number(value) >= 0 ? Number(value) : null
}

function publicNonNegativeIntegers(value: unknown): number[] {
  if (!Array.isArray(value) || !value.every(entry => Number.isSafeInteger(entry) && Number(entry) >= 0)) return []
  return value.map(Number)
}

function publicUuid(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const normalized = value.trim().toLocaleLowerCase('en-US')
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(normalized)
    ? normalized
    : null
}

function importedGameIdentity(value: unknown): ImportedGameIdentity | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null
  const imported = value as Record<string, unknown>
  if (imported.game === null || typeof imported.game !== 'object' || Array.isArray(imported.game)
    || imported.edition === null || typeof imported.edition !== 'object' || Array.isArray(imported.edition)) {
    return null
  }
  const gameId = publicUuid((imported.game as Record<string, unknown>).id)
  const editionId = publicUuid((imported.edition as Record<string, unknown>).id)
  const editionGameId = publicUuid((imported.edition as Record<string, unknown>).gameId)
  const bggId = Number.isSafeInteger(imported.bggId) && Number(imported.bggId) > 0
    ? Number(imported.bggId)
    : null
  return gameId && editionId && editionGameId && bggId
    ? { bggId, game: { id: gameId }, edition: { id: editionId, gameId: editionGameId } }
    : null
}

function restorableOfficialImport(
  value: unknown,
  expectedEditionId: string,
): RestorableOfficialImportIdentity | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null
  const job = value as Record<string, unknown>
  const id = publicUuid(job.id)
  const editionId = publicUuid(job.editionId)
  const documentVersionId = job.documentVersionId === null
    ? null
    : publicUuid(job.documentVersionId)
  const preparationRunId = job.teachingPreparationRunId === null
    ? null
    : publicUuid(job.teachingPreparationRunId)
  if (id === null
    || editionId !== expectedEditionId
    || typeof job.stage !== 'string'
    || typeof job.teachingHandoffState !== 'string'
    || (job.documentVersionId !== null && documentVersionId === null)
    || (job.teachingPreparationRunId !== null && preparationRunId === null)
    || job.teachingHandoffState === 'NOT_REQUESTED') return null
  return {
    id,
    editionId,
    stage: job.stage,
    documentVersionId,
    teachingHandoffState: job.teachingHandoffState,
    teachingPreparationRunId: preparationRunId,
    freshnessEligible: job.stage === 'COMPLETED'
      && documentVersionId !== null
      && job.teachingHandoffState === 'LAUNCHED'
      && preparationRunId !== null,
  }
}

function isExactPreparationRunRequest(value: unknown, expectedPreparationRunId: string) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false
  const request = value as Record<string, unknown>
  return Object.keys(request).length === 1
    && Object.hasOwn(request, 'expectedPreparationRunId')
    && publicUuid(request.expectedPreparationRunId) === expectedPreparationRunId
}

function rulebookDiscoveryIdentity(value: unknown): RulebookDiscoveryIdentityResponse | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null
  const discovery = value as Record<string, unknown>
  if (typeof discovery.configured !== 'boolean'
    || !Array.isArray(discovery.candidates)
    || discovery.identity === null
    || typeof discovery.identity !== 'object'
    || Array.isArray(discovery.identity)) return null
  const editionId = publicUuid((discovery.identity as Record<string, unknown>).editionId)
  const candidates = discovery.candidates.map(rulebookDiscoveryCandidateIdentity)
  if (candidates.some(candidate => candidate === null)) return null
  return editionId === null ? null : {
    configured: discovery.configured,
    identity: { editionId },
    candidates: candidates as RulebookDiscoveryCandidateIdentity[],
  }
}

function rulebookDiscoveryCandidateIdentity(value: unknown): RulebookDiscoveryCandidateIdentity | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null
  const candidate = value as Record<string, unknown>
  if (typeof candidate.url !== 'string'
    || typeof candidate.capability !== 'string'
    || typeof candidate.acquisitionMode !== 'string') return null
  try {
    const url = new URL(candidate.url)
    if (url.protocol !== 'https:' && url.protocol !== 'http:') return null
    return {
      url: url.href,
      capability: candidate.capability,
      acquisitionMode: candidate.acquisitionMode,
    }
  } catch {
    return null
  }
}

function isImportableRulebookCandidate(candidate: RulebookDiscoveryCandidateIdentity) {
  return candidate.capability === 'DIRECT_DOCUMENT' && candidate.acquisitionMode === 'DIRECT_PDF'
    || candidate.capability === 'CONTIGUOUS_RULE_PAGES'
      && candidate.acquisitionMode === 'IMAGE_GALLERY'
}

function displayedRulebookCandidateOrder(candidates: RulebookDiscoveryCandidateIdentity[]) {
  return [
    ...candidates.filter(candidate => candidate.capability !== 'GAME_INFO_ONLY'),
    ...candidates.filter(candidate => candidate.capability === 'GAME_INFO_ONLY'),
  ]
}

function publicFailureBoundary(value: unknown): string | null {
  return typeof value === 'string' && PUBLIC_FAILURE_BOUNDARIES.has(value) ? value : null
}

function normalizedPlayerText(value: string) {
  return value.replace(/\s+/gu, ' ').trim()
}

function publishedContent(result: RecommendationResult): RecommendationPublishedContent {
  return {
    assistantMessage: result.assistantMessage,
    games: result.games.map(entry => ({
      bggId: entry.game.bggId,
      name: entry.game.name,
      originalName: entry.game.originalName,
      minPlayers: entry.game.minPlayers,
      maxPlayers: entry.game.maxPlayers,
      minimumPlayTimeMinutes:
        entry.game.minimumPlayTimeMinutes ?? entry.game.playingTimeMinutes,
      maximumPlayTimeMinutes:
        entry.game.maximumPlayTimeMinutes ?? entry.game.playingTimeMinutes,
      replyParts: (entry.replyParts ?? []).map(part => ({
        role: part.role,
        claimType: part.claimType,
        subject: part.subject,
        text: part.text,
        sourceIndexes: [...part.sourceIndexes],
      })),
    })),
  }
}

function samePublishedContent(
  first: RecommendationPublishedContent,
  second: RecommendationPublishedContent,
) {
  return first.assistantMessage === second.assistantMessage
    && first.games.length === second.games.length
    && first.games.every((game, gameIndex) => {
      const candidate = second.games[gameIndex]
      return candidate !== undefined
        && game.bggId === candidate.bggId
        && game.name === candidate.name
        && game.originalName === candidate.originalName
        && game.minPlayers === candidate.minPlayers
        && game.maxPlayers === candidate.maxPlayers
        && game.minimumPlayTimeMinutes === candidate.minimumPlayTimeMinutes
        && game.maximumPlayTimeMinutes === candidate.maximumPlayTimeMinutes
        && game.replyParts.length === candidate.replyParts.length
        && game.replyParts.every((part, partIndex) => {
          const candidatePart = candidate.replyParts[partIndex]
          return candidatePart !== undefined
            && part.role === candidatePart.role
            && part.claimType === candidatePart.claimType
            && part.subject === candidatePart.subject
            && part.text === candidatePart.text
            && part.sourceIndexes.length === candidatePart.sourceIndexes.length
            && part.sourceIndexes.every((sourceIndex, index) =>
              sourceIndex === candidatePart.sourceIndexes[index])
        })
    })
}

function samePlayerVisibleContent(
  first: RecommendationPublishedContent,
  second: RecommendationPublishedContent,
) {
  return normalizedPlayerText(first.assistantMessage) === normalizedPlayerText(second.assistantMessage)
    && first.games.length === second.games.length
    && first.games.every((game, gameIndex) => {
      const candidate = second.games[gameIndex]
      return candidate !== undefined
        && game.bggId === candidate.bggId
        && normalizedPlayerText(game.name) === normalizedPlayerText(candidate.name)
        && normalizedPlayerText(game.originalName) === normalizedPlayerText(candidate.originalName)
        && game.minPlayers === candidate.minPlayers
        && game.maxPlayers === candidate.maxPlayers
        && game.minimumPlayTimeMinutes === candidate.minimumPlayTimeMinutes
        && game.maximumPlayTimeMinutes === candidate.maximumPlayTimeMinutes
        && game.replyParts.length === candidate.replyParts.length
        && game.replyParts.every((part, partIndex) => {
          const candidatePart = candidate.replyParts[partIndex]
          return candidatePart !== undefined
            && part.role === candidatePart.role
            && part.claimType === candidatePart.claimType
            && part.subject === candidatePart.subject
            && normalizedPlayerText(part.text) === normalizedPlayerText(candidatePart.text)
            && part.sourceIndexes.length === candidatePart.sourceIndexes.length
            && part.sourceIndexes.every((sourceIndex, index) =>
              sourceIndex === candidatePart.sourceIndexes[index])
        })
    })
}

function sha256(value: string) {
  return createHash('sha256').update(value, 'utf8').digest('hex')
}

function contentDigest(content: RecommendationPublishedContent): RecommendationContentDigest {
  const assistantMessage = normalizedPlayerText(content.assistantMessage)
  const normalizedGames = content.games.map(game => ({
    bggId: game.bggId,
    name: normalizedPlayerText(game.name),
    originalName: normalizedPlayerText(game.originalName),
    minPlayers: game.minPlayers,
    maxPlayers: game.maxPlayers,
    minimumPlayTimeMinutes: game.minimumPlayTimeMinutes,
    maximumPlayTimeMinutes: game.maximumPlayTimeMinutes,
    replyParts: game.replyParts.map(part => ({
      ...part,
      text: normalizedPlayerText(part.text),
    })),
  }))
  const replyParts = normalizedGames.flatMap(game => game.replyParts.map(part => part.text))
  return {
    assistantMessageSha256: sha256(assistantMessage),
    assistantMessageCharacterCount: Array.from(assistantMessage).length,
    cardReplyPartsSha256: sha256(JSON.stringify(normalizedGames)),
    cardReplyPartsCharacterCount: replyParts.reduce(
      (count, text) => count + Array.from(text).length,
      0,
    ),
    cardReplyPartCount: replyParts.length,
  }
}

async function markdownPlayerText(page: Page, source: string) {
  const rendered = PLAYER_MARKDOWN.render(source)
  return await page.evaluate(html => {
    const element = document.createElement('div')
    element.setAttribute('aria-hidden', 'true')
    element.style.position = 'fixed'
    element.style.left = '-100000px'
    element.style.top = '0'
    element.style.width = '800px'
    element.style.pointerEvents = 'none'
    element.innerHTML = html
    document.body.append(element)
    try {
      return element.innerText
    } finally {
      element.remove()
    }
  }, rendered)
}

async function playerVisibleProjection(page: Page, content: RecommendationPublishedContent) {
  return {
    assistantMessage: await markdownPlayerText(page, content.assistantMessage),
    games: await Promise.all(content.games.map(async game => ({
      ...game,
      replyParts: await Promise.all(game.replyParts.map(async part => ({
        ...part,
        text: await markdownPlayerText(page, part.text),
      }))),
    }))),
  }
}

function browserElapsed(observedAtEpochMs: number, clickStartedAtEpochMs: number) {
  const elapsed = Math.round(observedAtEpochMs - clickStartedAtEpochMs)
  return Number.isSafeInteger(elapsed) && elapsed >= 0 ? elapsed : null
}

class ObservedRecommendationStreamError extends Error {
  constructor(readonly observation: BrowserSseErrorObservation) {
    super(`Production recommendation stream failed (${observation.code})`)
    this.name = 'ObservedRecommendationStreamError'
  }
}

function hasPositiveDistinctBggIds(games: RecommendationGame[]) {
  const ids = games.map(entry => entry.game.bggId)
  return ids.length > 0
    && ids.every(id => Number.isSafeInteger(id) && id > 0)
    && new Set(ids).size === ids.length
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function isSafeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value)
}

function everyGameMatchesExpectedTitle(games: RecommendationGame[]) {
  if (EXPECTED_TITLE_TERM === '') return true
  return games.every(({ game }) => [game.name, game.originalName]
    .filter((title): title is string => typeof title === 'string')
    .some(title => title.normalize('NFKC').toLocaleLowerCase('en-US').includes(EXPECTED_TITLE_TERM)))
}

function profileMatchesHardConstraints(
  profile: RecommendationProfile | null | undefined,
  expectedPlayerCount: number,
  maximumDurationMinutes: number,
  maximumComplexity: number,
  expectedGameType: string,
) {
  return profile?.type === expectedGameType
    && profile.interaction === 'any'
    && profile.playerCount?.strength === 'hard'
    && isSafeInteger(profile.playerCount.minimum)
    && isSafeInteger(profile.playerCount.maximum)
    && profile.playerCount.minimum === expectedPlayerCount
    && profile.playerCount.maximum === expectedPlayerCount
    && profile.durationMinutes?.strength === 'hard'
    && isSafeInteger(profile.durationMinutes.maximum)
    && profile.durationMinutes.maximum === maximumDurationMinutes
    && (profile.durationMinutes.minimum === null
      || isSafeInteger(profile.durationMinutes.minimum)
        && profile.durationMinutes.minimum <= maximumDurationMinutes)
    && profile.complexity?.strength === 'hard'
    && isFiniteNumber(profile.complexity.maximum)
    && profile.complexity.maximum === maximumComplexity
    && (profile.complexity.minimum === null
      || isFiniteNumber(profile.complexity.minimum)
        && profile.complexity.minimum <= maximumComplexity)
}

function gamesMatchHardConstraints(
  games: RecommendationGame[],
  expectedPlayerCount: number,
  maximumDurationMinutes: number,
  maximumComplexity: number,
) {
  return games.length > 0 && games.every(({ game }) => {
    const maximumDuration = game.maximumPlayTimeMinutes ?? game.playingTimeMinutes
    return (game.overallRank === null
        || isSafeInteger(game.overallRank) && game.overallRank > 0)
      && isSafeInteger(game.minPlayers)
      && isSafeInteger(game.maxPlayers)
      && game.minPlayers >= 1
      && game.maxPlayers >= game.minPlayers
      && game.minPlayers <= expectedPlayerCount
      && game.maxPlayers >= expectedPlayerCount
      && isSafeInteger(maximumDuration)
      && maximumDuration > 0
      && maximumDuration <= maximumDurationMinutes
      && isFiniteNumber(game.averageWeight)
      && game.averageWeight >= 0
      && game.averageWeight <= maximumComplexity
  })
}

function gamesMatchCatalogBggType(games: RecommendationGame[], expectedGameType: string) {
  return games.length > 0 && games.every(({ game }) => Array.isArray(game.bggTypes)
    && game.bggTypes.every(type => typeof type === 'string')
    && game.bggTypes.includes(expectedGameType))
}

function gamesPublishSatisfiedHardFitClaims(games: RecommendationGame[]) {
  const requiredSubjects = ['playerCount', 'durationMinutes', 'complexity', 'bggType']
  return games.length > 0 && games.every(game => requiredSubjects.every(subject =>
    (game.fitClaims ?? []).some(claim => claim.subject === subject
      && claim.strength === 'hard'
      && claim.relation === 'satisfied')))
}

function gamesPublishEvidenceBoundReplyParts(games: RecommendationGame[]) {
  const sourceRequiredClaims = new Set([
    'attributed_experience',
    'rule_procedure',
    'publisher_description',
  ])
  return games.length > 0 && games.every(game => {
    const parts = game.replyParts ?? []
    return parts.some(part => Array.from(part.text.trim()).length >= 12)
      && parts.every(part => part.subject.trim() !== ''
        && part.text.trim() !== ''
        && Array.isArray(part.sourceIndexes)
        && part.sourceIndexes.every(index => Number.isSafeInteger(index) && index > 0)
        && (!sourceRequiredClaims.has(part.claimType) || part.sourceIndexes.length > 0))
  })
}

function sameOrderedBggSlate(rendered: number[], persisted: number[]) {
  return rendered.length === persisted.length
    && rendered.every((id, index) => Number.isSafeInteger(id) && id > 0 && id === persisted[index])
}

async function expectUsablePlayerSurface(element: Locator, message: string) {
  await element.scrollIntoViewIfNeeded()
  await expect.poll(async () => await element.evaluate(target => {
      if (!(target instanceof HTMLElement)) return false
      const geometry = target.getBoundingClientRect()
      let left = Math.max(0, geometry.left)
      let top = Math.max(0, geometry.top)
      let right = Math.min(document.documentElement.clientWidth, geometry.right)
      let bottom = Math.min(document.documentElement.clientHeight, geometry.bottom)
      let current: HTMLElement | null = target
      const clips = (value: string) => ['auto', 'clip', 'hidden', 'scroll'].includes(value)
      const clipsPermanently = (value: string) => ['clip', 'hidden'].includes(value)
      while (current) {
        const style = getComputedStyle(current)
        const opacity = Number.parseFloat(style.opacity)
        if (style.display === 'none'
          || style.visibility !== 'visible'
          || style.contentVisibility === 'hidden'
          || !Number.isFinite(opacity)
          || opacity < 0.999) return false
        if (current === target) {
          const lineClamp = style.getPropertyValue('-webkit-line-clamp')
          if ((lineClamp !== '' && lineClamp !== 'none' && lineClamp !== '0')
            || (clipsPermanently(style.overflowX) && current.scrollWidth > current.clientWidth + 1)
            || (clipsPermanently(style.overflowY) && current.scrollHeight > current.clientHeight + 1)) {
            return false
          }
        }
        if (current !== target) {
          const bounds = current.getBoundingClientRect()
          if ((clipsPermanently(style.overflowX)
              && (geometry.left < bounds.left - 1 || geometry.right > bounds.right + 1))
            || (clipsPermanently(style.overflowY)
              && (geometry.top < bounds.top - 1 || geometry.bottom > bounds.bottom + 1))) {
            return false
          }
          if (clips(style.overflowX)) {
            left = Math.max(left, bounds.left)
            right = Math.min(right, bounds.right)
          }
          if (clips(style.overflowY)) {
            top = Math.max(top, bounds.top)
            bottom = Math.min(bottom, bounds.bottom)
          }
        }
        current = current.parentElement
      }
      if (right - left < Math.min(24, geometry.width)
        || bottom - top < Math.min(20, geometry.height)) return false
      const hit = document.elementFromPoint((left + right) / 2, (top + bottom) / 2)
      return hit !== null && (hit === target || target.contains(hit))
    }), {
    message,
    timeout: 5_000,
    intervals: [50, 100, 250],
  }).toBe(true)
}

async function installRecommendationStreamEvidence(page: Page) {
  await page.addInitScript(() => {
    type StreamTerminal = {
      kind: 'result'
      browserReceivedAtEpochMs: number
      clientTurnId: string | null
      outcome: string | null
      failureBoundary: string | null
      bggIds: number[]
      content: {
        assistantMessage: string
        games: Array<{ bggId: number, replyParts: string[] }>
      }
    } | {
      kind: 'error'
      browserReceivedAtEpochMs: number
      code: string
      failureBoundary: string | null
    }

    interface StreamEvidenceState {
      generation: number
      clickStartedAtEpochMs: number | null
      progressEvents: Array<{
        stage: string
        phase: string
        action: string | null
        serverElapsedMs: number
        browserReceivedAtEpochMs: number
        observedCandidates: number | null
        verifiedCandidates: number | null
        hardRejectedCandidates: number | null
        sourceCount: number | null
      }>
      sseTerminal: StreamTerminal | null
      probeFailed: boolean
    }

    const observedWindow = window as typeof window & {
      __rulepilotRecommendationStreamEvidence?: StreamEvidenceState
    }
    if (observedWindow.__rulepilotRecommendationStreamEvidence) return

    const state: StreamEvidenceState = {
      generation: 0,
      clickStartedAtEpochMs: null,
      progressEvents: [],
      sseTerminal: null,
      probeFailed: false,
    }
    observedWindow.__rulepilotRecommendationStreamEvidence = state
    const nativeFetch: typeof window.fetch = window.fetch.bind(window)

    const observedAtEpochMs = () => Math.round(performance.timeOrigin + performance.now())
    const nonNegativeInteger = (value: unknown) => Number.isSafeInteger(value) && Number(value) >= 0
      ? Number(value)
      : null
    const publicFailureBoundaries = new Set([
      'time_budget',
      'model_response',
      'service_configuration',
      'action_budget',
      'publication_boundary',
      'service_failure',
    ])
    const publicErrorCodes = new Set([
      'recommendation_unavailable',
      'not_found',
      'revision_conflict',
      'turn_id_reused',
      'turn_in_progress',
      'concurrent_turn',
    ])
    const failureBoundary = (value: unknown) => typeof value === 'string'
      && publicFailureBoundaries.has(value) ? value : null

    document.addEventListener('click', event => {
      const target = event.target instanceof Element ? event.target : null
      const button = target?.closest('button[type="submit"]')
      if (!(button instanceof HTMLButtonElement)
        || !button.form?.querySelector('#recommendation-agent-message')) return
      state.clickStartedAtEpochMs ??= observedAtEpochMs()
    }, { capture: true })

    const consumeEvent = (raw: string, generation: number) => {
      if (generation !== state.generation || state.sseTerminal !== null) return
      let eventName = ''
      const data: string[] = []
      for (const line of raw.split('\n')) {
        if (line.startsWith(':')) continue
        const separator = line.indexOf(':')
        const field = separator < 0 ? line : line.slice(0, separator)
        const value = separator < 0 ? '' : line.slice(separator + 1).replace(/^ /, '')
        if (field === 'event') eventName = value
        if (field === 'data') data.push(value)
      }
      if (!eventName || data.length === 0) return

      let payload: Record<string, unknown>
      try {
        const decoded = JSON.parse(data.join('\n')) as unknown
        if (decoded === null || typeof decoded !== 'object' || Array.isArray(decoded)) {
          state.probeFailed = true
          if (eventName === 'error') {
            state.sseTerminal = {
              kind: 'error',
              browserReceivedAtEpochMs: observedAtEpochMs(),
              code: 'invalid_stream_error',
              failureBoundary: null,
            }
          }
          return
        }
        payload = decoded as Record<string, unknown>
      } catch {
        state.probeFailed = true
        if (eventName === 'error') {
          state.sseTerminal = {
            kind: 'error',
            browserReceivedAtEpochMs: observedAtEpochMs(),
            code: 'invalid_stream_error',
            failureBoundary: null,
          }
        }
        return
      }

      if (eventName === 'progress') {
        const serverElapsedMs = nonNegativeInteger(payload.elapsedMs)
        if (typeof payload.stage !== 'string'
          || typeof payload.phase !== 'string'
          || serverElapsedMs === null) return
        state.progressEvents.push({
          stage: payload.stage,
          phase: payload.phase,
          action: typeof payload.action === 'string' ? payload.action : null,
          serverElapsedMs,
          browserReceivedAtEpochMs: observedAtEpochMs(),
          observedCandidates: nonNegativeInteger(payload.observedCandidates),
          verifiedCandidates: nonNegativeInteger(payload.verifiedCandidates),
          hardRejectedCandidates: nonNegativeInteger(payload.hardRejectedCandidates),
          sourceCount: nonNegativeInteger(payload.sourceCount),
        })
        state.progressEvents = state.progressEvents.slice(-64)
        return
      }

      if (eventName === 'result') {
        const rawGames = Array.isArray(payload.games) ? payload.games : []
        const games = rawGames.map(entry => {
          if (entry === null || typeof entry !== 'object' || Array.isArray(entry)) return null
          const recommendation = entry as Record<string, unknown>
          const game = recommendation.game
          if (game === null || typeof game !== 'object' || Array.isArray(game)) return null
          const gameRecord = game as Record<string, unknown>
          const bggId = gameRecord.bggId
          if (!Number.isSafeInteger(bggId) || Number(bggId) <= 0) return null
          const nullableInteger = (value: unknown) => value === null
            ? null
            : Number.isSafeInteger(value) ? Number(value) : null
          const name = typeof gameRecord.name === 'string' ? gameRecord.name : ''
          const originalName = typeof gameRecord.originalName === 'string'
            ? gameRecord.originalName
            : ''
          const playingTimeMinutes = nullableInteger(gameRecord.playingTimeMinutes)
          const rawReplyParts = Array.isArray(recommendation.replyParts)
            ? recommendation.replyParts
            : []
          const replyParts = rawReplyParts.map(part => {
            if (part === null || typeof part !== 'object' || Array.isArray(part)) return null
            const partRecord = part as Record<string, unknown>
            const text = typeof partRecord.text === 'string' ? partRecord.text : null
            const role = typeof partRecord.role === 'string' ? partRecord.role : null
            const claimType = typeof partRecord.claimType === 'string' ? partRecord.claimType : null
            const subject = typeof partRecord.subject === 'string' ? partRecord.subject : null
            const sourceIndexes = Array.isArray(partRecord.sourceIndexes)
              && partRecord.sourceIndexes.every(index => Number.isSafeInteger(index))
              ? partRecord.sourceIndexes.map(Number)
              : null
            return text === null || role === null || claimType === null || subject === null
              || sourceIndexes === null
              ? null
              : { role, claimType, subject, text, sourceIndexes }
          }).filter(value => value !== null)
          return {
            bggId: Number(bggId),
            name,
            originalName,
            minPlayers: nullableInteger(gameRecord.minPlayers),
            maxPlayers: nullableInteger(gameRecord.maxPlayers),
            minimumPlayTimeMinutes:
              nullableInteger(gameRecord.minimumPlayTimeMinutes) ?? playingTimeMinutes,
            maximumPlayTimeMinutes:
              nullableInteger(gameRecord.maximumPlayTimeMinutes) ?? playingTimeMinutes,
            replyParts,
          }
        }).filter(value => value !== null)
        state.sseTerminal = {
          kind: 'result',
          browserReceivedAtEpochMs: observedAtEpochMs(),
          clientTurnId: typeof payload.clientTurnId === 'string' ? payload.clientTurnId : null,
          outcome: typeof payload.outcome === 'string' ? payload.outcome : null,
          failureBoundary: failureBoundary(payload.failureBoundary),
          bggIds: games.map(game => game.bggId),
          content: {
            assistantMessage: typeof payload.assistantMessage === 'string' ? payload.assistantMessage : '',
            games,
          },
        }
        return
      }

      if (eventName === 'error') {
        state.probeFailed = true
        const code = typeof payload.code === 'string' && publicErrorCodes.has(payload.code)
          ? payload.code
          : 'unknown_stream_error'
        state.sseTerminal = {
          kind: 'error',
          browserReceivedAtEpochMs: observedAtEpochMs(),
          code,
          failureBoundary: failureBoundary(payload.failureBoundary),
        }
      }
    }

    const observeStream = async (response: Response, generation: number) => {
      if (!response.body) return
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (true) {
        const chunk = await reader.read()
        buffer = `${buffer}${decoder.decode(chunk.value, { stream: !chunk.done })}`.replaceAll('\r\n', '\n')
        let boundary = buffer.indexOf('\n\n')
        while (boundary >= 0) {
          consumeEvent(buffer.slice(0, boundary), generation)
          buffer = buffer.slice(boundary + 2)
          boundary = buffer.indexOf('\n\n')
        }
        if (chunk.done) break
      }
      if (buffer.trim()) consumeEvent(buffer, generation)
    }

    observedWindow.fetch = async (...args: Parameters<typeof window.fetch>) => {
      const response = await nativeFetch(...args)
      const [input, init] = args
      const requestUrl = input instanceof Request ? input.url : input.toString()
      const requestMethod = (init?.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase()
      let isRecommendationStream = false
      try {
        isRecommendationStream = requestMethod === 'POST'
          && new URL(requestUrl, window.location.href).pathname === '/api/v1/bgg/recommendation-agent/stream'
      } catch {
        // An unrelated malformed request remains the application's responsibility.
      }
      if (isRecommendationStream) {
        try {
          const generation = state.generation
          void observeStream(response.clone(), generation).catch(() => {
            if (generation === state.generation) state.probeFailed = true
          })
        } catch {
          state.probeFailed = true
        }
      }
      return response
    }
  })
}

async function resetRecommendationStreamEvidence(page: Page) {
  await page.evaluate(() => {
    const observedWindow = window as typeof window & {
      __rulepilotRecommendationStreamEvidence?: {
        generation: number
        clickStartedAtEpochMs: number | null
        progressEvents: unknown[]
        sseTerminal: unknown
        probeFailed: boolean
      }
    }
    const state = observedWindow.__rulepilotRecommendationStreamEvidence
    if (!state) throw new Error('recommendation stream evidence probe is unavailable')
    state.generation += 1
    state.clickStartedAtEpochMs = null
    state.progressEvents = []
    state.sseTerminal = null
    state.probeFailed = false
  })
}

async function recommendationClickEpoch(page: Page) {
  return await page.evaluate(() => {
    const observedWindow = window as typeof window & {
      __rulepilotRecommendationStreamEvidence?: { clickStartedAtEpochMs: number | null }
    }
    return observedWindow.__rulepilotRecommendationStreamEvidence?.clickStartedAtEpochMs ?? null
  })
}

async function waitForBrowserSseResult(
  page: Page,
  timeoutMs: number,
): Promise<BrowserSseTerminalObservation | null> {
  try {
    const result = await page.waitForFunction(() => {
      const observedWindow = window as typeof window & {
        __rulepilotRecommendationStreamEvidence?: {
          sseTerminal: BrowserSseTerminalObservation | null
        }
      }
      return observedWindow.__rulepilotRecommendationStreamEvidence?.sseTerminal ?? null
    }, undefined, { polling: 50, timeout: timeoutMs })
    try {
      return await result.jsonValue() as BrowserSseTerminalObservation
    } finally {
      await result.dispose()
    }
  } catch {
    return null
  }
}

async function waitForFirstProgressVisible(
  page: Page,
  visibleSloMs: number,
  observerTimeoutMs: number,
) {
  try {
    const result = await page.waitForFunction((sloMs) => {
      const observedWindow = window as typeof window & {
        __rulepilotRecommendationStreamEvidence?: {
          clickStartedAtEpochMs: number | null
          progressEvents: Array<{ browserReceivedAtEpochMs: number }>
          sseTerminal: unknown
        }
      }
      const state = observedWindow.__rulepilotRecommendationStreamEvidence
      if (!state || state.clickStartedAtEpochMs === null) return null

      const observedAtEpochMs = Math.round(performance.timeOrigin + performance.now())
      const visibleProgress = () => {
        const progress = document.querySelector<HTMLElement>(
          '[data-testid="recommendation-progress-steps"]',
        )
        if (!progress) return false

        const geometry = progress.getBoundingClientRect()
        let left = Math.max(0, geometry.left)
        let top = Math.max(0, geometry.top)
        let right = Math.min(document.documentElement.clientWidth, geometry.right)
        let bottom = Math.min(document.documentElement.clientHeight, geometry.bottom)
        let current: HTMLElement | null = progress
        const clips = (value: string) => ['auto', 'clip', 'hidden', 'scroll'].includes(value)
        while (current) {
          const style = getComputedStyle(current)
          const opacity = Number.parseFloat(style.opacity)
          if (style.display === 'none'
            || style.visibility !== 'visible'
            || style.contentVisibility === 'hidden'
            || !Number.isFinite(opacity)
            || opacity < 0.999) return false
          if (current !== progress) {
            const bounds = current.getBoundingClientRect()
            if (clips(style.overflowX)) {
              left = Math.max(left, bounds.left)
              right = Math.min(right, bounds.right)
            }
            if (clips(style.overflowY)) {
              top = Math.max(top, bounds.top)
              bottom = Math.min(bottom, bounds.bottom)
            }
          }
          current = current.parentElement
        }
        if (right <= left || bottom <= top) return false
        const hit = document.elementFromPoint((left + right) / 2, (top + bottom) / 2)
        return hit !== null && (hit === progress || progress.contains(hit))
      }

      const causalProgressObserved = state.progressEvents.some(
        event => event.browserReceivedAtEpochMs >= state.clickStartedAtEpochMs!,
      )
      if (causalProgressObserved && visibleProgress()) {
        return { visible: true, observedAtEpochMs }
      }
      if (state.sseTerminal !== null
        || observedAtEpochMs - state.clickStartedAtEpochMs >= sloMs) {
        return { visible: false, observedAtEpochMs: null }
      }
      return null
    }, visibleSloMs, { polling: 50, timeout: observerTimeoutMs })
    try {
      return await result.jsonValue() as { visible: boolean; observedAtEpochMs: number | null }
    } finally {
      await result.dispose()
    }
  } catch {
    return { visible: false, observedAtEpochMs: null }
  }
}

async function readBrowserProgressEvidence(page: Page, startedAtEpochMs: number) {
  return await page.evaluate((startedAt) => {
    const observedWindow = window as typeof window & {
      __rulepilotRecommendationStreamEvidence?: {
        progressEvents: BrowserProgressObservation[]
        probeFailed: boolean
      }
    }
    const state = observedWindow.__rulepilotRecommendationStreamEvidence
    if (!state) return { progressEvents: [], probeFailed: true }
    return {
      progressEvents: state.progressEvents
        .filter(event => event.browserReceivedAtEpochMs >= startedAt)
        .map(({ browserReceivedAtEpochMs, ...event }) => ({
          ...event,
          browserReceivedMs: Math.round(browserReceivedAtEpochMs - startedAt),
        })),
      probeFailed: state.probeFailed,
    }
  }, startedAtEpochMs)
}

async function waitForFirstRenderedSlate(page: Page, timeoutMs: number): Promise<{
  observedAtEpochMs: number
  bggIds: number[]
} | null> {
  try {
    const result = await page.waitForFunction(() => {
      const observedWindow = window as typeof window & {
        __rulepilotRecommendationSlateStability?: { key: string; frames: number }
        __rulepilotRecommendationStreamEvidence?: {
          clickStartedAtEpochMs: number | null
          sseTerminal: {
            kind: 'result'
            bggIds: number[]
          } | { kind: 'error' } | null
        }
      }

      const renderedTree = (element: HTMLElement) => {
        let current: HTMLElement | null = element
        while (current) {
          const style = getComputedStyle(current)
          const opacity = Number.parseFloat(style.opacity)
          if (style.display === 'none'
            || style.visibility !== 'visible'
            || style.contentVisibility === 'hidden'
            || !Number.isFinite(opacity)
            || opacity < 0.999) return false
          current = current.parentElement
        }
        return true
      }

      const clippedVisibleRect = (element: HTMLElement, geometry: DOMRect) => {
        const viewportWidth = document.documentElement.clientWidth
        const viewportHeight = document.documentElement.clientHeight
        let left = Math.max(0, geometry.left)
        let top = Math.max(0, geometry.top)
        let right = Math.min(viewportWidth, geometry.right)
        let bottom = Math.min(viewportHeight, geometry.bottom)
        let ancestor = element.parentElement
        const clips = (value: string) => ['auto', 'clip', 'hidden', 'scroll'].includes(value)
        while (ancestor) {
          const style = getComputedStyle(ancestor)
          const bounds = ancestor.getBoundingClientRect()
          if (clips(style.overflowX)) {
            left = Math.max(left, bounds.left)
            right = Math.min(right, bounds.right)
          }
          if (clips(style.overflowY)) {
            top = Math.max(top, bounds.top)
            bottom = Math.min(bottom, bounds.bottom)
          }
          ancestor = ancestor.parentElement
        }
        if (right - left < Math.min(44, geometry.width)
          || bottom - top < Math.min(44, geometry.height)) return null
        return { left, top, right, bottom }
      }

      const usableInViewport = (element: HTMLElement, geometry: DOMRect) => {
        if (!renderedTree(element)) return null
        const visible = clippedVisibleRect(element, geometry)
        if (visible === null) return null
        const x = visible.left + (visible.right - visible.left) / 2
        const y = visible.top + (visible.bottom - visible.top) / 2
        const hit = document.elementFromPoint(x, y)
        if (hit === null || (hit !== element && !element.contains(hit))) return null
        return visible
      }

      const visibleSlate = () => {
        const streamEvidence = observedWindow.__rulepilotRecommendationStreamEvidence
        if (streamEvidence?.clickStartedAtEpochMs === null
          || streamEvidence?.clickStartedAtEpochMs === undefined
          || streamEvidence.sseTerminal?.kind !== 'result') {
          return null
        }
        const entries = [...document.querySelectorAll<HTMLElement>('[data-testid="recommendation-game-card"]')]
        if (entries.length === 0) return null
        const bggIds: number[] = []
        const geometryKeys: string[] = []
        for (const [index, card] of entries.entries()) {
          const bggId = Number(card.getAttribute('data-bgg-id'))
          const style = getComputedStyle(card)
          const geometry = card.getBoundingClientRect()
          const opacity = Number.parseFloat(style.opacity)
          if (!Number.isSafeInteger(bggId)
            || bggId <= 0
            || style.display === 'none'
            || style.visibility !== 'visible'
            || !Number.isFinite(opacity)
            || opacity < 0.999
            || geometry.width <= 0
            || geometry.height <= 0) return null
          const visible = index === 0 ? usableInViewport(card, geometry) : null
          if (index === 0 && visible === null) return null
          bggIds.push(bggId)
          geometryKeys.push([
            geometry.x,
            geometry.y,
            geometry.width,
            geometry.height,
          ].map(value => value.toFixed(2)).join(':'))
          if (visible) {
            geometryKeys.push([
              visible.left,
              visible.top,
              visible.right,
              visible.bottom,
            ].map(value => value.toFixed(2)).join(':'))
          }
        }
        if (bggIds.length !== streamEvidence.sseTerminal.bggIds.length
          || !bggIds.every((bggId, index) => bggId === streamEvidence.sseTerminal?.bggIds[index])) {
          return null
        }
        return { bggIds, key: `${bggIds.join(',')}|${geometryKeys.join('|')}` }
      }

      const slate = visibleSlate()
      if (slate === null) {
        delete observedWindow.__rulepilotRecommendationSlateStability
        return null
      }
      const previous = observedWindow.__rulepilotRecommendationSlateStability
      const frames = previous?.key === slate.key ? previous.frames + 1 : 1
      observedWindow.__rulepilotRecommendationSlateStability = { key: slate.key, frames }
      if (frames < 3) return null
      delete observedWindow.__rulepilotRecommendationSlateStability
      return {
        observedAtEpochMs: Math.round(performance.timeOrigin + performance.now()),
        bggIds: slate.bggIds,
      }
    }, undefined, { polling: 'raf', timeout: timeoutMs })
    try {
      return await result.jsonValue() as { observedAtEpochMs: number; bggIds: number[] }
    } finally {
      await result.dispose()
    }
  } catch {
    return null
  }
}

async function renderedRecommendationContent(page: Page): Promise<RecommendationPublishedContent> {
  return await page.getByTestId('assistant-recommendation-turn').last().evaluate(turn => {
    const nullableIntegerAttribute = (element: Element, name: string) => {
      const value = element.getAttribute(name)
      if (value === null || !/^-?\d+$/.test(value)) return null
      const parsed = Number(value)
      return Number.isSafeInteger(parsed) ? parsed : null
    }
    const sourceIndexes = (value: string | null) => value === null || value === ''
      ? []
      : value.split(',').map(Number)
    const assistantMessage = turn.querySelector<HTMLElement>(
      '[data-testid="assistant-recommendation-message"]',
    )
    if (!assistantMessage) throw new Error('player-visible recommendation reply is unavailable')
    return {
      assistantMessage: assistantMessage.innerText,
      games: [...turn.querySelectorAll<HTMLElement>('[data-testid="recommendation-game-card"]')]
        .map(card => ({
          bggId: Number(card.getAttribute('data-bgg-id')),
          name: card.getAttribute('data-game-name') ?? '',
          originalName: card.getAttribute('data-original-name') ?? '',
          minPlayers: nullableIntegerAttribute(card, 'data-min-players'),
          maxPlayers: nullableIntegerAttribute(card, 'data-max-players'),
          minimumPlayTimeMinutes: nullableIntegerAttribute(card, 'data-minimum-play-time'),
          maximumPlayTimeMinutes: nullableIntegerAttribute(card, 'data-maximum-play-time'),
          replyParts: [...card.querySelectorAll<HTMLElement>('dl > div')]
            .map(part => ({
              role: part.getAttribute('data-role') ?? '',
              claimType: part.getAttribute('data-claim-type') ?? '',
              subject: part.getAttribute('data-subject') ?? '',
              text: part.querySelector<HTMLElement>('dd')?.innerText ?? '',
              sourceIndexes: sourceIndexes(part.getAttribute('data-source-indexes')),
            })),
        })),
    }
  })
}

async function waitForPersistedTerminal(
  request: APIRequestContext,
  conversationId: string,
  baselineRevision: number,
  clientTurnId: string,
  nodeStartedAtEpochMs: number,
  nodeDeadlineAtEpochMs: number,
  signal: AbortSignal,
): Promise<TerminalObservation> {
  let successfulReads = 0
  do {
    if (signal.aborted) break
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
            elapsedMs: nodeElapsed(nodeStartedAtEpochMs),
          }
        }
      }
    } catch {
      // A later successful persisted-session read remains authoritative in this bounded window.
    }
    await sleep(250, signal)
  } while (!signal.aborted && Date.now() <= nodeDeadlineAtEpochMs)

  return {
    category: successfulReads > 0 ? 'SESSION_TIMEOUT' : 'SESSION_READ_FAILURE',
    session: null,
    elapsedMs: nodeElapsed(nodeStartedAtEpochMs),
  }
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

async function recommendationModelAssignment(request: APIRequestContext) {
  const response = await request.get('/api/v1/model-configuration')
  if (!response.ok()) throw new Error('Production model configuration snapshot is unavailable')
  const snapshot = await response.json() as ModelConfigurationSnapshot
  const provider = snapshot.recommendationModel?.provider?.trim()
  const model = snapshot.recommendationModel?.model?.trim()
  if (!provider || !model) throw new Error('Production recommendation model assignment is invalid')
  return { provider, model }
}

async function publicReleaseIdentity(request: APIRequestContext) {
  const response = await request.get('/api/public/release')
  if (!response.ok()) throw new Error('Public production release identity is unavailable')
  const identity = await response.json() as PublicReleaseIdentity
  const cacheControl = response.headers()['cache-control'] ?? ''
  if (!/^[0-9a-f]{40}-[0-9]+(?:-[0-9]+)?$/.test(identity.releaseId)
    || !/^[0-9a-f]{40}$/.test(identity.commitSha)
    || !cacheControl.toLocaleLowerCase('en-US').split(',').some(value => value.trim() === 'no-store')) {
    throw new Error('Public production release identity is invalid or cacheable')
  }
  return { ...identity, noStore: true }
}

async function retainReport(path: string, report: ProductionRecommendationReport) {
  report.generatedAt = new Date().toISOString()
  await writeFile(path, `${JSON.stringify(report, null, 2)}\n`, { mode: 0o600 })
}

test('production returns one recommendation slate and hands its exact identity to rulebook discovery', async ({ page }) => {
  test.skip(!enabled, 'Runs only through the credentialed production recommendation workflow')
  test.setTimeout(5 * 60_000)

  const requestedCardCount = parseRequestedCardCount(
    process.env.RULEPILOT_RECOMMENDATION_EXPECTED_CARD_COUNT,
  )
  const expectedPlayerCount = parseExpectedPositiveInteger(
    process.env.RULEPILOT_RECOMMENDATION_EXPECTED_PLAYER_COUNT,
    'expected recommendation player count',
    20,
  )
  const maximumDurationMinutes = parseExpectedPositiveInteger(
    process.env.RULEPILOT_RECOMMENDATION_MAXIMUM_DURATION_MINUTES,
    'maximum recommendation duration',
    1_440,
  )
  const maximumComplexity = parseExpectedDecimal(
    process.env.RULEPILOT_RECOMMENDATION_MAXIMUM_COMPLEXITY,
    'maximum recommendation BGG complexity',
    5,
  )
  const expectedGameType = parseExpectedGameType(
    process.env.RULEPILOT_RECOMMENDATION_EXPECTED_GAME_TYPE,
  )
  const username = process.env.RULEPILOT_RECOMMENDATION_USER
  const password = process.env.RULEPILOT_RECOMMENDATION_PASSWORD
  const reportFile = process.env.RULEPILOT_RECOMMENDATION_REPORT
  if (!username || !password || !reportFile || SELECTION_PROMPT.trim() === ''
    || !EXPECTED_MODEL_PROVIDER || !EXPECTED_MODEL_NAME) {
    throw new Error('Production recommendation credentials, prompt, expected model, and report path are required')
  }
  if (!/^[0-9a-f]{40}$/.test(TESTED_SHA)
    || !/^[0-9a-f]{40}-[0-9]+-[0-9]+$/.test(ACTIVE_RELEASE_ID)
    || !ACTIVE_RELEASE_ID.startsWith(`${TESTED_SHA}-`)) {
    throw new Error('Production recommendation verification requires one exact active tested release')
  }

  const report: ProductionRecommendationReport = {
    generatedAt: new Date().toISOString(),
    completed: false,
    stage: 'login',
    testedSha: TESTED_SHA,
    activeReleaseSha: TESTED_SHA,
    activeReleaseId: ACTIVE_RELEASE_ID,
    publicReleaseId: null,
    publicReleaseSha: null,
    publicReleaseNoStore: null,
    routeStayedOnDiscover: false,
    recommendationRequestedCardCount: requestedCardCount,
    recommendationExpectedPlayerCount: expectedPlayerCount,
    recommendationMaximumDurationMinutes: maximumDurationMinutes,
    recommendationMaximumComplexity: maximumComplexity,
    recommendationExpectedGameType: expectedGameType,
    recommendationRequestMessageMatched: false,
    recommendationProfileHardConstraintsMatched: null,
    recommendationCardsHardConstraintsMatched: null,
    recommendationFitClaimsHardConstraintsMatched: null,
    recommendationComplexityHardConstraintsMatched: null,
    recommendationGameTypeHardConstraintsMatched: null,
    recommendationEvidenceBoundReplyParts: null,
    recommendationPersistedCardCount: null,
    recommendationShortfallCount: null,
    recommendationOutcome: null,
    recommendationTerminalCategory: 'NOT_OBSERVED',
    recommendationTerminalObserved: false,
    recommendationClickCaptured: false,
    recommendationFirstProgressMs: null,
    recommendationSseTerminalCategory: 'NOT_OBSERVED',
    recommendationSseTerminalMs: null,
    recommendationSseResultMs: null,
    recommendationSseErrorCode: null,
    recommendationSseFailureBoundary: null,
    recommendationPersistedTerminalMs: null,
    recommendationRenderedSlateMs: null,
    recommendationElapsedMs: null,
    recommendationSloMet: null,
    recommendationProgressEvents: [],
    recommendationStreamProbeFailed: false,
    recommendationPublishedBggIds: [],
    recommendationAssistantReplyCharacterCount: null,
    recommendationRenderedReplyCharacterCount: null,
    recommendationCardReplyPartCount: null,
    recommendationUsableCardCount: 0,
    recommendationUsableReplyPartCount: 0,
    recommendationAssistantReplyUsable: false,
    recommendationAllCardsUsable: false,
    recommendationAllReplyPartsUsable: false,
    recommendationSseContentDigest: null,
    recommendationPersistedContentDigest: null,
    recommendationRenderedContentDigest: null,
    recommendationSsePersistedContentConsistent: null,
    recommendationPersistedDomContentConsistent: null,
    recommendationCompletedWork: [],
    recommendationExpectedModel: {
      provider: EXPECTED_MODEL_PROVIDER,
      model: EXPECTED_MODEL_NAME,
    },
    recommendationModelBeforeRequest: null,
    recommendationModelAfterRequest: null,
    recommendationModelProvider: null,
    recommendationModelName: null,
    recommendationModelCalls: null,
    recommendationModelCallElapsedMs: [],
    recommendationAgentElapsedMs: null,
    recommendationModelElapsedShare: null,
    recommendationCatalogCalls: null,
    recommendationWebResearchCalls: null,
    recommendationFailureBoundary: null,
    expectedRecommendationTitleTermSha256: sha256(EXPECTED_TITLE_TERM),
    handoffSelectedBggId: null,
    handoffActionClicked: false,
    handoffSurfaceVisible: false,
    handoffImportRequestedBggId: null,
    handoffImportResponseStatus: null,
    handoffImportResponseOk: null,
    handoffImportResponseBggId: null,
    handoffImportedGameId: null,
    handoffImportedEditionId: null,
    handoffEditionBelongsToImportedGame: null,
    handoffImportElapsedMs: null,
    handoffDiscoveryRequestedEditionId: null,
    handoffDiscoveryResponseStatus: null,
    handoffDiscoveryResponseOk: null,
    handoffDiscoveryIdentityEditionId: null,
    handoffDiscoveryIdentityMatched: null,
    handoffDiscoveryConfigured: null,
    handoffDiscoveryCandidateCount: null,
    handoffImportableCandidateCount: null,
    handoffImportableCandidateFound: null,
    handoffDiscoveryCandidateIdentitySha256: null,
    handoffRenderedCandidateIdentitySha256: null,
    handoffCandidateIdentityOrderConsistent: null,
    handoffDiscoveryElapsedMs: null,
    handoffTerminalCategory: 'NOT_OBSERVED',
    handoffTerminalVisible: false,
    handoffElapsedMs: null,
    handoffRulebookImportStarted: false,
    handoffRestoredExistingJourney: false,
    handoffRestoredImportJobId: null,
    handoffRestoredDocumentVersionId: null,
    handoffRestoredPreparationRunId: null,
    handoffFreshnessRequestPreparationRunMatched: null,
    handoffFreshnessResponseStatus: null,
    handoffFreshnessResponseIdentityMatched: null,
    handoffFreshnessResponseEligible: null,
    handoffOfficialMutationAttemptedPaths: [],
    handoffOfficialMutationBlocked: false,
    handoffStoppedAtDiscoveryBoundary: false,
    rawModelOutputCaptured: false,
  }

  try {
    await installRecommendationStreamEvidence(page)
    await retainReport(reportFile, report)
    const publicReleaseBefore = await publicReleaseIdentity(page.request)
    expect(publicReleaseBefore.commitSha,
      'The public API did not serve the exact production release selected over SSH')
      .toBe(TESTED_SHA)
    expect(publicReleaseBefore.releaseId,
      'The public API did not serve the exact production release selected over SSH')
      .toBe(ACTIVE_RELEASE_ID)
    report.publicReleaseId = publicReleaseBefore.releaseId
    report.publicReleaseSha = publicReleaseBefore.commitSha
    report.publicReleaseNoStore = publicReleaseBefore.noStore
    await login(page, username, password)
    const modelAssignment = await recommendationModelAssignment(page.request)
    report.recommendationModelBeforeRequest = modelAssignment
    report.recommendationModelProvider = modelAssignment.provider
    report.recommendationModelName = modelAssignment.model
    expect(modelAssignment.provider,
      'Production recommendation used a different effective provider than the canary requested')
      .toBe(EXPECTED_MODEL_PROVIDER)
    expect(modelAssignment.model,
      'Production recommendation used a different effective model than the canary requested')
      .toBe(EXPECTED_MODEL_NAME)
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
    await resetRecommendationStreamEvidence(page)
    const firstProgressVisiblePromise = waitForFirstProgressVisible(
      page,
      FIRST_PROGRESS_SLO_MS,
      TERMINAL_OBSERVATION_MS,
    )
    const sseResultPromise = waitForBrowserSseResult(page, TERMINAL_OBSERVATION_MS)
    const renderedSlatePromise = waitForFirstRenderedSlate(page, TERMINAL_OBSERVATION_MS)
    await page.getByRole('button', { name: '发送', exact: true }).click()
    const browserClickStartedAtEpochMs = await recommendationClickEpoch(page)
    if (browserClickStartedAtEpochMs === null) {
      throw new Error('The browser did not capture the recommendation send click')
    }
    report.recommendationClickCaptured = true
    const recommendationRequest = await requestPromise
    const requestBody = recommendationRequest.postDataJSON() as {
      conversationId?: unknown
      revision?: unknown
      clientTurnId?: unknown
      message?: unknown
    } | null
    expect(requestBody?.conversationId).toBe(created.conversationId)
    expect(requestBody?.revision).toBe(created.revision)
    expect(requestBody?.message,
      'The browser did not send the exact player request configured for this canary')
      .toBe(SELECTION_PROMPT)
    report.recommendationRequestMessageMatched = requestBody?.message === SELECTION_PROMPT
    expect(requestBody?.clientTurnId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    )
    const clientTurnId = String(requestBody?.clientTurnId)
    const observationAbort = new AbortController()
    const persistedTerminalPromise = waitForPersistedTerminal(
      page.request,
      created.conversationId,
      created.revision,
      clientTurnId,
      browserClickStartedAtEpochMs,
      browserClickStartedAtEpochMs + TERMINAL_OBSERVATION_MS,
      observationAbort.signal,
    )
    const gatedSseResultPromise = sseResultPromise.then((observation): BrowserSseResultObservation | null => {
      if (observation?.kind === 'error') throw new ObservedRecommendationStreamError(observation)
      return observation
    })
    let observations: {
      firstProgressVisible: { visible: boolean; observedAtEpochMs: number | null }
      terminal: TerminalObservation
      sseResult: BrowserSseResultObservation | null
      renderedSlate: { observedAtEpochMs: number, bggIds: number[] } | null
    }
    try {
      const [firstProgressVisible, terminal, sseResult, renderedSlate] = await Promise.all([
        firstProgressVisiblePromise,
        persistedTerminalPromise,
        gatedSseResultPromise,
        renderedSlatePromise,
      ])
      observations = { firstProgressVisible, terminal, sseResult, renderedSlate }
    } catch (error) {
      observationAbort.abort()
      if (error instanceof ObservedRecommendationStreamError) {
        const firstProgressVisible = await firstProgressVisiblePromise
        const streamEvidence = await readBrowserProgressEvidence(page, browserClickStartedAtEpochMs)
        const streamElapsedMs = browserElapsed(
          error.observation.browserReceivedAtEpochMs,
          browserClickStartedAtEpochMs,
        )
        report.stage = 'recommendation-stream-error'
        report.recommendationProgressEvents = streamEvidence.progressEvents
        report.recommendationStreamProbeFailed = true
        report.recommendationFirstProgressMs = firstProgressVisible.observedAtEpochMs === null
          ? null
          : browserElapsed(firstProgressVisible.observedAtEpochMs, browserClickStartedAtEpochMs)
        report.recommendationSseTerminalCategory = 'ERROR'
        report.recommendationSseTerminalMs = streamElapsedMs
        report.recommendationSseErrorCode = error.observation.code
        report.recommendationSseFailureBoundary = error.observation.failureBoundary
        report.recommendationTerminalCategory = 'STREAM_ERROR'
        report.recommendationTerminalObserved = true
        report.recommendationElapsedMs = streamElapsedMs
        report.recommendationSloMet = false
        report.recommendationFailureBoundary = error.observation.failureBoundary
        await retainReport(reportFile, report)
      }
      throw error
    }
    observationAbort.abort()
    const { firstProgressVisible, terminal, sseResult, renderedSlate } = observations
    const modelAssignmentAfterRequest = await recommendationModelAssignment(page.request)
    report.recommendationModelAfterRequest = modelAssignmentAfterRequest
    report.recommendationModelProvider = modelAssignmentAfterRequest.provider
    report.recommendationModelName = modelAssignmentAfterRequest.model
    const streamEvidence = await readBrowserProgressEvidence(page, browserClickStartedAtEpochMs)
    report.recommendationProgressEvents = streamEvidence.progressEvents
    report.recommendationStreamProbeFailed = streamEvidence.probeFailed
    report.recommendationFirstProgressMs = firstProgressVisible.observedAtEpochMs === null
      ? null
      : browserElapsed(firstProgressVisible.observedAtEpochMs, browserClickStartedAtEpochMs)
    report.recommendationSseTerminalCategory = sseResult === null ? 'NOT_OBSERVED' : 'RESULT'
    report.recommendationSseTerminalMs = sseResult === null
      ? null
      : browserElapsed(sseResult.browserReceivedAtEpochMs, browserClickStartedAtEpochMs)
    report.recommendationSseResultMs = sseResult === null
      ? null
      : browserElapsed(sseResult.browserReceivedAtEpochMs, browserClickStartedAtEpochMs)
    report.recommendationSseFailureBoundary = publicFailureBoundary(sseResult?.failureBoundary)
    report.recommendationTerminalCategory = terminal.category
    report.recommendationTerminalObserved = terminal.session !== null
    report.recommendationPersistedTerminalMs = terminal.elapsedMs
    const renderedSlateElapsedMs = renderedSlate === null
      ? null
      : browserElapsed(renderedSlate.observedAtEpochMs, browserClickStartedAtEpochMs)
    const slate: SlateObservation = renderedSlate === null || renderedSlateElapsedMs === null
      ? { rendered: false, elapsedMs: TERMINAL_OBSERVATION_MS, bggIds: [] }
      : {
          rendered: true,
          elapsedMs: renderedSlateElapsedMs,
          bggIds: renderedSlate.bggIds,
        }
    report.recommendationRenderedSlateMs = slate.elapsedMs
    report.recommendationElapsedMs = slate.elapsedMs
    report.recommendationSloMet = slate.rendered && slate.elapsedMs <= INTERACTION_SLO_MS

    const result = terminal.session?.latestResponse ?? null
    report.recommendationOutcome = result?.outcome ?? null
    report.recommendationFailureBoundary = publicFailureBoundary(result?.failureBoundary)
      ?? publicFailureBoundary(sseResult?.failureBoundary)
    report.recommendationModelCalls = publicNonNegativeInteger(result?.modelCalls)
    report.recommendationModelCallElapsedMs = publicNonNegativeIntegers(result?.modelCallElapsedMs)
    report.recommendationAgentElapsedMs = publicNonNegativeInteger(result?.agentElapsedMs)
    const modelElapsedTotal = report.recommendationModelCallElapsedMs
      .reduce((total, value) => total + value, 0)
    report.recommendationModelElapsedShare = report.recommendationAgentElapsedMs !== null
      && report.recommendationAgentElapsedMs > 0
      ? Math.round(modelElapsedTotal / report.recommendationAgentElapsedMs * 1_000) / 1_000
      : null
    report.recommendationCatalogCalls = publicNonNegativeInteger(result?.catalogCalls)
    report.recommendationWebResearchCalls = publicNonNegativeInteger(result?.webResearchCalls)
    report.recommendationCompletedWork = Array.isArray(result?.completedWork)
      ? result.completedWork.filter((value): value is string => typeof value === 'string')
      : []
    report.recommendationPublishedBggIds = result?.games.map(({ game }) => game.bggId) ?? []
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
    report.recommendationProfileHardConstraintsMatched = result === null || terminal.session === null
      ? null
      : profileMatchesHardConstraints(
        result.profile,
        expectedPlayerCount,
        maximumDurationMinutes,
        maximumComplexity,
        expectedGameType,
      )
        && profileMatchesHardConstraints(
          terminal.session.profile,
          expectedPlayerCount,
          maximumDurationMinutes,
          maximumComplexity,
          expectedGameType,
        )
    report.recommendationCardsHardConstraintsMatched = result === null
      ? null
      : gamesMatchHardConstraints(
        result.games,
        expectedPlayerCount,
        maximumDurationMinutes,
        maximumComplexity,
      )
    report.recommendationFitClaimsHardConstraintsMatched = result === null
      ? null
      : gamesPublishSatisfiedHardFitClaims(result.games)
    report.recommendationComplexityHardConstraintsMatched = result === null
      || terminal.session === null
      ? null
      : result.profile.complexity?.strength === 'hard'
        && result.profile.complexity.maximum === maximumComplexity
        && terminal.session.profile.complexity?.strength === 'hard'
        && terminal.session.profile.complexity.maximum === maximumComplexity
        && result.games.every(({ game, fitClaims }) => isFiniteNumber(game.averageWeight)
          && game.averageWeight >= 0
          && game.averageWeight <= maximumComplexity
          && (fitClaims ?? []).some(claim => claim.subject === 'complexity'
            && claim.strength === 'hard'
            && claim.relation === 'satisfied'))
    report.recommendationGameTypeHardConstraintsMatched = result === null
      || terminal.session === null
      ? null
      : result.profile.type === expectedGameType
        && terminal.session.profile.type === expectedGameType
        && gamesMatchCatalogBggType(result.games, expectedGameType)
        && result.games.every(({ fitClaims }) => (fitClaims ?? []).some(claim =>
          claim.subject === 'bggType'
          && claim.strength === 'hard'
          && claim.relation === 'satisfied'))
    report.recommendationEvidenceBoundReplyParts = result === null
      ? null
      : gamesPublishEvidenceBoundReplyParts(result.games)
    const sseContent = sseResult?.content ?? null
    const persistedContent = result === null ? null : publishedContent(result)
    const sseVisibleContent = sseContent === null
      ? null
      : await playerVisibleProjection(page, sseContent)
    const persistedVisibleContent = persistedContent === null
      ? null
      : await playerVisibleProjection(page, persistedContent)
    report.recommendationSseContentDigest = sseVisibleContent === null
      ? null
      : contentDigest(sseVisibleContent)
    report.recommendationPersistedContentDigest = persistedVisibleContent === null
      ? null
      : contentDigest(persistedVisibleContent)
    report.recommendationSsePersistedContentConsistent = sseContent === null
      || persistedContent === null
      ? null
      : samePublishedContent(sseContent, persistedContent)
    if (slate.rendered) {
      const domContent = await renderedRecommendationContent(page)
      report.recommendationRenderedContentDigest = contentDigest(domContent)
      report.recommendationRenderedReplyCharacterCount =
        report.recommendationRenderedContentDigest.assistantMessageCharacterCount
      report.recommendationPersistedDomContentConsistent = persistedVisibleContent === null
        ? null
        : samePlayerVisibleContent(persistedVisibleContent, domContent)
    }
    await retainReport(reportFile, report)

    expect(modelAssignmentAfterRequest,
      'The effective recommendation model changed while the canary request was running')
      .toEqual(modelAssignment)
    expect(modelAssignmentAfterRequest.provider,
      'Production recommendation finished on a different effective provider than expected')
      .toBe(EXPECTED_MODEL_PROVIDER)
    expect(modelAssignmentAfterRequest.model,
      'Production recommendation finished on a different effective model than expected')
      .toBe(EXPECTED_MODEL_NAME)

    expect(firstProgressVisible.visible,
      'The recommendation did not expose causal player-visible progress within 3 seconds').toBe(true)
    expect(report.recommendationFirstProgressMs,
      'The browser did not capture the first server progress event').not.toBeNull()
    expect(report.recommendationFirstProgressMs).toBeLessThanOrEqual(FIRST_PROGRESS_SLO_MS)
    expect(report.recommendationStreamProbeFailed,
      'The browser could not preserve structured recommendation stream evidence').toBe(false)
    expect(sseResult, 'The browser did not observe the recommendation SSE result').not.toBeNull()
    expect(sseResult?.clientTurnId).toBe(clientTurnId)
    expect(report.recommendationSsePersistedContentConsistent,
      'The SSE result content did not exactly match the persisted assistant reply and card explanations')
      .toBe(true)
    expect(terminal.category, 'The recommendation did not reach a persisted recommendation terminal')
      .toBe('RECOMMENDATIONS')
    expect(result?.outcome).toBe('recommendations')
    expect(sseResult?.outcome).toBe(result?.outcome)
    expect(report.recommendationAssistantReplyCharacterCount,
      'The persisted recommendation reply is still only a terse status line').toBeGreaterThanOrEqual(80)
    expect(report.recommendationModelCalls,
      'A successful recommendation must include the catalog and terminal model turns')
      .toBeGreaterThanOrEqual(2)
    expect(report.recommendationModelCalls,
      'A successful repair must remain inside the Agent model-call budget')
      .toBeLessThanOrEqual(MAX_RECOMMENDATION_MODEL_CALLS)
    expect(report.recommendationModelCallElapsedMs,
      'Every recommendation model call must expose one non-negative elapsed time')
      .toHaveLength(report.recommendationModelCalls!)
    expect(report.recommendationModelCallElapsedMs.every(value => value >= 0)).toBe(true)
    expect(report.recommendationAgentElapsedMs,
      'The recommendation must expose the full server-side Agent elapsed time').not.toBeNull()
    expect(report.recommendationCatalogCalls,
      'The fixed fresh recommendation must verify the catalog exactly once').toBe(1)
    expect(report.recommendationWebResearchCalls,
      'The fixed fresh recommendation must not require optional web research').toBe(0)
    expect(report.recommendationPersistedCardCount,
      'The fixed fresh recommendation must publish exactly the requested card count')
      .toBe(requestedCardCount)
    expect(report.recommendationShortfallCount,
      'The fixed fresh recommendation must not silently accept a partial slate').toBe(0)
    expect(hasPositiveDistinctBggIds(result?.games ?? []),
      'Every persisted recommendation needs a positive, distinct BGG identity').toBe(true)
    expect(report.recommendationProfileHardConstraintsMatched,
      'The persisted typed profile lost an exact hard player-count, duration, complexity, or BGG type constraint')
      .toBe(true)
    expect(report.recommendationCardsHardConstraintsMatched,
      'At least one persisted card metadata record violates or cannot prove the hard numeric constraints')
      .toBe(true)
    expect(report.recommendationFitClaimsHardConstraintsMatched,
      'At least one card did not publish every required satisfied hard fit claim')
      .toBe(true)
    expect(report.recommendationComplexityHardConstraintsMatched,
      'The typed profile, metadata, or fit claims did not prove the explicit BGG complexity ceiling')
      .toBe(true)
    expect(report.recommendationGameTypeHardConstraintsMatched,
      'The typed profile, catalog ranking type, or hard fit claim did not prove the explicit BGG product class')
      .toBe(true)
    expect(report.recommendationEvidenceBoundReplyParts,
      'Every persisted card needs substantive typed reply parts with valid source identities when the claim requires them')
      .toBe(true)
    expect((result?.games ?? []).every(({ game }) => [game.name, game.originalName]
      .some(title => typeof title === 'string' && title.trim().length > 0)),
    'Every persisted recommendation needs a public title').toBe(true)
    expect(everyGameMatchesExpectedTitle(result?.games ?? []),
      `Every persisted recommendation must match expected title term: ${EXPECTED_TITLE_TERM}`)
      .toBe(true)

    report.stage = 'player-visible-slate'
    const persistedBggIds = result!.games.map(entry => entry.game.bggId)
    await retainReport(reportFile, report)
    expect(slate.rendered,
      'The recommendation did not render any real card DOM within the bounded observation window').toBe(true)
    expect(sameOrderedBggSlate(slate.bggIds, persistedBggIds),
      'The first player-visible card DOM was not the exact persisted ordered slate').toBe(true)
    expect(sseResult?.bggIds).toEqual(persistedBggIds)
    expect(report.recommendationPersistedDomContentConsistent,
      'The player-visible assistant reply or card explanations did not match the persisted publication')
      .toBe(true)
    expect(report.recommendationSloMet,
      'The fixed fresh recommendation did not render within the 20-second interaction SLO').toBe(true)
    await retainReport(reportFile, report)
    expect(report.recommendationRenderedReplyCharacterCount,
      'The player-visible recommendation was replaced by a terse summary').toBeGreaterThanOrEqual(80)

    const renderedAssistantReply = page.getByTestId('assistant-recommendation-turn').last()
      .getByTestId('assistant-recommendation-message')
    await expectUsablePlayerSurface(
      renderedAssistantReply,
      'The complete recommendation reply was hidden, clipped, or obstructed',
    )
    report.recommendationAssistantReplyUsable = true

    for (const [gameIndex, entry] of result!.games.entries()) {
      const renderedCard = page.locator(
        `[data-testid="recommendation-game-card"][data-bgg-id="${entry.game.bggId}"]`,
      )
      await expect(renderedCard).toHaveCount(1)
      await expectUsablePlayerSurface(
        renderedCard,
        `Recommendation card ${gameIndex + 1} was hidden, clipped, or obstructed`,
      )
      await expectUsablePlayerSurface(
        renderedCard.getByTestId('recommendation-game-title'),
        `Recommendation card ${gameIndex + 1} title was hidden, clipped, or obstructed`,
      )
      const originalTitle = renderedCard.getByTestId('recommendation-game-original-title')
      if (await originalTitle.count() > 0) {
        await expectUsablePlayerSurface(
          originalTitle,
          `Recommendation card ${gameIndex + 1} original title was hidden, clipped, or obstructed`,
        )
      }
      await expectUsablePlayerSurface(
        renderedCard.getByTestId('recommendation-game-quick-facts'),
        `Recommendation card ${gameIndex + 1} player-count and duration facts were hidden, clipped, or obstructed`,
      )
      report.recommendationUsableCardCount += 1

      const expectedReplyPartCount = entry.replyParts?.length ?? 0
      const renderedReplyParts = renderedCard.locator('dl > div > dd')
      await expect(renderedReplyParts).toHaveCount(expectedReplyPartCount)
      for (let partIndex = 0; partIndex < expectedReplyPartCount; partIndex += 1) {
        await expectUsablePlayerSurface(
          renderedReplyParts.nth(partIndex),
          `Recommendation card ${gameIndex + 1} explanation ${partIndex + 1} was hidden, clipped, or obstructed`,
        )
        report.recommendationUsableReplyPartCount += 1
      }
    }
    report.recommendationAllCardsUsable = report.recommendationUsableCardCount
      === persistedBggIds.length
    report.recommendationAllReplyPartsUsable = report.recommendationUsableReplyPartCount
      === report.recommendationCardReplyPartCount
    expect(report.recommendationAllCardsUsable,
      'Not every persisted recommendation card was genuinely player-visible').toBe(true)
    expect(report.recommendationAllReplyPartsUsable,
      'Not every persisted recommendation explanation was genuinely player-visible').toBe(true)
    expect(report.recommendationAssistantReplyUsable,
      'The complete persisted recommendation reply was not genuinely player-visible').toBe(true)
    await retainReport(reportFile, report)

    report.stage = 'recommendation-to-rulebook-handoff'
    const selectedBggId = persistedBggIds[0]
    expect(selectedBggId,
      'The persisted recommendation slate did not expose a first BGG identity for handoff')
      .toBeGreaterThan(0)
    expect(slate.bggIds[0],
      'The handoff must start from the first exact persisted and rendered BGG identity')
      .toBe(selectedBggId)
    report.handoffSelectedBggId = selectedBggId!
    await retainReport(reportFile, report)

    const handoffStartedAtEpochMs = Date.now()
    const selectedCard = page.locator(
      `[data-testid="recommendation-game-card"][data-bgg-id="${selectedBggId}"]`,
    )
    const journey = page.getByTestId('player-journey-surface')
    const importResponsePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return /^\/api\/v1\/bgg\/games\/\d+\/import$/.test(url.pathname)
        && response.request().method() === 'POST'
    }, { timeout: TERMINAL_OBSERVATION_MS })
    const existingImportsResponsePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname === '/api/v1/documents/official-imports'
        && response.request().method() === 'GET'
    }, { timeout: TERMINAL_OBSERVATION_MS })
    let resolveImportedEdition!: (editionId: string) => void
    const importedEditionPromise = new Promise<string>(resolve => {
      resolveImportedEdition = resolve
    })
    const existingRestorePromise = Promise.all([
      existingImportsResponsePromise,
      importedEditionPromise,
    ]).then(async ([response, editionId]) => {
      if (!response.ok()) return null
      const jobs = await response.json() as unknown
      if (!Array.isArray(jobs)) return null
      return jobs.map(job => restorableOfficialImport(job, editionId))
        .find((job): job is RestorableOfficialImportIdentity => job?.freshnessEligible === true) ?? null
    })
    let resolveDiscoveryResponse!: (response: Response) => void
    const observedDiscoveryResponse = new Promise<Response>(resolve => {
      resolveDiscoveryResponse = resolve
    })
    let resolveEnsureCurrentResponse!: (response: Response) => void
    const observedEnsureCurrentResponse = new Promise<Response>(resolve => {
      resolveEnsureCurrentResponse = resolve
    })
    const observeHandoffResponse = (response: Response) => {
      const url = new URL(response.url())
      if (url.pathname === '/api/v1/documents/rulebook-candidates'
        && response.request().method() === 'GET') {
        resolveDiscoveryResponse(response)
      }
      if (/^\/api\/v1\/documents\/official-imports\/[0-9a-f-]+\/teaching-ensure-current$/i
        .test(url.pathname) && response.request().method() === 'POST') {
        resolveEnsureCurrentResponse(response)
      }
    }
    page.on('response', observeHandoffResponse)
    const guardOfficialImportMutation = async (route: import('@playwright/test').Route) => {
      const request = route.request()
      const url = new URL(request.url())
      if (request.method() === 'GET') return await route.continue()
      report.handoffOfficialMutationAttemptedPaths.push(`${request.method()} ${url.pathname}`)
      if (url.pathname === '/api/v1/documents/official-imports'
        && request.method() === 'POST') report.handoffRulebookImportStarted = true
      const ensureMatch = url.pathname.match(
        /^\/api\/v1\/documents\/official-imports\/([0-9a-f-]+)\/teaching-ensure-current$/i,
      )
      const existing = ensureMatch === null ? null : await existingRestorePromise
      let requestBody: unknown = null
      try {
        requestBody = request.postDataJSON()
      } catch {
        // A malformed or non-JSON mutation body cannot cross the production freshness boundary.
      }
      if (ensureMatch !== null) {
        report.handoffFreshnessRequestPreparationRunMatched = existing?.teachingPreparationRunId !== null
          && existing?.teachingPreparationRunId !== undefined
          && isExactPreparationRunRequest(requestBody, existing.teachingPreparationRunId)
      }
      if (existing?.freshnessEligible
        && existing.teachingPreparationRunId !== null
        && ensureMatch?.[1]?.toLocaleLowerCase('en-US') === existing.id
        && isExactPreparationRunRequest(requestBody, existing.teachingPreparationRunId)) {
        // The synthetic player is allowed to invoke the production idempotent freshness endpoint for the exact
        // edition it already owns. Let the real backend response prove restoration; a route-local fake would hide
        // stale ownership, evidence, persistence, or 5xx failures.
        return await route.continue()
      }
      report.handoffOfficialMutationBlocked = true
      await route.abort('blockedbyclient')
    }
    await page.route('**/api/v1/documents/official-imports**', guardOfficialImportMutation)
    try {
      expect(report.recommendationElapsedMs,
        'Recommendation SLO evidence must be finalized before the handoff starts').not.toBeNull()
      expect(report.recommendationSloMet,
        'Recommendation SLO evidence must pass before the handoff starts').toBe(true)
      await expect(selectedCard).toHaveCount(1)
      await selectedCard.getByRole('button', { name: '选这款，找规则书', exact: true }).click()
      report.handoffActionClicked = true

      await expect(journey).toBeVisible({ timeout: 10_000 })
      const journeyTitle = journey.getByRole('heading', { level: 3 })
      await expect(journeyTitle).toHaveCount(1)
      await expectUsablePlayerSurface(
        journeyTitle,
        'The selected recommendation opened a hidden, clipped, or obstructed handoff surface',
      )
      report.handoffSurfaceVisible = true

      const importResponse = await importResponsePromise
      report.handoffImportElapsedMs = nodeElapsed(handoffStartedAtEpochMs)
      report.handoffImportResponseStatus = importResponse.status()
      report.handoffImportResponseOk = importResponse.ok()
      const importPathMatch = new URL(importResponse.url()).pathname.match(
        /^\/api\/v1\/bgg\/games\/(\d+)\/import$/,
      )
      report.handoffImportRequestedBggId = importPathMatch === null
        ? null
        : Number(importPathMatch[1])
      expect(report.handoffImportRequestedBggId,
        'The rulebook handoff bound a different BGG identity than the selected persisted card')
        .toBe(selectedBggId)
      expect(importResponse.ok(),
        'The selected production recommendation could not be bound to My Games').toBe(true)

      const importedIdentity = importedGameIdentity(await importResponse.json() as unknown)
      report.handoffImportResponseBggId = importedIdentity?.bggId ?? null
      report.handoffImportedGameId = importedIdentity?.game.id ?? null
      report.handoffImportedEditionId = importedIdentity?.edition.id ?? null
      report.handoffEditionBelongsToImportedGame = importedIdentity === null
        ? null
        : importedIdentity.edition.gameId === importedIdentity.game.id
      expect(importedIdentity,
        'The production game-binding response did not contain stable game and edition identities')
        .not.toBeNull()
      expect(report.handoffImportResponseBggId,
        'The production game-binding response returned a different BGG identity')
        .toBe(selectedBggId)
      expect(report.handoffEditionBelongsToImportedGame,
        'The production game-binding response returned an edition owned by another game')
        .toBe(true)

      resolveImportedEdition(importedIdentity!.edition.id)
      const existingRestore = await existingRestorePromise
      const discoveryResponse = existingRestore === null
        ? await Promise.race([
            observedDiscoveryResponse,
            sleep(TERMINAL_OBSERVATION_MS).then(() => {
              throw new Error('The exact-edition handoff neither restored existing work nor reached discovery')
            }),
          ])
        : null
      if (discoveryResponse !== null) {
        report.handoffDiscoveryElapsedMs = nodeElapsed(handoffStartedAtEpochMs)
        report.handoffDiscoveryResponseStatus = discoveryResponse.status()
        report.handoffDiscoveryResponseOk = discoveryResponse.ok()
        const discoveryUrl = new URL(discoveryResponse.url())
        report.handoffDiscoveryRequestedEditionId = publicUuid(
          discoveryUrl.searchParams.get('editionId'),
        )
        expect(report.handoffDiscoveryRequestedEditionId,
          'Rulebook discovery did not query the exact edition returned by the selected BGG binding')
          .toBe(importedIdentity!.edition.id)
      }

      let terminalFailure: string | null = null
      if (existingRestore !== null) {
        let restoredIdentity = existingRestore
        if (existingRestore.freshnessEligible) {
          const ensureCurrentResponse = await Promise.race([
            observedEnsureCurrentResponse,
            sleep(TERMINAL_OBSERVATION_MS).then(() => {
              throw new Error('The exact-edition freshness request did not return a production response')
            }),
          ])
          report.handoffFreshnessResponseStatus = ensureCurrentResponse.status()
          expect(report.handoffFreshnessResponseStatus,
            'Production freshness restoration must be explicitly accepted, not merely return a generic success')
            .toBe(202)
          const ensuredExisting = restorableOfficialImport(
            await ensureCurrentResponse.json() as unknown,
            importedIdentity!.edition.id,
          )
          expect(ensuredExisting,
            'The real freshness response did not retain the exact imported edition identity').not.toBeNull()
          report.handoffFreshnessResponseIdentityMatched = ensuredExisting !== null
            && ensuredExisting.id === existingRestore.id
            && ensuredExisting.documentVersionId === existingRestore.documentVersionId
            && ensuredExisting.teachingPreparationRunId === existingRestore.teachingPreparationRunId
          report.handoffFreshnessResponseEligible = ensuredExisting?.freshnessEligible ?? null
          expect(report.handoffFreshnessResponseIdentityMatched,
            'The real freshness response changed the existing job, document, or preparation run identity')
            .toBe(true)
          expect(report.handoffFreshnessResponseEligible,
            'The accepted freshness response did not prove a completed document and launched preparation run')
            .toBe(true)
          restoredIdentity = ensuredExisting!
        }
        report.handoffRestoredExistingJourney = true
        report.handoffRestoredImportJobId = restoredIdentity.id
        report.handoffRestoredDocumentVersionId = restoredIdentity.documentVersionId
        report.handoffRestoredPreparationRunId = restoredIdentity.teachingPreparationRunId
        report.handoffTerminalCategory = 'RESTORED_EXISTING'
        await expect(journey).toHaveAttribute('data-state', /^(journey|browser-required)$/)
        await expectUsablePlayerSurface(
          journey,
          'The exact-edition existing journey was restored into a hidden, clipped, or obstructed surface',
        )
      } else if (discoveryResponse!.ok()) {
        const discovery = rulebookDiscoveryIdentity(await discoveryResponse!.json() as unknown)
        report.handoffDiscoveryIdentityEditionId = discovery?.identity.editionId ?? null
        report.handoffDiscoveryIdentityMatched = discovery?.identity.editionId
          === importedIdentity!.edition.id
        report.handoffDiscoveryConfigured = discovery?.configured ?? null
        report.handoffDiscoveryCandidateCount = discovery?.candidates.length ?? null
        const importableCandidateCount = discovery?.candidates
          .filter(isImportableRulebookCandidate).length ?? null
        report.handoffImportableCandidateCount = importableCandidateCount
        report.handoffImportableCandidateFound = importableCandidateCount === null
          ? null
          : importableCandidateCount > 0
        expect(discovery,
          'The production candidate response did not expose a valid edition identity and candidate boundary')
          .not.toBeNull()
        expect(report.handoffDiscoveryIdentityMatched,
          'Rulebook discovery returned candidates for a different edition identity').toBe(true)
        if (!discovery!.configured && discovery!.candidates.length > 0) {
          terminalFailure = 'Production discovery returned candidates while reporting itself unavailable'
        }

        if (discovery!.configured && discovery!.candidates.length > 0) {
          const expectedCandidates = displayedRulebookCandidateOrder(discovery!.candidates)
          const expectedRenderedCandidates = expectedCandidates.map(candidate => ({
            url: candidate.url,
            capability: candidate.capability,
            acquisitionMode: candidate.acquisitionMode,
          }))
          report.handoffDiscoveryCandidateIdentitySha256 = sha256(
            JSON.stringify(expectedRenderedCandidates),
          )
          const candidateItems = journey.locator('[data-capability]')
          await expect(candidateItems).toHaveCount(expectedCandidates.length)
          const renderedCandidates = await candidateItems.evaluateAll(elements => elements.map(element => ({
            url: element.querySelector<HTMLAnchorElement>('a[href]')?.href ?? '',
            capability: element.getAttribute('data-capability') ?? '',
            acquisitionMode: element.getAttribute('data-acquisition-mode') ?? '',
          })))
          report.handoffRenderedCandidateIdentitySha256 = sha256(JSON.stringify(renderedCandidates))
          report.handoffCandidateIdentityOrderConsistent = JSON.stringify(renderedCandidates)
            === JSON.stringify(expectedRenderedCandidates)
          expect(report.handoffCandidateIdentityOrderConsistent,
            'The rendered rulebook source identities or order differed from the exact discovery response')
            .toBe(true)
          for (let index = 0; index < expectedCandidates.length; index += 1) {
            const candidateItem = candidateItems.nth(index)
            await expectUsablePlayerSurface(
              candidateItem,
              `Rulebook source candidate ${index + 1} was hidden, clipped, or obstructed`,
            )
            if (expectedCandidates[index]!.capability !== 'GAME_INFO_ONLY') {
              const candidateAction = candidateItem.getByRole('button')
              await expect(candidateAction).toHaveCount(1)
              await expect(candidateAction).toBeEnabled()
              await expectUsablePlayerSurface(
                candidateAction,
                `Rulebook source action ${index + 1} was hidden, clipped, or obstructed`,
              )
            }
          }

          const hasImportableCandidate = discovery!.candidates.some(isImportableRulebookCandidate)
          report.handoffTerminalCategory = hasImportableCandidate
            ? 'REVIEW'
            : 'NO_IMPORTABLE_SOURCE'
          if (!hasImportableCandidate) {
            terminalFailure = 'Production discovery returned candidates but no importable rulebook source'
          }
          const reviewHeading = journey.getByRole('heading', { level: 4 })
          await expect(reviewHeading).toHaveText(hasImportableCandidate
            ? '选择并核对来源'
            : '暂未找到可直接导入的规则书')
          await expectUsablePlayerSurface(
            reviewHeading,
            'The rulebook source review terminal was hidden, clipped, or obstructed',
          )
        } else {
          report.handoffTerminalCategory = 'UNAVAILABLE'
          const unavailableNotice = journey.getByText(
            '当前没有找到可审阅的规则书来源，自动查找已停下。你可以提供公开 PDF / 规则页链接或上传自己的规则书。',
            { exact: true },
          )
          await expect(unavailableNotice).toBeVisible()
          await expectUsablePlayerSurface(
            unavailableNotice,
            'The no-source discovery terminal was hidden, clipped, or obstructed',
          )
        }
      } else if (discoveryResponse!.status() === 401 || discoveryResponse.status() === 403) {
        report.handoffTerminalCategory = 'LOGIN_REQUIRED'
        terminalFailure = 'Production rulebook discovery lost the authenticated session after game binding'
        const loginNotice = journey.getByText(
          '登录后即可保留这次选择并继续找规则书。',
          { exact: true },
        )
        await expect(loginNotice).toBeVisible()
        await expectUsablePlayerSurface(
          loginNotice,
          'The handoff login terminal was hidden, clipped, or obstructed',
        )
      } else {
        report.handoffTerminalCategory = 'ERROR'
        terminalFailure = `Production rulebook discovery failed with HTTP ${discoveryResponse!.status()}`
        const errorNotice = journey.getByRole('alert')
        await expect(errorNotice).toContainText(
          '这一步暂时没有完成；推荐对话和已选桌游不会受影响。',
        )
        await expect(journey.getByRole('button', { name: '重试当前步骤', exact: true }))
          .toBeVisible()
        await expectUsablePlayerSurface(
          errorNotice,
          'The discovery error terminal was hidden, clipped, or obstructed',
        )
      }

      report.handoffTerminalVisible = true
      report.handoffElapsedMs = nodeElapsed(handoffStartedAtEpochMs)
      await page.waitForTimeout(500)
      expect(report.handoffRulebookImportStarted,
        'The canary must never confirm or import new candidate rulebook material').toBe(false)
      expect(report.handoffOfficialMutationAttemptedPaths.every(path =>
        /\/teaching-ensure-current$/.test(path)),
      'The canary attempted an unexpected production official-import mutation').toBe(true)
      expect(existingRestore?.freshnessEligible
        ? report.handoffOfficialMutationAttemptedPaths.length === 1
          && !report.handoffOfficialMutationBlocked
        : report.handoffOfficialMutationAttemptedPaths.length === 0,
      'The canary did not preserve the exact production mutation boundary for this handoff branch').toBe(true)
      report.handoffStoppedAtDiscoveryBoundary = existingRestore === null
      await retainReport(reportFile, report)
      if (terminalFailure !== null) throw new Error(terminalFailure)
    } finally {
      page.off('response', observeHandoffResponse)
      await page.unroute('**/api/v1/documents/official-imports**', guardOfficialImportMutation)
    }

    await expect(page).toHaveURL(/\/discover$/)
    const publicReleaseAfter = await publicReleaseIdentity(page.request)
    expect(publicReleaseAfter,
      'The public API release identity changed during the recommendation and handoff journey')
      .toEqual(publicReleaseBefore)
    report.routeStayedOnDiscover = true
    report.completed = true
    report.stage = 'completed'
  } finally {
    await retainReport(reportFile, report)
  }
})
