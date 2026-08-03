import assert from 'node:assert/strict'
import test from 'node:test'

import {
  REQUIRED_CORPUS_FAMILIES,
  manifestIssues,
  protocolIssues,
  summarizeBaseline,
} from './evaluate-agent-baseline.mjs'

function manifestCase(family, index) {
  return {
    caseId: `rr-case-${index}`,
    family,
    taskKind: family === 'CROSS_LANGUAGE_QUESTION' ? 'ANSWER' : 'TEACHING',
    playerNeed: [
      'START_PLAYING',
      'RESOLVE_EXCEPTION',
      'MATCH_TABLE_STATE',
      'IDENTIFY_SYMBOL',
      'ASK_NATURALLY',
    ][index],
    sourceSha256: String(index).padStart(64, 'a'),
    baselineArtifact: `.local/agent-evaluation/case-${index}.json`,
    capabilityTags: ['EVIDENCE'],
  }
}

test('requires all five real-rulebook families with opaque ignored cases', () => {
  const valid = { schemaVersion: 1, cases: REQUIRED_CORPUS_FAMILIES.map(manifestCase) }
  assert.deepEqual(manifestIssues(valid), [])

  const invalid = { schemaVersion: 1, cases: [manifestCase('TEXT_LAYER', 1)] }
  assert.match(manifestIssues(invalid).join('\n'), /missing corpus family/)
})

test('requires the complete deterministic callable protocol suite', () => {
  const valid = {
    schemaVersion: 1,
    cases: [
      ['REQUIRED_TOOL', 'VALID_TOOL_CALL'],
      ['NO_TOOL', 'DIRECT_RESPONSE'],
      ['INVALID_ARGUMENT', 'REJECTED_BEFORE_EXECUTION'],
      ['TIMEOUT', 'BOUNDED_TERMINATION'],
      ['PROVIDER_FAILURE', 'DETERMINISTIC_FALLBACK'],
      ['TOOL_SELECTION', 'ONLY_ALLOW_LISTED_TOOL'],
      ['IRRELEVANT_TOOL', 'ZERO_TOOL_CALLS'],
      ['MISSING_PARAMETER', 'REJECTED_BEFORE_EXECUTION'],
      ['EXTRA_PARAMETER', 'REJECTED_BEFORE_EXECUTION'],
      ['MULTIPLE_CALLS', 'ORDERED_CORRELATED_RESULTS'],
      ['REPEATED_FAILURE', 'CIRCUIT_OPEN'],
    ].map(([scenario, expectedOutcome]) => ({ scenario, expectedOutcome })),
  }
  assert.deepEqual(protocolIssues(valid), [])
  assert.match(protocolIssues({ schemaVersion: 1, cases: [] }).join('\n'), /REQUIRED_TOOL/)
})

test('summarizes answer evidence without copying model prose', () => {
  assert.deepEqual(summarizeBaseline({ caseId: 'rr-answer', taskKind: 'ANSWER' }, {
    answer: {
      status: 'ANSWERED',
      confidence: 'HIGH',
      shortVerdict: 'copyrighted-like prose must not be copied',
      citations: [{ pageNumber: 2 }],
    },
  }), { terminalState: 'ANSWERED', confidence: 'HIGH', citationCount: 1 })
})

test('summarizes lesson measurements without titles or section text', () => {
  assert.deepEqual(summarizeBaseline({ caseId: 'rr-lesson', taskKind: 'TEACHING' }, {
    teaching: { activityCount: 8 },
    visual: { activityCount: 3 },
    result: {
      status: 'DRAFT_READY',
      sectionCount: 5,
      visualStepCount: 2,
      attemptElapsedSeconds: 42,
      evidenceStatuses: ['SUPPORTED', 'SUPPORTED'],
      sectionTitles: ['must not be copied'],
    },
  }), {
    terminalState: 'DRAFT_READY',
    sectionCount: 5,
    visualStepCount: 2,
    elapsedSeconds: 42,
    teachingActivityCount: 8,
    visualActivityCount: 3,
    evidenceStatuses: ['SUPPORTED'],
  })
})
