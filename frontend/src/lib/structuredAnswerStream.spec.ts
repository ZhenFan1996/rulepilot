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

  it('keeps the JSON response as a backward-compatible transport fallback', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ answer: { status: 'ANSWERED' } })))

    await expect(streamStructuredAnswer('/answers', { method: 'POST' }, () => undefined))
      .resolves.toEqual({ answer: { status: 'ANSWERED' } })
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
