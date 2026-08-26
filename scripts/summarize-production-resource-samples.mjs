import { chmod, readFile, stat, writeFile } from 'node:fs/promises'
import { pathToFileURL } from 'node:url'

const MAX_INPUT_BYTES = 2 * 1024 * 1024
const MIB = 1024 * 1024
const ALLOWED_SERVICES = new Set([
  'api',
  'frontend',
  'gateway',
  'grafana',
  'minio',
  'postgres',
  'prometheus',
  'rabbitmq',
  'redis',
  'tempo',
  'worker',
])
const REQUIRED_SERVICES = ['api', 'tempo', 'worker']

function requiredValue(args, index, option) {
  const value = args[index + 1]
  if (!value || value.startsWith('--')) throw new Error(`${option} requires a value`)
  return value
}

export function parseArguments(args) {
  const options = { failOnRuntimeReset: false }
  for (let index = 0; index < args.length; index += 1) {
    const option = args[index]
    if (option === '--fail-on-runtime-reset') {
      options.failOnRuntimeReset = true
    } else if (option === '--release-id') {
      options.releaseId = requiredValue(args, index, option)
      index += 1
    } else if (option === '--baseline') {
      options.baseline = requiredValue(args, index, option)
      index += 1
    } else if (option === '--samples') {
      options.samples = requiredValue(args, index, option)
      index += 1
    } else if (option === '--final') {
      options.final = requiredValue(args, index, option)
      index += 1
    } else if (option === '--output') {
      options.output = requiredValue(args, index, option)
      index += 1
    } else {
      throw new Error('unsupported option')
    }
  }

  if (!/^[0-9a-f]{40}-[0-9]+(?:-[0-9]+)?$/.test(options.releaseId ?? '')) {
    throw new Error('release id must be immutable')
  }
  for (const field of ['baseline', 'samples', 'final', 'output']) {
    if (!options[field]) throw new Error(`${field} is required`)
  }
  return options
}

async function readBounded(path) {
  try {
    const metadata = await stat(path)
    if (!metadata.isFile() || metadata.size > MAX_INPUT_BYTES) return null
    return await readFile(path, 'utf8')
  } catch (error) {
    if (error?.code === 'ENOENT') return null
    throw error
  }
}

function parseUnsignedInteger(value, field) {
  if (!/^\d+$/.test(value)) throw new Error(`${field} is invalid`)
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed)) throw new Error(`${field} is invalid`)
  return parsed
}

function parseBoolean(value, field) {
  if (value === 'true') return true
  if (value === 'false') return false
  throw new Error(`${field} is invalid`)
}

export function parseRuntimeState(input) {
  const states = new Map()
  for (const line of input.trim().split('\n').filter(Boolean)) {
    const fields = line.split('\t')
    if (fields.length !== 5) throw new Error('runtime state row is invalid')
    const [service, instanceId, restartCount, oomKilled, running] = fields
    if (!ALLOWED_SERVICES.has(service) || states.has(service)) {
      throw new Error('runtime service is invalid')
    }
    if (!/^[0-9a-f]{12,64}$/.test(instanceId)) throw new Error('runtime instance is invalid')
    states.set(service, {
      service,
      instanceId,
      restartCount: parseUnsignedInteger(restartCount, 'restart count'),
      oomKilled: parseBoolean(oomKilled, 'OOM state'),
      running: parseBoolean(running, 'running state'),
    })
  }
  return states
}

function parseDataSize(value) {
  const match = value.trim().match(/^(\d+(?:\.\d+)?)(B|KiB|MiB|GiB|TiB|kB|MB|GB|TB)$/)
  if (!match) throw new Error('memory value is invalid')
  const amount = Number(match[1])
  const factors = {
    B: 1,
    KiB: 1024,
    MiB: MIB,
    GiB: 1024 * MIB,
    TiB: 1024 * 1024 * MIB,
    kB: 1_000,
    MB: 1_000_000,
    GB: 1_000_000_000,
    TB: 1_000_000_000_000,
  }
  const bytes = Math.round(amount * factors[match[2]])
  if (!Number.isSafeInteger(bytes) || bytes < 0) throw new Error('memory value is invalid')
  return bytes
}

function parseCpuPercent(value) {
  const match = value.match(/^(\d+(?:\.\d+)?)%$/)
  if (!match) throw new Error('CPU value is invalid')
  const percent = Number(match[1])
  if (!Number.isFinite(percent) || percent < 0 || percent > 10_000) {
    throw new Error('CPU value is invalid')
  }
  return percent
}

export function parseResourceSamples(input) {
  const samples = []
  for (const line of input.trim().split('\n').filter(Boolean)) {
    const fields = line.split('\t')
    if (fields.length !== 6) throw new Error('resource sample row is invalid')
    const [epochSeconds, hostTotalKiB, hostAvailableKiB, service, cpuPercent, memoryUsage] = fields
    if (!ALLOWED_SERVICES.has(service)) throw new Error('resource sample service is invalid')
    const memoryParts = memoryUsage.split('/').map((part) => part.trim())
    if (memoryParts.length !== 2) throw new Error('memory usage is invalid')
    const totalKiB = parseUnsignedInteger(hostTotalKiB, 'host total memory')
    const availableKiB = parseUnsignedInteger(hostAvailableKiB, 'host available memory')
    if (totalKiB < 1 || availableKiB > totalKiB) throw new Error('host memory sample is invalid')
    samples.push({
      epochSeconds: parseUnsignedInteger(epochSeconds, 'sample timestamp'),
      hostTotalKiB: totalKiB,
      hostAvailableKiB: availableKiB,
      service,
      cpuPercent: parseCpuPercent(cpuPercent),
      memoryUsageBytes: parseDataSize(memoryParts[0]),
      memoryLimitBytes: parseDataSize(memoryParts[1]),
    })
  }
  return samples
}

function round(value, decimals = 2) {
  const factor = 10 ** decimals
  return Math.round(value * factor) / factor
}

function publicServiceEvidence(service, baseline, finalState, samples) {
  const serviceSamples = samples.filter((sample) => sample.service === service)
  const instanceChanged = Boolean(baseline && finalState && baseline.instanceId !== finalState.instanceId)
  const restartDelta = baseline && finalState && !instanceChanged
    ? finalState.restartCount - baseline.restartCount
    : instanceChanged ? 1 : null
  return {
    service,
    presentAtBaseline: baseline !== undefined,
    presentAtEnd: finalState !== undefined,
    sampleCount: serviceSamples.length,
    peakCpuPercent: serviceSamples.length > 0
      ? round(Math.max(...serviceSamples.map((sample) => sample.cpuPercent)))
      : null,
    peakMemoryMiB: serviceSamples.length > 0
      ? round(Math.max(...serviceSamples.map((sample) => sample.memoryUsageBytes)) / MIB)
      : null,
    observedMemoryLimitMiB: serviceSamples.length > 0
      ? round(Math.max(...serviceSamples.map((sample) => sample.memoryLimitBytes)) / MIB)
      : null,
    baselineRestartCount: baseline?.restartCount ?? null,
    finalRestartCount: finalState?.restartCount ?? null,
    restartDelta,
    restartCounterRegressed: restartDelta !== null && restartDelta < 0,
    instanceChanged,
    oomKilledAtBaseline: baseline?.oomKilled ?? null,
    oomKilledAtEnd: finalState?.oomKilled ?? null,
    runningAtEnd: finalState?.running ?? null,
  }
}

function unavailableSummary(releaseId, failureCause) {
  return {
    schemaVersion: 1,
    releaseId,
    collectionOutcome: 'NOT_AVAILABLE',
    failureCause,
    sampleIntervalSeconds: 5,
    sampleTimestampCount: 0,
    host: {
      totalMemoryMiB: null,
      minimumAvailableMemoryMiB: null,
      minimumAvailableMemoryPercent: null,
    },
    services: [],
    safety: {
      evaluated: false,
      passed: null,
      oomObserved: null,
      runtimeResetObserved: null,
    },
  }
}

export function summarizeResourceEvidence({ releaseId, baseline, finalState, samples }) {
  const missingRequiredState = REQUIRED_SERVICES.some((service) =>
    !baseline.has(service) || !finalState.has(service))
  const allServices = [...new Set([
    ...baseline.keys(),
    ...finalState.keys(),
    ...samples.map((sample) => sample.service),
  ])].sort()
  const services = allServices.map((service) => publicServiceEvidence(
    service,
    baseline.get(service),
    finalState.get(service),
    samples,
  ))
  const sampleTimestamps = new Set(samples.map((sample) => sample.epochSeconds))
  const missingRequiredSamples = REQUIRED_SERVICES.some((service) =>
    !samples.some((sample) => sample.service === service))
  const hostTotalKiB = samples.length > 0
    ? Math.max(...samples.map((sample) => sample.hostTotalKiB))
    : null
  const minimumAvailableKiB = samples.length > 0
    ? Math.min(...samples.map((sample) => sample.hostAvailableKiB))
    : null
  const safetyEvaluated = !missingRequiredState
  const oomObserved = safetyEvaluated
    ? services.some((service) => service.oomKilledAtBaseline || service.oomKilledAtEnd)
    : null
  const runtimeResetObserved = safetyEvaluated
    ? services.some((service) => !service.presentAtBaseline
      || !service.presentAtEnd
      || service.instanceChanged
      || service.restartDelta !== 0)
    : null
  const safetyPassed = safetyEvaluated ? !oomObserved && !runtimeResetObserved : null

  let collectionOutcome = 'SUCCEEDED'
  let failureCause = null
  if (missingRequiredState) {
    collectionOutcome = 'NOT_AVAILABLE'
    failureCause = 'INCOMPLETE_CORE_STATE'
  } else if (samples.length === 0) {
    collectionOutcome = 'NOT_AVAILABLE'
    failureCause = 'NO_SAMPLES'
  } else if (missingRequiredSamples) {
    collectionOutcome = 'NOT_AVAILABLE'
    failureCause = 'INCOMPLETE_CORE_SAMPLES'
  }

  return {
    schemaVersion: 1,
    releaseId,
    collectionOutcome,
    failureCause,
    sampleIntervalSeconds: 5,
    sampleTimestampCount: sampleTimestamps.size,
    host: {
      totalMemoryMiB: hostTotalKiB === null ? null : round(hostTotalKiB / 1024),
      minimumAvailableMemoryMiB: minimumAvailableKiB === null ? null : round(minimumAvailableKiB / 1024),
      minimumAvailableMemoryPercent: minimumAvailableKiB === null || hostTotalKiB === null
        ? null
        : round((minimumAvailableKiB / hostTotalKiB) * 100),
    },
    services,
    safety: {
      evaluated: safetyEvaluated,
      passed: safetyPassed,
      oomObserved,
      runtimeResetObserved,
    },
  }
}

export async function collectResourceEvidence(options) {
  const [baselineInput, samplesInput, finalInput] = await Promise.all([
    readBounded(options.baseline),
    readBounded(options.samples),
    readBounded(options.final),
  ])
  if (baselineInput === null) return unavailableSummary(options.releaseId, 'MISSING_BASELINE')
  if (finalInput === null) return unavailableSummary(options.releaseId, 'MISSING_FINAL')

  let baseline
  let finalState
  let samples
  try {
    baseline = parseRuntimeState(baselineInput)
    finalState = parseRuntimeState(finalInput)
  } catch {
    return unavailableSummary(options.releaseId, 'INVALID_RUNTIME_STATE')
  }
  try {
    samples = samplesInput === null ? [] : parseResourceSamples(samplesInput)
  } catch {
    return unavailableSummary(options.releaseId, 'INVALID_RESOURCE_SAMPLES')
  }
  return summarizeResourceEvidence({
    releaseId: options.releaseId,
    baseline,
    finalState,
    samples,
  })
}

async function writeSummary(path, summary) {
  await writeFile(path, `${JSON.stringify(summary, null, 2)}\n`, { mode: 0o600 })
  await chmod(path, 0o600)
}

async function main() {
  let options
  try {
    options = parseArguments(process.argv.slice(2))
  } catch {
    console.error('Production resource summary input is invalid.')
    process.exitCode = 2
    return
  }

  const summary = await collectResourceEvidence(options)
  await writeSummary(options.output, summary)
  console.log(JSON.stringify(summary))
  if (options.failOnRuntimeReset && summary.safety.evaluated && !summary.safety.passed) {
    process.exitCode = 1
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main()
}
