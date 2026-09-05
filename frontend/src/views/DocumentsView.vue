<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import DestructiveActionDialog from '@/components/DestructiveActionDialog.vue'
import RulebookDocumentList from '@/components/documents/RulebookDocumentList.vue'
import RulebookSourceImportPanel from '@/components/documents/RulebookSourceImportPanel.vue'
import RulebookStatusCard from '@/components/documents/RulebookStatusCard.vue'
import RulebookUploadPanel from '@/components/documents/RulebookUploadPanel.vue'
import type {
  BggSuggestion,
  BggSuggestionState,
  DocumentResponse,
  GameResponse,
  OfficialImportCopy,
  OfficialRulebookImportJob,
  PhotographedPage,
  RulebookCandidate,
  RulebookDiscoveryIdentity,
  RulebookDiscoveryCopy,
  RulebookDiscoveryStatus,
  RulebookDiscoverySummary,
} from '@/components/documents/types'
import { notifyLoginRequired } from '@/lib/authSession'
import {
  mergeDocumentProgress,
  parseDocumentProgressSnapshot,
  type DocumentProcessingSnapshot,
} from '@/lib/documentProgress'
import { useLocale } from '@/lib/locale'
import { playerFacingLanguageName } from '@/lib/playerFacingLanguage'
import {
  monotonicElapsedSeconds,
  normalizeRulebookDiscoverySummary,
} from '@/lib/rulebookDiscovery'
import { notifyTeachingLaunched, type TeachingLaunch } from '@/lib/teachingLaunch'

interface CsrfResponse { headerName: string; token: string }
interface BggLinkResponse { alreadyImported: boolean }
interface RulebookCandidateResponse {
  configured: boolean
  identity: RulebookDiscoveryIdentity
  candidates: RulebookCandidate[]
  discovery?: unknown
}
interface RulebookIdentityProblem { code?: string }
interface TeachingPlanResponse { id: string; documentVersionId: string }
interface TeachingPreparationLaunch { assistantRunId: string; state: string; reused: boolean }
interface TeachingPreparationRun {
  run: { id: string; subjectId: string; state: string; lastErrorCode: string | null }
  activities?: Array<{
    sequence: number
    operation: string
    outcome: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'REJECTED'
  }>
}
type ProcessingSnapshot = DocumentProcessingSnapshot
interface ModelConfigurationResponse {
  providers: Array<{ id: string; configured: boolean; visionCapable: boolean }>
  assignments: { teaching: string; visual: string }
}
interface RulebookIntakeSnapshot {
  editionId: string
  learningGoal: string
  officialImportIdentityConfirmed: boolean
  officialImportRightsConfirmed: boolean
  officialSourceUrl: string
  sourceType: string
  title: string
}

interface LessonPreparation {
  versionId: string
  learningGoal?: string
}

interface RulebookUploadPanelHandle {
  clearSelectedFileInput: () => void
  focusOfficialSource: () => void
  openLocalFilePicker: () => void
  openOfficialDetails: () => void
}

interface RulebookDocumentListHandle {
  focusTarget: () => HTMLElement | null
}

class PreparationFailedError extends Error {}

const router = useRouter()
const route = useRoute()
const { locale, t } = useLocale()
const username = ref('')
const games = ref<GameResponse[]>([])
const editionId = ref('')
const documents = ref<DocumentResponse[]>([])
const bggSuggestionStates = ref<Record<string, BggSuggestionState>>({})
const rulebookCandidates = ref<RulebookCandidate[]>([])
const rulebookDiscoveryIdentity = ref<RulebookDiscoveryIdentity | null>(null)
const selectedRulebookCandidate = ref<RulebookCandidate | null>(null)
const selectedRulebookDiscoveryIdentity = ref<RulebookDiscoveryIdentity | null>(null)
const rulebookDiscoveryStatus = ref<RulebookDiscoveryStatus>('idle')
const rulebookDiscoveryElapsedSeconds = ref(0)
const rulebookDiscoverySummary = ref<RulebookDiscoverySummary | null>(null)
const uploadPanel = ref<RulebookUploadPanelHandle | null>(null)
const file = ref<File | null>(null)
const photographedPages = ref<PhotographedPage[]>([])
const preparingPhotos = ref(false)
const title = ref('')
const officialSourceUrl = ref('')
const officialImportRightsConfirmed = ref(false)
const officialImportIdentityConfirmed = ref(false)
const sourceType = ref('BASE_RULEBOOK')
const learningGoal = ref('')
const loading = ref(true)
const uploading = ref(false)
const importingOfficial = ref(false)
const retryingOfficialImport = ref(false)
const officialImportJob = ref<OfficialRulebookImportJob | null>(null)
const deletingDocumentId = ref('')
const documentToDelete = ref<DocumentResponse | null>(null)
const deleteError = ref('')
const documentList = ref<RulebookDocumentListHandle | null>(null)
const restoreAfterDocumentDelete = ref(false)
const intakeReady = ref(false)
const intakeBaseline = ref<RulebookIntakeSnapshot>({
  editionId: '', learningGoal: '', officialImportIdentityConfirmed: false, officialImportRightsConfirmed: false,
  officialSourceUrl: '', sourceType: 'BASE_RULEBOOK', title: '',
})
const launchingTeaching = ref(false)
const navigationDialogOpen = ref(false)
let resolvePendingNavigation: ((allow: boolean) => void) | null = null
const deleteCopy = computed(() => locale.value === 'zh-CN' ? {
  title: '删除这本规则书？',
  description: (name: string) => `“${name}”及其本地页面图片和由它生成的讲解都会被删除，无法恢复。`,
  cancel: '保留规则书', confirm: '删除规则书', retry: '重新尝试删除',
} : {
  title: 'Delete this rulebook?',
  description: (name: string) => `“${name}”, its local page images, and the guides created from it will all be deleted. This cannot be undone.`,
  cancel: 'Keep rulebook', confirm: 'Delete rulebook', retry: 'Try deletion again',
})
const intakeDraftCopy = computed(() => locale.value === 'zh-CN' ? {
  status: (areas: string) => `尚未提交：${areas}`,
  memoryOnly: 'PDF、照片和这些输入只保留在当前页面；交给后台后才能安全离开。',
  pdf: (name: string) => `PDF“${name}”`,
  photos: (count: number) => `${count} 页照片`,
  details: '标题与资料类型', source: '来源与授权', game: '关联游戏', goal: '讲解目标',
  leaveTitle: '放弃这次规则书草稿并离开？',
  leaveDescription: (areas: string) => `${areas}还没有交给 RulePilot。离开会清除当前页面中的文件、照片和输入，无法恢复；已在后台运行的其他任务不受影响。`,
  pendingTitle: '正在完成规则书交接',
  pendingDescription: '照片整理或服务器接收完成前暂时不能离开。接收成功且没有其他草稿时，会自动前往刚才选择的页面；失败会留在这里供你重试。',
  stay: '继续准备', leave: '放弃草稿并离开', pending: '正在交接…',
} : {
  status: (areas: string) => `Not submitted: ${areas}`,
  memoryOnly: 'The PDF, photos, and these inputs stay only on this page until they are handed to background work.',
  pdf: (name: string) => `PDF “${name}”`,
  photos: (count: number) => `${count} photographed pages`,
  details: 'title and document type', source: 'source and permission', game: 'linked game', goal: 'teaching goal',
  leaveTitle: 'Discard this rulebook draft and leave?',
  leaveDescription: (areas: string) => `${areas} have not been handed to RulePilot. Leaving clears the files, photos, and inputs held on this page, and they cannot be recovered. Other background work is not affected.`,
  pendingTitle: 'Finishing the rulebook handoff',
  pendingDescription: 'You cannot leave until photo preparation or server acceptance finishes. If acceptance succeeds and no other draft remains, the page you chose opens automatically; a failure keeps you here to retry.',
  stay: 'Keep preparing', leave: 'Discard draft and leave', pending: 'Finishing handoff…',
})
const preparingVersionId = ref('')
const preparationElapsedSeconds = ref(0)
const processingVersionId = ref('')
const message = ref('')
const errorMessage = ref('')
const progress = ref<Record<string, ProcessingSnapshot>>({})
const modelConfiguration = ref<ModelConfigurationResponse | null>(null)
const progressConnections = new Map<string, EventSource>()
const progressRetryTimers = new Map<string, ReturnType<typeof setTimeout>>()
const progressRetryAttempts = new Map<string, number>()
const terminalHandoffs = new Set<string>()
const serverTeachingVersions = new Set<string>()
const progressGenerations = new Map<string, number>()
const progressReconcileControllers = new Map<string, AbortController>()
let disposed = false
let shellIdentityResolved = false
let identityGeneration = 0
let latestInitialLoad = 0
let activeInitialController: AbortController | null = null
let initialResourcesReady = false
let recoveredContextKey = ''
let officialImportPollGeneration = 0
let officialImportScopeJobId = ''
let routeImportTransitionJobId: string | null = null
let activeOfficialImportController: AbortController | null = null
let officialImportPollTimer: ReturnType<typeof setTimeout> | null = null
let resolveOfficialImportDelay: ((current: boolean) => void) | null = null
let preparationPollGeneration = 0
let activePreparationController: AbortController | null = null
let preparationPollTimer: ReturnType<typeof setTimeout> | null = null
let resolvePreparationDelay: ((current: boolean) => void) | null = null
let preparationClock: ReturnType<typeof setInterval> | null = null
let rulebookDiscoveryRequest = 0
let rulebookDiscoveryClock: ReturnType<typeof setInterval> | null = null
let rulebookDiscoveryStartedAt: number | null = null
let photographedPageSequence = 0

const editionOptions = computed(() => games.value.flatMap((entry) => entry.editions.map((edition) => ({
  id: edition.id,
  label: `${entry.game.name} · ${edition.name} · ${playerFacingLanguageName(edition.language, locale.value)}`,
}))))
const routeImportJobId = computed(() => typeof route.query.importJob === 'string' ? route.query.importJob : '')
const selectedEditionContext = computed(() => {
  for (const entry of games.value) {
    const edition = entry.editions.find(candidate => candidate.id === editionId.value)
    if (edition) return { game: entry.game, edition, bggMetadata: entry.bggMetadata ?? null }
  }
  return null
})
const officialImportIdentityTarget = computed<RulebookDiscoveryIdentity | null>(() => {
  const selected = selectedEditionContext.value
  return selected ? {
    editionId: selected.edition.id,
    gameName: selected.game.name,
    editionName: selected.edition.name,
    language: selected.edition.language,
  } : null
})
const selectedOfficialCandidate = computed(() => {
  const selected = selectedRulebookCandidate.value
  return selected?.url === officialSourceUrl.value.trim() ? selected : null
})
const officialImportSourceIdentity = computed(() => {
  const candidate = selectedOfficialCandidate.value
  return candidate ? {
    edition: candidate.edition,
    language: candidate.language,
    languageVerified: candidate.languageVerified === true,
  } : null
})
const officialImportDiscoveryIdentity = computed(() => (
  selectedOfficialCandidate.value ? selectedRulebookDiscoveryIdentity.value : null
))
const rulebookDiscoveryCopy = computed<RulebookDiscoveryCopy>(() => locale.value === 'zh-CN' ? {
  action: '帮我找规则书', loading: '正在查找多个可信来源…', title: '找到这些规则书来源',
  elapsed: (seconds: number) => `已等待 ${seconds} 秒`,
  detail: '会优先找出版社，也会补查 BGG、集石与可信规则库。来源页会在新窗口打开；PDF 直链和已识别的连续规则页图片都可以在确认后导入。',
  unavailable: '当前没有找到可审阅的规则书来源。你仍可继续查找、粘贴公开 PDF 链接或上传本地文件。',
  empty: '没有找到可信的规则书来源。请改用公开链接或本地上传。',
  error: '规则书搜索暂时不可用，手动入口仍可使用。',
  retrySearch: '继续查找',
  terminalTiming: (elapsed: number, budget: number) => `本次查找用时 ${elapsed} 秒，最长等待 ${budget} 秒。`,
  terminal: {
    PARTIAL: '部分来源未在本次预算内完成；下面只保留已经核验的结果。',
    TIMED_OUT: '本次查找已到达最长等待时间，尚未找到可审阅结果。',
    FAILED: '部分来源没有完成查找，尚未找到可审阅结果。',
  },
  providers: { CATALOG: '规则书目录', SOURCE_INSPECTION: '来源核验', WEB_SEARCH: '联网搜索' },
  providerStates: { FINISHED: '已完成', TIMED_OUT: '已超时', FAILED: '失败', SKIPPED: '未使用', UNAVAILABLE: '未配置' },
  sources: { PUBLISHER: '出版社 / 权利方来源', TRUSTED_REPOSITORY: '可信规则库', COMMUNITY_PLATFORM: '社区规则书来源（如 BGG / 集石）', PUBLIC_WEB: '公开来源（请重点核对）' },
  capabilities: { DIRECT_DOCUMENT: '已核验为可下载文档', CONTIGUOUS_RULE_PAGES: '已核验为连续规则页', DOCUMENT_LISTING: '仅确认是文档列表页', GAME_INFO_ONLY: '仅有桌游信息，没有规则书文件', UNVERIFIED_PAGE: '尚未核验出可导入文档' },
  noImportableTitle: '暂未找到可直接导入的规则书', noImportableDetail: '这些结果只能用于继续查找或核对桌游信息；你也可以直接上传本地规则书。',
  identityOnlyTitle: '仅用于核对桌游身份', identityOnlyDetail: '这些页面没有可导入的规则书文件，不属于规则书选择。',
  direct: 'PDF 可直接核验并下载', gallery: '连续规则页图片，可合成为 PDF', page: '来源页，需要继续查找文件', use: '选择并继续核对',
  continueListing: '继续查找文件', reviewUnverified: '审阅来源页', localUpload: '上传本地规则书',
  publisher: '发布者', language: '语言', languageVerified: '来源已明确标注', languageReview: '需在来源页核对', edition: '版本',
  searchSteps: ['核对 BGG 身份与版本', '搜索出版社、发行方与本地化方', '补查 BGG、集石和可信规则库'],
} : {
  action: 'Find a rulebook', loading: 'Searching multiple trusted sources…', title: 'Rulebook sources found',
  elapsed: (seconds: number) => `${seconds}s elapsed`,
  detail: 'Publisher sources come first, followed by BGG, Gstone, and trusted repositories. Source pages open separately; direct PDFs and recognized ordered page-image documents can be imported after confirmation.',
  unavailable: 'No reviewable rulebook source was found. Search again, paste a public PDF URL, or upload a local file.',
  empty: 'No credible rulebook source was found. Use a public URL or local upload instead.',
  error: 'Rulebook search is temporarily unavailable. Manual options still work.',
  retrySearch: 'Search again',
  terminalTiming: (elapsed: number, budget: number) => `Search finished in ${elapsed}s with a ${budget}s maximum budget.`,
  terminal: {
    PARTIAL: 'Some sources did not finish within this search budget. Only verified results are shown below.',
    TIMED_OUT: 'This search reached its time budget without a reviewable result.',
    FAILED: 'Some source checks failed and no reviewable result is available yet.',
  },
  providers: { CATALOG: 'Rulebook catalog', SOURCE_INSPECTION: 'Source verification', WEB_SEARCH: 'Web search' },
  providerStates: { FINISHED: 'finished', TIMED_OUT: 'timed out', FAILED: 'failed', SKIPPED: 'not needed', UNAVAILABLE: 'not configured' },
  sources: { PUBLISHER: 'Publisher / rights-holder', TRUSTED_REPOSITORY: 'Trusted rules repository', COMMUNITY_PLATFORM: 'Community rulebook source (such as BGG / Gstone)', PUBLIC_WEB: 'Public source (review carefully)' },
  capabilities: { DIRECT_DOCUMENT: 'Confirmed downloadable document', CONTIGUOUS_RULE_PAGES: 'Confirmed ordered rule pages', DOCUMENT_LISTING: 'Document listing only', GAME_INFO_ONLY: 'Game information only; no rulebook file', UNVERIFIED_PAGE: 'No importable document verified' },
  noImportableTitle: 'No directly importable rulebook yet', noImportableDetail: 'These results can only continue the search or confirm game identity. You can also upload a local rulebook.',
  identityOnlyTitle: 'Game identity references only', identityOnlyDetail: 'These pages do not contain an importable rulebook and are not rulebook choices.',
  direct: 'Direct PDF ready for verification', gallery: 'Ordered rulebook pages; RulePilot can build the PDF', page: 'Source page; continue there', use: 'Choose and review',
  continueListing: 'Continue finding a file', reviewUnverified: 'Review source page', localUpload: 'Upload a local rulebook',
  publisher: 'Provider', language: 'Language', languageVerified: 'stated by the source', languageReview: 'verify on the source page', edition: 'Edition',
  searchSteps: ['Verify BGG identity and edition', 'Search publishers, distributors, and localizers', 'Check BGG, Gstone, and trusted repositories'],
})
const officialImportCopy = computed<OfficialImportCopy>(() => locale.value === 'zh-CN' ? {
  title: '规则书与讲解正在后台准备', safe: '可以离开这一页；下载、核验、规则书读取和讲解生成都会继续。',
  QUEUED: '等待下载', CONNECTING: '正在连接来源', DOWNLOADING: '正在下载规则书内容',
  COMPRESSING: '文件超过普通导入上限，正在安全压缩 PDF',
  VERIFYING_FILE: '正在核验文件格式与大小', SAVING: '正在保存并交给规则书读取',
  COMPLETED: '下载完成，正在衔接规则书读取', FAILED: '下载失败，需要重新选择来源',
  WAITING_FOR_DOCUMENT: '规则书已下载，正在整理规则文字和原文页面', LAUNCHING: '规则书已可阅读，正在启动讲解',
  LAUNCHED: '讲解任务已经进入后台', TEACHING_FAILED: '规则书已可阅读，但讲解任务需要重试', DOCUMENT_FAILED: '规则书读取失败，讲解无法开始',
  background: '在任意页面打开“后台任务”都能找回这次进度。',
  failureTitle: '规则书导入需要处理',
  failureDetail: {
    NONE: '这次导入已经结束，请重新选择来源或改用本地文件。',
    TEMPORARY_SOURCE: '来源暂时无法连接。你可以重试原来源，也可以立即换来源或上传本地文件。',
    BROWSER_HANDOFF: '这个来源需要在浏览器里完成登录、隐私选择或下载；也可以改用本地文件。',
    INVALID_SOURCE: '下载的内容不是可安全导入的规则书文件。请选择真实 PDF、连续规则页或本地文件。',
    CAPACITY: '当前导入队列暂时已满。可以稍后重试原来源，或先改用本地文件。',
    INTERRUPTED: '应用重启中断了这次导入。可以重试原来源，也可以换来源或上传本地文件。',
    OTHER: '这次导入没有完成。请选择另一个来源或上传本地文件。',
  },
  chooseAnotherSource: '重新选择来源', useLocalUpload: '改用本地上传',
  retryOriginalSource: '重试原来源', openOriginalSource: '在来源网站继续',
} : {
  title: 'Preparing the rulebook and guide in the background', safe: 'You can leave this page; download, verification, reading, and guide generation will continue.',
  QUEUED: 'Waiting to download', CONNECTING: 'Connecting to source', DOWNLOADING: 'Downloading rulebook content',
  COMPRESSING: 'Compressing the PDF to the safe import limit',
  VERIFYING_FILE: 'Verifying file format and size', SAVING: 'Saving and handing off for reading',
  COMPLETED: 'Download complete; handing off to rulebook reading', FAILED: 'Download failed; choose another source',
  WAITING_FOR_DOCUMENT: 'Rulebook downloaded; organizing rule text and original pages', LAUNCHING: 'Rulebook readable; starting the guide',
  LAUNCHED: 'The guide task is now running in the background', TEACHING_FAILED: 'Rulebook readable, but the guide task needs a retry', DOCUMENT_FAILED: 'Rulebook reading failed, so the guide could not start',
  background: 'Open Background work from any page to return to this progress.',
  failureTitle: 'Rulebook import needs attention',
  failureDetail: {
    NONE: 'This import has ended. Choose another source or use a local file.',
    TEMPORARY_SOURCE: 'The source is temporarily unavailable. Retry it, choose another source, or upload a local file.',
    BROWSER_HANDOFF: 'This source requires an in-browser sign-in, privacy choice, or download. You can also use a local file.',
    INVALID_SOURCE: 'The downloaded content is not a safely importable rulebook. Choose a real PDF, ordered rule pages, or a local file.',
    CAPACITY: 'The import queue is temporarily full. Retry later or use a local file now.',
    INTERRUPTED: 'An application restart interrupted this import. Retry it, choose another source, or upload a local file.',
    OTHER: 'This import did not finish. Choose another source or upload a local file.',
  },
  chooseAnotherSource: 'Choose another source', useLocalUpload: 'Use local upload',
  retryOriginalSource: 'Retry original source', openOriginalSource: 'Continue on source site',
})
const officialImportIdentityErrorCopy = computed(() => locale.value === 'zh-CN' ? {
  changed: '提交前目录或来源身份发生了变化。请重新比较游戏、版本和语言后再次确认。',
  active: '这个链接正在为另一个版本导入。请等待那次导入结束后再试，或保留当前游戏与讲解目标并改用本地上传。',
} : {
  changed: 'The catalog or source identity changed before submission. Compare the game, edition, and language again, then reconfirm.',
  active: 'This URL is already being imported for another edition. Wait for that import to finish, or keep the current game and guide goal and use a local upload.',
})
const officialImportBusy = computed(() => {
  const job = officialImportJob.value
  if (!job) return false
  if (job.recovery) return job.recovery.busy
  if (job.stage === 'FAILED') return false
  return job.stage !== 'COMPLETED'
    || ['WAITING_FOR_DOCUMENT', 'LAUNCHING'].includes(job.teachingHandoffState)
})
const canUpload = computed(() => Boolean(
  (file.value || photographedPages.value.length)
  && !preparingPhotos.value
  && !uploading.value
  && !importingOfficial.value
  && !officialImportBusy.value
  && !preparingVersionId.value
  && intakeReady.value,
))
const canImportOfficial = computed(() => Boolean(
  officialSourceUrl.value.trim()
  && officialImportRightsConfirmed.value
  && (!editionId.value || officialImportIdentityConfirmed.value)
  && !uploading.value
  && !importingOfficial.value
  && !officialImportBusy.value
  && !preparingVersionId.value
  && intakeReady.value,
))
function currentIntakeSnapshot(): RulebookIntakeSnapshot {
  return {
    editionId: editionId.value,
    learningGoal: learningGoal.value,
    officialImportIdentityConfirmed: officialImportIdentityConfirmed.value,
    officialImportRightsConfirmed: officialImportRightsConfirmed.value,
    officialSourceUrl: officialSourceUrl.value,
    sourceType: sourceType.value,
    title: title.value,
  }
}

function resetIntakeBaseline() {
  intakeBaseline.value = currentIntakeSnapshot()
  intakeReady.value = true
}

const intakeDraftAreas = computed(() => {
  if (!intakeReady.value) return []
  const baseline = intakeBaseline.value
  return [
    ...(file.value ? [intakeDraftCopy.value.pdf(file.value.name)] : []),
    ...(photographedPages.value.length ? [intakeDraftCopy.value.photos(photographedPages.value.length)] : []),
    ...(title.value.trim() !== baseline.title.trim() || sourceType.value !== baseline.sourceType
      ? [intakeDraftCopy.value.details] : []),
    ...(officialSourceUrl.value.trim() !== baseline.officialSourceUrl.trim()
      || officialImportIdentityConfirmed.value !== baseline.officialImportIdentityConfirmed
      || officialImportRightsConfirmed.value !== baseline.officialImportRightsConfirmed
      ? [intakeDraftCopy.value.source] : []),
    ...(editionId.value !== baseline.editionId ? [intakeDraftCopy.value.game] : []),
    ...(learningGoal.value.trim() !== baseline.learningGoal.trim() ? [intakeDraftCopy.value.goal] : []),
  ]
})
const hasUnsavedIntake = computed(() => intakeDraftAreas.value.length > 0)
const intakeMutationPending = computed(() => (
  preparingPhotos.value || uploading.value || importingOfficial.value
  || retryingOfficialImport.value || launchingTeaching.value
))
const intakeControlsDisabled = computed(() => Boolean(
  loading.value || !intakeReady.value || intakeMutationPending.value || officialImportBusy.value || preparingVersionId.value,
))
const protectsIntakeNavigation = computed(() => hasUnsavedIntake.value || intakeMutationPending.value)
const navigationCopy = computed(() => intakeMutationPending.value ? {
  title: intakeDraftCopy.value.pendingTitle,
  description: intakeDraftCopy.value.pendingDescription,
} : {
  title: intakeDraftCopy.value.leaveTitle,
  description: intakeDraftCopy.value.leaveDescription(
    intakeDraftAreas.value.join(locale.value === 'zh-CN' ? '、' : ', ') || (
      locale.value === 'zh-CN' ? '这次规则书草稿' : 'This rulebook draft'
    ),
  ),
})
const visualProvider = computed(() => modelConfiguration.value?.providers.find(
  (provider) => provider.id === modelConfiguration.value?.assignments.visual,
))
const visualVisionCapable = computed(() => visualProvider.value?.visionCapable === true)

function bggSuggestionState(documentId: string) {
  return bggSuggestionStates.value[documentId]
}

async function loadBggSuggestions(documentId: string) {
  bggSuggestionStates.value = {
    ...bggSuggestionStates.value,
    [documentId]: {
      status: 'loading', candidates: [], selectedBggId: null, linkStatus: 'idle', linkAlreadyImported: false,
    },
  }
  try {
    const response = await checkedFetch(`/api/v1/documents/${encodeURIComponent(documentId)}/bgg-suggestions`)
    if (!response.ok) throw new Error(t('documents.bgg.error'))
    bggSuggestionStates.value = {
      ...bggSuggestionStates.value,
      [documentId]: {
        status: 'success',
        candidates: await response.json() as BggSuggestion[],
        selectedBggId: null,
        linkStatus: 'idle',
        linkAlreadyImported: false,
      },
    }
  } catch {
    bggSuggestionStates.value = {
      ...bggSuggestionStates.value,
      [documentId]: {
        status: 'error', candidates: [], selectedBggId: null, linkStatus: 'idle', linkAlreadyImported: false,
      },
    }
  }
}

function selectBggSuggestion(documentId: string, bggId: number) {
  const state = bggSuggestionState(documentId)
  if (!state || state.status !== 'success') return
  bggSuggestionStates.value = {
    ...bggSuggestionStates.value,
    [documentId]: {
      ...state, selectedBggId: bggId, linkStatus: 'idle', linkAlreadyImported: false,
    },
  }
}

async function confirmBggSuggestion(documentId: string) {
  const state = bggSuggestionState(documentId)
  if (!state?.selectedBggId || state.linkStatus === 'confirming') return
  bggSuggestionStates.value = {
    ...bggSuggestionStates.value,
    [documentId]: { ...state, linkStatus: 'confirming' },
  }
  try {
    const csrf = await csrfToken()
    const response = await checkedFetch(`/api/v1/documents/${encodeURIComponent(documentId)}/bgg-link`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({ bggId: state.selectedBggId }),
    })
    if (!response.ok) throw new Error(t('documents.bgg.linkError'))
    const linked = await response.json() as BggLinkResponse
    const [documentResponse, catalogResponse] = await Promise.all([
      checkedFetch('/api/v1/documents'),
      checkedFetch('/api/v1/games'),
    ])
    if (documentResponse.ok) documents.value = await documentResponse.json() as DocumentResponse[]
    if (catalogResponse.ok) games.value = await catalogResponse.json() as GameResponse[]
    bggSuggestionStates.value = {
      ...bggSuggestionStates.value,
      [documentId]: {
        ...state,
        linkStatus: 'linked',
        linkAlreadyImported: linked.alreadyImported,
      },
    }
  } catch {
    bggSuggestionStates.value = {
      ...bggSuggestionStates.value,
      [documentId]: { ...state, linkStatus: 'error' },
    }
  }
}

function progressMessage(snapshot: ProcessingSnapshot) {
  if (snapshot.stage === 'EXTRACTING') return t('documents.progress.extracting')
  if (snapshot.stage === 'RENDERING' && snapshot.totalPages > 0) {
    return t('documents.progress.rendering', {
      current: snapshot.processedPages,
      total: snapshot.totalPages,
    })
  }
  if (snapshot.stage === 'STRUCTURING') return t('documents.progress.structuring')
  return t('documents.progress.reading', { percentage: snapshot.percentage })
}

async function checkedFetch(path: string, options?: Parameters<typeof fetch>[1]) {
  const response = await fetch(path, { credentials: 'include', ...options })
  if (response.status === 401) {
    notifyLoginRequired()
    throw new Error(t('documents.login'))
  }
  return response
}

async function csrfToken(signal?: AbortSignal) {
  const response = await checkedFetch('/api/auth/csrf', { signal })
  if (!response.ok) throw new Error(t('documents.error'))
  return await response.json() as CsrfResponse
}

async function fetchDocuments(signal?: AbortSignal) {
  const response = await checkedFetch('/api/v1/documents', { signal })
  if (!response.ok) throw new Error(t('documents.error'))
  const payload = await response.json() as unknown
  if (!Array.isArray(payload)) throw new Error(t('documents.error'))
  return payload as DocumentResponse[]
}

async function loadDocuments(signal?: AbortSignal) {
  const received = await fetchDocuments(signal)
  if (disposed || signal?.aborted) return received
  documents.value = received
  return received
}

async function discoverOfficialRulebooks() {
  if (!editionId.value) return
  const request = ++rulebookDiscoveryRequest
  const requestedEditionId = editionId.value
  rulebookDiscoveryStatus.value = 'loading'
  rulebookDiscoverySummary.value = null
  rulebookCandidates.value = []
  rulebookDiscoveryIdentity.value = null
  startRulebookDiscoveryClock()
  try {
    const parameters = new URLSearchParams({ editionId: requestedEditionId, language: locale.value })
    const response = await checkedFetch(`/api/v1/documents/rulebook-candidates?${parameters.toString()}`)
    if (!response.ok) throw new Error(rulebookDiscoveryCopy.value.error)
    const result = await response.json() as RulebookCandidateResponse
    if (request !== rulebookDiscoveryRequest
      || editionId.value !== requestedEditionId
      || result.identity?.editionId !== requestedEditionId) return
    rulebookDiscoveryIdentity.value = result.identity
    rulebookCandidates.value = result.candidates
    rulebookDiscoverySummary.value = normalizeRulebookDiscoverySummary(result.discovery)
    rulebookDiscoveryStatus.value = result.configured ? 'success' : 'unavailable'
  } catch (error) {
    if (request !== rulebookDiscoveryRequest) return
    errorMessage.value = error instanceof Error ? error.message : rulebookDiscoveryCopy.value.error
    rulebookDiscoveryStatus.value = 'error'
  } finally {
    if (request === rulebookDiscoveryRequest) stopRulebookDiscoveryClock(true)
  }
}

function startRulebookDiscoveryClock() {
  stopRulebookDiscoveryClock(false)
  rulebookDiscoveryElapsedSeconds.value = 0
  rulebookDiscoveryStartedAt = performance.now()
  rulebookDiscoveryClock = setInterval(updateRulebookDiscoveryElapsed, 1_000)
}

function updateRulebookDiscoveryElapsed() {
  if (rulebookDiscoveryStartedAt === null) return
  rulebookDiscoveryElapsedSeconds.value = monotonicElapsedSeconds(rulebookDiscoveryStartedAt)
}

function stopRulebookDiscoveryClock(updateElapsed: boolean) {
  if (updateElapsed) updateRulebookDiscoveryElapsed()
  if (rulebookDiscoveryClock !== null) clearInterval(rulebookDiscoveryClock)
  rulebookDiscoveryClock = null
  rulebookDiscoveryStartedAt = null
}

function chooseRulebookCandidate(candidate: RulebookCandidate) {
  if (intakeControlsDisabled.value) return
  if (candidate.capability === 'DOCUMENT_LISTING' || candidate.capability === 'UNVERIFIED_PAGE') {
    window.open(candidate.url, '_blank', 'noopener,noreferrer')
    return
  }
  if (candidate.capability === 'GAME_INFO_ONLY') return
  const importable = candidate.capability === 'DIRECT_DOCUMENT' && candidate.acquisitionMode === 'DIRECT_PDF'
    || candidate.capability === 'CONTIGUOUS_RULE_PAGES' && candidate.acquisitionMode === 'IMAGE_GALLERY'
  if (!importable) return
  officialSourceUrl.value = candidate.url
  if (!title.value.trim()) title.value = candidate.title
  selectedRulebookCandidate.value = candidate
  selectedRulebookDiscoveryIdentity.value = rulebookDiscoveryIdentity.value
  officialImportIdentityConfirmed.value = false
  officialImportRightsConfirmed.value = false
  uploadPanel.value?.openOfficialDetails()
}

async function load() {
  const request = ++latestInitialLoad
  activeInitialController?.abort()
  const controller = new AbortController()
  activeInitialController = controller
  loading.value = true
  errorMessage.value = ''
  initialResourcesReady = false
  intakeReady.value = false
  recoveredContextKey = ''
  try {
    const [catalogResponse, modelResponse, receivedDocuments] = await Promise.all([
      checkedFetch('/api/v1/games', { signal: controller.signal }),
      checkedFetch('/api/v1/model-configuration', { signal: controller.signal }),
      fetchDocuments(controller.signal),
    ])
    if (!catalogResponse.ok) throw new Error(t('documents.error'))
    const receivedGames = await catalogResponse.json() as unknown
    if (!Array.isArray(receivedGames)) throw new Error(t('documents.error'))
    const receivedModel = modelResponse.ok ? await modelResponse.json() as ModelConfigurationResponse : null
    if (!isCurrentInitialLoad(request, controller)) return
    games.value = receivedGames as GameResponse[]
    documents.value = receivedDocuments
    modelConfiguration.value = receivedModel
    const requestedEdition = typeof route.query.editionId === 'string' ? route.query.editionId : ''
    editionId.value = editionOptions.value.some((item) => item.id === requestedEdition) ? requestedEdition : ''
    initialResourcesReady = true
    void recoverCurrentContext(request)
  } catch (error) {
    if (!isCurrentInitialLoad(request, controller) || controller.signal.aborted) return
    errorMessage.value = error instanceof Error ? error.message : t('documents.error')
  } finally {
    if (isCurrentInitialLoad(request, controller)) {
      activeInitialController = null
      loading.value = false
      if (errorMessage.value && !intakeReady.value) resetIntakeBaseline()
    }
  }
}

function isCurrentInitialLoad(request: number, controller: AbortController) {
  return !disposed && request === latestInitialLoad && activeInitialController === controller
}

function updateSessionIdentity(nextUsername: string) {
  if (disposed) return
  const normalizedUsername = nextUsername.trim()
  const identityChanged = shellIdentityResolved && normalizedUsername !== username.value
  shellIdentityResolved = true
  if (!identityChanged && normalizedUsername === username.value) {
    void recoverCurrentContext(latestInitialLoad)
    return
  }

  identityGeneration++
  username.value = normalizedUsername
  recoveredContextKey = ''
  cancelOfficialImportPolling()
  cancelPreparationPolling()
  endPreparation()
  launchingTeaching.value = false
  cancelAllProgressWatches()
  progress.value = {}
  progressRetryAttempts.clear()
  terminalHandoffs.clear()
  serverTeachingVersions.clear()
  officialImportJob.value = null
  processingVersionId.value = ''
  if (identityChanged) {
    documents.value = []
    games.value = []
    modelConfiguration.value = null
    void load()
    return
  }
  void recoverCurrentContext(latestInitialLoad)
}

async function recoverCurrentContext(initialRequest: number) {
  if (disposed || !shellIdentityResolved || !initialResourcesReady || initialRequest !== latestInitialLoad) return
  const contextGeneration = identityGeneration
  const importJobId = routeImportJobId.value
  const contextKey = `${contextGeneration}:${initialRequest}:${username.value}:${importJobId}`
  if (recoveredContextKey === contextKey) return
  recoveredContextKey = contextKey
  try {
    if (username.value && importJobId) await recoverOfficialImport(importJobId, contextGeneration)
  } finally {
    if (isCurrentRecoveryContext(contextGeneration, initialRequest) && !intakeReady.value) resetIntakeBaseline()
  }
}

function isCurrentRecoveryContext(contextGeneration: number, initialRequest = latestInitialLoad) {
  return !disposed
    && contextGeneration === identityGeneration
    && initialRequest === latestInitialLoad
    && initialResourcesReady
    && shellIdentityResolved
}

function selectFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
  if (file.value) clearPhotographedPages()
  message.value = ''
  errorMessage.value = ''
}

function clearSelectedFile() {
  file.value = null
  uploadPanel.value?.clearSelectedFileInput()
}

async function addPhotographedPages(event: Event) {
  const input = event.target as HTMLInputElement
  const selected = [...(input.files ?? [])]
  input.value = ''
  if (!selected.length) return
  if (photographedPages.value.length + selected.length > 40) {
    errorMessage.value = t('documents.capture.tooMany')
    return
  }
  preparingPhotos.value = true
  message.value = ''
  errorMessage.value = ''
  try {
    const prepared = [] as File[]
    for (const photo of selected) prepared.push(await preparePhotographedPage(photo))
    const totalBytes = photographedPages.value.reduce((total, page) => total + page.file.size, 0)
      + prepared.reduce((total, page) => total + page.size, 0)
    if (totalBytes > 48 * 1024 * 1024) throw new Error(t('documents.capture.tooLarge'))
    clearSelectedFile()
    photographedPages.value = [...photographedPages.value, ...prepared.map((photo) => ({
      id: `photo-${Date.now()}-${photographedPageSequence++}`,
      file: photo,
      previewUrl: URL.createObjectURL(photo),
    }))]
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('documents.capture.unsupported')
  } finally {
    preparingPhotos.value = false
    settlePendingNavigation(false)
  }
}

async function preparePhotographedPage(photo: File): Promise<File> {
  if (!photo.type.startsWith('image/') || typeof createImageBitmap !== 'function') {
    throw new Error(t('documents.capture.unsupported'))
  }
  let bitmap: ImageBitmap
  try {
    bitmap = await createImageBitmap(photo, { imageOrientation: 'from-image' })
  } catch {
    throw new Error(t('documents.capture.unsupported'))
  }
  try {
    let maximumEdge = 3200
    let quality = 0.9
    for (let attempt = 0; attempt < 4; attempt += 1) {
      const scale = Math.min(1, maximumEdge / Math.max(bitmap.width, bitmap.height))
      const width = Math.max(1, Math.round(bitmap.width * scale))
      const height = Math.max(1, Math.round(bitmap.height * scale))
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const context = canvas.getContext('2d')
      if (!context) throw new Error(t('documents.capture.unsupported'))
      context.fillStyle = '#fff'
      context.fillRect(0, 0, width, height)
      context.drawImage(bitmap, 0, 0, width, height)
      const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality))
      if (blob && blob.size <= 8 * 1024 * 1024) {
        const filename = photo.name.replace(/\.[^.]+$/, '') || 'rulebook-page'
        return new File([blob], `${filename}.jpg`, { type: 'image/jpeg' })
      }
      maximumEdge = Math.round(maximumEdge * 0.85)
      quality -= 0.08
    }
    throw new Error(t('documents.capture.tooLarge'))
  } finally {
    bitmap.close()
  }
}

function removePhotographedPage(index: number) {
  if (intakeControlsDisabled.value) return
  const page = photographedPages.value[index]
  if (!page) return
  URL.revokeObjectURL(page.previewUrl)
  photographedPages.value = photographedPages.value.filter((_, currentIndex) => currentIndex !== index)
}

function movePhotographedPage(index: number, direction: -1 | 1) {
  if (intakeControlsDisabled.value) return
  const destination = index + direction
  if (destination < 0 || destination >= photographedPages.value.length) return
  const pages = [...photographedPages.value]
  const [page] = pages.splice(index, 1)
  if (!page) return
  pages.splice(destination, 0, page)
  photographedPages.value = pages
}

function clearPhotographedPages() {
  for (const page of photographedPages.value) URL.revokeObjectURL(page.previewUrl)
  photographedPages.value = []
}

function discardIntakeDraft() {
  clearSelectedFile()
  clearPhotographedPages()
  const baseline = intakeBaseline.value
  editionId.value = baseline.editionId
  learningGoal.value = baseline.learningGoal
  officialImportIdentityConfirmed.value = baseline.officialImportIdentityConfirmed
  officialImportRightsConfirmed.value = baseline.officialImportRightsConfirmed
  officialSourceUrl.value = baseline.officialSourceUrl
  sourceType.value = baseline.sourceType
  title.value = baseline.title
  message.value = ''
  errorMessage.value = ''
}

function takePendingNavigationResolution() {
  const resolution = resolvePendingNavigation
  resolvePendingNavigation = null
  return resolution
}

function cancelPendingNavigation() {
  if (intakeMutationPending.value) return
  navigationDialogOpen.value = false
  takePendingNavigationResolution()?.(false)
}

function discardDraftAndLeave() {
  if (intakeMutationPending.value) return
  discardIntakeDraft()
  navigationDialogOpen.value = false
  takePendingNavigationResolution()?.(true)
}

function settlePendingNavigation(mutationAccepted: boolean) {
  if (!navigationDialogOpen.value || intakeMutationPending.value) return
  if (!mutationAccepted) {
    cancelPendingNavigation()
    return
  }
  if (hasUnsavedIntake.value) {
    cancelPendingNavigation()
    return
  }
  navigationDialogOpen.value = false
  takePendingNavigationResolution()?.(true)
}

function protectBrowserUnload(event: BeforeUnloadEvent) {
  if (!protectsIntakeNavigation.value) return
  event.preventDefault()
  event.returnValue = ''
}

const removeNavigationGuard = router.beforeEach((to, from) => {
  if (to.path === from.path || !protectsIntakeNavigation.value) return true
  if (navigationDialogOpen.value) takePendingNavigationResolution()?.(false)
  navigationDialogOpen.value = true
  return new Promise<boolean>((resolve) => {
    resolvePendingNavigation = resolve
  })
})

function titleFromFile(selected: File) {
  return selected.name.replace(/\.pdf$/i, '').replace(/[_-]+/g, ' ').trim() || t('documents.titleFallback')
}

function currentPreferences(versionId: string): LessonPreparation {
  return {
    versionId,
    ...(learningGoal.value.trim() ? { learningGoal: learningGoal.value.trim() } : {}),
  }
}

async function startLesson(versionId: string, preferences = currentPreferences(versionId)) {
  let accepted = false
  const requestIdentityGeneration = identityGeneration
  launchingTeaching.value = true
  const preparationGeneration = beginPreparation(versionId, 'RECEIVED')
  try {
    const csrfController = new AbortController()
    activePreparationController = csrfController
    const csrf = await csrfToken(csrfController.signal)
    if (!isCurrentPreparation(
      preparationGeneration, versionId, requestIdentityGeneration, csrfController,
    )) return
    activePreparationController = null
    const planResponse = await checkedFetch(`/api/v1/document-versions/${versionId}/teaching-plans`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({
        learningGoal: preferences.learningGoal ?? null,
      }),
    })
    if (!isCurrentPreparation(preparationGeneration, versionId, requestIdentityGeneration)) return
    if (!planResponse.ok) throw new Error(t('documents.error'))
    const launch = await planResponse.json() as TeachingPreparationLaunch
    if (!isCurrentPreparation(preparationGeneration, versionId, requestIdentityGeneration)
      || typeof launch.assistantRunId !== 'string' || !launch.assistantRunId) return
    accepted = true
    intakeBaseline.value = { ...intakeBaseline.value, learningGoal: learningGoal.value }
    const leavingAfterAcceptance = navigationDialogOpen.value && !hasUnsavedIntake.value
    launchingTeaching.value = false
    settlePendingNavigation(true)
    if (leavingAfterAcceptance) return
    await waitForTeachingPreparation(
      launch.assistantRunId, preferences, csrf, preparationGeneration, requestIdentityGeneration,
    )
  } finally {
    if (isCurrentPreparation(preparationGeneration, versionId, requestIdentityGeneration)) {
      endPreparation()
      launchingTeaching.value = false
      settlePendingNavigation(accepted)
    }
  }
}

function beginPreparation(versionId: string, state: string) {
  cancelPreparationPolling()
  const generation = preparationPollGeneration
  preparingVersionId.value = versionId
  preparationElapsedSeconds.value = 0
  updatePreparationMessage(state)
  if (preparationClock) clearInterval(preparationClock)
  preparationClock = setInterval(() => preparationElapsedSeconds.value += 1, 1000)
  return generation
}

function endPreparation() {
  cancelPreparationPolling()
  preparingVersionId.value = ''
  preparationElapsedSeconds.value = 0
  if (preparationClock) clearInterval(preparationClock)
  preparationClock = null
}

function updatePreparationMessage(state: string, activities: TeachingPreparationRun['activities'] = []) {
  const active = [...activities].reverse().find((activity) => activity.outcome === 'RUNNING')
    ?? activities.at(-1)
  if (active?.operation.startsWith('organizeTeachingOutline')) {
    message.value = t('documents.prepare.outline')
    return
  }
  message.value = {
    RECEIVED: t('documents.prepare.received'),
    DOCUMENT_READINESS: t('documents.prepare.readiness'),
    LESSON_PLANNING: t('documents.prepare.planning'),
    COMPLETED: t('documents.prepare.completed'),
  }[state] ?? t('documents.prepare.default')
}

function preparationElapsedLabel() {
  const seconds = preparationElapsedSeconds.value
  if (seconds < 60) return t('documents.elapsed.seconds', { seconds })
  const minutes = Math.floor(seconds / 60)
  return t('documents.elapsed.minutes', { minutes, seconds: String(seconds % 60).padStart(2, '0') })
}

async function waitForTeachingPreparation(
  runId: string,
  preferences: LessonPreparation,
  csrf: CsrfResponse,
  generation: number,
  requestIdentityGeneration: number,
  initial?: TeachingPreparationRun,
) {
  let snapshot = initial
  while (isCurrentPreparation(generation, preferences.versionId, requestIdentityGeneration)) {
    try {
      if (!snapshot) {
        const controller = new AbortController()
        activePreparationController = controller
        const response = await checkedFetch(`/api/v1/assistant-runs/${encodeURIComponent(runId)}`, {
          signal: controller.signal,
        })
        if (!isCurrentPreparation(generation, preferences.versionId, requestIdentityGeneration, controller)) return
        if (!response.ok) throw new Error(t('documents.error'))
        snapshot = await response.json() as TeachingPreparationRun
        if (!isCurrentPreparation(generation, preferences.versionId, requestIdentityGeneration, controller)) return
        activePreparationController = null
      }
      if (snapshot.run.id !== runId || snapshot.run.subjectId !== preferences.versionId) {
        throw new PreparationFailedError(t('documents.error'))
      }
      updatePreparationMessage(snapshot.run.state, snapshot.activities)
      if (snapshot.run.state === 'COMPLETED') {
        await openPreparedLesson(
          preferences, csrf, generation, requestIdentityGeneration,
        )
        return
      }
      if (snapshot.run.state === 'FAILED' || snapshot.run.state === 'DEGRADED') {
        throw new PreparationFailedError(t('documents.error'))
      }
    } catch (error) {
      if (error instanceof PreparationFailedError) throw error
      if (!isCurrentPreparation(generation, preferences.versionId, requestIdentityGeneration)) return
      message.value = t('documents.prepare.reconnect')
    }
    snapshot = undefined
    if (!await waitForPreparationDelay(generation, preferences.versionId, requestIdentityGeneration)) return
  }
}

async function openPreparedLesson(
  preferences: LessonPreparation,
  csrf: CsrfResponse,
  generation: number,
  requestIdentityGeneration: number,
) {
  const controller = new AbortController()
  activePreparationController = controller
  const latestResponse = await checkedFetch(
    `/api/v1/document-versions/${preferences.versionId}/teaching-plans/latest`,
    { signal: controller.signal },
  )
  if (!isCurrentPreparation(generation, preferences.versionId, requestIdentityGeneration, controller)) return
  if (!latestResponse.ok) throw new Error(t('documents.prepare.openLater'))
  const plan = await latestResponse.json() as TeachingPlanResponse
  if (!isCurrentPreparation(generation, preferences.versionId, requestIdentityGeneration, controller)) return
  if (!plan.id || plan.documentVersionId !== preferences.versionId) throw new PreparationFailedError(t('documents.error'))
  activePreparationController = null
  message.value = t('documents.prepare.started')
  const lessonResponse = await checkedFetch(`/api/v1/teaching-plans/${plan.id}/illustrated-lessons`, {
    method: 'POST', headers: { [csrf.headerName]: csrf.token },
  })
  if (!isCurrentPreparation(generation, preferences.versionId, requestIdentityGeneration)) return
  if (!lessonResponse.ok) throw new Error(t('documents.error'))
  const launch = await lessonResponse.json() as TeachingLaunch
  if (!isCurrentPreparation(generation, preferences.versionId, requestIdentityGeneration)
    || typeof launch.assistantRunId !== 'string' || !launch.assistantRunId) return
  notifyTeachingLaunched({ planId: plan.id, runId: launch.assistantRunId })
  localStorage.setItem('rulepilot:last-plan-id', plan.id)
  await router.push({ name: 'lessons', query: { started: plan.id, run: launch.assistantRunId } })
}

function isCurrentPreparation(
  generation: number,
  versionId: string,
  requestIdentityGeneration: number,
  controller?: AbortController,
) {
  return !disposed
    && generation === preparationPollGeneration
    && versionId === preparingVersionId.value
    && requestIdentityGeneration === identityGeneration
    && (!controller || activePreparationController === controller)
}

function waitForPreparationDelay(
  generation: number,
  versionId: string,
  requestIdentityGeneration: number,
) {
  return new Promise<boolean>((resolve) => {
    resolvePreparationDelay?.(false)
    resolvePreparationDelay = resolve
    preparationPollTimer = setTimeout(() => {
      preparationPollTimer = null
      resolvePreparationDelay = null
      resolve(isCurrentPreparation(generation, versionId, requestIdentityGeneration))
    }, 1200)
  })
}

function cancelPreparationPolling() {
  preparationPollGeneration++
  activePreparationController?.abort()
  activePreparationController = null
  if (preparationPollTimer) clearTimeout(preparationPollTimer)
  preparationPollTimer = null
  resolvePreparationDelay?.(false)
  resolvePreparationDelay = null
}

function closeProgressConnection(versionId: string) {
  progressConnections.get(versionId)?.close()
  progressConnections.delete(versionId)
  const timer = progressRetryTimers.get(versionId)
  if (timer) clearTimeout(timer)
  progressRetryTimers.delete(versionId)
}

function cancelProgressWatch(versionId: string) {
  progressGenerations.set(versionId, (progressGenerations.get(versionId) ?? 0) + 1)
  progressReconcileControllers.get(versionId)?.abort()
  progressReconcileControllers.delete(versionId)
  closeProgressConnection(versionId)
}

function cancelAllProgressWatches() {
  for (const versionId of new Set([
    ...progressConnections.keys(),
    ...progressRetryTimers.keys(),
    ...progressReconcileControllers.keys(),
    ...progressGenerations.keys(),
  ])) cancelProgressWatch(versionId)
}

function watchProgress(pending: LessonPreparation) {
  const versionId = pending.versionId
  cancelProgressWatch(versionId)
  if (disposed) return
  const generation = progressGenerations.get(versionId) ?? 0
  const requestIdentityGeneration = identityGeneration
  processingVersionId.value = versionId
  const events = new EventSource(`/api/v1/document-versions/${versionId}/progress`, { withCredentials: true })
  progressConnections.set(versionId, events)
  events.addEventListener('progress', (event) => {
    if (!isCurrentProgressWatch(versionId, generation, requestIdentityGeneration, events)) return
    const snapshot = parseProgressSnapshot((event as MessageEvent<string>).data)
    if (!snapshot) {
      events.close()
      progressConnections.delete(versionId)
      void reconcileProgressAfterDisconnect(pending, generation, requestIdentityGeneration)
      return
    }
    progressRetryAttempts.set(versionId, 0)
    const mergedSnapshot = mergeDocumentProgress(progress.value[versionId], snapshot)
    progress.value = { ...progress.value, [versionId]: mergedSnapshot }
    message.value = progressMessage(mergedSnapshot)
    if (mergedSnapshot.complete) {
      void handleTerminalProgress(pending, mergedSnapshot.stage, generation, requestIdentityGeneration)
    }
  })
  events.onerror = () => {
    if (!isCurrentProgressWatch(versionId, generation, requestIdentityGeneration, events)) return
    events.close()
    progressConnections.delete(versionId)
    void reconcileProgressAfterDisconnect(pending, generation, requestIdentityGeneration)
  }
}

function isCurrentProgressWatch(
  versionId: string,
  generation: number,
  requestIdentityGeneration: number,
  events?: EventSource,
) {
  return !disposed
    && generation === (progressGenerations.get(versionId) ?? 0)
    && requestIdentityGeneration === identityGeneration
    && (!events || progressConnections.get(versionId) === events)
}

async function handleTerminalProgress(
  pending: LessonPreparation,
  stage: string,
  generation = progressGenerations.get(pending.versionId) ?? 0,
  requestIdentityGeneration = identityGeneration,
  reconciledDocuments?: DocumentResponse[],
) {
  if (!isCurrentProgressWatch(pending.versionId, generation, requestIdentityGeneration)) return
  if (terminalHandoffs.has(pending.versionId)) return
  terminalHandoffs.add(pending.versionId)
  closeProgressConnection(pending.versionId)
  progressRetryAttempts.delete(pending.versionId)
  processingVersionId.value = ''
  if (reconciledDocuments) {
    documents.value = reconciledDocuments
  } else {
    const controller = new AbortController()
    progressReconcileControllers.set(pending.versionId, controller)
    try {
      const receivedDocuments = await fetchDocuments(controller.signal)
      if (!isCurrentProgressReconciliation(
        pending.versionId, generation, requestIdentityGeneration, controller,
      )) return
      documents.value = receivedDocuments
    } catch {
      if (!isCurrentProgressReconciliation(
        pending.versionId, generation, requestIdentityGeneration, controller,
      ) || controller.signal.aborted) return
    } finally {
      if (progressReconcileControllers.get(pending.versionId) === controller) {
        progressReconcileControllers.delete(pending.versionId)
      }
    }
  }
  if (!isCurrentProgressWatch(pending.versionId, generation, requestIdentityGeneration)) return
  if (stage === 'READY') {
    if (serverTeachingVersions.delete(pending.versionId)) {
      message.value = t('documents.background')
      return
    }
    if (pending.learningGoal) learningGoal.value = pending.learningGoal
    try {
      await startLesson(pending.versionId, pending)
    } catch (error) {
      if (!isCurrentProgressWatch(pending.versionId, generation, requestIdentityGeneration)) return
      terminalHandoffs.delete(pending.versionId)
      errorMessage.value = error instanceof Error ? error.message : t('documents.error')
    }
    return
  }
  errorMessage.value = t('documents.progress.failed')
}

function isCurrentProgressReconciliation(
  versionId: string,
  generation: number,
  requestIdentityGeneration: number,
  controller: AbortController,
) {
  return isCurrentProgressWatch(versionId, generation, requestIdentityGeneration)
    && progressReconcileControllers.get(versionId) === controller
}

async function reconcileProgressAfterDisconnect(
  pending: LessonPreparation,
  generation: number,
  requestIdentityGeneration: number,
) {
  if (!isCurrentProgressWatch(pending.versionId, generation, requestIdentityGeneration)) return
  progressReconcileControllers.get(pending.versionId)?.abort()
  const controller = new AbortController()
  progressReconcileControllers.set(pending.versionId, controller)
  try {
    const receivedDocuments = await fetchDocuments(controller.signal)
    if (!isCurrentProgressReconciliation(
      pending.versionId, generation, requestIdentityGeneration, controller,
    )) return
    documents.value = receivedDocuments
    const status = receivedDocuments.find((entry) => entry.latestVersion.id === pending.versionId)?.latestVersion.status
    if (status === 'READY' || status === 'FAILED') {
      progressReconcileControllers.delete(pending.versionId)
      await handleTerminalProgress(
        pending, status, generation, requestIdentityGeneration, receivedDocuments,
      )
      return
    }
    if (!status) {
      processingVersionId.value = ''
      errorMessage.value = t('documents.progress.missing')
      return
    }
    message.value = t('documents.progress.reconnect')
  } catch {
    if (!isCurrentProgressReconciliation(
      pending.versionId, generation, requestIdentityGeneration, controller,
    ) || controller.signal.aborted) return
    message.value = t('documents.progress.reconnect')
  } finally {
    if (progressReconcileControllers.get(pending.versionId) === controller) {
      progressReconcileControllers.delete(pending.versionId)
    }
  }
  scheduleProgressReconnect(pending, generation, requestIdentityGeneration)
}

function scheduleProgressReconnect(
  pending: LessonPreparation,
  generation: number,
  requestIdentityGeneration: number,
) {
  if (!isCurrentProgressWatch(pending.versionId, generation, requestIdentityGeneration)
    || progressRetryTimers.has(pending.versionId)) return
  const attempt = Math.min((progressRetryAttempts.get(pending.versionId) ?? 0) + 1, 4)
  progressRetryAttempts.set(pending.versionId, attempt)
  const delay = [1000, 2000, 5000, 10000][attempt - 1]!
  progressRetryTimers.set(pending.versionId, setTimeout(() => {
    progressRetryTimers.delete(pending.versionId)
    if (!isCurrentProgressWatch(pending.versionId, generation, requestIdentityGeneration)) return
    watchProgress(pending)
  }, delay))
}

function parseProgressSnapshot(value: string): ProcessingSnapshot | null {
  try {
    return parseDocumentProgressSnapshot(JSON.parse(value))
  } catch {
    return null
  }
}

async function uploadRulebook() {
  if (!file.value && photographedPages.value.length === 0) return
  let accepted = false
  uploading.value = true
  message.value = t('documents.uploading')
  errorMessage.value = ''
  try {
    await releaseOfficialImportScope()
    message.value = t('documents.uploading')
    const selectedFile = file.value
    const selectedPhotos = [...photographedPages.value]
    const csrf = await csrfToken()
    const form = new FormData()
    if (title.value.trim()) form.append('title', title.value.trim())
    else if (selectedFile) form.append('title', titleFromFile(selectedFile))
    form.append('sourceType', sourceType.value)
    form.append('startTeaching', 'true')
    if (learningGoal.value.trim()) form.append('learningGoal', learningGoal.value.trim())
    if (officialSourceUrl.value.trim()) form.append('officialSourceUrl', officialSourceUrl.value.trim())
    if (selectedFile) form.append('file', selectedFile)
    else selectedPhotos.forEach((page) => form.append('photos', page.file))
    const basePath = editionId.value
      ? `/api/v1/editions/${editionId.value}/documents`
      : '/api/v1/documents'
    const path = selectedFile ? basePath : `${basePath}/photo-pages`
    const response = await checkedFetch(path, {
      method: 'POST', headers: { [csrf.headerName]: csrf.token }, body: form,
    })
    if (!response.ok) throw new Error(t('documents.error'))
    const result = await response.json() as { duplicate: boolean; version: { id: string; status: string } }
    clearSelectedFile()
    clearPhotographedPages()
    title.value = ''
    officialSourceUrl.value = ''
    officialImportIdentityConfirmed.value = false
    officialImportRightsConfirmed.value = false
    resetIntakeBaseline()
    const leavingAfterAcceptance = navigationDialogOpen.value
    accepted = true
    uploading.value = false
    settlePendingNavigation(true)
    if (leavingAfterAcceptance) return
    await continueUploadedRulebook(result)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('documents.error')
  } finally {
    uploading.value = false
    settlePendingNavigation(accepted)
  }
}

async function continueUploadedRulebook(
  result: { duplicate: boolean; version: { id: string; status: string } },
  serverTeaching = true,
  receivedDocuments?: DocumentResponse[],
) {
  const pending = currentPreferences(result.version.id)
  const currentDocuments = receivedDocuments ?? await loadDocuments()
  if (receivedDocuments) documents.value = receivedDocuments
  if (result.version.status === 'READY') {
    message.value = serverTeaching ? t('documents.background') : t('documents.readyToRead')
  } else if (result.version.status === 'FAILED') {
    await handleTerminalProgress(
      pending, 'FAILED', undefined, undefined, currentDocuments,
    )
  } else {
    message.value = result.duplicate ? t('documents.uploadedExisting') : t('documents.uploadedReading')
    if (serverTeaching) {
      serverTeachingVersions.add(result.version.id)
      watchProgress(pending)
    }
  }
}

function titleFromOfficialSource() {
  if (title.value.trim()) return title.value.trim()
  try {
    const filename = decodeURIComponent(new URL(officialSourceUrl.value.trim()).pathname.split('/').pop() ?? '')
    return filename.replace(/\.pdf$/i, '').replace(/[_-]+/g, ' ').trim() || t('documents.titleFallback')
  } catch {
    return t('documents.titleFallback')
  }
}

async function importOfficialRulebook() {
  if (!canImportOfficial.value) return
  let accepted = false
  importingOfficial.value = true
  message.value = t('documents.officialImport.downloading')
  errorMessage.value = ''
  try {
    await releaseOfficialImportScope()
    message.value = t('documents.officialImport.downloading')
    const selectedCandidate = selectedOfficialCandidate.value
    const discoveryIdentity = officialImportDiscoveryIdentity.value
    const csrf = await csrfToken()
    const response = await checkedFetch('/api/v1/documents/official-imports', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({
        editionId: editionId.value || null,
        title: titleFromOfficialSource(),
        sourceType: sourceType.value,
        officialSourceUrl: officialSourceUrl.value.trim(),
        rightsConfirmed: officialImportRightsConfirmed.value,
        startTeaching: true,
        learningGoal: learningGoal.value.trim() || null,
        discoveredForEditionId: discoveryIdentity?.editionId ?? null,
        sourceEdition: selectedCandidate?.edition || null,
        sourceLanguage: selectedCandidate?.languageVerified ? selectedCandidate.language : null,
        sourceLanguageVerified: selectedCandidate?.languageVerified === true,
        identityConfirmed: editionId.value ? officialImportIdentityConfirmed.value : false,
      }),
    })
    if (response.status === 409) {
      const problem = await response.json().catch(() => ({})) as RulebookIdentityProblem
      officialImportIdentityConfirmed.value = false
      throw new Error(problem.code === 'RULEBOOK_ACTIVE_IMPORT_CONFLICT'
        ? officialImportIdentityErrorCopy.value.active
        : officialImportIdentityErrorCopy.value.changed)
    }
    if (!response.ok) throw new Error(t('documents.officialImport.error'))
    const acceptedJob = await response.json() as OfficialRulebookImportJob
    if (!acceptedJob.id) throw new Error(t('documents.officialImport.error'))
    officialImportJob.value = acceptedJob
    title.value = ''
    officialSourceUrl.value = ''
    officialImportIdentityConfirmed.value = false
    officialImportRightsConfirmed.value = false
    resetIntakeBaseline()
    const navigationWasRequested = navigationDialogOpen.value
    const leavingAfterAcceptance = navigationWasRequested && !hasUnsavedIntake.value
    if (!navigationWasRequested) {
      routeImportTransitionJobId = acceptedJob.id
      try {
        await router.replace({ query: { ...route.query, importJob: acceptedJob.id } })
      } finally {
        routeImportTransitionJobId = null
      }
    }
    accepted = true
    importingOfficial.value = false
    settlePendingNavigation(true)
    if (!leavingAfterAcceptance) {
      startOfficialImportPolling(acceptedJob, identityGeneration)
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('documents.officialImport.error')
  } finally {
    importingOfficial.value = false
    settlePendingNavigation(accepted)
  }
}

async function recoverOfficialImport(jobId: string, requestIdentityGeneration: number) {
  cancelOfficialImportPolling()
  const generation = officialImportPollGeneration
  officialImportScopeJobId = jobId
  const controller = new AbortController()
  activeOfficialImportController = controller
  try {
    const job = await fetchOfficialImport(jobId, controller.signal)
    if (!isCurrentOfficialImport(generation, jobId, requestIdentityGeneration, controller)) return
    activeOfficialImportController = null
    acceptOfficialImportJob(job)
    if (officialImportSettled(job)) {
      const finished = await finishOfficialImport(job, generation, requestIdentityGeneration)
      if (!finished && isCurrentOfficialImport(generation, jobId, requestIdentityGeneration)) {
        void waitForOfficialImport(jobId, generation, requestIdentityGeneration)
      }
    }
    else if (job.stage === 'FAILED') return
    else void waitForOfficialImport(jobId, generation, requestIdentityGeneration)
  } catch {
    if (!isCurrentOfficialImport(generation, jobId, requestIdentityGeneration)
      || controller.signal.aborted) return
    message.value = t('documents.progress.reconnect')
    void waitForOfficialImport(jobId, generation, requestIdentityGeneration)
  } finally {
    if (activeOfficialImportController === controller) activeOfficialImportController = null
  }
}

function startOfficialImportPolling(
  job: OfficialRulebookImportJob,
  requestIdentityGeneration: number,
) {
  cancelOfficialImportPolling()
  const generation = officialImportPollGeneration
  officialImportScopeJobId = job.id
  acceptOfficialImportJob(job)
  if (officialImportSettled(job)) {
    void finishOfficialImport(job, generation, requestIdentityGeneration).then((finished) => {
      if (!finished && isCurrentOfficialImport(generation, job.id, requestIdentityGeneration)) {
        void waitForOfficialImport(job.id, generation, requestIdentityGeneration)
      }
    })
    return
  }
  if (job.stage === 'FAILED') {
    return
  }
  void waitForOfficialImport(job.id, generation, requestIdentityGeneration)
}

async function fetchOfficialImport(jobId: string, signal?: AbortSignal) {
  const response = await checkedFetch(`/api/v1/documents/official-imports/${encodeURIComponent(jobId)}`, { signal })
  if (!response.ok) throw new Error(t('documents.officialImport.error'))
  const job = await response.json() as OfficialRulebookImportJob
  if (job.id !== jobId) throw new Error(t('documents.officialImport.error'))
  return job
}

async function waitForOfficialImport(
  jobId: string,
  generation: number,
  requestIdentityGeneration: number,
) {
  while (isCurrentOfficialImport(generation, jobId, requestIdentityGeneration)) {
    if (!await waitForOfficialImportDelay(generation, jobId, requestIdentityGeneration)) return
    try {
      const controller = new AbortController()
      activeOfficialImportController = controller
      const job = await fetchOfficialImport(jobId, controller.signal)
      if (!isCurrentOfficialImport(generation, jobId, requestIdentityGeneration, controller)) return
      activeOfficialImportController = null
      acceptOfficialImportJob(job)
      if (officialImportSettled(job)) {
        if (await finishOfficialImport(job, generation, requestIdentityGeneration)) return
        if (!isCurrentOfficialImport(generation, jobId, requestIdentityGeneration)) return
        continue
      }
      if (job.stage === 'FAILED') {
        return
      }
    } catch {
      if (!isCurrentOfficialImport(generation, jobId, requestIdentityGeneration)) return
      message.value = t('documents.progress.reconnect')
    }
  }
}

function isCurrentOfficialImport(
  generation: number,
  jobId: string,
  requestIdentityGeneration: number,
  controller?: AbortController,
) {
  return !disposed
    && generation === officialImportPollGeneration
    && requestIdentityGeneration === identityGeneration
    && officialImportScopeJobId === jobId
    && (!controller || activeOfficialImportController === controller)
}

function waitForOfficialImportDelay(
  generation: number,
  jobId: string,
  requestIdentityGeneration: number,
) {
  return new Promise<boolean>((resolve) => {
    resolveOfficialImportDelay?.(false)
    resolveOfficialImportDelay = resolve
    officialImportPollTimer = setTimeout(() => {
      officialImportPollTimer = null
      resolveOfficialImportDelay = null
      resolve(isCurrentOfficialImport(generation, jobId, requestIdentityGeneration))
    }, 1000)
  })
}

function cancelOfficialImportPolling() {
  officialImportPollGeneration++
  officialImportScopeJobId = ''
  activeOfficialImportController?.abort()
  activeOfficialImportController = null
  if (officialImportPollTimer) clearTimeout(officialImportPollTimer)
  officialImportPollTimer = null
  resolveOfficialImportDelay?.(false)
  resolveOfficialImportDelay = null
}

function officialImportSettled(job: OfficialRulebookImportJob) {
  return job.stage === 'COMPLETED'
    && ['LAUNCHED', 'FAILED', 'NOT_REQUESTED'].includes(job.teachingHandoffState)
}

const officialImportSourceTypes = new Set([
  'BASE_RULEBOOK', 'EXPANSION_RULEBOOK', 'OFFICIAL_FAQ', 'OFFICIAL_ERRATA',
])

function acceptOfficialImportJob(job: OfficialRulebookImportJob) {
  officialImportJob.value = job
  if (job.stage === 'FAILED') restoreFailedOfficialImportContext(job)
}

function restoreFailedOfficialImportContext(job: OfficialRulebookImportJob) {
  clearSelectedFile()
  clearPhotographedPages()
  if (job.editionId) editionId.value = job.editionId
  title.value = job.rulebookTitle?.trim() || job.title.trim()
  learningGoal.value = job.learningGoal?.trim() ?? ''
  if (job.sourceType && officialImportSourceTypes.has(job.sourceType)) {
    sourceType.value = job.sourceType
  }
  officialSourceUrl.value = ''
  officialImportIdentityConfirmed.value = false
  officialImportRightsConfirmed.value = false
  selectedRulebookCandidate.value = null
  selectedRulebookDiscoveryIdentity.value = null
  message.value = ''
  errorMessage.value = ''
  resetIntakeBaseline()
}

async function detachFailedOfficialImport() {
  const failedJob = officialImportJob.value
  if (failedJob?.stage !== 'FAILED') return
  await releaseOfficialImportScope()
}

async function releaseOfficialImportScope() {
  cancelOfficialImportPolling()
  if (routeImportJobId.value) {
    const query = { ...route.query }
    delete query.importJob
    routeImportTransitionJobId = ''
    try {
      await router.replace({ query })
    } finally {
      routeImportTransitionJobId = null
    }
  }
  officialImportJob.value = null
  message.value = ''
  errorMessage.value = ''
}

function resetFailedOfficialImportSourceInputs() {
  clearSelectedFile()
  clearPhotographedPages()
  officialSourceUrl.value = ''
  officialImportIdentityConfirmed.value = false
  officialImportRightsConfirmed.value = false
  selectedRulebookCandidate.value = null
  selectedRulebookDiscoveryIdentity.value = null
}

async function chooseAnotherOfficialSource() {
  resetFailedOfficialImportSourceInputs()
  await detachFailedOfficialImport()
  await nextTick()
  uploadPanel.value?.focusOfficialSource()
}

async function useLocalUploadAfterOfficialFailure() {
  resetFailedOfficialImportSourceInputs()
  await detachFailedOfficialImport()
  await nextTick()
  uploadPanel.value?.openLocalFilePicker()
}

async function retryOriginalOfficialImport() {
  const failedJob = officialImportJob.value
  if (failedJob?.stage !== 'FAILED'
    || !failedJob.recovery?.canRetryOriginalSource
    || retryingOfficialImport.value) return
  retryingOfficialImport.value = true
  errorMessage.value = ''
  try {
    const csrf = await csrfToken()
    const response = await checkedFetch(
      `/api/v1/documents/official-imports/${encodeURIComponent(failedJob.id)}/retry`, {
        method: 'POST', headers: { [csrf.headerName]: csrf.token },
      },
    )
    if (!response.ok) throw new Error(t('documents.officialImport.error'))
    const retriedJob = await response.json() as OfficialRulebookImportJob
    if (!retriedJob.id || retriedJob.id === failedJob.id) {
      throw new Error(t('documents.officialImport.error'))
    }
    cancelOfficialImportPolling()
    if (retriedJob.stage === 'FAILED') {
      acceptOfficialImportJob(retriedJob)
    } else {
      officialImportJob.value = retriedJob
      resetFailedOfficialImportSourceInputs()
      title.value = ''
      resetIntakeBaseline()
    }
    routeImportTransitionJobId = retriedJob.id
    try {
      await router.replace({ query: { ...route.query, importJob: retriedJob.id } })
    } finally {
      routeImportTransitionJobId = null
    }
    if (retriedJob.stage !== 'FAILED') startOfficialImportPolling(retriedJob, identityGeneration)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('documents.officialImport.error')
  } finally {
    retryingOfficialImport.value = false
  }
}

async function finishOfficialImport(
  job: OfficialRulebookImportJob,
  generation: number,
  requestIdentityGeneration: number,
) {
  if (!isCurrentOfficialImport(generation, job.id, requestIdentityGeneration)) return false
  if (!job.documentVersionId) {
    errorMessage.value = t('documents.officialImport.error')
    return false
  }
  const controller = new AbortController()
  activeOfficialImportController = controller
  let receivedDocuments: DocumentResponse[]
  try {
    receivedDocuments = await fetchDocuments(controller.signal)
    if (!isCurrentOfficialImport(generation, job.id, requestIdentityGeneration, controller)) return false
  } catch {
    if (isCurrentOfficialImport(generation, job.id, requestIdentityGeneration, controller)
      && !controller.signal.aborted) {
      message.value = t('documents.progress.reconnect')
    }
    return false
  } finally {
    if (activeOfficialImportController === controller) activeOfficialImportController = null
  }
  if (!isCurrentOfficialImport(generation, job.id, requestIdentityGeneration)) return false
  const entry = receivedDocuments.find(candidate => candidate.latestVersion.id === job.documentVersionId)
  await continueUploadedRulebook({
    duplicate: job.duplicate,
    version: { id: job.documentVersionId, status: entry?.latestVersion.status ?? 'UPLOADED' },
  }, false, receivedDocuments)
  if (!isCurrentOfficialImport(generation, job.id, requestIdentityGeneration)) return false
  message.value = job.teachingHandoffState === 'LAUNCHED'
    ? (locale.value === 'zh-CN'
        ? `《${job.title}》已进入“我的讲解”，规则书现在可以先读。`
        : `${job.title} is now in My Guides, and the rulebook is ready to read.`)
    : job.teachingHandoffState === 'FAILED'
      ? job.teachingErrorCode === 'DOCUMENT_PROCESSING_FAILED'
        ? officialImportCopy.value.DOCUMENT_FAILED
        : officialImportCopy.value.TEACHING_FAILED
      : message.value
  officialImportJob.value = null
  cancelOfficialImportPolling()
  const query = { ...route.query }
  delete query.importJob
  await router.replace({ query })
  return true
}

function requestDeleteRulebook(entry: DocumentResponse) {
  if (deletingDocumentId.value || preparingVersionId.value) return
  documentToDelete.value = entry
  deleteError.value = ''
  restoreAfterDocumentDelete.value = false
}

function cancelDeleteRulebook() {
  if (deletingDocumentId.value) return
  documentToDelete.value = null
  deleteError.value = ''
  restoreAfterDocumentDelete.value = false
}

function documentDeleteRestoreTarget() {
  if (!restoreAfterDocumentDelete.value) return null
  restoreAfterDocumentDelete.value = false
  return documentList.value?.focusTarget() ?? null
}

async function confirmDeleteRulebook() {
  const entry = documentToDelete.value
  if (!entry || deletingDocumentId.value || preparingVersionId.value) return
  const documentId = entry.document.id
  const versionId = entry.latestVersion.id
  deletingDocumentId.value = documentId
  deleteError.value = ''
  try {
    const csrf = await csrfToken()
    const response = await checkedFetch(`/api/v1/documents/${encodeURIComponent(documentId)}`, {
      method: 'DELETE', headers: { [csrf.headerName]: csrf.token },
    })
    if (!response.ok) throw new Error(t('documents.error'))
    documents.value = documents.value.filter(item => item.document.id !== documentId)
    if (processingVersionId.value === versionId) {
      cancelProgressWatch(versionId)
      processingVersionId.value = ''
    }
    restoreAfterDocumentDelete.value = true
    documentToDelete.value = null
    message.value = t('documents.deleted')
  } catch (error) {
    deleteError.value = error instanceof Error ? error.message : t('documents.error')
  } finally {
    deletingDocumentId.value = ''
  }
}

onMounted(() => {
  disposed = false
  window.addEventListener('beforeunload', protectBrowserUnload)
  void load()
})
watch(routeImportJobId, () => {
  if (routeImportTransitionJobId !== null && routeImportJobId.value === routeImportTransitionJobId) return
  recoveredContextKey = ''
  cancelOfficialImportPolling()
  officialImportJob.value = null
  void recoverCurrentContext(latestInitialLoad)
})
watch(editionId, () => {
  officialImportIdentityConfirmed.value = false
  rulebookDiscoveryRequest += 1
  stopRulebookDiscoveryClock(false)
  rulebookDiscoveryElapsedSeconds.value = 0
  rulebookDiscoverySummary.value = null
  rulebookDiscoveryIdentity.value = null
  rulebookCandidates.value = []
  rulebookDiscoveryStatus.value = 'idle'
})
watch(officialSourceUrl, (value) => {
  officialImportIdentityConfirmed.value = false
  if (selectedRulebookCandidate.value?.url !== value.trim()) {
    selectedRulebookCandidate.value = null
    selectedRulebookDiscoveryIdentity.value = null
  }
})
onBeforeUnmount(() => {
  disposed = true
  rulebookDiscoveryRequest += 1
  stopRulebookDiscoveryClock(false)
  latestInitialLoad++
  activeInitialController?.abort()
  activeInitialController = null
  cancelOfficialImportPolling()
  cancelPreparationPolling()
  window.removeEventListener('beforeunload', protectBrowserUnload)
  removeNavigationGuard()
  takePendingNavigationResolution()?.(false)
  if (preparationClock) clearInterval(preparationClock)
  preparationClock = null
  clearPhotographedPages()
  cancelAllProgressWatches()
})
</script>

<template>
  <AppShell @session-identity="updateSessionIdentity">
    <div class="tabletop-page max-w-6xl">
      <section class="mx-auto max-w-5xl">
        <header class="tabletop-heading mb-8">
          <h1 class="tabletop-title">{{ t('documents.heading.title') }}</h1>
          <p class="tabletop-lede">{{ t('documents.heading.description') }}</p>
        </header>

        <RulebookSourceImportPanel
          :selected-edition="selectedEditionContext"
          :status="rulebookDiscoveryStatus"
          :candidates="rulebookCandidates"
          :elapsed-seconds="rulebookDiscoveryElapsedSeconds"
          :discovery-summary="rulebookDiscoverySummary"
          :copy="rulebookDiscoveryCopy"
          @discover="discoverOfficialRulebooks"
          @choose="chooseRulebookCandidate"
        />

        <RulebookUploadPanel
          ref="uploadPanel"
          v-model:title="title"
          v-model:official-source-url="officialSourceUrl"
          v-model:official-import-identity-confirmed="officialImportIdentityConfirmed"
          v-model:official-import-rights-confirmed="officialImportRightsConfirmed"
          v-model:edition-id="editionId"
          v-model:learning-goal="learningGoal"
          v-model:source-type="sourceType"
          :file="file"
          :photographed-pages="photographedPages"
          :preparing-photos="preparingPhotos"
          :intake-controls-disabled="intakeControlsDisabled"
          :intake-draft-areas="intakeDraftAreas"
          :intake-draft-copy="intakeDraftCopy"
          :edition-options="editionOptions"
          :identity-target="officialImportIdentityTarget"
          :identity-source-context="officialImportDiscoveryIdentity"
          :identity-source="officialImportSourceIdentity"
          :model-configuration-available="Boolean(modelConfiguration)"
          :visual-vision-capable="visualVisionCapable"
          :can-import-official="canImportOfficial"
          :importing-official="importingOfficial"
          :can-upload="canUpload"
          :preparing-version-id="preparingVersionId"
          :uploading="uploading"
          @submit="uploadRulebook"
          @select-file="selectFile"
          @add-photos="addPhotographedPages"
          @move-photo="movePhotographedPage"
          @remove-photo="removePhotographedPage"
          @import-official="importOfficialRulebook"
        />

        <RulebookStatusCard
          :official-import-job="officialImportJob"
          :official-import-copy="officialImportCopy"
          :retrying-official-import="retryingOfficialImport"
          :message="message"
          :preparing-version-id="preparingVersionId"
          :preparation-elapsed-label="preparationElapsedLabel()"
          :error-message="errorMessage"
          :processing-version-id="processingVersionId"
          :processing-percentage="progress[processingVersionId]?.percentage ?? 0"
          @choose-source="chooseAnotherOfficialSource"
          @use-local-upload="useLocalUploadAfterOfficialFailure"
          @retry-original="retryOriginalOfficialImport"
        />
      </section>

      <RulebookDocumentList
        ref="documentList"
        :loading="loading"
        :documents="documents"
        :suggestion-states="bggSuggestionStates"
        :deleting-document-id="deletingDocumentId"
        :preparing-version-id="preparingVersionId"
        @load-suggestions="loadBggSuggestions"
        @select-suggestion="selectBggSuggestion"
        @confirm-suggestion="confirmBggSuggestion"
        @start-lesson="startLesson($event).catch((error: unknown) => errorMessage = error instanceof Error ? error.message : t('documents.error'))"
        @request-delete="requestDeleteRulebook"
      />

      <DestructiveActionDialog
        :open="Boolean(documentToDelete)"
        :pending="Boolean(deletingDocumentId)"
        :error="deleteError"
        :title="deleteCopy.title"
        :description="deleteCopy.description(documentToDelete?.document.title ?? t('documents.titleFallback'))"
        :cancel-label="deleteCopy.cancel"
        :confirm-label="deleteCopy.confirm"
        :pending-label="t('documents.deleting')"
        :retry-label="deleteCopy.retry"
        :restore-focus="documentDeleteRestoreTarget"
        @cancel="cancelDeleteRulebook"
        @confirm="confirmDeleteRulebook"
      />
      <DestructiveActionDialog
        :open="navigationDialogOpen"
        :pending="intakeMutationPending"
        :title="navigationCopy.title"
        :description="navigationCopy.description"
        :cancel-label="intakeDraftCopy.stay"
        :confirm-label="intakeDraftCopy.leave"
        :pending-label="intakeDraftCopy.pending"
        @cancel="cancelPendingNavigation"
        @confirm="discardDraftAndLeave"
      />
    </div>
  </AppShell>
</template>
