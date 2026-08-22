import { describe, expect, it, vi } from 'vitest'

import {
  StructuredAnswerStreamError,
  streamStructuredAnswer,
} from './structuredAnswerStream'

describe('streamStructuredAnswer', () => {
  it('surfaces the persisted run identity before returning the validated result', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response([
      'event: accepted\ndata: {"state":"answer_received"}\n\n',
      'event: run\ndata: {"runId":"run-1"}\n\n',
      'event: result\ndata: {"answer":{"status":"ANSWERED"}}\n\n',
    ].join(''), { headers: { 'Content-Type': 'text/event-stream' } }))
    vi.stubGlobal('fetch', fetchMock)
    const runs: string[] = []

    const result = await streamStructuredAnswer('/answers', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-CSRF': 'token' },
      body: '{}',
    }, runId => runs.push(runId))

    expect(runs).toEqual(['run-1'])
    expect(result).toEqual({ answer: { status: 'ANSWERED' } })
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get('accept')).toBe('text/event-stream')
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get('x-csrf')).toBe('token')
  })

  it('streams player-safe agent activities and validated answer fields without treating them as the final result', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response([
      'event: run\ndata: {"runId":"run-3"}\n\n',
      'event: activity\ndata: {"sequence":1,"actor":"rulebook_search","stage":"searching_evidence","message":"正在查找直接依据","status":"running","nextAction":"下一步：读取命中规则","latencyMs":0}\n\n',
      'event: activity\ndata: {"sequence":1,"actor":"rulebook_search","stage":"searching_evidence","message":"已找到直接依据","status":"succeeded","nextAction":"下一步：读取命中规则","latencyMs":84}\n\n',
      'event: answer_part\ndata: {"field":"verdict","text":"可以。"}\n\n',
      'event: result\ndata: {"answer":{"status":"ANSWERED","shortVerdict":"可以。"}}\n\n',
    ].join(''), { headers: { 'Content-Type': 'text/event-stream' } })))
    const activities: string[] = []
    const parts: string[] = []

    const result = await streamStructuredAnswer('/answers', { method: 'POST' }, () => undefined, {
      onActivity: activity => activities.push(`${activity.actor}:${activity.stage}:${activity.status}`),
      onAnswerPart: part => parts.push(`${part.field}:${part.text}`),
    })

    expect(activities).toEqual([
      'rulebook_search:searching_evidence:running',
      'rulebook_search:searching_evidence:succeeded',
    ])
    expect(parts).toEqual(['verdict:可以。'])
    expect(result).toEqual({ answer: { status: 'ANSWERED', shortVerdict: '可以。' } })
  })

  it('drops duplicate replayed activity states while accepting a later terminal update', async () => {
    const activity = 'event: activity\ndata: {"sequence":7,"actor":"answer_validator","stage":"validating_citations","message":"正在校验引用","status":"running","nextAction":"下一步：发布回答","latencyMs":0}\n\n'
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      `${activity}${activity}event: activity\ndata: {"sequence":7,"actor":"answer_validator","stage":"validating_citations","message":"引用校验完成","status":"succeeded","nextAction":"下一步：发布回答","latencyMs":12}\n\nevent: result\ndata: {}\n\n`,
      { headers: { 'Content-Type': 'text/event-stream' } },
    )))
    const statuses: string[] = []

    await streamStructuredAnswer('/answers', { method: 'POST' }, () => undefined, {
      onActivity: activity => statuses.push(activity.status),
    })

    expect(statuses).toEqual(['running', 'succeeded'])
  })

  it('keeps the JSON response as a backward-compatible transport fallback', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ answer: { status: 'ANSWERED' } })))

    await expect(streamStructuredAnswer('/answers', { method: 'POST' }, () => undefined))
      .resolves.toEqual({ answer: { status: 'ANSWERED' } })
  })

  it('publishes the validated answer without waiting for the proxy to close the SSE connection', async () => {
    const encoder = new TextEncoder()
    let transportCancelled = false
    vi.stubGlobal('fetch', vi.fn(async () => new Response(new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(
          'event: result\ndata: {"answer":{"status":"ANSWERED","shortVerdict":"可以。"}}\n\n',
        ))
      },
      cancel() {
        transportCancelled = true
      },
    }), { headers: { 'Content-Type': 'text/event-stream' } })))

    await expect(streamStructuredAnswer('/answers', { method: 'POST' }, () => undefined))
      .resolves.toEqual({ answer: { status: 'ANSWERED', shortVerdict: '可以。' } })
    expect(transportCancelled).toBe(true)
  })

  it('does not turn a terminal stream error into a partial answer', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      'event: run\ndata: {"runId":"run-2"}\n\nevent: error\ndata: {"code":"answer_unavailable"}\n\n',
      { headers: { 'Content-Type': 'text/event-stream' } },
    )))

    await expect(streamStructuredAnswer('/answers', { method: 'POST' }, () => undefined))
      .rejects.toEqual(expect.objectContaining<Partial<StructuredAnswerStreamError>>({
        name: 'StructuredAnswerStreamError',
        code: 'answer_unavailable',
      }))
  })
})
