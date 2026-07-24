import { afterEach, describe, expect, it, vi } from 'vitest'

import { useLessonAnswers } from './useLessonAnswers'
import { setLocale } from '@/lib/locale'

function createAnswers() {
  return useLessonAnswers({
    currentContext: () => ({
      planId: 'plan-1',
      documentVersionId: 'document-1',
      playerCount: 4,
      section: { topicKey: 'ACTIONS', title: 'Actions', coverageTags: [] },
      locale: 'en',
    }),
    currentLessonRequest: () => 1,
    isCurrentLessonLoad: () => true,
    requestLogin: vi.fn(),
    onReceived: vi.fn(),
  })
}

describe('useLessonAnswers', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    setLocale('zh-CN')
  })

  it('localizes a failed secure-session request in the active player language', async () => {
    setLocale('en')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 503 })))
    const answers = createAnswers()

    await answers.submitQuestion('When does this resolve?', null)

    expect(answers.answerError.value).toBe('We could not establish a secure session. Please try again shortly.')
  })

  it('localizes an unavailable answer after a secure session is established', async () => {
    setLocale('en')
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'token' }))
      .mockResolvedValueOnce(new Response(null, { status: 503 })))
    const answers = createAnswers()

    await answers.submitQuestion('When does this resolve?', null)

    expect(answers.answerError.value).toBe('We cannot answer that question right now. Please try again shortly.')
  })

  it('localizes an unexpected request failure instead of leaking a Chinese fallback', async () => {
    setLocale('en')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue({}))
    const answers = createAnswers()

    await answers.submitQuestion('When does this resolve?', null)

    expect(answers.answerError.value).toBe('Your question could not be sent. Please try again shortly.')
  })
})
