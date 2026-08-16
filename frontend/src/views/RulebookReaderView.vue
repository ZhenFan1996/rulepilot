<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import ConversationResetDialog from '@/components/ConversationResetDialog.vue'
import LessonAnswerPanel from '@/components/LessonAnswerPanel.vue'
import RulebookPageViewer from '@/components/RulebookPageViewer.vue'
import { useConfirmedRuling } from '@/composables/useConfirmedRuling'
import { useLessonAnswers, type CsrfResponse } from '@/composables/useLessonAnswers'
import { useLessonQuestionInput } from '@/composables/useLessonQuestionInput'
import { useModalFocus } from '@/composables/useModalFocus'
import { notifyLoginRequired } from '@/lib/authSession'
import {
  forgetLessonAnswerThread,
  readLessonAnswerThread,
  rememberLessonAnswerThread,
  type LessonAnswerThreadScope,
} from '@/lib/lessonAnswerThread'
import { useLocale } from '@/lib/locale'

interface RulebookPage { pageNumber: number; text: string; characterCount: number }
interface DocumentSummary {
  document: { title: string }
  latestVersion: { id: string; status: string; originalFilename: string }
}

const CardOcrCapture = defineAsyncComponent(() => import('@/components/CardOcrCapture.vue'))
const route = useRoute()
const { locale, t } = useLocale()
const versionId = computed(() => String(route.params.versionId ?? ''))
const workspaceId = computed(() => `rulebook:${versionId.value}`)
const title = ref('')
const filename = ref('')
const pages = ref<RulebookPage[]>([])
const loading = ref(true)
const errorMessage = ref('')
const username = ref('')
const online = ref(navigator.onLine)
const answersOpen = ref(false)
const cardOcrOpen = ref(false)
const answerPanel = ref<{ focusQuestion?: () => void } | null>(null)
const resetDialogOpen = ref(false)
const restoreAfterReset = ref(false)
const answersDialog = ref<HTMLElement | null>(null)
let loadSequence = 0
let disposed = false

useModalFocus({
  dialog: answersDialog,
  open: answersOpen,
  requestClose: () => { answersOpen.value = false },
})

const copy = computed(() => locale.value === 'zh-CN' ? {
  back: '返回规则书', eyebrow: '原规则书', pages: `${pages.value.length} 页`,
  loading: '正在打开规则书…', error: '暂时无法打开这本规则书。', retry: '重试',
  answer: '基于这本规则书答疑', close: '关闭答疑', hint: '讲解不是必经步骤；你可以边读边问。',
} : {
  back: 'Back to rulebooks', eyebrow: 'Original rulebook', pages: `${pages.value.length} pages`,
  loading: 'Opening the rulebook…', error: 'This rulebook cannot be opened right now.', retry: 'Retry',
  answer: 'Ask from this rulebook', close: 'Close questions', hint: 'A generated lesson is optional. Read and ask as you go.',
})

function isCurrentLoad(sequence: number, target: string) {
  return !disposed && sequence === loadSequence && (target === versionId.value || target === workspaceId.value)
}

function answerThreadScope(): LessonAnswerThreadScope | null {
  if (!username.value || !versionId.value) return null
  return { username: username.value, planId: workspaceId.value, documentVersionId: versionId.value, locale: locale.value }
}

const {
  question, answer, answeredQuestion, answerTurns, activeLearningIntent, answerLoading, answerError, answerOutcome,
  agentTrace, answerRulingReference, cancelAnswer, clearAnswerFeedback, resetConversation, restoreConversation, submitQuestion,
} = useLessonAnswers({
  currentContext: () => versionId.value && online.value
    ? { planId: workspaceId.value, documentVersionId: versionId.value, locale: locale.value }
    : null,
  currentLessonRequest: () => loadSequence,
  isCurrentLessonLoad: isCurrentLoad,
  requestLogin: async () => notifyLoginRequired(),
  onReceived: (_context, _text, received, reference) => {
    const scope = answerThreadScope()
    if (scope) rememberLessonAnswerThread(sessionStorage, scope, answerTurns.value)
    if (reference.confirmedRulingId !== null && reference.confirmedRulingVersion !== null
      && reference.citationIds.length === received.citations.length) {
      applyRuling({
        id: reference.confirmedRulingId, shortVerdict: received.shortVerdict, explanation: received.explanation,
        citations: received.citations.map((citation, index) => ({
          ...citation, chunkId: reference.citationIds[index]!, sectionType: '',
        })),
        exceptions: received.exceptions, confidence: received.confidence,
        status: 'CONFIRMED', version: reference.confirmedRulingVersion,
      })
    } else resetRuling()
  },
})

function learningAnchorQuestion() {
  for (let index = answerTurns.value.length - 1; index >= 0; index--) {
    const turn = answerTurns.value[index]
    if (turn?.learningIntent === null) return turn.question
  }
  return answeredQuestion.value
}

const { askQuestion, requestLearningHelp, useCardText, useVoiceTranscript } = useLessonQuestionInput({
  question,
  learningAnchorQuestion,
  submitQuestion,
  clearAnswerFeedback,
  closeCardOcr: () => { cardOcrOpen.value = false },
})

async function csrfToken() {
  const response = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (response.status === 401) {
    notifyLoginRequired()
    throw new Error(t('lesson.reader.error.loginRequired'))
  }
  if (!response.ok) throw new Error(t('lesson.reader.error.secureSession'))
  return await response.json() as CsrfResponse
}

const {
  ruling, saving: rulingSaving, error: rulingError, conflict: rulingConflict, editing: editingRuling,
  editedVerdict, editedExplanation, applyRuling, confirmAnswer, saveRulingRevision, reloadRuling, reset: resetRuling,
} = useConfirmedRuling({
  documentVersionId: computed(() => versionId.value || null), answer, answeredQuestion, csrfToken,
  rulingReference: answerRulingReference,
  onApplied: () => undefined,
  messages: {
    createFailed: () => t('lesson.answer.ruling.createFailed'),
    createRequestFailed: () => t('lesson.answer.ruling.createRequestFailed'),
    updateFailed: () => t('lesson.answer.ruling.updateFailed'),
    updateRequestFailed: () => t('lesson.answer.ruling.updateRequestFailed'),
    reloadFailed: () => t('lesson.answer.ruling.reloadFailed'),
    reloadRequestFailed: () => t('lesson.answer.ruling.reloadRequestFailed'),
  },
})

function requestClearThread() {
  if (answerLoading.value || rulingSaving.value || editingRuling.value) return
  resetDialogOpen.value = true
  restoreAfterReset.value = false
}

function cancelClearThread() {
  resetDialogOpen.value = false
  restoreAfterReset.value = false
}

function resetRestoreTarget() {
  if (!restoreAfterReset.value) return null
  restoreAfterReset.value = false
  answerPanel.value?.focusQuestion?.()
  return document.activeElement instanceof HTMLElement ? document.activeElement : null
}

function confirmClearThread() {
  const scope = answerThreadScope()
  if (scope) forgetLessonAnswerThread(sessionStorage, scope)
  resetConversation(false)
  resetRuling()
  restoreAfterReset.value = true
  resetDialogOpen.value = false
}

async function loadRulebook() {
  const target = versionId.value
  const sequence = ++loadSequence
  loading.value = true
  errorMessage.value = ''
  pages.value = []
  resetConversation(true)
  resetRuling()
  if (!target) return
  try {
    const [pagesResponse, documentsResponse, sessionResponse] = await Promise.all([
      fetch(`/api/v1/document-versions/${encodeURIComponent(target)}/pages`, { credentials: 'include' }),
      fetch('/api/v1/documents', { credentials: 'include' }),
      fetch('/api/auth/session', { credentials: 'include' }),
    ])
    if (!isCurrentLoad(sequence, target)) return
    if (pagesResponse.status === 401 || documentsResponse.status === 401) {
      notifyLoginRequired()
      throw new Error(t('lesson.reader.error.loginRequired'))
    }
    if (!pagesResponse.ok || !documentsResponse.ok) throw new Error(copy.value.error)
    const loadedPages = await pagesResponse.json() as RulebookPage[]
    const documents = await documentsResponse.json() as DocumentSummary[]
    const matched = documents.find(entry => entry.latestVersion.id === target)
    if (!matched || matched.latestVersion.status !== 'READY' || !loadedPages.length) throw new Error(copy.value.error)
    pages.value = loadedPages
    title.value = matched.document.title
    filename.value = matched.latestVersion.originalFilename
    if (sessionResponse.ok) {
      const session = await sessionResponse.json() as { username?: unknown }
      username.value = typeof session.username === 'string' ? session.username.trim() : ''
    }
    const scope = answerThreadScope()
    restoreConversation(scope ? readLessonAnswerThread(sessionStorage, scope) : [])
  } catch (error) {
    if (isCurrentLoad(sequence, target)) errorMessage.value = error instanceof Error ? error.message : copy.value.error
  } finally {
    if (isCurrentLoad(sequence, target)) loading.value = false
  }
}

function updateOnline() { online.value = navigator.onLine }

onMounted(() => {
  disposed = false
  void loadRulebook()
  window.addEventListener('online', updateOnline)
  window.addEventListener('offline', updateOnline)
})
watch(versionId, () => { void loadRulebook() })
watch(locale, () => {
  const scope = answerThreadScope()
  restoreConversation(scope ? readLessonAnswerThread(sessionStorage, scope) : [], false)
  resetRuling()
})
onUnmounted(() => {
  disposed = true
  loadSequence++
  window.removeEventListener('online', updateOnline)
  window.removeEventListener('offline', updateOnline)
})
</script>

<template>
  <AppShell>
    <div class="min-h-screen bg-[#ddd8cf] text-ink">
      <header class="app-sticky-top sticky z-30 border-b border-ink/10 bg-paper/95 backdrop-blur">
        <div class="mx-auto flex max-w-[100rem] items-center justify-between gap-4 px-4 py-3 sm:px-6">
          <div class="min-w-0">
            <RouterLink :to="{ name: 'teach' }" class="text-xs font-semibold text-indigo">← {{ copy.back }}</RouterLink>
            <div class="mt-1 flex min-w-0 items-baseline gap-3">
              <h1 class="truncate font-display text-xl font-semibold sm:text-2xl">{{ title || filename }}</h1>
              <span v-if="pages.length" class="shrink-0 text-xs text-ink/45">{{ copy.pages }}</span>
            </div>
          </div>
          <button type="button" class="min-h-11 shrink-0 rounded-xl bg-copper px-4 text-sm font-semibold text-white" @click="answersOpen = true">{{ copy.answer }}</button>
        </div>
      </header>

      <div class="mx-auto max-w-[100rem] px-3 py-4 sm:px-6">
        <p v-if="loading" class="rounded-xl bg-paper p-8 text-center text-sm text-ink/55" role="status">{{ copy.loading }}</p>
        <section v-else-if="errorMessage" class="rounded-xl border border-red-200 bg-paper p-8 text-center" role="alert">
          <p class="font-semibold">{{ errorMessage }}</p>
          <button type="button" class="mt-4 min-h-11 rounded-lg bg-indigo px-5 text-sm font-semibold text-white" @click="loadRulebook">{{ copy.retry }}</button>
        </section>
        <RulebookPageViewer v-else :version-id="versionId" :pages="pages" :eyebrow="copy.eyebrow" :hint="copy.hint" />
      </div>

      <button v-if="!answersOpen && !loading && !errorMessage" type="button" class="fixed bottom-5 right-4 z-30 min-h-12 rounded-full bg-copper px-5 text-sm font-bold text-white shadow-xl sm:right-6" @click="answersOpen = true">{{ copy.answer }}</button>

      <div v-if="answersOpen" class="fixed inset-0 z-50 bg-ink/40 backdrop-blur-[2px]" @click.self="answersOpen = false">
        <aside ref="answersDialog" tabindex="-1" class="absolute inset-y-0 right-0 w-full max-w-2xl overflow-y-auto border-l border-ink/10 bg-canvas p-4 shadow-2xl outline-none sm:p-6" role="dialog" aria-modal="true" :aria-label="copy.answer">
          <div class="app-sticky-top sticky z-10 flex items-center justify-between border-b border-ink/10 bg-canvas/95 pb-3 backdrop-blur">
            <div><p class="font-semibold">{{ copy.answer }}</p><p class="mt-1 text-xs text-ink/45">{{ copy.hint }}</p></div>
            <button type="button" data-modal-initial-focus class="grid min-h-11 min-w-11 place-items-center rounded-lg text-2xl text-ink/50 hover:bg-ink/5" :aria-label="copy.close" @click="answersOpen = false">×</button>
          </div>
          <LessonAnswerPanel
            ref="answerPanel"
            :question="question" :answer="answer" :answered-question="answeredQuestion" :answer-turns="answerTurns"
            :active-learning-intent="activeLearningIntent" :answer-loading="answerLoading" :answer-error="answerError" :answer-outcome="answerOutcome"
            :agent-trace="agentTrace" :online="online" :ruling="ruling"
            :ruling-saving="rulingSaving" :clear-thread-disabled="rulingSaving || editingRuling" :ruling-error="rulingError" :ruling-conflict="rulingConflict"
            :editing-ruling="editingRuling" :edited-verdict="editedVerdict" :edited-explanation="editedExplanation"
            @update:question="question = $event" @update:editing-ruling="editingRuling = $event"
            @update:edited-verdict="editedVerdict = $event" @update:edited-explanation="editedExplanation = $event"
            @ask="askQuestion" @cancel-answer="cancelAnswer" @request-help="requestLearningHelp"
            @open-card-ocr="cardOcrOpen = true" @voice-transcript="useVoiceTranscript" @clear-thread="requestClearThread"
            @confirm-ruling="confirmAnswer" @reload-ruling="reloadRuling" @save-ruling-revision="saveRulingRevision"
          />
        </aside>
      </div>
      <CardOcrCapture v-if="cardOcrOpen" @close="cardOcrOpen = false" @recognized="useCardText" />
      <ConversationResetDialog
        kind="private-browser"
        :open="resetDialogOpen"
        :turn-count="answerTurns.length"
        :restore-focus="resetRestoreTarget"
        @cancel="cancelClearThread"
        @confirm="confirmClearThread"
      />
    </div>
  </AppShell>
</template>
