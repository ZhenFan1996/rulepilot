import { ref, type Ref } from 'vue'

import type { AppLocale } from '@/lib/locale'

export type LessonLocalizationStatus = 'PENDING' | 'RUNNING' | 'READY' | 'FAILED'

export interface LocalizedLessonResponse<TLesson> {
  language: 'ZH_CN' | 'EN'
  status: LessonLocalizationStatus | null
  lesson: TLesson | null
  failureCode: string | null
}

interface CsrfToken {
  headerName: string
  token: string
}

interface LessonLocalizationOptions<TLesson> {
  locale: Readonly<Ref<AppLocale>>
  planId: Readonly<Ref<string>>
  sourceLesson: Ref<TLesson | null>
  displayedLesson: Ref<TLesson | null>
  currentRequest: () => number
  isCurrent: (request: number, planId: string) => boolean
  requestLogin: () => Promise<unknown>
  csrfToken: () => Promise<CsrfToken>
}

export function useLessonLocalization<TLesson>(options: LessonLocalizationOptions<TLesson>) {
  const status = ref<LessonLocalizationStatus | null>(null)
  const preparing = ref(false)
  let refreshTimer: ReturnType<typeof setTimeout> | undefined
  let disposed = false

  function endpoint(planId: string) {
    return `/api/v1/teaching-plans/${planId}/illustrated-lessons/latest/localizations/en`
  }

  function isCurrent(request: number, planId: string) {
    return !disposed && options.isCurrent(request, planId)
  }

  function clearRefresh() {
    if (refreshTimer) clearTimeout(refreshTimer)
    refreshTimer = undefined
  }

  function scheduleRefresh() {
    clearRefresh()
    if (
      disposed ||
      options.locale.value !== 'en' ||
      !options.sourceLesson.value ||
      !['PENDING', 'RUNNING'].includes(status.value ?? '')
    ) return
    refreshTimer = setTimeout(() => {
      refreshTimer = undefined
      void applySelectedLocale()
    }, 3_000)
  }

  async function applySelectedLocale(
    targetPlanId = options.planId.value,
    request = options.currentRequest(),
  ) {
    if (!isCurrent(request, targetPlanId)) return
    clearRefresh()
    const source = options.sourceLesson.value
    if (!source) return
    if (options.locale.value !== 'en') {
      status.value = 'READY'
      options.displayedLesson.value = source
      return
    }
    try {
      const response = await fetch(endpoint(targetPlanId), { credentials: 'include' })
      if (!isCurrent(request, targetPlanId)) return
      if (response.status === 401) {
        await options.requestLogin()
        return
      }
      if (!response.ok) throw new Error('English guide is unavailable.')
      const localized = await response.json() as LocalizedLessonResponse<TLesson>
      if (!isCurrent(request, targetPlanId)) return
      status.value = localized.status
      options.displayedLesson.value = localized.status === 'READY' && localized.lesson ? localized.lesson : source
    } catch {
      if (!isCurrent(request, targetPlanId)) return
      status.value = 'FAILED'
      options.displayedLesson.value = source
    } finally {
      if (isCurrent(request, targetPlanId)) scheduleRefresh()
    }
  }

  async function prepareEnglishGuide() {
    if (!options.sourceLesson.value || preparing.value) return
    const targetPlanId = options.planId.value
    const request = options.currentRequest()
    preparing.value = true
    try {
      const csrf = await options.csrfToken()
      if (!isCurrent(request, targetPlanId)) return
      const response = await fetch(endpoint(targetPlanId), {
        method: 'POST',
        credentials: 'include',
        headers: { [csrf.headerName]: csrf.token },
      })
      if (!response.ok) throw new Error('English guide could not be queued.')
      const localized = await response.json() as LocalizedLessonResponse<TLesson>
      if (!isCurrent(request, targetPlanId)) return
      status.value = localized.status
    } catch {
      if (!isCurrent(request, targetPlanId)) return
      status.value = 'FAILED'
    } finally {
      if (isCurrent(request, targetPlanId)) {
        preparing.value = false
        scheduleRefresh()
      }
    }
  }

  function reset() {
    clearRefresh()
    status.value = null
    preparing.value = false
  }

  function dispose() {
    disposed = true
    clearRefresh()
  }

  return {
    status,
    preparing,
    applySelectedLocale,
    prepareEnglishGuide,
    reset,
    dispose,
  }
}
