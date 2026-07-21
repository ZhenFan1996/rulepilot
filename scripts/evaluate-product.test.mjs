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
