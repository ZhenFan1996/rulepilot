import { ref } from 'vue'

import type { LessonComprehensionReport } from '@/composables/lessonSupportingContent'

export type MediaWarningCode =
  | 'QUALITY_UNAVAILABLE'
  | 'AUDIO_UNAVAILABLE'
  | 'VIDEO_UNAVAILABLE'
  | 'AUDIO_LOAD_FAILED'
  | 'SOURCE_LANGUAGE_MEDIA'

interface LoadSupportingContentRequest {
  planId: string
  signal: AbortSignal
  isCurrent: () => boolean
  requestLogin: () => Promise<unknown>
}

export function useLessonSupportingContent() {
  const comprehension = ref<LessonComprehensionReport | null>(null)
  const comprehensionSaving = ref<string | null>(null)
  const comprehensionError = ref('')

  function clearSupportingContent() {
    comprehension.value = null
    comprehensionSaving.value = null
    comprehensionError.value = ''
  }

  async function optionalFetch(url: string, signal: AbortSignal) {
    try {
      return await fetch(url, { credentials: 'include', signal })
    } catch {
      return null
    }
  }

  async function loadSupportingContent(request: LoadSupportingContentRequest) {
    if (!request.isCurrent() || request.signal.aborted) return
    clearSupportingContent()
    const comprehensionResponse = await optionalFetch(
      `/api/v1/teaching-plans/${request.planId}/comprehension`,
      request.signal,
    )
    if (!request.isCurrent() || request.signal.aborted) return
    if (comprehensionResponse?.status === 401) {
      await request.requestLogin()
      return
    }
    const loadedComprehension = comprehensionResponse?.ok
      ? await comprehensionResponse.json() as LessonComprehensionReport
      : null
    if (!request.isCurrent() || request.signal.aborted) return
    if (loadedComprehension) comprehension.value = loadedComprehension
    else comprehensionError.value = '学习检查暂时无法读取，不影响继续看讲解。'
  }

  return {
    comprehension,
    comprehensionSaving,
    comprehensionError,
    clearSupportingContent,
    loadSupportingContent,
  }
}
