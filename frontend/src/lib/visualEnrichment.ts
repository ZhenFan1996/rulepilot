interface VisualEnrichmentActivityLike {
  operation: string
  outcome: string
}

export interface VisualEnrichmentRunLike {
  run: { state: string }
  activities?: readonly VisualEnrichmentActivityLike[]
}

export type VisualEnrichmentOutcome =
  | 'ABSENT'
  | 'ACTIVE'
  | 'ADDED'
  | 'EMPTY'
  | 'FAILED'
  | 'PARTIAL'

export interface VisualEnrichmentResult {
  outcome: VisualEnrichmentOutcome
  addedSectionCount: number
}

export const VISUAL_RUN_DISCOVERY_LIMIT = 2
export const VISUAL_REFRESH_FAILURE_LIMIT = 3
export const VISUAL_LESSON_SETTLING_READS = 2
export const VISUAL_REQUEST_TIMEOUT_MS = 5_000

export class VisualRequestTimeoutError extends Error {
  constructor() {
    super('visual status request timed out')
    this.name = 'VisualRequestTimeoutError'
  }
}

/**
 * Optional visual status must never hold the already-readable lesson hostage. Successful bodies
 * are buffered before the deadline is released so callers can safely consume the returned
 * Response even when an adapter ignores an aborted signal.
 */
export async function fetchVisualStatusWithDeadline(
  input: RequestInfo | URL,
  init: RequestInit = {},
  timeoutMs = VISUAL_REQUEST_TIMEOUT_MS,
): Promise<Response> {
  const upstreamSignal = init.signal
  if (upstreamSignal?.aborted) throw new DOMException('Aborted', 'AbortError')

  const requestController = new AbortController()
  let timeout: ReturnType<typeof setTimeout> | undefined
  let removeUpstreamAbort: () => void = () => undefined
  const interruption = new Promise<never>((_resolve, reject) => {
    const abortFromUpstream = () => {
      requestController.abort()
      reject(new DOMException('Aborted', 'AbortError'))
    }
    upstreamSignal?.addEventListener('abort', abortFromUpstream, { once: true })
    removeUpstreamAbort = () => upstreamSignal?.removeEventListener('abort', abortFromUpstream)
    timeout = setTimeout(() => {
      requestController.abort()
      reject(new VisualRequestTimeoutError())
    }, timeoutMs)
  })

  try {
    const response = await Promise.race([
      fetch(input, { ...init, signal: requestController.signal }),
      interruption,
    ])
    if (!response.ok || response.body === null) return response

    const body = await Promise.race([
      response.arrayBuffer(),
      interruption,
    ])
    return new Response(body, {
      status: response.status,
      statusText: response.statusText,
      headers: response.headers,
    })
  } finally {
    if (timeout) clearTimeout(timeout)
    removeUpstreamAbort()
  }
}

const terminalVisualRunStates = new Set([
  'COMPLETED',
  'INSUFFICIENT_EVIDENCE',
  'DEGRADED',
  'FAILED',
])

export function visualRunIsTerminal(state: string | null | undefined) {
  return Boolean(state && terminalVisualRunStates.has(state))
}

function succeededVisualSectionPositions(activities: readonly VisualEnrichmentActivityLike[]) {
  const positions = new Set<number>()
  for (const activity of activities) {
    if (activity.outcome !== 'SUCCEEDED') continue
    const match = /^visualSection\|(\d+)$/.exec(activity.operation)
    if (!match) continue
    const position = Number(match[1])
    if (Number.isSafeInteger(position) && position > 0) positions.add(position)
  }
  return positions
}

/**
 * Derives presentation state only from the visual run contract. The text lesson remains an
 * independent, already-published capability when this optional enrichment ends without a crop.
 */
export function visualEnrichmentResult(
  run: VisualEnrichmentRunLike | null,
  active: boolean,
): VisualEnrichmentResult {
  if (!run) return { outcome: 'ABSENT', addedSectionCount: 0 }
  if (active) return { outcome: 'ACTIVE', addedSectionCount: 0 }

  const addedSectionCount = succeededVisualSectionPositions(run.activities ?? []).size
  const failed = ['FAILED', 'DEGRADED', 'INSUFFICIENT_EVIDENCE'].includes(run.run.state)
  if (failed) {
    return {
      outcome: addedSectionCount > 0 ? 'PARTIAL' : 'FAILED',
      addedSectionCount,
    }
  }
  return {
    outcome: addedSectionCount > 0 ? 'ADDED' : 'EMPTY',
    addedSectionCount,
  }
}
