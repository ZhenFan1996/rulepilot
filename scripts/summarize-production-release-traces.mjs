import { chmod, writeFile } from 'node:fs/promises'
import { pathToFileURL } from 'node:url'

const QUERY_LIMIT = 50
const MAX_WINDOW_SECONDS = 2 * 60 * 60
const MAX_RESPONSE_BYTES = 1024 * 1024
const TERMINAL_STATES = [
  'conversation',
  'needs_clarification',
  'recommendations',
  'no_match',
  'unavailable',
  'error',
]
const QUERY_FAILURE_CAUSES = new Set([
  'TUNNEL_UNAVAILABLE',
  'TEMPO_HTTP_ERROR',
  'TEMPO_RESPONSE_INVALID',
  'TEMPO_QUERY_TIMEOUT',
  'UNKNOWN',
])

class TempoQueryFailure extends Error {
  constructor(cause) {
    super('Production Tempo query failed')
    this.cause = cause
  }
}

function transportFailure(error) {
  return new TempoQueryFailure(
    error?.name === 'TimeoutError' || error?.name === 'AbortError'
      ? 'TEMPO_QUERY_TIMEOUT'
      : 'TUNNEL_UNAVAILABLE',
  )
}

function requiredValue(args, index, option) {
  const value = args[index + 1]
  if (!value || value.startsWith('--')) throw new Error(`${option} requires a value`)
  return value
}

export function parseArguments(args) {
  const options = {
    attempts: 8,
    retryDelayMs: 3_000,
    requireReleaseTrace: false,
    requireWorkflowTerminal: false,
    waitForWorkflowTerminal: false,
  }

  for (let index = 0; index < args.length; index += 1) {
    const option = args[index]
    if (option === '--require-release-trace') {
      options.requireReleaseTrace = true
    } else if (option === '--require-workflow-terminal') {
      options.requireWorkflowTerminal = true
      options.waitForWorkflowTerminal = true
    } else if (option === '--wait-for-workflow-terminal') {
      options.waitForWorkflowTerminal = true
    } else if (option === '--tempo-url') {
      options.tempoUrl = requiredValue(args, index, option)
      index += 1
    } else if (option === '--release-id') {
      options.releaseId = requiredValue(args, index, option)
      index += 1
    } else if (option === '--trace-id') {
      options.traceId = requiredValue(args, index, option)
      index += 1
    } else if (option === '--start-epoch-seconds') {
      options.startEpochSeconds = Number(requiredValue(args, index, option))
      index += 1
    } else if (option === '--end-epoch-seconds') {
      options.endEpochSeconds = Number(requiredValue(args, index, option))
      index += 1
    } else if (option === '--attempts') {
      options.attempts = Number(requiredValue(args, index, option))
      index += 1
    } else if (option === '--retry-delay-ms') {
      options.retryDelayMs = Number(requiredValue(args, index, option))
      index += 1
    } else if (option === '--output') {
      options.output = requiredValue(args, index, option)
      index += 1
    } else {
      throw new Error('unsupported option')
    }
  }

  validateOptions(options)
  return options
}

function validateOptions(options) {
  if (!/^[0-9a-f]{40}-[0-9]+(?:-[0-9]+)?$/.test(options.releaseId ?? '')) {
    throw new Error('release id must be immutable')
  }
  if (!/^[0-9a-f]{32}$/.test(options.traceId ?? '') || /^0{32}$/.test(options.traceId)) {
    throw new Error('trace id must be a non-zero lowercase W3C trace id')
  }
  if (!Number.isSafeInteger(options.startEpochSeconds) || options.startEpochSeconds <= 0
    || !Number.isSafeInteger(options.endEpochSeconds)
    || options.endEpochSeconds < options.startEpochSeconds
    || options.endEpochSeconds - options.startEpochSeconds > MAX_WINDOW_SECONDS) {
    throw new Error('trace window must be a bounded epoch-second range')
  }
  if (!Number.isSafeInteger(options.attempts) || options.attempts < 1 || options.attempts > 30) {
    throw new Error('attempt count is invalid')
  }
  if (!Number.isSafeInteger(options.retryDelayMs)
    || options.retryDelayMs < 0 || options.retryDelayMs > 10_000) {
    throw new Error('retry delay is invalid')
  }
  if (!options.output) throw new Error('output is required')

  let tempoUrl
  try {
    tempoUrl = new URL(options.tempoUrl)
  } catch {
    throw new Error('Tempo URL is invalid')
  }
  if (tempoUrl.protocol !== 'http:'
    || !['127.0.0.1', 'localhost'].includes(tempoUrl.hostname)
    || tempoUrl.username || tempoUrl.password || tempoUrl.search || tempoUrl.hash
    || !['', '/'].includes(tempoUrl.pathname)) {
    throw new Error('Tempo must be reached through an unauthenticated loopback tunnel')
  }
  options.tempoUrl = tempoUrl.toString()
}

function releaseScope(releaseId, traceId) {
  return `trace:id = "${traceId}"`
    + ` && resource.service.namespace = "rulepilot"`
    + ` && resource.deployment.environment.name = "production"`
    + ` && resource.service.version = "${releaseId}"`
}

export function releaseTraceQuery(releaseId, traceId) {
  return `{ ${releaseScope(releaseId, traceId)} }`
}

export function workflowTerminalQuery(releaseId, traceId) {
  return `{ ${releaseScope(releaseId, traceId)}`
    + ' && span:name = "recommendation-react"'
    + ` && span.outcome =~ "${TERMINAL_STATES.join('|')}" }`
}

async function queryTempo({ tempoUrl, query, startEpochSeconds, endEpochSeconds, fetchImpl }) {
  const url = new URL('/api/search', tempoUrl)
  url.searchParams.set('q', query)
  url.searchParams.set('limit', String(QUERY_LIMIT))
  url.searchParams.set('start', String(startEpochSeconds))
  url.searchParams.set('end', String(endEpochSeconds))

  let response
  try {
    response = await fetchImpl(url, {
      headers: { accept: 'application/json' },
      signal: AbortSignal.timeout(10_000),
    })
  } catch (error) {
    throw transportFailure(error)
  }
  if (!response.ok) throw new TempoQueryFailure('TEMPO_HTTP_ERROR')
  let body
  try {
    body = await response.text()
  } catch (error) {
    throw transportFailure(error)
  }
  if (Buffer.byteLength(body) > MAX_RESPONSE_BYTES) {
    throw new TempoQueryFailure('TEMPO_RESPONSE_INVALID')
  }

  let payload
  try {
    payload = JSON.parse(body)
  } catch {
    throw new TempoQueryFailure('TEMPO_RESPONSE_INVALID')
  }
  if (!Array.isArray(payload.traces)) throw new TempoQueryFailure('TEMPO_RESPONSE_INVALID')
  return payload.traces
}

function attributeValue(span, key) {
  const attribute = Array.isArray(span.attributes)
    ? span.attributes.find((candidate) => candidate?.key === key)
    : null
  return attribute?.value?.stringValue
}

function canonicalTempoResponseTraceId(value) {
  return typeof value === 'string' && /^[0-9a-f]{1,32}$/.test(value)
    ? value.padStart(32, '0')
    : null
}

function matchesCanaryTrace(trace, traceId) {
  return canonicalTempoResponseTraceId(trace?.traceID) === traceId
}

function matchedSpans(trace) {
  const spanSets = Array.isArray(trace.spanSets)
    ? trace.spanSets
    : trace.spanSet ? [trace.spanSet] : []
  return spanSets.flatMap((spanSet) => Array.isArray(spanSet?.spans) ? spanSet.spans : [])
}

function terminalEvents(traces) {
  const events = []
  for (const [traceIndex, trace] of traces.entries()) {
    for (const [spanIndex, span] of matchedSpans(trace).entries()) {
      const state = attributeValue(span, 'outcome')
      if (span?.name !== 'recommendation-react' || !TERMINAL_STATES.includes(state)) continue
      const start = /^\d+$/.test(span.startTimeUnixNano ?? '')
        ? BigInt(span.startTimeUnixNano)
        : 0n
      const duration = /^\d+$/.test(span.durationNanos ?? '')
        ? BigInt(span.durationNanos)
        : 0n
      events.push({
        state,
        start,
        duration,
        traceKey: canonicalTempoResponseTraceId(trace.traceID) ?? `trace-${traceIndex}`,
        spanKey: typeof span.spanID === 'string' ? span.spanID : `span-${spanIndex}`,
      })
    }
  }
  return events
    .filter((event, index, all) => all.findIndex((candidate) =>
      candidate.traceKey === event.traceKey && candidate.spanKey === event.spanKey) === index)
    .sort((left, right) => left.start < right.start ? -1 : left.start > right.start ? 1 : 0)
}

function safeDurationMillis(durationNanos) {
  const milliseconds = Number(durationNanos / 1_000_000n)
  return Number.isSafeInteger(milliseconds) ? milliseconds : null
}

export function summarizeTraceSearch({
  releaseId,
  traceId,
  startEpochSeconds,
  endEpochSeconds,
  releaseTraces,
  workflowTraces,
  attemptsUsed,
  queryOutcome = 'SUCCEEDED',
  queryFailureCause = null,
}) {
  if (queryFailureCause !== null && !QUERY_FAILURE_CAUSES.has(queryFailureCause)) {
    throw new Error('query failure cause is invalid')
  }
  const exactReleaseTraces = releaseTraces.filter((trace) => matchesCanaryTrace(trace, traceId))
  const exactWorkflowTraces = workflowTraces.filter((trace) => matchesCanaryTrace(trace, traceId))
  const events = terminalEvents(exactWorkflowTraces)
  const latest = events.at(-1) ?? null
  const uniqueReleaseTraces = new Set(exactReleaseTraces
    .map((trace) => canonicalTempoResponseTraceId(trace.traceID)))
  const uniqueWorkflowTraces = new Set(events.map((event) => event.traceKey))
  const stateCounts = TERMINAL_STATES
    .map((state) => ({ state, count: events.filter((event) => event.state === state).length }))
    .filter(({ count }) => count > 0)

  return {
    schemaVersion: 1,
    releaseId,
    traceId,
    window: { startEpochSeconds, endEpochSeconds },
    queryOutcome,
    queryFailureCause,
    releaseTraceObserved: uniqueReleaseTraces.size > 0,
    observedReleaseTraceCount: uniqueReleaseTraces.size,
    resultLimitReached: exactReleaseTraces.length >= QUERY_LIMIT || exactWorkflowTraces.length >= QUERY_LIMIT,
    attemptsUsed,
    businessWorkflowTerminal: {
      workflow: 'recommendation',
      observed: latest !== null,
      state: latest?.state ?? 'NOT_OBSERVED',
      latestDurationMs: latest ? safeDurationMillis(latest.duration) : null,
      observedTraceCount: uniqueWorkflowTraces.size,
      observedStateCounts: stateCounts,
    },
  }
}

function unavailableSummary(options, attemptsUsed, queryFailureCause = null) {
  return summarizeTraceSearch({
    releaseId: options.releaseId,
    traceId: options.traceId,
    startEpochSeconds: options.startEpochSeconds,
    endEpochSeconds: options.endEpochSeconds,
    releaseTraces: [],
    workflowTraces: [],
    attemptsUsed,
    queryOutcome: 'NOT_AVAILABLE',
    queryFailureCause,
  })
}

function sanitizedFailureCause(error) {
  return error instanceof TempoQueryFailure && QUERY_FAILURE_CAUSES.has(error.cause)
    ? error.cause
    : 'UNKNOWN'
}

export async function collectReleaseTraceSummary(options, dependencies = {}) {
  const fetchImpl = dependencies.fetchImpl ?? fetch
  const sleep = dependencies.sleep ?? ((milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)))
  let lastSummary = unavailableSummary(options, 0)
  let observedReleaseTraces = []
  let observedWorkflowTraces = []

  for (let attempt = 1; attempt <= options.attempts; attempt += 1) {
    const [releaseResult, workflowResult] = await Promise.allSettled([
      queryTempo({
        ...options,
        query: releaseTraceQuery(options.releaseId, options.traceId),
        fetchImpl,
      }),
      queryTempo({
        ...options,
        query: workflowTerminalQuery(options.releaseId, options.traceId),
        fetchImpl,
      }),
    ])
    if (releaseResult.status === 'fulfilled' && releaseResult.value.length > 0) {
      observedReleaseTraces = releaseResult.value
    }
    if (workflowResult.status === 'fulfilled' && workflowResult.value.length > 0) {
      observedWorkflowTraces = workflowResult.value
    }

    const failedResult = [releaseResult, workflowResult]
      .find((result) => result.status === 'rejected')
    const queryFailureCause = failedResult?.status === 'rejected'
      ? sanitizedFailureCause(failedResult.reason)
      : null
    const releaseTraceAlreadyObserved = observedReleaseTraces
      .some((trace) => matchesCanaryTrace(trace, options.traceId))
    lastSummary = summarizeTraceSearch({
      releaseId: options.releaseId,
      traceId: options.traceId,
      startEpochSeconds: options.startEpochSeconds,
      endEpochSeconds: options.endEpochSeconds,
      releaseTraces: observedReleaseTraces,
      workflowTraces: observedWorkflowTraces,
      attemptsUsed: attempt,
      queryOutcome: releaseTraceAlreadyObserved
        ? 'SUCCEEDED'
        : queryFailureCause === null ? 'NO_RELEASE_TRACE' : 'NOT_AVAILABLE',
      queryFailureCause,
    })
    if (lastSummary.releaseTraceObserved
      && (!options.waitForWorkflowTerminal || lastSummary.businessWorkflowTerminal.observed)) {
      return lastSummary
    }

    if (attempt < options.attempts) await sleep(options.retryDelayMs)
  }
  return lastSummary
}

async function writeSummary(path, summary) {
  await writeFile(path, `${JSON.stringify(summary, null, 2)}\n`, { mode: 0o600 })
  await chmod(path, 0o600)
}

export function traceCollectionFailed(options, summary) {
  return (options.requireReleaseTrace && !summary.releaseTraceObserved)
    || (options.requireWorkflowTerminal && !summary.businessWorkflowTerminal.observed)
}

async function main() {
  let options
  try {
    options = parseArguments(process.argv.slice(2))
  } catch {
    console.error('Production Tempo summary input is invalid.')
    process.exitCode = 2
    return
  }

  const summary = await collectReleaseTraceSummary(options)
  await writeSummary(options.output, summary)
  console.log(JSON.stringify(summary))

  if (traceCollectionFailed(options, summary)) {
    process.exitCode = 1
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main()
}
