import assert from 'node:assert/strict'
import test from 'node:test'

import {
  collectReleaseTraceSummary,
  parseArguments,
  releaseTraceQuery,
  summarizeTraceSearch,
  traceCollectionFailed,
  workflowTerminalQuery,
} from './summarize-production-release-traces.mjs'

const RELEASE_ID = '0123456789abcdef0123456789abcdef01234567-42'
const TRACE_ID = '89abcdef0123456789abcdef01234567'

function options(overrides = {}) {
  return {
    tempoUrl: 'http://127.0.0.1:3200/',
    releaseId: RELEASE_ID,
    traceId: TRACE_ID,
    startEpochSeconds: 1_000,
    endEpochSeconds: 1_100,
    attempts: 3,
    retryDelayMs: 0,
    output: '/tmp/rulepilot-release-trace-summary-test.json',
    requireReleaseTrace: true,
    requireWorkflowTerminal: false,
    waitForWorkflowTerminal: true,
    ...overrides,
  }
}

function tempoTrace({ traceId, spanId, state, startedAt, durationNanos = '2000000000' }) {
  return {
    traceID: traceId,
    rootServiceName: 'rulepilot',
    rootTraceName: 'recommendation-react',
    spanSets: [{
      spans: [{
        spanID: spanId,
        name: 'recommendation-react',
        startTimeUnixNano: startedAt,
        durationNanos,
        attributes: [
          { key: 'service.version', value: { stringValue: RELEASE_ID } },
          { key: 'outcome', value: { stringValue: state } },
          { key: 'prompt', value: { stringValue: 'raw player text must stay private' } },
        ],
      }],
    }],
  }
}

test('accepts only an exact immutable release and a bounded loopback Tempo window', () => {
  const parsed = parseArguments([
    '--tempo-url', 'http://127.0.0.1:13200',
    '--release-id', RELEASE_ID,
    '--trace-id', TRACE_ID,
    '--start-epoch-seconds', '1000',
    '--end-epoch-seconds', '1100',
    '--output', '/tmp/summary.json',
    '--require-release-trace',
    '--wait-for-workflow-terminal',
  ])
  assert.equal(parsed.requireReleaseTrace, true)
  assert.equal(parsed.waitForWorkflowTerminal, true)
  assert.equal(parsed.traceId, TRACE_ID)

  for (const counterexample of [
    ['--tempo-url', 'https://tempo.example.com'],
    ['--release-id', 'main'],
    ['--trace-id', '0'.repeat(32)],
    ['--trace-id', 'ABCDEF0123456789abcdef0123456789'],
    ['--end-epoch-seconds', String(1_000 + 2 * 60 * 60 + 1)],
  ]) {
    const args = [
      '--tempo-url', 'http://127.0.0.1:13200',
      '--release-id', RELEASE_ID,
      '--trace-id', TRACE_ID,
      '--start-epoch-seconds', '1000',
      '--end-epoch-seconds', '1100',
      '--output', '/tmp/summary.json',
    ]
    const [option, value] = counterexample
    args[args.indexOf(option) + 1] = value
    assert.throws(() => parseArguments(args))
  }
})

test('scopes both TraceQL searches to the canary trace, production, and exact service version', () => {
  for (const query of [
    releaseTraceQuery(RELEASE_ID, TRACE_ID),
    workflowTerminalQuery(RELEASE_ID, TRACE_ID),
  ]) {
    assert.match(query, new RegExp(`trace:id = "${TRACE_ID}"`))
    assert.match(query, /resource\.service\.namespace = "rulepilot"/)
    assert.match(query, /resource\.deployment\.environment\.name = "production"/)
    assert.match(query, new RegExp(`resource\\.service\\.version = "${RELEASE_ID}"`))
  }
  assert.match(workflowTerminalQuery(RELEASE_ID, TRACE_ID), /span:name = "recommendation-react"/)
  assert.match(workflowTerminalQuery(RELEASE_ID, TRACE_ID), /span\.outcome =~/)
})

test('publishes only allow-listed business terminals and never raw Tempo identifiers or player text', () => {
  const summary = summarizeTraceSearch({
    releaseId: RELEASE_ID,
    traceId: TRACE_ID,
    startEpochSeconds: 1_000,
    endEpochSeconds: 1_100,
    attemptsUsed: 1,
    releaseTraces: [
      tempoTrace({ traceId: TRACE_ID, spanId: 'private-span-one', state: 'conversation', startedAt: '1000000000' }),
      tempoTrace({ traceId: TRACE_ID, spanId: 'private-span-two', state: 'needs_clarification', startedAt: '3000000000' }),
      tempoTrace({ traceId: 'f'.repeat(32), spanId: 'unrelated-span', state: 'error', startedAt: '4000000000' }),
    ],
    workflowTraces: [
      tempoTrace({ traceId: TRACE_ID, spanId: 'private-span-one', state: 'conversation', startedAt: '1000000000' }),
      tempoTrace({ traceId: TRACE_ID, spanId: 'private-span-two', state: 'needs_clarification', startedAt: '3000000000' }),
      tempoTrace({ traceId: 'f'.repeat(32), spanId: 'unrelated-span', state: 'error', startedAt: '4000000000' }),
    ],
  })

  assert.equal(summary.releaseTraceObserved, true)
  assert.equal(summary.traceId, TRACE_ID)
  assert.equal(summary.observedReleaseTraceCount, 1)
  assert.equal(summary.queryFailureCause, null)
  assert.equal(summary.businessWorkflowTerminal.observed, true)
  assert.equal(summary.businessWorkflowTerminal.state, 'needs_clarification')
  assert.equal(summary.businessWorkflowTerminal.latestDurationMs, 2_000)
  assert.deepEqual(summary.businessWorkflowTerminal.observedStateCounts, [
    { state: 'conversation', count: 1 },
    { state: 'needs_clarification', count: 1 },
  ])
  assert.doesNotMatch(JSON.stringify(summary), /private-span|unrelated-span|raw player text|prompt/)
})

test('waits for Tempo ingestion and reports the persisted terminal independently of canary assertions', async () => {
  let workflowQueries = 0
  const delays = []
  const releaseTrace = tempoTrace({
    traceId: TRACE_ID, spanId: 'release-span', state: 'conversation', startedAt: '1000000000',
  })
  const terminalTrace = tempoTrace({
    traceId: TRACE_ID, spanId: 'workflow-span', state: 'needs_clarification', startedAt: '2000000000',
  })

  const summary = await collectReleaseTraceSummary(options({ retryDelayMs: 17 }), {
    fetchImpl: async (url) => {
      const query = new URL(url).searchParams.get('q') ?? ''
      if (query.includes('span:name')) workflowQueries += 1
      const traces = query.includes('span:name')
        ? workflowQueries >= 2 ? [terminalTrace] : []
        : [releaseTrace]
      return new Response(JSON.stringify({ traces }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      })
    },
    sleep: async (milliseconds) => delays.push(milliseconds),
  })

  assert.equal(summary.queryOutcome, 'SUCCEEDED')
  assert.equal(summary.attemptsUsed, 2)
  assert.equal(summary.businessWorkflowTerminal.state, 'needs_clarification')
  assert.deepEqual(delays, [17])
})

test('returns sanitized typed causes when Tempo evidence is unavailable', async () => {
  const failures = [
    {
      expected: 'TEMPO_HTTP_ERROR',
      fetchImpl: async () => new Response('raw backend failure with player data', { status: 503 }),
    },
    {
      expected: 'TEMPO_RESPONSE_INVALID',
      fetchImpl: async () => new Response('raw invalid Tempo response with player data', { status: 200 }),
    },
    {
      expected: 'TUNNEL_UNAVAILABLE',
      fetchImpl: async () => { throw new TypeError('private tunnel details') },
    },
    {
      expected: 'TEMPO_QUERY_TIMEOUT',
      fetchImpl: async () => { throw Object.assign(new Error('private timeout details'), { name: 'TimeoutError' }) },
    },
  ]

  for (const failure of failures) {
    const summary = await collectReleaseTraceSummary(options({ attempts: 1 }), {
      fetchImpl: failure.fetchImpl,
      sleep: async () => {},
    })

    assert.equal(summary.queryOutcome, 'NOT_AVAILABLE')
    assert.equal(summary.queryFailureCause, failure.expected)
    assert.equal(summary.releaseTraceObserved, false)
    assert.equal(summary.businessWorkflowTerminal.state, 'NOT_OBSERVED')
    assert.doesNotMatch(JSON.stringify(summary), /raw|player data|private|tunnel details|timeout details/)
  }
})

test('diagnostic collection does not become a release gate unless a requirement is explicit', () => {
  const summary = summarizeTraceSearch({
    releaseId: RELEASE_ID,
    traceId: TRACE_ID,
    startEpochSeconds: 1_000,
    endEpochSeconds: 1_100,
    attemptsUsed: 3,
    queryOutcome: 'NOT_AVAILABLE',
    queryFailureCause: 'TUNNEL_UNAVAILABLE',
    releaseTraces: [],
    workflowTraces: [],
  })

  assert.equal(traceCollectionFailed(options({
    requireReleaseTrace: false,
    requireWorkflowTerminal: false,
  }), summary), false)
  assert.equal(traceCollectionFailed(options({ requireReleaseTrace: true }), summary), true)
  assert.equal(traceCollectionFailed(options({
    requireReleaseTrace: false,
    requireWorkflowTerminal: true,
  }), summary), true)
})

test('retains previously observed exact-release and terminal evidence across later query failures', async () => {
  let attempt = 0
  const releaseTrace = tempoTrace({
    traceId: TRACE_ID, spanId: 'release-span', state: 'conversation', startedAt: '1000000000',
  })
  const terminalTrace = tempoTrace({
    traceId: TRACE_ID, spanId: 'terminal-span', state: 'recommendations', startedAt: '2000000000',
  })

  const releasePreserved = await collectReleaseTraceSummary(options({ attempts: 2 }), {
    fetchImpl: async (url) => {
      const query = new URL(url).searchParams.get('q') ?? ''
      if (!query.includes('span:name')) attempt += 1
      if (attempt >= 2) throw new TypeError('private tunnel details')
      return new Response(JSON.stringify({ traces: query.includes('span:name') ? [] : [releaseTrace] }))
    },
    sleep: async () => {},
  })
  assert.equal(releasePreserved.releaseTraceObserved, true)
  assert.equal(releasePreserved.queryOutcome, 'SUCCEEDED')
  assert.equal(releasePreserved.queryFailureCause, 'TUNNEL_UNAVAILABLE')

  attempt = 0
  const terminalPreserved = await collectReleaseTraceSummary(options({ attempts: 2 }), {
    fetchImpl: async (url) => {
      const query = new URL(url).searchParams.get('q') ?? ''
      if (!query.includes('span:name')) attempt += 1
      if (attempt >= 2) throw new TypeError('private tunnel details')
      return new Response(JSON.stringify({ traces: query.includes('span:name') ? [terminalTrace] : [] }))
    },
    sleep: async () => {},
  })
  assert.equal(terminalPreserved.releaseTraceObserved, false)
  assert.equal(terminalPreserved.businessWorkflowTerminal.observed, true)
  assert.equal(terminalPreserved.businessWorkflowTerminal.state, 'recommendations')
  assert.equal(terminalPreserved.queryFailureCause, 'TUNNEL_UNAVAILABLE')
})
