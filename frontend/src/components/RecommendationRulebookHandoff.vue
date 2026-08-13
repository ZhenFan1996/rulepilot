<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import type { RecommendationGame, RecommendationProfile } from '@/components/gameRecommendationTypes'
import { notifyLoginRequired } from '@/lib/authSession'
import { notifyBackgroundWorkChanged } from '@/lib/backgroundWorkRefresh'
import { acceptProgressiveLesson, teachingRunIsActive } from '@/lib/liveLesson'
import { useLocale } from '@/lib/locale'
import { notifyTeachingLaunched } from '@/lib/teachingLaunch'
import {
  acceptImportJob,
  acceptJourneyRun,
  derivePlayerJourney,
  playerJourneyPollDelay,
  type PlayerJourneyDocumentProgress,
  type PlayerJourneyImportJob,
  type PlayerJourneyLesson,
  type PlayerJourneyPlan,
  type PlayerJourneyProjection,
  type PlayerJourneyRun,
} from '@/lib/playerJourney'
import { teachingActivityText, type TeachingActivity } from '@/lib/teachingProgress'

interface ImportedGame {
  game: { id: string; name: string }
  edition: { id: string; name: string }
  alreadyImported: boolean
}

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

interface RulebookCandidateResponse {
  configured: boolean
  candidates: RulebookCandidate[]
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
  finding: '桌游已保存，正在检索出版社、BGG、集石和可信规则库（已等待 {seconds} 秒，通常几秒，偶尔约 30 秒）…', found: '选择一份规则书', detail: '优先展示出版社来源，也会保留社区与可信规则库结果。请核对语言和版本；PDF 直链与已识别的连续规则页图片都可直接导入。',
  sources: { PUBLISHER: '出版社 / 权利方来源', TRUSTED_REPOSITORY: '可信规则库', COMMUNITY_PLATFORM: '社区规则书来源（如 BGG / 集石）', PUBLIC_WEB: '公开来源（请重点核对）' },
  direct: 'PDF 可直接核验并下载', gallery: '连续规则页图片，可合成为 PDF', page: '来源页，需要继续查找文件', publisher: '发布者', language: '语言', edition: '版本', unknown: '未标明', choose: '选择这份', selected: '已选择', open: '打开来源页',
  consent: '我确认该链接来自有权提供这份规则书的来源，并授权 RulePilot 下载用于我的个人讲解。',
  import: '下载规则书并生成讲解', manual: '改用公开链接或本地上传',
  browserRequired: '已经找到这份文件，但来源网站要求在浏览器里完成隐私选择、刷新临时链接或登录。打开原始下载页取得 PDF 后，回到 RulePilot 上传即可继续；桌游、版本和讲解偏好都已保留。',
  sourcePageHandoff: '这是经过核对的来源页面，但搜索结果没有提供可验证的 PDF 直链。请在来源网站核对语言和版本并下载 PDF，再回到 RulePilot 上传；桌游和讲解偏好都已保留。',
  browserAction: '在来源网站继续下载',
  unavailable: '当前没有找到可审阅的规则书来源。你仍可粘贴公开 PDF 链接或上传自己的规则书。',
  login: '登录后即可保留这次选择并继续找规则书。', loginAction: '打开桌游详情并继续',
  error: '这一步暂时没有完成；推荐对话和已选桌游不会受影响。', partialFailure: '已生成的章节仍可阅读，但后台生成或核对没有完整结束。可以安全重试，现有内容不会丢失。', retry: '重试当前步骤', close: '关闭小窗', change: '换一款',
  safe: '可以关闭这个小窗继续聊天；下载、规则书处理和讲解生成会继续。',
  progress: '完整链路进度', current: '现在正在做', pollingWarning: '暂时没有拿到最新进度，正在自动重试；已确认的进度不会倒退。',
  gameBound: '桌游已绑定', rulebook: '获取规则书', document: '读取规则书', lesson: '生成讲解', questions: '进入答疑',
  readLesson: '打开已生成的讲解', askQuestions: '切换为规则答疑', catalog: '我的桌游',
  readRulebook: '先阅读原规则书', rulebookReady: '规则书已经可以阅读；讲解会继续在后台生成。', rulebookAvailable: '原规则书已就绪，可随时与讲解对照阅读。',
  readable: '讲解已有可读内容；后台仍可能继续核对和补全。', complete: '讲解已经完整生成并通过后台收尾。',
  phase: {
    GAME_BINDING: '正在把推荐结果加入“我的桌游”', RULEBOOK_DISCOVERY: '正在寻找可审阅的规则书来源', SOURCE_REVIEW: '等待你核对规则书语言和版本',
    IMPORT_QUEUED: '规则书下载已排队', IMPORT_CONNECTING: '正在连接规则书来源', IMPORT_DOWNLOADING: '正在下载规则书', IMPORT_COMPRESSING: '文件较大，正在压缩 PDF', IMPORT_VERIFYING: '正在核验文件确实是可读取的 PDF', IMPORT_SAVING: '正在保存规则书并绑定到这款桌游',
    DOCUMENT_PROCESSING: '正在提取规则、生成页面并建立检索结构', TEACHING_PREPARATION_QUEUED: '规则书已就绪，讲解准备任务正在排队', TEACHING_PREPARING: '正在通读规则书并组织讲解章节', LESSON_GENERATION_QUEUED: '讲解大纲已完成，正文生成正在排队', LESSON_GENERATING: '正在逐章生成、引用并核对讲解', LESSON_READABLE: '第一批讲解内容已经可以阅读', LESSON_COMPLETE: '完整讲解已经生成', FAILED: '当前步骤需要处理',
  },
  bytes: (done: string, total: string) => `${done} / ${total}`, pages: (done: number, total: number) => `第 ${done} / ${total} 页`, chapters: (done: number, total: number | null) => total ? `已有 ${done} / ${total} 章可读` : `已有 ${done} 章可读`,
} : {
  eyebrow: 'Recommendation to Q&A', title: `${props.game.name} selected`, preparing: 'Adding the game to My Games and finding reviewable rulebooks…',
  finding: 'Game saved. Searching publishers, BGG, Gstone, and trusted repositories ({seconds}s elapsed; usually a few seconds, occasionally about 30s)…', found: 'Choose a rulebook', detail: 'Publisher sources come first, with useful community and trusted-repository results preserved. Review language and edition; direct PDFs and recognized ordered page-image documents can both be imported.',
  sources: { PUBLISHER: 'Publisher / rights-holder', TRUSTED_REPOSITORY: 'Trusted rules repository', COMMUNITY_PLATFORM: 'Community rulebook source (such as BGG / Gstone)', PUBLIC_WEB: 'Public source (review carefully)' },
  direct: 'Direct PDF ready for verification', gallery: 'Ordered rulebook pages; RulePilot can build the PDF', page: 'Source page; continue there', publisher: 'Provider', language: 'Language', edition: 'Edition', unknown: 'Not stated', choose: 'Choose this one', selected: 'Selected', open: 'Open source page',
  consent: 'I confirm that this source may provide the rulebook and authorize RulePilot to download it for my personal guide.',
  import: 'Download and generate guide', manual: 'Use a public URL or local upload',
  browserRequired: 'The file was found, but its source requires an in-browser privacy choice, refreshed temporary link, or sign-in. Download it there, then return to upload it; the game, edition, and guide preferences are preserved.',
  sourcePageHandoff: 'This source page was verified, but search did not expose a verifiable PDF URL. Review the language and edition there, download the PDF, then return to upload it; the game and guide preferences are preserved.',
  browserAction: 'Continue on the source site',
  unavailable: 'No reviewable rulebook source was found. You can still paste a public PDF URL or upload your own rulebook.',
  login: 'Sign in to keep this selection and continue to its rulebook.', loginAction: 'Open game details and continue',
  error: 'This step did not complete. The conversation and selected game are unaffected.', partialFailure: 'Published chapters remain readable, but background generation or review did not finish. You can retry safely without losing existing content.', retry: 'Retry this step', close: 'Close', change: 'Choose another game',
  safe: 'You may close this panel and keep chatting. Download, rulebook processing, and guide generation will continue.',
  progress: 'End-to-end progress', current: 'Working on', pollingWarning: 'The latest update is temporarily unavailable. Retrying automatically without rolling back confirmed progress.',
  gameBound: 'Game linked', rulebook: 'Get rulebook', document: 'Read rules', lesson: 'Generate guide', questions: 'Start Q&A',
  readLesson: 'Open the generated guide', askQuestions: 'Switch to rules Q&A', catalog: 'My Games',
  readRulebook: 'Read the original rulebook now', rulebookReady: 'The rulebook is readable now while the guide continues in the background.', rulebookAvailable: 'The original rulebook is ready to compare with the guide at any time.',
  readable: 'Readable guide content is available while background review may continue.', complete: 'The complete guide is generated and background finishing is done.',
  phase: {
    GAME_BINDING: 'Adding the recommendation to My Games', RULEBOOK_DISCOVERY: 'Finding reviewable rulebook sources', SOURCE_REVIEW: 'Waiting for your language and edition review',
    IMPORT_QUEUED: 'Rulebook download is queued', IMPORT_CONNECTING: 'Connecting to the rulebook source', IMPORT_DOWNLOADING: 'Downloading the rulebook', IMPORT_COMPRESSING: 'Compressing the oversized PDF', IMPORT_VERIFYING: 'Verifying that the file is a readable PDF', IMPORT_SAVING: 'Saving and linking the rulebook to this game',
    DOCUMENT_PROCESSING: 'Extracting rules, rendering pages, and building retrieval data', TEACHING_PREPARATION_QUEUED: 'The rulebook is ready and guide preparation is queued', TEACHING_PREPARING: 'Reading the rules and organizing guide chapters', LESSON_GENERATION_QUEUED: 'The outline is ready and chapter generation is queued', LESSON_GENERATING: 'Generating, citing, and reviewing the guide chapter by chapter', LESSON_READABLE: 'The first guide content is ready to read', LESSON_COMPLETE: 'The complete guide is ready', FAILED: 'This step needs attention',
  },
  bytes: (done: string, total: string) => `${done} / ${total}`, pages: (done: number, total: number) => `Page ${done} / ${total}`, chapters: (done: number, total: number | null) => total ? `${done} / ${total} chapters readable` : `${done} chapters readable`,
})

const imported = ref<ImportedGame | null>(null)
const candidates = ref<RulebookCandidate[]>([])
const selected = ref<RulebookCandidate | null>(null)
const openedSource = ref<RulebookCandidate | null>(null)
const consent = ref(false)
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
let journeyTimer: ReturnType<typeof setTimeout> | null = null
let refreshingJourney = false
const ensuredLessonPlans = new Set<string>()

const canImport = computed(() => Boolean(
  selected.value
  && selected.value.acquisitionMode !== 'SOURCE_PAGE'
  && consent.value
  && state.value === 'review',
))
const findingText = computed(() => copy.value.finding.replace('{seconds}', String(findingSeconds.value)))
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
const currentPhaseText = computed(() => copy.value.phase[projection.value.phase])
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
    imported.value = await response.json() as ImportedGame
    persistJourney()
    await discover(request)
  } catch {
    if (request === sequence) state.value = 'error'
  }
}

async function discover(request = sequence) {
  if (!imported.value || request !== sequence) return
  state.value = 'finding'
  findingSeconds.value = 0
  if (findingClock) clearInterval(findingClock)
  findingClock = setInterval(() => { findingSeconds.value += 1 }, 1000)
  try {
    const parameters = new URLSearchParams({ editionId: imported.value.edition.id, language: locale.value })
    const response = await fetch(`/api/v1/documents/rulebook-candidates?${parameters.toString()}`, { credentials: 'include' })
    if (request !== sequence) return
    if (response.status === 401 || response.status === 403) return requireLogin()
    if (!response.ok) throw new Error('discovery failed')
    const result = await response.json() as RulebookCandidateResponse
    candidates.value = result.candidates
    state.value = result.configured && result.candidates.length ? 'review' : 'unavailable'
    persistJourney()
  } catch {
    if (request === sequence) state.value = 'error'
  } finally {
    if (request === sequence && findingClock) {
      clearInterval(findingClock)
      findingClock = null
    }
  }
}

function choose(candidate: RulebookCandidate) {
  if (candidate.acquisitionMode === 'SOURCE_PAGE') {
    openedSource.value = candidate
    window.open(candidate.url, '_blank', 'noopener,noreferrer')
    return
  }
  openedSource.value = null
  selected.value = candidate
  consent.value = false
  persistJourney()
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
      }),
    })
    if (request !== sequence) return
    if (response.status === 401 || response.status === 403) return requireLogin()
    if (!response.ok) throw new Error('import failed')
    const incoming = normalizeImportJob(await response.json() as OfficialImportJob)
    importJob.value = acceptImportJob(importJob.value?.id === incoming.id ? importJob.value : null, incoming) as OfficialImportJob
    preparationRunId.value = incoming.teachingPreparationRunId
    consent.value = true
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
      if (currentJob.stage === 'FAILED' && currentJob.errorCode === 'SOURCE_BROWSER_REQUIRED') {
        state.value = 'browser-required'
        persistJourney()
        return
      }
      if (!preparationRunId.value && currentJob.teachingPreparationRunId) {
        preparationRunId.value = currentJob.teachingPreparationRunId
      }
    }

    const versionId = currentJob.documentVersionId
    if (versionId && (currentJob.teachingHandoffState === 'WAITING_FOR_DOCUMENT' || !documentProgress.value?.complete)) {
      const progress = await checkedJson<PlayerJourneyDocumentProgress>(
        `/api/v1/document-versions/${encodeURIComponent(versionId)}/progress/snapshot`, true,
      )
      if (request !== sequence) return
      if (progress) documentProgress.value = progress
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
      scheduleJourney(playerJourneyPollDelay(
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
    if (action === 'IMPORT_RULEBOOK') return await enqueueImport()
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

function clearJourneyTimer() {
  if (journeyTimer) clearTimeout(journeyTimer)
  journeyTimer = null
}

function resetJourneyState() {
  clearJourneyTimer()
  imported.value = null
  candidates.value = []
  selected.value = null
  openedSource.value = null
  consent.value = false
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

function normalizeImportJob(job: OfficialImportJob): OfficialImportJob {
  return {
    ...job,
    downloadedBytes: Number(job.downloadedBytes ?? 0),
    totalBytes: job.totalBytes === undefined ? null : job.totalBytes,
    teachingHandoffState: job.teachingHandoffState ?? 'NOT_REQUESTED',
    teachingPreparationRunId: job.teachingPreparationRunId ?? null,
    duplicate: Boolean(job.duplicate),
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
      selected?: RulebookCandidate
      importJob?: OfficialImportJob
      preparationRunId?: string
      teachingRunId?: string
    }
    if (!stored.imported) return false
    imported.value = stored.imported
    candidates.value = Array.isArray(stored.candidates) ? stored.candidates : []
    selected.value = stored.selected ?? null
    if (stored.importJob?.id) {
      importJob.value = normalizeImportJob(stored.importJob)
      preparationRunId.value = stored.preparationRunId ?? stored.importJob.teachingPreparationRunId
      teachingRunId.value = stored.teachingRunId ?? null
      consent.value = true
      state.value = 'journey'
      scheduleJourney(0)
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
  if (findingClock) clearInterval(findingClock)
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
      <p v-if="state === 'preparing' || state === 'finding'" class="flex items-center gap-3 text-sm text-ink/65" role="status">
        <span class="size-2 animate-pulse rounded-full bg-copper" aria-hidden="true" />
        {{ state === 'preparing' ? copy.preparing : findingText }}
      </p>

      <template v-else-if="state === 'review'">
        <h4 class="font-display text-lg font-semibold">{{ copy.found }}</h4>
        <p class="mt-1 text-xs leading-5 text-ink/50">{{ copy.detail }}</p>
        <ol class="mt-4 grid grid-cols-2 gap-2 text-xs sm:grid-cols-5" :aria-label="copy.progress">
          <li v-for="milestone in milestones" :key="milestone.label" :data-fact-confirmed="milestone.done ? 'true' : 'false'" class="rounded-lg border px-2.5 py-2" :class="milestone.done ? 'border-emerald-200 bg-emerald-50 text-emerald-800' : milestone.active ? 'border-copper/30 bg-copper/5 font-semibold text-copper' : 'border-ink/8 bg-paper text-ink/40'">
            <span class="mr-1" aria-hidden="true">{{ milestone.done ? '✓' : milestone.active ? '●' : '○' }}</span>{{ milestone.label }}
          </li>
        </ol>
        <ul class="mt-4 stack-y-md">
          <li v-for="candidate in candidates" :key="candidate.url" class="rounded-xl border bg-paper p-4" :class="selected?.url === candidate.url ? 'border-copper/60 ring-2 ring-copper/10' : 'border-ink/10'">
            <div class="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
              <div class="min-w-0">
                <p class="font-semibold">{{ candidate.title }}</p>
                <a :href="candidate.url" target="_blank" rel="noopener noreferrer" class="mt-1 block break-all text-xs font-semibold text-indigo underline underline-offset-2">{{ candidate.sourceDomain }} ↗</a>
                <p class="mt-2 text-xs leading-5 text-ink/55">{{ copy.publisher }}：{{ candidate.publisher || copy.unknown }} · {{ copy.language }}：{{ candidate.language || copy.unknown }} · {{ copy.edition }}：{{ candidate.edition || copy.unknown }}</p>
                <p class="mt-1 text-xs font-semibold" :class="candidate.sourceType === 'PUBLIC_WEB' ? 'text-amber-700' : 'text-emerald-700'">{{ copy.sources[candidate.sourceType] }}</p>
                <p class="mt-1 text-xs text-ink/45">{{ candidate.acquisitionMode === 'DIRECT_PDF' ? copy.direct : candidate.acquisitionMode === 'IMAGE_GALLERY' ? copy.gallery : copy.page }}</p>
              </div>
              <button type="button" class="min-h-11 shrink-0 rounded-lg border border-copper/35 px-4 text-sm font-semibold text-copper" :aria-pressed="candidate.acquisitionMode !== 'SOURCE_PAGE' ? selected?.url === candidate.url : undefined" @click="choose(candidate)">{{ candidate.acquisitionMode === 'SOURCE_PAGE' ? copy.open : selected?.url === candidate.url ? copy.selected : copy.choose }}</button>
            </div>
          </li>
        </ul>
        <div v-if="openedSource" class="mt-4 rounded-xl border border-indigo/15 bg-indigo/5 p-4 text-sm leading-6 text-ink/65" role="status">
          <p>{{ copy.sourcePageHandoff }}</p>
          <a :href="openedSource.url" target="_blank" rel="noopener noreferrer" class="mt-3 inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.browserAction }} ↗</a>
          <RouterLink :to="manualRoute" class="ml-4 inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.manual }} →</RouterLink>
        </div>
        <div v-if="selected" class="mt-4 rounded-xl border border-indigo/15 bg-indigo/5 p-4">
          <label class="flex items-start gap-3 text-sm leading-6 text-ink/65">
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
            <p class="mt-1 text-sm font-semibold text-ink" role="status">{{ currentPhaseText }}</p>
            <p v-if="journeyDetail" class="mt-1 text-xs leading-5 text-ink/50">{{ journeyDetail }}</p>
          </div>
          <span class="font-mono text-sm font-semibold text-copper">{{ projection.progress }}%</span>
        </div>
        <div class="mt-3 h-2 overflow-hidden rounded-full bg-copper/10" role="progressbar" :aria-label="copy.progress" aria-valuemin="0" aria-valuemax="100" :aria-valuenow="projection.progress">
          <div class="h-full rounded-full bg-copper transition-[width] duration-500" :style="{ width: `${projection.progress}%` }" />
        </div>
        <ol class="mt-4 grid grid-cols-2 gap-2 text-xs sm:grid-cols-5" :aria-label="copy.progress">
          <li v-for="milestone in milestones" :key="milestone.label" :data-fact-confirmed="milestone.done ? 'true' : 'false'" class="rounded-lg border px-2.5 py-2" :class="milestone.done ? 'border-emerald-200 bg-emerald-50 text-emerald-800' : milestone.active ? 'border-copper/30 bg-copper/5 font-semibold text-copper' : 'border-ink/8 bg-paper text-ink/40'">
            <span class="mr-1" aria-hidden="true">{{ milestone.done ? '✓' : milestone.active ? '●' : '○' }}</span>{{ milestone.label }}
          </li>
        </ol>
        <p class="mt-4 rounded-xl border border-indigo/10 bg-indigo/5 px-4 py-3 text-xs leading-5 text-ink/60">{{ copy.safe }}</p>
        <p v-if="pollingWarning" class="mt-3 rounded-lg bg-amber-50 px-4 py-3 text-xs leading-5 text-amber-900" role="status">{{ copy.pollingWarning }}</p>
        <div v-if="projection.canReadRulebook" class="mt-4 rounded-xl border border-indigo/15 bg-indigo/5 p-4 text-sm leading-6 text-ink/65">
          <p>{{ projection.canReadLesson ? copy.rulebookAvailable : copy.rulebookReady }}</p>
          <button type="button" class="mt-3 min-h-11 rounded-lg border border-indigo/25 px-4 font-semibold text-indigo" @click="emit('open-rulebook', journeyStatus)">{{ copy.readRulebook }}</button>
        </div>
        <div v-if="projection.state === 'failed' || projection.canReadLesson && projection.retryAction" class="mt-4 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-800" role="alert">
          <p>{{ projection.canReadLesson ? copy.partialFailure : copy.error }}<span v-if="projection.errorCode" class="mt-1 block font-mono text-xs">{{ projection.errorCode }}</span></p>
          <button type="button" :disabled="retrying" class="mt-2 min-h-11 font-semibold underline disabled:opacity-40" @click="retryJourney">{{ copy.retry }}</button>
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
        <button type="button" class="mt-3 inline-flex min-h-11 items-center font-semibold text-indigo underline" @click="discover()">{{ copy.retry }} →</button>
        <RouterLink :to="manualRoute" class="ml-4 inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.manual }} →</RouterLink>
      </div>

      <div v-else-if="state === 'browser-required'" class="text-sm leading-6 text-ink/65" role="status">
        <p>{{ copy.browserRequired }}</p>
        <a v-if="selected" :href="selected.url" target="_blank" rel="noopener noreferrer" class="mt-3 inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.browserAction }} ↗</a>
        <RouterLink :to="manualRoute" class="ml-4 inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.manual }} →</RouterLink>
      </div>

      <div v-else class="text-sm text-danger" role="alert">
        <p>{{ copy.error }}</p>
        <button type="button" class="mt-2 min-h-11 font-semibold underline" @click="retryJourney">{{ copy.retry }}</button>
        <RouterLink v-if="imported" :to="manualRoute" class="ml-4 inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.manual }}</RouterLink>
      </div>
    </div>
  </aside>
</template>
