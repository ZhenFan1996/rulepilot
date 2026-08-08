<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import RecommendationGameCard from '@/components/RecommendationGameCard.vue'
import RecommendationRulebookHandoff from '@/components/RecommendationRulebookHandoff.vue'
import type {
  RecommendationAgentResponse,
  RecommendationClarification,
  RecommendationGame,
  RecommendationMessage,
  RecommendationProgressStage,
  RecommendationProfile,
} from '@/components/gameRecommendationTypes'
import { streamGameRecommendation } from '@/lib/gameRecommendationStream'
import { useLocale } from '@/lib/locale'

const { locale } = useLocale()
const copy = {
  'zh-CN': {
    eyebrow: '桌游推荐 Agent', title: '把今晚的场景告诉我，我来一起挑',
    description: '我会边聊边形成可纠正的偏好理解，从 BGG 全量目录找候选；需要深入介绍时，再联网核对发行商资料与玩家体验。',
    initial: '说说这次是什么场合，或者直接告诉我人数、时间和想要的感觉。信息够用时我会直接推荐，不必答完固定问卷。',
    inputLabel: '告诉推荐 Agent 你的想法', inputPlaceholder: '例如：第一次带家人玩，不想讲半天规则，希望大家都有参与感', send: '发送', sending: 'Agent 正在整理候选…',
    reset: '重新开始', error: '这轮对话没有完成，已有偏好和目录浏览不受影响。', retry: '重试', profile: '明确条件',
    players: '{value} 人', duration: '{value} 分钟内', durationAny: '时长不限', weight: '复杂度 ≤ {value}', weightAny: '复杂度不限',
    source: '从 {source} 条 BGG 快照记录中补齐并比较了 {count} 款候选。', agent: 'Agent 动态规划', fallback: 'BGG 安全降级', more: '换一批',
    understanding: '我目前的理解（可以随时纠正）', basedOn: '来自：“{value}”', low: '低置信', medium: '中置信', high: '高置信',
    catalogStatus: '已查询 BGG', toolChoiceStatus: '模型已自主选择工具', nameSearchStatus: '已在全量 BGG CSV 快照按名称搜索', lookupStatus: '已按 BGG ID 读取详情', discoveryStatus: '已联网发现候选并经 BGG 验证', structuredStatus: '已按明确条件完成可验证排序', researchStatus: '已联网调查', questionResearchStatus: '已围绕当前问题定向查证', rerankStatus: '已完成个性化重排', responseStatus: '已结合当前问题组织回答',
    starters: ['第一次和家人玩', '两个人想要有互动', '朋友聚会想热闹一点', '先随便推荐几款'],
    type: '类型：{value}', interaction: '互动：{value}',
  },
  en: {
    eyebrow: 'Board-game recommendation Agent', title: 'Tell me about tonight, and we will choose together',
    description: 'I form a correctable picture of your tastes, retrieve from the full BGG catalog, and research publisher material and player experience when you ask for a closer look.',
    initial: 'Describe the occasion, or share player count, time, and the feeling you want. I will recommend as soon as there is enough context—no fixed questionnaire.',
    inputLabel: 'Tell the recommendation Agent what you need', inputPlaceholder: 'For example: a first family game, easy to teach, with everyone involved', send: 'Send', sending: 'The Agent is shaping a shortlist…',
    reset: 'Start over', error: 'This turn did not complete. Your preferences and catalog browsing are unaffected.', retry: 'Retry', profile: 'Explicit constraints',
    players: '{value} players', duration: 'Up to {value} min', durationAny: 'Any duration', weight: 'Complexity ≤ {value}', weightAny: 'Any complexity',
    source: 'Enriched and compared {count} candidates from {source} BGG snapshot records.', agent: 'Agent-planned', fallback: 'Safe BGG fallback', more: 'Try another batch',
    understanding: 'My current understanding (correct me anytime)', basedOn: 'From: “{value}”', low: 'Low confidence', medium: 'Medium confidence', high: 'High confidence',
    catalogStatus: 'BGG searched', toolChoiceStatus: 'Model selected tools autonomously', nameSearchStatus: 'Full BGG CSV snapshot searched by name', lookupStatus: 'BGG details loaded by ID', discoveryStatus: 'Web candidates discovered and verified by BGG', structuredStatus: 'Verifiable constraint ranking complete', researchStatus: 'Web research complete', questionResearchStatus: 'Focused question researched', rerankStatus: 'Personalized reranking complete', responseStatus: 'Response shaped around this question',
    starters: ['First game with family', 'Interactive game for two', 'A lively friend gathering', 'Just suggest a few'],
    type: 'Type: {value}', interaction: 'Interaction: {value}',
  },
} as const

const loadingCopy = {
  'zh-CN': {
    requesting: '已发送请求…', understanding_request: '正在理解这轮需求…',
    selecting_tools: '模型正在选择下一个检索工具…',
    searching_bgg_catalog: '模型已选择名称搜索，正在查询全量 BGG CSV…', reading_game_details: '正在读取这款游戏的 BGG 详情…',
    discovering_candidates: '正在联网发现更符合这个说法的候选…', verifying_bgg_candidates: '正在用 BGG ID 和详情验证候选…',
    researching_game_fit: '正在核对发行商资料与玩家体验…', composing_response: '候选已就绪，正在组织回答…',
  },
  en: {
    requesting: 'Request sent…', understanding_request: 'Understanding this turn…',
    selecting_tools: 'The model is choosing the next retrieval tool…',
    searching_bgg_catalog: 'The model chose title search; querying the full BGG CSV…', reading_game_details: 'Reading this game\'s BGG details…',
    discovering_candidates: 'Discovering candidates that match your wording…', verifying_bgg_candidates: 'Verifying candidates by BGG ID and details…',
    researching_game_fit: 'Checking publisher material and player experience…', composing_response: 'Candidates are ready; shaping the response…',
  },
} as const

type LoadingStage = 'requesting' | RecommendationProgressStage

type CopyKey = Exclude<keyof typeof copy['zh-CN'], 'starters'>
type PendingRequest = { message: string; profile: RecommendationProfile; excludedBggIds: number[]; focusedBggId: number | null; transcript: RecommendationMessage[] }

function t(key: CopyKey, parameters: Record<string, string | number> = {}) {
  return copy[locale.value][key].replace(/\{(\w+)\}/g, (placeholder, name: string) => parameters[name] === undefined ? placeholder : String(parameters[name]))
}

function emptyProfile(): RecommendationProfile {
  return { players: null, maxMinutes: null, maxWeight: null, type: 'all', interaction: 'any' }
}

function initialClarification(): RecommendationClarification {
  return { field: 'conversation', prompt: t('initial'), options: copy[locale.value].starters.map(label => ({ value: label, label })) }
}

const profile = ref<RecommendationProfile>(emptyProfile())
const clarification = ref<RecommendationClarification | null>(initialClarification())
const response = ref<RecommendationAgentResponse | null>(null)
const messages = ref<RecommendationMessage[]>([{ id: 1, role: 'assistant', text: t('initial') }])
const draft = ref('')
const loading = ref(false)
const loadingStage = ref<LoadingStage>('requesting')
const loadingElapsedSeconds = ref(0)
const failed = ref(false)
const lastRequest = ref<PendingRequest | null>(null)
const seenBggIds = ref<number[]>([])
const knownGames = ref<RecommendationGame[]>([])
const activeFocusedBggId = ref<number | null>(null)
const selectedGame = ref<RecommendationGame | null>(null)
let messageId = 1
let csrf: { headerName: string; token: string } | null = null
let loadingClock: ReturnType<typeof setInterval> | null = null
let activeRequest: AbortController | null = null

const loadingMessage = computed(() => {
  const message = loadingCopy[locale.value][loadingStage.value]
  return loadingElapsedSeconds.value > 0 ? `${message} ${loadingElapsedSeconds.value}s` : message
})

const profileLabels = computed(() => {
  const labels: string[] = []
  if (profile.value.players !== null) labels.push(t('players', { value: profile.value.players }))
  if (profile.value.maxMinutes !== null) labels.push(profile.value.maxMinutes === 0 ? t('durationAny') : t('duration', { value: profile.value.maxMinutes }))
  if (profile.value.maxWeight !== null) labels.push(profile.value.maxWeight === 0 ? t('weightAny') : t('weight', { value: profile.value.maxWeight }))
  if (profile.value.type !== 'all') labels.push(t('type', { value: profile.value.type }))
  if (profile.value.interaction !== 'any') labels.push(t('interaction', { value: profile.value.interaction }))
  return labels
})

const harnessLabels = computed(() => {
  const actions = response.value?.harness?.actions ?? []
  const labels: string[] = []
  if (actions.includes('SEARCH_BGG_CATALOG')) labels.push(t('catalogStatus'))
  if (actions.includes('MODEL_SELECT_TOOLS')) labels.push(t('toolChoiceStatus'))
  if (actions.includes('SEARCH_BGG_BY_NAME')) labels.push(t('nameSearchStatus'))
  if (actions.includes('LOOKUP_BGG_GAME') || actions.includes('LOOKUP_BGG_CANDIDATES')) labels.push(t('lookupStatus'))
  if (actions.includes('DISCOVER_CANDIDATES')) labels.push(t('discoveryStatus'))
  if (actions.includes('RANK_STRUCTURED_CANDIDATES')) labels.push(t('structuredStatus'))
  if (actions.includes('RESEARCH_GAME_FIT')) labels.push(t('researchStatus'))
  if (actions.includes('RESEARCH_GAME_QUESTION')) labels.push(t('questionResearchStatus'))
  if (actions.includes('COMPOSE_RECOMMENDATIONS')) labels.push(t('rerankStatus'))
  if (actions.includes('COMPOSE_GAME_RESPONSE')) labels.push(t('responseStatus'))
  return labels
})

async function csrfToken() {
  if (csrf) return csrf
  const result = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (!result.ok) throw new Error('csrf unavailable')
  csrf = await result.json() as { headerName: string; token: string }
  return csrf
}

function beginLoading() {
  if (loadingClock) clearInterval(loadingClock)
  loadingStage.value = 'requesting'
  loadingElapsedSeconds.value = 0
  const startedAt = Date.now()
  loadingClock = setInterval(() => { loadingElapsedSeconds.value = Math.floor((Date.now() - startedAt) / 1000) }, 1000)
  loading.value = true
}

function endLoading() {
  if (loadingClock) clearInterval(loadingClock)
  loadingClock = null
  activeRequest = null
  loading.value = false
}

async function sendTurn(message: string, requestProfile: RecommendationProfile, userLabel?: string, excludedBggIds: number[] = [], focusedBggId: number | null = null) {
  if (userLabel) messages.value.push({ id: ++messageId, role: 'user', text: userLabel })
  const transcript = messages.value.slice(-24).map(item => ({ ...item }))
  const pending = { message, profile: { ...requestProfile }, excludedBggIds: [...excludedBggIds], focusedBggId, transcript }
  lastRequest.value = pending
  beginLoading()
  failed.value = false
  try {
    const token = await csrfToken()
    activeRequest = new AbortController()
    const parsed = await streamGameRecommendation(`/api/v1/bgg/recommendation-agent/stream?locale=${encodeURIComponent(locale.value)}`, {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json', [token.headerName]: token.token },
      body: JSON.stringify({ ...pending, transcript: transcript.map(({ role, text }) => ({ role, text })) }),
      signal: activeRequest.signal,
    }, update => {
      loadingStage.value = update.stage
      loadingElapsedSeconds.value = Math.max(loadingElapsedSeconds.value, Math.floor(update.elapsedMs / 1000))
    })
    profile.value = parsed.profile
    clarification.value = parsed.clarification
    response.value = parsed
    seenBggIds.value = [...new Set([...seenBggIds.value, ...parsed.games.map(entry => entry.game.bggId)])].slice(-60)
    knownGames.value = [...parsed.games.map(entry => entry.game), ...knownGames.value]
      .filter((game, index, games) => games.findIndex(candidate => candidate.bggId === game.bggId) === index)
      .slice(0, 60)
    const actions = parsed.harness?.actions ?? []
    if (actions.includes('LOOKUP_BGG_GAME') && pending.focusedBggId !== null) {
      activeFocusedBggId.value = pending.focusedBggId
    } else if (actions.includes('SEARCH_BGG_CATALOG') || actions.includes('SEARCH_BGG_BY_NAME') || actions.includes('DISCOVER_CANDIDATES')) {
      activeFocusedBggId.value = parsed.games.length === 1 ? parsed.games[0]!.game.bggId : null
    }
    messages.value.push({ id: ++messageId, role: 'assistant', text: parsed.assistantMessage })
  } catch {
    failed.value = true
  } finally {
    endLoading()
  }
}

function choose(option: { value: string; label: string }) {
  if (!clarification.value || loading.value) return
  const updated = { ...profile.value }
  if (clarification.value.field === 'players') updated.players = Number(option.value)
  else if (clarification.value.field === 'duration') updated.maxMinutes = Number(option.value)
  else if (clarification.value.field === 'complexity') updated.maxWeight = Number(option.value)
  const message = clarification.value.field === 'conversation' ? option.value : ''
  void sendTurn(message, updated, option.label)
}

function submitMessage() {
  const message = draft.value.trim().replace(/\s+/g, ' ')
  if (!message || loading.value) return
  draft.value = ''
  const wantsAnotherBatch = /(?:再(?:来|推荐|换|找)|换|不满意|不喜欢|太重|太轻|more|another|different)/i.test(message)
  const referencedBggId = resolveKnownGameReference(message)
  if (referencedBggId !== null) activeFocusedBggId.value = referencedBggId
  if (wantsAnotherBatch) activeFocusedBggId.value = null
  const focusedBggId = wantsAnotherBatch ? null : referencedBggId ?? activeFocusedBggId.value
  void sendTurn(message, profile.value, message, wantsAnotherBatch ? seenBggIds.value : [], focusedBggId)
}

function resolveKnownGameReference(message: string): number | null {
  const normalizedMessage = normalizeReference(message)
  if (!normalizedMessage) return null
  const matches = knownGames.value.flatMap(game => [game.name, game.originalName]
    .map(normalizeReference)
    .filter(title => title.length >= 3 || /\p{Script=Han}/u.test(title))
    .filter(title => normalizedMessage.includes(title))
    .map(title => ({ bggId: game.bggId, length: title.length })))
  if (!matches.length) return null
  const longest = Math.max(...matches.map(match => match.length))
  const ids = [...new Set(matches.filter(match => match.length === longest).map(match => match.bggId))]
  return ids.length === 1 ? ids[0]! : null
}

function normalizeReference(value: string) {
  return value.normalize('NFKC').toLocaleLowerCase().replace(/[^\p{L}\p{N}]+/gu, '')
}

function moreGames() {
  if (!response.value?.games.length || loading.value) return
  void sendTurn(t('more'), profile.value, t('more'), seenBggIds.value)
}

function introduce(bggId: number, name: string) {
  if (loading.value) return
  activeFocusedBggId.value = bggId
  const message = locale.value === 'zh-CN' ? `介绍一下《${name}》` : `Tell me more about ${name}`
  void sendTurn(message, profile.value, message, [], bggId)
}

function selectGame(game: RecommendationGame) {
  selectedGame.value = game
  activeFocusedBggId.value = game.bggId
}

function retry() {
  const pending = lastRequest.value
  if (!pending) return
  failed.value = false
  void sendTurn(pending.message, pending.profile, undefined, pending.excludedBggIds, pending.focusedBggId)
}

function reset() {
  activeRequest?.abort()
  endLoading()
  profile.value = emptyProfile()
  clarification.value = initialClarification()
  response.value = null
  messages.value = [{ id: ++messageId, role: 'assistant', text: t('initial') }]
  failed.value = false
  lastRequest.value = null
  seenBggIds.value = []
  knownGames.value = []
  activeFocusedBggId.value = null
  selectedGame.value = null
}

function confidenceLabel(confidence: 'low' | 'medium' | 'high') {
  return t(confidence)
}

watch(locale, reset)
onMounted(() => { void csrfToken().catch(() => undefined) })
onBeforeUnmount(() => {
  activeRequest?.abort()
  endLoading()
})
</script>

<template>
  <section class="border-b border-ink/10 py-8" aria-labelledby="recommendation-agent-title">
    <div class="grid gap-6 lg:grid-cols-[minmax(0,0.82fr)_minmax(28rem,1.18fr)] lg:items-start">
      <div class="lg:sticky lg:top-6">
        <p class="text-sm font-semibold text-copper">{{ t('eyebrow') }}</p>
        <h2 id="recommendation-agent-title" class="mt-1 max-w-xl font-display text-3xl font-semibold leading-tight sm:text-4xl">{{ t('title') }}</h2>
        <p class="mt-3 max-w-xl text-sm leading-7 text-ink/55">{{ t('description') }}</p>
        <div v-if="profileLabels.length" class="mt-5"><p class="text-xs font-bold uppercase tracking-[0.12em] text-ink/45">{{ t('profile') }}</p><ul class="mt-2 flex flex-wrap gap-2"><li v-for="label in profileLabels" :key="label" class="rounded-full border border-copper/20 bg-copper/7 px-3 py-1.5 text-xs font-semibold text-copper">{{ label }}</li></ul></div>
        <section v-if="response?.userModel?.summary" class="mt-5 rounded-2xl border border-indigo/10 bg-indigo/5 p-4">
          <h3 class="text-xs font-bold uppercase tracking-[0.1em] text-indigo">{{ t('understanding') }}</h3>
          <p class="mt-2 text-sm leading-6 text-ink/65">{{ response.userModel.summary }}</p>
          <ul v-if="response.userModel.hypotheses.length" class="mt-3 space-y-2"><li v-for="hypothesis in response.userModel.hypotheses" :key="`${hypothesis.text}-${hypothesis.basedOn}`" class="text-xs leading-5 text-ink/55"><span class="mr-2 rounded-full bg-paper px-2 py-1 font-semibold text-indigo">{{ confidenceLabel(hypothesis.confidence) }}</span>{{ hypothesis.text }}<span class="block pl-2 text-ink/40">{{ t('basedOn', { value: hypothesis.basedOn }) }}</span></li></ul>
        </section>
        <button type="button" class="mt-5 min-h-11 text-sm font-semibold text-indigo underline decoration-indigo/30 underline-offset-4" @click="reset">{{ t('reset') }}</button>
      </div>

      <div class="min-w-0 overflow-hidden rounded-3xl border border-ink/10 bg-paper shadow-sm">
        <div class="max-h-[30rem] space-y-3 overflow-y-auto px-4 py-5 sm:px-6" aria-live="polite">
          <div v-for="message in messages" :key="message.id" class="flex" :class="message.role === 'user' ? 'justify-end' : 'justify-start'"><p class="max-w-[88%] rounded-2xl px-4 py-3 text-sm leading-6" :class="message.role === 'user' ? 'bg-indigo text-white' : 'bg-canvas text-ink/70'">{{ message.text }}</p></div>
          <div v-if="loading" class="flex items-center gap-3 rounded-2xl bg-canvas px-4 py-3 text-sm text-ink/55" role="status"><span class="flex gap-1" aria-hidden="true"><span class="size-1.5 animate-pulse rounded-full bg-copper" /><span class="size-1.5 animate-pulse rounded-full bg-copper [animation-delay:160ms]" /><span class="size-1.5 animate-pulse rounded-full bg-copper [animation-delay:320ms]" /></span><span>{{ loadingMessage }}</span></div>
        </div>
        <div v-if="clarification?.options.length && !loading" class="border-t border-ink/8 px-4 py-4 sm:px-6"><div class="flex flex-wrap gap-2"><button v-for="option in clarification.options" :key="option.value" type="button" class="min-h-11 rounded-xl border border-indigo/20 bg-indigo/5 px-4 text-sm font-semibold text-indigo hover:border-indigo/50" @click="choose(option)">{{ option.label }}</button></div></div>
        <div v-if="failed" class="mx-4 mb-3 rounded-xl border border-danger/20 bg-danger/5 p-4 text-sm text-ink/65 sm:mx-6" role="alert"><p>{{ t('error') }}</p><button type="button" class="mt-2 min-h-11 font-semibold text-danger underline" @click="retry">{{ t('retry') }}</button></div>
        <form class="flex gap-2 border-t border-ink/8 p-4 sm:p-5" @submit.prevent="submitMessage"><label for="recommendation-agent-message" class="sr-only">{{ t('inputLabel') }}</label><textarea id="recommendation-agent-message" v-model="draft" rows="2" maxlength="500" :placeholder="t('inputPlaceholder')" class="min-h-12 min-w-0 flex-1 resize-none rounded-xl border border-ink/15 bg-canvas px-3 py-2.5 text-sm leading-6 outline-none focus:border-copper" /><button type="submit" :disabled="loading || !draft.trim()" class="min-h-12 self-end rounded-xl bg-copper px-5 text-sm font-semibold text-white disabled:opacity-40">{{ t('send') }}</button></form>
      </div>
    </div>

    <div v-if="response?.games.length" class="mt-8">
      <div class="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
        <div><p class="max-w-3xl text-sm leading-6 text-ink/55">{{ t('source', { source: response.sourceCount.toLocaleString(), count: response.candidatesEvaluated }) }}</p><p v-if="harnessLabels.length" class="mt-1 text-xs font-semibold text-indigo">{{ harnessLabels.join(' · ') }}</p></div>
        <div class="flex items-center gap-4"><span class="text-xs font-semibold text-indigo">{{ response.harness?.fallbackUsed ? t('fallback') : t('agent') }}</span><button type="button" :disabled="loading" class="min-h-11 text-sm font-semibold text-copper underline decoration-copper/30 underline-offset-4 disabled:opacity-40" @click="moreGames">{{ t('more') }}</button></div>
      </div>
      <div class="mt-4 grid gap-4 md:grid-cols-3"><RecommendationGameCard v-for="entry in response.games" :key="entry.game.bggId" :entry="entry" :sources="response.researchSources ?? []" :loading="loading" @introduce="introduce" @select="selectGame" /></div>
      <RecommendationRulebookHandoff v-if="selectedGame" :key="selectedGame.bggId" :game="selectedGame" :profile="profile" @close="selectedGame = null" />
    </div>
  </section>
</template>
