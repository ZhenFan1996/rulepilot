import assert from 'node:assert/strict'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import {
  corpusManifestIssues,
  localMarkdownTargets,
  maintainedDocumentation,
} from './verify-documentation.mjs'

test('extracts repository-local Markdown targets without treating web or anchors as files', () => {
  assert.deepEqual(localMarkdownTargets([
    '[local](../README.md#section)',
    '[web](https://example.com)',
    '[mail](mailto:test@example.com)',
    '[anchor](#local)',
  ].join('\n')), ['../README.md'])
})

test('discovers maintained Markdown without encoding phase names or dated history paths', async () => {
  const root = await mkdtemp(join(tmpdir(), 'rulepilot-docs-'))
  try {
    await mkdir(join(root, 'docs', 'roadmap', 'history'), { recursive: true })
    await mkdir(join(root, 'docs', 'learning', 'new-phase'), { recursive: true })
    await writeFile(join(root, 'README.md'), '# Root\n')
    await writeFile(join(root, 'AGENTS.md'), '# Agents\n')
    await writeFile(join(root, 'docs', 'roadmap', 'EXECUTION_STATE.md'), '# Arbitrary current state\n')
    await writeFile(join(root, 'docs', 'learning', 'new-phase', 'NOTE.md'), '# New note\n')
    await writeFile(join(root, 'docs', 'roadmap', 'history', 'old.md'), '# Archived\n')

    assert.deepEqual(maintainedDocumentation(root), [
      'AGENTS.md',
      'README.md',
      'docs/learning/new-phase/NOTE.md',
      'docs/roadmap/EXECUTION_STATE.md',
    ])
  } finally {
    await rm(root, { recursive: true, force: true })
  }
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
