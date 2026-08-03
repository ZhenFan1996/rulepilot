import assert from 'node:assert/strict'
import test from 'node:test'

import {
  corpusManifestIssues,
  executionStateIssues,
  localMarkdownTargets,
} from './verify-documentation.mjs'

test('extracts repository-local Markdown targets without treating web or anchors as files', () => {
  assert.deepEqual(localMarkdownTargets([
    '[local](../README.md#section)',
    '[web](https://example.com)',
    '[mail](mailto:test@example.com)',
    '[anchor](#local)',
  ].join('\n')), ['../README.md'])
})

test('accepts one bounded current task with an archived-history link', () => {
  const state = `# state

## Current task

P19-01 — docs

Status: IN_PROGRESS

[history](history/EXECUTION_STATE-through-2026-08-02.md)
`
  assert.deepEqual(executionStateIssues(state), { currentTask: 'P19-01', issues: [] })
})

test('accepts an explicitly complete phase with a done final task', () => {
  const state = `# state

Phase status: COMPLETE

## Current task

P19-09 — release

Status: DONE

[history](history/EXECUTION_STATE-through-2026-08-02.md)
`
  assert.deepEqual(executionStateIssues(state), { currentTask: 'P19-09', issues: [] })
})

test('accepts an incomplete phase whose current task is explicitly blocked', () => {
  const markdown = `# EXECUTION_STATE

Complete history: history/EXECUTION_STATE-through-2026-08-02.md

## Current phase

Phase status: IN_PROGRESS

## Current task

P19-10 — Release audit

Status: BLOCKED by the three-fix stop condition.
`

  assert.deepEqual(executionStateIssues(markdown).issues, [])
})

test('rejects ambiguous active state and an unbounded maintained log', () => {
  const state = [
    '## Current task',
    '',
    'P19-01 — docs',
    '',
    'Status: IN_PROGRESS',
    'Status: IN_PROGRESS',
    ...Array.from({ length: 251 }, () => 'history'),
  ].join('\n')
  const result = executionStateIssues(state)
  assert.equal(result.currentTask, 'P19-01')
  assert.match(result.issues.join('\n'), /one current IN_PROGRESS task or one current BLOCKED task/)
  assert.match(result.issues.join('\n'), /maximum is 250/)
  assert.match(result.issues.join('\n'), /does not link its preserved history/)
})

test('validates the minimum private corpus inventory contract without game vocabulary', () => {
  const valid = {
    qualifiedRulebooks: [{
      file: 'case-a.pdf',
      source: 'https://publisher.example/rules',
      sha256: 'a'.repeat(64),
    }],
  }
  assert.deepEqual(corpusManifestIssues(valid), [])

  const invalid = {
    qualifiedRulebooks: [
      { file: 'case-a.txt', source: 'http://example.com', sha256: 'short' },
      { file: 'case-a.txt', source: '', sha256: '' },
    ],
  }
  assert.match(corpusManifestIssues(invalid).join('\n'), /PDF file/)
  assert.match(corpusManifestIssues(invalid).join('\n'), /HTTPS source/)
  assert.match(corpusManifestIssues(invalid).join('\n'), /SHA-256/)
  assert.match(corpusManifestIssues(invalid).join('\n'), /duplicate/)
})
