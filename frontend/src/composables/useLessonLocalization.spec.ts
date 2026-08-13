import { ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { useLessonLocalization } from '@/composables/useLessonLocalization'
import type { AppLocale } from '@/lib/locale'

interface LessonFixture {
  id: string
  teachingPlanId: string
  title: string
}

function createLocalization() {
  const locale = ref<AppLocale>('en')
  const planId = ref('plan-1')
  const sourceLesson = ref<LessonFixture | null>({ id: 'source', teachingPlanId: 'plan-1', title: 'Source guide' })
  const displayedLesson = ref<LessonFixture | null>(sourceLesson.value)
  const request = ref(1)
  const requestLogin = vi.fn(async () => undefined)
  const csrfToken = vi.fn(async () => ({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
  const localization = useLessonLocalization({
    locale,
    planId,
    sourceLesson,
    displayedLesson,
    currentRequest: () => request.value,
    isCurrent: (candidate, targetPlanId) => candidate === request.value && targetPlanId === planId.value,
    isLessonForPlan: (lesson, targetPlanId) => lesson.teachingPlanId === targetPlanId,
    canRead: () => true,
    requestLogin,
    csrfToken,
  })

  return { locale, sourceLesson, displayedLesson, request, requestLogin, csrfToken, localization }
}

describe('useLessonLocalization', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('keeps the source guide visible while English preparation is pending and refreshes to the ready guide', async () => {
    vi.useFakeTimers()
    const source = { id: 'source', teachingPlanId: 'plan-1', title: 'Source guide' }
    const localized = { id: 'english', teachingPlanId: 'plan-1', title: 'English guide' }
    let reads = 0
    vi.stubGlobal('fetch', vi.fn(async () => {
      reads++
      return Response.json(reads === 1
        ? { language: 'EN', status: 'PENDING', lesson: null, failureCode: null }
        : { language: 'EN', status: 'READY', lesson: localized, failureCode: null })
    }))
    const fixture = createLocalization()
    fixture.sourceLesson.value = source
    fixture.displayedLesson.value = source

    await fixture.localization.applySelectedLocale()

    expect(fixture.localization.status.value).toBe('PENDING')
    expect(fixture.displayedLesson.value).toEqual(source)

    await vi.advanceTimersByTimeAsync(3_000)

    expect(fixture.localization.status.value).toBe('READY')
    expect(fixture.displayedLesson.value).toEqual(localized)
    expect(reads).toBe(2)
  })

  it('uses the CSRF contract when the player manually starts English preparation', async () => {
    const fetchMock = vi.fn(async (_input: string, init?: RequestInit) => {
      expect(init).toMatchObject({
        method: 'POST',
        credentials: 'include',
        headers: { 'X-CSRF-TOKEN': 'csrf' },
      })
      expect(init?.signal).toBeUndefined()
      return Response.json({ language: 'EN', status: 'RUNNING', lesson: null, failureCode: null })
    })
    vi.stubGlobal('fetch', fetchMock)
    const fixture = createLocalization()

    await fixture.localization.prepareEnglishGuide()

    expect(fixture.csrfToken).toHaveBeenCalledOnce()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/teaching-plans/plan-1/illustrated-lessons/latest/localizations/en',
      expect.any(Object),
    )
    expect(fixture.localization.status.value).toBe('RUNNING')
    expect(fixture.localization.preparing.value).toBe(false)
  })

  it('redirects for an unauthenticated localization response without replacing the source guide', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 401 })))
    const fixture = createLocalization()

    await fixture.localization.applySelectedLocale()

    expect(fixture.requestLogin).toHaveBeenCalledOnce()
    expect(fixture.localization.status.value).toBeNull()
    expect(fixture.displayedLesson.value).toEqual({
      id: 'source', teachingPlanId: 'plan-1', title: 'Source guide',
    })
  })

  it('aborts a superseded English read and ignores its late response', async () => {
    let resolveFirst: ((response: Response) => void) | undefined
    const signals: AbortSignal[] = []
    let reads = 0
    vi.stubGlobal('fetch', vi.fn((_input: string, init?: RequestInit) => {
      signals.push(init!.signal!)
      reads++
      if (reads === 1) return new Promise<Response>((resolve) => { resolveFirst = resolve })
      return Promise.resolve(Response.json({
        language: 'EN', status: 'READY',
        lesson: { id: 'english-2', teachingPlanId: 'plan-1', title: 'Current guide' }, failureCode: null,
      }))
    }))
    const fixture = createLocalization()

    const first = fixture.localization.applySelectedLocale()
    await fixture.localization.applySelectedLocale()

    expect(signals[0]?.aborted).toBe(true)
    expect(signals[1]?.aborted).toBe(false)
    expect(fixture.displayedLesson.value?.title).toBe('Current guide')

    resolveFirst!(Response.json({
      language: 'EN', status: 'READY',
      lesson: { id: 'english-1', teachingPlanId: 'plan-1', title: 'Stale guide' }, failureCode: null,
    }))
    await first
    expect(fixture.displayedLesson.value?.title).toBe('Current guide')
  })

  it('rejects a ready localization that belongs to another plan', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({
      language: 'EN', status: 'READY',
      lesson: { id: 'wrong', teachingPlanId: 'plan-2', title: 'Wrong guide' }, failureCode: null,
    })))
    const fixture = createLocalization()

    await fixture.localization.applySelectedLocale()

    expect(fixture.localization.status.value).toBe('FAILED')
    expect(fixture.displayedLesson.value?.title).toBe('Source guide')
  })

})
