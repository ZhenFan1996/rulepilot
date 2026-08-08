import type {
  RecommendationAgentResponse,
  RecommendationProgressUpdate,
} from '@/components/gameRecommendationTypes'

type StreamEvent = { event: string; data: string }

export async function streamGameRecommendation(
  url: string,
  init: RequestInit,
  onProgress: (update: RecommendationProgressUpdate) => void,
) {
  const response = await fetch(url, init)
  if (!response.ok) throw new Error('recommendation unavailable')
  const contentType = response.headers.get('content-type') ?? ''
  if (contentType.includes('application/json')) {
    return await response.json() as RecommendationAgentResponse
  }
  if (!response.body) throw new Error('recommendation stream unavailable')

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let result: RecommendationAgentResponse | null = null

  const consume = (event: StreamEvent) => {
    if (event.event === 'progress') {
      onProgress(JSON.parse(event.data) as RecommendationProgressUpdate)
    } else if (event.event === 'result') {
      result = JSON.parse(event.data) as RecommendationAgentResponse
    } else if (event.event === 'error') {
      throw new Error('recommendation unavailable')
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
  if (!result) throw new Error('recommendation stream ended without a result')
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
