<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import CardOcrCapture from '@/components/CardOcrCapture.vue'
import LessonAnswerPanel from '@/components/LessonAnswerPanel.vue'
import LessonOfflineKnowledgePanel from '@/components/LessonOfflineKnowledgePanel.vue'
import { useConfirmedRuling } from '@/composables/useConfirmedRuling'
import { useLessonAnswers, type CsrfResponse } from '@/composables/useLessonAnswers'
import { useLessonQuestionInput } from '@/composables/useLessonQuestionInput'
import { notifyLoginRequired } from '@/lib/authSession'
import {
  forgetLessonAnswerThread,
  readLessonAnswerThread,
  rememberLessonAnswerThread,
  type LessonAnswerThreadScope,
} from '@/lib/lessonAnswerThread'
import { useLocale } from '@/lib/locale'
import {
  cacheOfflineAnswer,
  cacheOfflineRuling,
  loadOfflineKnowledge,
  type OfflineKnowledgeEntry,
} from '@/lib/offlineKnowledge'

interface TeachingPlan {
  id: string
  documentVersionId: string
  gameTitle: string
}

interface IllustratedLesson {
  id: string
}

const route = useRoute()
const router = useRouter()
const { locale, t } = useLocale()
const planId = computed(() => String(route.params.planId ?? ''))
const plan = ref<TeachingPlan | null>(null)
const lesson = ref<IllustratedLesson | null>(null)
const loading = ref(true)
const errorMessage = ref('')
const online = ref(navigator.onLine)
const cardOcrOpen = ref(false)
const offlineKnowledge = ref<OfflineKnowledgeEntry[]>([])
const answerThreadUsername = ref('')
let latestWorkspaceLoad = 0
let disposed = false

function isCurrentWorkspaceLoad(request: number, targetPlanId: string) {
  return !disposed && request === latestWorkspaceLoad && targetPlanId === planId.value
}

function refreshOfflineKnowledge(targetPlanId = planId.value) {
  offlineKnowledge.value = loadOfflineKnowledge(targetPlanId)
}

const {
  question,
  answer,
  answeredQuestion,
  answerTurns,
  activeLearningIntent,
  answerLoading,
  answerError,
  agentTrace,
  answerRunId,
  cancelAnswer,
  clearAnswerFeedback,
  resetConversation,
  restoreConversation,
  submitQuestion,
} = useLessonAnswers({
  currentContext: () => {
    if (!plan.value || !online.value) return null
    return {
      planId: planId.value,
      documentVersionId: plan.value.documentVersionId,
      locale: locale.value,
    }
  },
  currentLessonRequest: () => latestWorkspaceLoad,
  isCurrentLessonLoad: isCurrentWorkspaceLoad,
  requestLogin: async () => notifyLoginRequired(),
  onReceived: (context, text, received) => {
    rememberCurrentAnswerThread()
    if (received.status === 'ANSWERED') {
      cacheOfflineAnswer(context.planId, text, received)
      refreshOfflineKnowledge(context.planId)
    }
    if (received.confirmedRulingId !== null && received.confirmedRulingVersion !== null) {
      applyRuling({
        id: received.confirmedRulingId,
        shortVerdict: received.shortVerdict,
        explanation: received.explanation,
        citations: received.citations,
        exceptions: received.exceptions,
        confidence: received.confidence,
        status: 'CONFIRMED',
        version: received.confirmedRulingVersion,
      })
    } else {
      resetRuling()
    }
  },
})

function currentAnswerThreadScope(): LessonAnswerThreadScope | null {
  if (!answerThreadUsername.value || !plan.value) return null
  return {
    username: answerThreadUsername.value,
    planId: planId.value,
    documentVersionId: plan.value.documentVersionId,
    locale: locale.value,
  }
}

function rememberCurrentAnswerThread() {
  const scope = currentAnswerThreadScope()
  if (scope) rememberLessonAnswerThread(sessionStorage, scope, answerTurns.value)
}

function restoreCurrentAnswerThread() {
  const scope = currentAnswerThreadScope()
  restoreConversation(scope ? readLessonAnswerThread(sessionStorage, scope) : [])
}

function clearCurrentAnswerThread() {
  const scope = currentAnswerThreadScope()
  if (scope) forgetLessonAnswerThread(sessionStorage, scope)
  resetConversation(true)
  resetRuling()
}

function learningAnchorQuestion() {
  for (let index = answerTurns.value.length - 1; index >= 0; index--) {
    const turn = answerTurns.value[index]
    if (turn?.learningIntent === null) return turn.question
  }
  return answeredQuestion.value
}

const {
  askQuestion,
  requestLearningHelp,
  useCardText,
  useVoiceTranscript,
} = useLessonQuestionInput({
  question,
  learningAnchorQuestion,
  submitQuestion,
  clearAnswerFeedback,
  closeCardOcr: () => { cardOcrOpen.value = false },
})

const {
  ruling,
  saving: rulingSaving,
  error: rulingError,
  conflict: rulingConflict,
  editing: editingRuling,
  editedVerdict,
  editedExplanation,
  applyRuling,
  confirmAnswer,
  saveRulingRevision,
  reloadRuling,
  reset: resetRuling,
} = useConfirmedRuling({
  documentVersionId: computed(() => plan.value?.documentVersionId ?? null),
  answer,
  answeredQuestion,
  csrfToken,
  onApplied: (value, answered) => {
    cacheOfflineRuling(planId.value, answered, value)
    refreshOfflineKnowledge()
  },
  messages: {
    createFailed: () => t('lesson.answer.ruling.createFailed'),
    createRequestFailed: () => t('lesson.answer.ruling.createRequestFailed'),
    updateFailed: () => t('lesson.answer.ruling.updateFailed'),
    updateRequestFailed: () => t('lesson.answer.ruling.updateRequestFailed'),
    reloadFailed: () => t('lesson.answer.ruling.reloadFailed'),
    reloadRequestFailed: () => t('lesson.answer.ruling.reloadRequestFailed'),
  },
})

async function csrfToken() {
  const response = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (response.status === 401) {
    notifyLoginRequired()
    throw new Error(t('lesson.reader.error.loginRequired'))
  }
  if (!response.ok) throw new Error(t('lesson.reader.error.secureSession'))
  return (await response.json()) as CsrfResponse
}

async function loadWorkspace() {
  const targetPlanId = planId.value
  const request = ++latestWorkspaceLoad
  loading.value = true
  errorMessage.value = ''
  plan.value = null
  lesson.value = null
  answerThreadUsername.value = ''
  resetConversation(true)
  resetRuling()
  cardOcrOpen.value = false
  refreshOfflineKnowledge(targetPlanId)
  if (!targetPlanId) {
    await router.replace({ name: 'lessons' })
    if (isCurrentWorkspaceLoad(request, targetPlanId)) loading.value = false
    return
  }
  try {
    const [planResponse, lessonResponse, sessionResponse] = await Promise.all([
      fetch(`/api/v1/teaching-plans/${targetPlanId}`, { credentials: 'include' }),
      fetch(`/api/v1/teaching-plans/${targetPlanId}/illustrated-lessons/latest`, { credentials: 'include' }),
      fetch('/api/auth/session', { credentials: 'include' }),
    ])
    if (!isCurrentWorkspaceLoad(request, targetPlanId)) return
    if (planResponse.status === 401 || lessonResponse.status === 401) {
      notifyLoginRequired()
      errorMessage.value = t('lesson.reader.error.loginRequired')
      return
    }
    if (!planResponse.ok || !lessonResponse.ok) throw new Error(t('questions.error'))
    plan.value = await planResponse.json() as TeachingPlan
    lesson.value = await lessonResponse.json() as IllustratedLesson
    if (sessionResponse.ok) {
      const session = await sessionResponse.json() as { username?: unknown }
      if (typeof session.username === 'string') answerThreadUsername.value = session.username.trim()
    }
    if (!isCurrentWorkspaceLoad(request, targetPlanId)) return
    restoreCurrentAnswerThread()
  } catch (error) {
    if (!isCurrentWorkspaceLoad(request, targetPlanId)) return
    errorMessage.value = error instanceof Error ? error.message : t('questions.error')
  } finally {
    if (isCurrentWorkspaceLoad(request, targetPlanId)) loading.value = false
  }
}

function updateOnlineStatus() {
  online.value = navigator.onLine
  if (!online.value) refreshOfflineKnowledge()
}

onMounted(() => {
  disposed = false
  void loadWorkspace()
  window.addEventListener('online', updateOnlineStatus)
  window.addEventListener('offline', updateOnlineStatus)
})

watch(planId, () => { void loadWorkspace() })
watch(locale, () => {
  restoreCurrentAnswerThread()
  resetRuling()
})

onUnmounted(() => {
  disposed = true
  latestWorkspaceLoad++
  window.removeEventListener('online', updateOnlineStatus)
  window.removeEventListener('offline', updateOnlineStatus)
})
</script>

<template>
  <AppShell>
    <div class="min-h-screen bg-canvas pb-20 text-ink">
      <header class="sticky top-0 z-20 border-b border-ink/10 bg-canvas/90 backdrop-blur">
        <div class="mx-auto flex max-w-4xl items-center justify-between gap-4 px-5 py-4 sm:px-8">
          <RouterLink :to="{ name: 'lesson', params: { planId } }" class="text-sm font-semibold text-indigo">← {{ t('questions.back') }}</RouterLink>
        </div>
      </header>

      <p v-if="!online" class="bg-amber-100 px-5 py-3 text-center text-sm font-semibold text-amber-900" role="status">{{ t('questions.offline') }}</p>
      <LessonOfflineKnowledgePanel v-if="!online && offlineKnowledge.length" :entries="offlineKnowledge" />

      <main class="mx-auto max-w-4xl px-5 py-9 sm:px-8 lg:py-14">
        <div v-if="loading" aria-live="polite">
          <div class="h-4 w-32 animate-pulse rounded bg-ink/10" />
          <div class="mt-4 h-12 w-3/5 animate-pulse rounded bg-ink/10" />
          <div class="mt-8 h-96 animate-pulse rounded-3xl bg-paper" />
        </div>

        <section v-else-if="errorMessage || !lesson" class="rounded-3xl border border-red-200 bg-paper p-7 text-center shadow-sm" role="alert">
          <p class="font-display text-2xl font-semibold">{{ t('questions.errorTitle') }}</p>
          <p class="mt-3 text-sm leading-6 text-ink/60">{{ errorMessage || t('questions.error') }}</p>
          <button type="button" class="mt-5 min-h-11 rounded-xl bg-indigo px-5 text-sm font-semibold text-white" @click="loadWorkspace">{{ t('lesson.reader.state.error.retry') }}</button>
        </section>

        <template v-else>
          <header class="border-b border-ink/10 pb-7">
            <p class="text-xs font-semibold uppercase tracking-[0.14em] text-copper">{{ t('questions.eyebrow') }}</p>
            <h1 class="mt-2 font-display text-4xl font-semibold tracking-tight sm:text-5xl">{{ t('questions.title', { game: plan?.gameTitle ?? '' }) }}</h1>
            <p class="mt-4 max-w-2xl leading-7 text-ink/60">{{ t('questions.description') }}</p>
          </header>

          <LessonAnswerPanel
            :question="question"
            :answer="answer"
            :answered-question="answeredQuestion"
            :answer-turns="answerTurns"
            :active-learning-intent="activeLearningIntent"
            :answer-loading="answerLoading"
            :answer-error="answerError"
            :agent-trace="agentTrace"
            :answer-run-id="answerRunId"
            :online="online"
            :ruling="ruling"
            :ruling-saving="rulingSaving"
            :ruling-error="rulingError"
            :ruling-conflict="rulingConflict"
            :editing-ruling="editingRuling"
            :edited-verdict="editedVerdict"
            :edited-explanation="editedExplanation"
            @update:question="question = $event"
            @update:editing-ruling="editingRuling = $event"
            @update:edited-verdict="editedVerdict = $event"
            @update:edited-explanation="editedExplanation = $event"
            @ask="askQuestion"
            @cancel-answer="cancelAnswer"
            @request-help="requestLearningHelp"
            @open-card-ocr="cardOcrOpen = true"
            @voice-transcript="useVoiceTranscript"
            @clear-thread="clearCurrentAnswerThread"
            @confirm-ruling="confirmAnswer"
            @reload-ruling="reloadRuling"
            @save-ruling-revision="saveRulingRevision"
          />
        </template>
      </main>

      <CardOcrCapture v-if="cardOcrOpen" @close="cardOcrOpen = false" @recognized="useCardText" />
    </div>
  </AppShell>
</template>
