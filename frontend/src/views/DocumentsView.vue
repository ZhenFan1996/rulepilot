<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import DestructiveActionDialog from '@/components/DestructiveActionDialog.vue'
import { notifyLoginRequired } from '@/lib/authSession'
import { mergeDocumentProgress, type DocumentProcessingSnapshot } from '@/lib/documentProgress'
import {
  forgetPendingRulebookLesson,
  readPendingRulebookLessons,
  type PendingRulebookLesson,
} from '@/lib/pendingRulebookLesson'
import { useLocale } from '@/lib/locale'
import { notifyTeachingLaunched, type TeachingLaunch } from '@/lib/teachingLaunch'

interface CsrfResponse { headerName: string; token: string }
interface GameResponse {
  game: { id: string; name: string }
  editions: Array<{ id: string; name: string; language: string }>
  bggMetadata?: null | { thumbnailUrl: string; bggUrl: string }
}
interface DocumentResponse {
  document: { id: string; gameEditionId: string | null; title: string; officialSourceUrl: string | null; officialCoverUrl: string | null }
  latestVersion: { id: string; originalFilename: string; size: number; status: string }
}
interface BggSuggestion {
  bggId: number
  name: string
  publicationYear: number | null
  coverUrl: string
  minPlayers: number | null
  maxPlayers: number | null
  playingTimeMinutes: number | null
  minimumAge: number | null
  normalizedTitleMatch: boolean
  bggUrl: string
}
interface BggSuggestionState {
  status: 'loading' | 'success' | 'error'
  candidates: BggSuggestion[]
  selectedBggId: number | null
  linkStatus: 'idle' | 'confirming' | 'linked' | 'error'
  linkAlreadyImported: boolean
}
interface BggLinkResponse { alreadyImported: boolean }
interface RulebookCandidate {
  title: string
  url: string
  publisher: string
  language: string
  edition: string
  sourceDomain: string
  officialDomainVerified: boolean
  sourceType: 'PUBLISHER' | 'TRUSTED_REPOSITORY' | 'COMMUNITY_PLATFORM' | 'PUBLIC_WEB'
  acquisitionMode: 'DIRECT_PDF' | 'IMAGE_GALLERY' | 'SOURCE_PAGE'
}
interface RulebookCandidateResponse { configured: boolean; candidates: RulebookCandidate[] }
interface OfficialRulebookImportJob {
  id: string
  title: string
  rulebookTitle?: string
  editionId?: string | null
  editionName?: string | null
  sourceDomain: string
  stage: 'QUEUED' | 'CONNECTING' | 'DOWNLOADING' | 'COMPRESSING' | 'VERIFYING_FILE' | 'SAVING' | 'COMPLETED' | 'FAILED'
  downloadedBytes: number
  totalBytes: number | null
  documentVersionId: string | null
  duplicate: boolean
  errorCode: string | null
  teachingHandoffState: 'NOT_REQUESTED' | 'WAITING_FOR_DOCUMENT' | 'LAUNCHING' | 'LAUNCHED' | 'FAILED'
  teachingPreparationRunId: string | null
  teachingErrorCode: string | null
  reused: boolean
}
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
interface PhotographedPage {
  id: string
  file: File
  previewUrl: string
}
interface RulebookIntakeSnapshot {
  editionId: string
  learningGoal: string
  officialImportRightsConfirmed: boolean
  officialSourceUrl: string
  sourceType: string
  title: string
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
const rulebookDiscoveryStatus = ref<'idle' | 'loading' | 'success' | 'unavailable' | 'error'>('idle')
const officialDetails = ref<HTMLDetailsElement | null>(null)
const file = ref<File | null>(null)
const photographedPages = ref<PhotographedPage[]>([])
const preparingPhotos = ref(false)
const title = ref('')
const officialSourceUrl = ref('')
const officialImportRightsConfirmed = ref(false)
const sourceType = ref('BASE_RULEBOOK')
const learningGoal = ref('')
const loading = ref(true)
const uploading = ref(false)
const importingOfficial = ref(false)
const officialImportJob = ref<OfficialRulebookImportJob | null>(null)
const deletingDocumentId = ref('')
const documentToDelete = ref<DocumentResponse | null>(null)
const deleteError = ref('')
const documentListHeading = ref<HTMLElement | null>(null)
const restoreAfterDocumentDelete = ref(false)
const rulebookFileInput = ref<HTMLInputElement | null>(null)
const intakeReady = ref(false)
const intakeBaseline = ref<RulebookIntakeSnapshot>({
  editionId: '', learningGoal: '', officialImportRightsConfirmed: false,
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
let routeImportTransitionJobId = ''
let activeOfficialImportController: AbortController | null = null
let officialImportPollTimer: ReturnType<typeof setTimeout> | null = null
let resolveOfficialImportDelay: ((current: boolean) => void) | null = null
let preparationPollGeneration = 0
let activePreparationController: AbortController | null = null
let preparationPollTimer: ReturnType<typeof setTimeout> | null = null
let resolvePreparationDelay: ((current: boolean) => void) | null = null
let preparationClock: ReturnType<typeof setInterval> | null = null
let photographedPageSequence = 0

const editionOptions = computed(() => games.value.flatMap((entry) => entry.editions.map((edition) => ({
  id: edition.id,
  label: `${entry.game.name} · ${edition.name}${edition.language ? ` · ${edition.language}` : ''}`,
}))))
const routeImportJobId = computed(() => typeof route.query.importJob === 'string' ? route.query.importJob : '')
const selectedEditionContext = computed(() => {
  for (const entry of games.value) {
    const edition = entry.editions.find(candidate => candidate.id === editionId.value)
    if (edition) return { game: entry.game, edition, bggMetadata: entry.bggMetadata ?? null }
  }
  return null
})
const rulebookDiscoveryCopy = computed(() => locale.value === 'zh-CN' ? {
  action: '帮我找规则书', loading: '正在检索多个可信来源…', title: '找到这些规则书来源',
  detail: '会优先找出版社，也会补查 BGG、集石与可信规则库。来源页会在新窗口打开；PDF 直链和已识别的连续规则页图片都可以在确认后导入。',
  unavailable: '当前模型未开启联网搜索。你仍可粘贴公开 PDF 链接或上传本地文件。',
  empty: '没有找到可信的规则书来源。请改用公开链接或本地上传。',
  error: '规则书搜索暂时不可用，手动入口仍可使用。',
  sources: { PUBLISHER: '出版社 / 权利方来源', TRUSTED_REPOSITORY: '可信规则库', COMMUNITY_PLATFORM: '社区规则书来源（如 BGG / 集石）', PUBLIC_WEB: '公开来源（请重点核对）' },
  direct: 'PDF 可直接核验并下载', gallery: '连续规则页图片，可合成为 PDF', page: '来源页，需要继续查找文件', use: '选择并继续核对', open: '打开来源页',
  publisher: '发布者', language: '语言', edition: '版本',
  searchSteps: ['核对 BGG 身份与版本', '搜索出版社、发行方与本地化方', '补查 BGG、集石和可信规则库'],
} : {
  action: 'Find a rulebook', loading: 'Searching multiple trusted sources…', title: 'Rulebook sources found',
  detail: 'Publisher sources come first, followed by BGG, Gstone, and trusted repositories. Source pages open separately; direct PDFs and recognized ordered page-image documents can be imported after confirmation.',
  unavailable: 'Web search is not enabled for the current model. You can still paste a public PDF URL or upload a local file.',
  empty: 'No credible rulebook source was found. Use a public URL or local upload instead.',
  error: 'Rulebook search is temporarily unavailable. Manual options still work.',
  sources: { PUBLISHER: 'Publisher / rights-holder', TRUSTED_REPOSITORY: 'Trusted rules repository', COMMUNITY_PLATFORM: 'Community rulebook source (such as BGG / Gstone)', PUBLIC_WEB: 'Public source (review carefully)' },
  direct: 'Direct PDF ready for verification', gallery: 'Ordered rulebook pages; RulePilot can build the PDF', page: 'Source page; continue there', use: 'Choose and review', open: 'Open source page',
  publisher: 'Provider', language: 'Language', edition: 'Edition',
  searchSteps: ['Verify BGG identity and edition', 'Search publishers, distributors, and localizers', 'Check BGG, Gstone, and trusted repositories'],
})
const officialImportCopy = computed(() => locale.value === 'zh-CN' ? {
  title: '规则书与讲解正在后台准备', safe: '可以离开这一页；下载、核验、规则书读取和讲解生成都会继续。',
  QUEUED: '等待下载', CONNECTING: '正在连接来源', DOWNLOADING: '正在下载规则书内容',
  COMPRESSING: '文件超过普通导入上限，正在安全压缩 PDF',
  VERIFYING_FILE: '正在核验文件格式与大小', SAVING: '正在保存并交给规则书读取',
  COMPLETED: '下载完成，正在衔接规则书读取', FAILED: '下载失败，需要重新选择来源',
  WAITING_FOR_DOCUMENT: '规则书已下载，正在读取页面与建立检索', LAUNCHING: '规则书已可阅读，正在启动讲解',
  LAUNCHED: '讲解任务已经进入后台', TEACHING_FAILED: '规则书已可阅读，但讲解任务需要重试', DOCUMENT_FAILED: '规则书读取失败，讲解无法开始',
  background: '在任意页面打开“后台任务”都能找回这次进度。',
} : {
  title: 'Preparing the rulebook and guide in the background', safe: 'You can leave this page; download, verification, reading, and guide generation will continue.',
  QUEUED: 'Waiting to download', CONNECTING: 'Connecting to source', DOWNLOADING: 'Downloading rulebook content',
  COMPRESSING: 'Compressing the PDF to the safe import limit',
  VERIFYING_FILE: 'Verifying file format and size', SAVING: 'Saving and handing off for reading',
  COMPLETED: 'Download complete; handing off to rulebook reading', FAILED: 'Download failed; choose another source',
  WAITING_FOR_DOCUMENT: 'Rulebook downloaded; reading pages and building retrieval data', LAUNCHING: 'Rulebook readable; starting the guide',
  LAUNCHED: 'The guide task is now running in the background', TEACHING_FAILED: 'Rulebook readable, but the guide task needs a retry', DOCUMENT_FAILED: 'Rulebook reading failed, so the guide could not start',
  background: 'Open Background work from any page to return to this progress.',
})
const canUpload = computed(() => Boolean(
  (file.value || photographedPages.value.length)
  && !preparingPhotos.value
  && !uploading.value
  && !importingOfficial.value
  && !officialImportJob.value
  && !preparingVersionId.value
  && intakeReady.value,
))
const canImportOfficial = computed(() => Boolean(
  officialSourceUrl.value.trim()
  && officialImportRightsConfirmed.value
  && !uploading.value
  && !importingOfficial.value
  && !officialImportJob.value
  && !preparingVersionId.value
  && intakeReady.value,
))
function currentIntakeSnapshot(): RulebookIntakeSnapshot {
  return {
    editionId: editionId.value,
    learningGoal: learningGoal.value,
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
      || officialImportRightsConfirmed.value !== baseline.officialImportRightsConfirmed
      ? [intakeDraftCopy.value.source] : []),
    ...(editionId.value !== baseline.editionId ? [intakeDraftCopy.value.game] : []),
    ...(learningGoal.value.trim() !== baseline.learningGoal.trim() ? [intakeDraftCopy.value.goal] : []),
  ]
})
const hasUnsavedIntake = computed(() => intakeDraftAreas.value.length > 0)
const intakeMutationPending = computed(() => (
  preparingPhotos.value || uploading.value || importingOfficial.value || launchingTeaching.value
))
const intakeControlsDisabled = computed(() => Boolean(
  loading.value || !intakeReady.value || intakeMutationPending.value || officialImportJob.value || preparingVersionId.value,
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

function documentStatusLabel(status: string) {
  return {
    UPLOADED: t('documents.status.uploaded'),
    EXTRACTING: t('documents.status.extracting'),
    READY: t('documents.status.ready'),
    FAILED: t('documents.status.failed'),
  }[status] ?? t('documents.status.processing')
}

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

function candidatePlayerLabel(candidate: BggSuggestion) {
  if (candidate.minPlayers == null || candidate.maxPlayers == null) return ''
  return candidate.minPlayers === candidate.maxPlayers
    ? t('documents.bgg.playersExact', { players: candidate.minPlayers })
    : t('documents.bgg.playersRange', { min: candidate.minPlayers, max: candidate.maxPlayers })
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
  rulebookDiscoveryStatus.value = 'loading'
  rulebookCandidates.value = []
  try {
    const parameters = new URLSearchParams({ editionId: editionId.value, language: locale.value })
    const response = await checkedFetch(`/api/v1/documents/rulebook-candidates?${parameters.toString()}`)
    if (!response.ok) throw new Error(rulebookDiscoveryCopy.value.error)
    const result = await response.json() as RulebookCandidateResponse
    rulebookCandidates.value = result.candidates
    rulebookDiscoveryStatus.value = result.configured ? 'success' : 'unavailable'
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : rulebookDiscoveryCopy.value.error
    rulebookDiscoveryStatus.value = 'error'
  }
}

function chooseRulebookCandidate(candidate: RulebookCandidate) {
  if (intakeControlsDisabled.value) return
  if (candidate.acquisitionMode === 'SOURCE_PAGE') {
    window.open(candidate.url, '_blank', 'noopener,noreferrer')
    return
  }
  officialSourceUrl.value = candidate.url
  if (!title.value.trim()) title.value = candidate.title
  officialImportRightsConfirmed.value = false
  if (officialDetails.value) officialDetails.value.open = true
  if (typeof officialDetails.value?.scrollIntoView === 'function') {
    officialDetails.value.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
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
    if (isCurrentRecoveryContext(contextGeneration, initialRequest)
      && !officialImportJob.value && !importJobId && username.value) {
      await recoverPendingHandoff()
    }
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
  if (rulebookFileInput.value) rulebookFileInput.value.value = ''
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

function currentPreferences(versionId: string): PendingRulebookLesson {
  return {
    versionId,
    ...(editionId.value ? { editionId: editionId.value } : {}),
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
  if (active?.operation.startsWith('selectProgressiveTeachingStart')) {
    message.value = t('documents.prepare.progressiveStart')
    return
  }
  if (active?.operation.startsWith('inspectRulebookVisualBatch')) {
    const batch = active.operation.split('|')[1]
    message.value = batch
      ? t('documents.prepare.visualBatch', { batch })
      : t('documents.prepare.visual')
    return
  }
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
  preferences: PendingRulebookLesson,
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
  preferences: PendingRulebookLesson,
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
  if (username.value) forgetPendingRulebookLesson(localStorage, username.value, preferences.versionId)
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

function watchProgress(pending: PendingRulebookLesson) {
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
  pending: PendingRulebookLesson,
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
      if (username.value) forgetPendingRulebookLesson(localStorage, username.value, pending.versionId)
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
  if (username.value) forgetPendingRulebookLesson(localStorage, username.value, pending.versionId)
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
  pending: PendingRulebookLesson,
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
      if (username.value) forgetPendingRulebookLesson(localStorage, username.value, pending.versionId)
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
  pending: PendingRulebookLesson,
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
    const snapshot = JSON.parse(value) as Partial<ProcessingSnapshot>
    if (!(typeof snapshot.stage === 'string'
      && snapshot.stage.length > 0
      && typeof snapshot.percentage === 'number'
      && snapshot.percentage >= 0
      && snapshot.percentage <= 100
      && typeof snapshot.processedPages === 'number'
      && snapshot.processedPages >= 0
      && typeof snapshot.complete === 'boolean')) return null
    const totalPages = typeof snapshot.totalPages === 'number' && snapshot.totalPages >= snapshot.processedPages
      ? snapshot.totalPages
      : snapshot.processedPages
    return { ...snapshot, totalPages } as ProcessingSnapshot
  } catch {
    return null
  }
}

async function recoverPendingHandoff() {
  if (!username.value || preparingVersionId.value) return
  for (const pending of readPendingRulebookLessons(localStorage, username.value)) {
    if (pending.learningGoal) learningGoal.value = pending.learningGoal
    if (!editionId.value && pending.editionId && editionOptions.value.some(item => item.id === pending.editionId)) {
      editionId.value = pending.editionId
    }
    const entry = documents.value.find((candidate) => candidate.latestVersion.id === pending.versionId)
    if (!entry) {
      forgetPendingRulebookLesson(localStorage, username.value, pending.versionId)
      continue
    }
    if (entry.latestVersion.status === 'READY' || entry.latestVersion.status === 'FAILED') {
      await handleTerminalProgress(pending, entry.latestVersion.status)
      return
    }
    watchProgress(pending)
    return
  }
}

async function uploadRulebook() {
  if (!file.value && photographedPages.value.length === 0) return
  let accepted = false
  uploading.value = true
  message.value = t('documents.uploading')
  errorMessage.value = ''
  try {
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
    if (username.value) forgetPendingRulebookLesson(localStorage, username.value, result.version.id)
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
      }),
    })
    if (!response.ok) throw new Error(t('documents.officialImport.error'))
    const acceptedJob = await response.json() as OfficialRulebookImportJob
    if (!acceptedJob.id) throw new Error(t('documents.officialImport.error'))
    officialImportJob.value = acceptedJob
    title.value = ''
    officialSourceUrl.value = ''
    officialImportRightsConfirmed.value = false
    resetIntakeBaseline()
    const navigationWasRequested = navigationDialogOpen.value
    const leavingAfterAcceptance = navigationWasRequested && !hasUnsavedIntake.value
    if (!navigationWasRequested) {
      routeImportTransitionJobId = acceptedJob.id
      try {
        await router.replace({ query: { ...route.query, importJob: acceptedJob.id } })
      } finally {
        routeImportTransitionJobId = ''
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
    officialImportJob.value = job
    if (officialImportSettled(job)) {
      const finished = await finishOfficialImport(job, generation, requestIdentityGeneration)
      if (!finished && isCurrentOfficialImport(generation, jobId, requestIdentityGeneration)) {
        void waitForOfficialImport(jobId, generation, requestIdentityGeneration)
      }
    }
    else if (job.stage === 'FAILED') errorMessage.value = t('documents.officialImport.error')
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
  officialImportJob.value = job
  if (officialImportSettled(job)) {
    void finishOfficialImport(job, generation, requestIdentityGeneration).then((finished) => {
      if (!finished && isCurrentOfficialImport(generation, job.id, requestIdentityGeneration)) {
        void waitForOfficialImport(job.id, generation, requestIdentityGeneration)
      }
    })
    return
  }
  if (job.stage === 'FAILED') {
    errorMessage.value = t('documents.officialImport.error')
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
      officialImportJob.value = job
      if (officialImportSettled(job)) {
        if (await finishOfficialImport(job, generation, requestIdentityGeneration)) return
        if (!isCurrentOfficialImport(generation, jobId, requestIdentityGeneration)) return
        continue
      }
      if (job.stage === 'FAILED') {
        errorMessage.value = t('documents.officialImport.error')
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

function officialImportProgress() {
  const job = officialImportJob.value
  if (!job || job.stage !== 'DOWNLOADING' || !job.totalBytes) return null
  return Math.min(100, Math.round(job.downloadedBytes / job.totalBytes * 100))
}

function officialImportBytes() {
  const job = officialImportJob.value
  if (!job || job.downloadedBytes <= 0) return ''
  const format = (bytes: number) => bytes < 1024 * 1024
    ? `${Math.max(1, Math.round(bytes / 1024))} KB`
    : `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return job.totalBytes ? `${format(job.downloadedBytes)} / ${format(job.totalBytes)}` : format(job.downloadedBytes)
}

function officialImportStage() {
  const job = officialImportJob.value
  if (!job) return ''
  if (job.stage === 'COMPLETED' && job.teachingHandoffState === 'FAILED') {
    return job.teachingErrorCode === 'DOCUMENT_PROCESSING_FAILED'
      ? officialImportCopy.value.DOCUMENT_FAILED
      : officialImportCopy.value.TEACHING_FAILED
  }
  if (job.stage === 'COMPLETED' && job.teachingHandoffState !== 'NOT_REQUESTED') {
    return officialImportCopy.value[job.teachingHandoffState]
  }
  return officialImportCopy.value[job.stage]
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
  return documentListHeading.value
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
    if (username.value) forgetPendingRulebookLesson(localStorage, username.value, versionId)
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
  if (routeImportJobId.value && routeImportJobId.value === routeImportTransitionJobId) return
  recoveredContextKey = ''
  cancelOfficialImportPolling()
  officialImportJob.value = null
  void recoverCurrentContext(latestInitialLoad)
})
onBeforeUnmount(() => {
  disposed = true
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
        <div class="tabletop-illustrated-hero player-board grid lg:grid-cols-[1.08fr_0.92fr]">
          <div class="relative min-h-64 overflow-hidden border-b border-ink/10 lg:min-h-full lg:border-b-0 lg:border-r" aria-hidden="true">
            <img src="/illustrations/rulebook-reading.webp" alt="" width="1600" height="900" fetchpriority="high" class="absolute inset-0 h-full w-full object-cover object-left">
          </div>
          <div class="tabletop-heading self-center bg-paper/95 px-6 py-8 sm:px-9 sm:py-10 lg:min-h-full lg:justify-center">
            <p class="tabletop-kicker">{{ t('documents.heading.eyebrow') }}</p>
            <h1 class="tabletop-title !text-[clamp(2.2rem,4vw,3.8rem)]">{{ t('documents.heading.title') }}</h1>
            <p class="tabletop-lede">{{ t('documents.heading.description') }}</p>
          </div>
        </div>

        <div v-if="selectedEditionContext" class="mt-7 flex items-center gap-4 rounded-xl border border-copper/20 bg-copper/5 p-4 text-left">
          <img v-if="selectedEditionContext.bggMetadata?.thumbnailUrl" :src="selectedEditionContext.bggMetadata.thumbnailUrl" :alt="t('documents.game.selectedCover', { game: selectedEditionContext.game.name })" class="h-20 w-16 shrink-0 rounded-lg bg-paper object-contain" referrerpolicy="no-referrer">
          <div class="min-w-0 flex-1">
            <p class="text-xs font-bold uppercase tracking-[0.12em] text-copper">{{ t('documents.game.selectedEyebrow') }}</p>
            <h2 class="mt-1 truncate font-display text-xl font-semibold">{{ selectedEditionContext.game.name }}</h2>
            <p class="mt-1 text-sm text-ink/55">{{ t('documents.game.selectedEdition', { edition: selectedEditionContext.edition.name }) }}</p>
            <a v-if="selectedEditionContext.bggMetadata?.bggUrl" :href="selectedEditionContext.bggMetadata.bggUrl" target="_blank" rel="noopener noreferrer" class="mt-1 inline-block text-xs font-semibold text-indigo">{{ t('documents.game.selectedSource') }} ↗</a>
          </div>
        </div>

        <div v-if="selectedEditionContext" class="mt-4 text-left">
          <button type="button" :disabled="rulebookDiscoveryStatus === 'loading'" class="min-h-11 rounded-xl bg-indigo px-5 text-sm font-semibold text-white disabled:opacity-50" @click="discoverOfficialRulebooks">
            {{ rulebookDiscoveryStatus === 'loading' ? rulebookDiscoveryCopy.loading : rulebookDiscoveryCopy.action }}
          </button>
          <ol v-if="rulebookDiscoveryStatus === 'loading'" class="mt-4 grid gap-2 rounded-xl border border-indigo/15 bg-indigo/[0.035] p-4 text-sm sm:grid-cols-3" role="status">
            <li v-for="(step, index) in rulebookDiscoveryCopy.searchSteps" :key="step" class="flex items-center gap-2 text-ink/60">
              <span class="grid size-6 shrink-0 place-items-center rounded-full bg-indigo/10 text-xs font-bold text-indigo">{{ index + 1 }}</span>
              <span>{{ step }}</span>
            </li>
          </ol>
          <section v-if="rulebookDiscoveryStatus === 'success'" class="mt-4 rounded-xl border border-indigo/15 bg-paper p-4 sm:p-5" aria-live="polite">
            <h2 class="font-display text-xl font-semibold">{{ rulebookDiscoveryCopy.title }}</h2>
            <p class="mt-1 text-xs leading-5 text-ink/50">{{ rulebookDiscoveryCopy.detail }}</p>
            <ul v-if="rulebookCandidates.length" class="mt-4 stack-y-md">
              <li v-for="candidate in rulebookCandidates" :key="candidate.url" class="rounded-lg border border-ink/10 bg-canvas p-4">
                <div class="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                  <div class="min-w-0">
                    <p class="font-semibold">{{ candidate.title }}</p>
                    <p class="mt-1 break-all text-xs text-ink/45">{{ candidate.sourceDomain }}</p>
                    <p class="mt-2 text-xs leading-5 text-ink/55">{{ rulebookDiscoveryCopy.publisher }}: {{ candidate.publisher || '—' }} · {{ rulebookDiscoveryCopy.language }}: {{ candidate.language || '—' }} · {{ rulebookDiscoveryCopy.edition }}: {{ candidate.edition || '—' }}</p>
                    <p class="mt-1 text-xs font-semibold" :class="candidate.sourceType === 'PUBLIC_WEB' ? 'text-amber-700' : 'text-emerald-700'">{{ rulebookDiscoveryCopy.sources[candidate.sourceType] }}</p>
                    <p class="mt-1 text-xs text-ink/45">{{ candidate.acquisitionMode === 'DIRECT_PDF' ? rulebookDiscoveryCopy.direct : candidate.acquisitionMode === 'IMAGE_GALLERY' ? rulebookDiscoveryCopy.gallery : rulebookDiscoveryCopy.page }}</p>
                  </div>
                  <button type="button" class="min-h-11 shrink-0 rounded-lg border border-indigo/30 px-4 text-sm font-semibold text-indigo" @click="chooseRulebookCandidate(candidate)">{{ candidate.acquisitionMode === 'SOURCE_PAGE' ? rulebookDiscoveryCopy.open : rulebookDiscoveryCopy.use }}</button>
                </div>
              </li>
            </ul>
            <p v-else class="mt-4 text-sm text-ink/55">{{ rulebookDiscoveryCopy.empty }}</p>
          </section>
          <p v-else-if="rulebookDiscoveryStatus === 'unavailable'" class="mt-3 rounded-lg bg-amber-50 px-4 py-3 text-sm text-amber-900" role="status">{{ rulebookDiscoveryCopy.unavailable }}</p>
        </div>

        <form class="tabletop-panel player-board mt-8 p-5 text-left sm:p-7" @submit.prevent="uploadRulebook">
          <div v-if="hasUnsavedIntake" data-testid="rulebook-intake-unsaved" class="mb-5 rounded-lg bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-900" role="status">
            <strong>{{ intakeDraftCopy.status(intakeDraftAreas.join(locale === 'zh-CN' ? '、' : ', ')) }}</strong>
            <span class="mt-1 block text-xs leading-5">{{ intakeDraftCopy.memoryOnly }}</span>
          </div>
          <p class="text-sm font-semibold text-ink/65">{{ t('documents.capture.label') }}</p>
          <div class="mt-3 grid gap-3 sm:grid-cols-3">
            <label for="rulebook-file" class="group flex min-h-32 flex-col rounded-xl border border-dashed border-ink/25 bg-canvas p-4 transition" :class="intakeControlsDisabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer hover:border-copper/60 hover:bg-copper/5'">
              <svg class="h-6 w-6 text-copper" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M14.5 2.75H6.75a2 2 0 0 0-2 2v14.5a2 2 0 0 0 2 2h10.5a2 2 0 0 0 2-2V8.75z" /><path d="M14 2.75v6h5.25M8 13h8M8 16.5h6" /></svg>
              <span class="mt-auto font-display text-lg font-semibold">{{ t('documents.capture.pdf.title') }}</span>
              <span class="mt-1 text-sm leading-5 text-ink/45">{{ file?.name ?? t('documents.capture.pdf.detail') }}</span>
            </label>
            <label for="rulebook-camera" class="flex min-h-32 flex-col rounded-xl border border-ink/12 bg-paper p-4 text-ink transition" :class="intakeControlsDisabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer hover:border-copper/60 hover:bg-copper/[0.1]'">
              <svg class="h-6 w-6 text-copper" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M4.75 7.75h3l1.25-2h6l1.25 2h3a1.75 1.75 0 0 1 1.75 1.75v8.75A1.75 1.75 0 0 1 19.25 20H4.75A1.75 1.75 0 0 1 3 18.25V9.5a1.75 1.75 0 0 1 1.75-1.75Z" /><circle cx="12" cy="13.5" r="3.25" /></svg>
              <span class="mt-auto font-display text-lg font-semibold">{{ t('documents.capture.camera.title') }}</span>
              <span class="mt-1 text-sm leading-5 text-ink/45">{{ t('documents.capture.camera.detail') }}</span>
            </label>
            <label for="rulebook-gallery" class="flex min-h-32 flex-col rounded-xl border border-ink/12 bg-paper p-4 text-ink transition" :class="intakeControlsDisabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer hover:border-indigo/50 hover:bg-indigo/[0.1]'">
              <svg class="h-6 w-6 text-indigo" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><rect x="3.5" y="4" width="17" height="16" rx="2" /><circle cx="8.5" cy="9" r="1.25" /><path d="m5.5 17 4.3-4.3 3.1 3.1 2.1-2.1L18.5 17" /></svg>
              <span class="mt-auto font-display text-lg font-semibold">{{ t('documents.capture.gallery.title') }}</span>
              <span class="mt-1 text-sm leading-5 text-ink/45">{{ t('documents.capture.gallery.detail') }}</span>
            </label>
          </div>
          <input id="rulebook-file" ref="rulebookFileInput" :disabled="intakeControlsDisabled" accept="application/pdf,.pdf" type="file" class="sr-only" @change="selectFile">
          <input id="rulebook-camera" :disabled="intakeControlsDisabled" accept="image/*" capture="environment" type="file" class="sr-only" :aria-label="t('documents.capture.cameraAlt')" @change="addPhotographedPages">
          <input id="rulebook-gallery" :disabled="intakeControlsDisabled" accept="image/*" multiple type="file" class="sr-only" :aria-label="t('documents.capture.galleryAlt')" @change="addPhotographedPages">

          <div v-if="photographedPages.length" class="mt-4 rounded-xl border border-ink/10 bg-canvas p-3 sm:p-4">
            <div class="flex flex-wrap items-baseline justify-between gap-2">
              <p class="font-semibold">{{ t('documents.capture.photoCount', { count: photographedPages.length }) }}</p>
              <p class="text-xs leading-5 text-ink/45">{{ t('documents.capture.photoHint') }}</p>
            </div>
            <ol class="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-4">
              <li v-for="(page, index) in photographedPages" :key="page.id" class="overflow-hidden rounded-lg border border-ink/10 bg-paper">
                <img :src="page.previewUrl" :alt="t('documents.capture.photoPage', { position: index + 1 })" class="aspect-[3/4] w-full object-cover">
                <div class="flex items-center justify-between gap-1 px-2 py-2">
                  <span class="text-xs font-semibold text-ink/60">{{ t('documents.capture.photoPage', { position: index + 1 }) }}</span>
                  <span class="flex gap-1">
                    <button type="button" :disabled="intakeControlsDisabled || index === 0" class="rounded px-1.5 py-0.5 text-sm text-ink/55 hover:bg-canvas disabled:opacity-25" :aria-label="t('documents.capture.moveEarlier', { position: index + 1 })" @click="movePhotographedPage(index, -1)">←</button>
                    <button type="button" :disabled="intakeControlsDisabled || index === photographedPages.length - 1" class="rounded px-1.5 py-0.5 text-sm text-ink/55 hover:bg-canvas disabled:opacity-25" :aria-label="t('documents.capture.moveLater', { position: index + 1 })" @click="movePhotographedPage(index, 1)">→</button>
                    <button type="button" :disabled="intakeControlsDisabled" class="rounded px-1.5 py-0.5 text-sm text-red-700 hover:bg-red-50 disabled:opacity-25" :aria-label="t('documents.capture.remove', { position: index + 1 })" @click="removePhotographedPage(index)">×</button>
                  </span>
                </div>
              </li>
            </ol>
          </div>
          <p v-else-if="file" class="mt-3 text-sm text-ink/45">{{ t('documents.file.change') }} · {{ t('documents.file.limit') }}</p>
          <p v-if="preparingPhotos" class="mt-4 rounded-lg bg-copper/8 px-4 py-3 text-sm text-copper" role="status">{{ t('documents.capture.preparing') }}</p>

          <label class="mt-4 block text-sm font-semibold">{{ t('documents.title.label') }} <span class="font-normal text-ink/40">{{ t('documents.optional') }}</span>
            <input v-model="title" :disabled="intakeControlsDisabled" maxlength="160" :placeholder="t('documents.title.placeholder')" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-normal outline-none focus:border-copper disabled:opacity-50">
            <span v-if="photographedPages.length" class="mt-1 block text-xs font-normal leading-5 text-ink/45">{{ t('documents.title.photoHint') }}</span>
          </label>

          <details ref="officialDetails" class="mt-4 border-t border-ink/10 pt-4">
            <summary class="cursor-pointer text-sm font-semibold text-ink/55">{{ t('documents.advanced') }}</summary>
            <div class="mt-4 stack-y-lg">
              <label class="block text-sm font-semibold">{{ t('documents.source.label') }}
                <input v-model="officialSourceUrl" :disabled="intakeControlsDisabled" type="url" inputmode="url" maxlength="2000" placeholder="https://publisher.example.com/rulebook.pdf" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-normal outline-none focus:border-copper disabled:opacity-50">
                <span class="mt-1 block text-xs font-normal leading-5 text-ink/45">{{ t('documents.source.hint') }}</span>
              </label>
              <div class="rounded-lg border border-indigo/15 bg-indigo/[0.035] p-4">
                <p class="text-sm font-semibold">{{ t('documents.officialImport.title') }}</p>
                <p class="mt-1 text-xs leading-5 text-ink/50">{{ t('documents.officialImport.detail') }}</p>
                <label class="mt-3 flex items-start gap-3 text-sm leading-6 text-ink/65">
                  <input v-model="officialImportRightsConfirmed" :disabled="intakeControlsDisabled" type="checkbox" class="mt-1 h-5 w-5 shrink-0 accent-indigo">
                  <span>{{ t('documents.officialImport.consent') }}</span>
                </label>
                <button type="button" :disabled="!canImportOfficial" class="mt-3 min-h-11 rounded-lg bg-indigo px-4 py-2.5 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-40" @click="importOfficialRulebook">{{ importingOfficial ? t('documents.officialImport.importing') : t('documents.officialImport.action') }}</button>
              </div>

              <label v-if="editionOptions.length" class="block text-sm font-semibold">{{ t('documents.game.label') }}
                <select v-model="editionId" :disabled="intakeControlsDisabled" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-normal disabled:opacity-50">
                  <option value="">{{ t('documents.game.none') }}</option>
                  <option v-for="edition in editionOptions" :key="edition.id" :value="edition.id">{{ edition.label }}</option>
                </select>
              </label>
              <p v-else class="text-sm leading-6 text-ink/55">{{ t('documents.game.missing') }} <RouterLink :to="{ name: 'catalog' }" class="font-semibold text-indigo underline">{{ t('documents.game.organize') }}</RouterLink>{{ t('documents.game.missingTail') }}</p>

              <div v-if="modelConfiguration && !visualVisionCapable" class="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-950" role="status">
                <p><span class="font-semibold">{{ t('documents.visual.warningLead') }}</span>{{ t('documents.visual.warningBody') }}</p>
                <RouterLink :to="{ name: 'model-settings' }" class="mt-1 inline-block font-semibold text-indigo underline underline-offset-2">{{ t('documents.visual.settings') }}</RouterLink>
              </div>

              <label class="block text-sm font-semibold">{{ t('documents.learningGoal.label') }} <span class="font-normal text-ink/40">{{ t('documents.optional') }}</span>
                <textarea v-model="learningGoal" :disabled="intakeControlsDisabled" maxlength="500" rows="3" :placeholder="t('documents.learningGoal.placeholder')" class="mt-2 w-full resize-y rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-normal leading-6 outline-none focus:border-copper disabled:opacity-50" />
                <span class="mt-1 block text-xs font-normal leading-5 text-ink/45">{{ t('documents.learningGoal.hint') }}</span>
              </label>

              <label class="block text-sm font-semibold">{{ t('documents.sourceType') }}
                <select v-model="sourceType" :disabled="intakeControlsDisabled" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-3 py-2.5 disabled:opacity-50">
                  <option value="BASE_RULEBOOK">{{ t('documents.type.base') }}</option>
                  <option value="EXPANSION_RULEBOOK">{{ t('documents.type.expansion') }}</option>
                  <option value="OFFICIAL_FAQ">{{ t('documents.type.faq') }}</option>
                  <option value="OFFICIAL_ERRATA">{{ t('documents.type.errata') }}</option>
                </select>
              </label>
            </div>
          </details>

          <button :disabled="!canUpload" class="mt-5 w-full rounded-lg bg-copper px-5 py-3.5 font-semibold text-white disabled:cursor-not-allowed disabled:opacity-40">
            {{ preparingVersionId ? t('documents.submitPreparing') : uploading ? t('documents.submitUploading') : t('documents.submit') }}
          </button>
        </form>

        <section v-if="officialImportJob" class="mt-5 rounded-xl border border-copper/20 bg-paper p-5 text-left" role="status" aria-live="polite">
          <div class="flex items-start justify-between gap-4">
            <div class="min-w-0">
              <p class="tabletop-kicker">{{ officialImportCopy.title }}</p>
              <h2 class="mt-1 truncate font-display text-xl font-semibold">{{ officialImportJob.title }}</h2>
              <p class="mt-2 text-sm font-semibold text-copper">{{ officialImportStage() }}</p>
              <p class="mt-1 text-xs leading-5 text-ink/50">{{ officialImportCopy.safe }}</p>
            </div>
            <span v-if="officialImportBytes()" class="shrink-0 text-xs font-semibold text-indigo">{{ officialImportBytes() }}</span>
          </div>
          <div v-if="officialImportProgress() !== null" class="mt-4 h-2 overflow-hidden rounded-full bg-ink/10" :aria-label="`${officialImportProgress()}%`">
            <div class="h-full rounded-full bg-copper transition-[width]" :style="{ width: `${officialImportProgress()}%` }" />
          </div>
          <div v-else-if="officialImportJob.stage !== 'COMPLETED' && officialImportJob.stage !== 'FAILED'" class="mt-4 flex gap-1.5" aria-hidden="true">
            <span v-for="index in 6" :key="index" class="h-1.5 flex-1 rounded-full" :class="index <= ['QUEUED', 'CONNECTING', 'DOWNLOADING', 'COMPRESSING', 'VERIFYING_FILE', 'SAVING'].indexOf(officialImportJob.stage) + 1 ? 'bg-copper' : 'bg-ink/10'" />
          </div>
          <p class="mt-3 border-t border-ink/8 pt-3 text-xs text-ink/45">{{ officialImportCopy.background }}</p>
        </section>

        <p v-if="message && !preparingVersionId" class="mt-5 rounded-lg bg-indigo/5 px-4 py-3 text-sm text-indigo" aria-live="polite">{{ message }}</p>
        <div v-if="preparingVersionId" class="mt-5 rounded-xl border border-indigo/15 bg-indigo/5 p-4 text-left" role="status" aria-live="polite">
          <div class="flex items-start justify-between gap-4">
            <div>
              <p class="font-semibold text-ink">{{ t('documents.organizing') }}</p>
              <p class="mt-1 text-sm leading-6 text-ink/60">{{ message }}</p>
            </div>
            <span class="shrink-0 text-xs font-medium text-indigo">{{ preparationElapsedLabel() }}</span>
          </div>
          <p class="mt-3 border-t border-indigo/10 pt-3 text-xs leading-5 text-ink/45">{{ t('documents.background') }}</p>
        </div>
        <p v-if="errorMessage" class="mt-5 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ errorMessage }}</p>
        <div v-if="processingVersionId" class="mx-auto mt-4 h-1.5 max-w-md overflow-hidden rounded-full bg-ink/10">
          <div class="h-full bg-copper transition-all" :style="{ width: `${progress[processingVersionId]?.percentage ?? 0}%` }" />
        </div>
      </section>

      <section class="mt-14 border-t border-ink/10 pt-8">
        <div class="flex items-center justify-between gap-4">
          <div>
            <h2 ref="documentListHeading" tabindex="-1" class="font-display text-2xl font-semibold outline-none">{{ t('documents.list.title') }}</h2>
            <p class="mt-1 text-sm text-ink/45">{{ t('documents.list.description') }}</p>
          </div>
          <RouterLink :to="{ name: 'catalog' }" class="shrink-0 text-sm font-semibold text-indigo">{{ t('documents.list.manage') }}</RouterLink>
        </div>
        <p v-if="loading" class="mt-5 text-sm text-ink/45">{{ t('documents.list.loading') }}</p>
        <div v-else-if="documents.length === 0" class="mt-5 rounded-xl border border-dashed border-ink/20 p-8 text-center">
          <p class="font-semibold">{{ t('documents.empty.title') }}</p>
          <p class="mt-2 text-sm text-ink/45">{{ t('documents.empty.description') }}</p>
        </div>
        <ul v-else class="mt-5 divide-y divide-ink/10 border-y border-ink/10">
          <li v-for="entry in documents" :key="entry.document.id" class="py-5">
            <div class="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
              <div class="min-w-0">
                <p class="truncate font-semibold">{{ entry.document.title }}</p>
                <p class="mt-1 text-sm text-ink/45">
                  {{ documentStatusLabel(entry.latestVersion.status) }} · {{ Math.ceil(entry.latestVersion.size / 1024) }} KiB
                </p>
              </div>
              <div class="flex shrink-0 flex-wrap gap-2">
                <button v-if="entry.latestVersion.status === 'READY'" type="button" :disabled="bggSuggestionState(entry.document.id)?.status === 'loading' || Boolean(deletingDocumentId)" class="min-h-11 rounded-lg border border-indigo/20 px-4 py-2.5 text-sm font-semibold text-indigo hover:border-indigo/50 disabled:opacity-40" @click="loadBggSuggestions(entry.document.id)">{{ bggSuggestionState(entry.document.id)?.status === 'loading' ? t('documents.bgg.loading') : t('documents.bgg.open') }}</button>
                <RouterLink v-if="entry.latestVersion.status === 'READY'" :to="{ name: 'rulebook-reader', params: { versionId: entry.latestVersion.id } }" class="inline-flex min-h-11 items-center rounded-lg bg-indigo px-4 py-2.5 text-sm font-semibold text-white">{{ t('documents.read') }}</RouterLink>
                <button v-if="entry.latestVersion.status === 'READY'" :disabled="Boolean(preparingVersionId) || Boolean(deletingDocumentId)" class="rounded-lg border border-ink/15 px-4 py-2.5 text-sm font-semibold hover:border-copper/50 disabled:opacity-40" @click="startLesson(entry.latestVersion.id).catch((error: unknown) => errorMessage = error instanceof Error ? error.message : t('documents.error'))">{{ t('documents.start') }}</button>
                <button type="button" :disabled="Boolean(preparingVersionId) || Boolean(deletingDocumentId)" class="rounded-lg px-3 py-2.5 text-sm font-semibold text-ink/45 hover:bg-red-50 hover:text-red-700 disabled:opacity-40" @click="requestDeleteRulebook(entry)">{{ deletingDocumentId === entry.document.id ? t('documents.deleting') : t('documents.delete') }}</button>
              </div>
            </div>
            <div v-if="bggSuggestionState(entry.document.id)" class="mt-4 rounded-xl border border-indigo/15 bg-indigo/[0.035] p-4">
              <p v-if="bggSuggestionState(entry.document.id)?.status === 'loading'" class="text-sm text-ink/55" role="status">{{ t('documents.bgg.loadingDetail') }}</p>
              <div v-else-if="bggSuggestionState(entry.document.id)?.status === 'error'" role="alert">
                <p class="text-sm leading-6 text-red-700">{{ t('documents.bgg.error') }}</p>
                <button type="button" class="mt-3 min-h-11 rounded-lg border border-red-200 px-4 py-2 text-sm font-semibold text-red-700" @click="loadBggSuggestions(entry.document.id)">{{ t('documents.bgg.retry') }}</button>
              </div>
              <div v-else-if="bggSuggestionState(entry.document.id)?.candidates.length === 0">
                <p class="text-sm font-semibold">{{ t('documents.bgg.noneTitle') }}</p>
                <p class="mt-1 text-sm leading-6 text-ink/50">{{ t('documents.bgg.noneDetail') }}</p>
              </div>
              <template v-else>
                <p class="text-sm font-semibold">{{ bggSuggestionState(entry.document.id)!.candidates.length === 1 ? t('documents.bgg.oneTitle') : t('documents.bgg.manyTitle', { count: bggSuggestionState(entry.document.id)!.candidates.length }) }}</p>
                <p class="mt-1 text-xs leading-5 text-ink/50">{{ t('documents.bgg.review') }}</p>
                <ul class="mt-4 grid gap-3 lg:grid-cols-2">
                  <li v-for="candidate in bggSuggestionState(entry.document.id)!.candidates" :key="candidate.bggId" class="flex gap-3 rounded-lg border bg-paper p-3" :class="bggSuggestionState(entry.document.id)?.selectedBggId === candidate.bggId ? 'border-indigo/50 ring-1 ring-indigo/20' : 'border-ink/10'">
                    <img v-if="candidate.coverUrl" :src="candidate.coverUrl" :alt="t('documents.bgg.coverAlt', { name: candidate.name })" class="h-24 w-20 shrink-0 rounded object-contain" loading="lazy">
                    <div class="min-w-0 flex-1">
                      <div class="flex flex-wrap items-center gap-2">
                        <p class="font-semibold">{{ candidate.name }}<span v-if="candidate.publicationYear" class="font-normal text-ink/45"> · {{ candidate.publicationYear }}</span></p>
                        <span v-if="candidate.normalizedTitleMatch" class="rounded-full bg-emerald-50 px-2 py-0.5 text-[11px] font-semibold text-emerald-800">{{ t('documents.bgg.titleMatch') }}</span>
                      </div>
                      <p v-if="candidatePlayerLabel(candidate) || candidate.playingTimeMinutes" class="mt-1 text-xs text-ink/50">
                        {{ [candidatePlayerLabel(candidate), candidate.playingTimeMinutes ? t('documents.bgg.minutes', { minutes: candidate.playingTimeMinutes }) : ''].filter(Boolean).join(' · ') }}
                      </p>
                      <div class="mt-3 flex flex-wrap items-center gap-3">
                        <button type="button" class="min-h-11 rounded-lg bg-indigo px-3 py-2 text-sm font-semibold text-white" :aria-pressed="bggSuggestionState(entry.document.id)?.selectedBggId === candidate.bggId" @click="selectBggSuggestion(entry.document.id, candidate.bggId)">{{ bggSuggestionState(entry.document.id)?.selectedBggId === candidate.bggId ? t('documents.bgg.selected') : t('documents.bgg.select') }}</button>
                        <a :href="candidate.bggUrl" target="_blank" rel="noopener noreferrer" class="py-2 text-xs font-semibold text-indigo underline underline-offset-2">{{ t('documents.bgg.view') }}</a>
                      </div>
                    </div>
                  </li>
                </ul>
                <div v-if="bggSuggestionState(entry.document.id)?.selectedBggId" class="mt-4 rounded-lg bg-indigo/8 px-3 py-3">
                  <p class="text-sm leading-6 text-indigo">{{ t('documents.bgg.handoff') }}</p>
                  <button v-if="bggSuggestionState(entry.document.id)?.linkStatus !== 'linked'" type="button" :disabled="bggSuggestionState(entry.document.id)?.linkStatus === 'confirming'" class="mt-3 min-h-11 rounded-lg bg-ink px-4 py-2 text-sm font-semibold text-paper disabled:opacity-50" @click="confirmBggSuggestion(entry.document.id)">{{ bggSuggestionState(entry.document.id)?.linkStatus === 'confirming' ? t('documents.bgg.confirming') : t('documents.bgg.confirm') }}</button>
                  <p v-if="bggSuggestionState(entry.document.id)?.linkStatus === 'error'" class="mt-2 text-sm text-red-700" role="alert">{{ t('documents.bgg.linkError') }}</p>
                  <p v-if="bggSuggestionState(entry.document.id)?.linkStatus === 'linked'" class="mt-2 text-sm font-semibold text-emerald-800" role="status">{{ bggSuggestionState(entry.document.id)?.linkAlreadyImported ? t('documents.bgg.reused') : t('documents.bgg.linked') }}</p>
                </div>
                <p class="mt-4 text-[11px] text-ink/40">{{ t('documents.bgg.attribution') }}</p>
              </template>
            </div>
          </li>
        </ul>
      </section>

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
