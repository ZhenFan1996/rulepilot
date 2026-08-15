<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import ConversationResetDialog from '@/components/ConversationResetDialog.vue'
import RecommendationGameCard from '@/components/RecommendationGameCard.vue'
import RecommendationComparisonTable from '@/components/RecommendationComparisonTable.vue'
import RecommendationAnswerWorkspace from '@/components/RecommendationAnswerWorkspace.vue'
import RecommendationGameDetailsDialog from '@/components/RecommendationGameDetailsDialog.vue'
import RecommendationLessonDialog from '@/components/RecommendationLessonDialog.vue'
import RecommendationRulebookDialog from '@/components/RecommendationRulebookDialog.vue'
import RecommendationRulebookHandoff, { type RecommendationJourneyStatus } from '@/components/RecommendationRulebookHandoff.vue'
import type {
  RecommendationAgentResponse,
  RecommendationClarification,
  RecommendationGame,
  RecommendationMessage,
  RecommendationProgressStage,
  RecommendationProfile,
  RecommendationServerSession,
} from '@/components/gameRecommendationTypes'
import { useModalFocus } from '@/composables/useModalFocus'
import { notifyLoginRequired } from '@/lib/authSession'
import { RecommendationRequestError, RecommendationStreamError, streamGameRecommendation } from '@/lib/gameRecommendationStream'
import { useLocale } from '@/lib/locale'
import { playerTurnLocale } from '@/lib/playerTurnLanguage'
import { canonicalRecommendationProfile, emptyRecommendationProfile } from '@/lib/recommendationProfile'
import {
  forgetRecommendationConversation,
  readRecommendationConversation,
  rememberRecommendationConversation,
  type RecommendationConversationGame,
  type RecommendationConversationSnapshot,
} from '@/lib/recommendationConversationSession'

const props = defineProps<{ sessionIdentity?: string | null }>()
const { locale } = useLocale()
const copy = {
  'zh-CN': {
    eyebrow: '一起挑一款', title: '今晚想玩什么？',
    description: '可以像和朋友一样聊：说一个游戏、一个感觉，或者上一批哪里不对。我会沿着上下文继续，不用按表格报条件。',
    initial: '晚上好。想一起挑一款，还是先聊聊最近喜欢的桌游？游戏名、气氛、人数，想到什么就说什么。',
    inputLabel: '和推荐 Agent 聊聊', inputPlaceholder: '例如：想找和花砖物语机制接近、但互动再多一点的游戏', send: '发送', sending: '正在接着你的话想…',
    reset: '清空这次对话', error: '刚才没有接上。你写下的条件还在，可以直接重试。', retry: '重试', profile: '这次想找',
    players: '{value} 人', duration: '{value} 分钟内', durationAny: '时长不限', weight: '复杂度 ≤ {value}', weightAny: '复杂度不限',
    inputCount: '{current} / {maximum}', inputTooLong: '请删减 {count} 个字后再发送；当前内容不会被裁掉。',
    source: '从完整 BGG 目录中核对了 {count} 款候选。', more: '换一批',
    understanding: '目前记下的偏好', basedOn: '你提到：“{value}”', low: '可能', medium: '大概', high: '明确',
    toolTrail: '本轮 Agent 轨迹', toolUnderstand: '理解上下文并决定下一步', toolCatalog: '浏览 BGG 目录候选',
    toolReference: '在 BGG 核对参考游戏',
    toolNames: '完整目录按标题找候选', toolDetails: 'BGG 详情核对', toolDiscover: '公开资料发现候选', toolResearch: '体验资料查证', toolCompare: '按候选事实并排核对',
    starters: ['想找和我喜欢的一款机制相近的', '先聊聊最近流行什么', '朋友聚会，想热闹但不要尴尬', '我不确定，先问我一个问题吧'],
    type: '类型：{value}', interaction: '互动：{value}',
    journeyWorking: '正在为《{game}》获取规则书并生成讲解 · {progress}%', journeyReady: '《{game}》的讲解已经可以阅读',
    journeyFailed: '《{game}》的准备流程需要处理', journeyOpen: '打开进度', journeyRead: '打开讲解', journeyProgress: '查看进度', journeyDialog: '规则书与讲解进度',
    recommendationRole: '继续推荐', answerRole: '规则答疑', roleLabel: '切换 Agent 任务',
    loginRequired: '推荐需要登录；你写的条件已保留在这个浏览器会话中。登录后回来检查一下，再发送。',
    login: '登录并继续', register: '创建账号', checkingSession: '正在确认登录…',
    resetFailed: '服务器没有确认删除，当前对话仍然保留。请重试。',
    restoredGames: '上次已核对候选：{games}。可以直接继续比较。',
  },
  en: {
    eyebrow: 'Choose together', title: 'What should we play tonight?',
    description: 'Talk as you would with a friend: name a game, describe a feeling, or say what missed the mark. I will continue from context; no form-filling required.',
    initial: 'Good evening. Want to choose a game together, or chat about what you have enjoyed lately? Start anywhere—a title, a mood, or the group.',
    inputLabel: 'Chat with the recommendation Agent', inputPlaceholder: 'For example: something with similar mechanisms to a tile-drafting game, but more interaction', send: 'Send', sending: 'Thinking from where we left off…',
    reset: 'Clear this conversation', error: 'That reply did not come through. Your preferences are still here.', retry: 'Retry', profile: 'Looking for',
    players: '{value} players', duration: 'Up to {value} min', durationAny: 'Any duration', weight: 'Complexity ≤ {value}', weightAny: 'Any complexity',
    inputCount: '{current} / {maximum}', inputTooLong: 'Remove {count} character(s) before sending. Your text has not been truncated.',
    source: 'Checked {count} candidates against the complete BGG catalog.', more: 'Try another batch',
    understanding: 'Preferences so far', basedOn: 'You said: “{value}”', low: 'Maybe', medium: 'Likely', high: 'Clear',
    toolTrail: 'Agent trajectory this turn', toolUnderstand: 'Understand context and choose the next step', toolCatalog: 'Browse BGG catalog candidates',
    toolReference: 'Resolve the reference game in BGG',
    toolNames: 'Find titles in the full catalog', toolDetails: 'Verify BGG details', toolDiscover: 'Discover from public sources', toolResearch: 'Verify play experience', toolCompare: 'Compare candidate-scoped facts',
    starters: ['Find something mechanically similar to a game I like', 'Let’s chat about what is popular', 'Lively with friends, but not awkward', 'I am not sure—ask me one useful question'],
    type: 'Type: {value}', interaction: 'Interaction: {value}',
    journeyWorking: 'Getting the rulebook and building a guide for {game} · {progress}%', journeyReady: 'The guide for {game} is ready to read',
    journeyFailed: 'The preparation flow for {game} needs attention', journeyOpen: 'Open progress', journeyRead: 'Open guide', journeyProgress: 'View progress', journeyDialog: 'Rulebook and guide progress',
    recommendationRole: 'Recommendations', answerRole: 'Rules Q&A', roleLabel: 'Switch Agent task',
    loginRequired: 'Sign in to use recommendations. Your draft is saved in this browser session; review it and send after you return.',
    login: 'Sign in and continue', register: 'Create account', checkingSession: 'Checking sign-in…',
    resetFailed: 'The server did not confirm deletion, so this conversation is still intact. Try again.',
    restoredGames: 'Previously verified candidates: {games}. You can continue comparing them here.',
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
  clientTurnId: string
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
  return emptyRecommendationProfile()
}

function initialClarification(): RecommendationClarification {
  return { field: 'conversation', prompt: t('initial'), options: copy[locale.value].starters.map(label => ({ value: label, label })) }
}

const DRAFT_STORAGE_KEY = 'rulepilot:recommendation-draft:v1'
const DRAFT_STORAGE_VERSION = 2
const MAX_RECOMMENDATION_CHARACTERS = 500
const MAX_STORED_DRAFT_CHARACTERS = 20_000

function characterCount(value: string) {
  return Array.from(value).length
}

function protocolUuid(value: unknown): value is string {
  if (typeof value !== 'string' || value.length !== 36) return false
  for (let index = 0; index < value.length; index += 1) {
    const character = value[index]!
    if (index === 8 || index === 13 || index === 18 || index === 23) {
      if (character !== '-') return false
      continue
    }
    const code = character.codePointAt(0) ?? -1
    if (!(code >= 48 && code <= 57 || code >= 65 && code <= 70 || code >= 97 && code <= 102)) return false
  }
  return true
}

function restoredDraft() {
  try {
    const parsed = JSON.parse(sessionStorage.getItem(DRAFT_STORAGE_KEY) ?? 'null') as unknown
    if (!parsed || typeof parsed !== 'object') return ''
    const saved = parsed as { version?: unknown; draft?: unknown }
    return (saved.version === 1 || saved.version === DRAFT_STORAGE_VERSION)
      && typeof saved.draft === 'string'
      && characterCount(saved.draft) <= MAX_STORED_DRAFT_CHARACTERS
      ? saved.draft
      : ''
  } catch {
    return ''
  }
}

function rememberDraft(value: string) {
  try {
    if (value) {
      if (characterCount(value) > MAX_STORED_DRAFT_CHARACTERS) return
      sessionStorage.setItem(DRAFT_STORAGE_KEY, JSON.stringify({ version: DRAFT_STORAGE_VERSION, draft: value }))
    } else {
      sessionStorage.removeItem(DRAFT_STORAGE_KEY)
    }
  } catch {
    // The visible draft remains authoritative when browser-session storage is unavailable.
  }
}

const profile = ref<RecommendationProfile>(emptyProfile())
const clarification = ref<RecommendationClarification | null>(initialClarification())
const response = ref<RecommendationAgentResponse | null>(null)
const messages = ref<RecommendationMessage[]>([{ id: 1, role: 'assistant', text: t('initial') }])
const draft = ref(restoredDraft())
const loading = ref(false)
const loadingStage = ref<LoadingStage>('requesting')
const loadingElapsedSeconds = ref(0)
const failed = ref(false)
const loginGateVisible = ref(false)
const lastRequest = ref<PendingRequest | null>(null)
const seenBggIds = ref<number[]>([])
const knownGames = ref<RecommendationGame[]>([])
const rememberedKnownGames = ref<RecommendationConversationGame[]>([])
const activeFocusedBggId = ref<number | null>(null)
const selectedGame = ref<RecommendationGame | null>(null)
const detailsGame = ref<RecommendationGame | null>(null)
const openSurface = ref<'none' | 'game-details' | 'journey' | 'rulebook' | 'lesson'>('none')
const journeyStatus = ref<RecommendationJourneyStatus | null>(null)
const conversationRole = ref<'recommendation' | 'rule-qa'>('recommendation')
const conversationScroller = ref<HTMLElement | null>(null)
const recommendationInput = ref<HTMLTextAreaElement | null>(null)
const journeyDialog = ref<HTMLElement | null>(null)
const journeyDock = ref<HTMLButtonElement | null>(null)
const returnToAnswerWorkspace = ref(false)
const resetDialogOpen = ref(false)
const resetPending = ref(false)
const resetError = ref('')
const restoreRecommendationInputAfterReset = ref(false)
const conversationId = ref<string | null>(null)
const conversationRevision = ref(0)
const serverSessionReady = ref(props.sessionIdentity === undefined || !explicitSessionOwner(props.sessionIdentity))
let messageId = 1
let csrf: { headerName: string; token: string } | null = null
let loadingClock: ReturnType<typeof setInterval> | null = null
let activeRequest: AbortController | null = null
let restoredConversationOwner: string | null = null
let restoredSummaryMessageId: number | null = null
let restoringConversation = false
let serverRestoreGeneration = 0

const sessionKnown = computed(() => props.sessionIdentity !== null)
const signedIn = computed(() => props.sessionIdentity === undefined
  || (typeof props.sessionIdentity === 'string' && Boolean(props.sessionIdentity.trim())))

useModalFocus({
  dialog: journeyDialog,
  open: () => openSurface.value === 'journey',
  requestClose: () => { openSurface.value = 'none' },
  restoreFocus: journeyReturnTarget,
})

const loadingMessage = computed(() => {
  const message = loadingCopy[locale.value][loadingStage.value]
  return loadingElapsedSeconds.value > 0 ? `${message} ${loadingElapsedSeconds.value}s` : message
})

const profileLabels = computed(() => {
  const labels: string[] = []
  const playerCount = profile.value.playerCount
  const duration = profile.value.durationMinutes
  const complexity = profile.value.complexity
  if (playerCount) labels.push(integerRangeLabel(playerCount.minimum, playerCount.maximum, 'players'))
  if (duration) labels.push(integerRangeLabel(duration.minimum, duration.maximum, 'duration'))
  if (complexity) labels.push(complexityRangeLabel(complexity.minimum, complexity.maximum))
  if (profile.value.type !== 'all') labels.push(t('type', { value: profile.value.type }))
  if (profile.value.interaction !== 'any') labels.push(t('interaction', { value: profile.value.interaction }))
  return labels
})

const draftCharacterCount = computed(() => characterCount(draft.value))
const draftOverLimit = computed(() => Math.max(0, draftCharacterCount.value - MAX_RECOMMENDATION_CHARACTERS))

function integerRangeLabel(minimum: number | null, maximum: number | null, kind: 'players' | 'duration') {
  if (minimum !== null && maximum !== null && minimum === maximum) {
    return kind === 'players' ? t('players', { value: minimum }) : `${minimum} ${locale.value === 'zh-CN' ? '分钟' : 'min'}`
  }
  if (minimum !== null && maximum !== null) {
    return `${minimum}–${maximum} ${locale.value === 'zh-CN' ? (kind === 'players' ? '人' : '分钟') : (kind === 'players' ? 'players' : 'min')}`
  }
  if (minimum !== null) {
    return locale.value === 'zh-CN'
      ? `至少 ${minimum} ${kind === 'players' ? '人' : '分钟'}`
      : `At least ${minimum} ${kind === 'players' ? 'players' : 'min'}`
  }
  return kind === 'players'
    ? locale.value === 'zh-CN' ? `最多 ${maximum} 人` : `Up to ${maximum} players`
    : t('duration', { value: maximum ?? 0 })
}

function complexityRangeLabel(minimum: number | null, maximum: number | null) {
  if (minimum !== null && maximum !== null) {
    return minimum === maximum
      ? `${locale.value === 'zh-CN' ? '复杂度' : 'Complexity'} ${minimum}`
      : `${locale.value === 'zh-CN' ? '复杂度' : 'Complexity'} ${minimum}–${maximum}`
  }
  if (minimum !== null) return `${locale.value === 'zh-CN' ? '复杂度 ≥' : 'Complexity ≥'} ${minimum}`
  return t('weight', { value: maximum ?? 0 })
}

const compactJourneyText = computed(() => {
  if (!selectedGame.value) return ''
  const parameters = { game: selectedGame.value.name, progress: journeyStatus.value?.projection.progress ?? 5 }
  if (journeyStatus.value?.projection.state === 'failed' || journeyStatus.value?.projection.retryAction) return t('journeyFailed', parameters)
  if (journeyStatus.value?.projection.canReadLesson) return t('journeyReady', parameters)
  return t('journeyWorking', parameters)
})

const answerWorkspaceReady = computed(() => Boolean(
  journeyStatus.value?.projection.canAskQuestions
    && journeyStatus.value.plan?.id
    && journeyStatus.value.importJob?.documentVersionId,
))
const canResetRecommendation = computed(() => Boolean(
  messages.value.length > 1
    || response.value
    || profileLabels.value.length
    || selectedGame.value
    || detailsGame.value
    || failed.value
    || lastRequest.value
    || knownGames.value.length,
))

function toolLabelsFor(turnResponse?: RecommendationAgentResponse) {
  const actions = turnResponse?.harness?.actions ?? []
  const labels: string[] = []
  const add = (label: string) => { if (!labels.includes(label)) labels.push(label) }
  if (actions.some(action => action === 'REPLY_TO_USER' || action === 'ASK_USER' || action === 'UPDATE_PREFERENCES' || action === 'RECOMMEND_GAMES')) add(t('toolUnderstand'))
  if (actions.includes('RESOLVE_BGG_REFERENCE')) add(t('toolReference'))
  if (actions.includes('SEARCH_BGG_CATALOG')) add(t('toolCatalog'))
  if (actions.includes('SEARCH_BGG_BY_NAME')) add(t('toolNames'))
  if (actions.some(action => action === 'LOOKUP_BGG_CANDIDATES' || action === 'LOOKUP_BGG_GAME')) add(t('toolDetails'))
  if (actions.includes('DISCOVER_CANDIDATES')) add(t('toolDiscover'))
  if (actions.some(action => action === 'RESEARCH_GAME_FIT' || action === 'RESEARCH_GAME_QUESTION')) add(t('toolResearch'))
  if (actions.includes('COMPARE_CANDIDATES')) add(t('toolCompare'))
  return labels
}

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
  if (!scroller) return
  const turns = scroller.querySelectorAll<HTMLElement>('[data-conversation-message]')
  const latest = turns.item(turns.length - 1)
  if (latest?.dataset.hasRecommendations === 'true') {
    const scrollerTop = scroller.getBoundingClientRect().top
    const latestTop = latest.getBoundingClientRect().top
    scroller.scrollTop = Math.max(0, scroller.scrollTop + latestTop - scrollerTop - 8)
    return
  }
  scroller.scrollTop = scroller.scrollHeight
}

async function sendTurn(
  message: string,
  requestProfile: RecommendationProfile,
  userLabel?: string,
  excludedBggIds: number[] = [],
  focusedBggId: number | null = null,
  retryClientTurnId?: string,
) {
  if (!serverSessionReady.value) return
  let optimisticUserMessageId: number | null = null
  if (userLabel) {
    optimisticUserMessageId = ++messageId
    messages.value.push({ id: optimisticUserMessageId, role: 'user', text: userLabel })
  }
  const transcript = playerConversationTranscript()
  const clientTurnId = retryClientTurnId ?? crypto.randomUUID()
  const pending = {
    clientTurnId,
    message,
    profile: canonicalRecommendationProfile(requestProfile),
    excludedBggIds: [...excludedBggIds],
    focusedBggId,
    transcript,
    knownGames: minimalKnownGames(),
    shownBggIds: [...seenBggIds.value],
  }
  lastRequest.value = pending
  beginLoading()
  failed.value = false
  try {
    const token = await csrfToken()
    activeRequest = new AbortController()
    const responseLocale = playerTurnLocale(message, locale.value)
    const serverResponse = await streamGameRecommendation(`/api/v1/bgg/recommendation-agent/stream?locale=${encodeURIComponent(responseLocale)}`, {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json', [token.headerName]: token.token },
      body: JSON.stringify({
        ...pending,
        conversationId: conversationId.value,
        revision: conversationRevision.value,
        transcript: transcript.map(({ role, text }) => ({ role, text })),
      }),
      signal: activeRequest.signal,
    }, update => {
      loadingStage.value = update.stage
      loadingElapsedSeconds.value = Math.max(loadingElapsedSeconds.value, Math.floor(update.elapsedMs / 1000))
    })
    if (serverResponse.clientTurnId && serverResponse.clientTurnId !== clientTurnId) {
      throw new Error('recommendation response belongs to another client turn')
    }
    if (serverResponse.conversationId && !protocolUuid(serverResponse.conversationId)) {
      throw new Error('recommendation response contains an invalid conversation identity')
    }
    if (conversationId.value && serverResponse.conversationId && serverResponse.conversationId !== conversationId.value) {
      throw new Error('recommendation response belongs to another conversation')
    }
    if (serverResponse.conversationId) conversationId.value = serverResponse.conversationId
    if (serverResponse.revision !== null && serverResponse.revision !== undefined) {
      if (!Number.isSafeInteger(serverResponse.revision) || serverResponse.revision < 0) {
        throw new Error('recommendation response contains an invalid revision')
      }
      conversationRevision.value = serverResponse.revision
    }
    const parsed: RecommendationAgentResponse = {
      ...serverResponse,
      profile: canonicalRecommendationProfile(serverResponse.profile),
      responseLocale: serverResponse.responseLocale ?? responseLocale,
    }
    profile.value = parsed.profile
    clarification.value = parsed.clarification
    response.value = parsed
    const responseGames = [
      ...parsed.games.map(entry => entry.game),
      ...(parsed.comparison?.candidates.map(candidate => candidate.game) ?? []),
    ]
    seenBggIds.value = [...new Set([...seenBggIds.value, ...responseGames.map(game => game.bggId)])].slice(-60)
    knownGames.value = [...responseGames, ...knownGames.value]
      .filter((game, index, games) => games.findIndex(candidate => candidate.bggId === game.bggId) === index)
      .slice(0, 60)
    rememberedKnownGames.value = minimalKnownGames()
    messages.value.push({
      id: ++messageId,
      role: 'assistant',
      text: parsed.assistantMessage,
      response: parsed,
    })
    lastRequest.value = null
  } catch (error) {
    if (error instanceof RecommendationRequestError && error.status === 401) {
      if (optimisticUserMessageId !== null) {
        messages.value = messages.value.filter(item => item.id !== optimisticUserMessageId)
      }
      csrf = null
      draft.value = userLabel ?? message
      loginGateVisible.value = true
      notifyLoginRequired({ showReminder: false })
    } else {
      failed.value = true
      if (error instanceof RecommendationStreamError
        && error.code !== 'turn_id_reused'
        && restoredConversationOwner) {
        serverSessionReady.value = false
        serverRestoreGeneration += 1
        await restoreServerRecommendationConversation(restoredConversationOwner, serverRestoreGeneration)
      }
    }
  } finally {
    endLoading()
  }
}

function choose(option: { value: string; label: string }) {
  if (!clarification.value || loading.value) return
  void sendTurn(option.value, profile.value, option.label)
}

function submitMessage() {
  const message = draft.value.trim()
  if (!message || draftOverLimit.value > 0 || loading.value || !sessionKnown.value || !serverSessionReady.value) return
  if (!signedIn.value) {
    loginGateVisible.value = true
    notifyLoginRequired({ showReminder: false })
    return
  }
  loginGateVisible.value = false
  draft.value = ''
  void sendTurn(message, profile.value, message, [], activeFocusedBggId.value)
}

function moreGames(turnResponse?: RecommendationAgentResponse) {
  if (!turnResponse?.games.length || loading.value || turnResponse !== response.value) return
  void sendTurn(t('more'), profile.value, t('more'), seenBggIds.value)
}

function introduce(bggId: number, name: string) {
  if (loading.value) return
  activeFocusedBggId.value = bggId
  const message = locale.value === 'zh-CN' ? `介绍一下《${name}》` : `Tell me more about ${name}`
  void sendTurn(message, profile.value, message, [], bggId)
}

function selectGame(game: RecommendationGame) {
  if (selectedGame.value?.bggId !== game.bggId) {
    journeyStatus.value = null
    conversationRole.value = 'recommendation'
  }
  selectedGame.value = game
  activeFocusedBggId.value = game.bggId
  openSurface.value = 'journey'
}

function openDetails(game: RecommendationGame) {
  detailsGame.value = game
  openSurface.value = 'game-details'
}

function selectFromDetails(game: RecommendationGame) {
  detailsGame.value = null
  selectGame(game)
}

function updateJourneyStatus(value: RecommendationJourneyStatus) {
  journeyStatus.value = value
}

function openRulebook(value: RecommendationJourneyStatus) {
  journeyStatus.value = value
  if (value.importJob?.documentVersionId) openSurface.value = 'rulebook'
}

function openLesson(value: RecommendationJourneyStatus) {
  journeyStatus.value = value
  if (value.plan?.id) openSurface.value = 'lesson'
}

function openJourneyDock() {
  const status = journeyStatus.value
  if (status?.projection.canReadLesson && status.plan?.id) {
    openLesson(status)
    return
  }
  openSurface.value = 'journey'
}

function journeyReturnTarget() {
  if (!returnToAnswerWorkspace.value) return null
  returnToAnswerWorkspace.value = false
  return document.querySelector<HTMLElement>('[data-testid="recommendation-answer-workspace"]')
}

function journeySurfaceReturnTarget() {
  return journeyReturnTarget() ?? journeyDock.value
}

function switchToQuestions(value?: RecommendationJourneyStatus) {
  returnToAnswerWorkspace.value = false
  if (value) journeyStatus.value = value
  if (!answerWorkspaceReady.value) {
    if (selectedGame.value) openSurface.value = 'journey'
    return
  }
  returnToAnswerWorkspace.value = true
  conversationRole.value = 'rule-qa'
  openSurface.value = 'none'
}

function switchToRecommendations() {
  conversationRole.value = 'recommendation'
}

function changeJourneyGame() {
  selectedGame.value = null
  journeyStatus.value = null
  conversationRole.value = 'recommendation'
  openSurface.value = 'none'
}

function retry() {
  const pending = lastRequest.value
  if (!pending) return
  failed.value = false
  void sendTurn(
    pending.message,
    pending.profile,
    undefined,
    pending.excludedBggIds,
    pending.focusedBggId,
    pending.clientTurnId,
  )
}

function playerConversationTranscript() {
  return messages.value
    .filter(message => message.id !== restoredSummaryMessageId)
    .slice(-24)
    .map(({ id, role, text }) => ({ id, role, text }))
}

function minimalKnownGames() {
  return [
    ...knownGames.value.map(game => ({ bggId: game.bggId, name: game.name, originalName: game.originalName })),
    ...rememberedKnownGames.value,
  ]
    .filter((game, index, games) => games.findIndex(candidate => candidate.bggId === game.bggId) === index)
    .slice(0, 60)
}

function explicitSessionOwner(value: string | null | undefined) {
  if (typeof value !== 'string') return null
  const owner = value.normalize('NFKC').trim().toLowerCase().slice(0, 320)
  return owner || null
}

function conversationSnapshot(): RecommendationConversationSnapshot {
  const pending = (failed.value || loading.value) && lastRequest.value
    ? {
        clientTurnId: lastRequest.value.clientTurnId,
        message: lastRequest.value.message,
        excludedBggIds: [...lastRequest.value.excludedBggIds],
        focusedBggId: lastRequest.value.focusedBggId,
      }
    : null
  return {
    conversationId: conversationId.value,
    revision: conversationRevision.value,
    profile: canonicalRecommendationProfile(profile.value),
    transcript: playerConversationTranscript().map(({ role, text }) => ({ role, text })),
    knownGames: minimalKnownGames(),
    shownBggIds: [...seenBggIds.value],
    failed: Boolean(failed.value && pending),
    pending,
  }
}

function persistRecommendationConversation(owner = restoredConversationOwner) {
  if (!owner || restoringConversation) return
  rememberRecommendationConversation(sessionStorage, owner, conversationSnapshot())
}

function clearVisibleRecommendationConversation() {
  conversationId.value = null
  conversationRevision.value = 0
  profile.value = emptyProfile()
  clarification.value = initialClarification()
  response.value = null
  messages.value = [{ id: ++messageId, role: 'assistant', text: t('initial') }]
  failed.value = false
  lastRequest.value = null
  seenBggIds.value = []
  knownGames.value = []
  rememberedKnownGames.value = []
  activeFocusedBggId.value = null
  selectedGame.value = null
  detailsGame.value = null
  journeyStatus.value = null
  conversationRole.value = 'recommendation'
  openSurface.value = 'none'
  restoredSummaryMessageId = null
}

function restoreRecommendationConversation(owner: string) {
  const snapshot = readRecommendationConversation(sessionStorage, owner)
  clearVisibleRecommendationConversation()
  if (!snapshot) return

  profile.value = canonicalRecommendationProfile(snapshot.profile)
  conversationId.value = snapshot.conversationId
  conversationRevision.value = snapshot.revision
  clarification.value = null
  messages.value = snapshot.transcript.map(turn => ({ id: ++messageId, ...turn }))
  if (!messages.value.length) {
    messages.value = [{ id: ++messageId, role: 'assistant', text: t('initial') }]
  }
  rememberedKnownGames.value = snapshot.knownGames.map(game => ({ ...game }))
  seenBggIds.value = [...snapshot.shownBggIds]
  failed.value = Boolean(snapshot.failed && snapshot.pending)
  if (snapshot.pending) {
    activeFocusedBggId.value = snapshot.pending.focusedBggId
    lastRequest.value = {
      clientTurnId: snapshot.pending.clientTurnId ?? crypto.randomUUID(),
      message: snapshot.pending.message,
      profile: canonicalRecommendationProfile(snapshot.profile),
      excludedBggIds: [...snapshot.pending.excludedBggIds],
      focusedBggId: snapshot.pending.focusedBggId,
      transcript: playerConversationTranscript(),
      knownGames: minimalKnownGames(),
      shownBggIds: [...snapshot.shownBggIds],
    }
  }

  const names = [...new Set(snapshot.knownGames.map(game => game.name || game.originalName))].slice(0, 5)
  if (names.length) {
    restoredSummaryMessageId = ++messageId
    messages.value.push({
      id: restoredSummaryMessageId,
      role: 'assistant',
      text: t('restoredGames', { games: names.join(locale.value === 'zh-CN' ? '、' : ', ') }),
    })
  }
}

function isRecommendationServerSession(value: unknown): value is RecommendationServerSession {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const session = value as Partial<RecommendationServerSession>
  return protocolUuid(session.conversationId)
    && Number.isSafeInteger(session.revision)
    && Number(session.revision) >= 0
    && Boolean(session.profile && typeof session.profile === 'object')
    && Array.isArray(session.transcript)
    && session.transcript.every(turn => turn
      && (turn.role === 'user' || turn.role === 'assistant')
      && typeof turn.text === 'string')
    && Array.isArray(session.knownGames)
    && Array.isArray(session.shownBggIds)
    && typeof session.processing === 'boolean'
    && (session.latestResponse === null
      || Boolean(session.latestResponse && typeof session.latestResponse === 'object'))
}

function applyServerRecommendationConversation(session: RecommendationServerSession) {
  const pending = lastRequest.value
  clearVisibleRecommendationConversation()
  conversationId.value = session.conversationId
  conversationRevision.value = session.revision
  profile.value = canonicalRecommendationProfile(session.profile)
  messages.value = session.transcript.map(turn => ({ id: ++messageId, ...turn }))
  if (!messages.value.length) {
    messages.value = [{ id: ++messageId, role: 'assistant', text: t('initial') }]
  }
  rememberedKnownGames.value = session.knownGames.map(game => ({ ...game }))
  seenBggIds.value = [...session.shownBggIds]

  if (session.latestResponse) {
    const latest = {
      ...session.latestResponse,
      profile: canonicalRecommendationProfile(session.latestResponse.profile),
    }
    response.value = latest
    clarification.value = latest.clarification
    const responseGames = [
      ...latest.games.map(entry => entry.game),
      ...(latest.comparison?.candidates.map(candidate => candidate.game) ?? []),
    ]
    knownGames.value = responseGames
    rememberedKnownGames.value = minimalKnownGames()
    const lastAssistant = [...messages.value].reverse().find(message => message.role === 'assistant')
    if (lastAssistant) lastAssistant.response = latest
  } else {
    clarification.value = null
  }

  if ((session.processing || !session.latestResponse) && pending) {
    lastRequest.value = pending
    activeFocusedBggId.value = pending.focusedBggId
    failed.value = true
  }
}

async function restoreServerRecommendationConversation(
  owner: string,
  generation: number,
  attempt = 0,
) {
  try {
    const result = await fetch('/api/v1/bgg/recommendation-agent/session', { credentials: 'include' })
    if (generation !== serverRestoreGeneration) return
    if (!result || typeof result.status !== 'number') return
    if (result.status === 204) return
    if (result.status === 401) {
      loginGateVisible.value = true
      notifyLoginRequired({ showReminder: false })
      return
    }
    if (!result.ok) return
    const candidate = await result.json() as unknown
    if (generation !== serverRestoreGeneration || !isRecommendationServerSession(candidate)) return

    restoringConversation = true
    applyServerRecommendationConversation(candidate)
    restoringConversation = false
    if (candidate.processing && attempt < 50) {
      if (!loading.value) beginLoading()
      await new Promise(resolve => setTimeout(resolve, 1_000))
      if (generation === serverRestoreGeneration) {
        await restoreServerRecommendationConversation(owner, generation, attempt + 1)
      }
      return
    }
  } catch {
    // The bounded browser snapshot remains available when session restoration is temporarily offline.
  } finally {
    if (generation === serverRestoreGeneration) {
      restoringConversation = false
      if (attempt === 0 || attempt >= 50) {
        if (loading.value) endLoading()
        serverSessionReady.value = true
        persistRecommendationConversation(owner)
      }
    }
  }
}

function reset(preserveJourney = false) {
  activeRequest?.abort()
  endLoading()
  conversationId.value = null
  conversationRevision.value = 0
  profile.value = emptyProfile()
  clarification.value = initialClarification()
  response.value = null
  messages.value = [{ id: ++messageId, role: 'assistant', text: t('initial') }]
  failed.value = false
  lastRequest.value = null
  seenBggIds.value = []
  knownGames.value = []
  rememberedKnownGames.value = []
  activeFocusedBggId.value = null
  restoredSummaryMessageId = null
  if (restoredConversationOwner) {
    forgetRecommendationConversation(sessionStorage, restoredConversationOwner)
  }
  if (!preserveJourney) {
    selectedGame.value = null
    detailsGame.value = null
    journeyStatus.value = null
    conversationRole.value = 'recommendation'
    openSurface.value = 'none'
  }
}

function requestReset() {
  if (loading.value || resetPending.value || !canResetRecommendation.value) return
  resetError.value = ''
  restoreRecommendationInputAfterReset.value = false
  resetDialogOpen.value = true
}

function cancelReset() {
  if (resetPending.value) return
  restoreRecommendationInputAfterReset.value = false
  resetDialogOpen.value = false
}

function recommendationResetRestoreTarget() {
  if (!restoreRecommendationInputAfterReset.value) return null
  restoreRecommendationInputAfterReset.value = false
  recommendationInput.value?.focus({ preventScroll: true })
  return recommendationInput.value
}

async function confirmReset() {
  if (loading.value || resetPending.value) return
  resetPending.value = true
  resetError.value = ''
  try {
    if (conversationId.value) {
      const token = await csrfToken()
      const result = await fetch(`/api/v1/bgg/recommendation-agent/sessions/${encodeURIComponent(conversationId.value)}`, {
        method: 'DELETE',
        credentials: 'include',
        headers: { [token.headerName]: token.token },
      })
      if (!result.ok && result.status !== 404) throw new RecommendationRequestError(result.status)
    }
    reset()
    restoreRecommendationInputAfterReset.value = true
    resetDialogOpen.value = false
  } catch (error) {
    if (error instanceof RecommendationRequestError && error.status === 401) {
      csrf = null
      loginGateVisible.value = true
      notifyLoginRequired({ showReminder: false })
    }
    resetError.value = t('resetFailed')
  } finally {
    resetPending.value = false
  }
}

function confidenceLabel(confidence: 'low' | 'medium' | 'high') {
  return t(confidence)
}

watch(locale, () => {
  if (messages.value.length !== 1 || response.value || lastRequest.value || profileLabels.value.length) return
  messages.value = [{ id: ++messageId, role: 'assistant', text: t('initial') }]
  clarification.value = initialClarification()
})
watch(draft, rememberDraft)
watch(
  () => props.sessionIdentity,
  value => {
    if (value === undefined) return
    const owner = explicitSessionOwner(value)
    if (owner === restoredConversationOwner) {
      if (owner) loginGateVisible.value = false
      return
    }

    if (restoredConversationOwner) {
      if (loading.value && lastRequest.value) failed.value = true
      persistRecommendationConversation(restoredConversationOwner)
    }
    activeRequest?.abort()
    endLoading()
    serverRestoreGeneration += 1
    const restoreGeneration = serverRestoreGeneration
    restoringConversation = true
    restoredConversationOwner = owner
    clearVisibleRecommendationConversation()
    if (owner) {
      loginGateVisible.value = false
      restoreRecommendationConversation(owner)
      serverSessionReady.value = false
    } else {
      serverSessionReady.value = true
    }
    restoringConversation = false
    if (owner) void restoreServerRecommendationConversation(owner, restoreGeneration)
  },
  { immediate: true },
)
watch(
  [profile, messages, rememberedKnownGames, seenBggIds, failed, lastRequest, conversationId, conversationRevision, loading],
  () => { persistRecommendationConversation() },
  { deep: true, flush: 'post' },
)
watch(
  () => [messages.value.length, loading.value, loadingStage.value],
  () => { void scrollConversationToLatest() },
  { flush: 'post' },
)
onMounted(() => { void scrollConversationToLatest() })
onBeforeUnmount(() => {
  if (loading.value && lastRequest.value) failed.value = true
  persistRecommendationConversation()
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
          <p class="recommendation-intro-copy mt-4 max-w-xl text-sm leading-7">{{ t('description') }}</p>
          <div v-if="profileLabels.length" class="mt-6"><p class="recommendation-profile-label text-xs font-bold uppercase tracking-[0.12em]">{{ t('profile') }}</p><ul class="mt-2 flex flex-wrap gap-2"><li v-for="label in profileLabels" :key="label" class="recommendation-profile-chip rounded-md border border-white/15 bg-white/7 px-2.5 py-1.5 text-xs font-semibold">{{ label }}</li></ul></div>
          <details v-if="response?.userModel?.summary" class="mt-5 rounded-xl border border-white/10 bg-black/10 p-4">
            <summary class="cursor-pointer text-xs font-bold uppercase tracking-[0.1em] text-[#e8bd6a]">{{ t('understanding') }}</summary>
            <p class="recommendation-understanding-copy mt-3 text-sm leading-6">{{ response.userModel.summary }}</p>
            <ul v-if="response.userModel.hypotheses.length" class="mt-3 stack-y-sm"><li v-for="hypothesis in response.userModel.hypotheses" :key="`${hypothesis.text}-${hypothesis.basedOn}`" class="recommendation-hypothesis text-xs leading-5"><span class="mr-2 font-semibold text-[#e8bd6a]">{{ confidenceLabel(hypothesis.confidence) }}</span>{{ hypothesis.text }}<span class="recommendation-basis block">{{ t('basedOn', { value: hypothesis.basedOn }) }}</span></li></ul>
          </details>
          <button v-if="canResetRecommendation" type="button" :disabled="loading" class="recommendation-reset mt-5 min-h-11 text-sm font-semibold underline decoration-light-soft underline-offset-4 disabled:cursor-not-allowed disabled:opacity-40" @click="requestReset">{{ t('reset') }}</button>
        </div>

        <div class="min-w-0 bg-paper text-ink">
          <nav v-if="answerWorkspaceReady" data-testid="agent-role-switcher" class="flex gap-2 border-b border-ink/8 px-4 py-3 sm:px-6" :aria-label="t('roleLabel')">
            <button type="button" class="min-h-11 rounded-xl px-4 text-sm font-semibold" :class="conversationRole === 'recommendation' ? 'bg-felt text-white' : 'border border-ink/12 text-ink/60'" :aria-pressed="conversationRole === 'recommendation'" @click="switchToRecommendations">{{ t('recommendationRole') }}</button>
            <button type="button" class="min-h-11 rounded-xl px-4 text-sm font-semibold" :class="conversationRole === 'rule-qa' ? 'bg-indigo text-white' : 'border border-ink/12 text-ink/60'" :aria-pressed="conversationRole === 'rule-qa'" @click="switchToQuestions()">{{ t('answerRole') }}</button>
          </nav>

          <div v-show="conversationRole === 'recommendation'">
            <div ref="conversationScroller" data-testid="recommendation-conversation" class="max-h-[70vh] min-h-72 scroll-pb-8 stack-y-md overflow-y-auto px-4 py-5 pb-8 sm:min-h-[31rem] sm:px-6 sm:py-7 sm:pb-9 lg:max-h-[46rem]" aria-live="polite">
              <div v-for="message in messages" :key="message.id" data-conversation-message :data-has-recommendations="message.response?.games.length ? 'true' : 'false'" class="flex min-w-0" :class="message.role === 'user' ? 'justify-end' : 'justify-start'">
                <p v-if="message.role === 'user'" class="max-w-[88%] rounded-2xl rounded-br-sm bg-felt px-4 py-3 text-sm leading-6 text-white">{{ message.text }}</p>
                <article v-else class="min-w-0 w-full" :data-testid="message.response?.games.length ? 'assistant-recommendation-turn' : 'assistant-conversation-turn'">
                  <p class="max-w-[88%] rounded-2xl rounded-bl-sm border border-ink/8 bg-canvas px-4 py-3 text-sm leading-6 text-ink/72">{{ message.text }}</p>

                  <div v-if="toolLabelsFor(message.response).length" class="mt-2 flex flex-wrap items-center gap-2 pl-1 text-[0.6875rem] text-ink/45" aria-label="recommendation tool trail">
                    <span class="recommendation-tool-label font-semibold">{{ t('toolTrail') }}</span>
                    <span v-for="label in toolLabelsFor(message.response)" :key="label" class="rounded-full border border-ink/10 bg-paper px-2.5 py-1">{{ label }}</span>
                  </div>

                  <RecommendationComparisonTable v-if="message.response?.comparison" :comparison="message.response.comparison" :response-locale="message.response.responseLocale" />

                  <div v-if="message.response?.games.length" class="mt-3 rounded-2xl border border-ink/8 bg-canvas/45 p-3 sm:p-4">
                    <div class="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                      <p class="recommendation-source-summary text-xs leading-5">{{ t('source', { source: message.response.sourceCount.toLocaleString(), count: message.response.candidatesEvaluated }) }}</p>
                      <button v-if="message.response === response" type="button" :disabled="loading" class="min-h-11 self-start text-sm font-semibold text-copper underline decoration-copper-soft underline-offset-4 disabled:opacity-40 sm:self-auto" @click="moreGames(message.response)">{{ t('more') }}</button>
                    </div>
                    <TransitionGroup tag="div" name="tile" class="mt-3 grid gap-3 xl:grid-cols-2 2xl:grid-cols-3">
                      <RecommendationGameCard v-for="entry in message.response.games" :key="entry.game.bggId" :entry="entry" :sources="message.response.researchSources ?? []" :loading="loading" :response-locale="message.response.responseLocale" @introduce="introduce" @select="selectGame" @details="openDetails" />
                    </TransitionGroup>
                  </div>
                </article>
              </div>
              <div v-if="loading" class="flex items-center gap-3 rounded-2xl rounded-bl-sm border border-ink/8 bg-canvas px-4 py-3 text-sm text-ink/55" role="status"><span class="flex gap-1" aria-hidden="true"><span class="size-1.5 animate-pulse rounded-full bg-copper" /><span class="size-1.5 animate-pulse rounded-full bg-copper [animation-delay:160ms]" /><span class="size-1.5 animate-pulse rounded-full bg-copper [animation-delay:320ms]" /></span><span>{{ loadingMessage }}</span></div>
            </div>
            <div v-if="clarification?.options.length && !loading" class="border-t border-ink/8 px-4 py-4 sm:px-6"><div class="flex flex-wrap gap-2"><button v-for="option in clarification.options" :key="option.value" type="button" class="min-h-11 rounded-lg border border-ink/15 bg-ink/5 px-4 text-sm font-semibold text-ink/72 hover:border-copper/50" @click="choose(option)">{{ option.label }}</button></div></div>
            <div v-if="failed" class="mx-4 mb-3 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-800 sm:mx-6" role="alert"><p>{{ t('error') }}</p><button type="button" class="mt-2 min-h-11 font-semibold underline" @click="retry">{{ t('retry') }}</button></div>
            <div v-if="loginGateVisible" class="mx-4 mb-3 rounded-xl border border-copper/25 bg-copper/5 p-4 text-sm leading-6 text-ink/72 sm:mx-6" role="status">
              <p>{{ t('loginRequired') }}</p>
              <div class="mt-3 flex flex-wrap gap-4">
                <RouterLink :to="{ name: 'login', query: { redirect: '/discover' } }" class="inline-flex min-h-11 items-center font-semibold text-indigo underline underline-offset-4">{{ t('login') }}</RouterLink>
                <RouterLink :to="{ name: 'register', query: { redirect: '/discover' } }" class="inline-flex min-h-11 items-center font-semibold text-indigo underline underline-offset-4">{{ t('register') }}</RouterLink>
              </div>
            </div>
            <form class="flex items-end gap-2 border-t border-ink/8 p-4 sm:p-5" @submit.prevent="submitMessage">
              <div class="min-w-0 flex-1">
                <label for="recommendation-agent-message" class="sr-only">{{ t('inputLabel') }}</label>
                <textarea id="recommendation-agent-message" ref="recommendationInput" v-model="draft" rows="2" :placeholder="t('inputPlaceholder')" :aria-invalid="draftOverLimit > 0" aria-describedby="recommendation-agent-input-status" class="min-h-14 w-full resize-none rounded-xl border bg-canvas px-4 py-3 text-sm leading-6 outline-none" :class="draftOverLimit > 0 ? 'border-red-400 focus:border-red-500' : 'border-ink/15 focus:border-felt'" />
                <div id="recommendation-agent-input-status" class="mt-1 flex items-start justify-between gap-3 text-xs" :class="draftOverLimit > 0 ? 'text-red-700' : 'text-ink/45'">
                  <span>{{ draftOverLimit > 0 ? t('inputTooLong', { count: draftOverLimit }) : '' }}</span>
                  <span class="shrink-0 tabular-nums">{{ t('inputCount', { current: draftCharacterCount, maximum: MAX_RECOMMENDATION_CHARACTERS }) }}</span>
                </div>
              </div>
              <button type="submit" :disabled="loading || !draft.trim() || draftOverLimit > 0 || !sessionKnown || !serverSessionReady" class="min-h-12 rounded-xl bg-felt px-5 text-sm font-semibold text-white disabled:opacity-40">{{ sessionKnown && serverSessionReady ? t('send') : t('checkingSession') }}</button>
            </form>
          </div>

          <RecommendationAnswerWorkspace
            v-if="journeyStatus?.plan?.id && journeyStatus.importJob?.documentVersionId"
            v-show="conversationRole === 'rule-qa'"
            :active="conversationRole === 'rule-qa'"
            :document-version-id="journeyStatus.importJob.documentVersionId"
            :plan-id="journeyStatus.plan.id"
            :edition-id="journeyStatus.imported?.edition.id"
            :game-title="selectedGame?.name ?? journeyStatus.game.name"
          />
        </div>
      </div>
    </div>

    <div v-if="selectedGame && openSurface !== 'journey'" data-testid="player-journey-continuation" class="mt-4 flex flex-col overflow-hidden rounded-2xl border border-copper/25 bg-paper elevation-sm hover:border-copper/45 sm:flex-row">
      <button ref="journeyDock" data-testid="player-journey-dock" type="button" class="flex min-h-16 min-w-0 flex-1 items-center gap-3 px-4 py-3 text-left" @click="openJourneyDock">
        <span class="grid size-9 shrink-0 place-items-center rounded-full bg-copper/10 font-mono text-xs font-bold text-copper">{{ journeyStatus?.projection.progress ?? 5 }}%</span>
        <span class="min-w-0 flex-1 text-sm font-semibold text-ink">{{ compactJourneyText }}</span>
        <span class="shrink-0 text-sm font-semibold text-indigo underline">{{ t(journeyStatus?.projection.canReadLesson && journeyStatus.plan?.id ? 'journeyRead' : 'journeyOpen') }}</span>
      </button>
      <button v-if="journeyStatus?.projection.canReadLesson && journeyStatus.plan?.id" data-testid="player-journey-progress-button" type="button" class="min-h-11 w-full shrink-0 border-t border-copper/20 px-4 text-sm font-semibold text-ink/55 underline sm:w-auto sm:border-l sm:border-t-0" @click="openSurface = 'journey'">{{ t('journeyProgress') }}</button>
    </div>

    <Teleport to="body">
      <div v-if="selectedGame" v-show="openSurface === 'journey'" data-testid="player-journey-backdrop" class="fixed inset-0 z-[100] overflow-y-auto bg-ink/45 px-3 py-6 backdrop-blur-[2px] sm:px-6" @click.self="openSurface = 'none'">
        <div ref="journeyDialog" tabindex="-1" class="mx-auto w-full max-w-3xl outline-none" role="dialog" aria-modal="true" :aria-label="t('journeyDialog')">
          <RecommendationRulebookHandoff
            :key="selectedGame.bggId"
            :game="selectedGame"
            :profile="profile"
            @close="openSurface = 'none'"
            @change="changeJourneyGame"
            @status="updateJourneyStatus"
            @open-rulebook="openRulebook"
            @open-lesson="openLesson"
            @ask-questions="switchToQuestions"
          />
        </div>
      </div>
    </Teleport>

    <RecommendationGameDetailsDialog v-if="detailsGame" :game="detailsGame" :open="openSurface === 'game-details'" @close="openSurface = 'none'" @select="selectFromDetails" />
    <RecommendationRulebookDialog
      v-if="selectedGame && journeyStatus?.importJob?.documentVersionId"
      :open="openSurface === 'rulebook'"
      :version-id="journeyStatus.importJob.documentVersionId"
      :title="selectedGame.name"
      :restore-focus="journeySurfaceReturnTarget"
      @close="openSurface = 'none'"
    />
    <RecommendationLessonDialog
      v-if="journeyStatus?.plan?.id"
      :open="openSurface === 'lesson'"
      :plan-id="journeyStatus.plan.id"
      :initial-plan="journeyStatus.plan"
      :initial-lesson="journeyStatus.lesson"
      :restore-focus="journeySurfaceReturnTarget"
      @close="openSurface = 'none'"
      @ask-questions="switchToQuestions()"
    />
    <ConversationResetDialog
      kind="recommendation"
      :open="resetDialogOpen"
      :pending="resetPending"
      :error="resetError"
      :restore-focus="recommendationResetRestoreTarget"
      @cancel="cancelReset"
      @confirm="confirmReset"
    />
  </section>
</template>

<style scoped>
.recommendation-intro-copy {
  color: color-mix(in srgb, white 62%, transparent);
}

.recommendation-profile-label {
  color: color-mix(in srgb, white 40%, transparent);
}

.recommendation-profile-chip {
  color: color-mix(in srgb, white 78%, transparent);
}

.recommendation-understanding-copy {
  color: color-mix(in srgb, white 72%, transparent);
}

.recommendation-hypothesis {
  color: color-mix(in srgb, white 58%, transparent);
}

.recommendation-basis {
  color: color-mix(in srgb, white 38%, transparent);
}

.recommendation-reset {
  color: color-mix(in srgb, white 55%, transparent);
}

.recommendation-reset:hover {
  color: white;
}

.recommendation-tool-label {
  color: color-mix(in srgb, var(--color-ink) 58%, transparent);
}

.recommendation-source-summary {
  color: color-mix(in srgb, var(--color-ink) 48%, transparent);
}
</style>
