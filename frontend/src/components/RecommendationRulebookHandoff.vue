<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import type { RecommendationGame, RecommendationProfile } from '@/components/gameRecommendationTypes'
import PlayerFailureDetails from '@/components/PlayerFailureDetails.vue'
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
import {
  acceptProgressiveLesson,
  teachingLessonNeedsFinalSnapshot,
  teachingRunIsActive,
} from '@/lib/liveLesson'
import { useLocale } from '@/lib/locale'
import { teachingFailureOwner, type PlayerFailureDescriptor } from '@/lib/playerFailureSemantics'
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
  playerJourneyRunIsTerminal,
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
  summarizeTeachingVisualPageRuleGroups,
  teachingActivityText,
  type TeachingActivityOutcome,
  type TeachingVisualPageRuleGroupState,
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
  editionId?: string
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
      visualFoci?: Array<{
        pageNumber: number
        label: string
        visibleDescription?: string
        x: number
        y: number
        width: number
        height: number
      }>
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

const props = defineProps<{
  game: RecommendationGame
  profile: RecommendationProfile
}>()
const emit = defineEmits<{
  close: []
  change: []
  status: [value: RecommendationJourneyStatus]
  'open-rulebook': [value: RecommendationJourneyStatus]
  'open-lesson': [value: RecommendationJourneyStatus]
  'ask-questions': [value: RecommendationJourneyStatus]
  remove: []
}>()
const { locale } = useLocale()

const copy = computed(() => locale.value === 'zh-CN' ? {
  eyebrow: '从推荐到答疑', title: `已选《${props.game.name}》`, preparing: '正在加入“我的桌游”并寻找可审阅的规则书…',
  finding: '桌游已保存，正在查找出版社、BGG、集石和可信规则库（已等待 {seconds} 秒，通常几秒，偶尔约 30 秒）…', found: '选择并核对来源', detail: '优先展示出版社来源，也会保留社区与可信规则库结果。请核对语言和版本；只有已核验的 PDF 或连续规则页可以导入。',
  noImportableTitle: '暂未找到可直接导入的规则书', noImportableDetail: '自动查找没有产出可直接导入的文件。可以继续查找，也可以提供公开 PDF / 规则页链接或上传自己的规则书。',
  identityOnlyTitle: '仅用于核对桌游身份', identityOnlyDetail: '这些页面没有可导入的规则书文件，不属于规则书选择。',
  sources: { PUBLISHER: '出版社 / 权利方来源', TRUSTED_REPOSITORY: '可信规则库', COMMUNITY_PLATFORM: '社区规则书来源（如 BGG / 集石）', PUBLIC_WEB: '公开来源（请重点核对）' },
  capabilities: { DIRECT_DOCUMENT: '已核验为可下载文档', CONTIGUOUS_RULE_PAGES: '已核验为连续规则页', DOCUMENT_LISTING: '仅确认是文档列表页', GAME_INFO_ONLY: '仅有桌游信息，没有规则书文件', UNVERIFIED_PAGE: '尚未核验出可导入文档' },
  direct: 'PDF 可直接核验并下载', gallery: '连续规则页图片，可合成为 PDF', page: '来源页，需要继续查找文件', publisher: '发布者', language: '语言', languageVerified: '来源已明确标注', languageReview: '需在来源页核对', edition: '版本', unknown: '未标明', choose: '选择这份', selected: '已选择', continueListing: '继续查找文件', reviewUnverified: '审阅来源页',
  consent: '我确认该链接来自有权提供这份规则书的来源，并授权 RulePilot 下载用于我的个人讲解。',
  identityChanged: '提交前目录或来源身份发生了变化。请重新比较上面的游戏、版本和语言后再次确认。',
  identityActive: '这个链接正在为另一个版本导入。请等待那次导入结束，或改用公开链接 / 本地上传；当前桌游选择不会丢失。',
  import: '下载规则书并生成讲解', manual: '提供公开链接或自己的规则书',
  retryDiscovery: '继续查找',
  discoveryTerminal: {
    PARTIAL: '部分来源未在本次预算内完成，自动查找已停下；下面只保留已核验结果。如果没有合适来源，可以提供公开链接或自己的规则书。',
    TIMED_OUT: '来源查找阶段已到达整体等待上限，自动查找已停下。可以重试，也可以提供公开链接或自己的规则书。',
    FAILED: '部分来源检查失败，自动查找已停下。可以重试，也可以提供公开链接或自己的规则书。',
  },
  discoveryTiming: (elapsed: number, budget: number) => `本次查找用时 ${elapsed} 秒，最长等待 ${budget} 秒。`,
  discoveryProviders: { CATALOG: '规则书目录', SOURCE_INSPECTION: '来源核验', WEB_SEARCH: '联网搜索' },
  discoveryProviderStates: { FINISHED: '已完成', TIMED_OUT: '单个请求超时', FAILED: '失败', SKIPPED: '未使用', UNAVAILABLE: '未配置' },
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
  unavailable: '当前没有找到可审阅的规则书来源，自动查找已停下。你可以提供公开 PDF / 规则页链接或上传自己的规则书。',
  login: '登录后即可保留这次选择并继续找规则书。', loginAction: '打开桌游详情并继续',
  error: '这一步暂时没有完成；推荐对话和已选桌游不会受影响。', partialFailure: '已生成的章节仍可阅读，但后台生成或核对没有完整结束。现有内容不会丢失。',
  missingChapterEvidence: (titles: string) => `“${titles}”对应的规则页没有提供足够依据，所以没有猜测并发布；其他已校验章节仍可阅读。`,
  invalidChapterEvidence: (titles: string) => `“${titles}”读取到的依据没有通过来源或规则书版本校验，因此没有进入正文生成；其他已校验章节仍可阅读。`,
  invalidChapter: (titles: string) => `“${titles}”的草稿没有通过引用或结构校验，因此没有发布；其他已校验章节仍可阅读。`,
  retry: '重试当前步骤', stop: '停止本次生成', restart: '从已完成内容重新开始', retryFailed: '本次重试没有启动，后台不会自动继续重试。请检查连接后再手动操作。', stopFailed: '本次停止请求没有完成，当前运行可能仍在继续。请检查连接后再操作。', remove: '删除讲解', removeConfirm: '确认删除这份讲解及其生成任务？规则书会保留，已生成章节会被删除。', removeYes: '确认删除', removeNo: '先保留', close: '关闭小窗', change: '换一款',
  safe: '可以关闭这个小窗继续聊天；正在运行的下载和讲解会继续。关闭后，页面上的“讲解状态”入口会一直显示，也可以随时打开“我的讲解”。', safeStopped: '可以关闭这个小窗继续聊天；已确认内容会保留，已停止的任务不会在后台自行重新开始。页面上的“讲解状态”入口会一直显示。',
  progress: '当前步骤进度', inProgress: '进行中', waiting: '等待你继续', stopped: '已停止', current: '现在正在做', generationSteps: '讲解生成步骤', generationLatest: '最新实际进度', generationProcessHint: '真实后台活动会在下方逐条追加；进入逐章生成后，第 4～7 步会按章节重复。',
  currentFailure: '本次属于',
  currentFailureDetail: {
    'local-degradation': '可用内容已经保留，只有可选或局部工作未完成；主流程不会因此回退。',
    'retry-preserved': '当前运行已经停止，已确认页面和已发布章节保留；系统不会在后台自行重新开始。',
    'repair-required': '当前错误不适合原样重试；请按下面实际提供的操作处理，若没有操作入口则需等待服务恢复或联系支持。',
    'internal-correction': '这不是玩家输入失败；同一个 Agent 会收到完整候选与校验记录并返回完整替代结果。完全重复、无进展或资源停止会结束这一步。',
  },
  failureRecoveryDetail: {
    'retry-step': '后端允许你明确重试当前步骤；只有点击下面的按钮才会开始。',
    'restart-from-completed': '重新开始会创建一次新的运行，并复用已经确认的内容。',
    'choose-source': '请使用下面实际显示的换来源、上传或浏览器下载操作。',
    'manual-repair': '这里没有安全的自动重试；修复权限、来源、输入或服务问题后再继续。',
  },
  failureCauseDetail: {
    TEACHING_PREPARATION_PLAN_RESOLUTION_FAILED: '失败发生在整理讲解结构时；规则书页面已经保留，第一段讲解尚未开始。',
    TEACHING_PREPARATION_FIRST_SECTION_STARTUP_FAILED: '讲解结构已经形成；失败发生在生成并保存第一段带引用讲解时，规则书和结构都会保留。',
    TEACHING_PREPARATION_QUEUE_FULL: '讲解准备队列已满；模型工作尚未开始，规则书不受影响。',
    TEACHING_PREPARATION_QUEUE_TIMEOUT: '讲解准备任务在限定时间内没有获得 worker；模型工作尚未开始。',
    TEACHING_PREPARATION_WORKER_ADMISSION_FAILED: '讲解准备任务获得 worker 后未能持久接管；模型工作尚未开始。',
    TEACHING_QUEUE_FULL: '正文生成任务尚未进入执行队列；已有规则书和讲解结构不受影响。',
    TEACHING_QUEUE_TIMEOUT: '正文生成任务在限定时间内没有获得 worker；模型工作尚未开始，可以直接重试。',
    TEACHING_WORKER_ADMISSION_FAILED: '正文生成任务获得 worker 后未能持久接管；模型工作尚未开始，可以直接重试。',
    TEACHING_CONTINUATION_QUEUE_FULL: '第一段带引用讲解已经可读；失败发生在其余章节进入后台队列时。',
    TEACHING_CONTINUATION_QUEUE_TIMEOUT: '第一段带引用讲解已经可读；其余章节在限定时间内没有获得 worker。',
    TEACHING_CONTINUATION_ADMISSION_FAILED: '第一段带引用讲解已经可读；其余章节获得 worker 后未能持久接管。',
    TEACHING_WORKFLOW_FAILED: '失败发生在读取章节证据、生成正文或核对引用期间；已经发布的章节会保留。',
    TEACHING_COMPLETION_FAILED: '讲解内容已经生成，但保存最终完成状态失败；已有可读章节会保留。',
    AGENT_TIMEOUT: '本轮达到后端记录的截止时间；已确认内容会保留，可以原样启动新任务。',
  },
  generationLocalFailureTitle: '局部降级：可用内容保留',
  generationLocalFailure: '单页、单章或配图不可用只影响对应局部；其他成功页面和已经发布的正文都会保留。',
  generationPreservedStopTitle: '可原样重试，进度保留',
  generationPreservedStop: '模型服务、排队、截止时间、传输或取消会停止当前任务，但已确认页面和已发布章节保留；可以用相同输入启动新任务。',
  generationRepairTitle: '需要你或运维修复后继续',
  generationRepair: '登录或权限、无效输入、错误来源、所有权、版本、保存、身份或引用问题需要先修复，再重新发起。',
  generationInternalCorrectionTitle: '内部 JSON 修正，不是玩家输入失败',
  generationInternalCorrection: '同一个 Agent 会收到完整候选、code、path、reason、schema 和 allowed IDs，并必须返回完整替代结果；完全重复、无进展或资源停止才结束这一步。',
  visualRuleGroupSummaryTitle: '每页规则组最新状态',
  visualRuleGroupSummaryHint: '只按每页已发出的最新真实活动汇总；还没有活动的页面不会计入，下方保留每次玩家可见尝试。',
  visualRuleGroupStatus: {
    'directly-completed': '直接完成',
    'completed-after-correction': '经完整候选修正后完成',
    processing: '正在处理',
    'local-unavailable': '本页局部不可用',
  } satisfies Record<TeachingVisualPageRuleGroupState, string>,
  visualRuleGroupCount: (count: number) => `${count} 页`,
  visualRuleGroupPages: (pages: readonly number[]) => `第 ${pages.join('、')} 页`,
  generationAttemptMarkerHint: '“!”表示这一条真实尝试未完成或未通过校验，“?”表示活动状态无法识别；两者都不代表整份讲解失败，请以上方每页最新状态和整条任务状态为准。',
  planning: '规划中', pollingWarning: '暂时没有拿到最新进度，正在自动重试；已确认的进度不会倒退。',
  generationProcess: [
    '图片页直接按原图和页码整理规则，文字页直接读取原文；结构化格式不合格时，完整候选和校验记录会交回同一个 Agent，并要求返回完整替代结果',
    '按页面整理规则组，并记录规则书要求的外部资料',
    '通读整本规则书，形成整局认识并规划章节',
    '读取当前章节绑定的规则页与引用',
    '依据原文生成玩家可以直接照做的讲解步骤',
    '校验引用归属、规则书版本与章节结构',
    '通过后立即发布当前章节，再继续下一章',
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
  noImportableTitle: 'No directly importable rulebook yet', noImportableDetail: 'Automatic discovery did not produce an importable file. Continue the search, provide a public PDF or rule-page link, or upload your own rulebook.',
  identityOnlyTitle: 'Game identity references only', identityOnlyDetail: 'These pages do not contain an importable rulebook and are not rulebook choices.',
  sources: { PUBLISHER: 'Publisher / rights-holder', TRUSTED_REPOSITORY: 'Trusted rules repository', COMMUNITY_PLATFORM: 'Community rulebook source (such as BGG / Gstone)', PUBLIC_WEB: 'Public source (review carefully)' },
  capabilities: { DIRECT_DOCUMENT: 'Confirmed downloadable document', CONTIGUOUS_RULE_PAGES: 'Confirmed ordered rule pages', DOCUMENT_LISTING: 'Document listing only', GAME_INFO_ONLY: 'Game information only; no rulebook file', UNVERIFIED_PAGE: 'No importable document verified' },
  direct: 'Direct PDF ready for verification', gallery: 'Ordered rulebook pages; RulePilot can build the PDF', page: 'Source page; continue there', publisher: 'Provider', language: 'Language', languageVerified: 'stated by the source', languageReview: 'verify on the source page', edition: 'Edition', unknown: 'Not stated', choose: 'Choose this one', selected: 'Selected', continueListing: 'Continue finding a file', reviewUnverified: 'Review source page',
  consent: 'I confirm that this source may provide the rulebook and authorize RulePilot to download it for my personal guide.',
  identityChanged: 'The catalog or source identity changed before submission. Compare the game, edition, and language above, then reconfirm.',
  identityActive: 'This URL is already being imported for another edition. Wait for it to finish or use a public URL / local upload; the selected game remains intact.',
  import: 'Download and generate guide', manual: 'Provide a public link or your own rulebook',
  retryDiscovery: 'Search again',
  discoveryTerminal: {
    PARTIAL: 'Some sources did not finish within this search budget, so automatic discovery stopped. Only verified results are shown below. If none fits, provide a public link or your own rulebook.',
    TIMED_OUT: 'The source-discovery phase reached its overall waiting limit, so automatic discovery stopped. Retry it, provide a public link, or upload your own rulebook.',
    FAILED: 'Some source checks failed, so automatic discovery stopped. Retry it, provide a public link, or upload your own rulebook.',
  },
  discoveryTiming: (elapsed: number, budget: number) => `Search finished in ${elapsed}s with a ${budget}s maximum budget.`,
  discoveryProviders: { CATALOG: 'Rulebook catalog', SOURCE_INSPECTION: 'Source verification', WEB_SEARCH: 'Web search' },
  discoveryProviderStates: { FINISHED: 'finished', TIMED_OUT: 'request timed out', FAILED: 'failed', SKIPPED: 'not needed', UNAVAILABLE: 'not configured' },
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
  unavailable: 'No reviewable rulebook source was found, and automatic discovery has stopped. Provide a public PDF or rule-page link, or upload your own rulebook.',
  login: 'Sign in to keep this selection and continue to its rulebook.', loginAction: 'Open game details and continue',
  error: 'This step did not complete. The conversation and selected game are unaffected.', partialFailure: 'Published chapters remain readable, but background generation or review did not finish. Existing content remains available.',
  missingChapterEvidence: (titles: string) => `The cited rulebook pages did not provide enough support for “${titles}”, so RulePilot did not guess or publish it. Other validated chapters remain readable.`,
  invalidChapterEvidence: (titles: string) => `The evidence read for “${titles}” did not pass source or rulebook-version validation, so chapter writing did not start. Other validated chapters remain readable.`,
  invalidChapter: (titles: string) => `The draft for “${titles}” did not pass citation or structure checks, so it was not published. Other validated chapters remain readable.`,
  retry: 'Retry this step', stop: 'Stop this run', restart: 'Start a new run from completed work', retryFailed: 'This retry did not start. Nothing is retrying in the background; check your connection and try the action again.', stopFailed: 'The stop request did not complete, so this run may still be active. Check your connection and try again.', remove: 'Delete guide', removeConfirm: 'Delete this guide and its generation work? The rulebook stays, but published chapters are removed.', removeYes: 'Delete guide', removeNo: 'Keep it', close: 'Close', change: 'Choose another game',
  safe: 'You may close this panel and keep chatting. Downloads and guide runs that are still active will continue. The “Guide status” shortcut stays visible, and My guides is always available.', safeStopped: 'You may close this panel and keep chatting. Confirmed content remains available, and stopped work will not restart in the background. The “Guide status” shortcut stays visible.',
  progress: 'Current-step progress', inProgress: 'In progress', waiting: 'Waiting for you', stopped: 'Stopped', current: 'Working on', generationSteps: 'Guide generation steps', generationLatest: 'Latest actual progress', generationProcessHint: 'Real background activities are appended below. Once chapter writing starts, steps 4–7 repeat for each chapter.',
  currentFailure: 'This run is classified as',
  currentFailureDetail: {
    'local-degradation': 'Usable content remains available; only optional or page-local work did not finish, and the main flow does not roll back.',
    'retry-preserved': 'This run has stopped. Confirmed pages and published chapters remain available, and the system will not restart it in the background.',
    'repair-required': 'Repeating the same request is not a safe recovery. Use only the actions actually shown below; if none is available, wait for service recovery or contact support.',
    'internal-correction': 'This is not a player-input failure. The same Agent receives the complete candidate and validation record and returns a complete replacement. Exact repetition, no progress, or a resource stop ends the step.',
  },
  failureRecoveryDetail: {
    'retry-step': 'The backend allows an explicit retry of this step; it starts only when you use the button below.',
    'restart-from-completed': 'Starting again creates a new run and reuses work that has already been confirmed.',
    'choose-source': 'Use one of the source change, upload, or browser-download actions actually shown below.',
    'manual-repair': 'There is no safe automatic retry here. Repair the authorization, source, input, or service problem before continuing.',
  },
  failureCauseDetail: {
    TEACHING_PREPARATION_PLAN_RESOLUTION_FAILED: 'The failure occurred while organizing the guide structure. Rulebook pages remain available, and first-section writing had not started.',
    TEACHING_PREPARATION_FIRST_SECTION_STARTUP_FAILED: 'The guide structure is ready. The failure occurred while generating and saving the first cited section; the rulebook and structure remain available.',
    TEACHING_PREPARATION_QUEUE_FULL: 'The guide-preparation queue was full. No model work started, and the rulebook is unaffected.',
    TEACHING_PREPARATION_QUEUE_TIMEOUT: 'Guide preparation did not acquire a worker before the backend queue deadline. No model work started.',
    TEACHING_PREPARATION_WORKER_ADMISSION_FAILED: 'Guide preparation acquired a worker but could not durably claim the run. No model work started.',
    TEACHING_QUEUE_FULL: 'The writing run did not enter the execution queue. The rulebook and guide structure are unaffected.',
    TEACHING_QUEUE_TIMEOUT: 'The writing run did not acquire a worker before the backend queue deadline. No model work started, so it is safe to retry.',
    TEACHING_WORKER_ADMISSION_FAILED: 'The writing run acquired a worker but could not durably claim the run. No model work started, so it is safe to retry.',
    TEACHING_CONTINUATION_QUEUE_FULL: 'The first cited section is already readable. The failure occurred while queueing the remaining chapters.',
    TEACHING_CONTINUATION_QUEUE_TIMEOUT: 'The first cited section is already readable. The remaining chapters did not acquire a worker before the backend queue deadline.',
    TEACHING_CONTINUATION_ADMISSION_FAILED: 'The first cited section is already readable. The remaining chapters acquired a worker but could not durably claim the run.',
    TEACHING_WORKFLOW_FAILED: 'The failure occurred while retrieving chapter evidence, writing content, or checking citations. Published chapters remain available.',
    TEACHING_COMPLETION_FAILED: 'Guide content was generated, but its final completed state could not be saved. Readable chapters remain available.',
    AGENT_TIMEOUT: 'The run reached the backend-recorded deadline. Confirmed content remains available, and the same input can start a fresh task.',
  },
  generationLocalFailureTitle: 'Local degradation: usable content remains',
  generationLocalFailure: 'An unavailable page, chapter, or visual affects only that item. Other successful pages and published text remain available.',
  generationPreservedStopTitle: 'Retry unchanged; progress preserved',
  generationPreservedStop: 'Provider, queue, deadline, transport, or cancellation stops preserve confirmed pages and published chapters; the same input can start a fresh task.',
  generationRepairTitle: 'You or operations must repair this before continuing',
  generationRepair: 'Authentication, invalid input, source, ownership, version, persistence, identity, or citation errors must be repaired before starting again.',
  generationInternalCorrectionTitle: 'Internal JSON correction, not a player-input failure',
  generationInternalCorrection: 'The same Agent receives the complete candidate, code, path, reason, schema, and allowed IDs and must return a complete replacement. Exact repetition, no progress, or a resource stop ends the step.',
  visualRuleGroupSummaryTitle: 'Latest rule-group state by page',
  visualRuleGroupSummaryHint: 'This uses only the latest real activity emitted for each page. Pages without an activity are not counted, and every player-visible attempt remains below.',
  visualRuleGroupStatus: {
    'directly-completed': 'Completed directly',
    'completed-after-correction': 'Completed after full-candidate correction',
    processing: 'Processing',
    'local-unavailable': 'Page locally unavailable',
  } satisfies Record<TeachingVisualPageRuleGroupState, string>,
  visualRuleGroupCount: (count: number) => `${count} ${count === 1 ? 'page' : 'pages'}`,
  visualRuleGroupPages: (pages: readonly number[]) => pages.length === 1
    ? `Page ${pages[0]}`
    : `Pages ${pages.join(', ')}`,
  generationAttemptMarkerHint: '“!” marks one real attempt that did not complete or pass validation, while “?” marks an unrecognized activity status. Neither means the entire guide failed; use the latest per-page state above and the overall run state.',
  planning: 'Planning', pollingWarning: 'The latest update is temporarily unavailable. Retrying automatically without rolling back confirmed progress.',
  generationProcess: [
    'Build page-bound rule facts from image pages and text layers; typed-format failures return the complete candidate and exact validation record to the same Agent for a complete replacement',
    'Organise each page into rule groups and record any external material the rulebook requires',
    'Read the whole rulebook, form a whole-game view, and plan the chapters',
    'Read the source pages and citations bound to the current chapter',
    'Generate player-actionable teaching steps directly from the source',
    'Check citation ownership, rulebook version, and chapter structure',
    'Publish the current chapter immediately, then continue with the next one',
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
const retryFailure = ref(false)
const stopFailure = ref(false)
const retrying = ref(false)
const generationStoppedByPlayer = ref(false)
const deleteConfirmOpen = ref(false)
const deleting = ref(false)
const deleteTrigger = ref<HTMLButtonElement | null>(null)
const deleteCancel = ref<HTMLButtonElement | null>(null)
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

class IdentityBoundaryError extends Error {}

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
  const cancelled = current.errorCode === 'AGENT_CANCELLED'
  let stage: PlayerWorkStage
  if (current.phase === 'GAME_BINDING' || current.phase === 'RULEBOOK_DISCOVERY') stage = 'FINDING_RULEBOOK'
  else if (current.phase === 'SOURCE_REVIEW') stage = 'WAITING_FOR_PLAYER'
  else if (current.phase.startsWith('IMPORT_')) stage = 'ACQUIRING_RULEBOOK'
  else if (current.phase === 'DOCUMENT_PROCESSING') stage = 'READING_RULEBOOK'
  else if (current.phase === 'LESSON_READABLE') stage = 'GUIDE_READABLE'
  else if (current.phase === 'LESSON_COMPLETE') stage = 'GUIDE_COMPLETE'
  else if (current.phase === 'FAILED') stage = cancelled ? 'CANCELLED' : current.retryAction ? 'NEEDS_ACTION' : 'FAILED'
  else stage = 'ORGANIZING_GUIDE'

  const capability = current.canReadLesson ? 'guide' : current.canReadRulebook ? 'rulebook' : 'none'
  const readiness = current.phase === 'LESSON_COMPLETE'
    ? 'complete'
    : current.canReadLesson || current.canReadRulebook ? 'usable' : 'unavailable'
  const terminality = current.state === 'waiting'
    ? 'waiting'
    : current.state === 'active' || current.state === 'ready' && !current.failureClassification ? 'active' : 'terminal'
  const outcome = cancelled
    ? 'cancelled'
    : current.retryAction ? 'needs-action'
      : current.state === 'failed' || current.failureClassification === 'repair-required' ? 'failed' : 'none'
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
        visualEvidenceRecommended: section.visualEvidenceRecommended ?? false,
      })),
    }
    return teachingActivityText(
      progressPlan,
      activities,
      activities.at(-1),
      locale.value,
    )
  }
  return ''
})
const currentFailureTitle = computed(() => {
  const classification = projection.value.failureClassification
  if (!classification) return ''
  if (classification === 'local-degradation') return copy.value.generationLocalFailureTitle
  if (classification === 'retry-preserved') return copy.value.generationPreservedStopTitle
  if (classification === 'internal-correction') return copy.value.generationInternalCorrectionTitle
  return copy.value.generationRepairTitle
})
const currentFailureDetail = computed(() => {
  const classification = projection.value.failureClassification
  return classification ? copy.value.currentFailureDetail[classification] : copy.value.error
})
const currentFailureRecoveryDetail = computed(() => {
  const recovery = projection.value.failureRecovery
  return recovery ? copy.value.failureRecoveryDetail[recovery] : ''
})
const currentFailureCauseDetail = computed(() => {
  const errorCode = projection.value.errorCode
  if (!errorCode) return ''
  return (copy.value.failureCauseDetail as Record<string, string>)[errorCode] ?? ''
})
const visibleFailureDetails = computed<PlayerFailureDescriptor | null>(() => {
  const code = projection.value.errorCode
  if (projection.value.failureClassification && code) {
    return {
      category: projection.value.failureClassification,
      owner: teachingFailureOwner(code, locale.value),
      code,
    }
  }
  const activity = [...(teachingRun.value?.activities ?? preparationRun.value?.activities ?? [])]
    .reverse()
    .find(entry => entry.outcome === 'FAILED' || entry.outcome === 'REJECTED')
  if (!activity) return null
  const operation = activity.operation
  const category = operation.startsWith('enrichTeachingSectionVisual|')
    || operation.startsWith('publishTeachingSection|')
    ? 'local-degradation'
    : operation.startsWith('validateTeachingOutlineAction|')
      || operation.startsWith('advanceTeachingOutlineAgent|')
      ? 'internal-correction'
      : 'retry-preserved'
  const owner = operation.startsWith('enrichTeachingSectionVisual|')
    ? locale.value === 'en' ? 'Visual enrichment' : '配图处理'
    : operation.startsWith('publishTeachingSection|')
      ? locale.value === 'en' ? 'Chapter publication' : '章节发布'
      : locale.value === 'en' ? 'Guide Agent' : '讲解 Agent'
  return { category, owner, code: `${operation} · ${activity.summary}` }
})
const retryActionLabel = computed(() => projection.value.failureRecovery === 'restart-from-completed'
  ? copy.value.restart
  : copy.value.retry)
const terminalAlertClass = computed(() => projection.value.failureClassification === 'local-degradation'
  ? 'border-emerald-200 bg-emerald-50 text-emerald-900'
  : projection.value.failureClassification === 'retry-preserved'
    ? 'border-amber-200 bg-amber-50 text-amber-950'
    : projection.value.failureClassification === 'internal-correction'
      ? 'border-indigo/25 bg-indigo/5 text-indigo'
    : 'border-red-200 bg-red-50 text-red-800')
const safeCloseDetail = computed(() => projection.value.failureClassification || generationStoppedByPlayer.value
  ? copy.value.safeStopped
  : copy.value.safe)
const journeyTeachingSteps = computed(() => {
  const preparationSteps = recentTeachingPreparationActivitySteps(
    preparationRun.value?.activities ?? [],
    locale.value,
  ).map(step => ({ ...step, key: `preparation-${step.sequence}` }))
  if (!plan.value || !teachingRun.value?.activities?.length) return preparationSteps
  const progressPlan = {
    sections: plan.value.sections.map(section => ({
      ...section,
      visualEvidenceRecommended: section.visualEvidenceRecommended ?? false,
    })),
  }
  const chapterSteps = recentTeachingActivitySteps(
    progressPlan,
    teachingRun.value.activities,
    locale.value,
  ).map(step => ({ ...step, key: `chapter-${step.sequence}` }))
  return [...preparationSteps, ...chapterSteps]
})
const visualPageRuleGroupStates = [
  'directly-completed',
  'completed-after-correction',
  'processing',
  'local-unavailable',
] as const satisfies readonly TeachingVisualPageRuleGroupState[]
const visualPageRuleGroupSummary = computed(() => summarizeTeachingVisualPageRuleGroups(
  preparationRun.value?.activities ?? [],
  preparationRun.value?.run.state,
))
const visualPageRuleGroupBuckets = computed(() => visualPageRuleGroupStates.map(state => ({
  state,
  pages: visualPageRuleGroupSummary.value
    .filter(page => page.state === state)
    .map(page => page.pageNumber),
})))
const hasUnsuccessfulTeachingAttempt = computed(() => journeyTeachingSteps.value.some(
  step => step.outcome === 'FAILED' || step.outcome === 'REJECTED' || step.outcome === 'UNKNOWN',
))
const teachingJourneyPhases = new Set([
  'TEACHING_PREPARATION_QUEUED', 'TEACHING_PREPARING', 'LESSON_GENERATION_QUEUED',
  'LESSON_GENERATING', 'LESSON_READABLE', 'LESSON_COMPLETE',
])
const showTeachingGenerationSteps = computed(() => teachingJourneyPhases.has(projection.value.phase)
  || projection.value.phase === 'FAILED' && Boolean(
    projection.value.canReadRulebook || preparationRun.value || plan.value || teachingRun.value,
  ))
const visibleJourneyTeachingSteps = computed(() => {
  if (journeyTeachingSteps.value.length) {
    return [...journeyTeachingSteps.value].reverse()
  }
  const phase = projection.value.phase
  const preparationState = preparationRun.value?.run.state
  let text = copy.value.generationFallback.queued
  let outcome: TeachingActivityOutcome = 'RUNNING'
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
const journeyProgressLabel = computed(() => {
  if (projection.value.failureClassification || generationStoppedByPlayer.value) return copy.value.stopped
  if (projection.value.state === 'waiting') return copy.value.waiting
  if (projection.value.phase.startsWith('LESSON_') && projection.value.totalSections) {
    return copy.value.chapters(projection.value.availableSections, projection.value.totalSections)
  }
  if (projection.value.progress === null) return copy.value.inProgress
  return `${projection.value.progress}%`
})
const journeyProgressValue = computed(() => projection.value.progress)
const journeyStatus = computed<RecommendationJourneyStatus>(() => ({
  projection: projection.value,
  game: props.game,
  imported: imported.value,
  importJob: importJob.value,
  plan: plan.value,
  lesson: lesson.value,
}))
let lastEmittedStatus = ''

function visualFocusSignature(candidate: IllustratedLesson | null) {
  if (!candidate) return ''
  const values: string[] = []
  const sections = [...candidate.sections].sort((left, right) => left.position - right.position)
  for (const section of sections) {
    const steps = [...(section.steps ?? [])].sort((left, right) => left.position - right.position)
    for (const step of steps) {
      const foci = step.visualFoci?.length
        ? [...step.visualFoci]
        : step.visualFocus ? [step.visualFocus] : []
      foci.sort((left, right) => left.pageNumber - right.pageNumber
        || left.x - right.x
        || left.y - right.y
        || left.width - right.width
        || left.height - right.height)
      for (const focus of foci) {
        values.push([
          section.position,
          step.position,
          focus.pageNumber,
          focus.x,
          focus.y,
          focus.width,
          focus.height,
        ].join(':'))
      }
    }
  }
  return values.join('|')
}

function emitJourneyStatus(value: RecommendationJourneyStatus) {
  const signature = JSON.stringify({
    bggId: value.game.bggId,
    phase: value.projection.phase,
    state: value.projection.state,
    progress: value.projection.progress,
    retryAction: value.projection.retryAction,
    failureClassification: value.projection.failureClassification,
    failureRecovery: value.projection.failureRecovery,
    canReadRulebook: value.projection.canReadRulebook,
    canReadLesson: value.projection.canReadLesson,
    canAskQuestions: value.projection.canAskQuestions,
    importJobId: value.importJob?.id ?? null,
    documentVersionId: value.importJob?.documentVersionId ?? null,
    planId: value.plan?.id ?? null,
    lessonId: value.lesson?.id ?? null,
    lessonStatus: value.lesson?.status ?? null,
    availableSections: value.lesson?.sections.length ?? 0,
    visualFocus: visualFocusSignature(value.lesson),
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
  const shouldNotify = state.value !== 'login'
  state.value = 'login'
  clearJourneyTimer()
  if (shouldNotify) notifyLoginRequired()
}

async function checkedJson<T>(path: string, optional = false): Promise<T | null> {
  const response = await fetch(path, { credentials: 'include' })
  if (response.status === 401 || response.status === 403) {
    throw new IdentityBoundaryError('login required')
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
    if (await restoreServerJourney(request)) return
    await discover(request)
  } catch {
    if (request === sequence) state.value = 'error'
  }
}

async function restoreServerJourney(request: number) {
  if (!imported.value || request !== sequence) return false
  const response = await fetch(
    `/api/v1/documents/official-imports?editionId=${encodeURIComponent(imported.value.edition.id)}`,
    { credentials: 'include' },
  )
  if (request !== sequence) return false
  if (response.status === 401 || response.status === 403) {
    requireLogin()
    return true
  }
  if (!response.ok) return false
  const jobs = await response.json() as OfficialImportJob[]
  if (request !== sequence || !Array.isArray(jobs)) return false
  let matching = jobs
    .map(normalizeImportJob)
    .find(job => job.editionId === imported.value?.edition.id && job.teachingHandoffState !== 'NOT_REQUESTED')
  if (!matching) return false
  if (matching.stage === 'COMPLETED'
    && matching.documentVersionId
    && matching.teachingHandoffState === 'LAUNCHED'
    && matching.teachingPreparationRunId) {
    const token = await csrfToken()
    const ensured = await fetch(
      `/api/v1/documents/official-imports/${encodeURIComponent(matching.id)}/teaching-ensure-current`, {
        method: 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/json', [token.headerName]: token.token },
        body: JSON.stringify({ expectedPreparationRunId: matching.teachingPreparationRunId }),
      })
    if (request !== sequence) return false
    if (ensured.status === 401 || ensured.status === 403) {
      requireLogin()
      return true
    }
    if (!ensured.ok) throw new Error('teaching evidence freshness check failed')
    const current = normalizeImportJob(await ensured.json() as OfficialImportJob)
    if (current.id !== matching.id) throw new Error('teaching evidence freshness identity changed')
    matching = current
  }
  importJob.value = matching
  preparationRunId.value = matching.teachingPreparationRunId
  consent.value = true
  identityConfirmed.value = true
  pollingWarning.value = false
  state.value = matching.stage === 'FAILED' && matching.recovery?.canOpenSourceInBrowser
    ? 'browser-required'
    : 'journey'
  persistJourney()
  if (state.value === 'journey') scheduleJourney(0)
  return true
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
    notifyBackgroundWorkChanged({ importJob: incoming })
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
      if (incoming) {
        if (incoming.run.subjectId !== versionId) throw new Error('preparation run response identity mismatch')
        preparationRun.value = acceptJourneyRun(preparationRun.value, incoming)
      }
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
        if (incomingRun.run.subjectId !== targetPlanId) throw new Error('teaching run response identity mismatch')
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
    persistJourney()
  } catch (caught) {
    if (request === sequence) {
      if (caught instanceof IdentityBoundaryError) {
        requireLogin()
        pollingWarning.value = false
      } else {
        pollingWarning.value = true
      }
    }
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
  retryFailure.value = false
  stopFailure.value = false
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
      notifyBackgroundWorkChanged({ importJob: retriedJob })
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
    if (state.value !== 'login') retryFailure.value = true
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
  notifyBackgroundWorkChanged({ importJob: retriedJob })
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
  if (clearFailedRun) {
    teachingRun.value = null
  }
}

const activeGenerationRunId = computed(() => {
  if (teachingRunId.value && teachingRunIsActive(teachingRun.value?.run.state)) return teachingRunId.value
  if (preparationRunId.value && preparationRun.value && !runTerminal(preparationRun.value.run.state)) {
    return preparationRunId.value
  }
  return null
})

async function stopGeneration() {
  const runId = activeGenerationRunId.value
  if (!runId || retrying.value) return
  retrying.value = true
  stopFailure.value = false
  try {
    const token = await csrfToken()
    const response = await fetch(`/api/v1/assistant-runs/${encodeURIComponent(runId)}/cancellation`, {
      method: 'POST', credentials: 'include', headers: { [token.headerName]: token.token },
    })
    if (!response.ok) throw new Error('teaching cancellation failed')
    generationStoppedByPlayer.value = true
    persistJourney()
    scheduleJourney(0)
  } catch {
    stopFailure.value = true
  } finally {
    retrying.value = false
  }
}

async function restartGeneration() {
  if (retrying.value) return
  retrying.value = true
  retryFailure.value = false
  stopFailure.value = false
  try {
    generationStoppedByPlayer.value = false
    if (plan.value) {
      await launchLesson(plan.value.id, true)
    } else if (importJob.value) {
      const token = await csrfToken()
      const response = await fetch(
        `/api/v1/documents/official-imports/${encodeURIComponent(importJob.value.id)}/teaching-retry`, {
          method: 'POST', credentials: 'include',
          headers: { 'Content-Type': 'application/json', [token.headerName]: token.token },
          body: JSON.stringify({ expectedPreparationRunId: preparationRunId.value }),
        },
      )
      if (!response.ok) throw new Error('teaching resume failed')
      importJob.value = normalizeImportJob(await response.json() as OfficialImportJob)
      preparationRunId.value = importJob.value.teachingPreparationRunId
      notifyBackgroundWorkChanged({ importJob: importJob.value })
    }
    persistJourney()
    scheduleJourney(0)
  } catch {
    generationStoppedByPlayer.value = true
    retryFailure.value = true
  } finally {
    retrying.value = false
  }
}

function openDeleteConfirmation() {
  deleteConfirmOpen.value = true
  void nextTick(() => deleteCancel.value?.focus())
}

function closeDeleteConfirmation() {
  deleteConfirmOpen.value = false
  void nextTick(() => deleteTrigger.value?.focus())
}

async function deleteTeachingJourney() {
  if (deleting.value) return
  deleting.value = true
  try {
    const token = await csrfToken()
    if (plan.value) {
      const response = await fetch(`/api/v1/teaching-plans/${encodeURIComponent(plan.value.id)}`, {
        method: 'DELETE', credentials: 'include', headers: { [token.headerName]: token.token },
      })
      if (!response.ok && response.status !== 404) throw new Error('teaching deletion failed')
    } else if (activeGenerationRunId.value) {
      const response = await fetch(
        `/api/v1/assistant-runs/${encodeURIComponent(activeGenerationRunId.value)}/cancellation`, {
          method: 'POST', credentials: 'include', headers: { [token.headerName]: token.token },
        },
      )
      if (!response.ok && response.status !== 404) throw new Error('teaching cancellation failed')
    }
    sessionStorage.removeItem(storageKey())
    deleteConfirmOpen.value = false
    resetJourneyState()
    emit('remove')
    emit('close')
  } finally {
    deleting.value = false
  }
}

function scheduleJourney(delay: number) {
  clearJourneyTimer()
  if (!importJob.value || state.value === 'login') return
  const current = projection.value
  const generationStillRunning = Boolean(plan.value && !teachingRun.value)
    || teachingRunIsActive(teachingRun.value?.run.state)
    || teachingLessonNeedsFinalSnapshot(teachingRun.value?.run.state, lesson.value?.status)
  if ((current.state === 'complete' || current.state === 'failed'
    || current.state === 'ready' && !generationStillRunning)) return
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
  retryFailure.value = false
  stopFailure.value = false
  generationStoppedByPlayer.value = false
  deleteConfirmOpen.value = false
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
  return playerJourneyRunIsTerminal(runState)
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
      generationStoppedByPlayer: generationStoppedByPlayer.value,
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
      generationStoppedByPlayer?: boolean
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
      generationStoppedByPlayer.value = stored.generationStoppedByPlayer === true
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
  <aside data-testid="player-journey-surface" :data-state="state" class="isolate overflow-hidden rounded-2xl border border-copper/25 text-ink shadow-2xl" style="background-color: var(--color-paper); opacity: 1">
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
          <li v-for="candidate in sourceCandidates" :key="candidate.url" :data-capability="candidate.capability" :data-acquisition-mode="candidate.acquisitionMode" class="rounded-xl border bg-paper p-4" :class="selected?.url === candidate.url ? 'border-copper/60 ring-2 ring-copper/10' : 'border-ink/10'">
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
        <div class="mt-4 flex flex-wrap gap-x-4 gap-y-2">
          <button v-if="!hasImportableCandidate" type="button" class="inline-flex min-h-11 items-center text-sm font-semibold text-indigo underline" @click="discover()">{{ copy.retryDiscovery }} →</button>
          <RouterLink :to="manualRoute" class="inline-flex min-h-11 items-center text-sm font-semibold text-indigo underline">{{ copy.manual }} →</RouterLink>
        </div>
        <section v-if="identityCandidates.length" class="mt-5 border-t border-ink/10 pt-4" :aria-label="copy.identityOnlyTitle">
          <h5 class="text-sm font-semibold text-ink/70">{{ copy.identityOnlyTitle }}</h5>
          <p class="mt-1 text-xs leading-5 text-ink/50">{{ copy.identityOnlyDetail }}</p>
          <ul class="mt-3 stack-y-sm">
            <li v-for="candidate in identityCandidates" :key="candidate.url" :data-capability="candidate.capability" :data-acquisition-mode="candidate.acquisitionMode" class="rounded-lg border border-ink/10 bg-paper p-3 text-xs">
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
        <PlayerFailureDetails
          v-if="visibleFailureDetails"
          class="mt-4"
          :category="visibleFailureDetails.category"
          :owner="visibleFailureDetails.owner"
          :code="visibleFailureDetails.code"
        />
        <div
          v-if="projection.failureClassification"
          data-testid="recommendation-journey-terminal-alert"
          class="mt-4 rounded-xl border p-4 text-sm"
          :class="terminalAlertClass"
          :data-failure-classification="projection.failureClassification"
          role="alert"
        >
          <p data-testid="recommendation-current-failure-classification" class="font-semibold">{{ copy.currentFailure }}：{{ currentFailureTitle }}</p>
          <p class="mt-1 leading-6">{{ currentFailureDetail }}</p>
          <p v-if="currentFailureCauseDetail" data-testid="recommendation-current-failure-cause" class="mt-1 leading-6">{{ currentFailureCauseDetail }}</p>
          <p v-if="currentFailureRecoveryDetail" class="mt-1 leading-6">{{ currentFailureRecoveryDetail }}</p>
          <template v-if="importJob?.stage === 'FAILED'">
            <p class="mt-2 leading-6">{{ importFailureDetail }}</p>
            <div class="mt-3 flex flex-wrap gap-3">
              <button v-if="importJob.recovery?.canChooseAnotherSource !== false" type="button" class="min-h-11 rounded-lg bg-indigo px-4 font-semibold text-white" @click="reviewAnotherSource">{{ copy.chooseAnotherSource }}</button>
              <RouterLink v-if="importJob.recovery?.canUseLocalUpload !== false" :to="manualRoute" class="inline-flex min-h-11 items-center rounded-lg border border-indigo/25 px-4 font-semibold text-indigo underline" @click="reviewAnotherSource">{{ copy.manual }} →</RouterLink>
              <a v-if="importJob.recovery?.canOpenSourceInBrowser && selected" :href="selected.url" target="_blank" rel="noopener noreferrer" class="inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.browserAction }} ↗</a>
              <button v-if="importJob.recovery?.canRetryOriginalSource" type="button" :disabled="retrying" class="min-h-11 rounded-lg border border-red-300 px-4 font-semibold disabled:opacity-40" @click="retryJourney">{{ copy.retryOriginalSource }}</button>
            </div>
          </template>
          <template v-else>
            <button v-if="projection.retryAction && !generationStoppedByPlayer" type="button" :disabled="retrying" class="mt-2 min-h-11 font-semibold underline disabled:opacity-40" @click="retryJourney">{{ retryActionLabel }}</button>
          </template>
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
          <div data-testid="recommendation-teaching-failure-boundary" class="mt-3 grid gap-2 md:grid-cols-2 xl:grid-cols-4">
            <div data-failure-classification="local-degradation" :data-current-failure="projection.failureClassification === 'local-degradation' ? 'true' : undefined" class="rounded-lg border border-emerald-200 bg-emerald-50/70 px-3 py-2" :class="projection.failureClassification === 'local-degradation' ? 'ring-2 ring-emerald-500/40' : ''">
              <p class="text-xs font-semibold text-emerald-800">{{ copy.generationLocalFailureTitle }}</p>
              <p class="mt-1 text-xs leading-5 text-ink/60">{{ copy.generationLocalFailure }}</p>
            </div>
            <div data-failure-classification="retry-preserved" :data-current-failure="projection.failureClassification === 'retry-preserved' ? 'true' : undefined" class="rounded-lg border border-amber-200 bg-amber-50/70 px-3 py-2" :class="projection.failureClassification === 'retry-preserved' ? 'ring-2 ring-amber-500/40' : ''">
              <p class="text-xs font-semibold text-amber-800">{{ copy.generationPreservedStopTitle }}</p>
              <p class="mt-1 text-xs leading-5 text-ink/60">{{ copy.generationPreservedStop }}</p>
            </div>
            <div data-failure-classification="repair-required" :data-current-failure="projection.failureClassification === 'repair-required' ? 'true' : undefined" class="rounded-lg border border-red-200 bg-red-50/70 px-3 py-2" :class="projection.failureClassification === 'repair-required' ? 'ring-2 ring-red-500/40' : ''">
              <p class="text-xs font-semibold text-red-800">{{ copy.generationRepairTitle }}</p>
              <p class="mt-1 text-xs leading-5 text-ink/60">{{ copy.generationRepair }}</p>
            </div>
            <div data-failure-classification="internal-correction" :data-current-failure="projection.failureClassification === 'internal-correction' ? 'true' : undefined" class="rounded-lg border border-indigo/20 bg-indigo/5 px-3 py-2" :class="projection.failureClassification === 'internal-correction' ? 'ring-2 ring-indigo/30' : ''">
              <p class="text-xs font-semibold text-indigo">{{ copy.generationInternalCorrectionTitle }}</p>
              <p class="mt-1 text-xs leading-5 text-ink/60">{{ copy.generationInternalCorrection }}</p>
            </div>
          </div>
          <section
            v-if="visualPageRuleGroupSummary.length"
            data-testid="recommendation-visual-rule-group-summary"
            class="mt-3 rounded-lg border border-indigo/15 bg-paper/80 px-3 py-2.5"
            :aria-label="copy.visualRuleGroupSummaryTitle"
          >
            <p class="text-xs font-semibold text-indigo">{{ copy.visualRuleGroupSummaryTitle }}</p>
            <p class="mt-1 text-xs leading-5 text-ink/55">{{ copy.visualRuleGroupSummaryHint }}</p>
            <dl class="mt-2 grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
              <div
                v-for="bucket in visualPageRuleGroupBuckets"
                :key="bucket.state"
                :data-rule-group-state="bucket.state"
                class="rounded-md border px-2.5 py-2"
                :class="bucket.state === 'directly-completed'
                  ? 'border-emerald-200 bg-emerald-50/70'
                  : bucket.state === 'completed-after-correction'
                    ? 'border-indigo/20 bg-indigo/5'
                    : bucket.state === 'processing'
                      ? 'border-copper/20 bg-copper/5'
                      : 'border-amber-200 bg-amber-50/70'"
              >
                <dt class="text-[11px] font-semibold leading-4 text-ink/65">{{ copy.visualRuleGroupStatus[bucket.state] }}</dt>
                <dd class="mt-1 text-xs leading-5 text-ink/55">
                  <span class="font-semibold text-ink/75">{{ copy.visualRuleGroupCount(bucket.pages.length) }}</span>
                  <span v-if="bucket.pages.length"> · {{ copy.visualRuleGroupPages(bucket.pages) }}</span>
                </dd>
              </div>
            </dl>
          </section>
          <p class="mt-3 text-[11px] font-bold uppercase tracking-[0.08em] text-ink/45">{{ copy.generationLatest }}</p>
          <p
            v-if="hasUnsuccessfulTeachingAttempt"
            data-testid="recommendation-teaching-attempt-marker-hint"
            class="mt-1 text-xs leading-5 text-ink/55"
          >
            {{ copy.generationAttemptMarkerHint }}
          </p>
          <p data-testid="recommendation-teaching-live-status" class="sr-only" aria-live="polite" aria-atomic="true">{{ visibleJourneyTeachingSteps[0]?.text }}</p>
          <ol data-testid="recommendation-teaching-activity-list" class="mt-2 grid max-h-72 gap-2 overflow-y-auto pr-1 sm:grid-cols-2">
            <li
              v-for="step in visibleJourneyTeachingSteps"
              :key="step.key"
              class="flex items-start gap-2 text-xs leading-5 text-ink/65"
            >
              <span
                class="mt-0.5 grid size-4 shrink-0 place-items-center rounded-full text-[10px] font-bold text-white"
                :class="step.outcome === 'RUNNING' ? 'animate-pulse bg-copper' : step.outcome === 'SUCCEEDED' ? 'bg-emerald-600' : 'bg-amber-600'"
                aria-hidden="true"
              >{{ step.outcome === 'SUCCEEDED' ? '✓' : step.outcome === 'RUNNING' ? '●' : step.outcome === 'UNKNOWN' ? '?' : '!' }}</span>
              <span>{{ step.text }}</span>
            </li>
          </ol>
        </section>
        <p class="mt-4 rounded-xl border border-indigo/10 bg-indigo/5 px-4 py-3 text-xs leading-5 text-ink/60">{{ safeCloseDetail }}</p>
        <p v-if="pollingWarning" class="mt-3 rounded-lg bg-amber-50 px-4 py-3 text-xs leading-5 text-amber-900" role="status">{{ copy.pollingWarning }}</p>
        <p v-if="retryFailure" data-testid="recommendation-retry-failure" class="mt-3 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-xs leading-5 text-red-900" role="alert">{{ copy.retryFailed }}</p>
        <p v-if="stopFailure" data-testid="recommendation-stop-failure" class="mt-3 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-xs leading-5 text-red-900" role="alert">{{ copy.stopFailed }}</p>
        <div v-if="projection.canReadRulebook" class="mt-4 rounded-xl border border-indigo/15 bg-indigo/5 p-4 text-sm leading-6 text-ink/65">
          <p>{{ projection.canReadLesson ? copy.rulebookAvailable : copy.rulebookReady }}</p>
          <button type="button" class="mt-3 min-h-11 rounded-lg border border-indigo/25 px-4 font-semibold text-indigo" @click="emit('open-rulebook', journeyStatus)">{{ copy.readRulebook }}</button>
        </div>
        <div v-if="projection.canReadLesson" class="mt-4 rounded-xl border border-emerald-200 bg-emerald-50 p-4 text-sm leading-6 text-emerald-900">
          <p>{{ projection.state === 'complete' ? copy.complete : copy.readable }}</p>
          <div class="mt-3 flex flex-wrap gap-3">
            <button type="button" class="min-h-11 rounded-lg bg-indigo px-4 font-semibold text-white" @click="emit('open-lesson', journeyStatus)">{{ copy.readLesson }}</button>
            <button type="button" class="min-h-11 rounded-lg border border-indigo/25 px-4 font-semibold text-indigo" @click="emit('ask-questions', journeyStatus)">{{ copy.askQuestions }}</button>
          </div>
        </div>
        <div class="mt-4 flex flex-wrap gap-x-5 gap-y-2">
          <button v-if="activeGenerationRunId && !generationStoppedByPlayer" type="button" :disabled="retrying" class="min-h-11 text-sm font-semibold text-amber-700 underline disabled:opacity-40" @click="stopGeneration">{{ copy.stop }}</button>
          <button v-if="generationStoppedByPlayer" type="button" :disabled="retrying" class="min-h-11 text-sm font-semibold text-indigo underline disabled:opacity-40" @click="restartGeneration">{{ copy.restart }}</button>
          <button ref="deleteTrigger" type="button" :disabled="deleting" class="min-h-11 text-sm font-semibold text-red-700 underline disabled:opacity-40" @click="openDeleteConfirmation">{{ copy.remove }}</button>
          <RouterLink to="/catalog" class="inline-flex min-h-11 items-center text-sm font-semibold text-indigo underline">{{ copy.catalog }} →</RouterLink>
          <button type="button" class="min-h-11 text-sm font-semibold text-ink/50 underline" @click="emit('change')">{{ copy.change }}</button>
        </div>
        <div v-if="deleteConfirmOpen" class="mt-3 rounded-xl border border-red-200 bg-red-50 p-4 text-sm leading-6 text-red-900" role="alertdialog" aria-modal="true" :aria-label="copy.remove" :aria-describedby="`recommendation-delete-confirm-${game.bggId}`">
          <p :id="`recommendation-delete-confirm-${game.bggId}`">{{ copy.removeConfirm }}</p>
          <div class="mt-3 flex flex-wrap gap-3">
            <button type="button" :disabled="deleting" class="min-h-11 rounded-lg bg-red-700 px-4 font-semibold text-white disabled:opacity-40" @click="deleteTeachingJourney">{{ copy.removeYes }}</button>
            <button ref="deleteCancel" type="button" :disabled="deleting" class="min-h-11 rounded-lg border border-red-300 px-4 font-semibold disabled:opacity-40" @click="closeDeleteConfirmation">{{ copy.removeNo }}</button>
          </div>
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
