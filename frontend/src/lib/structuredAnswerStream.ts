type StreamEvent = { event: string; data: string }

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

  const consume = (event: StreamEvent) => {
    if (event.event === 'run') {
      const payload = JSON.parse(event.data) as { runId?: unknown }
      if (typeof payload.runId === 'string' && payload.runId) onRun(payload.runId)
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
