<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import type { RecommendationGame, RecommendationProfile } from '@/components/gameRecommendationTypes'
import PlayerWorkStatusText from '@/components/PlayerWorkStatusText.vue'
import RulebookIdentityConfirmation from '@/components/documents/RulebookIdentityConfirmation.vue'
import type {
  RulebookCandidate,
  RulebookCapabilityEvidence,
  RulebookDiscoveryIdentity,
  RulebookDiscoverySummary,
  RulebookSourceAction,
  RulebookSourceCapability,
} from '@/components/documents/types'
import { notifyLoginRequired } from '@/lib/authSession'
import { notifyBackgroundWorkChanged } from '@/lib/backgroundWorkRefresh'
import {
  mergeDocumentProgress,
  parseDocumentProgressSnapshot,
} from '@/lib/documentProgress'
import { acceptProgressiveLesson, teachingRunIsActive } from '@/lib/liveLesson'
import { useLocale } from '@/lib/locale'
import { playerFacingLanguageName } from '@/lib/playerFacingLanguage'
import { playerWorkStatus, type PlayerWorkStage } from '@/lib/playerWorkStatus'
import {
  monotonicElapsedSeconds,
  normalizeRulebookDiscoverySummary,
} from '@/lib/rulebookDiscovery'
import { notifyTeachingLaunched } from '@/lib/teachingLaunch'
import {
  acceptImportJob,
  acceptJourneyRun,
  derivePlayerJourney,
  playerJourneyPollDelay,
  type OfficialImportFailureKind,
  type OfficialImportRecovery,
  type PlayerJourneyDocumentProgress,
  type PlayerJourneyImportJob,
  type PlayerJourneyLesson,
  type PlayerJourneyPlan,
  type PlayerJourneyProjection,
  type PlayerJourneyRun,
} from '@/lib/playerJourney'
import {
  recentTeachingActivitySteps,
  recentTeachingPreparationActivitySteps,
  teachingActivityText,
  type TeachingActivity,
} from '@/lib/teachingProgress'

interface ImportedGame {
  game: { id: string; name: string }
  edition: { id: string; name: string; language: string }
  alreadyImported: boolean
}

interface RulebookCandidateResponse {
  configured: boolean
  identity: RulebookDiscoveryIdentity
  candidates: RulebookCandidate[]
  discovery?: unknown
}

interface OfficialImportJob extends PlayerJourneyImportJob {
  title?: string
  sourceDomain?: string
  duplicate: boolean
}

interface IllustratedLesson extends PlayerJourneyLesson {
  teachingPlanId: string
  sections: Array<{
    position: number
    topicKey: string
    coverageTags: string[]
    title: string
    required: boolean
    evidenceStatus: 'SUPPORTED' | 'CITED_DRAFT' | 'INSUFFICIENT_EVIDENCE'
    visualKind: 'REFERENCE_CARD' | 'TABLE_LAYOUT' | 'FLOW_DIAGRAM' | 'SCOREBOARD'
    visualCaption: string
    visualSourcePages: number[]
    visualSourceChunkIds: string[]
    steps: Array<{
      position: number
      heading: string
      kind: string
      text: string
      sourcePages: number[]
      visualFocus: {
        pageNumber: number
        label: string
        visibleDescription?: string
        x: number
        y: number
        width: number
        height: number
      } | null
    }>
  }>
}

interface CsrfResponse { headerName: string; token: string }
interface LaunchResponse { assistantRunId: string; state: string; reused: boolean }
interface RulebookIdentityProblem { code?: string }

export interface RecommendationJourneyStatus {
  projection: PlayerJourneyProjection
  game: RecommendationGame
  imported: ImportedGame | null
  importJob: OfficialImportJob | null
  plan: PlayerJourneyPlan | null
  lesson: IllustratedLesson | null
}

const props = defineProps<{ game: RecommendationGame; profile: RecommendationProfile }>()
const emit = defineEmits<{
  close: []
  change: []
  status: [value: RecommendationJourneyStatus]
  'open-rulebook': [value: RecommendationJourneyStatus]
  'open-lesson': [value: RecommendationJourneyStatus]
  'ask-questions': [value: RecommendationJourneyStatus]
}>()
const { locale } = useLocale()

const copy = computed(() => locale.value === 'zh-CN' ? {
  eyebrow: '从推荐到答疑', title: `已选《${props.game.name}》`, preparing: '正在加入“我的桌游”并寻找可审阅的规则书…',
  finding: '桌游已保存，正在查找出版社、BGG、集石和可信规则库（已等待 {seconds} 秒，通常几秒，偶尔约 30 秒）…', found: '选择并核对来源', detail: '优先展示出版社来源，也会保留社区与可信规则库结果。请核对语言和版本；只有已核验的 PDF 或连续规则页可以导入。',
  noImportableTitle: '暂未找到可直接导入的规则书', noImportableDetail: '当前结果只能继续查找文件或核对桌游信息；也可以改用公开链接或本地上传。',
  identityOnlyTitle: '仅用于核对桌游身份', identityOnlyDetail: '这些页面没有可导入的规则书文件，不属于规则书选择。',
  sources: { PUBLISHER: '出版社 / 权利方来源', TRUSTED_REPOSITORY: '可信规则库', COMMUNITY_PLATFORM: '社区规则书来源（如 BGG / 集石）', PUBLIC_WEB: '公开来源（请重点核对）' },
  capabilities: { DIRECT_DOCUMENT: '已核验为可下载文档', CONTIGUOUS_RULE_PAGES: '已核验为连续规则页', DOCUMENT_LISTING: '仅确认是文档列表页', GAME_INFO_ONLY: '仅有桌游信息，没有规则书文件', UNVERIFIED_PAGE: '尚未核验出可导入文档' },
  direct: 'PDF 可直接核验并下载', gallery: '连续规则页图片，可合成为 PDF', page: '来源页，需要继续查找文件', publisher: '发布者', language: '语言', languageVerified: '来源已明确标注', languageReview: '需在来源页核对', edition: '版本', unknown: '未标明', choose: '选择这份', selected: '已选择', continueListing: '继续查找文件', reviewUnverified: '审阅来源页',
  consent: '我确认该链接来自有权提供这份规则书的来源，并授权 RulePilot 下载用于我的个人讲解。',
  identityChanged: '提交前目录或来源身份发生了变化。请重新比较上面的游戏、版本和语言后再次确认。',
  identityActive: '这个链接正在为另一个版本导入。请等待那次导入结束，或改用公开链接 / 本地上传；当前桌游选择不会丢失。',
  import: '下载规则书并生成讲解', manual: '改用公开链接或本地上传',
  retryDiscovery: '继续查找',
  discoveryTerminal: {
    PARTIAL: '部分来源未在本次预算内完成；下面只保留已经核验的结果。',
    TIMED_OUT: '本次查找已到达最长等待时间，尚未找到可审阅结果。',
    FAILED: '部分来源没有完成查找，尚未找到可审阅结果。',
  },
  discoveryTiming: (elapsed: number, budget: number) => `本次查找用时 ${elapsed} 秒，最长等待 ${budget} 秒。`,
  discoveryProviders: { CATALOG: '规则书目录', SOURCE_INSPECTION: '来源核验', WEB_SEARCH: '联网搜索' },
  discoveryProviderStates: { FINISHED: '已完成', TIMED_OUT: '已超时', FAILED: '失败', SKIPPED: '未使用', UNAVAILABLE: '未配置' },
  browserRequired: '已经找到这份文件，但来源网站要求在浏览器里完成隐私选择、刷新临时链接或登录。打开原始下载页取得 PDF 后，回到 RulePilot 上传即可继续；桌游、版本和讲解偏好都已保留。',
  sourcePageHandoff: '这个结果不是可直接导入的规则书文档。请在来源网站继续查找或核对语言和版本，取得 PDF 后回到 RulePilot 上传；桌游和讲解偏好都已保留。',
  browserAction: '在来源网站继续下载',
  chooseAnotherSource: '重新选择来源', retryOriginalSource: '重试原来源',
  importFailureDetail: {
    NONE: '这次规则书导入已经结束。请选择另一个来源，或改用公开链接 / 本地文件。',
    TEMPORARY_SOURCE: '规则书来源暂时无法连接。你可以重试原来源，也可以立即换来源或上传本地文件。',
    BROWSER_HANDOFF: '来源网站需要你在浏览器里完成登录、隐私选择或下载。桌游与版本仍保留，可以继续下载或换来源。',
    INVALID_SOURCE: '下载的内容不是可安全导入的规则书文件。请选择真实 PDF、连续规则页或本地文件。',
    CAPACITY: '当前导入队列暂时已满。可以稍后重试原来源，或先改用本地文件。',
    INTERRUPTED: '应用重启中断了这次导入。可以重试原来源，也可以换来源或上传本地文件。',
    OTHER: '这次规则书导入没有完成。请选择另一个来源，或改用公开链接 / 本地文件。',
  } satisfies Record<OfficialImportFailureKind, string>,
  unavailable: '当前没有找到可审阅的规则书来源。你仍可粘贴公开 PDF 链接或上传自己的规则书。',
  login: '登录后即可保留这次选择并继续找规则书。', loginAction: '打开桌游详情并继续',
  error: '这一步暂时没有完成；推荐对话和已选桌游不会受影响。', partialFailure: '已生成的章节仍可阅读，但后台生成或核对没有完整结束。可以安全重试，现有内容不会丢失。', retry: '重试当前步骤', close: '关闭小窗', change: '换一款',
  safe: '可以关闭这个小窗继续聊天；下载、规则书处理和讲解生成会继续。',
  progress: '完整链路进度', current: '现在正在做', generationSteps: '讲解生成步骤', generationLatest: '最新实际进度', generationProcessHint: '后台会按下面的顺序推进；进入逐章生成后，这四步会对每一章重复。', planning: '规划中', pollingWarning: '暂时没有拿到最新进度，正在自动重试；已确认的进度不会倒退。',
  generationProcess: [
    '通读整本规则书，形成整局认识并规划章节',
    '读取每一章需要引用的规则书页面',
    '依据原文编写玩家可以直接照做的讲解',
    '校验引用、章节结构与数量边界，通过后逐章发布',
  ],
  generationFallback: {
    queued: '讲解准备任务已排队，等待后台开始通读规则书',
    readiness: '正在确认规则书页面已经可以用于讲解',
    planning: '正在通读整本规则书，形成整局认识并规划讲解章节',
    outlineReady: '讲解章节已经规划完成，正在载入章节目录',
    writingQueued: '章节目录已经完成，正文生成正在排队',
    writing: '逐章生成已经启动，正在等待第一条读取或编写进度',
    readable: '已有章节可以阅读，后台正在继续生成和校验其余章节',
    complete: '所有讲解章节已经生成并发布',
  },
  gameBound: '桌游已绑定', rulebook: '获取规则书', document: '读取规则书', lesson: '生成讲解', questions: '进入答疑',
  readLesson: '打开已生成的讲解', askQuestions: '切换为规则答疑', catalog: '我的桌游',
  readRulebook: '先阅读原规则书', rulebookReady: '规则书已经可以阅读；讲解会继续在后台生成。', rulebookAvailable: '原规则书已就绪，可随时与讲解对照阅读。',
  readable: '讲解已有可读内容；后台仍可能继续核对和补全。', complete: '讲解已经完整生成并通过后台收尾。',
  phase: {
    GAME_BINDING: '正在把推荐结果加入“我的桌游”', RULEBOOK_DISCOVERY: '正在寻找可审阅的规则书来源', SOURCE_REVIEW: '等待你核对规则书语言和版本',
    IMPORT_QUEUED: '规则书下载已排队', IMPORT_CONNECTING: '正在连接规则书来源', IMPORT_DOWNLOADING: '正在下载规则书', IMPORT_COMPRESSING: '文件较大，正在压缩 PDF', IMPORT_VERIFYING: '正在核验文件确实是可读取的 PDF', IMPORT_SAVING: '正在保存规则书并绑定到这款桌游',
    DOCUMENT_PROCESSING: '正在读取规则文字并准备原文页面', TEACHING_PREPARATION_QUEUED: '规则书已就绪，讲解准备任务正在排队', TEACHING_PREPARING: '正在通读规则书并组织讲解章节', LESSON_GENERATION_QUEUED: '讲解大纲已完成，正文生成正在排队', LESSON_GENERATING: '正在逐章生成、引用并核对讲解', LESSON_READABLE: '第一批讲解内容已经可以阅读', LESSON_COMPLETE: '完整讲解已经生成', FAILED: '当前步骤需要处理',
  },
  bytes: (done: string, total: string) => `${done} / ${total}`, pages: (done: number, total: number) => `第 ${done} / ${total} 页`, chapters: (done: number, total: number | null) => total ? `已有 ${done} / ${total} 章可读` : `已有 ${done} 章可读`,
} : {
  eyebrow: 'Recommendation to Q&A', title: `${props.game.name} selected`, preparing: 'Adding the game to My Games and finding reviewable rulebooks…',
  finding: 'Game saved. Searching publishers, BGG, Gstone, and trusted repositories ({seconds}s elapsed; usually a few seconds, occasionally about 30s)…', found: 'Choose and verify a source', detail: 'Publisher sources come first, with useful community and trusted-repository results preserved. Review language and edition; only verified PDFs or ordered rule pages can be imported.',
  noImportableTitle: 'No directly importable rulebook yet', noImportableDetail: 'The current results can only continue the file search or confirm game identity. You can also use a public URL or local upload.',
  identityOnlyTitle: 'Game identity references only', identityOnlyDetail: 'These pages do not contain an importable rulebook and are not rulebook choices.',
  sources: { PUBLISHER: 'Publisher / rights-holder', TRUSTED_REPOSITORY: 'Trusted rules repository', COMMUNITY_PLATFORM: 'Community rulebook source (such as BGG / Gstone)', PUBLIC_WEB: 'Public source (review carefully)' },
  capabilities: { DIRECT_DOCUMENT: 'Confirmed downloadable document', CONTIGUOUS_RULE_PAGES: 'Confirmed ordered rule pages', DOCUMENT_LISTING: 'Document listing only', GAME_INFO_ONLY: 'Game information only; no rulebook file', UNVERIFIED_PAGE: 'No importable document verified' },
  direct: 'Direct PDF ready for verification', gallery: 'Ordered rulebook pages; RulePilot can build the PDF', page: 'Source page; continue there', publisher: 'Provider', language: 'Language', languageVerified: 'stated by the source', languageReview: 'verify on the source page', edition: 'Edition', unknown: 'Not stated', choose: 'Choose this one', selected: 'Selected', continueListing: 'Continue finding a file', reviewUnverified: 'Review source page',
  consent: 'I confirm that this source may provide the rulebook and authorize RulePilot to download it for my personal guide.',
  identityChanged: 'The catalog or source identity changed before submission. Compare the game, edition, and language above, then reconfirm.',
  identityActive: 'This URL is already being imported for another edition. Wait for it to finish or use a public URL / local upload; the selected game remains intact.',
  import: 'Download and generate guide', manual: 'Use a public URL or local upload',
  retryDiscovery: 'Search again',
  discoveryTerminal: {
    PARTIAL: 'Some sources did not finish within this search budget. Only verified results are shown below.',
    TIMED_OUT: 'This search reached its time budget without a reviewable result.',
    FAILED: 'Some source checks failed and no reviewable result is available yet.',
  },
  discoveryTiming: (elapsed: number, budget: number) => `Search finished in ${elapsed}s with a ${budget}s maximum budget.`,
  discoveryProviders: { CATALOG: 'Rulebook catalog', SOURCE_INSPECTION: 'Source verification', WEB_SEARCH: 'Web search' },
  discoveryProviderStates: { FINISHED: 'finished', TIMED_OUT: 'timed out', FAILED: 'failed', SKIPPED: 'not needed', UNAVAILABLE: 'not configured' },
  browserRequired: 'The file was found, but its source requires an in-browser privacy choice, refreshed temporary link, or sign-in. Download it there, then return to upload it; the game, edition, and guide preferences are preserved.',
  sourcePageHandoff: 'This result is not a directly importable rulebook document. Continue the search or review language and edition on the source site, then return to upload the PDF; the game and guide preferences are preserved.',
  browserAction: 'Continue on the source site',
  chooseAnotherSource: 'Choose another source', retryOriginalSource: 'Retry original source',
  importFailureDetail: {
    NONE: 'This rulebook import has ended. Choose another source or use a public link / local file.',
    TEMPORARY_SOURCE: 'The rulebook source is temporarily unavailable. Retry it, choose another source, or upload a local file.',
    BROWSER_HANDOFF: 'The source requires an in-browser sign-in, privacy choice, or download. Your game and edition remain selected.',
    INVALID_SOURCE: 'The downloaded content is not a safely importable rulebook. Choose a real PDF, ordered rule pages, or a local file.',
    CAPACITY: 'The import queue is temporarily full. Retry later or use a local file now.',
    INTERRUPTED: 'An application restart interrupted this import. Retry it, choose another source, or upload a local file.',
    OTHER: 'This rulebook import did not finish. Choose another source or use a public link / local file.',
  } satisfies Record<OfficialImportFailureKind, string>,
  unavailable: 'No reviewable rulebook source was found. You can still paste a public PDF URL or upload your own rulebook.',
  login: 'Sign in to keep this selection and continue to its rulebook.', loginAction: 'Open game details and continue',
  error: 'This step did not complete. The conversation and selected game are unaffected.', partialFailure: 'Published chapters remain readable, but background generation or review did not finish. You can retry safely without losing existing content.', retry: 'Retry this step', close: 'Close', change: 'Choose another game',
  safe: 'You may close this panel and keep chatting. Download, rulebook processing, and guide generation will continue.',
  progress: 'End-to-end progress', current: 'Working on', generationSteps: 'Guide generation steps', generationLatest: 'Latest actual progress', generationProcessHint: 'The background task follows this order. Once chapter writing starts, these four steps repeat for each chapter.', planning: 'Planning', pollingWarning: 'The latest update is temporarily unavailable. Retrying automatically without rolling back confirmed progress.',
  generationProcess: [
    'Read the whole rulebook, form a whole-game view, and plan the chapters',
    'Read the rulebook pages needed by each chapter',
    'Write player-actionable guidance directly from the source',
    'Check citations, chapter structure, and quantities, then publish each chapter',
  ],
  generationFallback: {
    queued: 'Guide preparation is queued and waiting to start reading the rulebook',
    readiness: 'Confirming that the rulebook pages are ready for guide generation',
    planning: 'Reading the whole rulebook to form a whole-game view and plan the chapters',
    outlineReady: 'The chapter plan is ready and its directory is being loaded',
    writingQueued: 'The chapter directory is ready and chapter writing is queued',
    writing: 'Chapter generation has started; waiting for the first page-reading or writing update',
    readable: 'Some chapters are readable while the remaining chapters continue through writing and checks',
    complete: 'All guide chapters have been generated and published',
  },
  gameBound: 'Game linked', rulebook: 'Get rulebook', document: 'Read rules', lesson: 'Generate guide', questions: 'Start Q&A',
  readLesson: 'Open the generated guide', askQuestions: 'Switch to rules Q&A', catalog: 'My Games',
  readRulebook: 'Read the original rulebook now', rulebookReady: 'The rulebook is readable now while the guide continues in the background.', rulebookAvailable: 'The original rulebook is ready to compare with the guide at any time.',
  readable: 'Readable guide content is available while background review may continue.', complete: 'The complete guide is generated and background finishing is done.',
  phase: {
    GAME_BINDING: 'Adding the recommendation to My Games', RULEBOOK_DISCOVERY: 'Finding reviewable rulebook sources', SOURCE_REVIEW: 'Waiting for your language and edition review',
    IMPORT_QUEUED: 'Rulebook download is queued', IMPORT_CONNECTING: 'Connecting to the rulebook source', IMPORT_DOWNLOADING: 'Downloading the rulebook', IMPORT_COMPRESSING: 'Compressing the oversized PDF', IMPORT_VERIFYING: 'Verifying that the file is a readable PDF', IMPORT_SAVING: 'Saving and linking the rulebook to this game',
    DOCUMENT_PROCESSING: 'Reading the rules and preparing the original pages', TEACHING_PREPARATION_QUEUED: 'The rulebook is ready and guide preparation is queued', TEACHING_PREPARING: 'Reading the rules and organizing guide chapters', LESSON_GENERATION_QUEUED: 'The outline is ready and chapter generation is queued', LESSON_GENERATING: 'Generating, citing, and reviewing the guide chapter by chapter', LESSON_READABLE: 'The first guide content is ready to read', LESSON_COMPLETE: 'The complete guide is ready', FAILED: 'This step needs attention',
  },
  bytes: (done: string, total: string) => `${done} / ${total}`, pages: (done: number, total: number) => `Page ${done} / ${total}`, chapters: (done: number, total: number | null) => total ? `${done} / ${total} chapters readable` : `${done} chapters readable`,
})

const imported = ref<ImportedGame | null>(null)
const candidates = ref<RulebookCandidate[]>([])
const discoveryIdentity = ref<RulebookDiscoveryIdentity | null>(null)
const discoverySummary = ref<RulebookDiscoverySummary | null>(null)
const selected = ref<RulebookCandidate | null>(null)
const openedSource = ref<RulebookCandidate | null>(null)
const consent = ref(false)
const identityConfirmed = ref(false)
const identityNotice = ref('')
const state = ref<'preparing' | 'finding' | 'review' | 'unavailable' | 'login' | 'error' | 'browser-required' | 'journey'>('preparing')
const findingSeconds = ref(0)
const importJob = ref<OfficialImportJob | null>(null)
const documentProgress = ref<PlayerJourneyDocumentProgress | null>(null)
const preparationRun = ref<PlayerJourneyRun | null>(null)
const preparationRunId = ref<string | null>(null)
const plan = ref<PlayerJourneyPlan | null>(null)
const teachingRun = ref<PlayerJourneyRun | null>(null)
const teachingRunId = ref<string | null>(null)
const lesson = ref<IllustratedLesson | null>(null)
const pollingWarning = ref(false)
const retrying = ref(false)
let csrf: CsrfResponse | null = null
let sequence = 0
let findingClock: ReturnType<typeof setInterval> | null = null
let findingStartedAt: number | null = null
let journeyTimer: ReturnType<typeof setTimeout> | null = null
let refreshingJourney = false
let documentProgressSource: EventSource | null = null
let documentProgressVersionId: string | null = null
let documentProgressStreamRetryAt = 0
let documentProgressStreamRetryAttempt = 0
let documentReadyRefreshPending = false
const ensuredLessonPlans = new Set<string>()

const hasImportableCandidate = computed(() => candidates.value.some(isImportableCandidate))
const sourceCandidates = computed(() => candidates.value.filter(candidate => candidate.capability !== 'GAME_INFO_ONLY'))
const identityCandidates = computed(() => candidates.value.filter(candidate => candidate.capability === 'GAME_INFO_ONLY'))
const canImport = computed(() => Boolean(
  selected.value
  && isImportableCandidate(selected.value)
  && consent.value
  && identityConfirmed.value
  && state.value === 'review',
))
const identityTarget = computed<RulebookDiscoveryIdentity | null>(() => imported.value ? {
  editionId: imported.value.edition.id,
  gameName: imported.value.game.name,
  editionName: imported.value.edition.name,
  language: imported.value.edition.language,
} : null)
const findingText = computed(() => copy.value.finding.replace('{seconds}', String(findingSeconds.value)))
const discoveryNotice = computed(() => {
  const summary = discoverySummary.value
  if (!summary || summary.completion === 'COMPLETE') return ''
  return copy.value.discoveryTerminal[summary.completion]
})
const discoveryTiming = computed(() => {
  const summary = discoverySummary.value
  if (!summary) return ''
  return copy.value.discoveryTiming(
    Math.max(1, Math.ceil(summary.elapsedMs / 1_000)),
    Math.max(1, Math.ceil(summary.totalBudgetMs / 1_000)),
  )
})
const manualRoute = computed(() => ({
  name: 'teach' as const,
  query: imported.value ? { editionId: imported.value.edition.id, onboarding: 'recommendation-agent' } : {},
}))
const projection = computed(() => derivePlayerJourney({
  gameBound: imported.value !== null,
  discovery: state.value === 'finding' ? 'loading'
    : state.value === 'review' ? 'review'
    : state.value === 'unavailable' ? 'unavailable'
    : state.value === 'error' ? 'failed'
    : 'idle',
  importJob: importJob.value,
  documentProgress: documentProgress.value,
  preparationRun: preparationRun.value,
  plan: plan.value,
  teachingRun: teachingRun.value,
  lesson: lesson.value,
}))
const currentPhaseDetail = computed(() => copy.value.phase[projection.value.phase])
const currentWorkStatus = computed(() => {
  const current = projection.value
  let stage: PlayerWorkStage
  if (current.phase === 'GAME_BINDING' || current.phase === 'RULEBOOK_DISCOVERY') stage = 'FINDING_RULEBOOK'
  else if (current.phase === 'SOURCE_REVIEW') stage = 'WAITING_FOR_PLAYER'
  else if (current.phase.startsWith('IMPORT_')) stage = 'ACQUIRING_RULEBOOK'
  else if (current.phase === 'DOCUMENT_PROCESSING') stage = 'READING_RULEBOOK'
  else if (current.phase === 'LESSON_READABLE') stage = 'GUIDE_READABLE'
  else if (current.phase === 'LESSON_COMPLETE') stage = 'GUIDE_COMPLETE'
  else if (current.phase === 'FAILED') stage = current.retryAction ? 'NEEDS_ACTION' : 'FAILED'
  else stage = 'ORGANIZING_GUIDE'

  const capability = current.canReadLesson ? 'guide' : current.canReadRulebook ? 'rulebook' : 'none'
  const readiness = current.phase === 'LESSON_COMPLETE'
    ? 'complete'
    : current.canReadLesson || current.canReadRulebook ? 'usable' : 'unavailable'
  const terminality = current.state === 'waiting'
    ? 'waiting'
    : current.state === 'active' || current.state === 'ready' && !current.retryAction ? 'active' : 'terminal'
  const outcome = current.retryAction
    ? 'needs-action'
    : current.state === 'failed' ? 'failed' : 'none'
  return playerWorkStatus(stage, { capability, readiness, terminality, outcome }, locale.value)
})
const sourceWorkStatus = computed(() => {
  if (state.value === 'error') {
    return playerWorkStatus('NEEDS_ACTION', {
      capability: 'none', readiness: 'unavailable', terminality: 'terminal', outcome: 'needs-action',
    }, locale.value)
  }
  if (state.value === 'review' || state.value === 'unavailable' || state.value === 'browser-required') {
    return playerWorkStatus('WAITING_FOR_PLAYER', {
      capability: 'none', readiness: 'unavailable', terminality: 'waiting', outcome: 'none',
    }, locale.value)
  }
  return playerWorkStatus('FINDING_RULEBOOK', {
    capability: 'none', readiness: 'unavailable', terminality: 'active', outcome: 'none',
  }, locale.value)
})
const importFailureDetail = computed(() => {
  if (importJob.value?.stage !== 'FAILED') return ''
  return copy.value.importFailureDetail[importJob.value.recovery?.failureKind ?? 'OTHER']
})
const journeyDetail = computed(() => {
  if (importJob.value?.stage === 'DOWNLOADING' && importJob.value.downloadedBytes > 0) {
    const done = formatBytes(importJob.value.downloadedBytes)
    return importJob.value.totalBytes
      ? copy.value.bytes(done, formatBytes(importJob.value.totalBytes))
      : done
  }
  if (projection.value.phase === 'DOCUMENT_PROCESSING' && documentProgress.value?.totalPages) {
    return copy.value.pages(documentProgress.value.processedPages, documentProgress.value.totalPages)
  }
  if (projection.value.canReadLesson) {
    return copy.value.chapters(projection.value.availableSections, projection.value.totalSections)
  }
  if (plan.value && teachingRun.value?.activities?.length) {
    const activities = teachingRun.value.activities
    const progressPlan = {
      sections: plan.value.sections.map(section => ({
        ...section,
        visualEvidenceRecommended: false,
      })),
    }
    return teachingActivityText(
      progressPlan,
      activities as unknown as TeachingActivity[],
      activities.at(-1) as unknown as TeachingActivity,
      locale.value,
    )
  }
  return ''
})
const journeyTeachingSteps = computed(() => {
  const preparationSteps = recentTeachingPreparationActivitySteps(
    (preparationRun.value?.activities ?? []) as unknown as TeachingActivity[],
    locale.value,
  ).map(step => ({ ...step, key: `preparation-${step.sequence}` }))
  if (!plan.value || !teachingRun.value?.activities?.length) return preparationSteps
  const progressPlan = {
    sections: plan.value.sections.map(section => ({
      ...section,
      visualEvidenceRecommended: false,
    })),
  }
  const chapterSteps = recentTeachingActivitySteps(
    progressPlan,
    teachingRun.value.activities as unknown as TeachingActivity[],
    locale.value,
  ).map(step => ({ ...step, key: `chapter-${step.sequence}` }))
  return [...preparationSteps, ...chapterSteps].slice(-6)
})
const teachingJourneyPhases = new Set([
  'TEACHING_PREPARATION_QUEUED', 'TEACHING_PREPARING', 'LESSON_GENERATION_QUEUED',
  'LESSON_GENERATING', 'LESSON_READABLE', 'LESSON_COMPLETE',
])
const showTeachingGenerationSteps = computed(() => teachingJourneyPhases.has(projection.value.phase))
const visibleJourneyTeachingSteps = computed(() => {
  if (journeyTeachingSteps.value.length) return journeyTeachingSteps.value
  const phase = projection.value.phase
  const preparationState = preparationRun.value?.run.state
  let text = copy.value.generationFallback.queued
  let outcome: TeachingActivity['outcome'] = 'RUNNING'
  if (phase === 'TEACHING_PREPARING') {
    text = preparationState === 'DOCUMENT_READINESS'
      ? copy.value.generationFallback.readiness
      : copy.value.generationFallback.planning
  } else if (phase === 'LESSON_GENERATION_QUEUED') {
    text = plan.value ? copy.value.generationFallback.writingQueued : copy.value.generationFallback.outlineReady
  } else if (phase === 'LESSON_GENERATING') {
    text = copy.value.generationFallback.writing
  } else if (phase === 'LESSON_READABLE') {
    text = copy.value.generationFallback.readable
  } else if (phase === 'LESSON_COMPLETE') {
    text = copy.value.generationFallback.complete
    outcome = 'SUCCEEDED'
  }
  return [{ key: `phase-${phase}`, sequence: 0, outcome, text }]
})
const teachingProgressIsIndeterminate = computed(() => [
  'TEACHING_PREPARATION_QUEUED', 'TEACHING_PREPARING',
].includes(projection.value.phase))
const journeyProgressLabel = computed(() => {
  if (teachingProgressIsIndeterminate.value) return copy.value.planning
  if (projection.value.phase.startsWith('LESSON_') && projection.value.totalSections) {
    return copy.value.chapters(projection.value.availableSections, projection.value.totalSections)
  }
  return `${projection.value.progress}%`
})
const journeyProgressValue = computed(() => {
  if (teachingProgressIsIndeterminate.value) return null
  if (projection.value.phase.startsWith('LESSON_') && projection.value.totalSections) {
    return Math.round(projection.value.availableSections / projection.value.totalSections * 100)
  }
  return projection.value.progress
})
const journeyStatus = computed<RecommendationJourneyStatus>(() => ({
  projection: projection.value,
  game: props.game,
  imported: imported.value,
  importJob: importJob.value,
  plan: plan.value,
  lesson: lesson.value,
}))
let lastEmittedStatus = ''

function emitJourneyStatus(value: RecommendationJourneyStatus) {
  const signature = JSON.stringify({
    bggId: value.game.bggId,
    phase: value.projection.phase,
    state: value.projection.state,
    progress: value.projection.progress,
    retryAction: value.projection.retryAction,
    canReadRulebook: value.projection.canReadRulebook,
    canReadLesson: value.projection.canReadLesson,
    canAskQuestions: value.projection.canAskQuestions,
    importJobId: value.importJob?.id ?? null,
    documentVersionId: value.importJob?.documentVersionId ?? null,
    planId: value.plan?.id ?? null,
    lessonId: value.lesson?.id ?? null,
    lessonStatus: value.lesson?.status ?? null,
    availableSections: value.lesson?.sections.length ?? 0,
  })
  if (signature === lastEmittedStatus) return
  lastEmittedStatus = signature
  emit('status', value)
}
const milestones = computed(() => {
  const values = [
    { label: copy.value.gameBound, done: imported.value !== null },
    { label: copy.value.rulebook, done: importJob.value?.stage === 'COMPLETED' && Boolean(importJob.value.documentVersionId) },
    { label: copy.value.document, done: projection.value.canReadRulebook },
    { label: copy.value.lesson, done: projection.value.canReadLesson },
    { label: copy.value.questions, done: projection.value.canAskQuestions },
  ]
  const activeIndex = values.findIndex(milestone => !milestone.done)
  return values.map((milestone, index) => ({ ...milestone, active: index === activeIndex }))
})

async function csrfToken() {
  if (csrf) return csrf
  const response = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (!response.ok) throw new Error('csrf unavailable')
  csrf = await response.json() as CsrfResponse
  return csrf
}

function requireLogin() {
  state.value = 'login'
  clearJourneyTimer()
  notifyLoginRequired()
}

async function checkedJson<T>(path: string, optional = false): Promise<T | null> {
  const response = await fetch(path, { credentials: 'include' })
  if (response.status === 401 || response.status === 403) {
    requireLogin()
    throw new Error('login required')
  }
  if (optional && response.status === 404) return null
  if (!response.ok) throw new Error(`request failed: ${path}`)
  return await response.json() as T
}

async function prepare() {
  const request = ++sequence
  resetJourneyState()
  state.value = 'preparing'
  try {
    const token = await csrfToken()
    const response = await fetch(`/api/v1/bgg/games/${props.game.bggId}/import`, {
      method: 'POST', credentials: 'include', headers: { [token.headerName]: token.token },
    })
    if (request !== sequence) return
    if (response.status === 401 || response.status === 403) return requireLogin()
    if (!response.ok) throw new Error('selection failed')
    imported.value = normalizeImportedGame(await response.json() as ImportedGame)
    persistJourney()
    await discover(request)
  } catch {
    if (request === sequence) state.value = 'error'
  }
}

async function discover(request = sequence) {
  if (!imported.value || request !== sequence) return
  state.value = 'finding'
  discoverySummary.value = null
  startFindingClock()
  try {
    const parameters = new URLSearchParams({ editionId: imported.value.edition.id, language: locale.value })
    const response = await fetch(`/api/v1/documents/rulebook-candidates?${parameters.toString()}`, { credentials: 'include' })
    if (request !== sequence) return
    if (response.status === 401 || response.status === 403) return requireLogin()
    if (!response.ok) throw new Error('discovery failed')
    const result = await response.json() as RulebookCandidateResponse
    if (result.identity?.editionId !== imported.value.edition.id) throw new Error('discovery identity mismatch')
    discoveryIdentity.value = result.identity
    candidates.value = result.candidates.map(normalizeRulebookCandidate)
    discoverySummary.value = normalizeRulebookDiscoverySummary(result.discovery)
    state.value = result.configured && candidates.value.length ? 'review' : 'unavailable'
    persistJourney()
  } catch {
    if (request === sequence) state.value = 'error'
  } finally {
    if (request === sequence) stopFindingClock(true)
  }
}

function startFindingClock() {
  stopFindingClock(false)
  findingSeconds.value = 0
  findingStartedAt = performance.now()
  findingClock = setInterval(updateFindingElapsed, 1_000)
}

function updateFindingElapsed() {
  if (findingStartedAt === null) return
  findingSeconds.value = monotonicElapsedSeconds(findingStartedAt)
}

function stopFindingClock(updateElapsed: boolean) {
  if (updateElapsed) updateFindingElapsed()
  if (findingClock !== null) clearInterval(findingClock)
  findingClock = null
  findingStartedAt = null
}

function choose(candidate: RulebookCandidate) {
  if (candidate.capability === 'DOCUMENT_LISTING' || candidate.capability === 'UNVERIFIED_PAGE') {
    openedSource.value = candidate
    window.open(candidate.url, '_blank', 'noopener,noreferrer')
    return
  }
  if (!isImportableCandidate(candidate)) return
  openedSource.value = null
  selected.value = candidate
  consent.value = false
  identityConfirmed.value = false
  identityNotice.value = ''
  persistJourney()
}

function isImportableCandidate(candidate: RulebookCandidate) {
  return candidate.capability === 'DIRECT_DOCUMENT' && candidate.acquisitionMode === 'DIRECT_PDF'
    || candidate.capability === 'CONTIGUOUS_RULE_PAGES' && candidate.acquisitionMode === 'IMAGE_GALLERY'
}

function candidateActionLabel(candidate: RulebookCandidate) {
  if (isImportableCandidate(candidate)) return selected.value?.url === candidate.url ? copy.value.selected : copy.value.choose
  return candidate.capability === 'DOCUMENT_LISTING' ? copy.value.continueListing : copy.value.reviewUnverified
}

function nextAction(capability: RulebookSourceCapability): RulebookSourceAction {
  if (capability === 'DIRECT_DOCUMENT') return 'IMPORT_DOCUMENT'
  if (capability === 'CONTIGUOUS_RULE_PAGES') return 'IMPORT_PAGE_SEQUENCE'
  if (capability === 'DOCUMENT_LISTING') return 'CONTINUE_ON_SOURCE'
  if (capability === 'GAME_INFO_ONLY') return 'USE_FOR_IDENTITY_ONLY'
  return 'REVIEW_OR_UPLOAD'
}

function normalizeRulebookCandidate(candidate: RulebookCandidate): RulebookCandidate {
  const allowedCapabilities: RulebookSourceCapability[] = [
    'DIRECT_DOCUMENT', 'CONTIGUOUS_RULE_PAGES', 'DOCUMENT_LISTING', 'GAME_INFO_ONLY', 'UNVERIFIED_PAGE',
  ]
  const requestedCapability = allowedCapabilities.includes(candidate.capability)
    ? candidate.capability
    : 'UNVERIFIED_PAGE'
  const consistent = requestedCapability === 'DIRECT_DOCUMENT'
    ? candidate.acquisitionMode === 'DIRECT_PDF'
    : requestedCapability === 'CONTIGUOUS_RULE_PAGES'
      ? candidate.acquisitionMode === 'IMAGE_GALLERY'
      : candidate.acquisitionMode === 'SOURCE_PAGE'
  const capability: RulebookSourceCapability = consistent ? requestedCapability : 'UNVERIFIED_PAGE'
  const evidence: RulebookCapabilityEvidence[] = Array.isArray(candidate.capabilityEvidence)
    && candidate.capabilityEvidence.length
    ? candidate.capabilityEvidence
    : ['CANDIDATE_ONLY']
  return {
    ...candidate,
    acquisitionMode: capability === 'DIRECT_DOCUMENT' ? 'DIRECT_PDF'
      : capability === 'CONTIGUOUS_RULE_PAGES' ? 'IMAGE_GALLERY' : 'SOURCE_PAGE',
    capability,
    capabilityEvidence: evidence,
    capabilityCheckedAt: typeof candidate.capabilityCheckedAt === 'string' ? candidate.capabilityCheckedAt : '',
    nextAction: nextAction(capability),
  }
}

function candidateLanguage(candidate: RulebookCandidate) {
  const name = playerFacingLanguageName(candidate.language, locale.value)
  if (!candidate.language) return name
  return `${name}（${candidate.languageVerified ? copy.value.languageVerified : copy.value.languageReview}）`
}

async function importAndTeach() {
  if (!canImport.value) return
  await enqueueImport()
}

async function enqueueImport() {
  if (!imported.value || !selected.value) return
  const request = sequence
  state.value = 'journey'
  retrying.value = true
  try {
    const token = await csrfToken()
    const candidate = selected.value
    const response = await fetch('/api/v1/documents/official-imports', {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json', [token.headerName]: token.token },
      body: JSON.stringify({
        editionId: imported.value.edition.id,
        title: candidate.title,
        sourceType: 'BASE_RULEBOOK',
        officialSourceUrl: candidate.url,
        rightsConfirmed: true,
        startTeaching: true,
        learningGoal: null,
        discoveredForEditionId: discoveryIdentity.value?.editionId ?? null,
        sourceEdition: candidate.edition || null,
        sourceLanguage: candidate.languageVerified ? candidate.language : null,
        sourceLanguageVerified: candidate.languageVerified === true,
        identityConfirmed: identityConfirmed.value,
      }),
    })
    if (request !== sequence) return
    if (response.status === 401 || response.status === 403) return requireLogin()
    if (response.status === 409) {
      const problem = await response.json().catch(() => ({})) as RulebookIdentityProblem
      identityConfirmed.value = false
      identityNotice.value = problem.code === 'RULEBOOK_ACTIVE_IMPORT_CONFLICT'
        ? copy.value.identityActive
        : copy.value.identityChanged
      state.value = 'review'
      return
    }
    if (!response.ok) throw new Error('import failed')
    const incoming = normalizeImportJob(await response.json() as OfficialImportJob)
    importJob.value = acceptImportJob(importJob.value?.id === incoming.id ? importJob.value : null, incoming) as OfficialImportJob
    preparationRunId.value = incoming.teachingPreparationRunId
    consent.value = true
    identityConfirmed.value = true
    identityNotice.value = ''
    pollingWarning.value = false
    persistJourney()
    notifyBackgroundWorkChanged()
    scheduleJourney(0)
  } catch {
    if (request === sequence) {
      pollingWarning.value = false
      state.value = importJob.value ? 'journey' : 'error'
    }
  } finally {
    retrying.value = false
  }
}

async function refreshJourney(request = sequence) {
  if (request !== sequence || refreshingJourney || !importJob.value || state.value === 'login') return
  refreshingJourney = true
  clearJourneyTimer()
  try {
    let currentJob = importJob.value
    if (!importHandoffSettled(currentJob)) {
      const incoming = await checkedJson<OfficialImportJob>(
        `/api/v1/documents/official-imports/${encodeURIComponent(currentJob.id)}`,
      )
      if (!incoming || request !== sequence) return
      currentJob = acceptImportJob(currentJob, normalizeImportJob(incoming)) as OfficialImportJob
      importJob.value = currentJob
      pollingWarning.value = false
      if (currentJob.stage === 'FAILED' && currentJob.recovery?.canOpenSourceInBrowser) {
        state.value = 'browser-required'
        persistJourney()
        return
      }
      if (!preparationRunId.value && currentJob.teachingPreparationRunId) {
        preparationRunId.value = currentJob.teachingPreparationRunId
      }
    }

    const versionId = currentJob.documentVersionId
    if (versionId && !documentProgress.value?.complete) {
      const progress = await checkedJson<PlayerJourneyDocumentProgress>(
        `/api/v1/document-versions/${encodeURIComponent(versionId)}/progress/snapshot`, true,
      )
      if (request !== sequence) return
      if (progress) {
        const checked = parseDocumentProgressSnapshot(progress)
        if (!checked) throw new Error('document progress response is invalid')
        documentProgress.value = mergeDocumentProgress(documentProgress.value ?? undefined, checked)
        if (!checked.complete) watchDocumentProgress(versionId, request)
      }
    }

    const activePreparationRunId = preparationRunId.value ?? currentJob.teachingPreparationRunId
    if (activePreparationRunId && (!preparationRun.value || !runTerminal(preparationRun.value.run.state))) {
      const incoming = await checkedJson<PlayerJourneyRun>(
        `/api/v1/assistant-runs/${encodeURIComponent(activePreparationRunId)}`,
      )
      if (request !== sequence) return
      if (incoming) preparationRun.value = acceptJourneyRun(preparationRun.value, incoming)
    }

    if (versionId && preparationRun.value?.run.state === 'COMPLETED' && !plan.value) {
      plan.value = await checkedJson<PlayerJourneyPlan>(
        `/api/v1/document-versions/${encodeURIComponent(versionId)}/teaching-plans/latest`, true,
      )
      if (request !== sequence) return
    }

    if (plan.value) {
      const targetPlanId = plan.value.id
      const [incomingRun, incomingLesson] = await Promise.all([
        teachingRunId.value
          ? checkedJson<PlayerJourneyRun>(`/api/v1/assistant-runs/${encodeURIComponent(teachingRunId.value)}`, true)
          : checkedJson<PlayerJourneyRun>(`/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(targetPlanId)}`, true),
        checkedJson<IllustratedLesson>(`/api/v1/teaching-plans/${encodeURIComponent(targetPlanId)}/illustrated-lessons/latest`, true),
      ])
      if (request !== sequence) return
      if (incomingRun) {
        teachingRun.value = acceptJourneyRun(teachingRun.value, incomingRun)
        teachingRunId.value = incomingRun.run.id
      }
      if (incomingLesson) {
        if (incomingLesson.teachingPlanId !== targetPlanId) throw new Error('lesson response identity mismatch')
        lesson.value = acceptProgressiveLesson(lesson.value, incomingLesson)
      }
      if (!incomingRun && preparationRun.value?.run.state === 'COMPLETED' && !ensuredLessonPlans.has(targetPlanId)) {
        ensuredLessonPlans.add(targetPlanId)
        try {
          await launchLesson(targetPlanId, false)
        } catch (error) {
          ensuredLessonPlans.delete(targetPlanId)
          throw error
        }
      }
    }
    pollingWarning.value = false
    persistJourney()
  } catch {
    if (request === sequence) pollingWarning.value = true
  } finally {
    refreshingJourney = false
    if (request === sequence) {
      const immediateReadyRefresh = documentReadyRefreshPending
      documentReadyRefreshPending = false
      scheduleJourney(immediateReadyRefresh ? 0 : playerJourneyPollDelay(
        pollingWarning.value,
        Boolean(plan.value)
          && !projection.value.canReadLesson
          && (!teachingRun.value || teachingRunIsActive(teachingRun.value.run.state)),
      ))
    }
  }
}

async function retryJourney() {
  if (retrying.value) return
  retrying.value = true
  pollingWarning.value = false
  try {
    const action = projection.value.retryAction
    if (action === 'BIND_GAME') return await prepare()
    if (action === 'DISCOVER_RULEBOOK') return await discover()
    if (action === 'IMPORT_RULEBOOK') return await retryOriginalImport()
    if (action === 'PREPARE_TEACHING') {
      const currentJob = importJob.value
      if (!currentJob?.documentVersionId) throw new Error('document version unavailable')
      const token = await csrfToken()
      const response = await fetch(
        `/api/v1/documents/official-imports/${encodeURIComponent(currentJob.id)}/teaching-retry`, {
        method: 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/json', [token.headerName]: token.token },
        body: JSON.stringify({ expectedPreparationRunId: currentJob.teachingPreparationRunId }),
      })
      if (!response.ok) throw new Error('teaching preparation retry failed')
      const retriedJob = normalizeImportJob(await response.json() as OfficialImportJob)
      if (retriedJob.id !== currentJob.id) throw new Error('teaching preparation retry identity changed')
      importJob.value = retriedJob
      preparationRunId.value = retriedJob.teachingPreparationRunId
      preparationRun.value = null
      teachingRun.value = null
      teachingRunId.value = null
      notifyBackgroundWorkChanged()
      scheduleJourney(0)
      return
    }
    if (action === 'GENERATE_LESSON' && plan.value) {
      ensuredLessonPlans.delete(plan.value.id)
      await launchLesson(plan.value.id, true)
      scheduleJourney(0)
      return
    }
    if (state.value === 'error') await (imported.value ? discover() : prepare())
  } catch {
    if (state.value !== 'login') pollingWarning.value = true
  } finally {
    retrying.value = false
    persistJourney()
  }
}

function clearImportDownstreamState() {
  clearJourneyTimer()
  closeDocumentProgress()
  documentProgressStreamRetryAt = 0
  documentProgressStreamRetryAttempt = 0
  documentReadyRefreshPending = false
  importJob.value = null
  documentProgress.value = null
  preparationRun.value = null
  preparationRunId.value = null
  plan.value = null
  teachingRun.value = null
  teachingRunId.value = null
  lesson.value = null
  pollingWarning.value = false
  ensuredLessonPlans.clear()
}

function reviewAnotherSource() {
  sequence += 1
  clearImportDownstreamState()
  selected.value = null
  openedSource.value = null
  consent.value = false
  identityConfirmed.value = false
  identityNotice.value = ''
  state.value = candidates.value.length ? 'review' : 'finding'
  persistJourney()
  if (!candidates.value.length) void discover(sequence)
}

async function retryOriginalImport() {
  const failedJob = importJob.value
  if (failedJob?.stage !== 'FAILED' || !failedJob.recovery?.canRetryOriginalSource) return
  const request = sequence
  const token = await csrfToken()
  const response = await fetch(
    `/api/v1/documents/official-imports/${encodeURIComponent(failedJob.id)}/retry`, {
      method: 'POST', credentials: 'include', headers: { [token.headerName]: token.token },
    },
  )
  if (request !== sequence) return
  if (response.status === 401 || response.status === 403) return requireLogin()
  if (!response.ok) throw new Error('official import retry failed')
  const retriedJob = normalizeImportJob(await response.json() as OfficialImportJob)
  if (!retriedJob.id || retriedJob.id === failedJob.id) {
    throw new Error('official import retry response is invalid')
  }
  clearImportDownstreamState()
  importJob.value = retriedJob
  preparationRunId.value = retriedJob.teachingPreparationRunId
  state.value = retriedJob.stage === 'FAILED' && retriedJob.recovery?.canOpenSourceInBrowser
    ? 'browser-required'
    : 'journey'
  persistJourney()
  notifyBackgroundWorkChanged()
  if (retriedJob.stage !== 'FAILED') scheduleJourney(0)
}

async function launchLesson(planId: string, clearFailedRun: boolean) {
  const token = await csrfToken()
  const response = await fetch(`/api/v1/teaching-plans/${encodeURIComponent(planId)}/illustrated-lessons`, {
    method: 'POST', credentials: 'include', headers: { [token.headerName]: token.token },
  })
  if (!response.ok) throw new Error('lesson launch failed')
  const launch = await response.json() as LaunchResponse
  teachingRunId.value = launch.assistantRunId
  notifyTeachingLaunched({ planId, runId: launch.assistantRunId, gameTitle: props.game.name })
  if (clearFailedRun) teachingRun.value = null
}

function scheduleJourney(delay: number) {
  clearJourneyTimer()
  if (!importJob.value || state.value === 'login') return
  const current = projection.value
  const generationStillRunning = Boolean(plan.value && !teachingRun.value)
    || teachingRunIsActive(teachingRun.value?.run.state)
  if (current.state === 'complete' || current.state === 'failed' || current.state === 'ready' && !generationStillRunning) return
  journeyTimer = setTimeout(() => { void refreshJourney() }, delay)
}

function watchDocumentProgress(versionId: string, request: number) {
  if (typeof EventSource === 'undefined'
    || Date.now() < documentProgressStreamRetryAt
    || documentProgressSource && documentProgressVersionId === versionId) return
  closeDocumentProgress()
  const source = new EventSource(
    `/api/v1/document-versions/${encodeURIComponent(versionId)}/progress`,
    { withCredentials: true },
  )
  documentProgressSource = source
  documentProgressVersionId = versionId
  source.addEventListener('progress', (event) => {
    if (!currentDocumentProgressSource(source, versionId, request)) return
    let incoming: ReturnType<typeof parseDocumentProgressSnapshot>
    try {
      incoming = parseDocumentProgressSnapshot(JSON.parse((event as MessageEvent<string>).data))
    } catch {
      handleDocumentProgressDisconnect(source, versionId, request)
      return
    }
    if (!incoming) {
      handleDocumentProgressDisconnect(source, versionId, request)
      return
    }
    documentProgressStreamRetryAttempt = 0
    documentProgressStreamRetryAt = 0
    documentProgress.value = mergeDocumentProgress(documentProgress.value ?? undefined, incoming)
    pollingWarning.value = false
    persistJourney()
    if (incoming.complete) {
      closeDocumentProgress()
      documentReadyRefreshPending = true
      notifyBackgroundWorkChanged()
      if (!refreshingJourney) scheduleJourney(0)
    }
  })
  source.onerror = () => handleDocumentProgressDisconnect(source, versionId, request)
}

function currentDocumentProgressSource(source: EventSource, versionId: string, request: number) {
  return request === sequence
    && state.value !== 'login'
    && documentProgressSource === source
    && documentProgressVersionId === versionId
    && importJob.value?.documentVersionId === versionId
}

function handleDocumentProgressDisconnect(source: EventSource, versionId: string, request: number) {
  if (!currentDocumentProgressSource(source, versionId, request)) return
  closeDocumentProgress()
  documentProgressStreamRetryAttempt = Math.min(documentProgressStreamRetryAttempt + 1, 4)
  documentProgressStreamRetryAt = Date.now()
    + [1_000, 2_000, 5_000, 10_000][documentProgressStreamRetryAttempt - 1]!
  scheduleJourney(0)
}

function closeDocumentProgress() {
  documentProgressSource?.close()
  documentProgressSource = null
  documentProgressVersionId = null
}

function clearJourneyTimer() {
  if (journeyTimer) clearTimeout(journeyTimer)
  journeyTimer = null
}

function resetJourneyState() {
  clearJourneyTimer()
  stopFindingClock(false)
  closeDocumentProgress()
  documentProgressStreamRetryAt = 0
  documentProgressStreamRetryAttempt = 0
  documentReadyRefreshPending = false
  imported.value = null
  candidates.value = []
  discoveryIdentity.value = null
  discoverySummary.value = null
  selected.value = null
  openedSource.value = null
  consent.value = false
  identityConfirmed.value = false
  identityNotice.value = ''
  importJob.value = null
  documentProgress.value = null
  preparationRun.value = null
  preparationRunId.value = null
  plan.value = null
  teachingRun.value = null
  teachingRunId.value = null
  lesson.value = null
  pollingWarning.value = false
  ensuredLessonPlans.clear()
}

function normalizeImportedGame(game: ImportedGame): ImportedGame {
  return {
    ...game,
    edition: {
      ...game.edition,
      language: typeof game.edition.language === 'string' && game.edition.language.trim()
        ? game.edition.language
        : 'und',
    },
  }
}

function normalizeImportJob(job: OfficialImportJob): OfficialImportJob {
  return {
    ...job,
    downloadedBytes: Number(job.downloadedBytes ?? 0),
    totalBytes: job.totalBytes === undefined ? null : job.totalBytes,
    teachingHandoffState: job.teachingHandoffState ?? 'NOT_REQUESTED',
    teachingPreparationRunId: job.teachingPreparationRunId ?? null,
    recovery: normalizeImportRecovery(job),
    duplicate: Boolean(job.duplicate),
  }
}

const officialImportFailureKinds = new Set<OfficialImportFailureKind>([
  'NONE', 'TEMPORARY_SOURCE', 'BROWSER_HANDOFF', 'INVALID_SOURCE',
  'CAPACITY', 'INTERRUPTED', 'OTHER',
])

function normalizeImportRecovery(job: OfficialImportJob): OfficialImportRecovery {
  if (job.stage === 'FAILED') {
    const recovery = job.recovery
    if (recovery?.state === 'FAILED') {
      const failureKind = officialImportFailureKinds.has(recovery.failureKind)
        ? recovery.failureKind
        : 'OTHER'
      return {
        state: 'FAILED', failureKind, busy: false,
        canChooseAnotherSource: recovery.canChooseAnotherSource === true,
        canUseLocalUpload: recovery.canUseLocalUpload === true,
        canRetryOriginalSource: recovery.canRetryOriginalSource === true,
        canOpenSourceInBrowser: recovery.canOpenSourceInBrowser === true,
      }
    }
    const browserHandoff = job.errorCode === 'SOURCE_BROWSER_REQUIRED'
    return {
      state: 'FAILED', failureKind: browserHandoff ? 'BROWSER_HANDOFF' : 'OTHER', busy: false,
      canChooseAnotherSource: true, canUseLocalUpload: true,
      canRetryOriginalSource: false, canOpenSourceInBrowser: browserHandoff,
    }
  }
  const settled = job.stage === 'COMPLETED'
    && ['LAUNCHED', 'FAILED', 'NOT_REQUESTED'].includes(job.teachingHandoffState ?? 'NOT_REQUESTED')
  return {
    state: settled ? 'SUCCEEDED' : 'RUNNING', failureKind: 'NONE', busy: !settled,
    canChooseAnotherSource: false, canUseLocalUpload: false,
    canRetryOriginalSource: false, canOpenSourceInBrowser: false,
  }
}

function importHandoffSettled(job: OfficialImportJob) {
  return job.stage === 'FAILED'
    || job.stage === 'COMPLETED' && ['LAUNCHED', 'FAILED', 'NOT_REQUESTED'].includes(job.teachingHandoffState)
}

function runTerminal(runState: string) {
  return ['COMPLETED', 'FAILED', 'DEGRADED', 'INSUFFICIENT_EVIDENCE'].includes(runState)
}

function formatBytes(value: number) {
  if (value < 1024 * 1024) return `${Math.max(1, Math.round(value / 1024))} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

function storageKey() {
  return `rulepilot:recommendation-journey:${props.game.bggId}`
}

function persistJourney() {
  try {
    sessionStorage.setItem(storageKey(), JSON.stringify({
      imported: imported.value,
      candidates: candidates.value,
      discoveryIdentity: discoveryIdentity.value,
      discoverySummary: discoverySummary.value,
      selected: selected.value,
      importJob: importJob.value,
      preparationRunId: preparationRunId.value,
      planId: plan.value?.id ?? null,
      teachingRunId: teachingRunId.value,
    }))
  } catch {
    // Server state remains authoritative when browser storage is unavailable.
  }
}

function restoreJourney() {
  try {
    const raw = sessionStorage.getItem(storageKey())
    if (!raw) return false
    const stored = JSON.parse(raw) as {
      imported?: ImportedGame
      candidates?: RulebookCandidate[]
      discoveryIdentity?: RulebookDiscoveryIdentity
      discoverySummary?: unknown
      selected?: RulebookCandidate
      importJob?: OfficialImportJob
      preparationRunId?: string
      teachingRunId?: string
    }
    if (!stored.imported) return false
    const restoredImported = normalizeImportedGame(stored.imported)
    imported.value = restoredImported
    discoveryIdentity.value = stored.discoveryIdentity?.editionId === restoredImported.edition.id
      ? stored.discoveryIdentity
      : null
    discoverySummary.value = normalizeRulebookDiscoverySummary(stored.discoverySummary)
    candidates.value = Array.isArray(stored.candidates)
      ? stored.candidates.map(normalizeRulebookCandidate)
      : []
    const restoredSelection = stored.selected ? normalizeRulebookCandidate(stored.selected) : null
    selected.value = restoredSelection && isImportableCandidate(restoredSelection) ? restoredSelection : null
    if (stored.importJob?.id) {
      importJob.value = normalizeImportJob(stored.importJob)
      preparationRunId.value = stored.preparationRunId ?? stored.importJob.teachingPreparationRunId
      teachingRunId.value = stored.teachingRunId ?? null
      consent.value = true
      identityConfirmed.value = true
      state.value = importJob.value.stage === 'FAILED' && importJob.value.recovery?.canOpenSourceInBrowser
        ? 'browser-required'
        : 'journey'
      if (state.value === 'journey') scheduleJourney(0)
    } else {
      state.value = candidates.value.length ? 'review' : 'finding'
      if (!candidates.value.length) void discover()
    }
    return true
  } catch {
    sessionStorage.removeItem(storageKey())
    return false
  }
}

function startForCurrentGame() {
  sequence += 1
  resetJourneyState()
  if (!restoreJourney()) void prepare()
}

watch(() => props.game.bggId, startForCurrentGame)
watch(journeyStatus, emitJourneyStatus, { immediate: true })
onMounted(startForCurrentGame)
onBeforeUnmount(() => {
  sequence += 1
  clearJourneyTimer()
  closeDocumentProgress()
  stopFindingClock(false)
})
</script>

<template>
  <aside data-testid="player-journey-surface" class="isolate overflow-hidden rounded-2xl border border-copper/25 text-ink shadow-2xl" style="background-color: var(--color-paper); opacity: 1" aria-live="polite">
    <div class="flex items-start gap-4 border-b border-copper/15 p-4 sm:p-5">
      <img v-if="game.thumbnailUrl" :src="game.thumbnailUrl" :alt="game.name" class="h-20 w-16 shrink-0 rounded-lg bg-paper object-contain" referrerpolicy="no-referrer">
      <div class="min-w-0 flex-1">
        <p class="text-xs font-bold uppercase tracking-[0.12em] text-copper">{{ copy.eyebrow }}</p>
        <h3 class="mt-1 font-display text-xl font-semibold">{{ copy.title }}</h3>
        <p v-if="game.nameLocalized" class="mt-1 text-xs text-ink/45">{{ game.originalName }}</p>
      </div>
      <button type="button" data-modal-initial-focus class="grid min-h-11 min-w-11 shrink-0 place-items-center rounded-lg text-2xl text-ink/45 hover:bg-ink/5" :aria-label="copy.close" @click="emit('close')">×</button>
    </div>

    <div class="p-4 sm:p-5">
      <PlayerWorkStatusText
        v-if="state !== 'journey' && state !== 'login'"
        :status="sourceWorkStatus"
        class="mb-2 text-sm font-semibold text-copper"
        role="status"
      />

      <p v-if="state === 'preparing' || state === 'finding'" class="flex items-center gap-3 text-sm text-ink/65">
        <span class="size-2 animate-pulse rounded-full bg-copper" aria-hidden="true" />
        {{ state === 'preparing' ? copy.preparing : findingText }}
      </p>

      <template v-else-if="state === 'review'">
        <h4 class="font-display text-lg font-semibold">{{ hasImportableCandidate ? copy.found : copy.noImportableTitle }}</h4>
        <p class="mt-1 text-xs leading-5 text-ink/50">{{ hasImportableCandidate ? copy.detail : copy.noImportableDetail }}</p>
        <div
          v-if="discoveryNotice && discoverySummary"
          data-testid="rulebook-discovery-summary"
          class="mt-3 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-xs leading-5 text-amber-950"
          role="status"
        >
          <p>{{ discoveryNotice }} {{ discoveryTiming }}</p>
          <ul class="mt-2 flex flex-wrap gap-x-4 gap-y-1">
            <li v-for="provider in discoverySummary.providers" :key="provider.provider">
              {{ copy.discoveryProviders[provider.provider] }}：{{ copy.discoveryProviderStates[provider.state] }}
            </li>
          </ul>
        </div>
        <ol class="mt-4 grid grid-cols-2 gap-2 text-xs sm:grid-cols-5" :aria-label="copy.progress">
          <li v-for="milestone in milestones" :key="milestone.label" :data-fact-confirmed="milestone.done ? 'true' : 'false'" class="rounded-lg border px-2.5 py-2" :class="milestone.done ? 'border-emerald-200 bg-emerald-50 text-emerald-800' : milestone.active ? 'border-copper/30 bg-copper/5 font-semibold text-copper' : 'border-ink/8 bg-paper text-ink/40'">
            <span class="mr-1" aria-hidden="true">{{ milestone.done ? '✓' : milestone.active ? '●' : '○' }}</span>{{ milestone.label }}
          </li>
        </ol>
        <ul class="mt-4 stack-y-md">
          <li v-for="candidate in sourceCandidates" :key="candidate.url" :data-capability="candidate.capability" class="rounded-xl border bg-paper p-4" :class="selected?.url === candidate.url ? 'border-copper/60 ring-2 ring-copper/10' : 'border-ink/10'">
            <div class="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
              <div class="min-w-0">
                <p class="font-semibold">{{ candidate.title }}</p>
                <a :href="candidate.url" target="_blank" rel="noopener noreferrer" class="mt-1 block break-all text-xs font-semibold text-indigo underline underline-offset-2">{{ candidate.sourceDomain }} ↗</a>
                <p class="mt-2 text-xs leading-5 text-ink/55">{{ copy.publisher }}：{{ candidate.publisher || copy.unknown }} · {{ copy.language }}：{{ candidateLanguage(candidate) }} · {{ copy.edition }}：{{ candidate.edition || copy.unknown }}</p>
                <p class="mt-1 text-xs font-semibold" :class="candidate.sourceType === 'PUBLIC_WEB' ? 'text-amber-700' : 'text-emerald-700'">{{ copy.sources[candidate.sourceType] }}</p>
                <p class="mt-1 text-xs text-ink/45">{{ candidate.acquisitionMode === 'DIRECT_PDF' ? copy.direct : candidate.acquisitionMode === 'IMAGE_GALLERY' ? copy.gallery : copy.page }}</p>
                <p class="mt-1 text-xs font-semibold text-indigo">{{ copy.capabilities[candidate.capability] }}</p>
              </div>
              <button v-if="candidate.capability !== 'GAME_INFO_ONLY'" type="button" class="min-h-11 shrink-0 rounded-lg border border-copper/35 px-4 text-sm font-semibold text-copper" :aria-pressed="isImportableCandidate(candidate) ? selected?.url === candidate.url : undefined" @click="choose(candidate)">{{ candidateActionLabel(candidate) }}</button>
            </div>
          </li>
        </ul>
        <div v-if="!hasImportableCandidate" class="mt-4 flex flex-wrap gap-x-4 gap-y-2">
          <button type="button" class="inline-flex min-h-11 items-center text-sm font-semibold text-indigo underline" @click="discover()">{{ copy.retryDiscovery }} →</button>
          <RouterLink :to="manualRoute" class="inline-flex min-h-11 items-center text-sm font-semibold text-indigo underline">{{ copy.manual }} →</RouterLink>
        </div>
        <section v-if="identityCandidates.length" class="mt-5 border-t border-ink/10 pt-4" :aria-label="copy.identityOnlyTitle">
          <h5 class="text-sm font-semibold text-ink/70">{{ copy.identityOnlyTitle }}</h5>
          <p class="mt-1 text-xs leading-5 text-ink/50">{{ copy.identityOnlyDetail }}</p>
          <ul class="mt-3 stack-y-sm">
            <li v-for="candidate in identityCandidates" :key="candidate.url" :data-capability="candidate.capability" class="rounded-lg border border-ink/10 bg-paper p-3 text-xs">
              <p class="font-semibold text-ink/70">{{ candidate.title }}</p>
              <a :href="candidate.url" target="_blank" rel="noopener noreferrer" class="mt-1 block break-all font-semibold text-indigo underline underline-offset-2">{{ candidate.sourceDomain }} ↗</a>
              <p class="mt-1 text-ink/50">{{ copy.capabilities[candidate.capability] }}</p>
            </li>
          </ul>
        </section>
        <div v-if="openedSource" class="mt-4 rounded-xl border border-indigo/15 bg-indigo/5 p-4 text-sm leading-6 text-ink/65" role="status">
          <p>{{ copy.sourcePageHandoff }}</p>
          <a :href="openedSource.url" target="_blank" rel="noopener noreferrer" class="mt-3 inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.browserAction }} ↗</a>
          <RouterLink :to="manualRoute" class="ml-4 inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.manual }} →</RouterLink>
        </div>
        <div v-if="selected" class="mt-4 rounded-xl border border-indigo/15 bg-indigo/5 p-4">
          <RulebookIdentityConfirmation
            v-if="identityTarget"
            v-model="identityConfirmed"
            :target="identityTarget"
            :source-context="discoveryIdentity"
            :source="{
              edition: selected.edition,
              language: selected.language,
              languageVerified: selected.languageVerified === true,
            }"
            :disabled="retrying"
          />
          <p v-if="identityNotice" class="mt-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-950" role="alert">{{ identityNotice }}</p>
          <label class="mt-3 flex items-start gap-3 text-sm leading-6 text-ink/65">
            <input v-model="consent" type="checkbox" class="mt-1 size-5 shrink-0 accent-indigo">
            <span>{{ copy.consent }}</span>
          </label>
          <button type="button" :disabled="!canImport" class="mt-3 min-h-11 rounded-lg bg-indigo px-5 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-40" @click="importAndTeach">{{ copy.import }}</button>
        </div>
      </template>

      <div v-else-if="state === 'journey'" data-testid="player-journey-progress">
        <div class="flex items-start justify-between gap-4">
          <div>
            <p class="text-xs font-bold uppercase tracking-[0.12em] text-copper">{{ copy.current }}</p>
            <PlayerWorkStatusText
              :status="currentWorkStatus"
              class="mt-1 text-sm font-semibold text-ink"
              role="status"
            />
            <p class="mt-1 text-xs leading-5 text-ink/55">{{ currentPhaseDetail }}</p>
            <p v-if="journeyDetail" class="mt-1 text-xs leading-5 text-ink/50">{{ journeyDetail }}</p>
          </div>
          <span class="text-right text-sm font-semibold text-copper" :class="journeyProgressValue === null ? '' : 'font-mono'">{{ journeyProgressLabel }}</span>
        </div>
        <div v-if="journeyProgressValue !== null" class="mt-3 h-2 overflow-hidden rounded-full bg-copper/10" role="progressbar" :aria-label="copy.progress" aria-valuemin="0" aria-valuemax="100" :aria-valuenow="journeyProgressValue">
          <div class="h-full rounded-full bg-copper transition-[width] duration-500" :style="{ width: `${journeyProgressValue}%` }" />
        </div>
        <ol class="mt-4 grid grid-cols-2 gap-2 text-xs sm:grid-cols-5" :aria-label="copy.progress">
          <li v-for="milestone in milestones" :key="milestone.label" :data-fact-confirmed="milestone.done ? 'true' : 'false'" class="rounded-lg border px-2.5 py-2" :class="milestone.done ? 'border-emerald-200 bg-emerald-50 text-emerald-800' : milestone.active ? 'border-copper/30 bg-copper/5 font-semibold text-copper' : 'border-ink/8 bg-paper text-ink/40'">
            <span class="mr-1" aria-hidden="true">{{ milestone.done ? '✓' : milestone.active ? '●' : '○' }}</span>{{ milestone.label }}
          </li>
        </ol>
        <section
          v-if="showTeachingGenerationSteps"
          data-testid="recommendation-teaching-generation-steps"
          class="mt-4 rounded-xl border border-copper/15 bg-copper/5 px-4 py-3"
          :aria-label="copy.generationSteps"
          aria-live="polite"
        >
          <p class="text-xs font-bold uppercase tracking-[0.1em] text-copper">{{ copy.generationSteps }}</p>
          <p class="mt-1 text-xs leading-5 text-ink/50">{{ copy.generationProcessHint }}</p>
          <ol class="mt-3 grid gap-2 sm:grid-cols-2">
            <li
              v-for="(step, index) in copy.generationProcess"
              :key="step"
              class="flex items-start gap-2 rounded-lg border border-copper/10 bg-paper/70 px-3 py-2 text-xs leading-5 text-ink/65"
            >
              <span class="grid size-5 shrink-0 place-items-center rounded-full bg-copper/10 font-mono text-[10px] font-bold text-copper" aria-hidden="true">{{ index + 1 }}</span>
              <span>{{ step }}</span>
            </li>
          </ol>
          <p class="mt-3 text-[11px] font-bold uppercase tracking-[0.08em] text-ink/45">{{ copy.generationLatest }}</p>
          <ol class="mt-2 grid gap-2 sm:grid-cols-2">
            <li
              v-for="step in visibleJourneyTeachingSteps"
              :key="step.key"
              class="flex items-start gap-2 text-xs leading-5 text-ink/65"
            >
              <span
                class="mt-0.5 grid size-4 shrink-0 place-items-center rounded-full text-[10px] font-bold text-white"
                :class="step.outcome === 'RUNNING' ? 'animate-pulse bg-copper' : step.outcome === 'SUCCEEDED' ? 'bg-emerald-600' : 'bg-amber-600'"
                aria-hidden="true"
              >{{ step.outcome === 'SUCCEEDED' ? '✓' : step.outcome === 'RUNNING' ? '●' : '!' }}</span>
              <span>{{ step.text }}</span>
            </li>
          </ol>
        </section>
        <p class="mt-4 rounded-xl border border-indigo/10 bg-indigo/5 px-4 py-3 text-xs leading-5 text-ink/60">{{ copy.safe }}</p>
        <p v-if="pollingWarning" class="mt-3 rounded-lg bg-amber-50 px-4 py-3 text-xs leading-5 text-amber-900" role="status">{{ copy.pollingWarning }}</p>
        <div v-if="projection.canReadRulebook" class="mt-4 rounded-xl border border-indigo/15 bg-indigo/5 p-4 text-sm leading-6 text-ink/65">
          <p>{{ projection.canReadLesson ? copy.rulebookAvailable : copy.rulebookReady }}</p>
          <button type="button" class="mt-3 min-h-11 rounded-lg border border-indigo/25 px-4 font-semibold text-indigo" @click="emit('open-rulebook', journeyStatus)">{{ copy.readRulebook }}</button>
        </div>
        <div v-if="projection.state === 'failed' || projection.canReadLesson && projection.retryAction" class="mt-4 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-800" role="alert">
          <template v-if="importJob?.stage === 'FAILED'">
            <p class="leading-6">{{ importFailureDetail }}</p>
            <div class="mt-3 flex flex-wrap gap-3">
              <button v-if="importJob.recovery?.canChooseAnotherSource !== false" type="button" class="min-h-11 rounded-lg bg-indigo px-4 font-semibold text-white" @click="reviewAnotherSource">{{ copy.chooseAnotherSource }}</button>
              <RouterLink v-if="importJob.recovery?.canUseLocalUpload !== false" :to="manualRoute" class="inline-flex min-h-11 items-center rounded-lg border border-indigo/25 px-4 font-semibold text-indigo underline" @click="reviewAnotherSource">{{ copy.manual }} →</RouterLink>
              <a v-if="importJob.recovery?.canOpenSourceInBrowser && selected" :href="selected.url" target="_blank" rel="noopener noreferrer" class="inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.browserAction }} ↗</a>
              <button v-if="importJob.recovery?.canRetryOriginalSource" type="button" :disabled="retrying" class="min-h-11 rounded-lg border border-red-300 px-4 font-semibold disabled:opacity-40" @click="retryJourney">{{ copy.retryOriginalSource }}</button>
            </div>
          </template>
          <template v-else>
            <p>{{ projection.canReadLesson ? copy.partialFailure : copy.error }}</p>
            <button v-if="projection.retryAction" type="button" :disabled="retrying" class="mt-2 min-h-11 font-semibold underline disabled:opacity-40" @click="retryJourney">{{ copy.retry }}</button>
          </template>
        </div>
        <div v-if="projection.canReadLesson" class="mt-4 rounded-xl border border-emerald-200 bg-emerald-50 p-4 text-sm leading-6 text-emerald-900">
          <p>{{ projection.state === 'complete' ? copy.complete : copy.readable }}</p>
          <div class="mt-3 flex flex-wrap gap-3">
            <button type="button" class="min-h-11 rounded-lg bg-indigo px-4 font-semibold text-white" @click="emit('open-lesson', journeyStatus)">{{ copy.readLesson }}</button>
            <button type="button" class="min-h-11 rounded-lg border border-indigo/25 px-4 font-semibold text-indigo" @click="emit('ask-questions', journeyStatus)">{{ copy.askQuestions }}</button>
          </div>
        </div>
        <div class="mt-4 flex flex-wrap gap-x-5 gap-y-2">
          <RouterLink to="/catalog" class="inline-flex min-h-11 items-center text-sm font-semibold text-indigo underline">{{ copy.catalog }} →</RouterLink>
          <button type="button" class="min-h-11 text-sm font-semibold text-ink/50 underline" @click="emit('change')">{{ copy.change }}</button>
        </div>
      </div>

      <div v-else-if="state === 'login'" class="text-sm leading-6 text-ink/65" role="status">
        <p>{{ copy.login }}</p>
        <RouterLink :to="{ name: 'game-discovery', params: { bggId: game.bggId } }" class="mt-3 inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.loginAction }} →</RouterLink>
      </div>

      <div v-else-if="state === 'unavailable'" class="text-sm leading-6 text-ink/65" role="status">
        <p>{{ copy.unavailable }}</p>
        <div
          v-if="discoveryNotice && discoverySummary"
          data-testid="rulebook-discovery-summary"
          class="mt-3 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-xs leading-5 text-amber-950"
        >
          <p>{{ discoveryNotice }} {{ discoveryTiming }}</p>
          <ul class="mt-2 flex flex-wrap gap-x-4 gap-y-1">
            <li v-for="provider in discoverySummary.providers" :key="provider.provider">
              {{ copy.discoveryProviders[provider.provider] }}：{{ copy.discoveryProviderStates[provider.state] }}
            </li>
          </ul>
        </div>
        <button type="button" class="mt-3 inline-flex min-h-11 items-center font-semibold text-indigo underline" @click="discover()">{{ copy.retryDiscovery }} →</button>
        <RouterLink :to="manualRoute" class="ml-4 inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.manual }} →</RouterLink>
      </div>

      <div v-else-if="state === 'browser-required'" class="text-sm leading-6 text-ink/65" role="status">
        <p>{{ copy.browserRequired }}</p>
        <a v-if="selected" :href="selected.url" target="_blank" rel="noopener noreferrer" class="mt-3 inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.browserAction }} ↗</a>
        <RouterLink :to="manualRoute" class="ml-4 inline-flex min-h-11 items-center font-semibold text-indigo underline" @click="reviewAnotherSource">{{ copy.manual }} →</RouterLink>
        <button type="button" class="ml-4 inline-flex min-h-11 items-center font-semibold text-indigo underline" @click="reviewAnotherSource">{{ copy.chooseAnotherSource }}</button>
      </div>

      <div v-else class="text-sm text-danger" role="alert">
        <p>{{ copy.error }}</p>
        <button type="button" class="mt-2 min-h-11 font-semibold underline" @click="retryJourney">{{ copy.retry }}</button>
        <RouterLink v-if="imported" :to="manualRoute" class="ml-4 inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.manual }}</RouterLink>
      </div>
    </div>
  </aside>
</template>
