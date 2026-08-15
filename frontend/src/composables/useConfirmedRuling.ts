import { ref, type Ref } from 'vue'

import type {
  AnswerRulingReference,
  ConfirmedRuling,
  CsrfResponse,
  StructuredRuleAnswer,
} from '@/composables/useLessonAnswers'

interface ConfirmedRulingMessages {
  createFailed: () => string
  createRequestFailed: () => string
  updateFailed: () => string
  updateRequestFailed: () => string
  reloadFailed: () => string
  reloadRequestFailed: () => string
}

interface UseConfirmedRulingOptions {
  documentVersionId: Readonly<Ref<string | null>>
  answer: Ref<StructuredRuleAnswer | null>
  rulingReference: Readonly<Ref<AnswerRulingReference | null>>
  answeredQuestion: Readonly<Ref<string>>
  csrfToken: () => Promise<CsrfResponse>
  onApplied: (ruling: ConfirmedRuling, question: string) => void
  currentReadContext?: () => string | null
  isCurrentReadContext?: (context: string) => boolean
  messages: ConfirmedRulingMessages
}

export function useConfirmedRuling(options: UseConfirmedRulingOptions) {
  const ruling = ref<ConfirmedRuling | null>(null)
  const saving = ref(false)
  const error = ref('')
  const conflict = ref(false)
  const editing = ref(false)
  const editedVerdict = ref('')
  const editedExplanation = ref('')
  let reloadSequence = 0
  let activeReloadController: AbortController | null = null
  let mutationSequence = 0

  function cancelReads() {
    const wasReloading = activeReloadController !== null
    reloadSequence += 1
    activeReloadController?.abort()
    activeReloadController = null
    if (wasReloading) saving.value = false
  }

  function applyRuling(value: ConfirmedRuling) {
    cancelReads()
    ruling.value = value
    editedVerdict.value = value.shortVerdict
    editedExplanation.value = value.explanation
    conflict.value = false
    editing.value = false
    options.onApplied(value, options.answeredQuestion.value)
  }

  function reset() {
    cancelReads()
    mutationSequence += 1
    ruling.value = null
    saving.value = false
    error.value = ''
    conflict.value = false
    editing.value = false
    editedVerdict.value = ''
    editedExplanation.value = ''
  }

  async function confirmAnswer() {
    const answer = options.answer.value
    const rulingReference = options.rulingReference.value
    const documentVersionId = options.documentVersionId.value
    if (!answer || answer.status !== 'ANSWERED' || !rulingReference?.citationIds.length
      || !documentVersionId || saving.value) return
    const context = options.currentReadContext?.() ?? documentVersionId
    if (!context) return
    const mutation = ++mutationSequence
    saving.value = true
    error.value = ''
    try {
      const csrf = await options.csrfToken()
      if (!isCurrentMutation(mutation, context)) return
      const response = await fetch('/api/v1/confirmed-rulings', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({
          documentVersionId,
          expansionIds: [],
          question: options.answeredQuestion.value,
          shortVerdict: answer.shortVerdict,
          explanation: answer.explanation,
          citationChunkIds: rulingReference.citationIds,
          exceptions: answer.exceptions,
          confidence: answer.confidence,
        }),
      })
      if (!isCurrentMutation(mutation, context)) return
      if (!response.ok) throw new Error(options.messages.createFailed())
      const created = await response.json() as ConfirmedRuling
      if (!isCurrentMutation(mutation, context)) return
      applyRuling(created)
    } catch (caught) {
      if (!isCurrentMutation(mutation, context)) return
      error.value = caught instanceof Error ? caught.message : options.messages.createRequestFailed()
    } finally {
      if (isCurrentMutation(mutation, context)) saving.value = false
    }
  }

  async function saveRulingRevision() {
    const currentRuling = ruling.value
    if (!currentRuling || saving.value) return
    const context = options.currentReadContext?.() ?? options.documentVersionId.value
    if (!context) return
    const mutation = ++mutationSequence
    saving.value = true
    error.value = ''
    conflict.value = false
    try {
      const csrf = await options.csrfToken()
      if (!isCurrentMutation(mutation, context, currentRuling.id)) return
      const response = await fetch(`/api/v1/confirmed-rulings/${currentRuling.id}`, {
        method: 'PATCH',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({
          expectedVersion: currentRuling.version,
          shortVerdict: editedVerdict.value,
          explanation: editedExplanation.value,
          citationChunkIds: currentRuling.citations.map((citation) => citation.chunkId),
          exceptions: currentRuling.exceptions,
          confidence: currentRuling.confidence,
        }),
      })
      if (!isCurrentMutation(mutation, context, currentRuling.id)) return
      if (response.status === 409) {
        conflict.value = true
        return
      }
      if (!response.ok) throw new Error(options.messages.updateFailed())
      const updated = await response.json() as ConfirmedRuling
      if (!isCurrentMutation(mutation, context, currentRuling.id) || updated.id !== currentRuling.id) return
      applyRuling(updated)
    } catch (caught) {
      if (!isCurrentMutation(mutation, context, currentRuling.id)) return
      error.value = caught instanceof Error ? caught.message : options.messages.updateRequestFailed()
    } finally {
      if (isCurrentMutation(mutation, context, currentRuling.id)) saving.value = false
    }
  }

  async function reloadRuling() {
    const currentRuling = ruling.value
    if (!currentRuling) return
    const context = options.currentReadContext?.() ?? options.documentVersionId.value
    if (!context) return
    const read = ++reloadSequence
    activeReloadController?.abort()
    const controller = new AbortController()
    activeReloadController = controller
    saving.value = true
    error.value = ''
    try {
      const response = await fetch(`/api/v1/confirmed-rulings/${currentRuling.id}`, {
        credentials: 'include',
        signal: controller.signal,
      })
      if (!isCurrentReload(read, controller, context, currentRuling.id)) return
      if (!response.ok) throw new Error(options.messages.reloadFailed())
      const loaded = await response.json() as ConfirmedRuling
      if (!isCurrentReload(read, controller, context, currentRuling.id)) return
      if (loaded.id !== currentRuling.id) throw new Error(options.messages.reloadFailed())
      applyRuling(loaded)
    } catch (caught) {
      if (controller.signal.aborted || !isCurrentReload(read, controller, context, currentRuling.id)) return
      error.value = caught instanceof Error ? caught.message : options.messages.reloadRequestFailed()
    } finally {
      if (isCurrentReload(read, controller, context, currentRuling.id)) {
        activeReloadController = null
        saving.value = false
      }
    }
  }

  function isCurrentReload(
    read: number,
    controller: AbortController,
    context: string,
    rulingId: string,
  ) {
    return read === reloadSequence
      && activeReloadController === controller
      && ruling.value?.id === rulingId
      && (options.isCurrentReadContext?.(context) ?? options.documentVersionId.value === context)
  }

  function isCurrentMutation(mutation: number, context: string, rulingId?: string) {
    return mutation === mutationSequence
      && (options.isCurrentReadContext?.(context) ?? options.documentVersionId.value === context)
      && (!rulingId || ruling.value?.id === rulingId)
  }

  return {
    ruling,
    saving,
    error,
    conflict,
    editing,
    editedVerdict,
    editedExplanation,
    applyRuling,
    confirmAnswer,
    saveRulingRevision,
    reloadRuling,
    cancelReads,
    reset,
  }
}
