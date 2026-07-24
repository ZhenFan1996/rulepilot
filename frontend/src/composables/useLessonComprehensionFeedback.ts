import type { Ref } from 'vue'

import type { LessonComprehensionReport } from '@/composables/lessonSupportingContent'

type ComprehensionTask = LessonComprehensionReport['tasks'][number]
type TaskResult = 'CAN_DO' | 'NEEDS_HELP'
type VisualAidResult = 'HELPFUL' | 'NOT_HELPFUL'

interface UseLessonComprehensionFeedbackOptions {
  planId: Readonly<Ref<string>>
  online: Readonly<Ref<boolean>>
  currentRequest: () => number
  isCurrent: (request: number, planId: string) => boolean
  comprehension: Ref<LessonComprehensionReport | null>
  saving: Ref<string | null>
  errorMessage: Ref<string>
  csrfToken: () => Promise<{ headerName: string; token: string }>
  messages: {
    saveTaskRetry: () => string
    saveTask: () => string
    saveVisualRetry: () => string
    saveVisual: () => string
  }
}

export function useLessonComprehensionFeedback(options: UseLessonComprehensionFeedbackOptions) {
  function visualAidKey(sectionPosition: number, stepPosition: number) {
    return `s${sectionPosition}-v${stepPosition}`
  }

  function visualAidFor(sectionPosition: number, stepPosition: number) {
    return options.comprehension.value?.visualAids
      .find((aid) => aid.key === visualAidKey(sectionPosition, stepPosition)) ?? null
  }

  function visualAidResult(sectionPosition: number, stepPosition: number) {
    return visualAidFor(sectionPosition, stepPosition)?.result ?? 'NOT_RATED'
  }

  function hasVisualAid(sectionPosition: number, stepPosition: number) {
    return visualAidFor(sectionPosition, stepPosition) !== null
  }

  async function saveFeedback(
    savingKey: string,
    path: string,
    result: TaskResult | VisualAidResult,
    failedMessage: () => string,
    fallbackMessage: () => string,
  ) {
    if (options.saving.value || !options.online.value || !options.planId.value) return
    const targetPlanId = options.planId.value
    const request = options.currentRequest()
    if (!options.isCurrent(request, targetPlanId)) return
    options.saving.value = savingKey
    options.errorMessage.value = ''
    try {
      const csrf = await options.csrfToken()
      const response = await fetch(`/api/v1/teaching-plans/${targetPlanId}/comprehension/${path}`, {
        method: 'PUT',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({ result }),
      })
      if (!response.ok) throw new Error(failedMessage())
      const updated = await response.json() as LessonComprehensionReport
      if (!options.isCurrent(request, targetPlanId)) return
      options.comprehension.value = updated
    } catch (error) {
      if (!options.isCurrent(request, targetPlanId)) return
      options.errorMessage.value = error instanceof Error ? error.message : fallbackMessage()
    } finally {
      if (options.isCurrent(request, targetPlanId)) options.saving.value = null
    }
  }

  async function recordComprehension(taskType: ComprehensionTask['type'], result: TaskResult) {
    await saveFeedback(taskType, taskType, result, options.messages.saveTaskRetry, options.messages.saveTask)
  }

  async function recordVisualAid(key: string, result: VisualAidResult) {
    await saveFeedback(`visual-${key}`, `visual-aids/${key}`, result, options.messages.saveVisualRetry, options.messages.saveVisual)
  }

  function recordChapterVisualAid(
    sectionPosition: number,
    stepPosition: number,
    result: VisualAidResult,
  ) {
    void recordVisualAid(visualAidKey(sectionPosition, stepPosition), result)
  }

  return {
    visualAidFor,
    visualAidResult,
    hasVisualAid,
    recordComprehension,
    recordVisualAid,
    recordChapterVisualAid,
  }
}
