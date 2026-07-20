<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'

interface TeachingPlan {
  id: string
  documentVersionId: string
  playerCount: number
  gameTitle: string
}

interface GameSession {
  id: string
  documentVersionId: string
  playerCount: number
  roundNumber: number
  phase: string
  activePlayer: number | null
}

interface Citation {
  chunkId: string
  heading: string
  excerpt: string
  pageFrom: number
  pageTo: number
}

interface RuleAnswer {
  status: 'ANSWERED' | 'CLARIFICATION_REQUIRED' | 'INSUFFICIENT_EVIDENCE' | 'MODEL_TIMEOUT' | 'INVALID_MODEL_OUTPUT' | 'VERSION_CONFLICT'
  shortVerdict: string
  explanation: string
  citations: Citation[]
  exceptions: string[]
  confidence: 'HIGH' | 'MEDIUM' | 'LOW'
  clarification: string | null
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

const route = useRoute()
const router = useRouter()
const planId = computed(() => String(route.params.planId ?? ''))
const plan = ref<TeachingPlan | null>(null)
const session = ref<GameSession | null>(null)
const turns = ref<ConversationTurn[]>([])
const question = ref('')
const loading = ref(true)
const asking = ref(false)
const updating = ref(false)
const feedbackByTurn = ref<Record<string, FeedbackRating>>({})
const feedbackSubmitting = ref(false)
const errorMessage = ref('')
const elapsedSeconds = ref(0)
let elapsedTimer: number | undefined

const latestTurn = computed(() => turns.value[turns.value.length - 1] ?? null)
const earlierTurns = computed(() => turns.value.slice(0, -1).reverse())
const stageMessage = computed(() => {
  if (elapsedSeconds.value < 3) return '正在理解问题和当前局面…'
  if (elapsedSeconds.value < 8) return '正在规则书里查找相关条目…'
  return '正在核对结论与出处；可以留在此页等待。'
})

function storageKey() {
  return `rulepilot:table-session:${planId.value}`
}

async function csrfToken() {
  const response = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (response.status === 401) {
    await router.push({ name: 'login' })
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
      expansionIds: [],
      playerCount: targetPlan.playerCount,
      phase: '开局准备',
      activePlayer: 1,
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
  if (!response.ok) throw new Error('无法恢复这局的问答记录。')
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
      await router.push({ name: 'login' })
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

async function updateTurn() {
  if (!session.value || updating.value) return
  updating.value = true
  errorMessage.value = ''
  try {
    const csrf = await csrfToken()
    const response = await fetch(`/api/v1/game-sessions/${session.value.id}/turn`, {
      method: 'PATCH',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({
        roundNumber: session.value.roundNumber,
        phase: session.value.phase,
        activePlayer: session.value.activePlayer,
      }),
    })
    if (!response.ok) throw new Error('当前局面保存失败。')
    session.value = (await response.json()) as GameSession
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '当前局面保存失败。'
  } finally {
    updating.value = false
  }
}

function pages(citation: Citation) {
  return citation.pageFrom === citation.pageTo ? `第 ${citation.pageFrom} 页` : `第 ${citation.pageFrom}–${citation.pageTo} 页`
}

onMounted(() => void loadTable())
onUnmounted(() => {
  if (elapsedTimer !== undefined) window.clearInterval(elapsedTimer)
})
</script>

<template>
  <AppShell immersive>
    <div class="min-h-screen bg-ink-panel pb-36 text-panel-text">
      <header class="sticky top-0 z-20 border-b border-white/10 bg-ink-panel/95 px-4 py-3 backdrop-blur">
        <div class="mx-auto flex max-w-3xl items-center justify-between gap-3">
          <RouterLink :to="{ name: 'lesson', params: { planId } }" class="min-h-11 py-3 text-sm font-semibold text-amber-200">← 返回讲解</RouterLink>
          <div v-if="plan" class="min-w-0 text-right">
            <p class="truncate font-display font-semibold">{{ plan.gameTitle }}</p>
            <p class="text-xs text-panel-text/55">桌边快速裁定</p>
          </div>
        </div>
      </header>

      <main class="mx-auto max-w-3xl px-4 py-5">
        <div v-if="loading" class="space-y-4" aria-live="polite">
          <p class="text-sm text-panel-text/65">正在恢复这局游戏…</p>
          <div class="h-28 animate-pulse rounded-2xl bg-white/8" />
        </div>

        <section v-else-if="!session" class="rounded-2xl bg-red-950/50 p-5">
          <p class="font-semibold">桌边模式暂时打不开</p>
          <p class="mt-2 text-sm text-panel-text/70">{{ errorMessage }}</p>
          <button class="mt-4 min-h-11 rounded-xl bg-amber-300 px-4 font-semibold text-ink" @click="loadTable">重试</button>
        </section>

        <template v-else>
          <details class="rounded-2xl border border-white/10 bg-white/5 p-4">
            <summary class="cursor-pointer list-none font-semibold">
              第 {{ session.roundNumber }} 轮 · {{ session.phase }}<span v-if="session.activePlayer"> · {{ session.activePlayer }} 号玩家</span>
              <span class="float-right text-xs text-panel-text/50">修改局面</span>
            </summary>
            <form class="mt-4 grid grid-cols-2 gap-3" @submit.prevent="updateTurn">
              <label class="text-xs text-panel-text/60">轮次<input v-model.number="session.roundNumber" min="1" type="number" class="mt-1 min-h-11 w-full rounded-xl border border-white/15 bg-black/20 px-3 text-base text-white"></label>
              <label class="text-xs text-panel-text/60">当前玩家<input v-model.number="session.activePlayer" :max="session.playerCount" min="1" type="number" class="mt-1 min-h-11 w-full rounded-xl border border-white/15 bg-black/20 px-3 text-base text-white"></label>
              <label class="col-span-2 text-xs text-panel-text/60">当前阶段<input v-model="session.phase" maxlength="80" class="mt-1 min-h-11 w-full rounded-xl border border-white/15 bg-black/20 px-3 text-base text-white" placeholder="例如：主要行动"></label>
              <button :disabled="updating" class="col-span-2 min-h-11 rounded-xl border border-amber-200/40 text-sm font-semibold text-amber-100 disabled:opacity-40">{{ updating ? '保存中…' : '保存局面' }}</button>
            </form>
          </details>

          <article v-if="latestTurn" class="mt-5 overflow-hidden rounded-3xl bg-paper text-ink shadow-2xl shadow-black/20" aria-live="polite">
            <div class="p-5 sm:p-7">
              <p class="text-xs font-semibold text-ink/45">你问：{{ latestTurn.question }}</p>
              <p class="mt-4 text-xs font-bold text-emerald-700">{{ latestTurn.answer.status === 'ANSWERED' ? '已核对，可以继续游戏' : '先暂停这一步' }}</p>
              <h1 class="mt-2 font-display text-2xl font-semibold leading-9">{{ latestTurn.answer.shortVerdict }}</h1>
              <p v-if="latestTurn.answer.status !== 'ANSWERED'" class="mt-4 rounded-xl bg-amber-50 p-3 text-sm text-amber-900">{{ latestTurn.answer.clarification ?? '当前证据还不足以给出可靠裁定。' }}</p>
              <details v-else class="mt-5 border-t border-ink/10 pt-4">
                <summary class="cursor-pointer font-semibold text-indigo">为什么？有没有例外？</summary>
                <p class="mt-3 leading-7 text-ink/70">{{ latestTurn.answer.explanation }}</p>
                <ul v-if="latestTurn.answer.exceptions.length" class="mt-3 list-disc space-y-1 pl-5 text-sm leading-6 text-ink/65">
                  <li v-for="item in latestTurn.answer.exceptions" :key="item">{{ item }}</li>
                </ul>
              </details>
            </div>
            <details v-if="latestTurn.answer.citations.length" class="border-t border-indigo/15 bg-indigo/5 p-5">
              <summary class="cursor-pointer font-semibold text-indigo">查看规则出处</summary>
              <div class="mt-3 space-y-3">
                <div v-for="citation in latestTurn.answer.citations" :key="citation.chunkId" class="rounded-xl bg-paper p-3 text-sm">
                  <p class="font-semibold">{{ citation.heading }} · {{ pages(citation) }}</p>
                  <p class="mt-1 leading-6 text-ink/60">{{ citation.excerpt }}</p>
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
            <p class="mt-2 text-sm leading-6 text-panel-text/60">比如“我现在还能再登陆一次吗？”系统会结合当前轮次和阶段重新核对规则书。</p>
          </div>

          <p v-if="errorMessage" class="mt-4 rounded-xl bg-red-950/60 p-3 text-sm text-red-100" role="alert">{{ errorMessage }}</p>

          <details v-if="earlierTurns.length" class="mt-5 rounded-2xl border border-white/10 p-4">
            <summary class="cursor-pointer text-sm font-semibold text-panel-text/70">这局之前的 {{ earlierTurns.length }} 条裁定</summary>
            <ol class="mt-3 space-y-3">
              <li v-for="turn in earlierTurns" :key="turn.id" class="border-l-2 border-amber-200/40 pl-3 text-sm">
                <p class="text-panel-text/55">{{ turn.question }}</p>
                <p class="mt-1 font-semibold">{{ turn.answer.shortVerdict }}</p>
              </li>
            </ol>
          </details>
        </template>
      </main>

      <div v-if="session" class="fixed inset-x-0 bottom-0 z-30 border-t border-white/10 bg-ink-panel/95 px-4 pb-[max(1rem,env(safe-area-inset-bottom))] pt-3 backdrop-blur">
        <form class="mx-auto flex max-w-3xl gap-2" @submit.prevent="ask">
          <label for="table-question" class="sr-only">输入桌边规则问题</label>
          <input id="table-question" v-model="question" maxlength="800" :disabled="asking" class="min-h-12 min-w-0 flex-1 rounded-xl border border-white/15 bg-white/10 px-4 text-base text-white placeholder:text-white/35 focus:border-amber-200 focus:outline-none" placeholder="现在发生了什么争议？">
          <button :disabled="asking || !question.trim()" class="min-h-12 rounded-xl bg-amber-300 px-5 font-bold text-ink disabled:opacity-40">{{ asking ? `${elapsedSeconds}s` : '查规则' }}</button>
        </form>
        <p v-if="asking" class="mx-auto mt-2 max-w-3xl text-xs text-panel-text/60" role="status">{{ stageMessage }}</p>
      </div>
    </div>
  </AppShell>
</template>
