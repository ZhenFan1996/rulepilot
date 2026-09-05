import { createHash } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import { createServer } from 'node:http'

import {
  expect,
  test,
  type APIRequestContext,
  type Page,
  type Response as PlaywrightResponse,
  type Route,
} from '@playwright/test'
import MarkdownIt from 'markdown-it'

import {
  classifyRecommendationStreamError,
  RECOMMENDATION_STREAM_ERROR_CODES,
  type RecommendationCanaryFailureClass,
} from '../src/lib/recommendationCanaryDiagnostics'

const enabled = process.env.RULEPILOT_PRODUCTION_RECOMMENDATION_JOURNEY === 'true'
const NATURAL_PROMPT = '你好'
const TURN_OBSERVATION_MS = 155_000
const HANDOFF_OBSERVATION_MS = 60_000
const ALLOWED_GAME_TYPES = new Set([
  'abstract', 'customizable', 'children', 'family', 'party',
  'strategy', 'thematic', 'war', 'expansion',
])
const FAILURE_BOUNDARIES = new Set([
  'time_budget', 'model_response', 'service_configuration', 'action_budget',
  'publication_boundary', 'service_failure',
])
const FAILURE_REASONS = new Set([
  'time_limit', 'model_not_configured', 'resource_budget_exhausted',
  'provider_call_failed', 'provider_protocol_invalid', 'provider_output_truncated',
  'empty_model_response', 'repeated_incompatible_actions', 'repeated_invalid_action',
  'publication_rejected', 'service_failure',
])
const STREAM_ERROR_CODES = new Set<string>(RECOMMENDATION_STREAM_ERROR_CODES)
const MARKDOWN = new MarkdownIt({ breaks: true, html: false, linkify: true, typographer: false })
MARKDOWN.renderer.rules.image = (tokens, index) =>
  MARKDOWN.utils.escapeHtml(tokens[index]?.content ?? '')

type RecommendationOutcome = 'conversation' | 'needs_clarification' | 'recommendations' | 'no_match' | 'unavailable'

type Constraint = {
  minimum: number | null, maximum: number | null, strength: 'hard' | 'soft'
}

interface RecommendationResult {
  conversationId?: string | null, revision?: number | null
  clientTurnId: string, outcome: RecommendationOutcome, assistantMessage: string
  profile: {
    type: string, interaction: string
    playerCount: Constraint | null, durationMinutes: Constraint | null, complexity: Constraint | null
  }
  shortfall?: { requestedCount: number, availableCount: number } | null
  completedWork?: string[], catalogCalls?: number, webResearchCalls?: number
  modelCallElapsedMs?: number[], agentElapsedMs?: number
  failureBoundary?: string | null, failureReason?: string | null
  games: RecommendationGame[]
  researchSources?: Array<{ title: string, url: string }>
}

interface RecommendationGame {
  game: {
    bggId: number, name: string, originalName: string, bggUrl: string, bggTypes: string[]
    minPlayers: number | null, maxPlayers: number | null, playingTimeMinutes: number | null
    minimumPlayTimeMinutes?: number | null, maximumPlayTimeMinutes?: number | null
    averageWeight: number | null
  }
}

interface RecommendationSession {
  conversationId: string, revision: number, processing: boolean
  latestResponse: RecommendationResult | null
  lastTurnResult: { clientTurnId: string, outcome: RecommendationOutcome } | null
}

type FailureEvidence = {
  classification: RecommendationCanaryFailureClass, boundary: string | null
  reason: string | null, code: string | null
}

type HandoffTerminal =
  'NOT_OBSERVED' | 'SOURCE_REVIEW' | 'SOURCE_UNAVAILABLE'
  | 'RULEBOOK_READABLE' | 'LESSON_READABLE' | 'EXPLICIT_FAILURE'

interface ProductionReport {
  reportSchemaVersion: 3, generatedAt: string, completed: boolean, stage: string
  failedStage: string | null, fatalFailure: FailureEvidence | null
  rawModelOutputCaptured: false
  deployment: {
    testedSha: string, activeReleaseId: string
    before: ReleaseIdentity | null, after: ReleaseIdentity | null
    exactAndStable: boolean | null
  }
  model: {
    expected: ModelIdentity, before: ModelIdentity | null, after: ModelIdentity | null
    stable: boolean | null
  }
  naturalReply: {
    promptSha256: string, requestMatched: boolean, outcome: RecommendationOutcome | null
    assistantMessageSha256: string | null
    noExternalWork: boolean | null, persistedMatched: boolean | null, domMatched: boolean | null
    agentElapsedMs: number | null, modelCallElapsedMs: number[]
    firstAnswerPartMs: number | null, firstRecommendationPartMs: number | null, terminalMs: number | null
    failure: FailureEvidence | null
  }
  recommendation: {
    promptSha256: string, requestedCardCount: number | null, expectedPlayerCount: number | null
    maximumDurationMinutes: number | null, maximumComplexity: number | null
    expectedGameType: string | null, requestMatched: boolean, outcome: RecommendationOutcome | null
    assistantMessageSha256: string | null
    cards: Array<{
      bggId: number, nameSha256: string, originalNameSha256: string
    }>
    shortfallCount: number | null, publicationErrors: string[]
    persistedMatched: boolean | null, domMatched: boolean | null
    agentElapsedMs: number | null, modelCallElapsedMs: number[]
    firstAnswerPartMs: number | null, firstRecommendationPartMs: number | null, terminalMs: number | null
    failure: FailureEvidence | null
  }
  handoff: {
    selectedBggId: number | null, actionClicked: boolean, importResponseStatus: number | null
    importedBggId: number | null, importedGameId: string | null, importedEditionId: string | null
    editionBelongsToGame: boolean | null, existingJobId: string | null
    discoveryEditionMatched: boolean | null, sourceCount: number | null
    terminal: HandoffTerminal, surfaceState: string | null
    canReadRulebook: boolean, canReadLesson: boolean, failureClassification: string | null
    blockedMutationPaths: string[]
  }
}

type ReleaseIdentity = { releaseId: string, commitSha: string, noStore: boolean }
type ModelIdentity = { provider: string, model: string }

type StreamTerminal =
  | { kind: 'result', result: RecommendationResult }
  | { kind: 'error', code: string, boundary: string | null, reason: string | null }

// Browser fetch initiation to useful SSE delivery; absence stays null and is not a completion gate.
interface StreamTimings {
  firstAnswerPartMs: number | null
  firstRecommendationPartMs: number | null
  terminalMs: number | null
}

interface StreamEvidenceState extends StreamTimings {
  generation: number
  terminalBlock: string | null
  observerError: string | null
}

type BrowserStreamObservation = StreamTimings & (
  | { kind: 'terminal', block: string }
  | { kind: 'observer_error', code: string })

function sha256(value: string) {
  return createHash('sha256').update(value, 'utf8').digest('hex')
}

function normalized(value: string) {
  return value.replace(/\s+/gu, ' ').trim()
}

function safeEnum(value: unknown, allowed: ReadonlySet<string>) {
  return typeof value === 'string' && allowed.has(value) ? value : null
}

function positiveInteger(value: string | undefined, name: string) {
  if (!value || !/^\d+$/.test(value)) throw new Error(`Invalid ${name}`)
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed) || parsed < 1) throw new Error(`Invalid ${name}`)
  return parsed
}

function positiveDecimal(value: string | undefined, name: string) {
  if (!value || !/^(?:0|[1-9]\d*)(?:\.\d+)?$/.test(value)) throw new Error(`Invalid ${name}`)
  const parsed = Number(value)
  if (!Number.isFinite(parsed) || parsed <= 0 || parsed > 5) throw new Error(`Invalid ${name}`)
  return parsed
}

function publicUuid(value: unknown) {
  if (typeof value !== 'string') return null
  const candidate = value.trim().toLocaleLowerCase('en-US')
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(candidate)
    ? candidate
    : null
}

function initialReport(): ProductionReport {
  const testedSha = process.env.RULEPILOT_RECOMMENDATION_TESTED_SHA ?? ''
  const activeReleaseId = process.env.RULEPILOT_RECOMMENDATION_ACTIVE_RELEASE_ID ?? ''
  const selectionPrompt = process.env.RULEPILOT_RECOMMENDATION_SELECTION_PROMPT ?? ''
  return {
    reportSchemaVersion: 3,
    generatedAt: new Date().toISOString(),
    completed: false,
    stage: 'validation',
    failedStage: null,
    fatalFailure: null,
    rawModelOutputCaptured: false,
    deployment: { testedSha, activeReleaseId, before: null, after: null, exactAndStable: null },
    model: {
      expected: {
        provider: (process.env.RULEPILOT_RECOMMENDATION_EXPECTED_PROVIDER ?? '').trim(),
        model: (process.env.RULEPILOT_RECOMMENDATION_EXPECTED_MODEL ?? '').trim(),
      },
      before: null,
      after: null,
      stable: null,
    },
    naturalReply: {
      promptSha256: sha256(NATURAL_PROMPT), requestMatched: false, outcome: null,
      assistantMessageSha256: null, noExternalWork: null, persistedMatched: null,
      domMatched: null, agentElapsedMs: null, modelCallElapsedMs: [], failure: null,
      firstAnswerPartMs: null, firstRecommendationPartMs: null, terminalMs: null,
    },
    recommendation: {
      promptSha256: sha256(selectionPrompt), requestedCardCount: null, expectedPlayerCount: null,
      maximumDurationMinutes: null, maximumComplexity: null, expectedGameType: null,
      requestMatched: false, outcome: null, assistantMessageSha256: null, cards: [],
      shortfallCount: null, publicationErrors: [], persistedMatched: null, domMatched: null,
      agentElapsedMs: null, modelCallElapsedMs: [], failure: null,
      firstAnswerPartMs: null, firstRecommendationPartMs: null, terminalMs: null,
    },
    handoff: {
      selectedBggId: null, actionClicked: false, importResponseStatus: null,
      importedBggId: null, importedGameId: null, importedEditionId: null,
      editionBelongsToGame: null, existingJobId: null, discoveryEditionMatched: null,
      sourceCount: null, terminal: 'NOT_OBSERVED', surfaceState: null,
      canReadRulebook: false, canReadLesson: false, failureClassification: null,
      blockedMutationPaths: [],
    },
  }
}
async function retainReport(path: string, report: ProductionReport) {
  report.generatedAt = new Date().toISOString()
  await writeFile(path, `${JSON.stringify(report, null, 2)}\n`, { mode: 0o600 })
}
async function publicRelease(request: APIRequestContext) {
  const response = await request.get('/api/public/release')
  if (!response.ok()) throw new Error('Public release identity is unavailable')
  const value = await response.json() as { releaseId?: unknown, commitSha?: unknown }
  const noStore = (response.headers()['cache-control'] ?? '')
    .split(',').some(entry => entry.trim().toLocaleLowerCase('en-US') === 'no-store')
  if (typeof value.releaseId !== 'string'
    || typeof value.commitSha !== 'string'
    || !/^[0-9a-f]{40}-[0-9]+(?:-[0-9]+)?$/.test(value.releaseId)
    || !/^[0-9a-f]{40}$/.test(value.commitSha)
    || !noStore) throw new Error('Public release identity is invalid')
  return { releaseId: value.releaseId, commitSha: value.commitSha, noStore }
}
async function modelAssignment(request: APIRequestContext) {
  const response = await request.get('/api/v1/model-configuration')
  if (!response.ok()) throw new Error('Recommendation model assignment is unavailable')
  const value = await response.json() as {
    recommendationModel?: { provider?: unknown, model?: unknown }
  }
  const provider = value.recommendationModel?.provider
  const model = value.recommendationModel?.model
  const safe = /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/
  if (typeof provider !== 'string' || typeof model !== 'string'
    || !safe.test(provider) || !safe.test(model)) throw new Error('Invalid model assignment')
  return { provider, model }
}
async function login(page: Page, username: string, password: string) {
  await page.addInitScript(() => localStorage.setItem('rulepilot:locale', 'zh-CN'))
  await page.goto('/login')
  await page.locator('input[name="username"]').fill(username)
  await page.locator('input[name="password"]').fill(password)
  await page.locator('form button[type="submit"]').click()
  await expect(page).toHaveURL(new URL('/', page.url()).toString())
}
function parseStream(text: string): StreamTerminal {
  let terminal: StreamTerminal | null = null
  for (const block of text.replaceAll('\r\n', '\n').split('\n\n')) {
    let event = 'message'
    const data: string[] = []
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
    }
    if (data.length === 0 || (event !== 'result' && event !== 'error')) continue
    const value = JSON.parse(data.join('\n')) as Record<string, unknown>
    terminal = event === 'result'
      ? { kind: 'result', result: value as unknown as RecommendationResult }
      : {
          kind: 'error',
          code: typeof value.code === 'string' ? value.code : 'invalid_stream_error',
          boundary: safeEnum(value.failureBoundary, FAILURE_BOUNDARIES),
          reason: safeEnum(value.failureReason, FAILURE_REASONS),
        }
  }
  return terminal ?? { kind: 'error', code: 'unknown_stream_error', boundary: null, reason: null }
}
async function installRecommendationStreamObserver(page: Page) {
  await page.addInitScript(({ maximumEventCharacters, streamPath }) => {
    const observedWindow = window as typeof window & {
      __rulepilotRecommendationStreamEvidence?: StreamEvidenceState
    }
    if (observedWindow.__rulepilotRecommendationStreamEvidence) return

    const state: StreamEvidenceState = {
      generation: 0,
      terminalBlock: null,
      observerError: null,
      firstAnswerPartMs: null, firstRecommendationPartMs: null, terminalMs: null,
    }
    observedWindow.__rulepilotRecommendationStreamEvidence = state
    const nativeFetch: typeof window.fetch = window.fetch.bind(window)

    const belongsToGeneration = (generation: number) => generation === state.generation
    const fail = (generation: number, code: string) => {
      if (belongsToGeneration(generation) && state.terminalBlock === null) {
        state.observerError = code
      }
    }
    const consumeBlock = (block: string, generation: number, startedAt: number) => {
      if (!belongsToGeneration(generation)) return false
      let event = 'message'
      const data: string[] = []
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
      }
      if (!data.length) return false
      const elapsedMs = Math.round(performance.now() - startedAt)
      if (event === 'result' || event === 'error') {
        state.terminalBlock = block
        state.terminalMs = elapsedMs
        return true
      }
      try {
        if (event === 'answer_part' && state.firstAnswerPartMs === null) {
          const value = JSON.parse(data.join('\n')) as { text?: unknown }
          if (typeof value.text === 'string' && value.text.trim()) state.firstAnswerPartMs = elapsedMs
        } else if (event === 'recommendation_part' && state.firstRecommendationPartMs === null) {
          const value = JSON.parse(data.join('\n')) as { game?: { game?: { bggId?: unknown } } }
          const id = value.game?.game?.bggId
          if (Number.isSafeInteger(id) && Number(id) > 0) state.firstRecommendationPartMs = elapsedMs
        }
      } catch {
        // A malformed intermediate event cannot manufacture useful-output timing or hide the terminal result.
      }
      return false
    }
    const observe = async (response: Response, generation: number, startedAt: number) => {
      if (!response.body) {
        fail(generation, 'observer_stream_body_missing')
        return
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (true) {
        const chunk = await reader.read()
        buffer = `${buffer}${decoder.decode(chunk.value, { stream: !chunk.done })}`
          .replaceAll('\r\n', '\n')
        let boundary = buffer.indexOf('\n\n')
        while (boundary >= 0) {
          const block = buffer.slice(0, boundary)
          buffer = buffer.slice(boundary + 2)
          if (consumeBlock(block, generation, startedAt)) {
            void reader.cancel()
            return
          }
          boundary = buffer.indexOf('\n\n')
        }
        if (buffer.length > maximumEventCharacters) {
          void reader.cancel()
          fail(generation, 'observer_stream_event_too_large')
          return
        }
        if (chunk.done) break
      }
      if (buffer.trim() && consumeBlock(buffer, generation, startedAt)) return
      fail(generation, 'observer_stream_ended_without_terminal')
    }

    observedWindow.fetch = async (...args: Parameters<typeof window.fetch>) => {
      const generation = state.generation
      const [input, init] = args
      const requestUrl = input instanceof Request ? input.url : input.toString()
      const requestMethod = (init?.method ?? (input instanceof Request ? input.method : 'GET'))
        .toUpperCase()
      let observesRecommendationStream = false
      try {
        observesRecommendationStream = requestMethod === 'POST'
          && new URL(requestUrl, window.location.href).pathname === streamPath
      } catch {
        // An unrelated malformed request remains the application's responsibility.
      }

      const startedAt = performance.now()
      const response = await nativeFetch(...args)
      if (observesRecommendationStream && response.ok) {
        try {
          void observe(response.clone(), generation, startedAt)
            .catch(() => fail(generation, 'observer_stream_read_failed'))
        } catch {
          fail(generation, 'observer_stream_clone_failed')
        }
      }
      return response
    }
  }, {
    maximumEventCharacters: 1_048_576,
    streamPath: '/api/v1/bgg/recommendation-agent/stream',
  })
}
async function resetRecommendationStreamObserver(page: Page) {
  await page.evaluate(() => {
    const observedWindow = window as typeof window & {
      __rulepilotRecommendationStreamEvidence?: StreamEvidenceState
    }
    const state = observedWindow.__rulepilotRecommendationStreamEvidence
    if (!state) throw new Error('Recommendation stream observer is unavailable')
    state.generation += 1
    state.terminalBlock = null
    state.observerError = null
    state.firstAnswerPartMs = null
    state.firstRecommendationPartMs = null
    state.terminalMs = null
  })
}
async function waitForRecommendationStreamObservation(page: Page) {
  const result = await page.waitForFunction(() => {
    const observedWindow = window as typeof window & {
      __rulepilotRecommendationStreamEvidence?: StreamEvidenceState
    }
    const state = observedWindow.__rulepilotRecommendationStreamEvidence
    if (!state) return null
    const timings = {
      firstAnswerPartMs: state.firstAnswerPartMs,
      firstRecommendationPartMs: state.firstRecommendationPartMs,
      terminalMs: state.terminalMs,
    }
    if (state.terminalBlock !== null) return { kind: 'terminal', block: state.terminalBlock, ...timings }
    if (state.observerError) return { kind: 'observer_error', code: state.observerError, ...timings }
    return null
  }, undefined, { polling: 50, timeout: TURN_OBSERVATION_MS })
  let observation: BrowserStreamObservation
  try {
    observation = await result.jsonValue() as BrowserStreamObservation
  } finally {
    await result.dispose()
  }
  await resetRecommendationStreamObserver(page)
  return observation
}
async function submitTurn(page: Page, prompt: string) {
  await resetRecommendationStreamObserver(page)
  const responsePromise = page.waitForResponse(response => {
    const url = new URL(response.url())
    return url.pathname === '/api/v1/bgg/recommendation-agent/stream'
      && response.request().method() === 'POST'
  }, { timeout: TURN_OBSERVATION_MS })
  const composer = page.getByLabel('聊聊你想玩的游戏')
  await composer.fill(prompt)
  const send = page.getByRole('button', { name: '发送', exact: true })
  await expect(send).toBeEnabled()
  await send.click()
  const response = await responsePromise
  const body = response.request().postDataJSON() as { message?: unknown } | null
  if (!response.ok()) {
    return {
      messageMatched: body?.message === prompt,
      terminal: { kind: 'error', code: `http_${response.status()}`, boundary: null, reason: null } as StreamTerminal,
      timings: { firstAnswerPartMs: null, firstRecommendationPartMs: null, terminalMs: null },
    }
  }
  const observation = await waitForRecommendationStreamObservation(page)
  return {
    messageMatched: body?.message === prompt,
    timings: {
      firstAnswerPartMs: observation.firstAnswerPartMs,
      firstRecommendationPartMs: observation.firstRecommendationPartMs,
      terminalMs: observation.terminalMs,
    },
    terminal: observation.kind === 'terminal'
      ? parseStream(observation.block)
      : { kind: 'error', code: observation.code, boundary: null, reason: null } as StreamTerminal,
  }
}
function streamFailure(terminal: Extract<StreamTerminal, { kind: 'error' }>): FailureEvidence {
  return {
    classification: STREAM_ERROR_CODES.has(terminal.code)
      ? classifyRecommendationStreamError(terminal.code)
      : 'observer_failure',
    boundary: terminal.boundary,
    reason: terminal.reason,
    code: terminal.code,
  }
}
function resultFailure(result: RecommendationResult): FailureEvidence {
  return {
    classification: 'product_terminal',
    boundary: safeEnum(result.failureBoundary, FAILURE_BOUNDARIES),
    reason: safeEnum(result.failureReason, FAILURE_REASONS),
    code: `outcome_${result.outcome}`,
  }
}
async function persistedSession(request: APIRequestContext, conversationId: string) {
  const response = await request.get(
    `/api/v1/bgg/recommendation-agent/sessions/${encodeURIComponent(conversationId)}`,
  )
  if (!response.ok()) throw new Error('Persisted recommendation session is unavailable')
  return await response.json() as RecommendationSession
}
function published(result: RecommendationResult) {
  return {
    assistantMessage: result.assistantMessage,
    games: result.games.map(entry => ({
      bggId: entry.game.bggId,
      name: entry.game.name,
      originalName: entry.game.originalName,
    })),
  }
}
function persistedResultMatches(result: RecommendationResult, session: RecommendationSession) {
  return !session.processing
    && session.lastTurnResult?.clientTurnId === result.clientTurnId
    && session.lastTurnResult.outcome === result.outcome
    && session.latestResponse !== null
    && JSON.stringify(published(session.latestResponse)) === JSON.stringify(published(result))
}
async function markdownText(page: Page, source: string) {
  const html = MARKDOWN.render(source)
  return await page.evaluate(rendered => {
    const element = document.createElement('div')
    element.style.position = 'fixed'
    element.style.left = '-100000px'
    element.innerHTML = rendered
    document.body.append(element)
    try {
      return element.innerText
    } finally {
      element.remove()
    }
  }, html)
}
async function renderedRecommendation(page: Page) {
  return await page.getByTestId('assistant-recommendation-turn').last().evaluate(turn => ({
    assistantMessage: turn.querySelector<HTMLElement>(
      '[data-testid="assistant-recommendation-message"]',
    )?.innerText ?? '',
    games: [...turn.querySelectorAll<HTMLElement>('[data-testid="recommendation-game-card"]')]
      .map(card => ({
        bggId: Number(card.dataset.bggId),
        name: card.dataset.gameName ?? '',
        originalName: card.dataset.originalName ?? '',
      })),
  }))
}
async function recommendationDomMatches(page: Page, result: RecommendationResult) {
  const expected = published(result)
  expected.assistantMessage = await markdownText(page, expected.assistantMessage)
  const rendered = await renderedRecommendation(page)
  const normalizePublication = (value: typeof expected) => ({
    assistantMessage: normalized(value.assistantMessage),
    games: value.games.map(game => ({
      ...game,
      name: normalized(game.name),
      originalName: normalized(game.originalName),
    })),
  })
  return JSON.stringify(normalizePublication(rendered)) === JSON.stringify(normalizePublication(expected))
}
async function recommendationSourcesMatch(page: Page, result: RecommendationResult) {
  const expected = [
    ...result.games.map(({ game }) => ({ title: `${game.name} · BGG`, url: game.bggUrl })),
    ...(result.researchSources ?? []).map(({ title, url }) => ({ title, url })),
  ]
  if (expected.length === 0) return true
  const details = page.getByTestId('assistant-recommendation-turn').last()
    .getByTestId('recommendation-verification-details')
  await expect(details).not.toHaveAttribute('open')
  await details.locator('summary').click()
  const sources = details.getByTestId('recommendation-research-sources')
  await expect(sources).toBeVisible()
  const rendered = await sources.locator('a').evaluateAll(links => links.map(link => ({
    title: link.textContent?.replace(/ ↗$/u, '') ?? '',
    url: link.getAttribute('href') ?? '',
  })))
  await details.locator('summary').click()
  return JSON.stringify(rendered) === JSON.stringify(expected)
}
function recommendationErrors(result: RecommendationResult, expected: {
  requestedCardCount: number
  playerCount: number
  maximumDuration: number
  maximumComplexity: number
  gameType: string
  titleTerm: string
}) {
  const errors: string[] = []
  const profile = result.profile
  if (result.outcome !== 'recommendations') errors.push('outcome')
  if (!result.assistantMessage.trim()) errors.push('assistant-message')
  if (result.games.length < 1 || result.games.length > expected.requestedCardCount) errors.push('card-count')
  if (profile.type !== expected.gameType
    || profile.playerCount?.strength !== 'hard'
    || profile.playerCount.minimum !== expected.playerCount
    || profile.playerCount.maximum !== expected.playerCount
    || profile.durationMinutes?.strength !== 'hard'
    || profile.durationMinutes.maximum !== expected.maximumDuration
    || profile.complexity?.strength !== 'hard'
    || profile.complexity.maximum !== expected.maximumComplexity) errors.push('typed-profile')
  const ids = result.games.map(entry => entry.game.bggId)
  if (ids.some(id => !Number.isSafeInteger(id) || id < 1) || new Set(ids).size !== ids.length) {
    errors.push('game-identity')
  }
  for (const entry of result.games) {
    const game = entry.game
    const duration = game.maximumPlayTimeMinutes ?? game.playingTimeMinutes
    if (!game.name.trim() || !game.originalName.trim()) errors.push('public-title')
    if (game.minPlayers === null || game.maxPlayers === null
      || game.minPlayers > expected.playerCount || game.maxPlayers < expected.playerCount
      || duration === null || duration > expected.maximumDuration
      || game.averageWeight === null || game.averageWeight > expected.maximumComplexity
      || !game.bggTypes.includes(expected.gameType)) errors.push(`hard-facts:${game.bggId}`)
    if (expected.titleTerm && ![game.name, game.originalName].some(title =>
      title.normalize('NFKC').toLocaleLowerCase('en-US').includes(expected.titleTerm))) {
      errors.push(`title-term:${game.bggId}`)
    }
  }
  const shortfall = expected.requestedCardCount - result.games.length
  if (shortfall > 0 && (result.shortfall?.requestedCount !== expected.requestedCardCount
    || result.shortfall.availableCount !== result.games.length)) errors.push('shortfall')
  return [...new Set(errors)]
}
function importedIdentity(value: unknown) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const imported = value as Record<string, unknown>
  const game = imported.game as Record<string, unknown> | undefined
  const edition = imported.edition as Record<string, unknown> | undefined
  const bggId = Number.isSafeInteger(imported.bggId) && Number(imported.bggId) > 0
    ? Number(imported.bggId)
    : null
  const gameId = publicUuid(game?.id)
  const editionId = publicUuid(edition?.id)
  const editionGameId = publicUuid(edition?.gameId)
  return bggId && gameId && editionId && editionGameId
    ? { bggId, gameId, editionId, editionGameId }
    : null
}
async function handoffTerminal(page: Page) {
  const handle = await page.waitForFunction(() => {
    const surface = document.querySelector<HTMLElement>('[data-testid="player-journey-surface"]')
    if (!surface) return null
    const state = surface.dataset.state ?? ''
    const visibleButton = (label: string) => [...surface.querySelectorAll<HTMLButtonElement>('button')]
      .some(button => button.textContent?.trim() === label
        && getComputedStyle(button).display !== 'none'
        && getComputedStyle(button).visibility === 'visible')
    const failure = surface.querySelector<HTMLElement>(
      '[data-testid="recommendation-journey-terminal-alert"]',
    )?.dataset.failureClassification ?? null
    if (state === 'review') return { terminal: 'SOURCE_REVIEW', state, failure, rulebook: false, lesson: false }
    if (state === 'unavailable') return { terminal: 'SOURCE_UNAVAILABLE', state, failure, rulebook: false, lesson: false }
    if (state === 'error' || state === 'login' || state === 'browser-required') {
      return { terminal: 'EXPLICIT_FAILURE', state, failure: failure ?? state, rulebook: false, lesson: false }
    }
    if (state !== 'journey') return null
    const lesson = visibleButton('打开已生成的讲解')
    const rulebook = visibleButton('先阅读原规则书')
    if (lesson) return { terminal: 'LESSON_READABLE', state, failure, rulebook: true, lesson: true }
    if (rulebook) return { terminal: 'RULEBOOK_READABLE', state, failure, rulebook: true, lesson: false }
    if (failure) return { terminal: 'EXPLICIT_FAILURE', state, failure, rulebook: false, lesson: false }
    return null
  }, undefined, { timeout: HANDOFF_OBSERVATION_MS, polling: 250 })
  try {
    return await handle.jsonValue() as {
      terminal: HandoffTerminal
      state: string
      failure: string | null
      rulebook: boolean
      lesson: boolean
    }
  } finally {
    await handle.dispose()
  }
}
async function observeHandoff(page: Page, report: ProductionReport, selectedBggId: number) {
  const guard = async (route: Route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'GET'
      || /^\/api\/v1\/documents\/official-imports\/[0-9a-f-]+\/teaching-ensure-current$/i.test(path)) {
      return await route.continue()
    }
    report.handoff.blockedMutationPaths.push(`${request.method()} ${path}`)
    await route.abort('blockedbyclient')
  }
  await page.route('**/api/v1/documents/official-imports**', guard)
  const importResponsePromise = page.waitForResponse(response =>
    new URL(response.url()).pathname === `/api/v1/bgg/games/${selectedBggId}/import`
      && response.request().method() === 'POST', { timeout: HANDOFF_OBSERVATION_MS })
  const jobsResponsePromise = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/documents/official-imports'
      && response.request().method() === 'GET', { timeout: HANDOFF_OBSERVATION_MS })
  const discoveryResponsePromise: Promise<PlaywrightResponse | null> = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/documents/rulebook-candidates'
      && response.request().method() === 'GET', { timeout: HANDOFF_OBSERVATION_MS }).catch(() => null)
  const ensureResponsePromise: Promise<PlaywrightResponse | null> = page.waitForResponse(response =>
    /\/teaching-ensure-current$/.test(new URL(response.url()).pathname)
      && response.request().method() === 'POST', { timeout: HANDOFF_OBSERVATION_MS }).catch(() => null)
  try {
    const card = page.locator(
      `[data-testid="recommendation-game-card"][data-bgg-id="${selectedBggId}"]`,
    )
    await card.getByRole('button', { name: '选这款，找规则书', exact: true }).click()
    report.handoff.actionClicked = true
    await expect(page.getByTestId('player-journey-surface')).toBeVisible()

    const importResponse = await importResponsePromise
    report.handoff.importResponseStatus = importResponse.status()
    const imported = importResponse.ok() ? importedIdentity(await importResponse.json() as unknown) : null
    report.handoff.importedBggId = imported?.bggId ?? null
    report.handoff.importedGameId = imported?.gameId ?? null
    report.handoff.importedEditionId = imported?.editionId ?? null
    report.handoff.editionBelongsToGame = imported
      ? imported.editionGameId === imported.gameId
      : null
    expect(imported?.bggId).toBe(selectedBggId)
    expect(report.handoff.editionBelongsToGame).toBe(true)

    const jobsResponse = await jobsResponsePromise
    const jobs = jobsResponse.ok() ? await jobsResponse.json() as unknown : null
    const matchingJob = Array.isArray(jobs) ? jobs.find(job => {
      if (!job || typeof job !== 'object' || Array.isArray(job)) return false
      const candidate = job as Record<string, unknown>
      return publicUuid(candidate.editionId) === imported!.editionId
        && candidate.teachingHandoffState !== 'NOT_REQUESTED'
    }) as Record<string, unknown> | undefined : undefined
    report.handoff.existingJobId = publicUuid(matchingJob?.id)

    if (matchingJob) {
      const freshnessEligible = matchingJob.stage === 'COMPLETED'
        && publicUuid(matchingJob.documentVersionId) !== null
        && matchingJob.teachingHandoffState === 'LAUNCHED'
        && publicUuid(matchingJob.teachingPreparationRunId) !== null
      if (freshnessEligible) {
        const ensureResponse = await ensureResponsePromise
        expect(ensureResponse?.ok(), 'Existing readable work did not pass its freshness boundary').toBe(true)
        const ensured = await ensureResponse!.json() as Record<string, unknown>
        expect(publicUuid(ensured.id)).toBe(report.handoff.existingJobId)
        expect(publicUuid(ensured.editionId)).toBe(imported!.editionId)
      }
    } else {
      const discoveryResponse = await discoveryResponsePromise
      expect(discoveryResponse?.ok(), 'Exact-edition source discovery did not complete').toBe(true)
      const discovery = await discoveryResponse!.json() as Record<string, unknown>
      const identity = discovery.identity as Record<string, unknown> | undefined
      report.handoff.discoveryEditionMatched = publicUuid(identity?.editionId) === imported!.editionId
      report.handoff.sourceCount = Array.isArray(discovery.candidates)
        ? discovery.candidates.length
        : null
      expect(report.handoff.discoveryEditionMatched).toBe(true)
      expect(report.handoff.sourceCount).not.toBeNull()
    }

    const terminal = await handoffTerminal(page)
    report.handoff.terminal = terminal.terminal
    report.handoff.surfaceState = terminal.state
    report.handoff.canReadRulebook = terminal.rulebook
    report.handoff.canReadLesson = terminal.lesson
    report.handoff.failureClassification = terminal.failure
    expect(report.handoff.blockedMutationPaths,
      'The canary blocked an unexpected automatic official-import mutation').toEqual([])
    expect(terminal.terminal, 'The handoff ended in an explicit failure terminal')
      .not.toBe('EXPLICIT_FAILURE')
  } finally {
    await page.unroute('**/api/v1/documents/official-imports**', guard)
  }
}
function thrownFailure(error: unknown): FailureEvidence {
  return {
    classification: error instanceof Error && error.name === 'TimeoutError'
      ? 'lifecycle_deadline'
      : 'observer_failure',
    boundary: null,
    reason: null,
    code: error instanceof Error ? error.name : 'unknown_error',
  }
}

test('browser observer reads a cloned recommendation stream without taking the page response', async ({ page }) => {
  const result: RecommendationResult = {
    clientTurnId: 'observer-smoke-turn',
    outcome: 'conversation',
    assistantMessage: 'observer smoke reply',
    profile: {
      type: '', interaction: '', playerCount: null, durationMinutes: null, complexity: null,
    },
    catalogCalls: 0,
    webResearchCalls: 0,
    games: [],
  }
  const pendingStream = `event: progress\ndata: {"stage":"understanding_request"}\n\n`
    + 'event: answer_part\ndata: {"text":" "}\n\n'
    + 'event: answer_part\ndata: {"text":"A useful public summary"}\n\n'
    + 'event: recommendation_part\ndata: {"game":{"game":{"bggId":123}}}\n\n'
  const resultStream = `event: result\ndata: ${JSON.stringify(result)}\n\n`
  const errorStream = 'event: error\ndata: '
    + '{"code":"recommendation_unavailable","failureBoundary":"service_failure",'
    + '"failureReason":"provider_call_failed"}\n\n'
  let releaseResult: (() => void) | undefined
  const server = createServer((request, response) => {
    const url = new URL(request.url ?? '/', 'http://127.0.0.1')
    if (request.method === 'GET' && url.pathname === '/') {
      response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' })
      response.end('<!doctype html><title>stream observer smoke</title>')
      return
    }
    if (request.method === 'POST'
      && url.pathname === '/api/v1/bgg/recommendation-agent/stream') {
      response.writeHead(200, {
        'cache-control': 'no-store',
        'content-type': 'text/event-stream',
      })
      if (url.searchParams.get('case') === 'error') response.write(errorStream)
      else {
        response.write(pendingStream)
        releaseResult = () => { response.write(resultStream) }
      }
      return
    }
    response.writeHead(404)
    response.end()
  })
  await new Promise<void>((resolve, reject) => {
    const failed = (error: Error) => reject(error)
    server.once('error', failed)
    server.listen(0, '127.0.0.1', () => {
      server.off('error', failed)
      resolve()
    })
  })
  const address = server.address()
  if (address === null || typeof address === 'string') throw new Error('Observer server unavailable')
  const consumePageTerminal = (streamCase: 'result' | 'error') => page.evaluate(async value => {
    const response = await fetch(
      `/api/v1/bgg/recommendation-agent/stream?case=${encodeURIComponent(value)}`,
      { method: 'POST' },
    )
    if (!response.body) throw new Error('Synthetic stream unavailable')
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const chunk = await reader.read()
      buffer = `${buffer}${decoder.decode(chunk.value, { stream: !chunk.done })}`
        .replaceAll('\r\n', '\n')
      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const block = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        if (/^event: (?:result|error)$/mu.test(block) && /^data:/mu.test(block)) {
          await reader.cancel()
          return block
        }
        boundary = buffer.indexOf('\n\n')
      }
      if (chunk.done) throw new Error('Synthetic stream ended without a terminal')
    }
  }, streamCase)

  try {
    await installRecommendationStreamObserver(page)
    await page.goto(`http://127.0.0.1:${address.port}/`)
    await resetRecommendationStreamObserver(page)
    const resultObservationPromise = Promise.all([
      consumePageTerminal('result'),
      waitForRecommendationStreamObservation(page),
    ])
    await expect.poll(() => page.evaluate(() => {
      const state = (window as typeof window & {
        __rulepilotRecommendationStreamEvidence?: StreamEvidenceState
      }).__rulepilotRecommendationStreamEvidence
      return state?.firstRecommendationPartMs ?? null
    })).not.toBeNull()
    if (!releaseResult) throw new Error('Synthetic response was not requested')
    releaseResult()
    const [pageResultBlock, resultObservation] = await resultObservationPromise

    expect(parseStream(pageResultBlock).kind).toBe('result')
    expect(resultObservation.kind).toBe('terminal')
    if (resultObservation.kind !== 'terminal') throw new Error(resultObservation.code)
    const terminal = parseStream(resultObservation.block)
    expect(terminal.kind).toBe('result')
    if (terminal.kind === 'result') expect(terminal.result).toEqual(result)
    expect(resultObservation.firstAnswerPartMs).not.toBeNull()
    expect(resultObservation.firstRecommendationPartMs).toBeGreaterThanOrEqual(resultObservation.firstAnswerPartMs!)
    expect(resultObservation.terminalMs).toBeGreaterThanOrEqual(resultObservation.firstRecommendationPartMs!)

    await resetRecommendationStreamObserver(page)
    const [pageErrorBlock, errorObservation] = await Promise.all([
      consumePageTerminal('error'),
      waitForRecommendationStreamObservation(page),
    ])

    expect(parseStream(pageErrorBlock).kind).toBe('error')
    expect(errorObservation.kind).toBe('terminal')
    if (errorObservation.kind !== 'terminal') throw new Error(errorObservation.code)
    expect(errorObservation.firstAnswerPartMs).toBeNull()
    expect(errorObservation.firstRecommendationPartMs).toBeNull()
    expect(errorObservation.terminalMs).toBeGreaterThanOrEqual(0)
    expect(parseStream(errorObservation.block)).toEqual({
      kind: 'error',
      code: 'recommendation_unavailable',
      boundary: 'service_failure',
      reason: 'provider_call_failed',
    })
  } finally {
    server.closeAllConnections()
    await new Promise<void>((resolve, reject) => server.close(error => {
      if (error) reject(error)
      else resolve()
    }))
  }
})

test('production publishes natural and grounded recommendation replies before the exact-card handoff', async ({ page }) => {
  test.skip(!enabled, 'Runs only through the credentialed production recommendation workflow')
  test.setTimeout(6 * 60_000)

  const reportFile = process.env.RULEPILOT_RECOMMENDATION_REPORT
  if (!reportFile) throw new Error('Production recommendation report path is required')
  const report = initialReport()
  await retainReport(reportFile, report)

  try {
    const username = process.env.RULEPILOT_RECOMMENDATION_USER
    const password = process.env.RULEPILOT_RECOMMENDATION_PASSWORD
    const selectionPrompt = process.env.RULEPILOT_RECOMMENDATION_SELECTION_PROMPT ?? ''
    if (!username || !password || !selectionPrompt.trim()
      || !report.model.expected.provider || !report.model.expected.model) {
      throw new Error('Production credentials, prompt, and expected model are required')
    }
    if (!/^[0-9a-f]{40}$/.test(report.deployment.testedSha)
      || !new RegExp(`^${report.deployment.testedSha}-[0-9]+-[0-9]+$`)
        .test(report.deployment.activeReleaseId)) {
      throw new Error('One exact active tested release is required')
    }
    const requestedCardCount = positiveInteger(
      process.env.RULEPILOT_RECOMMENDATION_EXPECTED_CARD_COUNT,
      'requested card count',
    )
    const expectedPlayerCount = positiveInteger(
      process.env.RULEPILOT_RECOMMENDATION_EXPECTED_PLAYER_COUNT,
      'expected player count',
    )
    const maximumDuration = positiveInteger(
      process.env.RULEPILOT_RECOMMENDATION_MAXIMUM_DURATION_MINUTES,
      'maximum duration',
    )
    const maximumComplexity = positiveDecimal(
      process.env.RULEPILOT_RECOMMENDATION_MAXIMUM_COMPLEXITY,
      'maximum complexity',
    )
    const expectedGameType = (
      process.env.RULEPILOT_RECOMMENDATION_EXPECTED_GAME_TYPE ?? ''
    ).trim().toLocaleLowerCase('en-US')
    if (!ALLOWED_GAME_TYPES.has(expectedGameType)) throw new Error('Invalid expected game type')
    const titleTerm = (process.env.RULEPILOT_RECOMMENDATION_EXPECTED_TITLE_TERM ?? '')
      .normalize('NFKC').trim().toLocaleLowerCase('en-US')
    Object.assign(report.recommendation, {
      requestedCardCount,
      expectedPlayerCount,
      maximumDurationMinutes: maximumDuration,
      maximumComplexity,
      expectedGameType,
    })

    report.stage = 'deployment-before'
    report.deployment.before = await publicRelease(page.request)
    expect(report.deployment.before.commitSha).toBe(report.deployment.testedSha)
    expect(report.deployment.before.releaseId).toBe(report.deployment.activeReleaseId)

    report.stage = 'login'
    await installRecommendationStreamObserver(page)
    await login(page, username, password)
    report.model.before = await modelAssignment(page.request)
    expect(report.model.before).toEqual(report.model.expected)
    await page.goto('/discover')

    report.stage = 'fresh-session'
    const sessionResponsePromise = page.waitForResponse(response =>
      new URL(response.url()).pathname === '/api/v1/bgg/recommendation-agent/sessions'
        && response.request().method() === 'POST', { timeout: 60_000 })
    const newConversation = page.getByRole('button', { name: '建立新聊天', exact: true })
    await expect(newConversation).toBeEnabled({ timeout: 60_000 })
    await newConversation.click()
    const sessionResponse = await sessionResponsePromise
    expect(sessionResponse.ok()).toBe(true)
    const created = await sessionResponse.json() as RecommendationSession

    report.stage = 'natural-reply'
    await retainReport(reportFile, report)
    const naturalTurn = await submitTurn(page, NATURAL_PROMPT)
    report.naturalReply.requestMatched = naturalTurn.messageMatched
    Object.assign(report.naturalReply, naturalTurn.timings)
    if (naturalTurn.terminal.kind === 'error') {
      report.naturalReply.failure = streamFailure(naturalTurn.terminal)
      throw new Error('Natural reply stream failed')
    }
    const natural = naturalTurn.terminal.result
    report.naturalReply.outcome = natural.outcome
    report.naturalReply.assistantMessageSha256 = sha256(natural.assistantMessage)
    report.naturalReply.agentElapsedMs = natural.agentElapsedMs ?? null
    report.naturalReply.modelCallElapsedMs = natural.modelCallElapsedMs ?? []
    report.naturalReply.noExternalWork = natural.catalogCalls === 0
      && natural.webResearchCalls === 0 && natural.games.length === 0
    if (natural.outcome !== 'conversation') {
      report.naturalReply.failure = resultFailure(natural)
      throw new Error('Natural reply did not reach a conversation terminal')
    }
    const naturalSession = await persistedSession(page.request, created.conversationId)
    report.naturalReply.persistedMatched = persistedResultMatches(natural, naturalSession)
    const naturalDom = await page.getByTestId('assistant-conversation-turn').last().innerText()
    report.naturalReply.domMatched = normalized(naturalDom)
      === normalized(await markdownText(page, natural.assistantMessage))
    expect(report.naturalReply.requestMatched).toBe(true)
    expect(report.naturalReply.noExternalWork).toBe(true)
    expect(report.naturalReply.persistedMatched).toBe(true)
    expect(report.naturalReply.domMatched).toBe(true)

    report.stage = 'recommendation'
    await retainReport(reportFile, report)
    const recommendationTurn = await submitTurn(page, selectionPrompt)
    report.recommendation.requestMatched = recommendationTurn.messageMatched
    Object.assign(report.recommendation, recommendationTurn.timings)
    if (recommendationTurn.terminal.kind === 'error') {
      report.recommendation.failure = streamFailure(recommendationTurn.terminal)
      throw new Error('Recommendation stream failed')
    }
    const recommendation = recommendationTurn.terminal.result
    report.recommendation.outcome = recommendation.outcome
    report.recommendation.assistantMessageSha256 = sha256(recommendation.assistantMessage)
    report.recommendation.agentElapsedMs = recommendation.agentElapsedMs ?? null
    report.recommendation.modelCallElapsedMs = recommendation.modelCallElapsedMs ?? []
    report.recommendation.cards = recommendation.games.map(entry => ({
      bggId: entry.game.bggId,
      nameSha256: sha256(entry.game.name),
      originalNameSha256: sha256(entry.game.originalName),
    }))
    report.recommendation.shortfallCount = requestedCardCount - recommendation.games.length
    if (recommendation.outcome !== 'recommendations') {
      report.recommendation.failure = resultFailure(recommendation)
      throw new Error('Recommendation did not reach a recommendation terminal')
    }
    report.recommendation.publicationErrors = recommendationErrors(recommendation, {
      requestedCardCount,
      playerCount: expectedPlayerCount,
      maximumDuration,
      maximumComplexity,
      gameType: expectedGameType,
      titleTerm,
    })
    if (report.recommendation.publicationErrors.length) {
      report.recommendation.failure = {
        classification: 'product_terminal', boundary: 'publication_boundary',
        reason: 'publication_rejected', code: 'client_publication_contract',
      }
    }
    const recommendationSession = await persistedSession(page.request, created.conversationId)
    report.recommendation.persistedMatched = persistedResultMatches(
      recommendation,
      recommendationSession,
    )
    await expect(page.getByTestId('recommendation-game-card'))
      .toHaveCount(recommendation.games.length)
    report.recommendation.domMatched = await recommendationDomMatches(page, recommendation)
      && await recommendationSourcesMatch(page, recommendation)
    for (const card of await page.getByTestId('recommendation-game-card').all()) {
      await expect(card.getByRole('button', { name: '选这款，找规则书', exact: true })).toBeEnabled()
    }
    if (report.recommendation.persistedMatched === false
      || report.recommendation.domMatched === false) {
      report.recommendation.failure ??= {
        classification: 'terminal_evidence_gap', boundary: null,
        reason: null, code: 'publication_projection_mismatch',
      }
    }
    expect(report.recommendation.requestMatched).toBe(true)
    expect(report.recommendation.publicationErrors).toEqual([])
    expect(report.recommendation.persistedMatched).toBe(true)
    expect(report.recommendation.domMatched).toBe(true)

    report.model.after = await modelAssignment(page.request)
    report.model.stable = JSON.stringify(report.model.after) === JSON.stringify(report.model.before)
    expect(report.model.after).toEqual(report.model.expected)
    expect(report.model.stable).toBe(true)

    report.stage = 'handoff'
    await retainReport(reportFile, report)
    const selectedBggId = recommendation.games[0]!.game.bggId
    report.handoff.selectedBggId = selectedBggId
    await observeHandoff(page, report, selectedBggId)

    report.stage = 'deployment-after'
    report.deployment.after = await publicRelease(page.request)
    report.deployment.exactAndStable = JSON.stringify(report.deployment.after)
      === JSON.stringify(report.deployment.before)
      && report.deployment.after.commitSha === report.deployment.testedSha
      && report.deployment.after.releaseId === report.deployment.activeReleaseId
    expect(report.deployment.exactAndStable).toBe(true)
    await expect(page).toHaveURL(/\/discover$/)

    report.completed = true
    report.stage = 'completed'
  } catch (error) {
    report.failedStage = report.stage
    report.fatalFailure = report.recommendation.failure
      ?? report.naturalReply.failure
      ?? thrownFailure(error)
    report.stage = 'failed'
    throw error
  } finally {
    await retainReport(reportFile, report)
  }
})
