import { ref, type Ref } from 'vue'

import type { ConfirmedRuling, CsrfResponse, StructuredRuleAnswer } from '@/composables/useLessonAnswers'

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
  answeredQuestion: Readonly<Ref<string>>
  csrfToken: () => Promise<CsrfResponse>
  onApplied: (ruling: ConfirmedRuling, question: string) => void
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

  function applyRuling(value: ConfirmedRuling) {
    ruling.value = value
    editedVerdict.value = value.shortVerdict
    editedExplanation.value = value.explanation
    conflict.value = false
    editing.value = false
    options.onApplied(value, options.answeredQuestion.value)
  }

  function reset() {
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
    const documentVersionId = options.documentVersionId.value
    if (!answer || answer.status !== 'ANSWERED' || !documentVersionId || saving.value) return
    saving.value = true
    error.value = ''
    try {
      const csrf = await options.csrfToken()
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
          citationChunkIds: answer.citations.map((citation) => citation.chunkId),
          exceptions: answer.exceptions,
          confidence: answer.confidence,
        }),
      })
      if (!response.ok) throw new Error(options.messages.createFailed())
      applyRuling((await response.json()) as ConfirmedRuling)
    } catch (caught) {
      error.value = caught instanceof Error ? caught.message : options.messages.createRequestFailed()
    } finally {
      saving.value = false
    }
  }

  async function saveRulingRevision() {
    const currentRuling = ruling.value
    if (!currentRuling || saving.value) return
    saving.value = true
    error.value = ''
    conflict.value = false
    try {
      const csrf = await options.csrfToken()
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
      if (response.status === 409) {
        conflict.value = true
        return
      }
      if (!response.ok) throw new Error(options.messages.updateFailed())
      applyRuling((await response.json()) as ConfirmedRuling)
    } catch (caught) {
      error.value = caught instanceof Error ? caught.message : options.messages.updateRequestFailed()
    } finally {
      saving.value = false
    }
  }

  async function reloadRuling() {
    const currentRuling = ruling.value
    if (!currentRuling) return
    saving.value = true
    try {
      const response = await fetch(`/api/v1/confirmed-rulings/${currentRuling.id}`, { credentials: 'include' })
      if (!response.ok) throw new Error(options.messages.reloadFailed())
      applyRuling((await response.json()) as ConfirmedRuling)
    } catch (caught) {
      error.value = caught instanceof Error ? caught.message : options.messages.reloadRequestFailed()
    } finally {
      saving.value = false
    }
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
    reset,
  }
}
