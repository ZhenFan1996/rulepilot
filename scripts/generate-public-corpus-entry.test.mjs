import test from 'node:test'
import assert from 'node:assert/strict'
import {
  parseArguments,
  needsBggCatalog,
  selectEntry,
  selectReusableDocument,
  slugify,
  summarizeLesson,
  summarizeRunProgress,
  resetGeneratedLessonStateForPlanRefresh,
} from './generate-public-corpus-entry.mjs'

test('selects one qualified title without accepting excluded or fuzzy entries', () => {
  const manifest = {
    qualifiedRulebooks: [{ title: 'Point Salad', file: 'point-salad.pdf' }],
    excluded: [{ title: 'Point Salad FAQ' }],
  }

  assert.equal(selectEntry(manifest, 'point salad').file, 'point-salad.pdf')
  assert.throws(() => selectEntry(manifest, 'Point'), /not found/)
  assert.throws(() => selectEntry(manifest, 'Point Salad FAQ'), /not found/)
})

test('uses BGG catalog metadata only when an official publisher cover is unavailable', () => {
  assert.equal(needsBggCatalog({ publisherCover: 'https://publisher.example/cover.png', bggId: 123 }), false)
  assert.equal(needsBggCatalog({ publisherCover: null, bggId: 123 }), true)
  assert.equal(needsBggCatalog({ publisherCover: null, bggId: null }), false)
})

test('parses bounded local-run options and creates stable report slugs', () => {
  assert.deepEqual(parseArguments([
    '--title', "Atelier: The Painter's Studio",
    '--timeout-minutes', '15',
    '--teaching', 'deepseek',
    '--visual', 'qwen',
    '--restart',
    '--refresh-plan',
  ]), {
    title: "Atelier: The Painter's Studio",
    timeoutMinutes: 15,
    teaching: 'deepseek',
    visual: 'qwen',
    restart: true,
    refreshPlan: true,
  })
  assert.equal(slugify("Atelier: The Painter's Studio"), 'atelier-the-painter-s-studio')
})

test('reduces a large run response to useful bounded progress', () => {
  assert.equal(summarizeRunProgress({
    run: { state: 'RETRIEVING' },
    budget: { usedModelCalls: 4, maxModelCalls: 36, usedToolCalls: 19, maxToolCalls: 72 },
    activities: [
      { operation: 'searchRuleEvidence|1', outcome: 'SUCCEEDED' },
      { operation: 'composeTeachingSection|4', outcome: 'RUNNING' },
    ],
  }), 'RETRIEVING · 模型 4/36 · 工具 19/72 · 活动 2 · 当前 composeTeachingSection|4')
})

test('summarizes player-visible lesson density and visual grounding', () => {
  const summary = summarizeLesson({
    id: 'lesson-1',
    status: 'DRAFT_READY',
    generatorVersion: 'v1',
    sections: [
      {
        title: '设置',
        evidenceStatus: 'SUPPORTED',
        steps: [{ visualFocus: { pageNumber: 2 } }, { visualFocus: null }],
      },
      {
        title: '算分',
        evidenceStatus: 'CITED_DRAFT',
        steps: [{ visualFocus: null }],
      },
    ],
  })

  assert.deepEqual(summary, {
    lessonId: 'lesson-1',
    status: 'DRAFT_READY',
    generatorVersion: 'v1',
    sectionCount: 2,
    stepCount: 3,
    visualStepCount: 1,
    evidenceStatuses: ['SUPPORTED', 'CITED_DRAFT'],
    sectionTitles: ['设置', '算分'],
  })
})

test('keeps the reusable source checkpoint while refreshing the generated lesson state', () => {
  const refreshed = resetGeneratedLessonStateForPlanRefresh({
    title: 'Example Game',
    source: { sha256: 'checksum' },
    catalog: { editionId: 'edition-1' },
    document: { id: 'document-1', versionId: 'version-1', status: 'READY' },
    preparation: { runId: 'prepare-old', state: 'COMPLETED' },
    plan: { id: 'plan-old' },
    teaching: { runId: 'teach-old', state: 'COMPLETED' },
    visual: { runId: 'visual-old', state: 'COMPLETED' },
    result: { lessonId: 'lesson-old', status: 'DRAFT_READY' },
  })

  assert.deepEqual(refreshed, {
    title: 'Example Game',
    source: { sha256: 'checksum' },
    catalog: { editionId: 'edition-1' },
    document: { id: 'document-1', versionId: 'version-1', status: 'READY' },
  })
})

test('reuses an assigned document with the same official source and checksum', () => {
  const entry = { title: 'Custom Heroes', source: 'https://publisher.example/custom-heroes' }
  const unassigned = {
    document: {
      id: 'duplicate',
      title: 'Custom Heroes Rules',
      gameEditionId: null,
      officialSourceUrl: entry.source,
    },
    latestVersion: { id: 'duplicate-version', checksum: 'same-checksum', status: 'READY' },
  }
  const assigned = {
    document: {
      id: 'original',
      title: 'Custom Heroes Rules',
      gameEditionId: 'edition-1',
      officialSourceUrl: entry.source,
    },
    latestVersion: { id: 'original-version', checksum: 'same-checksum', status: 'READY' },
  }

  assert.equal(selectReusableDocument([
    unassigned,
    assigned,
    { ...assigned, latestVersion: { ...assigned.latestVersion, checksum: 'other-checksum' } },
  ], entry, 'same-checksum').document.id, 'original')
  assert.equal(selectReusableDocument([unassigned], entry, 'other-checksum'), null)
})
