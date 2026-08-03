import assert from 'node:assert/strict'
import test from 'node:test'

import { claimIssues, verifyRealRelease } from './verify-complete-agent-release.mjs'

test('requires all twelve complete-Agent claims with direct evidence fields', () => {
  const ids = [
    'goal-and-state', 'native-tool-choice', 'bounded-loop', 'role-scoped-tools', 'typed-observations',
    'trusted-scope', 'grounded-completion', 'provenance-context', 'recovery-fallback', 'evaluation',
    'product-completion', 'understandable-implementation',
  ]
  const matrix = { schemaVersion: 1, claims: ids.map((id) => ({ id, code: 'code', test: 'test', real: 'real', player: 'player' })) }
  assert.deepEqual(claimIssues(matrix), [])
  matrix.claims.pop()
  assert.match(claimIssues(matrix).join('\n'), /understandable-implementation/)
})

test('requires five player needs, five families, and two concrete providers', () => {
  const needs = ['START_PLAYING', 'RESOLVE_EXCEPTION', 'MATCH_TABLE_STATE', 'IDENTIFY_SYMBOL', 'ASK_NATURALLY']
  const input = {
    security: { hardInvariants: 'PASSED', coveredFamilies: ['a', 'b', 'c', 'd', 'e'] },
    baseline: { cases: needs.map((playerNeed) => ({ playerNeed, baseline: { terminalState: 'COMPLETE' } })) },
    providerReports: [
      { results: [
        { provider: 'deepseek', citationPublished: true },
        { provider: 'qwen', citationPublished: true },
      ] },
      { results: [
        { provider: 'deepseek', citationAccepted: true },
        { provider: 'qwen', citationAccepted: true },
      ] },
    ],
  }
  input.baseline.cases[0].taskKind = 'TEACHING'
  input.baseline.cases[0].baseline.evidenceStatuses = ['SUPPORTED']
  input.baseline.cases[1].taskKind = 'ANSWER'
  input.baseline.cases[1].baseline.terminalState = 'ANSWERED'
  input.baseline.cases[1].baseline.citationCount = 1
  assert.equal(verifyRealRelease(input).status, 'PASSED')

  input.providerReports[1].results = input.providerReports[1].results
    .map((result) => ({ ...result, citationAccepted: false }))
  assert.throws(() => verifyRealRelease(input), /Teaching evidence/)
  input.providerReports[1].results = [
    { provider: 'deepseek', citationAccepted: true },
    { provider: 'qwen', citationAccepted: true },
  ]
  input.baseline.cases[1].baseline.citationCount = 0
  assert.throws(() => verifyRealRelease(input), /Answer player journey/)
  input.baseline.cases[1].baseline.citationCount = 1
  input.providerReports = [{ results: [{ provider: 'qwen', citationPublished: true, citationAccepted: true }] }]
  assert.throws(() => verifyRealRelease(input), /two paid providers/)
})

test('requires every report to be generated during the current release invocation', () => {
  const generatedAt = '2026-08-03T01:00:01.000Z'
  const needs = ['START_PLAYING', 'RESOLVE_EXCEPTION', 'MATCH_TABLE_STATE', 'IDENTIFY_SYMBOL', 'ASK_NATURALLY']
  const input = {
    notBefore: '2026-08-03T01:00:00.000Z',
    now: new Date('2026-08-03T01:01:00.000Z'),
    security: { generatedAt, hardInvariants: 'PASSED', coveredFamilies: ['a', 'b', 'c', 'd', 'e'] },
    baseline: {
      generatedAt,
      cases: needs.map((playerNeed) => ({ playerNeed, baseline: { terminalState: 'COMPLETE' } })),
    },
    providerReports: [
      { generatedAt, results: [
        { provider: 'deepseek', citationPublished: true },
        { provider: 'qwen', citationPublished: true },
      ] },
      { generatedAt, results: [
        { provider: 'deepseek', citationAccepted: true },
        { provider: 'qwen', citationAccepted: true },
      ] },
    ],
  }
  input.baseline.cases[0].taskKind = 'TEACHING'
  input.baseline.cases[0].baseline.evidenceStatuses = ['SUPPORTED']
  input.baseline.cases[1].taskKind = 'ANSWER'
  input.baseline.cases[1].baseline.terminalState = 'ANSWERED'
  input.baseline.cases[1].baseline.citationCount = 1
  assert.equal(verifyRealRelease(input).status, 'PASSED')

  input.providerReports[0].generatedAt = '2026-08-02T23:59:59.000Z'
  assert.throws(() => verifyRealRelease(input), /stale/)

  input.providerReports[0].generatedAt = '2026-08-03T01:07:00.000Z'
  assert.throws(() => verifyRealRelease(input), /future-dated/)

  delete input.providerReports[0].generatedAt
  assert.throws(() => verifyRealRelease(input), /missing or invalid/)
})
