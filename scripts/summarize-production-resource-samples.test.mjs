import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { promisify } from 'node:util'

import {
  collectResourceEvidence,
  parseArguments,
  parseResourceSamples,
  parseRuntimeState,
  summarizeResourceEvidence,
} from './summarize-production-resource-samples.mjs'

const RELEASE_ID = '0123456789abcdef0123456789abcdef01234567-42'
const execFileAsync = promisify(execFile)
const INSTANCE_IDS = {
  api: 'a'.repeat(64),
  tempo: 'b'.repeat(64),
  worker: 'c'.repeat(64),
}

function stateRows(overrides = {}) {
  return Object.entries(INSTANCE_IDS).map(([service, defaultId]) => {
    const state = overrides[service] ?? {}
    return [
      service,
      state.instanceId ?? defaultId,
      String(state.restartCount ?? 0),
      String(state.oomKilled ?? false),
      String(state.running ?? true),
    ].join('\t')
  }).join('\n')
}

function sampleRows() {
  return [
    '1000\t4194304\t1048576\tapi\t42.50%\t850MiB / 1.172GiB',
    '1000\t4194304\t1048576\tworker\t8.00%\t350MiB / 560MiB',
    '1000\t4194304\t1048576\ttempo\t4.25%\t180MiB / 320MiB',
    '1005\t4194304\t786432\tapi\t81.75%\t900MiB / 1.172GiB',
    '1005\t4194304\t786432\tworker\t11.00%\t360MiB / 560MiB',
    '1005\t4194304\t786432\ttempo\t7.50%\t210MiB / 320MiB',
  ].join('\n')
}

test('accepts only exact release-scoped bounded resource inputs', () => {
  const parsed = parseArguments([
    '--release-id', RELEASE_ID,
    '--baseline', '/tmp/baseline.tsv',
    '--samples', '/tmp/samples.tsv',
    '--final', '/tmp/final.tsv',
    '--output', '/tmp/summary.json',
    '--fail-on-runtime-reset',
  ])
  assert.equal(parsed.failOnRuntimeReset, true)
  assert.throws(() => parseArguments([
    '--release-id', 'main',
    '--baseline', '/tmp/baseline.tsv',
    '--samples', '/tmp/samples.tsv',
    '--final', '/tmp/final.tsv',
    '--output', '/tmp/summary.json',
  ]))
})

test('summarizes four-GiB host headroom and per-service peaks without hard memory thresholds', () => {
  const baseline = parseRuntimeState(stateRows())
  const finalState = parseRuntimeState(stateRows())
  const samples = parseResourceSamples(sampleRows())
  const summary = summarizeResourceEvidence({ releaseId: RELEASE_ID, baseline, finalState, samples })

  assert.equal(summary.collectionOutcome, 'SUCCEEDED')
  assert.equal(summary.host.totalMemoryMiB, 4096)
  assert.equal(summary.host.minimumAvailableMemoryMiB, 768)
  assert.equal(summary.host.minimumAvailableMemoryPercent, 18.75)
  assert.equal(summary.sampleTimestampCount, 2)
  assert.equal(summary.safety.passed, true)
  assert.deepEqual(summary.services.find(({ service }) => service === 'api'), {
    service: 'api',
    presentAtBaseline: true,
    presentAtEnd: true,
    sampleCount: 2,
    peakCpuPercent: 81.75,
    peakMemoryMiB: 900,
    observedMemoryLimitMiB: 1200.13,
    baselineRestartCount: 0,
    finalRestartCount: 0,
    restartDelta: 0,
    restartCounterRegressed: false,
    instanceChanged: false,
    oomKilledAtBaseline: false,
    oomKilledAtEnd: false,
    runningAtEnd: true,
  })
  assert.doesNotMatch(JSON.stringify(summary), new RegExp(INSTANCE_IDS.api))

  const diagnosticPressure = summarizeResourceEvidence({
    releaseId: RELEASE_ID,
    baseline,
    finalState,
    samples: parseResourceSamples(sampleRows()
      .replaceAll('\t1048576\t', '\t1\t')
      .replaceAll('\t786432\t', '\t1\t')
      .replace('api\t81.75%\t900MiB', 'api\t9999.00%\t1199MiB')),
  })
  assert.equal(diagnosticPressure.host.minimumAvailableMemoryMiB, 0)
  assert.equal(diagnosticPressure.services.find(({ service }) => service === 'api').peakCpuPercent, 9999)
  assert.equal(diagnosticPressure.safety.passed, true)
})

test('fails the runtime safety boundary only for OOM, restart delta, or instance replacement', () => {
  const samples = parseResourceSamples(sampleRows())
  for (const finalRows of [
    stateRows({ api: { restartCount: 1 } }),
    stateRows({ worker: { oomKilled: true } }),
    stateRows({ tempo: { instanceId: 'd'.repeat(64) } }),
  ]) {
    const summary = summarizeResourceEvidence({
      releaseId: RELEASE_ID,
      baseline: parseRuntimeState(stateRows()),
      finalState: parseRuntimeState(finalRows),
      samples,
    })
    assert.equal(summary.safety.evaluated, true)
    assert.equal(summary.safety.passed, false)
    assert.equal(summary.safety.oomObserved || summary.safety.runtimeResetObserved, true)
  }
})

test('localizes missing resource evidence without inventing a healthy result', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'rulepilot-resource-summary-'))
  try {
    const summary = await collectResourceEvidence({
      releaseId: RELEASE_ID,
      baseline: join(directory, 'missing-baseline.tsv'),
      samples: join(directory, 'missing-samples.tsv'),
      final: join(directory, 'missing-final.tsv'),
    })
    assert.equal(summary.collectionOutcome, 'NOT_AVAILABLE')
    assert.equal(summary.failureCause, 'MISSING_BASELINE')
    assert.equal(summary.safety.evaluated, false)
    assert.equal(summary.safety.passed, null)

    await writeFile(join(directory, 'baseline.tsv'), stateRows())
    await writeFile(join(directory, 'final.tsv'), stateRows())
    const noSamples = await collectResourceEvidence({
      releaseId: RELEASE_ID,
      baseline: join(directory, 'baseline.tsv'),
      samples: join(directory, 'missing-samples.tsv'),
      final: join(directory, 'final.tsv'),
    })
    assert.equal(noSamples.collectionOutcome, 'NOT_AVAILABLE')
    assert.equal(noSamples.failureCause, 'NO_SAMPLES')
    assert.equal(noSamples.safety.passed, true)
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})

test('writes sanitized evidence before the CLI fails a runtime reset', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'rulepilot-resource-gate-'))
  try {
    const baseline = join(directory, 'baseline.tsv')
    const samples = join(directory, 'samples.tsv')
    const finalState = join(directory, 'final.tsv')
    const output = join(directory, 'summary.json')
    await Promise.all([
      writeFile(baseline, stateRows()),
      writeFile(samples, sampleRows()),
      writeFile(finalState, stateRows({ api: { restartCount: 1 } })),
    ])

    await assert.rejects(
      execFileAsync(process.execPath, [
        new URL('./summarize-production-resource-samples.mjs', import.meta.url).pathname,
        '--release-id', RELEASE_ID,
        '--baseline', baseline,
        '--samples', samples,
        '--final', finalState,
        '--fail-on-runtime-reset',
        '--output', output,
      ]),
      (error) => error.code === 1,
    )
    const summary = JSON.parse(await readFile(output, 'utf8'))
    assert.equal(summary.safety.passed, false)
    assert.equal(summary.services.find(({ service }) => service === 'api').restartDelta, 1)
    assert.doesNotMatch(JSON.stringify(summary), new RegExp(INSTANCE_IDS.api))
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})
