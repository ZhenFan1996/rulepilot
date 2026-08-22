type StreamEvent = { event: string; data: string }

export interface AnswerStreamActivity {
  sequence: number
  actor: 'answer_agent' | 'rulebook_search' | 'rulebook_reader' | 'answer_reviewer' | 'answer_validator' | 'rulebook_tool'
  stage: 'searching_evidence' | 'checking_exceptions' | 'expanding_context' | 'reading_pages' | 'composing_answer' | 'reviewing_support' | 'validating_citations' | 'checking_rule_details'
  message: string
  status: 'running' | 'succeeded' | 'failed' | 'rejected'
  nextAction: string
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

export class StructuredAnswerStreamError extends Error {
  constructor(readonly code: string) {
    super('structured answer stream did not complete')
    this.name = 'StructuredAnswerStreamError'
  }
}

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
  const contentType = response.headers.get('content-type') ?? ''
  if (contentType.includes('application/json')) return await response.json() as unknown
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
      const payload = JSON.parse(event.data) as { code?: unknown }
      throw new StructuredAnswerStreamError(
        typeof payload.code === 'string' ? payload.code : 'answer_unavailable',
      )
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
  const actors = new Set(['answer_agent', 'rulebook_search', 'rulebook_reader', 'answer_reviewer', 'answer_validator', 'rulebook_tool'])
  const stages = new Set(['searching_evidence', 'checking_exceptions', 'expanding_context', 'reading_pages', 'composing_answer', 'reviewing_support', 'validating_citations', 'checking_rule_details'])
  const statuses = new Set(['running', 'succeeded', 'failed', 'rejected'])
  if (!Number.isSafeInteger(item.sequence) || (item.sequence as number) <= 0
    || typeof item.actor !== 'string' || !actors.has(item.actor)
    || typeof item.stage !== 'string' || !stages.has(item.stage)
    || typeof item.message !== 'string' || !item.message.trim()
    || typeof item.status !== 'string' || !statuses.has(item.status)
    || typeof item.nextAction !== 'string' || !item.nextAction.trim()
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
