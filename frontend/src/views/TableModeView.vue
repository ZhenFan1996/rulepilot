<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import { notifyLoginRequired } from '@/lib/authSession'
import { playerFacingTitle } from '@/lib/lessonPresentation'
import { playerFacingCitationExcerpt } from '@/lib/playerFacingCitation'

interface TeachingPlan {
  id: string
  documentVersionId: string
  gameTitle: string
}

interface GameSession {
  id: string
}

interface Citation {
  heading: string
  excerpt: string
  pageFrom: number
  pageTo: number
}

interface RuleAnswer {
  status: 'ANSWERED' | 'ANSWERED_WITH_WARNING' | 'CLARIFICATION_REQUIRED' | 'INSUFFICIENT_EVIDENCE' | 'MODEL_TIMEOUT' | 'INVALID_MODEL_OUTPUT' | 'VERSION_CONFLICT'
  shortVerdict: string
  explanation: string
  citations: Citation[]
  exceptions: string[]
  confidence: 'HIGH' | 'MEDIUM' | 'LOW'
  answerBasis?: 'DIRECT_RULE' | 'GROUNDED_APPLICATION' | null
  clarification: string | null
  warnings: Array<{ type: 'INDIRECT_CITATION' | 'LOW_CONFIDENCE' | 'REVIEW_UNRESOLVED' | 'REVIEW_UNAVAILABLE' }>
}

interface ConversationTurn {
  id: string
  question: string
  answer: RuleAnswer
  createdAt: string
  feedback: FeedbackRating | null
}

interface CsrfResponse {
  headerName: string
  token: string
}

type FeedbackRating = 'HELPFUL' | 'UNCLEAR' | 'INCORRECT'

const NON_SEMANTIC_TABLE_SESSION_PLAYER_COUNT = 1

const route = useRoute()
const planId = computed(() => String(route.params.planId ?? ''))
const plan = ref<TeachingPlan | null>(null)
const session = ref<GameSession | null>(null)
const turns = ref<ConversationTurn[]>([])
const question = ref('')
const loading = ref(true)
const asking = ref(false)
const feedbackByTurn = ref<Record<string, FeedbackRating>>({})
const feedbackSubmitting = ref(false)
const errorMessage = ref('')
const elapsedSeconds = ref(0)
let elapsedTimer: number | undefined

const latestTurn = computed(() => turns.value[turns.value.length - 1] ?? null)
const earlierTurns = computed(() => turns.value.slice(0, -1).reverse())
const displayPlanTitle = computed(() => plan.value ? playerFacingTitle(plan.value.gameTitle) : '')
const stageMessage = computed(() => {
  if (elapsedSeconds.value < 3) return '正在理解问题…'
  if (elapsedSeconds.value < 8) return '正在规则书里查找相关条目…'
  return '正在核对结论与出处；可以留在此页等待。'
})

function storageKey() {
  return `rulepilot:table-session:${planId.value}`
}

async function csrfToken() {
  const response = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (response.status === 401) {
    notifyLoginRequired()
    throw new Error('请先登录。')
  }
  if (!response.ok) throw new Error('无法建立安全会话。')
  return (await response.json()) as CsrfResponse
}

async function createSession(targetPlan: TeachingPlan) {
  const csrf = await csrfToken()
  const response = await fetch('/api/v1/game-sessions', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: JSON.stringify({
      documentVersionId: targetPlan.documentVersionId,
      // Table sessions keep their own storage-only count; it is not teaching-plan audience context and rule answers
      // never read it implicitly.
      expansionIds: [],
      playerCount: NON_SEMANTIC_TABLE_SESSION_PLAYER_COUNT,
      phase: '规则问答',
      activePlayer: null,
    }),
  })
  if (!response.ok) throw new Error('无法开始桌边模式。')
  session.value = (await response.json()) as GameSession
  localStorage.setItem(storageKey(), session.value.id)
}

async function loadConversation() {
  if (!plan.value || !session.value) return
  const response = await fetch(
    `/api/v1/document-versions/${plan.value.documentVersionId}/answers/conversation?gameSessionId=${session.value.id}`,
    { credentials: 'include' },
  )
  if (!response.ok) throw new Error('无法恢复本次问答记录。')
  turns.value = (await response.json()) as ConversationTurn[]
  feedbackByTurn.value = Object.fromEntries(
    turns.value
      .filter((turn) => turn.feedback !== null)
      .map((turn) => [turn.id, turn.feedback as FeedbackRating]),
  )
}

async function loadTable() {
  loading.value = true
  errorMessage.value = ''
  try {
    const planResponse = await fetch(`/api/v1/teaching-plans/${planId.value}`, { credentials: 'include' })
    if (planResponse.status === 401) {
      notifyLoginRequired()
      errorMessage.value = '请先登录后查看这份讲解。'
      return
    }
    if (!planResponse.ok) throw new Error('找不到这份讲解。')
    plan.value = (await planResponse.json()) as TeachingPlan
    const rememberedSessionId = localStorage.getItem(storageKey())
    if (rememberedSessionId) {
      const response = await fetch(`/api/v1/game-sessions/${rememberedSessionId}`, { credentials: 'include' })
      if (response.ok) session.value = (await response.json()) as GameSession
      else localStorage.removeItem(storageKey())
    }
    if (!session.value) await createSession(plan.value)
    await loadConversation()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '桌边模式加载失败。'
  } finally {
    loading.value = false
  }
}

async function ask() {
  const text = question.value.trim()
  if (!text || !plan.value || !session.value || asking.value) return
  asking.value = true
  elapsedSeconds.value = 0
  errorMessage.value = ''
  elapsedTimer = window.setInterval(() => (elapsedSeconds.value += 1), 1_000)
  try {
    const csrf = await csrfToken()
    const response = await fetch(`/api/v1/document-versions/${plan.value.documentVersionId}/answers`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({ question: text, gameSessionId: session.value.id }),
    })
    if (!response.ok) throw new Error('这次裁定没有完成，请重试。')
    const creation = (await response.json()) as { answer: RuleAnswer; conversationTurnId: string }
    turns.value.push({ id: creation.conversationTurnId, question: text, answer: creation.answer, createdAt: new Date().toISOString(), feedback: null })
    question.value = ''
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '提问失败。'
  } finally {
    asking.value = false
    if (elapsedTimer !== undefined) window.clearInterval(elapsedTimer)
  }
}

async function submitFeedback(turnId: string, rating: FeedbackRating) {
  if (!session.value || feedbackSubmitting.value) return
  feedbackSubmitting.value = true
  errorMessage.value = ''
  try {
    const csrf = await csrfToken()
    const response = await fetch(`/api/v1/game-sessions/${session.value.id}/conversation/${turnId}/feedback`, {
      method: 'PUT',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({ rating }),
    })
    if (!response.ok) throw new Error('反馈暂时没有保存，请稍后再试。')
    feedbackByTurn.value[turnId] = rating
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '反馈暂时没有保存。'
  } finally {
    feedbackSubmitting.value = false
  }
}

function pages(citation: Citation) {
  return citation.pageFrom === citation.pageTo ? `第 ${citation.pageFrom} 页` : `第 ${citation.pageFrom}–${citation.pageTo} 页`
}

function answerBasisLabel(answerBasis: RuleAnswer['answerBasis']) {
  return answerBasis === 'GROUNDED_APPLICATION' ? '按规则回答当前问题' : '规则原文直接裁定'
}

function answerBasisDescription(answerBasis: RuleAnswer['answerBasis']) {
  return answerBasis === 'GROUNDED_APPLICATION'
    ? '这条结论只把已引用规则用于你问题中明确写出的条件；不会读取或猜测桌面状态。'
    : '这条结论可由下方引用的规则原文直接核对。'
}

function publishesConclusion(status: RuleAnswer['status']) {
  return status === 'ANSWERED' || status === 'ANSWERED_WITH_WARNING'
}

function warningMessage(type: RuleAnswer['warnings'][number]['type']) {
  if (type === 'INDIRECT_CITATION') return '引用属于当前规则书，但可能不是这个条件下最直接的规则。'
  if (type === 'LOW_CONFIDENCE') return '模型对这条结论把握较低，请结合规则原文确认。'
  if (type === 'REVIEW_UNRESOLVED') return '事实审查仍有非关键疑点，请结合规则原文核对。'
  return '引用检查已通过，但自动事实审查暂时无法完成。'
}

onMounted(() => void loadTable())
onUnmounted(() => {
  if (elapsedTimer !== undefined) window.clearInterval(elapsedTimer)
})
</script>

<template>
  <AppShell immersive>
    <div class="min-h-screen bg-ink-panel pb-36 text-panel-text">
      <header class="app-sticky-top sticky z-20 border-b border-white/10 bg-ink-panel/95 px-4 py-3 backdrop-blur">
        <div class="mx-auto flex max-w-3xl items-center justify-between gap-3">
          <RouterLink :to="{ name: 'lesson', params: { planId } }" class="min-h-11 py-3 text-sm font-semibold text-amber-200">← 返回讲解</RouterLink>
          <div v-if="plan" class="min-w-0 text-right">
            <p class="truncate font-display font-semibold">{{ displayPlanTitle }}</p>
            <p class="text-xs text-panel-text/55">桌边快速裁定</p>
          </div>
        </div>
      </header>

      <div class="mx-auto max-w-3xl px-4 py-5">
        <div v-if="loading" class="stack-y-lg" aria-live="polite">
          <p class="text-sm text-panel-text/65">正在恢复问答记录…</p>
          <div class="h-28 animate-pulse rounded-2xl bg-white/8" />
        </div>

        <section v-else-if="!session" class="rounded-2xl bg-red-950/50 p-5">
          <p class="font-semibold">桌边模式暂时打不开</p>
          <p class="mt-2 text-sm text-panel-text/70">{{ errorMessage }}</p>
          <button class="mt-4 min-h-11 rounded-xl bg-amber-300 px-4 font-semibold text-ink" @click="loadTable">重试</button>
        </section>

        <template v-else>
          <div class="rounded-2xl border border-white/10 bg-white/5 p-4 text-sm leading-6 text-panel-text/70">
            回答只参考这款桌游的当前规则书版本。人数、轮次、阶段和当前玩家不会影响检索或结论；若问题依赖某个条件，请直接写在问题里。
          </div>

          <article v-if="latestTurn" class="mt-5 overflow-hidden rounded-3xl bg-paper text-ink elevation-2xl-black" aria-live="polite">
            <div class="p-5 sm:p-7">
              <p class="text-xs font-semibold text-ink/45">你问：{{ latestTurn.question }}</p>
              <p class="mt-4 text-xs font-bold" :class="latestTurn.answer.status === 'ANSWERED' ? 'text-emerald-700' : latestTurn.answer.status === 'ANSWERED_WITH_WARNING' ? 'text-amber-700' : 'text-red-700'">{{ latestTurn.answer.status === 'ANSWERED' ? '已核对，可以继续游戏' : latestTurn.answer.status === 'ANSWERED_WITH_WARNING' ? '有依据，但请先核对提醒' : '先暂停这一步' }}</p>
              <h1 class="mt-2 font-display text-2xl font-semibold leading-9">{{ latestTurn.answer.shortVerdict }}</h1>
              <p v-if="!publishesConclusion(latestTurn.answer.status)" class="mt-4 rounded-xl bg-amber-50 p-3 text-sm text-amber-900">{{ latestTurn.answer.clarification ?? '当前证据还不足以给出可靠裁定。' }}</p>
              <div v-if="latestTurn.answer.warnings.length" class="mt-4 rounded-xl border border-amber-300 bg-amber-50 p-3 text-sm text-amber-950" role="status"><p class="font-semibold">使用这条裁定前请留意</p><ul class="mt-1 list-disc stack-y-xs pl-5"><li v-for="warning in latestTurn.answer.warnings" :key="warning.type">{{ warningMessage(warning.type) }}</li></ul></div>
              <details v-if="publishesConclusion(latestTurn.answer.status)" class="mt-5 border-t border-ink/10 pt-4">
                <summary class="cursor-pointer font-semibold text-indigo">这条裁定如何得出？</summary>
                <p class="mt-3 text-sm font-semibold text-copper">{{ answerBasisLabel(latestTurn.answer.answerBasis) }}</p>
                <p class="mt-2 text-sm leading-6 text-ink/60">{{ answerBasisDescription(latestTurn.answer.answerBasis) }}</p>
                <p class="mt-3 leading-7 text-ink/70"><span class="font-semibold text-ink">套用到当前问题：</span>{{ latestTurn.answer.explanation }}</p>
                <ul v-if="latestTurn.answer.exceptions.length" class="mt-3 list-disc stack-y-xs pl-5 text-sm leading-6 text-ink/65">
                  <li v-for="item in latestTurn.answer.exceptions" :key="item">{{ item }}</li>
                </ul>
              </details>
            </div>
            <details v-if="latestTurn.answer.citations.length" class="border-t border-indigo/15 bg-indigo/5 p-5">
              <summary class="cursor-pointer font-semibold text-indigo">查看规则出处</summary>
              <div class="mt-3 stack-y-md">
                <div v-for="(citation, citationIndex) in latestTurn.answer.citations" :key="`${citation.heading}-${citation.pageFrom}-${citation.pageTo}-${citationIndex}`" class="rounded-xl bg-paper p-3 text-sm">
                  <p class="font-semibold">{{ citation.heading }} · {{ pages(citation) }}</p>
                  <p class="mt-1 leading-6 text-ink/60">{{ playerFacingCitationExcerpt(citation.excerpt) }}</p>
                </div>
              </div>
            </details>
            <div class="border-t border-ink/10 px-5 py-4 sm:px-7">
              <p class="text-xs font-semibold text-ink/45">这条裁定讲清楚了吗？</p>
              <div class="mt-2 flex flex-wrap gap-2">
                <button
                  v-for="option in ([['HELPFUL', '有帮助'], ['UNCLEAR', '没讲清'], ['INCORRECT', '规则有误']] as const)"
                  :key="option[0]"
                  type="button"
                  :disabled="feedbackSubmitting"
                  class="min-h-10 rounded-full border px-3 text-sm font-semibold disabled:opacity-40"
                  :class="feedbackByTurn[latestTurn.id] === option[0] ? 'border-indigo bg-indigo text-white' : 'border-ink/15 text-ink/65'"
                  @click="submitFeedback(latestTurn.id, option[0])"
                >
                  {{ option[1] }}
                </button>
              </div>
              <p v-if="feedbackByTurn[latestTurn.id]" class="mt-2 text-xs text-emerald-700" role="status">已记下，会用于复查这类问题。</p>
            </div>
          </article>

          <div v-else class="mt-5 rounded-3xl border border-white/10 p-6 text-center">
            <p class="font-display text-xl font-semibold">桌上遇到争议，就直接问</p>
            <p class="mt-2 text-sm leading-6 text-panel-text/60">直接描述规则疑问；如果有前提条件，也请一并写进问题。系统只依据这款桌游的规则书回答。</p>
          </div>

          <p v-if="errorMessage" class="mt-4 rounded-xl bg-red-950/60 p-3 text-sm text-red-100" role="alert">{{ errorMessage }}</p>

          <details v-if="earlierTurns.length" class="mt-5 rounded-2xl border border-white/10 p-4">
            <summary class="cursor-pointer text-sm font-semibold text-panel-text/70">本次问答之前的 {{ earlierTurns.length }} 条裁定</summary>
            <ol class="mt-3 stack-y-md">
              <li v-for="turn in earlierTurns" :key="turn.id" class="border-l-2 border-amber-200/40 pl-3 text-sm">
                <p class="text-panel-text/55">{{ turn.question }}</p>
                <p v-if="turn.answer.status === 'ANSWERED_WITH_WARNING'" class="mt-1 text-xs font-semibold text-amber-200">带核对提醒</p>
                <p class="mt-1 font-semibold">{{ turn.answer.shortVerdict }}</p>
              </li>
            </ol>
          </details>
        </template>
      </div>

      <div v-if="session" class="fixed inset-x-0 bottom-0 z-30 border-t border-white/10 bg-ink-panel/95 px-4 pb-[max(1rem,env(safe-area-inset-bottom))] pt-3 backdrop-blur">
        <form class="mx-auto flex max-w-3xl gap-2" @submit.prevent="ask">
          <label for="table-question" class="sr-only">输入桌边规则问题</label>
          <input id="table-question" v-model="question" maxlength="800" :disabled="asking" class="min-h-12 min-w-0 flex-1 rounded-xl border border-white/15 bg-white/10 px-4 text-base text-white placeholder:text-white/35 focus:border-amber-200 focus:outline-none" placeholder="要查哪条规则？">
          <button :disabled="asking || !question.trim()" class="min-h-12 rounded-xl bg-amber-300 px-5 font-bold text-ink disabled:opacity-40">{{ asking ? `${elapsedSeconds}s` : '查规则' }}</button>
        </form>
        <p v-if="asking" class="mx-auto mt-2 max-w-3xl text-xs text-panel-text/60" role="status">{{ stageMessage }}</p>
      </div>
    </div>
  </AppShell>
</template>
