<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import ConversationResetDialog from '@/components/ConversationResetDialog.vue'
import RecommendationGameCard from '@/components/RecommendationGameCard.vue'
import RecommendationAnswerWorkspace from '@/components/RecommendationAnswerWorkspace.vue'
import RecommendationGameDetailsDialog from '@/components/RecommendationGameDetailsDialog.vue'
import RecommendationLessonDialog from '@/components/RecommendationLessonDialog.vue'
import RecommendationRulebookDialog from '@/components/RecommendationRulebookDialog.vue'
import RecommendationRulebookHandoff, { type RecommendationJourneyStatus } from '@/components/RecommendationRulebookHandoff.vue'
import SafeMarkdown from '@/components/SafeMarkdown.vue'
import type {
  RecommendationAgentResponse,
  RecommendationClarification,
  RecommendationFailureBoundary,
  RecommendationFailureReason,
  RecommendationGame,
  RecommendationMessage,
  RecommendationProgressAction,
  RecommendationProgressUpdate,
  RecommendationProgressStage,
  RecommendationProfile,
  RecommendationServerSession,
} from '@/components/gameRecommendationTypes'
import { useModalFocus } from '@/composables/useModalFocus'
import { notifyLoginRequired } from '@/lib/authSession'
import { RecommendationRequestError, RecommendationStreamError, streamGameRecommendation } from '@/lib/gameRecommendationStream'
import { useLocale, type AppLocale } from '@/lib/locale'
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
    inputLabel: '聊聊你想玩的游戏', inputPlaceholder: '例如：想找和花砖物语机制接近、但互动再多一点的游戏', send: '发送', sending: '正在接着你的话想…', workingReply: '正在回复', workingSearch: '正在查找桌游', workingRecommendation: '正在整理推荐', replyingDetail: '正在生成回复…',
    reset: '清空这次对话', newChat: '建立新聊天', chatHistory: '聊天记录', chatUntitled: '新的桌游聊天', error: '刚才没有接上。你写下的条件还在，可以直接重试。', failureReply: '这轮没有形成可安全提交的推荐，所以我没有猜测或伪造候选。你的问题和已有条件都还在，可以直接重试。', unavailableError: '这次推荐没有完成，也没有写入对话结果。当前页面仍保留你刚才的请求，已核对条件也保留在会话中，可以直接重试。', modelConfigurationError: '这次推荐没有完成，也没有写入对话结果。你刚才的请求仍保留；请先配置并保存推荐模型。当前配置不变时，同一请求不会成功，也不会自动重试。', failureTimeBudget: '失败原因：查找、核对或生成没有在本轮时间上限内完成。', failureModelResponse: '失败原因：模型这次没有返回完整、可执行的结构，因此未发布临时文字。', failureServiceConfiguration: '失败原因：推荐模型或所需能力当前没有可用配置。', failureActionBudget: '失败原因：模型在收到逐步执行或参数校验反馈后，仍重复不兼容或无效动作。', failurePublicationBoundary: '失败原因：最终结果没有在安全发布边界内完整交付。', failureService: '失败原因：推荐服务没有完成本轮请求。', failureTimeLimit: '失败原因：整轮总时限已用完，模型或检索仍未形成完整结果。', failureModelNotConfigured: '失败原因：当前账号没有可用的推荐模型配置。', failureProviderCall: '失败原因：推荐模型连接失败或提前中断；这不代表没有匹配候选。', failureProviderProtocol: '失败原因：模型返回的工具协议无法安全解析，因此没有执行不确定动作。', failureProviderTruncated: '失败原因：模型输出达到长度上限，回答或工具参数不完整。', failureEmptyModel: '失败原因：模型既没有自然回答也没有选择工具；系统没有伪造回复或强制再调用一次。', failureRepeatedParallel: '失败原因：模型在看到逐步执行提示后，仍重复同一组并行动作。', failureRepeatedInvalid: '失败原因：模型在看到参数错误后，仍重复完全相同的无效动作。', failurePublicationRejected: '失败原因：候选、证据归属或完整回复没有通过发布校验，因此未展示未经支持的推荐。', failureUnclassified: '失败原因：推荐服务遇到未能归类的运行故障，没有写入未完成结果。', retry: '重试', modelSettings: '前往模型设置', profile: '这次想找',
    players: '{value} 人', duration: '{value} 分钟内', durationAny: '时长不限', weight: '复杂度 ≤ {value}', weightAny: '复杂度不限',
    source: '可核对的 BGG 资料 · 从完整 BGG 目录中核对了 {count} 款候选。', more: '换一批',
    researchSources: '资料来源',
    recommendationJudgment: '我的选择与取舍',
    shortfall: '本轮已核对 · {available} / {requested} 款',
    understanding: '目前记下的偏好', basedOn: '你提到：“{value}”', low: '可能', medium: '大概', high: '明确',
    toolTrail: '本轮查找与核对', toolUnderstand: '理解你的条件', toolCatalog: '浏览 BGG 目录候选',
    toolReference: '在 BGG 核对参考游戏',
    toolNames: '完整目录按标题找候选', toolDetails: 'BGG 详情核对', toolDiscover: '公开资料发现候选', toolResearch: '体验资料查证', toolCompare: '按候选事实并排核对',
    starters: ['想找和我喜欢的一款机制相近的', '先聊聊最近流行什么', '朋友聚会，想热闹但不要尴尬', '我不确定，先问我一个问题吧'],
    type: '类型：{value}', interaction: '互动：{value}',
    journeyPending: '正在确认《{game}》的准备状态', journeyWorking: '正在为《{game}》获取规则书并生成讲解 · 当前步骤 {progress}%', journeyWorkingUnknown: '正在为《{game}》获取规则书并生成讲解', journeyReady: '《{game}》的基础讲解可读',
    journeyFailed: '《{game}》的准备流程需要处理', journeyStopped: '《{game}》的本次生成已停止，可从已完成内容重新开始', journeyPreserved: '《{game}》的已完成讲解可读，本次生成已经停止', journeyStatusLabel: '从推荐到答疑', journeyOpen: '打开进度', journeyRead: '打开讲解', journeyReadRulebook: '阅读规则书', journeyProgress: '查看详细进度', journeyAllWork: '全部任务', journeyDialog: '规则书与讲解进度',
    recommendationRole: '继续推荐', answerRole: '规则答疑', roleLabel: '切换任务',
    loginRequired: '推荐需要登录；你写的条件已保留在这个浏览器会话中。登录后回来检查一下，再发送。',
    login: '登录并继续', register: '创建账号', checkingSession: '正在确认登录…',
    resetFailed: '服务器没有确认删除，当前对话仍然保留。请重试。',
  },
  en: {
    eyebrow: 'Choose together', title: 'What should we play tonight?',
    description: 'Talk as you would with a friend: name a game, describe a feeling, or say what missed the mark. I will continue from context; no form-filling required.',
    initial: 'Good evening. Want to choose a game together, or chat about what you have enjoyed lately? Start anywhere—a title, a mood, or the group.',
    inputLabel: 'Tell us what you want to play', inputPlaceholder: 'For example: something with similar mechanisms to a tile-drafting game, but more interaction', send: 'Send', sending: 'Thinking from where we left off…', workingReply: 'Replying', workingSearch: 'Finding board games', workingRecommendation: 'Preparing the recommendation', replyingDetail: 'Writing the reply…',
    reset: 'Clear this conversation', newChat: 'New chat', chatHistory: 'Chat history', chatUntitled: 'New board-game chat', error: 'That reply did not come through. Your preferences are still here.', failureReply: 'This turn did not produce a recommendation that was safe to commit, so I did not guess or invent candidates. Your request and existing preferences are still here; you can retry directly.', unavailableError: 'This recommendation did not complete and was not written into the conversation. This page still has your request, and verified context remains in the session, so you can retry it.', modelConfigurationError: 'This recommendation did not complete and was not written into the conversation. Your request is still saved; first configure and save a recommendation model. With the configuration unchanged, the same request cannot succeed and will not retry automatically.', failureTimeBudget: 'Why it failed: search, verification, or generation did not finish within this turn’s time limit.', failureModelResponse: 'Why it failed: the model did not return a complete executable structure, so provisional text was not published.', failureServiceConfiguration: 'Why it failed: the recommendation model or a required capability is not currently configured.', failureActionBudget: 'Why it failed: after receiving step-by-step or argument-validation feedback, the model repeated an incompatible or invalid action.', failurePublicationBoundary: 'Why it failed: the final result did not complete inside the safe publication boundary.', failureService: 'Why it failed: the recommendation service did not complete this turn.', failureTimeLimit: 'Why it failed: the total turn time limit expired before the model or retrieval produced a complete result.', failureModelNotConfigured: 'Why it failed: this account has no available recommendation-model configuration.', failureProviderCall: 'Why it failed: the recommendation-model request failed or disconnected; this does not mean no candidate matched.', failureProviderProtocol: 'Why it failed: the model returned a tool protocol that could not be parsed safely, so no uncertain action ran.', failureProviderTruncated: 'Why it failed: the model reached its output limit, leaving the answer or action arguments incomplete.', failureEmptyModel: 'Why it failed: the model returned neither a natural reply nor an action; the app did not invent text or force another call.', failureRepeatedParallel: 'Why it failed: after a step-by-step observation, the model repeated the same parallel action set.', failureRepeatedInvalid: 'Why it failed: after an argument error, the model repeated the identical invalid action.', failurePublicationRejected: 'Why it failed: the candidates, evidence ownership, or complete reply failed publication checks, so no unsupported recommendation was shown.', failureUnclassified: 'Why it failed: the recommendation service hit an unclassified runtime fault and did not save an incomplete result.', retry: 'Retry', modelSettings: 'Open model settings', profile: 'Looking for',
    players: '{value} players', duration: 'Up to {value} min', durationAny: 'Any duration', weight: 'Complexity ≤ {value}', weightAny: 'Any complexity',
    source: 'Verifiable BGG details · Checked {count} candidates against the complete BGG catalog.', more: 'Try another batch',
    researchSources: 'Sources',
    recommendationJudgment: 'My choice and tradeoffs',
    shortfall: 'Verified this turn · {available} / {requested}',
    understanding: 'Preferences so far', basedOn: 'You said: “{value}”', low: 'Maybe', medium: 'Likely', high: 'Clear',
    toolTrail: 'Search and checks this turn', toolUnderstand: 'Understand your preferences', toolCatalog: 'Browse BGG catalog candidates',
    toolReference: 'Resolve the reference game in BGG',
    toolNames: 'Find titles in the full catalog', toolDetails: 'Verify BGG details', toolDiscover: 'Discover from public sources', toolResearch: 'Verify play experience', toolCompare: 'Compare candidate-scoped facts',
    starters: ['Find something mechanically similar to a game I like', 'Let’s chat about what is popular', 'Lively with friends, but not awkward', 'I am not sure—ask me one useful question'],
    type: 'Type: {value}', interaction: 'Interaction: {value}',
    journeyPending: 'Checking the preparation status for {game}', journeyWorking: 'Getting the rulebook and building a guide for {game} · current step {progress}%', journeyWorkingUnknown: 'Getting the rulebook and building a guide for {game}', journeyReady: 'Base guide ready for {game}',
    journeyFailed: 'The preparation flow for {game} needs attention', journeyStopped: 'This guide run for {game} has stopped; start a new run from completed work', journeyPreserved: 'Completed guide content for {game} is readable; this run has stopped', journeyStatusLabel: 'Recommendation to Q&A', journeyOpen: 'Continue this journey', journeyRead: 'Open guide', journeyReadRulebook: 'Read rulebook', journeyProgress: 'Open detailed progress', journeyAllWork: 'All work', journeyDialog: 'Rulebook and guide progress',
    recommendationRole: 'Recommendations', answerRole: 'Rules Q&A', roleLabel: 'Switch task',
    loginRequired: 'Sign in to use recommendations. Your draft is saved in this browser session; review it and send after you return.',
    login: 'Sign in and continue', register: 'Create account', checkingSession: 'Checking sign-in…',
    resetFailed: 'The server did not confirm deletion, so this conversation is still intact. Try again.',
  },
} as const

const loadingCopy = {
  'zh-CN': {
    requesting: '收到，接着聊下去…', understanding_request: '正在结合前文理解你这句话…',
    selecting_tools: '正在确认下一步该核对什么…',
    searching_bgg_catalog: '正在桌游目录里查找…', reading_game_details: '正在翻看这款游戏的详细资料…',
    discovering_candidates: '正在补充更贴近这个感觉的候选…', verifying_bgg_candidates: '正在核对人数、时长和玩法…',
    researching_game_fit: '正在看看实际游玩感受…', composing_response: '正在整理已经核对的结果…',
  },
  en: {
    requesting: 'Got it. Continuing from here…', understanding_request: 'Understanding this turn in the context of the conversation…',
    selecting_tools: 'Choosing what to check next…',
    searching_bgg_catalog: 'Searching the game catalog…', reading_game_details: 'Reading this game\'s details…',
    discovering_candidates: 'Looking for a closer fit…', verifying_bgg_candidates: 'Checking player count, time, and play style…',
    researching_game_fit: 'Checking how it feels to play…', composing_response: 'A few good options are ready…',
  },
} as const

const progressActionCopy: Record<AppLocale, Record<RecommendationProgressAction, string>> = {
  'zh-CN': {
    understand_request: '读取当前消息与对话上下文',
    choose_next_action: '正在确认需要核对的资料',
    reply_to_user: '根据当前对话直接组织回答', ask_user: '确认一个会改变结果的必要信息',
    resolve_bgg_game: '在 BGG 核对玩家提到的完整游戏名',
    inspect_candidate_titles: '按候选标题搜索 BGG 并读取详情', browse_bgg_catalog: '按当前条件浏览 BGG 候选',
    discover_public_candidates: '从公开资料寻找符合描述的新候选', lookup_bgg_games: '读取候选的 BGG 人数、时长与机制详情',
    research_game_fit: '查证有出处的实际游玩感受', compare_candidates: '梳理候选之间有证据的关键差异',
    report_no_match: '说明当前条件下没有足够匹配', recommend_games: '校验候选并组织最终推荐',
  },
  en: {
    understand_request: 'Read the current message and conversation context',
    choose_next_action: 'Confirm what information needs checking',
    reply_to_user: 'Compose a direct response from the conversation', ask_user: 'Ask for one necessary choice that changes the result',
    resolve_bgg_game: 'Resolve the player-authored title in BGG',
    inspect_candidate_titles: 'Search candidate titles and load their BGG details', browse_bgg_catalog: 'Browse BGG under the current constraints',
    discover_public_candidates: 'Find new identities from attributed public sources', lookup_bgg_games: 'Load player count, time, and mechanism facts from BGG',
    research_game_fit: 'Check attributed reports about play experience', compare_candidates: 'Compare candidate-scoped evidence',
    report_no_match: 'Explain the shortfall under the current constraints', recommend_games: 'Validate candidates and compose the recommendation',
  },
}

type LoadingStage = 'requesting' | RecommendationProgressStage

type CopyKey = Exclude<keyof typeof copy['zh-CN'], 'starters'>
type PendingRequest = {
  clientTurnId: string
  responseLocale: AppLocale
  message: string
  profile: RecommendationProfile
  excludedBggIds: number[]
  focusedBggId: number | null
  transcript: RecommendationMessage[]
  knownGames: { bggId: number; name: string; originalName: string }[]
  shownBggIds: number[]
}

function translated(responseLocale: AppLocale, key: CopyKey, parameters: Record<string, string | number> = {}) {
  return copy[responseLocale][key].replace(/\{(\w+)\}/g, (placeholder, name: string) => parameters[name] === undefined ? placeholder : String(parameters[name]))
}

function t(key: CopyKey, parameters: Record<string, string | number> = {}) {
  return translated(locale.value, key, parameters)
}

function responseT(
  turnResponse: RecommendationAgentResponse | null | undefined,
  key: CopyKey,
  parameters: Record<string, string | number> = {},
) {
  return translated(turnResponse?.responseLocale ?? locale.value, key, parameters)
}

function emptyProfile(): RecommendationProfile {
  return emptyRecommendationProfile()
}

function initialClarification(): RecommendationClarification {
  return { field: 'conversation', prompt: t('initial'), options: copy[locale.value].starters.map(label => ({ value: label, label })) }
}

const DRAFT_STORAGE_KEY = 'rulepilot:recommendation-draft:v2'

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

function playerSafeFailureBoundary(value: unknown): RecommendationFailureBoundary | null {
  return value === 'time_budget'
    || value === 'model_response'
    || value === 'service_configuration'
    || value === 'action_budget'
    || value === 'publication_boundary'
    || value === 'service_failure'
    ? value
    : null
}

function playerSafeFailureReason(value: unknown): RecommendationFailureReason | null {
  return value === 'time_limit'
    || value === 'model_not_configured'
    || value === 'resource_budget_exhausted'
    || value === 'provider_call_failed'
    || value === 'provider_protocol_invalid'
    || value === 'provider_output_truncated'
    || value === 'empty_model_response'
    || value === 'repeated_incompatible_actions'
    || value === 'repeated_invalid_action'
    || value === 'publication_rejected'
    || value === 'service_failure'
    ? value
    : null
}

function boundedPlayerFacingText(value: string, maximumCodePoints = 1_200) {
  return Array.from(value.trim()).slice(0, maximumCodePoints).join('')
}

function restoredDraft() {
  try {
    const parsed = JSON.parse(sessionStorage.getItem(DRAFT_STORAGE_KEY) ?? 'null') as unknown
    if (!parsed || typeof parsed !== 'object') return ''
    const saved = parsed as { draft?: unknown }
    return typeof saved.draft === 'string'
      ? saved.draft
      : ''
  } catch {
    return ''
  }
}

function rememberDraft(value: string) {
  try {
    if (value) {
      sessionStorage.setItem(DRAFT_STORAGE_KEY, JSON.stringify({ draft: value }))
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
const messages = ref<RecommendationMessage[]>([])
const draft = ref(restoredDraft())
const loading = ref(false)
const loadingStage = ref<LoadingStage>('requesting')
const reportedLoadingStages = ref<RecommendationProgressUpdate[]>([])
const latestRecommendationProgress = ref<RecommendationProgressUpdate | null>(null)
const loadingElapsedSeconds = ref(0)
const failed = ref(false)
const unavailableFailure = ref(false)
const turnIdentityConflict = ref(false)
const failureBoundary = ref<RecommendationFailureBoundary | null>(null)
const failureReason = ref<RecommendationFailureReason | null>(null)
const failedAssistantMessage = ref('')
const pendingAssistantPreview = ref('')
const activeTurnLocale = ref<AppLocale | null>(null)
const failedTurnLocale = ref<AppLocale | null>(null)
const loginGateVisible = ref(false)
const lastRequest = ref<PendingRequest | null>(null)
const seenBggIds = ref<number[]>([])
const knownGames = ref<RecommendationGame[]>([])
const rememberedKnownGames = ref<RecommendationConversationGame[]>([])
const activeFocusedBggId = ref<number | null>(null)
const selectedGame = ref<RecommendationGame | null>(null)
const journeyGames = ref<RecommendationGame[]>([])
const journeyStatuses = ref<Record<number, RecommendationJourneyStatus>>({})
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
const conversationHistory = ref<RecommendationServerSession[]>([])
const conversationHistoryOpen = ref(false)
const conversationNavigationPending = ref(false)
const serverSessionReady = ref(props.sessionIdentity === undefined || !explicitSessionOwner(props.sessionIdentity))
let messageId = 1
let csrf: { headerName: string; token: string } | null = null
let loadingClock: ReturnType<typeof setInterval> | null = null
let activeRequest: AbortController | null = null
let restoredConversationOwner: string | null = null
let restoringConversation = false
let serverRestoreGeneration = 0
let turnCancellationGeneration = 0
let disposed = false
let selectedBggIdToRestore: number | null = null

function journeyStorageKey(owner = restoredConversationOwner) {
  return owner ? `rulepilot:recommendation-journeys:v1:${encodeURIComponent(owner)}` : null
}

function isStoredJourneyGame(value: unknown): value is RecommendationGame {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const game = value as Partial<RecommendationGame>
  return Number.isSafeInteger(game.bggId) && Number(game.bggId) > 0
    && typeof game.name === 'string' && Boolean(game.name.trim())
    && typeof game.originalName === 'string'
    && typeof game.thumbnailUrl === 'string'
    && Array.isArray(game.categories)
    && Array.isArray(game.mechanics)
}

function restoreJourneyGames(owner: string | null) {
  journeyGames.value = []
  journeyStatuses.value = {}
  const key = journeyStorageKey(owner)
  if (!key) return
  try {
    const raw = sessionStorage.getItem(key)
    if (raw === null) return
    const stored = JSON.parse(raw) as unknown
    if (!Array.isArray(stored)) {
      sessionStorage.removeItem(key)
      return
    }
    journeyGames.value = stored.filter(isStoredJourneyGame)
  } catch {
    sessionStorage.removeItem(key)
  }
}

function persistJourneyGames(owner = restoredConversationOwner) {
  const key = journeyStorageKey(owner)
  if (!key) return false
  try {
    sessionStorage.setItem(key, JSON.stringify(journeyGames.value))
    return true
  } catch {
    // Server-side jobs continue even when this browser cannot persist navigation shortcuts.
    return false
  }
}

const sessionKnown = computed(() => props.sessionIdentity !== null)
const signedIn = computed(() => props.sessionIdentity === undefined
  || (typeof props.sessionIdentity === 'string' && Boolean(props.sessionIdentity.trim())))

useModalFocus({
  dialog: journeyDialog,
  open: () => openSurface.value === 'journey',
  requestClose: () => { openSurface.value = 'none' },
  restoreFocus: journeyReturnTarget,
})

const publiclyReportedActions = new Set<RecommendationProgressAction>([
  'understand_request',
  'reply_to_user',
  'ask_user',
  'resolve_bgg_game',
  'inspect_candidate_titles',
  'browse_bgg_catalog',
  'discover_public_candidates',
  'lookup_bgg_games',
  'research_game_fit',
  'compare_candidates',
  'report_no_match',
  'recommend_games',
])

const externalWorkActions = new Set<RecommendationProgressAction>([
  'resolve_bgg_game',
  'inspect_candidate_titles',
  'browse_bgg_catalog',
  'discover_public_candidates',
  'lookup_bgg_games',
  'research_game_fit',
  'compare_candidates',
  'report_no_match',
  'recommend_games',
])

const externalWorkActive = computed(() => {
  const progress = latestRecommendationProgress.value
  return Boolean(
    progress?.action !== null && progress?.action !== undefined && externalWorkActions.has(progress.action)
    || reportedLoadingStages.value.some(update =>
      update.action !== null && externalWorkActions.has(update.action)),
  )
})

const publicLoadingStage = computed<LoadingStage>(() => {
  const stage = loadingStage.value
  if (stage !== 'selecting_tools') return stage
  return externalWorkActive.value ? 'composing_response' : 'understanding_request'
})

const loadingWorkTitle = computed(() => translated(
  activeTurnLocale.value ?? locale.value,
  externalWorkActive.value && publicLoadingStage.value === 'composing_response'
    ? 'workingRecommendation'
    : externalWorkActive.value ? 'workingSearch' : 'workingReply',
))

const loadingMessage = computed(() => {
  const turnLocale = activeTurnLocale.value ?? locale.value
  const message = loadingCopy[turnLocale][publicLoadingStage.value]
  return loadingElapsedSeconds.value > 0 ? `${message} ${loadingElapsedSeconds.value}s` : message
})

function progressStepLabel(update: RecommendationProgressUpdate) {
  const turnLocale = activeTurnLocale.value ?? locale.value
  const action = progressFocusCopy(update, turnLocale) ?? (update.action
    ? progressActionCopy[turnLocale][update.action]
    : loadingCopy[turnLocale][update.stage])
  const phase = turnLocale === 'zh-CN'
    ? { started: '开始', completed: '完成', retrying: '未通过校验，重新选择', failed: '失败' }[update.phase]
    : { started: 'Started', completed: 'Completed', retrying: 'Validation failed; choosing again', failed: 'Failed' }[update.phase]
  return `${phase}：${action}`
}

function progressFocusCopy(update: RecommendationProgressUpdate, turnLocale: AppLocale) {
  const focus = update.focus
  if (!focus) return null
  const quoted = turnLocale === 'zh-CN'
    ? focus.values.map(value => `“${value}”`).join('、')
    : focus.values.map(value => `“${value}”`).join(', ')
  if (turnLocale === 'zh-CN') {
    switch (focus.kind) {
      case 'catalog_mechanics': return `按${quoted}机制筛选 BGG 候选`
      case 'catalog_categories': return `按${quoted}类别筛选 BGG 候选`
      case 'catalog_families': return `按${quoted}系列筛选 BGG 候选`
      case 'catalog_designers': return `按设计师${quoted}筛选 BGG 候选`
      case 'catalog_publishers': return `按出版方${quoted}筛选 BGG 候选`
      case 'candidate_title_count': return `核对 ${focus.values[0]} 个候选标题`
      case 'verified_game_count': return `读取 ${focus.values[0]} 款候选的 BGG 详情`
      case 'research_games': return `查证${focus.values.map(value => `《${value}》`).join('、')}的公开游玩体验`
    }
  }
  switch (focus.kind) {
    case 'catalog_mechanics': return `Filter BGG candidates by the ${quoted} mechanism`
    case 'catalog_categories': return `Filter BGG candidates by the ${quoted} category`
    case 'catalog_families': return `Filter BGG candidates by the ${quoted} family`
    case 'catalog_designers': return `Filter BGG candidates by designer ${quoted}`
    case 'catalog_publishers': return `Filter BGG candidates by publisher ${quoted}`
    case 'candidate_title_count': return `Resolve ${focus.values[0]} candidate titles in BGG`
    case 'verified_game_count': return `Load BGG details for ${focus.values[0]} candidates`
    case 'research_games': return `Check attributed play reports for ${quoted}`
  }
}

function sameProgressFocus(left: RecommendationProgressUpdate['focus'], right: RecommendationProgressUpdate['focus']) {
  return left === right || (left !== null
    && right !== null
    && left.kind === right.kind
    && left.values.length === right.values.length
    && left.values.every((value, index) => value === right.values[index]))
}

const reportedLoadingSteps = computed(() => {
  const events = reportedLoadingStages.value.filter(update =>
    update.action !== null && publiclyReportedActions.has(update.action))
  return events.map((update, index) => ({
    update,
    label: progressStepLabel(update),
    current: index === events.length - 1 && update.phase === 'started',
    icon: update.phase === 'failed' ? '!' : update.phase === 'retrying' ? '↻' : update.phase === 'completed' ? '✓' : '●',
  }))
})
const recommendationEvidenceSummary = computed(() => {
  const progress = latestRecommendationProgress.value
  if (!progress || progress.observedCandidates === 0) return ''
  const localeForTurn = activeTurnLocale.value ?? locale.value
  const stage = publicLoadingStage.value
  if (localeForTurn === 'en') {
    const source = stage === 'researching_game_fit'
      ? 'Sources: BGG facts and attributed public play reports.'
      : 'Source: BGG catalog and game-detail facts.'
    return `${source} ${progress.observedCandidates} candidates seen · ${progress.verifiedCandidates} facts checked · ${progress.hardRejectedCandidates} did not meet your stated constraints. Next: ${loadingCopy.en[stage]}`
  }
  const source = stage === 'researching_game_fit'
    ? '来源：BGG 事实与有出处的公开游玩资料。'
    : '来源：BGG 目录与游戏详情事实。'
  return `${source}目录候选 ${progress.observedCandidates} 款 · 已核对 ${progress.verifiedCandidates} 款 · 有 ${progress.hardRejectedCandidates} 款不满足你明确说出的条件。下一步：${loadingCopy['zh-CN'][stage]}`
})
const recommendationSoftBudgetReached = computed(() => loading.value && externalWorkActive.value && loadingElapsedSeconds.value >= 8)
const hasVerifiedCandidates = computed(() => messages.value.some(message =>
  Boolean(message.response?.games.length || message.response?.comparison?.candidates.length)))
const recommendationSoftBudgetCopy = computed(() => {
  const english = (activeTurnLocale.value ?? locale.value) === 'en'
  if (hasVerifiedCandidates.value) {
    return english
      ? 'Previously verified candidates remain above. This turn still needs catalog facts and fit tradeoffs checked; no new candidate appears before validation.'
      : '上一轮已核对候选仍保留在上方；当前轮还需核对目录事实与匹配取舍，通过校验前不会显示新候选。'
  }
  return english
    ? 'There is not yet a new candidate safe to show. Catalog facts and fit tradeoffs still need checking; unfinished candidates stay hidden.'
    : '目前还没有足以展示的新候选；还需核对目录事实与匹配取舍，未完成候选不会提前显示。'
})
const failureBoundaryCopy: Record<RecommendationFailureBoundary, CopyKey> = {
  time_budget: 'failureTimeBudget',
  model_response: 'failureModelResponse',
  service_configuration: 'failureServiceConfiguration',
  action_budget: 'failureActionBudget',
  publication_boundary: 'failurePublicationBoundary',
  service_failure: 'failureService',
}
const failureReasonCopy: Record<Exclude<RecommendationFailureReason, 'resource_budget_exhausted'>, CopyKey> = {
  time_limit: 'failureTimeLimit',
  model_not_configured: 'failureModelNotConfigured',
  provider_call_failed: 'failureProviderCall',
  provider_protocol_invalid: 'failureProviderProtocol',
  provider_output_truncated: 'failureProviderTruncated',
  empty_model_response: 'failureEmptyModel',
  repeated_incompatible_actions: 'failureRepeatedParallel',
  repeated_invalid_action: 'failureRepeatedInvalid',
  publication_rejected: 'failurePublicationRejected',
  service_failure: 'failureUnclassified',
}
const resourceBudgetRecoveryCopy = {
  'zh-CN': {
    summary: '这次推荐没有完成，也没有写入对话结果。你刚才的请求仍保留；请缩小问题后重新发送，或建立新对话后再试。',
    explanation: '失败原因：本轮 token 安全预算，或由它派生的 step/tool 安全预算已用尽；系统已停止继续执行，不会发布未完成结果。',
    action: '缩小问题后再发送',
  },
  en: {
    summary: 'This recommendation did not complete and was not written into the conversation. Your request is still saved; narrow it and send a new turn, or retry from a new conversation.',
    explanation: 'Why it failed: this turn exhausted its token safety budget or the step/tool safety budgets derived from it. Execution stopped without publishing an incomplete result.',
    action: 'Narrow the request and send again',
  },
} as const
const requiresModelConfiguration = computed(() => unavailableFailure.value
  && failureReason.value === 'model_not_configured')
const requiresChangedRequest = computed(() => unavailableFailure.value
  && failureReason.value === 'resource_budget_exhausted')
const failureMessage = computed(() => {
  const responseLocale = failedTurnLocale.value ?? locale.value
  const summary = requiresModelConfiguration.value
    ? translated(responseLocale, 'modelConfigurationError')
    : requiresChangedRequest.value
      ? resourceBudgetRecoveryCopy[responseLocale].summary
      : translated(responseLocale, unavailableFailure.value ? 'unavailableError' : 'error')
  const reason = failureReason.value
  const explanation = requiresChangedRequest.value
    ? resourceBudgetRecoveryCopy[responseLocale].explanation
    : unavailableFailure.value && reason && reason !== 'resource_budget_exhausted'
      ? translated(responseLocale, failureReasonCopy[reason])
    : unavailableFailure.value && failureBoundary.value
      ? translated(responseLocale, failureBoundaryCopy[failureBoundary.value])
      : ''
  return [summary, explanation].filter(Boolean).join(' ')
})
const visibleFailedAssistantMessage = computed(() => failedAssistantMessage.value
  || (failed.value ? translated(failedTurnLocale.value ?? locale.value, 'failureReply') : ''))
const retryLabel = computed(() => translated(failedTurnLocale.value ?? locale.value, 'retry'))
const modelSettingsLabel = computed(() => translated(failedTurnLocale.value ?? locale.value, 'modelSettings'))
const resourceBudgetActionLabel = computed(() => resourceBudgetRecoveryCopy[failedTurnLocale.value ?? locale.value].action)
const loginLocale = computed(() => activeTurnLocale.value ?? locale.value)

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

function journeyText(game: RecommendationGame, status?: RecommendationJourneyStatus | null) {
  if (!status) return t('journeyPending', { game: game.name })
  const parameters = { game: game.name, progress: status.projection.progress ?? 0 }
  if (status.projection.failureRecovery === 'restart-from-completed') return t('journeyStopped', parameters)
  if (status.projection.failureClassification === 'preserved-stop') return t('journeyPreserved', parameters)
  if (status.projection.failureClassification === 'external-repair') return t('journeyFailed', parameters)
  if (status?.projection.state === 'failed' || status?.projection.retryAction) return t('journeyFailed', parameters)
  if (status?.projection.canReadLesson) return t('journeyReady', parameters)
  return status.projection.progress === null
    ? t('journeyWorkingUnknown', parameters)
    : t('journeyWorking', parameters)
}

function statusForJourney(game: RecommendationGame) {
  return journeyStatuses.value[game.bggId]
}

const answerWorkspaceReady = computed(() => Boolean(
  journeyStatus.value?.projection.canAskQuestions
    && journeyStatus.value.plan?.id
    && journeyStatus.value.importJob?.documentVersionId,
))
const canResetRecommendation = computed(() => Boolean(
  messages.value.length > 0
    || response.value
    || profileLabels.value.length
    || selectedGame.value
    || detailsGame.value
    || failed.value
    || lastRequest.value
    || knownGames.value.length,
))

function toolLabelsFor(turnResponse?: RecommendationAgentResponse) {
  const actions = turnResponse?.completedWork ?? []
  const turnLocale = turnResponse?.responseLocale ?? locale.value
  const labels: string[] = []
  const add = (label: string) => { if (!labels.includes(label)) labels.push(label) }
  if (actions.includes('recommend_games')) add(translated(turnLocale, 'toolUnderstand'))
  if (actions.includes('resolve_bgg_game')) add(translated(turnLocale, 'toolReference'))
  if (actions.includes('browse_bgg_catalog')) add(translated(turnLocale, 'toolCatalog'))
  if (actions.includes('inspect_candidate_titles')) add(translated(turnLocale, 'toolNames'))
  if (actions.includes('lookup_bgg_games')) add(translated(turnLocale, 'toolDetails'))
  if (actions.includes('discover_public_candidates')) add(translated(turnLocale, 'toolDiscover'))
  if (actions.includes('research_game_fit')) add(translated(turnLocale, 'toolResearch'))
  if (actions.includes('compare_candidates')) add(translated(turnLocale, 'toolCompare'))
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
  pendingAssistantPreview.value = ''
  loadingStage.value = 'requesting'
  reportedLoadingStages.value = []
  latestRecommendationProgress.value = null
  loadingElapsedSeconds.value = 0
  const startedAt = Date.now()
  loadingClock = setInterval(() => { loadingElapsedSeconds.value = Math.floor((Date.now() - startedAt) / 1000) }, 1000)
  loading.value = true
}

function endLoading() {
  if (loadingClock) clearInterval(loadingClock)
  loadingClock = null
  activeRequest = null
  pendingAssistantPreview.value = ''
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
  requestedResponseLocale?: AppLocale,
) {
  if (!serverSessionReady.value) return
  const responseLocale = requestedResponseLocale ?? locale.value
  let optimisticUserMessageId: number | null = null
  if (userLabel) {
    optimisticUserMessageId = ++messageId
    messages.value.push({ id: optimisticUserMessageId, role: 'user', text: userLabel })
  }
  const transcript = playerConversationTranscript()
  const pending = {
    clientTurnId: crypto.randomUUID(),
    responseLocale,
    message,
    profile: canonicalRecommendationProfile(requestProfile),
    excludedBggIds: [...excludedBggIds],
    focusedBggId,
    transcript,
    knownGames: minimalKnownGames(),
    shownBggIds: [...seenBggIds.value],
  }
  await submitPendingTurn(pending, optimisticUserMessageId)
}

function copiedPendingRequest(pending: PendingRequest): PendingRequest {
  return {
    clientTurnId: pending.clientTurnId,
    responseLocale: pending.responseLocale,
    message: pending.message,
    profile: canonicalRecommendationProfile(pending.profile),
    excludedBggIds: [...pending.excludedBggIds],
    focusedBggId: pending.focusedBggId,
    transcript: pending.transcript.map(turn => ({ ...turn })),
    knownGames: pending.knownGames.map(candidate => ({ ...candidate })),
    shownBggIds: [...pending.shownBggIds],
  }
}

function pendingUserText(pending: PendingRequest) {
  const lastTurn = pending.transcript.at(-1)
  return lastTurn?.role === 'user' ? lastTurn.text : pending.message
}

async function submitPendingTurn(
  pendingValue: PendingRequest,
  optimisticUserMessageId: number | null = null,
  revisionRetry = 0,
) {
  if (!serverSessionReady.value || disposed) return
  const cancellationGeneration = turnCancellationGeneration
  const pending = copiedPendingRequest(pendingValue)
  lastRequest.value = pending
  activeTurnLocale.value = pending.responseLocale
  failedTurnLocale.value = null
  unavailableFailure.value = false
  turnIdentityConflict.value = false
  failureBoundary.value = null
  failureReason.value = null
  failedAssistantMessage.value = ''
  beginLoading()
  failed.value = false
  try {
    const token = await csrfToken()
    if (disposed
      || cancellationGeneration !== turnCancellationGeneration
      || !serverSessionReady.value) return
    activeRequest = new AbortController()
    const serverResponse = await streamGameRecommendation(`/api/v1/bgg/recommendation-agent/stream?locale=${encodeURIComponent(pending.responseLocale)}`, {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json', [token.headerName]: token.token },
      body: JSON.stringify({
        ...pending,
        conversationId: conversationId.value,
        revision: conversationRevision.value,
        transcript: pending.transcript.map(({ role, text }) => ({ role, text })),
      }),
      signal: activeRequest.signal,
    }, update => {
      latestRecommendationProgress.value = update
      loadingStage.value = update.stage
      const previous = reportedLoadingStages.value.at(-1)
      const repeated = previous
        && previous.stage === update.stage
        && previous.phase === update.phase
        && previous.action === update.action
        && sameProgressFocus(previous.focus, update.focus)
        && previous.observedCandidates === update.observedCandidates
        && previous.verifiedCandidates === update.verifiedCandidates
        && previous.hardRejectedCandidates === update.hardRejectedCandidates
      if (!repeated) {
        reportedLoadingStages.value = [...reportedLoadingStages.value, update].slice(-16)
      }
      loadingElapsedSeconds.value = Math.max(loadingElapsedSeconds.value, Math.floor(update.elapsedMs / 1000))
    }, text => {
      if (disposed || cancellationGeneration !== turnCancellationGeneration) return
      pendingAssistantPreview.value = text
    })
    if (serverResponse.clientTurnId && serverResponse.clientTurnId !== pending.clientTurnId) {
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
      responseLocale: serverResponse.responseLocale ?? pending.responseLocale,
    }
    activeTurnLocale.value = parsed.responseLocale ?? pending.responseLocale
    if (parsed.outcome === 'unavailable') {
      profile.value = parsed.profile
      clarification.value = null
      failedTurnLocale.value = parsed.responseLocale ?? pending.responseLocale
      unavailableFailure.value = true
      failureBoundary.value = playerSafeFailureBoundary(parsed.failureBoundary)
      failureReason.value = playerSafeFailureReason(parsed.failureReason)
      failedAssistantMessage.value = boundedPlayerFacingText(parsed.assistantMessage)
      failed.value = true
      return
    }
    profile.value = parsed.profile
    clarification.value = parsed.clarification
    response.value = parsed
    const responseGames = [
      ...parsed.games.map(entry => entry.game),
      ...(parsed.comparison?.candidates.map(candidate => candidate.game) ?? []),
    ]
    seenBggIds.value = [...new Set([...seenBggIds.value, ...responseGames.map(game => game.bggId)])]
    knownGames.value = uniqueRecommendationGames([...responseGames, ...knownGames.value])
    rememberedKnownGames.value = minimalKnownGames()
    messages.value.push({
      id: ++messageId,
      role: 'assistant',
      text: parsed.assistantMessage,
      response: parsed,
    })
    lastRequest.value = null
  } catch (error) {
    if (disposed || cancellationGeneration !== turnCancellationGeneration) return
    pendingAssistantPreview.value = ''
    failedTurnLocale.value = pending.responseLocale
    if (error instanceof RecommendationRequestError && error.status === 401) {
      if (optimisticUserMessageId !== null) {
        messages.value = messages.value.filter(item => item.id !== optimisticUserMessageId)
      }
      csrf = null
      draft.value = pendingUserText(pending)
      loginGateVisible.value = true
      notifyLoginRequired({ showReminder: false })
    } else if (error instanceof RecommendationStreamError
      && error.code === 'revision_conflict'
      && revisionRetry === 0
      && restoredConversationOwner) {
      clarification.value = null
      serverSessionReady.value = false
      serverRestoreGeneration += 1
      const generation = serverRestoreGeneration
      await restoreServerRecommendationConversation(restoredConversationOwner, generation)
      if (!disposed
        && cancellationGeneration === turnCancellationGeneration
        && serverSessionReady.value) {
        if (response.value?.clientTurnId === pending.clientTurnId) {
          lastRequest.value = null
          return
        }
        return await submitPendingTurn(pending, optimisticUserMessageId, revisionRetry + 1)
      }
      failed.value = true
    } else {
      clarification.value = null
      turnIdentityConflict.value = error instanceof RecommendationStreamError
        && error.code === 'turn_id_reused'
      if (turnIdentityConflict.value) {
        lastRequest.value = { ...copiedPendingRequest(pending), clientTurnId: crypto.randomUUID() }
      }
      failed.value = true
      if (!disposed
        && !turnIdentityConflict.value
        && restoredConversationOwner) {
        serverSessionReady.value = false
        serverRestoreGeneration += 1
        await restoreServerRecommendationConversation(restoredConversationOwner, serverRestoreGeneration)
      }
    }
  } finally {
    if (cancellationGeneration === turnCancellationGeneration) endLoading()
  }
}

function choose(option: { value: string; label: string }) {
  if (!clarification.value || loading.value) return
  void sendTurn(
    option.value,
    profile.value,
    option.label,
    [],
    null,
    response.value?.responseLocale,
  )
}

function submitMessage() {
  const message = draft.value.trim()
  if (!message || loading.value || !sessionKnown.value || !serverSessionReady.value) return
  if (!signedIn.value) {
    loginGateVisible.value = true
    notifyLoginRequired({ showReminder: false })
    return
  }
  loginGateVisible.value = false
  draft.value = ''
  // A card or journey selected in the UI is not a linguistic reference in a later free-form turn.
  // The transcript still lets the Agent resolve real continuations such as “它呢？”, while an
  // explicit new person, game, or topic is no longer structurally pinned to the old selection.
  void sendTurn(message, profile.value, message, [], null)
}

function moreGames(turnResponse?: RecommendationAgentResponse) {
  if (!turnResponse?.games.length || loading.value || turnResponse !== response.value) return
  const responseLocale = turnResponse.responseLocale ?? locale.value
  const message = translated(responseLocale, 'more')
  void sendTurn(message, profile.value, message, seenBggIds.value, null, responseLocale)
}

function introduce(bggId: number, name: string, responseLocale: AppLocale) {
  if (loading.value) return
  activeFocusedBggId.value = bggId
  const message = responseLocale === 'zh-CN' ? `介绍一下《${name}》` : `Tell me more about ${name}`
  void sendTurn(message, profile.value, message, [], bggId, responseLocale)
}

function selectGame(game: RecommendationGame) {
  if (selectedGame.value?.bggId !== game.bggId) {
    journeyStatus.value = null
    conversationRole.value = 'recommendation'
  }
  selectedGame.value = game
  journeyGames.value = [
    ...journeyGames.value.filter(candidate => candidate.bggId !== game.bggId),
    game,
  ]
  persistJourneyGames()
  selectedBggIdToRestore = game.bggId
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
  journeyStatuses.value = { ...journeyStatuses.value, [value.game.bggId]: value }
}

function removeCurrentJourney() {
  const game = selectedGame.value
  if (!game) return
  journeyGames.value = journeyGames.value.filter(candidate => candidate.bggId !== game.bggId)
  const nextStatuses = { ...journeyStatuses.value }
  delete nextStatuses[game.bggId]
  journeyStatuses.value = nextStatuses
  journeyStatus.value = null
  openSurface.value = 'none'
  persistJourneyGames()
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

function activateJourneyCard(game: RecommendationGame, target?: EventTarget | null) {
  selectedGame.value = game
  selectedBggIdToRestore = game.bggId
  activeFocusedBggId.value = game.bggId
  journeyStatus.value = statusForJourney(game) ?? null
  journeyDock.value = target instanceof HTMLButtonElement ? target : null
}

function openJourneyCard(game: RecommendationGame, target?: EventTarget | null) {
  activateJourneyCard(game, target)
  openJourneyDock()
}

function openJourneyProgressCard(game: RecommendationGame, target?: EventTarget | null) {
  activateJourneyCard(game, target)
  openJourneyProgress()
}

function openJourneyRulebookCard(game: RecommendationGame, status: RecommendationJourneyStatus) {
  activateJourneyCard(game)
  openRulebook(status)
}

function openJourneyLessonCard(game: RecommendationGame, status: RecommendationJourneyStatus) {
  activateJourneyCard(game)
  openLesson(status)
}

function openJourneyProgress() {
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
  if (requiresModelConfiguration.value || requiresChangedRequest.value) return
  const pending = lastRequest.value
  if (!pending) return
  const retried = unavailableFailure.value
    ? { ...copiedPendingRequest(pending), clientTurnId: crypto.randomUUID() }
    : pending
  failed.value = false
  void submitPendingTurn(retried)
}

function focusNarrowerRequest() {
  recommendationInput.value?.focus({ preventScroll: true })
}

function playerConversationTranscript() {
  return messages.value
    .map(({ id, role, text }) => ({ id, role, text }))
}

function minimalKnownGames() {
  const games = [
    ...knownGames.value.map(game => ({ bggId: game.bggId, name: game.name, originalName: game.originalName })),
    ...rememberedKnownGames.value,
  ]
  const seen = new Set<number>()
  return games.filter(game => {
    if (seen.has(game.bggId)) return false
    seen.add(game.bggId)
    return true
  })
}

function uniqueRecommendationGames(games: RecommendationGame[]) {
  const seen = new Set<number>()
  return games.filter(game => {
    if (seen.has(game.bggId)) return false
    seen.add(game.bggId)
    return true
  })
}

function explicitSessionOwner(value: string | null | undefined) {
  if (typeof value !== 'string') return null
  const owner = value.normalize('NFKC').trim().toLowerCase()
  return owner || null
}

function conversationSnapshot(): RecommendationConversationSnapshot {
  const pending = (failed.value || loading.value) && lastRequest.value
    ? {
        clientTurnId: lastRequest.value.clientTurnId,
        responseLocale: lastRequest.value.responseLocale,
        message: lastRequest.value.message,
        excludedBggIds: [...lastRequest.value.excludedBggIds],
        focusedBggId: lastRequest.value.focusedBggId,
      }
    : null
  return {
    conversationId: conversationId.value,
    revision: conversationRevision.value,
    responseLocale: response.value?.responseLocale ?? activeTurnLocale.value,
    profile: canonicalRecommendationProfile(profile.value),
    transcript: playerConversationTranscript().map(({ role, text }) => ({ role, text })),
    knownGames: minimalKnownGames(),
    shownBggIds: [...seenBggIds.value],
    selectedBggId: selectedGame.value?.bggId ?? selectedBggIdToRestore,
    failed: Boolean(failed.value && pending),
    pending,
  }
}

function persistRecommendationConversation(owner = restoredConversationOwner) {
  if (!owner || restoringConversation) return
  rememberRecommendationConversation(sessionStorage, owner, conversationSnapshot())
}

function clearVisibleRecommendationConversation(preserveSelectedIdentity = false) {
  conversationId.value = null
  conversationRevision.value = 0
  profile.value = emptyProfile()
  clarification.value = initialClarification()
  response.value = null
  messages.value = []
  failed.value = false
  unavailableFailure.value = false
  turnIdentityConflict.value = false
  failureBoundary.value = null
  failureReason.value = null
  failedAssistantMessage.value = ''
  activeTurnLocale.value = null
  failedTurnLocale.value = null
  lastRequest.value = null
  seenBggIds.value = []
  knownGames.value = []
  rememberedKnownGames.value = []
  activeFocusedBggId.value = null
  selectedGame.value = null
  if (!preserveSelectedIdentity) selectedBggIdToRestore = null
  detailsGame.value = null
  journeyStatus.value = null
  conversationRole.value = 'recommendation'
  openSurface.value = 'none'
}

function restoreRecommendationConversation(owner: string) {
  const snapshot = readRecommendationConversation(sessionStorage, owner)
  clearVisibleRecommendationConversation()
  if (!snapshot) return

  profile.value = canonicalRecommendationProfile(snapshot.profile)
  conversationId.value = snapshot.conversationId
  conversationRevision.value = snapshot.revision
  activeTurnLocale.value = snapshot.responseLocale
  clarification.value = null
  messages.value = snapshot.transcript.map(turn => ({ id: ++messageId, ...turn }))
  rememberedKnownGames.value = snapshot.knownGames.map(game => ({ ...game }))
  seenBggIds.value = [...snapshot.shownBggIds]
  selectedBggIdToRestore = snapshot.selectedBggId
  failed.value = Boolean(snapshot.failed && snapshot.pending)
  failedTurnLocale.value = failed.value
    ? snapshot.pending?.responseLocale ?? snapshot.responseLocale
    : null
  if (snapshot.pending) {
    const responseLocale = snapshot.pending.responseLocale ?? snapshot.responseLocale ?? locale.value
    activeFocusedBggId.value = snapshot.pending.focusedBggId
    lastRequest.value = {
      clientTurnId: snapshot.pending.clientTurnId ?? crypto.randomUUID(),
      responseLocale,
      message: snapshot.pending.message,
      profile: canonicalRecommendationProfile(snapshot.profile),
      excludedBggIds: [...snapshot.pending.excludedBggIds],
      focusedBggId: snapshot.pending.focusedBggId,
      transcript: playerConversationTranscript(),
      knownGames: minimalKnownGames(),
      shownBggIds: [...snapshot.shownBggIds],
    }
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
  const pending = lastRequest.value ? copiedPendingRequest(lastRequest.value) : null
  const pendingTurnIdentityConflict = turnIdentityConflict.value
  const selectedBggId = selectedGame.value?.bggId ?? selectedBggIdToRestore
  const unavailableResponse = session.latestResponse?.outcome === 'unavailable'
  clearVisibleRecommendationConversation(true)
  conversationId.value = session.conversationId
  conversationRevision.value = session.revision
  profile.value = canonicalRecommendationProfile(session.profile)
  messages.value = session.transcript.map(turn => ({ id: ++messageId, ...turn }))
  rememberedKnownGames.value = session.knownGames.map(game => ({ ...game }))
  seenBggIds.value = [...session.shownBggIds]

  if (session.latestResponse && !unavailableResponse) {
    const latest = {
      ...session.latestResponse,
      profile: canonicalRecommendationProfile(session.latestResponse.profile),
    }
    response.value = latest
    activeTurnLocale.value = latest.responseLocale ?? activeTurnLocale.value
    clarification.value = latest.clarification
    const responseGames = [
      ...latest.games.map(entry => entry.game),
      ...(latest.comparison?.candidates.map(candidate => candidate.game) ?? []),
    ]
    knownGames.value = responseGames
    const restoredSelection = responseGames.find(game => game.bggId === selectedBggId)
    if (restoredSelection) {
      selectedGame.value = restoredSelection
      activeFocusedBggId.value = restoredSelection.bggId
      selectedBggIdToRestore = restoredSelection.bggId
    }
    rememberedKnownGames.value = minimalKnownGames()
    const lastAssistant = [...messages.value].reverse().find(message => message.role === 'assistant')
    if (lastAssistant) lastAssistant.response = latest
  } else {
    clarification.value = null
  }

  const completedPending = pending
    && !unavailableResponse
    && !pendingTurnIdentityConflict
    && protocolUuid(session.latestResponse?.clientTurnId)
    && session.latestResponse.clientTurnId === pending.clientTurnId
  if (pending && !completedPending) {
    clarification.value = null
    lastRequest.value = pending
    activeTurnLocale.value = pending.responseLocale
    failedTurnLocale.value = pending.responseLocale
    activeFocusedBggId.value = pending.focusedBggId
    failed.value = true
    turnIdentityConflict.value = pendingTurnIdentityConflict
    unavailableFailure.value = Boolean(
      unavailableResponse && session.latestResponse?.clientTurnId === pending.clientTurnId,
    )
    failureBoundary.value = unavailableFailure.value
      ? playerSafeFailureBoundary(session.latestResponse?.failureBoundary)
      : null
    failureReason.value = unavailableFailure.value
      ? playerSafeFailureReason(session.latestResponse?.failureReason)
      : null
    failedAssistantMessage.value = unavailableFailure.value
      ? boundedPlayerFacingText(session.latestResponse?.assistantMessage ?? '')
      : ''
    const pendingTurn = pending.transcript.at(-1)
    const visibleLastTurn = messages.value.at(-1)
    if (pendingTurn?.role === 'user'
      && (visibleLastTurn?.role !== 'user' || visibleLastTurn.text !== pendingTurn.text)) {
      messages.value.push({ id: ++messageId, role: 'user', text: pendingTurn.text })
    }
  }
}

async function restoreServerRecommendationConversation(
  owner: string,
  generation: number,
  attempt = 0,
) {
  if (disposed || generation !== serverRestoreGeneration) return
  try {
    const result = await fetch('/api/v1/bgg/recommendation-agent/session', { credentials: 'include' })
    if (disposed || generation !== serverRestoreGeneration) return
    if (!result || typeof result.status !== 'number') return
    if (result.status === 204) return
    if (result.status === 401) {
      loginGateVisible.value = true
      notifyLoginRequired({ showReminder: false })
      return
    }
    if (!result.ok) return
    const candidate = await result.json() as unknown
    if (disposed || generation !== serverRestoreGeneration || !isRecommendationServerSession(candidate)) return

    restoringConversation = true
    applyServerRecommendationConversation(candidate)
    restoringConversation = false
    if (candidate.processing && attempt < 50) {
      if (!loading.value) beginLoading()
      await new Promise(resolve => setTimeout(resolve, 1_000))
      if (!disposed && generation === serverRestoreGeneration) {
        await restoreServerRecommendationConversation(owner, generation, attempt + 1)
      }
      return
    }
  } catch {
    // The bounded browser snapshot remains available when session restoration is temporarily offline.
  } finally {
    if (!disposed && generation === serverRestoreGeneration) {
      restoringConversation = false
      if (attempt === 0 || attempt >= 50) {
        if (loading.value) endLoading()
        serverSessionReady.value = true
        persistRecommendationConversation(owner)
      }
    }
  }
}

async function loadConversationHistory() {
  if (!restoredConversationOwner) return
  try {
    const result = await fetch('/api/v1/bgg/recommendation-agent/sessions', { credentials: 'include' })
    if (!result.ok) return
    const candidates = await result.json() as unknown
    if (!Array.isArray(candidates)) return
    conversationHistory.value = candidates.filter(isRecommendationServerSession)
  } catch {
    // The active conversation remains usable while history is temporarily unavailable.
  }
}

async function toggleConversationHistory() {
  conversationHistoryOpen.value = !conversationHistoryOpen.value
  if (conversationHistoryOpen.value) await loadConversationHistory()
}

function conversationHistoryTitle(session: RecommendationServerSession) {
  const firstPlayerTurn = session.transcript.find(turn => turn.role === 'user')?.text.trim()
  return firstPlayerTurn || t('chatUntitled')
}

async function startNewConversation() {
  if (loading.value || conversationNavigationPending.value || !restoredConversationOwner) return
  conversationNavigationPending.value = true
  try {
    const token = await csrfToken()
    const result = await fetch('/api/v1/bgg/recommendation-agent/sessions', {
      method: 'POST', credentials: 'include', headers: { [token.headerName]: token.token },
    })
    if (!result.ok) throw new RecommendationRequestError(result.status)
    const candidate = await result.json() as unknown
    if (!isRecommendationServerSession(candidate)) throw new Error('new recommendation session is invalid')
    restoringConversation = true
    applyServerRecommendationConversation(candidate)
    restoringConversation = false
    conversationHistoryOpen.value = false
    persistRecommendationConversation()
    await loadConversationHistory()
    await nextTick()
    recommendationInput.value?.focus({ preventScroll: true })
  } finally {
    restoringConversation = false
    conversationNavigationPending.value = false
  }
}

async function openConversationFromHistory(session: RecommendationServerSession) {
  if (loading.value || conversationNavigationPending.value || session.conversationId === conversationId.value) return
  conversationNavigationPending.value = true
  try {
    const result = await fetch(
      `/api/v1/bgg/recommendation-agent/sessions/${encodeURIComponent(session.conversationId)}`,
      { credentials: 'include' },
    )
    if (!result.ok) throw new RecommendationRequestError(result.status)
    const candidate = await result.json() as unknown
    if (!isRecommendationServerSession(candidate)) throw new Error('recommendation session is invalid')
    restoringConversation = true
    applyServerRecommendationConversation(candidate)
    restoringConversation = false
    conversationHistoryOpen.value = false
    persistRecommendationConversation()
  } finally {
    restoringConversation = false
    conversationNavigationPending.value = false
  }
}

function reset(preserveJourney = false) {
  turnCancellationGeneration += 1
  activeRequest?.abort()
  endLoading()
  conversationId.value = null
  conversationRevision.value = 0
  profile.value = emptyProfile()
  clarification.value = initialClarification()
  response.value = null
  messages.value = []
  failed.value = false
  unavailableFailure.value = false
  turnIdentityConflict.value = false
  failureBoundary.value = null
  failureReason.value = null
  failedAssistantMessage.value = ''
  activeTurnLocale.value = null
  failedTurnLocale.value = null
  lastRequest.value = null
  seenBggIds.value = []
  knownGames.value = []
  rememberedKnownGames.value = []
  activeFocusedBggId.value = null
  if (restoredConversationOwner) {
    forgetRecommendationConversation(sessionStorage, restoredConversationOwner)
  }
  if (!preserveJourney) {
    selectedGame.value = null
    selectedBggIdToRestore = null
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

function confidenceLabel(
  confidence: 'low' | 'medium' | 'high',
  responseLocale: AppLocale | undefined,
) {
  return translated(responseLocale ?? locale.value, confidence)
}

function loginT(key: 'loginRequired' | 'login' | 'register') {
  return translated(loginLocale.value, key)
}

watch(locale, () => {
  if (messages.value.length || response.value || lastRequest.value || profileLabels.value.length) return
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
      persistJourneyGames(restoredConversationOwner)
    }
    turnCancellationGeneration += 1
    activeRequest?.abort()
    endLoading()
    serverRestoreGeneration += 1
    const restoreGeneration = serverRestoreGeneration
    restoringConversation = true
    restoredConversationOwner = owner
    clearVisibleRecommendationConversation()
    restoreJourneyGames(owner)
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
  [profile, messages, rememberedKnownGames, seenBggIds, selectedGame, failed, lastRequest, conversationId, conversationRevision, loading],
  () => { persistRecommendationConversation() },
  { deep: true, flush: 'post' },
)
watch(journeyGames, () => { persistJourneyGames() }, { deep: true, flush: 'post' })
watch(
  () => [messages.value.length, pendingAssistantPreview.value, loading.value, loadingStage.value],
  () => { void scrollConversationToLatest() },
  { flush: 'post' },
)
onMounted(() => { void scrollConversationToLatest() })
onBeforeUnmount(() => {
  disposed = true
  serverRestoreGeneration += 1
  turnCancellationGeneration += 1
  if (loading.value && lastRequest.value) failed.value = true
  persistRecommendationConversation()
  persistJourneyGames()
  activeRequest?.abort()
  endLoading()
})
</script>

<template>
  <section class="py-7 sm:py-9" aria-labelledby="recommendation-agent-title">
    <div class="tabletop-panel player-board tabletop-felt overflow-hidden p-1">
      <div class="grid gap-px overflow-hidden rounded-[1.15rem] bg-white/10 lg:grid-cols-[minmax(14rem,0.46fr)_minmax(38rem,1.54fr)]">
        <div class="bg-felt-deep px-5 py-6 sm:px-7 sm:py-8">
          <p class="text-xs font-bold uppercase tracking-[0.16em] text-[#e8bd6a]">{{ t('eyebrow') }}</p>
          <h2 id="recommendation-agent-title" class="mt-2 max-w-xl font-display text-4xl font-semibold leading-none tracking-tight text-white sm:text-5xl">{{ t('title') }}</h2>
          <p class="recommendation-intro-copy mt-4 max-w-xl text-sm leading-7">{{ t('description') }}</p>
          <div v-if="profileLabels.length" class="mt-6"><p class="recommendation-profile-label text-xs font-bold uppercase tracking-[0.12em]">{{ t('profile') }}</p><ul class="mt-2 flex flex-wrap gap-2"><li v-for="label in profileLabels" :key="label" class="recommendation-profile-chip rounded-md border border-white/15 bg-white/7 px-2.5 py-1.5 text-xs font-semibold">{{ label }}</li></ul></div>
          <details v-if="response?.userModel?.summary" class="mt-5 rounded-xl border border-white/10 bg-black/10 p-4">
            <summary class="cursor-pointer text-xs font-bold uppercase tracking-[0.1em] text-[#e8bd6a]">{{ responseT(response, 'understanding') }}</summary>
            <p class="recommendation-understanding-copy mt-3 text-sm leading-6">{{ response.userModel.summary }}</p>
            <ul v-if="response.userModel.hypotheses.length" class="mt-3 stack-y-sm"><li v-for="hypothesis in response.userModel.hypotheses" :key="`${hypothesis.text}-${hypothesis.basedOn}`" class="recommendation-hypothesis text-xs leading-5"><span class="mr-2 font-semibold text-[#e8bd6a]">{{ confidenceLabel(hypothesis.confidence, response.responseLocale) }}</span>{{ hypothesis.text }}<span class="recommendation-basis block">{{ responseT(response, 'basedOn', { value: hypothesis.basedOn }) }}</span></li></ul>
          </details>
          <div v-if="sessionKnown" class="mt-5 flex flex-wrap gap-x-4 gap-y-2">
            <button type="button" :disabled="loading || conversationNavigationPending" class="recommendation-reset min-h-11 text-sm font-semibold underline decoration-light-soft underline-offset-4 disabled:cursor-not-allowed disabled:opacity-40" @click="startNewConversation">{{ t('newChat') }}</button>
            <button type="button" :disabled="loading || conversationNavigationPending" class="recommendation-reset min-h-11 text-sm font-semibold underline decoration-light-soft underline-offset-4 disabled:cursor-not-allowed disabled:opacity-40" :aria-expanded="conversationHistoryOpen" @click="toggleConversationHistory">{{ t('chatHistory') }}</button>
            <button v-if="canResetRecommendation" type="button" :disabled="loading || conversationNavigationPending" class="recommendation-reset min-h-11 text-sm font-semibold underline decoration-light-soft underline-offset-4 disabled:cursor-not-allowed disabled:opacity-40" @click="requestReset">{{ t('reset') }}</button>
          </div>
          <ul v-if="conversationHistoryOpen" class="mt-2 grid gap-2 rounded-xl border border-white/10 bg-black/10 p-2">
            <li v-for="session in conversationHistory" :key="session.conversationId">
              <button type="button" class="w-full rounded-lg px-3 py-2 text-left text-xs leading-5 text-white/70 hover:bg-white/10" :class="session.conversationId === conversationId ? 'bg-white/10 font-semibold text-white' : ''" @click="openConversationFromHistory(session)">{{ conversationHistoryTitle(session) }}</button>
            </li>
          </ul>
        </div>

        <div data-testid="recommendation-chat-workspace" class="min-w-0 bg-paper text-ink">
          <nav v-if="answerWorkspaceReady" data-testid="agent-role-switcher" class="flex gap-2 border-b border-ink/8 px-4 py-3 sm:px-6" :aria-label="t('roleLabel')">
            <button type="button" class="min-h-11 rounded-xl px-4 text-sm font-semibold" :class="conversationRole === 'recommendation' ? 'bg-felt text-white' : 'border border-ink/12 text-ink/60'" :aria-pressed="conversationRole === 'recommendation'" @click="switchToRecommendations">{{ t('recommendationRole') }}</button>
            <button type="button" class="min-h-11 rounded-xl px-4 text-sm font-semibold" :class="conversationRole === 'rule-qa' ? 'bg-indigo text-white' : 'border border-ink/12 text-ink/60'" :aria-pressed="conversationRole === 'rule-qa'" @click="switchToQuestions()">{{ t('answerRole') }}</button>
          </nav>

          <div v-show="conversationRole === 'recommendation'">
            <div ref="conversationScroller" data-testid="recommendation-conversation" class="max-h-[76vh] min-h-80 scroll-pb-8 stack-y-md overflow-y-auto px-4 py-5 pb-8 sm:min-h-[38rem] sm:px-6 sm:py-7 sm:pb-9 lg:max-h-[54rem]">
              <div v-if="messages.length === 0 && !loading && !failed" data-testid="recommendation-empty-state" class="rounded-2xl border border-ink/8 bg-canvas px-4 py-4 text-sm leading-6 text-ink/65">
                <p>{{ t('initial') }}</p>
              </div>
              <div v-for="message in messages" :key="message.id" data-conversation-message :data-has-recommendations="message.response?.games.length ? 'true' : 'false'" class="flex min-w-0" :class="message.role === 'user' ? 'justify-end' : 'justify-start'">
                <p v-if="message.role === 'user'" class="max-w-[88%] rounded-2xl rounded-br-sm bg-felt px-4 py-3 text-sm leading-6 text-white">{{ message.text }}</p>
                <article v-else class="min-w-0 w-full" :data-testid="message.response?.games.length ? 'assistant-recommendation-turn' : 'assistant-conversation-turn'">
                  <span v-if="message.response?.games.length" class="mb-1.5 block pl-1 text-[0.6875rem] font-bold uppercase tracking-[0.12em] text-copper">{{ responseT(message.response, 'recommendationJudgment') }}</span>
                  <SafeMarkdown
                    :source="message.text"
                    :data-testid="message.response?.games.length ? 'assistant-recommendation-message' : undefined"
                    class="max-w-[88%] rounded-2xl rounded-bl-sm border border-ink/8 bg-canvas px-4 py-3 text-sm leading-6 text-ink/72"
                  />

                  <div v-if="message.response?.researchSources?.length" data-testid="recommendation-research-sources" class="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 pl-1 text-[0.6875rem] text-ink/50">
                    <span class="font-semibold">{{ responseT(message.response, 'researchSources') }}</span>
                    <a v-for="source in message.response.researchSources" :key="`${source.index}-${source.url}`" :href="source.url" target="_blank" rel="noopener noreferrer" class="font-medium text-indigo underline decoration-indigo/25 underline-offset-2">{{ source.title }} ↗</a>
                  </div>

                  <div v-if="toolLabelsFor(message.response).length" class="mt-2 flex flex-wrap items-center gap-2 pl-1 text-[0.6875rem] text-ink/45" :aria-label="responseT(message.response, 'toolTrail')">
                    <span class="recommendation-tool-label font-semibold">{{ responseT(message.response, 'toolTrail') }}</span>
                    <span v-for="label in toolLabelsFor(message.response)" :key="label" class="rounded-full border border-ink/10 bg-paper px-2.5 py-1">{{ label }}</span>
                  </div>

                  <div v-if="message.response?.games.length" class="mt-3 rounded-2xl border border-ink/8 bg-canvas/45 p-3 sm:p-4">
                    <p v-if="message.response.shortfall" class="mb-2 inline-flex rounded-full border border-copper/25 bg-copper/5 px-2.5 py-1 text-[0.6875rem] font-semibold text-copper">{{ responseT(message.response, 'shortfall', { available: message.response.shortfall.availableCount, requested: message.response.shortfall.requestedCount }) }}</p>
                    <div class="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                      <p class="recommendation-source-summary text-xs leading-5">{{ responseT(message.response, 'source', { source: message.response.sourceCount.toLocaleString(), count: message.response.candidatesEvaluated }) }}</p>
                      <button v-if="message.response === response" type="button" :disabled="loading" class="min-h-11 self-start text-sm font-semibold text-copper underline decoration-copper-soft underline-offset-4 disabled:opacity-40 sm:self-auto" @click="moreGames(message.response)">{{ responseT(message.response, 'more') }}</button>
                    </div>
                    <TransitionGroup tag="div" name="tile" class="mt-3 grid gap-3 xl:grid-cols-2 2xl:grid-cols-3">
                      <RecommendationGameCard v-for="entry in message.response.games" :key="entry.game.bggId" :entry="entry" :sources="message.response.researchSources ?? []" :loading="loading" :response-locale="message.response.responseLocale" @introduce="introduce" @select="selectGame" @details="openDetails" />
                    </TransitionGroup>
                  </div>
                </article>
              </div>
              <div v-if="loading && pendingAssistantPreview" data-conversation-message data-has-recommendations="false" class="flex min-w-0 justify-start">
                <article data-testid="pending-assistant-preview" class="min-w-0 w-full">
                  <SafeMarkdown :source="pendingAssistantPreview" class="max-w-[88%] rounded-2xl rounded-bl-sm border border-ink/8 bg-canvas px-4 py-3 text-sm leading-6 text-ink/72" />
                </article>
              </div>
              <div v-if="failed && visibleFailedAssistantMessage" data-testid="recommendation-failed-assistant-reply" class="flex min-w-0 justify-start">
                <SafeMarkdown :source="visibleFailedAssistantMessage" class="max-w-[88%] rounded-2xl rounded-bl-sm border border-ink/8 bg-canvas px-4 py-3 text-sm leading-6 text-ink/72" />
              </div>
              <article v-for="game in journeyGames" :key="`journey-${game.bggId}`" data-testid="player-journey-continuation" :data-bgg-id="game.bggId" class="overflow-hidden rounded-2xl border border-copper/25 bg-canvas elevation-sm hover:border-copper/45">
                <button data-testid="player-journey-dock" type="button" class="flex min-h-20 w-full min-w-0 items-center gap-3 px-4 py-3 text-left" @click="openJourneyCard(game, $event.currentTarget)">
                  <img v-if="game.thumbnailUrl" :src="game.thumbnailUrl" :alt="game.name" class="h-14 w-11 shrink-0 rounded-md bg-paper object-contain" referrerpolicy="no-referrer">
                  <span v-else class="grid size-11 shrink-0 place-items-center rounded-lg bg-copper/10 font-mono text-xs font-bold text-copper">{{ statusForJourney(game)?.projection.progress !== null && statusForJourney(game)?.projection.progress !== undefined ? `${statusForJourney(game)?.projection.progress}%` : '…' }}</span>
                  <span class="min-w-0 flex-1">
                    <span class="block text-[0.6875rem] font-bold uppercase tracking-[0.12em] text-copper">{{ t('journeyStatusLabel') }} · {{ game.name }}</span>
                    <span class="mt-1 block text-sm font-semibold leading-5 text-ink">{{ journeyText(game, statusForJourney(game)) }}</span>
                    <span v-if="statusForJourney(game)?.projection.progress !== null && statusForJourney(game)?.projection.progress !== undefined" class="mt-1 block text-xs text-ink/45">{{ statusForJourney(game)?.projection.progress }}% · {{ t('journeyProgress') }}</span>
                    <span v-else-if="statusForJourney(game)" class="mt-1 block text-xs text-ink/45">{{ t('journeyProgress') }}</span>
                  </span>
                  <span class="shrink-0 text-sm font-semibold text-indigo underline">{{ t(statusForJourney(game)?.projection.canReadLesson && statusForJourney(game)?.plan?.id ? 'journeyRead' : 'journeyOpen') }}</span>
                </button>
                <div class="flex flex-col border-t border-copper/20 sm:flex-row">
                  <button data-testid="player-journey-progress-button" type="button" class="inline-flex min-h-11 w-full items-center justify-center px-3 text-sm font-semibold text-indigo underline sm:flex-1" @click="openJourneyProgressCard(game, $event.currentTarget)">{{ t('journeyProgress') }}</button>
                  <button v-if="statusForJourney(game)?.projection.canReadRulebook && statusForJourney(game)?.importJob?.documentVersionId" type="button" class="inline-flex min-h-11 w-full items-center justify-center border-t border-copper/20 px-3 text-sm font-semibold text-indigo underline sm:flex-1 sm:border-l sm:border-t-0" @click="openJourneyRulebookCard(game, statusForJourney(game)!)">{{ t('journeyReadRulebook') }}</button>
                  <button v-if="statusForJourney(game)?.projection.canReadLesson && statusForJourney(game)?.plan?.id" type="button" class="inline-flex min-h-11 w-full items-center justify-center border-t border-copper/20 px-3 text-sm font-semibold text-indigo underline sm:flex-1 sm:border-l sm:border-t-0" @click="openJourneyLessonCard(game, statusForJourney(game)!)">{{ t('journeyRead') }}</button>
                  <RouterLink v-if="statusForJourney(game)?.plan?.id" data-testid="player-journey-all-work-link" :to="{ path: '/work', query: { started: statusForJourney(game)?.plan?.id } }" class="inline-flex min-h-11 w-full items-center justify-center border-t border-copper/20 px-3 text-sm font-semibold text-ink/55 underline sm:flex-1 sm:border-l sm:border-t-0">{{ t('journeyAllWork') }}</RouterLink>
                </div>
              </article>
              <div v-if="loading" class="flex items-start gap-3 rounded-2xl rounded-bl-sm border border-ink/8 bg-canvas px-4 py-3 text-ink/55" role="status">
                <span class="flex gap-1" aria-hidden="true"><span class="size-1.5 animate-pulse rounded-full bg-copper" /><span class="size-1.5 animate-pulse rounded-full bg-copper [animation-delay:160ms]" /><span class="size-1.5 animate-pulse rounded-full bg-copper [animation-delay:320ms]" /></span>
                <div class="min-w-0 flex-1">
                  <strong data-testid="player-work-status" class="block text-sm font-semibold text-ink/70">{{ loadingWorkTitle }}</strong>
                  <span class="mt-0.5 block text-xs">{{ loadingMessage }}</span>
                  <ol v-if="reportedLoadingSteps.length" data-testid="recommendation-progress-steps" class="mt-3 grid gap-1.5 text-xs leading-5">
                    <li v-for="(step, index) in reportedLoadingSteps" :key="`${step.update.stage}-${step.update.phase}-${step.update.action}-${step.update.focus?.kind}-${step.update.focus?.values.join('|')}-${step.update.elapsedMs}-${index}`" class="flex items-start gap-2" :class="step.current ? 'font-semibold text-ink/70' : step.update.phase === 'failed' ? 'text-red-700' : step.update.phase === 'retrying' ? 'text-amber-700' : 'text-ink/50'">
                      <span aria-hidden="true" class="mt-px w-3 shrink-0 text-center">{{ step.icon }}</span>
                      <span>{{ step.label }}</span>
                    </li>
                  </ol>
                  <p v-if="recommendationEvidenceSummary" data-testid="recommendation-evidence-summary" class="mt-2 rounded-lg bg-paper px-3 py-2 text-xs leading-5 text-ink/55">{{ recommendationEvidenceSummary }}</p>
                  <p v-if="recommendationSoftBudgetReached" data-testid="recommendation-soft-budget" class="mt-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-950">{{ recommendationSoftBudgetCopy }}</p>
                </div>
              </div>
            </div>
            <div v-if="clarification?.options.length && !loading && !failed" class="border-t border-ink/8 px-4 py-4 sm:px-6"><div class="flex flex-wrap gap-2"><button v-for="option in clarification.options" :key="option.value" type="button" class="min-h-11 rounded-lg border border-ink/15 bg-ink/5 px-4 text-sm font-semibold text-ink/72 hover:border-copper/50" @click="choose(option)">{{ option.label }}</button></div></div>
            <div v-if="failed" class="mx-4 mb-3 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-800 sm:mx-6" role="alert">
              <p>{{ failureMessage }}</p>
              <RouterLink v-if="requiresModelConfiguration" data-testid="recommendation-model-settings" :to="{ name: 'model-settings' }" class="mt-2 inline-flex min-h-11 items-center font-semibold underline underline-offset-4">{{ modelSettingsLabel }}</RouterLink>
              <button v-else-if="requiresChangedRequest" data-testid="recommendation-revise-after-budget" type="button" class="mt-2 min-h-11 font-semibold underline" @click="focusNarrowerRequest">{{ resourceBudgetActionLabel }}</button>
              <button v-else type="button" class="mt-2 min-h-11 font-semibold underline" @click="retry">{{ retryLabel }}</button>
            </div>
            <div v-if="loginGateVisible" class="mx-4 mb-3 rounded-xl border border-copper/25 bg-copper/5 p-4 text-sm leading-6 text-ink/72 sm:mx-6" role="status">
              <p>{{ loginT('loginRequired') }}</p>
              <div class="mt-3 flex flex-wrap gap-4">
                <RouterLink :to="{ name: 'login', query: { redirect: '/discover' } }" class="inline-flex min-h-11 items-center font-semibold text-indigo underline underline-offset-4">{{ loginT('login') }}</RouterLink>
                <RouterLink :to="{ name: 'register', query: { redirect: '/discover' } }" class="inline-flex min-h-11 items-center font-semibold text-indigo underline underline-offset-4">{{ loginT('register') }}</RouterLink>
              </div>
            </div>
            <form class="flex items-end gap-2 border-t border-ink/8 p-4 sm:p-5" @submit.prevent="submitMessage">
              <div class="min-w-0 flex-1">
                <label for="recommendation-agent-message" class="sr-only">{{ t('inputLabel') }}</label>
                <textarea id="recommendation-agent-message" ref="recommendationInput" v-model="draft" rows="2" :placeholder="t('inputPlaceholder')" class="min-h-14 w-full resize-none rounded-xl border border-ink/15 bg-canvas px-4 py-3 text-sm leading-6 outline-none focus:border-felt" />
              </div>
              <button type="submit" :disabled="loading || !draft.trim() || !sessionKnown || !serverSessionReady" class="min-h-12 rounded-xl bg-felt px-5 text-sm font-semibold text-white disabled:opacity-40">{{ sessionKnown && serverSessionReady ? t('send') : t('checkingSession') }}</button>
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

    <Teleport to="body">
      <div v-if="selectedGame" v-show="openSurface === 'journey'" data-testid="player-journey-backdrop" class="fixed inset-0 z-[100] overflow-y-auto bg-ink/45 px-3 py-6 backdrop-blur-[2px] sm:px-6" @click.self="openSurface = 'none'">
        <div ref="journeyDialog" tabindex="-1" class="mx-auto w-full max-w-5xl outline-none" role="dialog" aria-modal="true" :aria-label="t('journeyDialog')">
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
            @remove="removeCurrentJourney"
          />
        </div>
      </div>
    </Teleport>

    <Teleport to="body">
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
    </Teleport>
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
