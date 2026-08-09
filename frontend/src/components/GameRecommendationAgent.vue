<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

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
    eyebrow: '一起挑一款', title: '今晚想玩什么？',
    description: '可以像和朋友一样聊：说一个游戏、一个感觉，或者上一批哪里不对。我会沿着上下文继续，不用按表格报条件。',
    initial: '晚上好。想一起挑一款，还是先聊聊最近喜欢的桌游？游戏名、气氛、人数，想到什么就说什么。',
    inputLabel: '和推荐 Agent 聊聊', inputPlaceholder: '例如：想找和花砖物语机制接近、但互动再多一点的游戏', send: '发送', sending: '正在接着你的话想…',
    reset: '清空这次对话', error: '刚才没有接上。你写下的条件还在，可以直接重试。', retry: '重试', profile: '这次想找',
    players: '{value} 人', duration: '{value} 分钟内', durationAny: '时长不限', weight: '复杂度 ≤ {value}', weightAny: '复杂度不限',
    source: '从完整 BGG 目录中核对了 {count} 款候选。', more: '换一批',
    understanding: '目前记下的偏好', basedOn: '你提到：“{value}”', low: '可能', medium: '大概', high: '明确',
    toolTrail: '本轮 Agent 轨迹', toolUnderstand: '理解上下文并决定下一步', toolCatalog: '浏览 BGG 目录候选',
    toolReference: '在 BGG 核对参考游戏',
    toolNames: '完整目录按标题找候选', toolDetails: 'BGG 详情核对', toolDiscover: '公开资料发现候选', toolResearch: '体验资料查证',
    starters: ['想找和我喜欢的一款机制相近的', '先聊聊最近流行什么', '朋友聚会，想热闹但不要尴尬', '我不确定，先问我一个问题吧'],
    type: '类型：{value}', interaction: '互动：{value}',
  },
  en: {
    eyebrow: 'Choose together', title: 'What should we play tonight?',
    description: 'Talk as you would with a friend: name a game, describe a feeling, or say what missed the mark. I will continue from context; no form-filling required.',
    initial: 'Good evening. Want to choose a game together, or chat about what you have enjoyed lately? Start anywhere—a title, a mood, or the group.',
    inputLabel: 'Chat with the recommendation Agent', inputPlaceholder: 'For example: something with similar mechanisms to a tile-drafting game, but more interaction', send: 'Send', sending: 'Thinking from where we left off…',
    reset: 'Clear this conversation', error: 'That reply did not come through. Your preferences are still here.', retry: 'Retry', profile: 'Looking for',
    players: '{value} players', duration: 'Up to {value} min', durationAny: 'Any duration', weight: 'Complexity ≤ {value}', weightAny: 'Any complexity',
    source: 'Checked {count} candidates against the complete BGG catalog.', more: 'Try another batch',
    understanding: 'Preferences so far', basedOn: 'You said: “{value}”', low: 'Maybe', medium: 'Likely', high: 'Clear',
    toolTrail: 'Agent trajectory this turn', toolUnderstand: 'Understand context and choose the next step', toolCatalog: 'Browse BGG catalog candidates',
    toolReference: 'Resolve the reference game in BGG',
    toolNames: 'Find titles in the full catalog', toolDetails: 'Verify BGG details', toolDiscover: 'Discover from public sources', toolResearch: 'Verify play experience',
    starters: ['Find something mechanically similar to a game I like', 'Let’s chat about what is popular', 'Lively with friends, but not awkward', 'I am not sure—ask me one useful question'],
    type: 'Type: {value}', interaction: 'Interaction: {value}',
  },
} as const

const loadingCopy = {
  'zh-CN': {
    requesting: '收到，接着聊下去…', understanding_request: '正在结合前文理解你这句话…',
    selecting_tools: '推荐 Agent 正在决定下一步…',
    searching_bgg_catalog: '正在桌游目录里查找…', reading_game_details: '正在翻看这款游戏的详细资料…',
    discovering_candidates: '正在补充更贴近这个感觉的候选…', verifying_bgg_candidates: '正在核对人数、时长和玩法…',
    researching_game_fit: '正在看看实际游玩感受…', composing_response: '已经找到几款，马上整理好…',
  },
  en: {
    requesting: 'Got it. Continuing from here…', understanding_request: 'Understanding this turn in the context of the conversation…',
    selecting_tools: 'The recommendation Agent is choosing its next step…',
    searching_bgg_catalog: 'Searching the game catalog…', reading_game_details: 'Reading this game\'s details…',
    discovering_candidates: 'Looking for a closer fit…', verifying_bgg_candidates: 'Checking player count, time, and play style…',
    researching_game_fit: 'Checking how it feels to play…', composing_response: 'A few good options are ready…',
  },
} as const

type LoadingStage = 'requesting' | RecommendationProgressStage

type CopyKey = Exclude<keyof typeof copy['zh-CN'], 'starters'>
type PendingRequest = {
  message: string
  profile: RecommendationProfile
  excludedBggIds: number[]
  focusedBggId: number | null
  transcript: RecommendationMessage[]
  knownGames: { bggId: number; name: string; originalName: string }[]
  shownBggIds: number[]
}

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
const conversationScroller = ref<HTMLElement | null>(null)
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

const toolLabels = computed(() => {
  const actions = response.value?.harness?.actions ?? []
  const labels: string[] = []
  const add = (label: string) => { if (!labels.includes(label)) labels.push(label) }
  if (actions.some(action => action === 'REPLY_TO_USER' || action === 'ASK_USER' || action === 'UPDATE_PREFERENCES' || action === 'RECOMMEND_GAMES')) add(t('toolUnderstand'))
  if (actions.includes('RESOLVE_BGG_REFERENCE')) add(t('toolReference'))
  if (actions.includes('SEARCH_BGG_CATALOG')) add(t('toolCatalog'))
  if (actions.includes('SEARCH_BGG_BY_NAME')) add(t('toolNames'))
  if (actions.some(action => action === 'LOOKUP_BGG_CANDIDATES' || action === 'LOOKUP_BGG_GAME')) add(t('toolDetails'))
  if (actions.includes('DISCOVER_CANDIDATES')) add(t('toolDiscover'))
  if (actions.some(action => action === 'RESEARCH_GAME_FIT' || action === 'RESEARCH_GAME_QUESTION')) add(t('toolResearch'))
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

async function scrollConversationToLatest() {
  await nextTick()
  const scroller = conversationScroller.value
  if (scroller) scroller.scrollTop = scroller.scrollHeight
}

async function sendTurn(message: string, requestProfile: RecommendationProfile, userLabel?: string, excludedBggIds: number[] = [], focusedBggId: number | null = null) {
  if (userLabel) messages.value.push({ id: ++messageId, role: 'user', text: userLabel })
  const transcript = messages.value.slice(-24).map(item => ({ ...item }))
  const pending = {
    message,
    profile: { ...requestProfile },
    excludedBggIds: [...excludedBggIds],
    focusedBggId,
    transcript,
    knownGames: knownGames.value.map(game => ({ bggId: game.bggId, name: game.name, originalName: game.originalName })),
    shownBggIds: [...seenBggIds.value],
  }
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
    messages.value.push({ id: ++messageId, role: 'assistant', text: parsed.assistantMessage })
  } catch {
    failed.value = true
  } finally {
    endLoading()
  }
}

function choose(option: { value: string; label: string }) {
  if (!clarification.value || loading.value) return
  void sendTurn(option.value, profile.value, option.label)
}

function submitMessage() {
  const message = draft.value.trim().replace(/\s+/g, ' ')
  if (!message || loading.value) return
  draft.value = ''
  void sendTurn(message, profile.value, message, [], activeFocusedBggId.value)
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
watch(
  () => [messages.value.length, loading.value, loadingStage.value],
  () => { void scrollConversationToLatest() },
  { flush: 'post' },
)
onMounted(() => {
  void csrfToken().catch(() => undefined)
  void scrollConversationToLatest()
})
onBeforeUnmount(() => {
  activeRequest?.abort()
  endLoading()
})
</script>

<template>
  <section class="py-7 sm:py-9" aria-labelledby="recommendation-agent-title">
    <div class="tabletop-panel player-board tabletop-felt overflow-hidden p-1">
      <div class="grid gap-px overflow-hidden rounded-[1.15rem] bg-white/10 lg:grid-cols-[minmax(16rem,0.7fr)_minmax(28rem,1.3fr)]">
        <div class="bg-felt-deep px-5 py-6 sm:px-7 sm:py-8">
          <p class="text-xs font-bold uppercase tracking-[0.16em] text-[#e8bd6a]">{{ t('eyebrow') }}</p>
          <h2 id="recommendation-agent-title" class="mt-2 max-w-xl font-display text-4xl font-semibold leading-none tracking-tight text-white sm:text-5xl">{{ t('title') }}</h2>
          <p class="mt-4 max-w-xl text-sm leading-7 text-white/62">{{ t('description') }}</p>
          <div v-if="profileLabels.length" class="mt-6"><p class="text-xs font-bold uppercase tracking-[0.12em] text-white/40">{{ t('profile') }}</p><ul class="mt-2 flex flex-wrap gap-2"><li v-for="label in profileLabels" :key="label" class="rounded-md border border-white/15 bg-white/7 px-2.5 py-1.5 text-xs font-semibold text-white/78">{{ label }}</li></ul></div>
          <details v-if="response?.userModel?.summary" class="mt-5 rounded-xl border border-white/10 bg-black/10 p-4">
            <summary class="cursor-pointer text-xs font-bold uppercase tracking-[0.1em] text-[#e8bd6a]">{{ t('understanding') }}</summary>
            <p class="mt-3 text-sm leading-6 text-white/72">{{ response.userModel.summary }}</p>
            <ul v-if="response.userModel.hypotheses.length" class="mt-3 stack-y-sm"><li v-for="hypothesis in response.userModel.hypotheses" :key="`${hypothesis.text}-${hypothesis.basedOn}`" class="text-xs leading-5 text-white/58"><span class="mr-2 font-semibold text-[#e8bd6a]">{{ confidenceLabel(hypothesis.confidence) }}</span>{{ hypothesis.text }}<span class="block text-white/38">{{ t('basedOn', { value: hypothesis.basedOn }) }}</span></li></ul>
          </details>
          <button type="button" class="mt-5 min-h-11 text-sm font-semibold text-white/55 underline decoration-light-soft underline-offset-4 hover:text-white" @click="reset">{{ t('reset') }}</button>
        </div>

        <div class="min-w-0 bg-paper text-ink">
          <div ref="conversationScroller" data-testid="recommendation-conversation" class="max-h-[31rem] min-h-72 scroll-pb-8 stack-y-md overflow-y-auto px-4 py-5 pb-8 sm:px-6 sm:py-7 sm:pb-9" aria-live="polite">
            <div v-for="message in messages" :key="message.id" class="flex" :class="message.role === 'user' ? 'justify-end' : 'justify-start'"><p class="max-w-[88%] rounded-2xl px-4 py-3 text-sm leading-6" :class="message.role === 'user' ? 'rounded-br-sm bg-felt text-white' : 'rounded-bl-sm border border-ink/8 bg-canvas text-ink/72'">{{ message.text }}</p></div>
            <div v-if="loading" class="flex items-center gap-3 rounded-2xl rounded-bl-sm border border-ink/8 bg-canvas px-4 py-3 text-sm text-ink/55" role="status"><span class="flex gap-1" aria-hidden="true"><span class="size-1.5 animate-pulse rounded-full bg-copper" /><span class="size-1.5 animate-pulse rounded-full bg-copper [animation-delay:160ms]" /><span class="size-1.5 animate-pulse rounded-full bg-copper [animation-delay:320ms]" /></span><span>{{ loadingMessage }}</span></div>
          </div>
          <div v-if="clarification?.options.length && !loading" class="border-t border-ink/8 px-4 py-4 sm:px-6"><div class="flex flex-wrap gap-2"><button v-for="option in clarification.options" :key="option.value" type="button" class="min-h-11 rounded-lg border border-ink/15 bg-ink/5 px-4 text-sm font-semibold text-ink/72 hover:border-copper/50" @click="choose(option)">{{ option.label }}</button></div></div>
          <div v-if="failed" class="mx-4 mb-3 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-800 sm:mx-6" role="alert"><p>{{ t('error') }}</p><button type="button" class="mt-2 min-h-11 font-semibold underline" @click="retry">{{ t('retry') }}</button></div>
          <form class="flex gap-2 border-t border-ink/8 p-4 sm:p-5" @submit.prevent="submitMessage"><label for="recommendation-agent-message" class="sr-only">{{ t('inputLabel') }}</label><textarea id="recommendation-agent-message" v-model="draft" rows="2" maxlength="500" :placeholder="t('inputPlaceholder')" class="min-h-14 min-w-0 flex-1 resize-none rounded-xl border border-ink/15 bg-canvas px-4 py-3 text-sm leading-6 outline-none focus:border-felt" /><button type="submit" :disabled="loading || !draft.trim()" class="min-h-12 self-end rounded-xl bg-felt px-5 text-sm font-semibold text-white disabled:opacity-40">{{ t('send') }}</button></form>
        </div>
      </div>
    </div>

    <div v-if="response?.games.length" class="mt-8">
      <div class="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p class="max-w-3xl text-sm leading-6 text-ink/48">{{ t('source', { source: response.sourceCount.toLocaleString(), count: response.candidatesEvaluated }) }}</p>
          <div v-if="toolLabels.length" class="mt-2 flex flex-wrap items-center gap-2 text-xs text-ink/48" aria-label="recommendation tool trail">
            <span class="font-semibold text-ink/58">{{ t('toolTrail') }}</span>
            <span v-for="label in toolLabels" :key="label" class="rounded-full border border-ink/10 bg-paper px-2.5 py-1">{{ label }}</span>
          </div>
        </div>
        <button type="button" :disabled="loading" class="min-h-11 text-sm font-semibold text-copper underline decoration-copper-soft underline-offset-4 disabled:opacity-40" @click="moreGames">{{ t('more') }}</button>
      </div>
      <TransitionGroup tag="div" name="tile" class="mt-4 grid gap-4 md:grid-cols-3"><RecommendationGameCard v-for="entry in response.games" :key="entry.game.bggId" :entry="entry" :sources="response.researchSources ?? []" :loading="loading" @introduce="introduce" @select="selectGame" /></TransitionGroup>
      <RecommendationRulebookHandoff v-if="selectedGame" :key="selectedGame.bggId" :game="selectedGame" :profile="profile" @close="selectedGame = null" />
    </div>
  </section>
</template>
