import assert from 'node:assert/strict'
import test from 'node:test'

import {
  releaseMatrixIssues,
  verifyConversationalRelease,
} from './verify-conversational-agent-release.mjs'

const reportPaths = {
  recommendation: '.local/agent-evaluation/recommendation-conversation-real.json',
  acquisition: '.local/agent-evaluation/rulebook-acquisition-real.json',
  teachingDialogue: '.local/agent-evaluation/teaching-dialogue-intent-real.json',
  answerDialogue: '.local/agent-evaluation/context-agent-real-rulebooks.json',
  answerEvidence: '.local/agent-evaluation/answer-agent-real-rulebooks.json',
  teachingEvidence: '.local/agent-evaluation/teaching-agent-real-rulebooks.json',
  security: '.local/agent-evaluation/security-agent-release-gate.json',
}

const limits = {
  recommendationLatencyMs: 30_000,
  recommendationModelCalls: 6,
  recommendationCatalogCalls: 12,
  recommendationWebCalls: 1,
  adaptiveIntentLatencyMs: 45_000,
  answerDialogueLatencyMs: 90_000,
  acquisitionDiscoveryLatencyMs: 120_000,
  acquisitionDownloadLatencyMs: 600_000,
  acquisitionMaximumBytes: 100 * 1024 * 1024,
}

function input() {
  const generatedAt = '2026-08-10T01:00:01.000Z'
  const recommendationResult = (caseId, provider, model) => ({
    caseId,
    provider,
    model,
    outcome: 'RECOMMENDATIONS',
    scenarioTags: ['comparison-correction', 'direct-target', 'everyday-refinement'],
    continuationResolved: true,
    targetOutcome: 'RECOMMENDATIONS',
    targetSelected: true,
    targetRecommendationCount: 1,
    targetLatencyMs: 3_000,
    targetActions: ['RESOLVE_BGG_REFERENCE', 'RECOMMEND_GAMES'],
    everydayOutcome: 'RECOMMENDATIONS',
    everydayNaturalTurnCount: 3,
    everydayPlayers: 4,
    everydayMaxMinutes: 60,
    everydayPreferenceUpdates: true,
    everydayRecommendationCount: 3,
    everydayLatencyMs: 4_000,
    everydayActions: ['UPDATE_PREFERENCES', 'BROWSE_BGG_CANDIDATES', 'RECOMMEND_GAMES'],
    referenceExcluded: true,
    referenceGrounded: true,
    naturalTurnCount: 3,
    recommendationCount: 3,
    uniqueRecommendationCount: 3,
    modelCalls: 3,
    catalogCalls: 3,
    webResearchCalls: 0,
    fallbackUsed: false,
    totalLatencyMs: 12_000,
    actions: ['RESOLVE_BGG_REFERENCE', 'LOOKUP_BGG_CANDIDATES', 'RECOMMEND_GAMES'],
  })
  const adaptiveResult = (caseId, provider, model, learningIntent, interactionTag) => ({
    caseId,
    provider,
    model,
    referenceBinding: 'PREVIOUS_QUESTION',
    learningIntent,
    interactionTag,
    naturalTurnCount: 2,
    subquestionCount: 2,
    modelCalls: 1,
    toolCalls: 0,
    latencyMs: 2_500,
  })
  const reports = {
    recommendation: {
      schemaVersion: 1,
      generatedAt,
      results: [
        recommendationResult('cx-rec-a', 'deepseek', 'deepseek-v4-flash'),
        recommendationResult('cx-rec-b', 'qwen', 'qwen3.7-plus'),
      ],
      controls: {
        explicitPreferenceEnumInjected: false,
        rawModelOutputStored: false,
      },
    },
    acquisition: {
      schemaVersion: 1,
      generatedAt,
      results: [{
        caseId: 'cx-pdf-a',
        provider: 'qwen',
        model: 'qwen3.7-plus',
        discoveredCandidateCount: 3,
        directPdfCandidateCount: 2,
        attemptedDownloads: 1,
        sourceObserved: true,
        publicHttpsVerified: true,
        mimeVerified: true,
        pdfMagicVerified: true,
        digestRecorded: true,
        sha256: 'a'.repeat(64),
        finalHostDigest: 'b'.repeat(64),
        bytes: 11_000_000,
        discoveryLatencyMs: 8_000,
        downloadLatencyMs: 4_000,
      }],
      controls: {
        explicitConsentRequired: true,
        applicationFetcherUsed: true,
        rawPdfStored: false,
        rawProviderOutputStored: false,
      },
    },
    teachingDialogue: {
      schemaVersion: 1,
      generatedAt,
      results: [
        adaptiveResult('cx-adapt-a', 'deepseek', 'deepseek-v4-flash', 'EXAMPLE', 'example'),
        adaptiveResult('cx-source-a', 'deepseek', 'deepseek-v4-flash', 'SOURCE', 'source'),
        adaptiveResult('cx-adapt-b', 'qwen', 'qwen3.7-plus', 'SIMPLIFY', 'simplify'),
        adaptiveResult('cx-exception-b', 'qwen', 'qwen3.7-plus', 'EXCEPTIONS', 'exception'),
      ],
      controls: {
        explicitEnumInjected: false,
        rulebookTextUsedAsIntentEvidence: false,
        rawModelOutputStored: false,
      },
    },
    answerDialogue: {
      schemaVersion: 1,
      generatedAt,
      results: [
        {
          caseId: 'rr-text-001', provider: 'deepseek', model: 'deepseek-v4-flash',
          interactionTags: ['pronoun', 'why', 'simplify', 'example'], naturalTurnCount: 3,
          refinementRequired: true, freshCanonicalExpectedPage: true, sameVersionOnly: true,
          toolPortfolioRegistered: true, toolCalls: 2, modelCalls: 2, latencyMs: 8_000,
          withinLatencyBudget: true,
        },
        {
          caseId: 'rr-long-001', provider: 'qwen', model: 'qwen3.7-plus',
          interactionTags: ['pronoun', 'exception', 'why', 'simplify', 'example'], naturalTurnCount: 3,
          refinementRequired: true, freshCanonicalExpectedPage: true, sameVersionOnly: true,
          toolPortfolioRegistered: true, toolCalls: 2, modelCalls: 2, latencyMs: 12_000,
          withinLatencyBudget: true,
        },
      ],
      controls: {
        priorAnswerIsEvidence: false,
        orphanedRunsReplayIncompleteCalls: false,
        providerDowngradeKeepsDeterministicEvidence: true,
        staleSchemaRejectedBeforeToolExecution: true,
      },
    },
    answerEvidence: {
      schemaVersion: 1,
      generatedAt,
      results: [
        { caseId: 'rr-a', provider: 'deepseek', citationPublished: true, withinLatencyBudget: true },
        { caseId: 'rr-b', provider: 'qwen', citationPublished: true, withinLatencyBudget: true },
      ],
      crossRulebookNegative: { crossScopeEvidence: false },
    },
    teachingEvidence: {
      schemaVersion: 1,
      generatedAt,
      results: [
        { caseId: 'rr-a', provider: 'application', citationAccepted: true, expectedCoverageAdded: true, withinLatencyBudget: true },
      ],
      semanticResults: [
        { caseId: 'rr-a', provider: 'deepseek', citationAccepted: true, expectedCoverageAdded: true, withinLatencyBudget: true },
        { caseId: 'rr-b', provider: 'qwen', citationAccepted: true, expectedCoverageAdded: true, withinLatencyBudget: true },
      ],
      crossRulebookNegative: { crossScopeEvidence: false },
    },
    security: {
      schemaVersion: 1,
      generatedAt,
      hardInvariants: 'PASSED',
    },
  }
  return {
    matrix: {
      schemaVersion: 1,
      reportPaths: { ...reportPaths },
      limits: { ...limits },
    },
    reports,
    notBefore: '2026-08-10T01:00:00.000Z',
    now: new Date('2026-08-10T01:01:00.000Z'),
    deterministicVerified: true,
    playerSurfaceVerified: true,
  }
}

test('accepts the exact ignored-report matrix and bounded release limits', () => {
  assert.deepEqual(releaseMatrixIssues(input().matrix), [])
  const invalid = input().matrix
  invalid.reportPaths.recommendation = 'tracked/raw-model-output.json'
  invalid.limits.recommendationLatencyMs = 120_000
  assert.match(releaseMatrixIssues(invalid).join('\n'), /ignored local JSON evidence/)
  assert.match(releaseMatrixIssues(invalid).join('\n'), /recommendationLatencyMs/)
})

test('promotes only fresh natural multi-turn, acquisition, evidence, and player-surface results', () => {
  const release = verifyConversationalRelease(input())
  assert.equal(release.releaseDecision, 'CANARY_READY')
  assert.deepEqual(release.stages.map((stage) => stage.id), [
    'DETERMINISTIC_READY',
    'SEMANTIC_READY',
    'EVIDENCE_READY',
    'PLAYER_SURFACE_READY',
    'CANARY_READY',
  ])
  assert.deepEqual(release.semanticProviders, ['deepseek', 'qwen'])
  assert.equal(release.acquisitionCases, 1)
})

test('blocks semantic release for a mechanical, slow, fallback, or ungrounded recommendation', () => {
  for (const mutate of [
    (value) => { value.reports.recommendation.controls.explicitPreferenceEnumInjected = true },
    (value) => { value.reports.recommendation.results[0].totalLatencyMs = 30_001 },
    (value) => { value.reports.recommendation.results[0].fallbackUsed = true },
    (value) => { value.reports.recommendation.results[0].referenceGrounded = false },
    (value) => { value.reports.recommendation.results[0].targetSelected = false },
  ]) {
    const value = input()
    mutate(value)
    assert.throws(() => verifyConversationalRelease(value), /SEMANTIC_READY/)
  }
})

test('judges a configured model by measured release behavior instead of its name', () => {
  const value = input()
  value.reports.recommendation.results[1].model = 'qwen-plus'
  value.reports.teachingDialogue.results[2].model = 'qwen-plus-legacy-alias'
  value.reports.acquisition.results[0].model = 'qwen-plus'

  assert.equal(verifyConversationalRelease(value).releaseDecision, 'CANARY_READY')

  value.reports.recommendation.results[1].model = ''
  assert.throws(() => verifyConversationalRelease(value), /recommendation model is missing/)
})

test('blocks evidence release when discovery cannot become a validated PDF or citations escape scope', () => {
  const noPdf = input()
  noPdf.reports.acquisition.results[0].directPdfCandidateCount = 0
  assert.throws(() => verifyConversationalRelease(noPdf), /EVIDENCE_READY: .*direct PDF/)

  const wrongMime = input()
  wrongMime.reports.acquisition.results[0].mimeVerified = false
  assert.throws(() => verifyConversationalRelease(wrongMime), /EVIDENCE_READY: .*PDF validation/)

  const crossScope = input()
  crossScope.reports.answerEvidence.crossRulebookNegative.crossScopeEvidence = true
  assert.throws(() => verifyConversationalRelease(crossScope), /EVIDENCE_READY: .*escaped scope/)
})

test('rejects stale evidence, missing UI verification, and report fields that could retain hidden content', () => {
  const stale = input()
  stale.reports.acquisition.generatedAt = '2026-08-10T00:59:59.000Z'
  assert.throws(() => verifyConversationalRelease(stale), /DETERMINISTIC_READY: acquisition evidence is stale/)

  const noUi = input()
  noUi.playerSurfaceVerified = false
  assert.throws(() => verifyConversationalRelease(noUi), /PLAYER_SURFACE_READY/)

  const raw = input()
  raw.reports.recommendation.results[0].rawModelResponse = 'not allowed'
  assert.throws(() => verifyConversationalRelease(raw), /DETERMINISTIC_READY: .*not an allowed aggregate evidence field/)
})
