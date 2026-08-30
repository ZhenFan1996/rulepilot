type StreamEvent = { event: string; data: string }

export interface AnswerStreamActivity {
  sequence: number
  actor: 'answer_agent' | 'rulebook_tool'
  stage: 'model_decision' | 'read_tool' | 'tool_observation' | 'repairing_action'
    | 'repairing_terminal' | 'publication_boundary' | 'agent_stopped'
  message: string
  status: 'running' | 'succeeded' | 'failed' | 'rejected'
  latencyMs: number
}

export interface AnswerStreamCallbacks {
  onActivity?: (activity: AnswerStreamActivity) => void
  onAnswerPart?: (part: { field: 'verdict' | 'explanation'; text: string }) => void
}

export class StructuredAnswerRequestError extends Error {
  constructor(readonly status: number) {
    super('structured answer unavailable')
    this.name = 'StructuredAnswerRequestError'
  }
}

export interface AnswerStreamFailureRecovery {
  message: string
  actionLabel: string
  draft: string
  canRetryUnchanged: boolean
}

export class StructuredAnswerStreamError extends Error {
  constructor(
    readonly code: string,
    readonly recovery: AnswerStreamFailureRecovery,
  ) {
    super('structured answer stream did not complete')
    this.name = 'StructuredAnswerStreamError'
  }
}

const CONSERVATIVE_STREAM_FAILURE = {
  code: 'answer_unavailable',
  recovery: {
    message: '',
    actionLabel: '',
    draft: '',
    canRetryUnchanged: false,
  },
} as const

export async function streamStructuredAnswer(
  url: string,
  init: RequestInit,
  onRun: (runId: string) => void,
  callbacks: AnswerStreamCallbacks = {},
): Promise<unknown> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'text/event-stream')
  const response = await fetch(url, {
    ...init,
    headers,
  })
  if (!response.ok) throw new StructuredAnswerRequestError(response.status)
  if (!response.body) throw new Error('structured answer stream unavailable')

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let result: unknown
  let completed = false
  const latestActivityStatus = new Map<number, AnswerStreamActivity['status']>()

  const consume = (event: StreamEvent) => {
    if (event.event === 'run') {
      const payload = JSON.parse(event.data) as { runId?: unknown }
      if (typeof payload.runId === 'string' && payload.runId) onRun(payload.runId)
    } else if (event.event === 'activity') {
      const activity = parseActivity(JSON.parse(event.data) as unknown)
      if (activity && latestActivityStatus.get(activity.sequence) !== activity.status) {
        latestActivityStatus.set(activity.sequence, activity.status)
        callbacks.onActivity?.(activity)
      }
    } else if (event.event === 'answer_part') {
      const part = parseAnswerPart(JSON.parse(event.data) as unknown)
      if (part) callbacks.onAnswerPart?.(part)
    } else if (event.event === 'result') {
      result = JSON.parse(event.data) as unknown
      completed = true
    } else if (event.event === 'error') {
      const failure = parseStreamFailure(event.data)
      throw new StructuredAnswerStreamError(failure.code, failure.recovery)
    }
  }

  while (true) {
    const chunk = await reader.read()
    buffer = `${buffer}${decoder.decode(chunk.value, { stream: !chunk.done })}`.replaceAll('\r\n', '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const raw = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const event = parseEvent(raw)
      if (event) consume(event)
      if (completed) {
        void reader.cancel()
        return result
      }
      boundary = buffer.indexOf('\n\n')
    }
    if (chunk.done) break
  }
  if (buffer.trim()) {
    const event = parseEvent(buffer)
    if (event) consume(event)
  }
  if (!completed) throw new Error('structured answer stream ended without a result')
  return result
}

function parseActivity(value: unknown): AnswerStreamActivity | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const item = value as Record<string, unknown>
  const actors = new Set(['answer_agent', 'rulebook_tool'])
  const stages = new Set([
    'model_decision',
    'read_tool',
    'tool_observation',
    'repairing_action',
    'repairing_terminal',
    'publication_boundary',
    'agent_stopped',
  ])
  const statuses = new Set(['running', 'succeeded', 'failed', 'rejected'])
  if (!Number.isSafeInteger(item.sequence) || (item.sequence as number) <= 0
    || typeof item.actor !== 'string' || !actors.has(item.actor)
    || typeof item.stage !== 'string' || !stages.has(item.stage)
    || typeof item.message !== 'string' || !item.message.trim()
    || typeof item.status !== 'string' || !statuses.has(item.status)
    || !Number.isSafeInteger(item.latencyMs) || (item.latencyMs as number) < 0) return null
  return item as unknown as AnswerStreamActivity
}

function parseAnswerPart(value: unknown): { field: 'verdict' | 'explanation'; text: string } | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const part = value as Record<string, unknown>
  if ((part.field !== 'verdict' && part.field !== 'explanation')
    || typeof part.text !== 'string' || !part.text.trim()) return null
  return { field: part.field, text: part.text }
}

function parseStreamFailure(data: string): {
  code: string
  recovery: AnswerStreamFailureRecovery
} {
  let value: unknown
  try {
    value = JSON.parse(data) as unknown
  } catch {
    return conservativeStreamFailure()
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return conservativeStreamFailure()
  const payload = value as Record<string, unknown>
  const recovery = payload.recovery
  if (typeof payload.code !== 'string' || !payload.code.trim()
    || !recovery || typeof recovery !== 'object' || Array.isArray(recovery)) {
    return conservativeStreamFailure()
  }
  const fields = recovery as Record<string, unknown>
  if (typeof fields.message !== 'string' || !fields.message.trim()
    || typeof fields.actionLabel !== 'string' || !fields.actionLabel.trim()
    || typeof fields.draft !== 'string'
    || typeof fields.canRetryUnchanged !== 'boolean') {
    return conservativeStreamFailure()
  }
  return {
    code: payload.code,
    recovery: {
      message: fields.message,
      actionLabel: fields.actionLabel,
      draft: fields.draft,
      canRetryUnchanged: fields.canRetryUnchanged,
    },
  }
}

function conservativeStreamFailure() {
  return {
    code: CONSERVATIVE_STREAM_FAILURE.code,
    recovery: { ...CONSERVATIVE_STREAM_FAILURE.recovery },
  }
}

function parseEvent(raw: string): StreamEvent | null {
  let event = 'message'
  const data: string[] = []
  for (const line of raw.split('\n')) {
    if (line.startsWith(':')) continue
    if (line.startsWith('event:')) event = line.slice(6).trim()
    if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  }
  return data.length ? { event, data: data.join('\n') } : null
}
