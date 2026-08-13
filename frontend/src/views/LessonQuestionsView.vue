<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import ConversationResetDialog from '@/components/ConversationResetDialog.vue'
import LessonAnswerPanel from '@/components/LessonAnswerPanel.vue'
import LessonGuideHero from '@/components/LessonGuideHero.vue'
import LessonModeNav from '@/components/LessonModeNav.vue'
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
import type { CatalogGamePresentation } from '@/lib/catalogGamePresentation'
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
  teachingPlanId: string
}

const CardOcrCapture = defineAsyncComponent(() => import('@/components/CardOcrCapture.vue'))

const route = useRoute()
const router = useRouter()
const { locale, t } = useLocale()
const planId = computed(() => String(route.params.planId ?? ''))
const plan = ref<TeachingPlan | null>(null)
const lesson = ref<IllustratedLesson | null>(null)
const catalogPresentation = ref<CatalogGamePresentation | null>(null)
const catalogCoverUnavailable = ref(false)
const loading = ref(true)
const errorMessage = ref('')
const online = ref(navigator.onLine)
const cardOcrOpen = ref(false)
const offlineKnowledge = ref<OfflineKnowledgeEntry[]>([])
const answerThreadUsername = ref('')
const answerPanel = ref<{ focusQuestion?: () => void } | null>(null)
const resetDialogOpen = ref(false)
const restoreAfterReset = ref(false)
let latestWorkspaceLoad = 0
let disposed = false
let shellIdentityResolved = false
let activeWorkspaceController: AbortController | null = null

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
  cancelReadTransport: cancelAnswerReads,
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
  canRead: () => online.value,
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

function restoreCurrentAnswerThread(clearQuestion = true) {
  const scope = currentAnswerThreadScope()
  restoreConversation(scope ? readLessonAnswerThread(sessionStorage, scope) : [], clearQuestion)
}

function requestClearCurrentAnswerThread() {
  if (answerLoading.value || rulingSaving.value || editingRuling.value) return
  resetDialogOpen.value = true
  restoreAfterReset.value = false
}

function cancelClearCurrentAnswerThread() {
  resetDialogOpen.value = false
  restoreAfterReset.value = false
}

function resetRestoreTarget() {
  if (!restoreAfterReset.value) return null
  restoreAfterReset.value = false
  answerPanel.value?.focusQuestion?.()
  return document.activeElement instanceof HTMLElement ? document.activeElement : null
}

function confirmClearCurrentAnswerThread() {
  const scope = currentAnswerThreadScope()
  if (scope) forgetLessonAnswerThread(sessionStorage, scope)
  resetConversation(false)
  resetRuling()
  restoreAfterReset.value = true
  resetDialogOpen.value = false
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
  cancelReads: cancelRulingReads,
  reset: resetRuling,
} = useConfirmedRuling({
  documentVersionId: computed(() => plan.value?.documentVersionId ?? null),
  answer,
  answeredQuestion,
  csrfToken,
  currentReadContext: () => plan.value
    ? `${planId.value}:${plan.value.documentVersionId}`
    : null,
  isCurrentReadContext: (context) => context === `${planId.value}:${plan.value?.documentVersionId ?? ''}`,
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

function updateSessionIdentity(username: string) {
  const normalizedUsername = username.trim()
  const previousUsername = answerThreadUsername.value
  const identityWasResolved = shellIdentityResolved
  if (identityWasResolved && normalizedUsername === previousUsername) return
  shellIdentityResolved = true
  answerThreadUsername.value = normalizedUsername
  if (plan.value) {
    restoreCurrentAnswerThread(identityWasResolved && previousUsername !== normalizedUsername)
    loading.value = false
  }
}

function cancelWorkspaceReads() {
  activeWorkspaceController?.abort()
  activeWorkspaceController = null
  cancelAnswerReads()
  cancelRulingReads()
}

async function loadCatalogPresentation(targetPlanId: string, signal: AbortSignal) {
  try {
    const response = await fetch(
      `/api/v1/teaching-plans/${encodeURIComponent(targetPlanId)}/catalog-presentation`,
      { credentials: 'include', signal },
    )
    return response.ok ? await response.json() as CatalogGamePresentation : null
  } catch {
    if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
    return null
  }
}

async function loadWorkspace() {
  const targetPlanId = planId.value
  const request = ++latestWorkspaceLoad
  cancelWorkspaceReads()
  loading.value = true
  errorMessage.value = ''
  plan.value = null
  lesson.value = null
  catalogPresentation.value = null
  catalogCoverUnavailable.value = false
  resetConversation(true)
  resetRuling()
  cardOcrOpen.value = false
  refreshOfflineKnowledge(targetPlanId)
  if (!targetPlanId) {
    await router.replace({ name: 'lessons' })
    if (isCurrentWorkspaceLoad(request, targetPlanId)) loading.value = false
    return
  }
  if (!online.value) {
    loading.value = false
    return
  }
  const controller = new AbortController()
  activeWorkspaceController = controller
  try {
    const [planResponse, lessonResponse, loadedCatalogPresentation] = await Promise.all([
      fetch(`/api/v1/teaching-plans/${encodeURIComponent(targetPlanId)}`, {
        credentials: 'include', signal: controller.signal,
      }),
      fetch(`/api/v1/teaching-plans/${encodeURIComponent(targetPlanId)}/illustrated-lessons/latest`, {
        credentials: 'include', signal: controller.signal,
      }),
      loadCatalogPresentation(targetPlanId, controller.signal),
    ])
    if (!isCurrentWorkspaceRead(request, targetPlanId, controller)) return
    if (planResponse.status === 401 || lessonResponse.status === 401) {
      notifyLoginRequired()
      errorMessage.value = t('lesson.reader.error.loginRequired')
      return
    }
    if (!planResponse.ok || !lessonResponse.ok) throw new Error(t('questions.error'))
    const [loadedPlan, loadedLesson] = await Promise.all([
      planResponse.json() as Promise<TeachingPlan>,
      lessonResponse.json() as Promise<IllustratedLesson>,
    ])
    if (!isCurrentWorkspaceRead(request, targetPlanId, controller)) return
    if (loadedPlan.id !== targetPlanId || loadedLesson.teachingPlanId !== targetPlanId) {
      throw new Error(t('questions.error'))
    }
    plan.value = loadedPlan
    lesson.value = loadedLesson
    catalogPresentation.value = loadedCatalogPresentation
    if (shellIdentityResolved) {
      restoreCurrentAnswerThread()
      loading.value = false
    }
  } catch (error) {
    if (!isCurrentWorkspaceRead(request, targetPlanId, controller) || controller.signal.aborted) return
    controller.abort()
    errorMessage.value = error instanceof Error ? error.message : t('questions.error')
  } finally {
    if (isCurrentWorkspaceRead(request, targetPlanId, controller)) {
      activeWorkspaceController = null
      if (errorMessage.value) loading.value = false
    }
  }
}

function isCurrentWorkspaceRead(
  request: number,
  targetPlanId: string,
  controller: AbortController,
) {
  return isCurrentWorkspaceLoad(request, targetPlanId)
    && activeWorkspaceController === controller
}

function updateOnlineStatus() {
  online.value = navigator.onLine
  if (!online.value) {
    cancelWorkspaceReads()
    loading.value = false
    refreshOfflineKnowledge()
    return
  }
  if (!plan.value || !lesson.value) void loadWorkspace()
}

onMounted(() => {
  disposed = false
  void loadWorkspace()
  window.addEventListener('online', updateOnlineStatus)
  window.addEventListener('offline', updateOnlineStatus)
})

watch(planId, () => { void loadWorkspace() })
watch(locale, () => {
  restoreCurrentAnswerThread(false)
  resetRuling()
})

onUnmounted(() => {
  disposed = true
  latestWorkspaceLoad++
  cancelWorkspaceReads()
  window.removeEventListener('online', updateOnlineStatus)
  window.removeEventListener('offline', updateOnlineStatus)
})
</script>

<template>
  <AppShell @session-identity="updateSessionIdentity">
    <div class="min-h-screen bg-canvas pb-20 text-ink">
      <header class="app-sticky-top sticky z-20 border-b border-ink/10 bg-canvas/90 backdrop-blur">
        <div class="mx-auto flex max-w-4xl items-center justify-between gap-4 px-5 py-4 sm:px-8">
          <RouterLink :to="{ name: 'lessons' }" class="text-sm font-semibold text-indigo">← {{ t('lesson.reader.back') }}</RouterLink>
          <LessonModeNav
            :plan-id="planId"
            guide-route="lesson"
            questions-route="lesson-questions"
            active="questions"
          />
        </div>
      </header>

      <p v-if="!online" class="bg-amber-100 px-5 py-3 text-center text-sm font-semibold text-amber-900" role="status">{{ t('questions.offline') }}</p>
      <LessonOfflineKnowledgePanel v-if="!online && offlineKnowledge.length" :entries="offlineKnowledge" />

      <div class="tabletop-page max-w-4xl">
        <div v-if="loading" aria-live="polite">
          <div class="h-4 w-32 animate-pulse rounded bg-ink/10" />
          <div class="mt-4 h-12 w-3/5 animate-pulse rounded bg-ink/10" />
          <div class="mt-8 h-96 animate-pulse rounded-3xl bg-paper" />
        </div>

        <section v-else-if="errorMessage || !lesson" class="rounded-3xl border border-red-200 bg-paper p-7 text-center elevation-sm" role="alert">
          <p class="font-display text-2xl font-semibold">{{ t('questions.errorTitle') }}</p>
          <p class="mt-3 text-sm leading-6 text-ink/60">{{ errorMessage || t('questions.error') }}</p>
          <button type="button" class="mt-5 min-h-11 rounded-xl bg-indigo px-5 text-sm font-semibold text-white" @click="loadWorkspace">{{ t('lesson.reader.state.error.retry') }}</button>
        </section>

        <template v-else>
          <LessonGuideHero
            :title="t('questions.title', { game: catalogPresentation?.gameName ?? plan?.gameTitle ?? '' })"
            :eyebrow="t('questions.eyebrow')"
            :description="t('questions.description')"
            :rulebook-title="catalogPresentation ? plan?.gameTitle : ''"
            :cover-url="catalogPresentation?.thumbnailUrl ?? ''"
            :cover-alt="t('lesson.catalog.coverAlt', { game: catalogPresentation?.gameName ?? '' })"
            :cover-href="catalogPresentation?.bggUrl ?? ''"
            :cover-unavailable="catalogCoverUnavailable"
            compact
            @cover-error="catalogCoverUnavailable = true"
          >
            <template v-if="catalogPresentation" #actions>
              <a :href="catalogPresentation.bggUrl" target="_blank" rel="noopener noreferrer" class="inline-flex min-h-10 items-center rounded-xl border border-[rgba(248,239,223,0.2)] bg-[rgba(248,239,223,0.08)] px-3 text-xs font-semibold text-[rgba(248,239,223,0.78)] underline decoration-[rgba(248,239,223,0.3)] underline-offset-2">
                {{ t('lesson.catalog.attribution') }} ↗
              </a>
            </template>
          </LessonGuideHero>

          <LessonAnswerPanel
            ref="answerPanel"
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
            :clear-thread-disabled="rulingSaving || editingRuling"
            :ruling-error="rulingError"
            :ruling-conflict="rulingConflict"
            :editing-ruling="editingRuling"
            :edited-verdict="editedVerdict"
            :edited-explanation="editedExplanation"
            :show-header="false"
            @update:question="question = $event"
            @update:editing-ruling="editingRuling = $event"
            @update:edited-verdict="editedVerdict = $event"
            @update:edited-explanation="editedExplanation = $event"
            @ask="askQuestion"
            @cancel-answer="cancelAnswer"
            @request-help="requestLearningHelp"
            @open-card-ocr="cardOcrOpen = true"
            @voice-transcript="useVoiceTranscript"
            @clear-thread="requestClearCurrentAnswerThread"
            @confirm-ruling="confirmAnswer"
            @reload-ruling="reloadRuling"
            @save-ruling-revision="saveRulingRevision"
          />
        </template>
      </div>

      <CardOcrCapture v-if="cardOcrOpen" @close="cardOcrOpen = false" @recognized="useCardText" />
      <ConversationResetDialog
        kind="private-browser"
        :open="resetDialogOpen"
        :turn-count="answerTurns.length"
        :restore-focus="resetRestoreTarget"
        @cancel="cancelClearCurrentAnswerThread"
        @confirm="confirmClearCurrentAnswerThread"
      />
    </div>
  </AppShell>
</template>
