import { afterEach, describe, expect, it, vi } from 'vitest'

import { useLessonSupportingContent } from './useLessonSupportingContent'

describe('useLessonSupportingContent', () => {
  afterEach(() => {
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('keeps the readable lesson usable when optional content is unavailable', async () => {
    localStorage.setItem('rulepilot:narration-position:lesson-1', '4200')
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.endsWith('/quality')) return Response.json({ status: 'READY', score: 100, checks: [] })
      if (path.endsWith('/comprehension')) return new Response(null, { status: 404 })
      if (path.endsWith('/narration/playback')) {
        return Response.json({
          script: { id: 'script-1', status: 'READY', chapters: [] },
          provider: 'fixture', durationMillis: 8_000, cues: [],
        })
      }
      return new Response(null, { status: 404 })
    }))
    const content = useLessonSupportingContent()

    await content.loadSupportingContent({
      planId: 'plan-1',
      isCurrent: () => true,
      narrationPositionKey: () => 'rulepilot:narration-position:lesson-1',
      requestLogin: vi.fn(),
    })

    expect(content.quality.value?.status).toBe('READY')
    expect(content.comprehension.value).toBeNull()
    expect(content.comprehensionError.value).toContain('不影响继续看讲解')
    expect(content.narration.value?.id).toBe('script-1')
    expect(content.audioAvailable.value).toBe(true)
    expect(content.narrationMillis.value).toBe(4_200)
    expect(content.mediaWarnings.value).toEqual([
      '视频暂不可用，可继续使用图文或语音讲解。',
    ])
  })

  it('drops optional content that returns after the selected lesson changed', async () => {
    let resolveQuality: ((response: Response) => void) | undefined
    let current = true
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request) => {
      if (String(input).endsWith('/quality')) {
        return new Promise<Response>((resolve) => { resolveQuality = resolve })
      }
      return Promise.resolve(new Response(null, { status: 404 }))
    }))
    const content = useLessonSupportingContent()
    const loading = content.loadSupportingContent({
      planId: 'plan-1',
      isCurrent: () => current,
      narrationPositionKey: () => '',
      requestLogin: vi.fn(),
    })

    current = false
    resolveQuality!(Response.json({ status: 'READY', score: 100, checks: [] }))
    await loading

    expect(content.quality.value).toBeNull()
    expect(content.mediaWarnings.value).toEqual([])
  })
})
