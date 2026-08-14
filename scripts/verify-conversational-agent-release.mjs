#!/usr/bin/env node

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const REPORT_KEYS = Object.freeze([
  'recommendation',
  'acquisition',
  'teachingDialogue',
  'answerDialogue',
  'answerEvidence',
  'teachingEvidence',
  'security',
])

const LIMITS = Object.freeze({
  recommendationLatencyMs: [1_000, 55_000],
  recommendationModelCalls: [1, 6],
  recommendationCatalogCalls: [1, 20],
  recommendationWebCalls: [0, 2],
  adaptiveIntentLatencyMs: [1_000, 120_000],
  answerDialogueLatencyMs: [1_000, 120_000],
  acquisitionDiscoveryLatencyMs: [1_000, 300_000],
  acquisitionDownloadLatencyMs: [1_000, 900_000],
  acquisitionMaximumBytes: [1_024, 512 * 1024 * 1024],
})

function requireInvariant(condition, stage, message) {
  if (!condition) throw new Error(`${stage}: ${message}`)
}

function prohibitedModel(model) {
  const normalized = String(model ?? '').trim().toLowerCase()
  return normalized === 'qwen-plus'
    || normalized.startsWith('qwen-plus-')
    || normalized.startsWith('qwen-plus_')
}

function opaqueCaseId(value) {
  return /^cx-[a-z0-9-]+$/.test(value ?? '')
}

export function releaseMatrixIssues(matrix, pathExists = () => true) {
  const issues = []
  if (matrix?.schemaVersion !== 1) issues.push('schemaVersion must be 1')
  const paths = matrix?.reportPaths
  if (!paths || typeof paths !== 'object' || Array.isArray(paths)) {
    issues.push('reportPaths must be an object')
  } else {
    const names = Object.keys(paths)
    for (const key of REPORT_KEYS) {
      if (names.filter((candidate) => candidate === key).length !== 1) {
        issues.push(`report path must appear once: ${key}`)
        continue
      }
      const path = paths[key]
      if (typeof path !== 'string' || !path.startsWith('.local/agent-evaluation/') || !path.endsWith('.json')) {
        issues.push(`${key} report must be ignored local JSON evidence`)
      } else if (!pathExists(path)) {
        issues.push(`${key} report path is missing`)
      }
    }
    for (const key of names) if (!REPORT_KEYS.includes(key)) issues.push(`unknown report path: ${key}`)
  }
  const limits = matrix?.limits
  if (!limits || typeof limits !== 'object' || Array.isArray(limits)) {
    issues.push('limits must be an object')
  } else {
    for (const [name, [minimum, maximum]] of Object.entries(LIMITS)) {
      const value = limits[name]
      if (!Number.isInteger(value) || value < minimum || value > maximum) {
        issues.push(`${name} must be an integer between ${minimum} and ${maximum}`)
      }
    }
    for (const key of Object.keys(limits)) if (!(key in LIMITS)) issues.push(`unknown limit: ${key}`)
  }
  return [...new Set(issues)]
}

function verifyFreshReports(reports, notBefore, now) {
  requireInvariant(typeof notBefore === 'string' && notBefore.length > 0,
    'DETERMINISTIC_READY', 'a release evidence lower bound is required')
  const lowerBound = new Date(notBefore)
  requireInvariant(!Number.isNaN(lowerBound.getTime()),
    'DETERMINISTIC_READY', 'release evidence lower bound is invalid')
  requireInvariant(now instanceof Date && !Number.isNaN(now.getTime()),
    'DETERMINISTIC_READY', 'release verification time is invalid')
  for (const [name, report] of Object.entries(reports)) {
    requireInvariant(report?.schemaVersion === 1, 'DETERMINISTIC_READY', `${name} report schema is invalid`)
    const generatedAt = new Date(report?.generatedAt)
    requireInvariant(!Number.isNaN(generatedAt.getTime()),
      'DETERMINISTIC_READY', `${name} generatedAt is missing or invalid`)
    requireInvariant(generatedAt >= lowerBound, 'DETERMINISTIC_READY', `${name} evidence is stale`)
    requireInvariant(generatedAt <= new Date(now.getTime() + 5 * 60 * 1000),
      'DETERMINISTIC_READY', `${name} evidence is future-dated`)
  }
}

function verifyNoSensitiveReportFields(value, path = 'report') {
  if (Array.isArray(value)) {
    value.forEach((item, index) => verifyNoSensitiveReportFields(item, `${path}[${index}]`))
    return
  }
  if (!value || typeof value !== 'object') return
  for (const [key, child] of Object.entries(value)) {
    const sensitiveName = /(prompt|raw.*(?:output|response|text|pdf)|chain.*thought|reasoning.*text|api.*key|credential)/i
      .test(key)
    const negativeRetentionControl = sensitiveName
      && path.endsWith('.controls')
      && /(?:stored|retained)$/i.test(key)
      && child === false
    requireInvariant(!sensitiveName || negativeRetentionControl,
      'DETERMINISTIC_READY', `${path}.${key} is not an allowed aggregate evidence field`)
    verifyNoSensitiveReportFields(child, `${path}.${key}`)
  }
}

function verifyRecommendation(report, limits) {
  const stage = 'SEMANTIC_READY'
  const results = Array.isArray(report?.results) ? report.results : []
  requireInvariant(results.length >= 2, stage, 'recommendation needs at least two real provider results')
  const providers = new Set()
  for (const result of results) {
    requireInvariant(opaqueCaseId(result?.caseId), stage, 'recommendation case ID must be opaque')
    requireInvariant(typeof result?.provider === 'string' && result.provider.length > 0,
      stage, `${result?.caseId} recommendation provider is missing`)
    requireInvariant(typeof result?.model === 'string' && result.model.length > 0 && !prohibitedModel(result.model),
      stage, `${result?.caseId} recommendation model is prohibited or missing`)
    providers.add(result.provider)
    requireInvariant(result.outcome === 'RECOMMENDATIONS', stage,
      `${result.caseId} recommendation did not reach a useful terminal state`)
    requireInvariant(Array.isArray(result.scenarioTags)
        && ['comparison-correction', 'direct-target', 'everyday-refinement']
          .every((tag) => result.scenarioTags.includes(tag)),
    stage, `${result.caseId} does not cover the required everyday recommendation scenarios`)
    requireInvariant(result.continuationResolved === true, stage,
      `${result.caseId} did not preserve the context-dependent player goal`)
    requireInvariant(result.targetOutcome === 'RECOMMENDATIONS' && result.targetSelected === true,
      stage, `${result.caseId} did not turn a player-selected named game into a selectable card`)
    requireInvariant(result.targetRecommendationCount === 1,
      stage, `${result.caseId} mixed unrelated games into a direct named-game selection`)
    requireInvariant(Number.isInteger(result.targetLatencyMs)
        && result.targetLatencyMs > 0 && result.targetLatencyMs <= limits.recommendationLatencyMs,
      stage, `${result.caseId} named-game target latency budget failed`)
    requireInvariant(Array.isArray(result.targetActions)
        && result.targetActions.includes('RESOLVE_BGG_REFERENCE')
        && result.targetActions.includes('RECOMMEND_GAMES'),
      stage, `${result.caseId} named-game target did not use the observable selection handoff`)
    requireInvariant(result.everydayOutcome === 'RECOMMENDATIONS'
        && result.everydayPlayers === 4
        && result.everydayMaxMinutes === 60
        && result.everydayPreferenceUpdates === true,
    stage, `${result.caseId} did not preserve an everyday refinement into hard preferences`)
    requireInvariant(Number.isInteger(result.everydayNaturalTurnCount) && result.everydayNaturalTurnCount >= 3,
      stage, `${result.caseId} everyday refinement is not multi-turn`)
    requireInvariant(Number.isInteger(result.everydayRecommendationCount)
        && result.everydayRecommendationCount >= 2,
    stage, `${result.caseId} everyday refinement did not produce a useful slate`)
    requireInvariant(Number.isInteger(result.everydayLatencyMs)
        && result.everydayLatencyMs > 0 && result.everydayLatencyMs <= limits.recommendationLatencyMs,
    stage, `${result.caseId} everyday refinement latency budget failed`)
    requireInvariant(Array.isArray(result.everydayActions)
        && result.everydayActions.includes('UPDATE_PREFERENCES')
        && result.everydayActions.includes('RECOMMEND_GAMES'),
    stage, `${result.caseId} everyday refinement did not expose preference and recommendation actions`)
    requireInvariant(result.referenceExcluded === true, stage,
      `${result.caseId} recommended the player's comparison target back to them`)
    requireInvariant(result.referenceGrounded === true, stage,
      `${result.caseId} did not ground candidate cards in the corrected reference facts`)
    requireInvariant(Number.isInteger(result.naturalTurnCount) && result.naturalTurnCount >= 2,
      stage, `${result.caseId} is not a natural multi-turn conversation`)
    requireInvariant(Number.isInteger(result.recommendationCount) && result.recommendationCount >= 2,
      stage, `${result.caseId} did not publish a useful candidate slate`)
    requireInvariant(result.uniqueRecommendationCount === result.recommendationCount,
      stage, `${result.caseId} published duplicate candidates`)
    requireInvariant(Number.isInteger(result.modelCalls)
        && result.modelCalls >= 1 && result.modelCalls <= limits.recommendationModelCalls,
      stage, `${result.caseId} model-call budget failed`)
    requireInvariant(Number.isInteger(result.catalogCalls)
        && result.catalogCalls >= 1 && result.catalogCalls <= limits.recommendationCatalogCalls,
      stage, `${result.caseId} catalog-call budget failed`)
    requireInvariant(Number.isInteger(result.webResearchCalls)
        && result.webResearchCalls >= 0 && result.webResearchCalls <= limits.recommendationWebCalls,
      stage, `${result.caseId} web-call budget failed`)
    requireInvariant(result.fallbackUsed === false, stage, `${result.caseId} used an unverified fallback`)
    requireInvariant(Number.isInteger(result.totalLatencyMs)
        && result.totalLatencyMs > 0 && result.totalLatencyMs <= limits.recommendationLatencyMs,
      stage, `${result.caseId} recommendation latency budget failed`)
    requireInvariant(Array.isArray(result.actions) && result.actions.includes('RECOMMEND_GAMES'),
      stage, `${result.caseId} has no observable recommendation action`)
  }
  requireInvariant(providers.size >= 2, stage, 'recommendation needs two distinct providers')
  const controls = report?.controls ?? {}
  requireInvariant(controls.explicitPreferenceEnumInjected === false, stage,
    'recommendation evaluation injected a preference enum')
  requireInvariant(controls.rawModelOutputStored === false, stage,
    'recommendation evaluation retained raw model output')
  requireInvariant(controls.prohibitedQwenPlusUsed === false, stage,
    'recommendation evaluation used qwen-plus')
  return providers
}

function verifyAdaptiveDialogue(report, limits) {
  const stage = 'SEMANTIC_READY'
  const results = Array.isArray(report?.results) ? report.results : []
  requireInvariant(results.length >= 4, stage, 'adaptive dialogue needs four everyday real-provider moves')
  const providers = new Set()
  const providerCounts = new Map()
  const observedIntents = new Set()
  for (const result of results) {
    requireInvariant(opaqueCaseId(result?.caseId), stage, 'adaptive dialogue case ID must be opaque')
    requireInvariant(typeof result?.provider === 'string' && result.provider.length > 0,
      stage, `${result?.caseId} adaptive provider is missing`)
    requireInvariant(typeof result?.model === 'string' && result.model.length > 0 && !prohibitedModel(result.model),
      stage, `${result?.caseId} adaptive model is prohibited or missing`)
    providers.add(result.provider)
    providerCounts.set(result.provider, (providerCounts.get(result.provider) ?? 0) + 1)
    observedIntents.add(result.learningIntent)
    requireInvariant(result.referenceBinding === 'PREVIOUS_QUESTION', stage,
      `${result.caseId} did not bind the prior player question`)
    requireInvariant(['SIMPLIFY', 'EXAMPLE', 'DEFINE', 'WHY', 'EXCEPTIONS', 'SOURCE', 'VERIFY']
      .includes(result.learningIntent), stage, `${result.caseId} has no adaptive teaching move`)
    requireInvariant(typeof result.interactionTag === 'string' && result.interactionTag.length > 0,
      stage, `${result.caseId} has no everyday interaction tag`)
    requireInvariant(Number.isInteger(result.naturalTurnCount) && result.naturalTurnCount >= 2,
      stage, `${result.caseId} is not grounded in a prior conversational turn`)
    requireInvariant(Number.isInteger(result.subquestionCount)
        && result.subquestionCount >= 1 && result.subquestionCount <= 4,
      stage, `${result.caseId} evidence plan is incomplete`)
    requireInvariant(result.modelCalls === 1 && result.toolCalls === 0,
      stage, `${result.caseId} added an unexpected classifier or tool call`)
    requireInvariant(Number.isInteger(result.latencyMs)
        && result.latencyMs > 0 && result.latencyMs <= limits.adaptiveIntentLatencyMs,
      stage, `${result.caseId} adaptive intent latency budget failed`)
  }
  requireInvariant(providers.size >= 2, stage, 'adaptive dialogue needs two distinct providers')
  requireInvariant([...providerCounts.values()].every((count) => count >= 2), stage,
    'adaptive dialogue needs at least two everyday moves per provider')
  for (const intent of ['SIMPLIFY', 'EXAMPLE', 'SOURCE', 'EXCEPTIONS']) {
    requireInvariant(observedIntents.has(intent), stage, `adaptive dialogue is missing ${intent}`)
  }
  const controls = report?.controls ?? {}
  requireInvariant(controls.explicitEnumInjected === false, stage,
    'adaptive dialogue evaluation injected a learning-intent enum')
  requireInvariant(controls.rulebookTextUsedAsIntentEvidence === false, stage,
    'adaptive dialogue used rulebook text as intent evidence')
  requireInvariant(controls.rawModelOutputStored === false, stage,
    'adaptive dialogue retained raw model output')
  requireInvariant(controls.prohibitedQwenPlusUsed === false, stage,
    'adaptive dialogue used qwen-plus')
  return providers
}

function verifyAnswerDialogue(report, limits) {
  const stage = 'SEMANTIC_READY'
  const results = Array.isArray(report?.results) ? report.results : []
  requireInvariant(results.length >= 2, stage, 'Answer dialogue needs two grounded real-rulebook conversations')
  const providers = new Set()
  const interactionTags = new Set()
  for (const result of results) {
    requireInvariant(/^rr-[a-z0-9-]+$/.test(result?.caseId ?? ''), stage,
      'Answer dialogue case ID must identify a sanitized real-rulebook case')
    requireInvariant(typeof result?.provider === 'string' && result.provider.length > 0,
      stage, `${result?.caseId} Answer dialogue provider is missing`)
    requireInvariant(typeof result?.model === 'string' && result.model.length > 0 && !prohibitedModel(result.model),
      stage, `${result?.caseId} Answer dialogue model is prohibited or missing`)
    providers.add(result.provider)
    requireInvariant(Array.isArray(result.interactionTags) && result.interactionTags.length >= 3,
      stage, `${result.caseId} Answer dialogue is not a rich everyday follow-up`)
    result.interactionTags.forEach((tag) => interactionTags.add(tag))
    requireInvariant(Number.isInteger(result.naturalTurnCount) && result.naturalTurnCount >= 3,
      stage, `${result.caseId} Answer dialogue did not preserve the prior exchange`)
    requireInvariant(result.refinementRequired === true
        && result.freshCanonicalExpectedPage === true
        && result.sameVersionOnly === true
        && result.toolPortfolioRegistered === true,
    stage, `${result.caseId} Answer follow-up was not regrounded in the bound rulebook`)
    requireInvariant(Number.isInteger(result.toolCalls) && result.toolCalls >= 1 && result.toolCalls <= 6,
      stage, `${result.caseId} Answer dialogue tool-call budget failed`)
    requireInvariant(Number.isInteger(result.modelCalls) && result.modelCalls >= 1 && result.modelCalls <= 6,
      stage, `${result.caseId} Answer dialogue model-call budget failed`)
    requireInvariant(result.withinLatencyBudget === true
        && Number.isInteger(result.latencyMs)
        && result.latencyMs > 0
        && result.latencyMs <= limits.answerDialogueLatencyMs,
    stage, `${result.caseId} Answer dialogue latency budget failed`)
  }
  requireInvariant(providers.size >= 2, stage, 'Answer dialogue needs two distinct providers')
  for (const tag of ['pronoun', 'why', 'simplify', 'example', 'exception']) {
    requireInvariant(interactionTags.has(tag), stage, `Answer dialogue is missing ${tag}`)
  }
  const controls = report?.controls ?? {}
  requireInvariant(controls.priorAnswerIsEvidence === false, stage,
    'Answer dialogue promoted a prior answer to rule evidence')
  requireInvariant(controls.providerDowngradeKeepsDeterministicEvidence === true, stage,
    'Answer dialogue loses deterministic evidence on provider downgrade')
  requireInvariant(controls.staleSchemaRejectedBeforeToolExecution === true, stage,
    'Answer dialogue can execute stale tool schemas')
  return providers
}

function verifyAcquisition(report, limits) {
  const stage = 'EVIDENCE_READY'
  const results = Array.isArray(report?.results) ? report.results : []
  requireInvariant(results.length >= 1, stage, 'rulebook acquisition has no real result')
  for (const result of results) {
    requireInvariant(opaqueCaseId(result?.caseId), stage, 'acquisition case ID must be opaque')
    requireInvariant(typeof result?.model === 'string' && result.model.length > 0 && !prohibitedModel(result.model),
      stage, `${result?.caseId} acquisition model is prohibited or missing`)
    requireInvariant(Number.isInteger(result.discoveredCandidateCount) && result.discoveredCandidateCount > 0,
      stage, `${result.caseId} discovered no source candidate`)
    requireInvariant(Number.isInteger(result.directPdfCandidateCount) && result.directPdfCandidateCount > 0,
      stage, `${result.caseId} discovered no direct PDF`)
    requireInvariant(Number.isInteger(result.attemptedDownloads)
        && result.attemptedDownloads >= 1 && result.attemptedDownloads <= result.directPdfCandidateCount,
      stage, `${result.caseId} download-attempt accounting is invalid`)
    requireInvariant(result.sourceObserved === true && result.publicHttpsVerified === true,
      stage, `${result.caseId} source provenance or HTTPS validation failed`)
    requireInvariant(result.mimeVerified === true && result.pdfMagicVerified === true,
      stage, `${result.caseId} downloaded content did not pass PDF validation`)
    requireInvariant(result.digestRecorded === true && /^[a-f0-9]{64}$/.test(result.sha256 ?? ''),
      stage, `${result.caseId} content digest is missing`)
    requireInvariant(/^[a-f0-9]{64}$/.test(result.finalHostDigest ?? ''),
      stage, `${result.caseId} sanitized host digest is missing`)
    requireInvariant(Number.isInteger(result.bytes)
        && result.bytes > 0 && result.bytes <= limits.acquisitionMaximumBytes,
      stage, `${result.caseId} downloaded byte count is outside the release limit`)
    requireInvariant(Number.isInteger(result.discoveryLatencyMs)
        && result.discoveryLatencyMs > 0 && result.discoveryLatencyMs <= limits.acquisitionDiscoveryLatencyMs,
      stage, `${result.caseId} discovery latency budget failed`)
    requireInvariant(Number.isInteger(result.downloadLatencyMs)
        && result.downloadLatencyMs > 0 && result.downloadLatencyMs <= limits.acquisitionDownloadLatencyMs,
      stage, `${result.caseId} download latency budget failed`)
  }
  const controls = report?.controls ?? {}
  requireInvariant(controls.explicitConsentRequired === true, stage,
    'rulebook acquisition bypassed explicit consent')
  requireInvariant(controls.applicationFetcherUsed === true, stage,
    'rulebook acquisition did not use the application downloader')
  requireInvariant(controls.rawPdfStored === false && controls.rawProviderOutputStored === false,
    stage, 'rulebook acquisition report retained raw content')
  requireInvariant(controls.prohibitedQwenPlusUsed === false, stage,
    'rulebook acquisition used qwen-plus')
}

function verifyGroundedEvidence(answer, teaching) {
  const stage = 'EVIDENCE_READY'
  const answerResults = Array.isArray(answer?.results) ? answer.results : []
  const teachingResults = Array.isArray(teaching?.semanticResults)
    ? teaching.semanticResults
    : Array.isArray(teaching?.results) ? teaching.results : []
  const answerProviders = new Set(answerResults
    .filter((result) => result.citationPublished === true && result.withinLatencyBudget === true)
    .map((result) => result.provider).filter(Boolean))
  const teachingProviders = new Set(teachingResults
    .filter((result) => result.citationAccepted === true
      && result.expectedCoverageAdded === true
      && result.withinLatencyBudget === true)
    .map((result) => result.provider).filter(Boolean))
  requireInvariant(answerProviders.size >= 2, stage,
    'Answer evidence must publish citations with two paid providers')
  requireInvariant(teachingProviders.size >= 2, stage,
    'Teaching evidence must accept complete cited coverage with two paid providers')
  requireInvariant(answer?.crossRulebookNegative?.crossScopeEvidence === false,
    stage, 'Answer cross-rulebook evidence escaped scope')
  requireInvariant(teaching?.crossRulebookNegative?.crossScopeEvidence === false,
    stage, 'Teaching cross-rulebook evidence escaped scope')
  return new Set([...answerProviders, ...teachingProviders])
}

export function verifyConversationalRelease({
  matrix,
  reports,
  notBefore,
  deterministicVerified,
  playerSurfaceVerified,
  now = new Date(),
}) {
  const issues = releaseMatrixIssues(matrix)
  requireInvariant(issues.length === 0, 'DETERMINISTIC_READY', issues.join('; '))
  requireInvariant(deterministicVerified === true, 'DETERMINISTIC_READY',
    'deterministic repository gates were not verified')
  requireInvariant(reports?.security?.hardInvariants === 'PASSED', 'DETERMINISTIC_READY',
    'Agent security hard invariants did not pass')
  verifyFreshReports(reports, notBefore, now)
  for (const report of Object.values(reports)) verifyNoSensitiveReportFields(report)

  const recommendationProviders = verifyRecommendation(reports.recommendation, matrix.limits)
  const adaptiveProviders = verifyAdaptiveDialogue(reports.teachingDialogue, matrix.limits)
  const answerDialogueProviders = verifyAnswerDialogue(reports.answerDialogue, matrix.limits)
  const semanticProviders = new Set([
    ...recommendationProviders,
    ...adaptiveProviders,
    ...answerDialogueProviders,
  ])

  verifyAcquisition(reports.acquisition, matrix.limits)
  const evidenceProviders = verifyGroundedEvidence(reports.answerEvidence, reports.teachingEvidence)

  requireInvariant(playerSurfaceVerified === true, 'PLAYER_SURFACE_READY',
    'desktop/mobile player-surface gate was not verified')

  return {
    schemaVersion: 1,
    generatedAt: now.toISOString(),
    evidenceNotBefore: notBefore,
    stages: [
      { id: 'DETERMINISTIC_READY', status: 'PASSED' },
      { id: 'SEMANTIC_READY', status: 'PASSED' },
      { id: 'EVIDENCE_READY', status: 'PASSED' },
      { id: 'PLAYER_SURFACE_READY', status: 'PASSED' },
      { id: 'CANARY_READY', status: 'PASSED' },
    ],
    semanticProviders: [...semanticProviders].sort(),
    evidenceProviders: [...evidenceProviders].sort(),
    recommendationCases: reports.recommendation.results.length,
    adaptiveDialogueCases: reports.teachingDialogue.results.length,
    answerDialogueCases: reports.answerDialogue.results.length,
    acquisitionCases: reports.acquisition.results.length,
    releaseDecision: 'CANARY_READY',
  }
}

function parseArguments(arguments_) {
  const options = {
    matrix: 'examples/evaluation/conversational-agent-release-v1.json',
    output: '.local/agent-evaluation/conversational-agent-release.json',
    notBefore: null,
    deterministicVerified: false,
    playerSurfaceVerified: false,
  }
  for (let index = 0; index < arguments_.length; index += 1) {
    const argument = arguments_[index]
    if (argument === '--matrix') options.matrix = arguments_[++index]
    else if (argument === '--output') options.output = arguments_[++index]
    else if (argument === '--not-before') options.notBefore = arguments_[++index]
    else if (argument === '--deterministic-verified') options.deterministicVerified = true
    else if (argument === '--player-surface-verified') options.playerSurfaceVerified = true
    else throw new Error(`unknown argument: ${argument}`)
  }
  return options
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
    const options = parseArguments(process.argv.slice(2))
    const matrix = JSON.parse(readFileSync(resolve(root, options.matrix), 'utf8'))
    const matrixIssues = releaseMatrixIssues(matrix, (path) => existsSync(resolve(root, path)))
    requireInvariant(matrixIssues.length === 0, 'DETERMINISTIC_READY', matrixIssues.join('; '))
    const reports = Object.fromEntries(Object.entries(matrix.reportPaths)
      .map(([name, path]) => [name, JSON.parse(readFileSync(resolve(root, path), 'utf8'))]))
    const result = verifyConversationalRelease({
      matrix,
      reports,
      notBefore: options.notBefore,
      deterministicVerified: options.deterministicVerified,
      playerSurfaceVerified: options.playerSurfaceVerified,
    })
    const output = resolve(root, options.output)
    mkdirSync(dirname(output), { recursive: true })
    writeFileSync(output, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o600 })
    console.log(`Conversational Agent release gate passed: ${result.releaseDecision}, ${result.semanticProviders.length} semantic providers, ${result.acquisitionCases} real acquisition case(s).`)
  } catch (error) {
    console.error(`FAIL ${error.message}`)
    process.exitCode = 1
  }
}
