import { ref, type Ref } from 'vue'

interface UseLessonResumeOptions {
  planId: Readonly<Ref<string>>
  online: Readonly<Ref<boolean>>
  currentRequest: () => number
  isCurrent: (request: number, planId: string) => boolean
  csrfToken: () => Promise<{ headerName: string; token: string }>
  onStarted: (planId: string) => Promise<unknown>
  messages: {
    requestFailed: () => string
    requestError: () => string
  }
}

export function useLessonResume(options: UseLessonResumeOptions) {
  const resuming = ref(false)
  const errorMessage = ref('')

  function reset() {
    resuming.value = false
    errorMessage.value = ''
  }

  async function resume() {
    if (!options.planId.value || resuming.value || !options.online.value) return
    const targetPlanId = options.planId.value
    const request = options.currentRequest()
    if (!options.isCurrent(request, targetPlanId)) return
    resuming.value = true
    errorMessage.value = ''
    try {
      const csrf = await options.csrfToken()
      if (!options.isCurrent(request, targetPlanId)) return
      const response = await fetch(`/api/v1/teaching-plans/${targetPlanId}/illustrated-lessons`, {
        method: 'POST',
        credentials: 'include',
        headers: { [csrf.headerName]: csrf.token },
      })
      if (!options.isCurrent(request, targetPlanId)) return
      if (!response.ok) throw new Error(options.messages.requestFailed())
      await options.onStarted(targetPlanId)
    } catch (error) {
      if (!options.isCurrent(request, targetPlanId)) return
      errorMessage.value = error instanceof Error ? error.message : options.messages.requestError()
    } finally {
      if (options.isCurrent(request, targetPlanId)) resuming.value = false
    }
  }

  return {
    resuming,
    errorMessage,
    reset,
    resume,
  }
}
