import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { evaluateProduct, loadEvaluationBundle } from './product-evaluation/evaluator.mjs'

const datasetPath = fileURLToPath(new URL('../examples/evaluation/lantern-relay/product-evaluation.json', import.meta.url))

async function fixture() {
  return structuredClone(await loadEvaluationBundle(datasetPath))
}

test('self-authored ordinary-player fixture passes every recorded gate', async () => {
  const report = evaluateProduct(await fixture())

  assert.equal(report.status, 'PASS')
  assert.equal(report.metrics.firstUsefulContentMs, 5000)
  assert.equal(report.metrics.completeBaseLessonMs, 42000)
  assert.equal(report.playerTasks.machinePassed, 4)
  assert.equal(report.playerTasks.playerCanDo, 4)
  assert.deepEqual(report.failureStages, [])
})

test('missing document-specific scoring concepts locate the failure in teaching', async () => {
  const bundle = await fixture()
  const ledger = bundle.lesson.sections.flatMap((section) => section.steps).find((step) => step.kind === 'LEDGER')
  ledger.text = 'Add the final values shown by the game.'

  const report = evaluateProduct(bundle)
  const scoring = report.playerTasks.results.find((task) => task.id === 'score-game')

  assert.equal(report.status, 'FAIL')
  assert.deepEqual(report.failureStages, ['TEACHING'])
  assert.equal(scoring.machineStatus, 'FAIL')
  assert.deepEqual(scoring.missingConcepts, ['positive-scoring', 'unlit-dock-penalty'])
})

test('an unperformed player walkthrough stays pending instead of becoming a false pass', async () => {
  const bundle = await fixture()
  bundle.dataset.tasks[1].playerAssessment = { result: 'NOT_RUN' }

  const report = evaluateProduct(bundle)

  assert.equal(report.status, 'NEEDS_REVIEW')
  assert.deepEqual(report.failureStages, [])
  assert.deepEqual(report.needsEvaluationStages, ['PLAYER'])
})

test('a visually rich dataset fails in the visual stage when no matching crop exists', async () => {
  const bundle = await fixture()
  bundle.dataset.visualEvaluation = {
    applicability: 'REQUIRED',
    checks: [{
      id: 'setup-layout',
      label: 'Use the setup diagram',
      coverageTagsAny: ['setup'],
      expectedPages: [2],
      termGroups: [['harbor board'], ['round marker']],
      playerAssessment: { result: 'HELPFUL', method: 'AUTHOR_WALKTHROUGH' },
    }],
  }

  const report = evaluateProduct(bundle)

  assert.equal(report.status, 'FAIL')
  assert.deepEqual(report.failureStages, ['VISUAL'])
  assert.equal(report.visuals.passed, 0)
  assert.equal(report.visuals.total, 1)
})

test('a visual benchmark stays pending until it has enough unique player-rated crops', async () => {
  const bundle = await fixture()
  bundle.lesson.sections[1].steps.push({
    heading: 'Study the setup', kind: 'VISUAL', text: 'Use the harbor board and the round marker.',
    sourcePages: [2], sourceChunkIds: ['visual-evidence'],
    visualFocus: { pageNumber: 2, label: 'Harbor board round marker', x: 100, y: 120, width: 400, height: 300 },
  })
  bundle.dataset.visualEvaluation = {
    applicability: 'REQUIRED', minimumRatedCrops: 2, minimumHelpfulPercent: 90,
    checks: [
      { id: 'setup-layout', label: 'Use the setup diagram', coverageTagsAny: ['setup'], expectedPages: [2],
        termGroups: [['harbor board'], ['round marker']], playerAssessment: { result: 'HELPFUL' } },
      { id: 'setup-repeat', label: 'Recheck the setup diagram', coverageTagsAny: ['setup'], expectedPages: [2],
        termGroups: [['harbor board'], ['round marker']], playerAssessment: { result: 'HELPFUL' } },
    ],
  }

  const report = evaluateProduct(bundle)

  assert.equal(report.status, 'NEEDS_REVIEW')
  assert.deepEqual(report.needsEvaluationStages, ['VISUAL'])
  assert.equal(report.visuals.rated, 1)
  assert.equal(report.visuals.helpfulPercent, 100)
})

test('a visual benchmark fails when enough rated crops fall below the helpfulness threshold', async () => {
  const bundle = await fixture()
  bundle.lesson.sections[1].steps.push(
    { heading: 'Study setup', kind: 'VISUAL', text: 'Use the harbor board and round marker.', sourcePages: [2], sourceChunkIds: ['visual-1'],
      visualFocus: { pageNumber: 2, label: 'Harbor board round marker', x: 100, y: 120, width: 400, height: 300 } },
    { heading: 'Study scoring', kind: 'VISUAL', text: 'Use the score track and final lantern.', sourcePages: [3], sourceChunkIds: ['visual-2'],
      visualFocus: { pageNumber: 3, label: 'Score track final lantern', x: 100, y: 120, width: 400, height: 300 } },
  )
  bundle.dataset.visualEvaluation = {
    applicability: 'REQUIRED', minimumRatedCrops: 2, minimumHelpfulPercent: 90,
    checks: [
      { id: 'setup-layout', label: 'Use the setup diagram', coverageTagsAny: ['setup'], expectedPages: [2],
        termGroups: [['harbor board'], ['round marker']], playerAssessment: { result: 'HELPFUL' } },
      { id: 'score-layout', label: 'Use the score diagram', coverageTagsAny: ['setup'], expectedPages: [3],
        termGroups: [['score track'], ['final lantern']], playerAssessment: { result: 'NOT_HELPFUL' } },
    ],
  }

  const report = evaluateProduct(bundle)

  assert.equal(report.status, 'FAIL')
  assert.deepEqual(report.failureStages, ['PLAYER', 'VISUAL'])
  assert.equal(report.visuals.rated, 2)
  assert.equal(report.visuals.helpfulPercent, 50)
})
