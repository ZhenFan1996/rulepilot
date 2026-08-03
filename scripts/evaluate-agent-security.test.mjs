import assert from 'node:assert/strict'
import test from 'node:test'

import { REQUIRED_CORPUS_FAMILIES } from './evaluate-agent-baseline.mjs'
import { evaluateAgentSecurity } from './evaluate-agent-security.mjs'

function fixture() {
  const cases = REQUIRED_CORPUS_FAMILIES.map((family, index) => ({
    caseId: `rr-case-${index}`,
    family,
    taskKind: index === 4 ? 'ANSWER' : 'TEACHING',
    playerNeed: ['START_PLAYING', 'RESOLVE_EXCEPTION', 'MATCH_TABLE_STATE', 'IDENTIFY_SYMBOL', 'ASK_NATURALLY'][index],
    sourceSha256: String(index).padStart(64, 'a'),
    baselineArtifact: `.local/case-${index}.json`,
    capabilityTags: ['EVIDENCE'],
  }))
  const protocolCases = [
    ['REQUIRED_TOOL', 'VALID_TOOL_CALL'], ['NO_TOOL', 'DIRECT_RESPONSE'],
    ['INVALID_ARGUMENT', 'REJECTED_BEFORE_EXECUTION'], ['TIMEOUT', 'BOUNDED_TERMINATION'],
    ['PROVIDER_FAILURE', 'DETERMINISTIC_FALLBACK'], ['TOOL_SELECTION', 'ONLY_ALLOW_LISTED_TOOL'],
    ['IRRELEVANT_TOOL', 'ZERO_TOOL_CALLS'], ['MISSING_PARAMETER', 'REJECTED_BEFORE_EXECUTION'],
    ['EXTRA_PARAMETER', 'REJECTED_BEFORE_EXECUTION'], ['MULTIPLE_CALLS', 'ORDERED_CORRELATED_RESULTS'],
    ['REPEATED_FAILURE', 'CIRCUIT_OPEN'],
  ].map(([scenario, expectedOutcome]) => ({ scenario, expectedOutcome }))
  const bounded = (caseId) => ({ caseId, toolCalls: 1, modelCalls: 2, withinLatencyBudget: true })
  return {
    manifest: { schemaVersion: 1, cases },
    protocol: { schemaVersion: 1, cases: protocolCases },
    reports: {
      native: { schemaVersion: 1, results: cases.slice(0, 2).map((case_) => ({
        ...bounded(case_.caseId), nativeLoopStatus: 'COMPLETED', invalidArgumentRejected: true,
        timeoutFallback: true, providerFallback: true,
      })) },
      answer: { schemaVersion: 1, results: [{ ...bounded(cases[4].caseId), citationPublished: true,
        directAdditionalModelCalls: 0 }], crossRulebookNegative: { crossScopeEvidence: false } },
      teaching: { schemaVersion: 1, results: [{ ...bounded(cases[1].caseId), citationAccepted: true,
        expectedCoverageAdded: true, directAdditionalModelCalls: 0 }],
        crossRulebookNegative: { crossScopeEvidence: false } },
      visual: { schemaVersion: 1, results: cases.slice(2, 4).map((case_) => ({
        ...bounded(case_.caseId), mechanicalRuleAuthority: false, compactCrop: true, fallbackCalls: 0,
      })), controls: { inventedPageRejected: true, crossScopeMediaCount: 0 } },
      context: { schemaVersion: 1, results: [bounded(cases[0].caseId)], controls: {
        priorAnswerIsEvidence: false, staleSchemaRejectedBeforeToolExecution: true,
        providerDowngradeKeepsDeterministicEvidence: true,
      } },
    },
    adversarialVerified: true,
  }
}

test('accepts all five real families within hard call, latency, evidence, and scope invariants', () => {
  const result = evaluateAgentSecurity(fixture())
  assert.equal(result.coveredFamilies.length, 5)
  assert.equal(result.hardInvariants, 'PASSED')
})

test('rejects a duplicated provider fallback or missing adversarial gate', () => {
  const duplicate = fixture()
  duplicate.reports.visual.results[0].fallbackCalls = 1
  assert.throws(() => evaluateAgentSecurity(duplicate), /duplicated provider fallback/)
  const unverified = fixture()
  unverified.adversarialVerified = false
  assert.throws(() => evaluateAgentSecurity(unverified), /adversarial/)
})
