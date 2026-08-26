<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import PlayerWorkStatusText from '@/components/PlayerWorkStatusText.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import { useModalFocus } from '@/composables/useModalFocus'
import {
  backgroundWorkStorageKeys,
  clearLegacyBackgroundWorkStorage,
  parseBackgroundTeachingItems,
  reconcileBackgroundTeaching,
  type BackgroundTeachingItem,
  type BackgroundWorkStorageKeys,
} from '@/lib/backgroundTeachingStatus'
import { BACKGROUND_WORK_CHANGED_EVENT } from '@/lib/backgroundWorkRefresh'
import {
  parseActiveTeachingRuns,
  parseDocumentProgress,
  parseExpectedAssistantRun,
  parseLatestTeachingRun,
  parseOwnedDocuments,
  parsePreparationTeachingPlans,
  parseRulebookImports,
  parseTeachingProgressPlan,
  parseTeachingRunProgress,
  parseTeachingPlans,
  parseUploadedHandoffs,
  validateDocumentRelationships,
  type DocumentProgress,
  type DocumentSummary,
  type RulebookImportJob,
  type UploadedTeachingHandoff,
} from '@/lib/backgroundWorkSnapshot'
import { mergeDocumentProgress } from '@/lib/documentProgress'
import { playerFacingTitle } from '@/lib/lessonPresentation'
import { useLocale } from '@/lib/locale'
import { playerJourneyRunIsTerminal } from '@/lib/playerJourney'
import {
  playerWorkStatus,
  type PlayerCapability,
  type PlayerReadiness,
  type PlayerTerminality,
  type PlayerWorkOutcome,
  type PlayerWorkStage,
  type PlayerWorkStatus,
} from '@/lib/playerWorkStatus'
import { TEACHING_LAUNCHED_EVENT, teachingLaunchDetail } from '@/lib/teachingLaunch'
import {
  mergeTeachingRunProgress,
  processedTeachingChapterCount,
  recentTeachingActivitySteps,
  recentTeachingPreparationActivitySteps,
  rejectedTeachingChapterCount,
  supportedTeachingChapterCount,
  teachingChapterFailureText,
  type TeachingProgressPlan,
  type TeachingRunProgress,
} from '@/lib/teachingProgress'

const props = defineProps<{ username: string }>()
const emit = defineEmits<{
  status: [activeCount: number, finishedCount: number, activeTitle: string, finishedTitle: string]
}>()

const dialog = ref<HTMLElement | null>(null)
const requestedRestoreTarget = ref<HTMLElement | null>(null)

type WorkState = 'active' | 'complete' | 'failed'
interface WorkItem {
  id: string
  kind: 'download' | 'rulebook' | 'lesson'
  title: string
  status: PlayerWorkStatus
  detail: string
  context: string
  state: WorkState
  progress: number | null
  target: { name: string; query?: Record<string, string> }
  updatedAt?: string
}

const { locale } = useLocale()
const open = ref(false)
const loading = ref(true)
const unavailable = ref(false)
const clearingFinished = ref(false)
const dismissError = ref('')
const activeTeaching = ref<BackgroundTeachingItem[]>([])
const completedTeaching = ref<BackgroundTeachingItem[]>([])
const teachingStates = ref<Record<string, string>>({})
const teachingRunDetails = ref<Record<string, TeachingRunProgress>>({})
const teachingPlanDetails = ref<Record<string, TeachingProgressPlan>>({})
const imports = ref<RulebookImportJob[]>([])
const uploadedTeachingHandoffs = ref<UploadedTeachingHandoff[]>([])
const documents = ref<DocumentSummary[]>([])
const documentProgress = ref<Record<string, DocumentProgress>>({})
const preparationStates = ref<Record<string, string>>({})
const preparationSubjects = ref<Record<string, string>>({})
const preparationRunDetails = ref<Record<string, TeachingRunProgress>>({})
const preparationTeachingTransitions = ref<PreparationTeachingTransition[]>([])
const preparationTeachingPlanIds = new Map<string, { documentVersionId: string; planId: string }>()
const dismissedTeachingRunIds = ref<Set<string>>(new Set())
const dismissedImportIds = ref<Set<string>>(new Set())
const dismissedUploadedHandoffIds = ref<Set<string>>(new Set())
const titles = new Map<string, string>()
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
let account = ''
let refreshGeneration = 0
let activeRefreshController: AbortController | null = null

useModalFocus({
  dialog,
  open,
  requestClose: () => { open.value = false },
  restoreFocus: () => requestedRestoreTarget.value,
})

const copy = computed(() => locale.value === 'zh-CN' ? {
  trigger: '后台任务', title: '后台任务', close: '关闭后台任务', empty: '当前没有后台任务。',
  safe: '可以继续浏览，离开页面不会中断这些任务。', retrying: '暂时没有拿到最新进度，正在自动重试。',
  download: '获取规则书', rulebook: '读取规则书', lesson: '生成讲解', done: '已完成', failed: '需要处理',
  queued: '等待下载', connecting: '正在连接规则书来源', downloading: '正在下载规则书内容', compressing: '文件较大，正在压缩 PDF', verifying: '正在核验 PDF',
  saving: '正在保存并交给规则书读取', uploaded: '等待开始读取', extracting: '正在提取规则文字',
  validating: '正在核验规则书文件', rendering: '正在生成规则书页面', structuring: '正在整理章节与图例',
  chunking: '正在整理规则内容', embedding: '正在整理规则内容', indexing: '正在完成规则书读取', teaching: '正在组织讲解',
  rulebookFailed: '规则书读取失败，讲解无法开始',
  waitingForTeaching: '规则书已保存，读取完成后会自动开始讲解', launchingTeaching: '规则书已就绪，正在启动讲解任务',
  teachingLaunched: '规则书已保存，讲解任务已交给后台', teachingLaunchFailed: '规则书已保存，但自动讲解没有启动',
  preparationReceived: '讲解任务已接收', preparationReading: '正在确认规则书可以用于讲解',
  preparationPlanning: '正在整理讲解结构', preparationFailed: '讲解准备没有完成，可在讲解中心重试',
  teachingPlanningEvidence: '正在确定各章节需要核对的规则', teachingRetrieving: '正在查找各章节需要的规则依据',
  teachingVerifying: '正在逐条核对讲解与规则依据', teachingComposing: '正在把规则整理成可读的讲解',
  teachingPackaging: '正在补充规则页与图示', teachingReviewing: '正在复核讲解中的规则结论',
  publishedChapters: (done: number, total: number | null) => total
    ? `已发布 ${done} / ${total} 章`
    : `已发布 ${done} 章`,
  processedChapters: (done: number, total: number | null) => total
    ? `已处理 ${done} / ${total} 章`
    : `已处理 ${done} 章`,
  rejectedChapters: (done: number) => `${done} 章未发布`,
  noPublishedChapter: '尚未发布可读章节',
  recoveringMissingResult: '准备任务已经结束，但还没有找到可读章节；后台正在自动恢复',
  automaticRecovery: '上一次任务没有留下可读章节，正在进行第 1 / 1 次自动恢复',
  recoveryExhausted: '自动恢复后仍没有生成可读章节',
  recoveryExhaustedContext: '已完成第 1 / 1 次自动恢复；请打开讲解中心重试',
  failureCode: (code: string) => `失败记录：${code}`,
  bytes: (done: string, total: string) => `${done} / ${total}`, pages: (done: number, total: number) => `第 ${done} / ${total} 页`,
  browserRequired: '需要在来源网站刷新链接或登录',
  openRulebooks: '打开规则书', openLessons: '打开讲解中心',
  clearing: '正在清除…', clearFailed: '有些失败记录没有清除成功，请稍后重试。',
} : {
  trigger: 'Background work', title: 'Background work', close: 'Close background work', empty: 'No background work right now.',
  safe: 'You can keep browsing. Leaving this page will not interrupt these tasks.', retrying: 'Progress is temporarily unavailable; retrying automatically.',
  download: 'Get rulebook', rulebook: 'Read rulebook', lesson: 'Generate lesson', done: 'Complete', failed: 'Needs attention',
  queued: 'Waiting to download', connecting: 'Connecting to rulebook source', downloading: 'Downloading rulebook content', compressing: 'Compressing the oversized PDF', verifying: 'Verifying PDF',
  saving: 'Saving and handing off for reading', uploaded: 'Waiting to read', extracting: 'Extracting searchable rules',
  validating: 'Validating the rulebook file', rendering: 'Rendering rulebook pages', structuring: 'Organizing chapters and visual references',
  chunking: 'Organizing rulebook content', embedding: 'Organizing rulebook content', indexing: 'Finishing rulebook reading', teaching: 'Organizing the guide',
  rulebookFailed: 'Rulebook reading failed, so the guide could not start',
  waitingForTeaching: 'Rulebook saved; the guide will start automatically when reading completes', launchingTeaching: 'Rulebook ready; starting the guide task',
  teachingLaunched: 'Rulebook saved; guide work was handed to the background', teachingLaunchFailed: 'Rulebook saved, but the automatic guide did not start',
  preparationReceived: 'Guide task received', preparationReading: 'Confirming that the rulebook is ready for a guide',
  preparationPlanning: 'Organizing the guide structure', preparationFailed: 'Guide preparation did not finish; retry from the guide center',
  teachingPlanningEvidence: 'Deciding which rules each section must verify', teachingRetrieving: 'Finding rule evidence for each section',
  teachingVerifying: 'Checking each guide claim against the rules', teachingComposing: 'Turning the rules into a readable guide',
  teachingPackaging: 'Adding rule pages and visual references', teachingReviewing: 'Reviewing the guide\'s rule claims',
  publishedChapters: (done: number, total: number | null) => total
    ? `${done} of ${total} chapters published`
    : `${done} chapters published`,
  processedChapters: (done: number, total: number | null) => total
    ? `${done} of ${total} chapters processed`
    : `${done} chapters processed`,
  rejectedChapters: (done: number) => `${done} chapters not published`,
  noPublishedChapter: 'No readable chapter has been published yet',
  recoveringMissingResult: 'Preparation ended without a readable chapter; background recovery is running',
  automaticRecovery: 'The previous task left no readable chapter; automatic recovery 1 of 1 is running',
  recoveryExhausted: 'Automatic recovery still produced no readable chapter',
  recoveryExhaustedContext: 'Automatic recovery 1 of 1 finished; open the lesson center to retry',
  failureCode: (code: string) => `Failure record: ${code}`,
  bytes: (done: string, total: string) => `${done} / ${total}`, pages: (done: number, total: number) => `Page ${done} / ${total}`,
  browserRequired: 'Refresh the link or sign in on the source site',
  openRulebooks: 'Open rulebooks', openLessons: 'Open lesson center',
  clearing: 'Clearing…', clearFailed: 'Some failed records could not be cleared. Please try again.',
})

function workStatus(
  stage: PlayerWorkStage,
  capability: PlayerCapability,
  readiness: PlayerReadiness,
  terminality: PlayerTerminality,
  outcome: PlayerWorkOutcome = 'none',
) {
  return playerWorkStatus(stage, { capability, readiness, terminality, outcome }, locale.value)
}

function importPlayerStatus(
  job: RulebookImportJob,
  documentStatus: string | undefined,
  state: WorkState,
) {
  const rulebookUsable = documentStatus === 'READY'
  if (state === 'failed') {
    return workStatus(
      'NEEDS_ACTION',
      rulebookUsable ? 'rulebook' : 'none',
      rulebookUsable ? 'usable' : 'unavailable',
      'terminal',
      'needs-action',
    )
  }
  if (job.stage !== 'COMPLETED') {
    return workStatus('ACQUIRING_RULEBOOK', 'none', 'unavailable', 'active')
  }
  if (job.teachingHandoffState === 'WAITING_FOR_DOCUMENT' || documentStatus && documentStatus !== 'READY') {
    return workStatus('READING_RULEBOOK', 'none', 'unavailable', 'active')
  }
  if (job.teachingHandoffState === 'LAUNCHING' || job.teachingHandoffState === 'LAUNCHED') {
    return workStatus('ORGANIZING_GUIDE', 'rulebook', 'usable', 'active')
  }
  return workStatus('RULEBOOK_READY', 'rulebook', 'usable', 'terminal')
}

function formatBytes(value: number) {
  if (value < 1024 * 1024) return `${Math.max(1, Math.round(value / 1024))} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

function importStage(job: RulebookImportJob) {
  if (job.stage === 'COMPLETED') {
    if (job.teachingErrorCode === 'DOCUMENT_PROCESSING_FAILED') return copy.value.rulebookFailed
    if (job.teachingErrorCode === 'TEACHING_RECOVERY_EXHAUSTED') return copy.value.recoveryExhausted
    if (job.teachingHandoffState === 'WAITING_FOR_DOCUMENT') return job.teachingAutomaticRecoveryCount
      ? copy.value.automaticRecovery
      : copy.value.waitingForTeaching
    if (job.teachingHandoffState === 'LAUNCHING') return job.teachingAutomaticRecoveryCount
      ? copy.value.automaticRecovery
      : copy.value.launchingTeaching
    if (job.teachingHandoffState === 'LAUNCHED') return copy.value.teachingLaunched
    if (job.teachingHandoffState === 'FAILED') return copy.value.teachingLaunchFailed
  }
  return {
    QUEUED: copy.value.queued,
    CONNECTING: copy.value.connecting,
    DOWNLOADING: copy.value.downloading,
    COMPRESSING: copy.value.compressing,
    VERIFYING_FILE: copy.value.verifying,
    SAVING: copy.value.saving,
    COMPLETED: copy.value.done,
    FAILED: copy.value.failed,
  }[job.stage]
}

function importState(job: RulebookImportJob, documentFailed: boolean): WorkState {
  if (job.stage === 'FAILED'
    || job.teachingHandoffState === 'FAILED'
    || job.teachingErrorCode === 'DOCUMENT_PROCESSING_FAILED'
    || documentFailed) return 'failed'
  if (job.teachingHandoffState === 'LAUNCHED' && job.teachingPreparationRunId) return 'active'
  if (job.stage !== 'COMPLETED'
    || job.teachingHandoffState === 'WAITING_FOR_DOCUMENT'
    || job.teachingHandoffState === 'LAUNCHING') return 'active'
  return 'complete'
}

function officialImportFinished(job: RulebookImportJob) {
  if (job.stage === 'FAILED'
    || job.teachingHandoffState === 'FAILED'
    || job.teachingErrorCode === 'DOCUMENT_PROCESSING_FAILED') return true
  const runState = job.teachingPreparationRunId
    ? preparationStates.value[job.teachingPreparationRunId]
    : undefined
  const document = job.documentVersionId
    ? documents.value.find(entry => entry.latestVersion.id === job.documentVersionId)
    : undefined
  if (document?.latestVersion.status === 'FAILED') return true
  if (job.teachingPreparationRunId || job.teachingHandoffState === 'LAUNCHED') {
    return playerJourneyRunIsTerminal(runState)
  }
  return job.stage === 'COMPLETED'
    && !['WAITING_FOR_DOCUMENT', 'LAUNCHING'].includes(job.teachingHandoffState ?? 'NOT_REQUESTED')
}

function uploadedTeachingHandoffFailed(handoff: UploadedTeachingHandoff) {
  const runState = handoff.preparationRunId
    ? preparationStates.value[handoff.preparationRunId]
    : undefined
  const document = documents.value.find(entry => entry.latestVersion.id === handoff.documentVersionId)
  return handoff.state === 'FAILED'
    || handoff.errorCode === 'DOCUMENT_PROCESSING_FAILED'
    || document?.latestVersion.status === 'FAILED'
    || Boolean(runState && playerJourneyRunIsTerminal(runState) && runState !== 'COMPLETED')
}

function uploadedTeachingHandoffFinished(handoff: UploadedTeachingHandoff) {
  const runState = handoff.preparationRunId
    ? preparationStates.value[handoff.preparationRunId]
    : undefined
  return uploadedTeachingHandoffFailed(handoff)
    || playerJourneyRunIsTerminal(runState)
}

function preparationStage(state: string) {
  return {
    RECEIVED: copy.value.preparationReceived,
    DOCUMENT_READINESS: copy.value.preparationReading,
    LESSON_PLANNING: copy.value.preparationPlanning,
    FAILED: copy.value.preparationFailed,
    CANCELLED: copy.value.preparationFailed,
  }[state] ?? copy.value.teaching
}

function teachingStateDetail(state: string | undefined) {
  return {
    RECEIVED: copy.value.preparationReceived,
    DOCUMENT_READINESS: copy.value.preparationReading,
    LESSON_PLANNING: copy.value.preparationPlanning,
    RETRIEVAL_PLANNING: copy.value.teachingPlanningEvidence,
    RETRIEVING: copy.value.teachingRetrieving,
    VERIFYING_EVIDENCE: copy.value.teachingVerifying,
    LESSON_COMPOSITION: copy.value.teachingComposing,
    MEDIA_PACKAGING: copy.value.teachingPackaging,
    CRITIQUING: copy.value.teachingReviewing,
  }[state ?? ''] ?? copy.value.safe
}

function latestPreparationDetail(runId: string | null | undefined, fallbackState: string) {
  const run = runId ? preparationRunDetails.value[runId] : undefined
  return recentTeachingPreparationActivitySteps(run?.activities ?? [], locale.value).at(-1)?.text
    ?? preparationStage(fallbackState)
}

function latestTeachingDetail(item: BackgroundTeachingItem) {
  const run = teachingRunDetails.value[item.runId]
  const plan = teachingPlanDetails.value[item.planId] ?? { sections: [] }
  return recentTeachingActivitySteps(plan, run?.activities ?? [], locale.value).at(-1)?.text
    ?? teachingStateDetail(teachingStates.value[item.runId] ?? run?.run.state)
}

function teachingProgressContext(item: BackgroundTeachingItem) {
  const run = teachingRunDetails.value[item.runId]
  const processed = processedTeachingChapterCount(run ?? null)
  const published = supportedTeachingChapterCount(run ?? null)
  const rejected = rejectedTeachingChapterCount(run ?? null)
  const total = teachingPlanDetails.value[item.planId]?.sections.length ?? null
  const details = [
    processed > 0 ? copy.value.processedChapters(processed, total) : '',
    published > 0 ? copy.value.publishedChapters(published, total) : copy.value.noPublishedChapter,
    rejected > 0 ? copy.value.rejectedChapters(rejected) : '',
    teachingChapterFailureText(run ?? null, locale.value),
    run?.run.lastErrorCode ? copy.value.failureCode(run.run.lastErrorCode) : '',
  ].filter(Boolean)
  return details.join(' · ')
}

function documentStage(progress: DocumentProgress | undefined, status: string) {
  const stage = progress?.stage ?? status
  return {
    UPLOADED: copy.value.uploaded,
    VALIDATING: copy.value.validating,
    EXTRACTING: copy.value.extracting,
    RENDERING: copy.value.rendering,
    STRUCTURING: copy.value.structuring,
    CHUNKING: copy.value.chunking,
    EMBEDDING: copy.value.embedding,
    INDEXING: copy.value.indexing,
    READY: copy.value.done,
    FAILED: copy.value.failed,
  }[stage] ?? copy.value.rulebook
}

const workItems = computed<WorkItem[]>(() => {
  const processingVersionIds = new Set(documents.value
    .filter(entry => !['READY', 'FAILED'].includes(entry.latestVersion.status))
    .map(entry => entry.latestVersion.id))
  const importItems = imports.value
    .filter(job => !dismissedImportIds.value.has(job.id))
    .filter(job => job.teachingHandoffState === 'WAITING_FOR_DOCUMENT'
      || job.teachingHandoffState === 'LAUNCHING'
      || job.stage !== 'COMPLETED'
      || !job.documentVersionId
      || !processingVersionIds.has(job.documentVersionId))
    .filter(job => job.teachingHandoffState === 'WAITING_FOR_DOCUMENT'
      || job.teachingHandoffState === 'LAUNCHING'
      || job.teachingHandoffState === 'FAILED'
      || job.stage !== 'COMPLETED'
      || Date.now() - Date.parse(job.updatedAt) < 15 * 60_000)
    .filter((job) => {
      const runId = job.teachingPreparationRunId
      return job.teachingHandoffState === 'FAILED' || !runId || !preparationStates.value[runId]
    })
    .map((job): WorkItem => {
      const document = job.documentVersionId
        ? documents.value.find(entry => entry.latestVersion.id === job.documentVersionId)
        : undefined
      const documentFailed = document?.latestVersion.status === 'FAILED'
      const progress = job.stage === 'DOWNLOADING' && job.totalBytes
        ? Math.min(100, Math.round(job.downloadedBytes / job.totalBytes * 100))
        : job.stage === 'COMPLETED' ? 100 : null
      const context = job.teachingErrorCode === 'TEACHING_RECOVERY_EXHAUSTED'
        ? copy.value.recoveryExhaustedContext
        : job.stage === 'FAILED' && job.errorCode === 'SOURCE_BROWSER_REQUIRED'
        ? copy.value.browserRequired
        : job.stage === 'DOWNLOADING' && job.downloadedBytes > 0
        ? job.totalBytes
          ? copy.value.bytes(formatBytes(job.downloadedBytes), formatBytes(job.totalBytes))
          : formatBytes(job.downloadedBytes)
        : job.sourceDomain
      const state = importState(job, documentFailed)
      return {
        id: `import:${job.id}`, kind: 'download', title: job.title,
        status: importPlayerStatus(job, document?.latestVersion.status, state),
        detail: documentFailed ? copy.value.rulebookFailed : importStage(job), context,
        state,
        progress, target: { name: 'teach', query: { importJob: job.id } }, updatedAt: job.updatedAt,
      }
    })
  const importVersionIds = new Set(imports.value
    .filter(job => !['COMPLETED', 'FAILED'].includes(job.stage))
    .map(job => job.documentVersionId)
    .filter(Boolean))
  const uploadedTeachingVersionIds = new Set(uploadedTeachingHandoffs.value
    .map(handoff => handoff.documentVersionId))
  const documentItems = documents.value
    .filter(entry => !['READY', 'FAILED'].includes(entry.latestVersion.status))
    .filter(entry => !importVersionIds.has(entry.latestVersion.id))
    .filter(entry => !uploadedTeachingVersionIds.has(entry.latestVersion.id))
    .map((entry): WorkItem => {
      const progress = documentProgress.value[entry.latestVersion.id]
      return {
        id: `document:${entry.latestVersion.id}`, kind: 'rulebook', title: entry.document.title,
        status: workStatus('READING_RULEBOOK', 'none', 'unavailable', 'active'),
        detail: documentStage(progress, entry.latestVersion.status),
        context: progress?.stage === 'RENDERING' && progress.totalPages > 0
          ? copy.value.pages(progress.processedPages, progress.totalPages) : '',
        state: 'active', progress: progress?.percentage ?? null, target: { name: 'teach' },
      }
    })
  const teachingItems = activeTeaching.value.map((item): WorkItem => ({
    id: `teaching:${item.runId}`, kind: 'lesson', title: item.gameTitle,
    status: workStatus('ORGANIZING_GUIDE', 'rulebook', 'usable', 'active'),
    detail: latestTeachingDetail(item), context: teachingProgressContext(item),
    state: 'active', progress: null, target: { name: 'lessons' },
  }))
  const finishedTeachingItems = completedTeaching.value
    .filter(item => !dismissedTeachingRunIds.value.has(item.runId))
    .map((item): WorkItem => ({
    id: `teaching-finished:${item.runId}`, kind: 'lesson', title: item.gameTitle,
    status: item.terminalState && item.terminalState !== 'COMPLETED'
      ? workStatus('NEEDS_ACTION', 'rulebook', 'usable', 'terminal', 'needs-action')
      : workStatus('GUIDE_COMPLETE', 'guide', 'complete', 'terminal'),
    detail: latestTeachingDetail(item), context: teachingProgressContext(item),
    state: item.terminalState && item.terminalState !== 'COMPLETED' ? 'failed' : 'complete',
    progress: item.terminalState && item.terminalState !== 'COMPLETED' ? null : 100,
    target: { name: 'lessons' },
  }))
  const preparationTransitionItems = preparationTeachingTransitions.value.map((transition): WorkItem => ({
      id: `teaching-transition:${transition.id}`,
      kind: 'lesson',
      title: transition.title,
      status: workStatus('ORGANIZING_GUIDE', 'rulebook', 'usable', 'active'),
      detail: transition.planId ? copy.value.launchingTeaching : copy.value.recoveringMissingResult,
      context: '',
      state: 'active',
      progress: null,
      target: { name: 'lessons' },
  }))
  const preparationItems = imports.value.flatMap((job): WorkItem[] => {
    const runId = job.teachingPreparationRunId
    const runState = runId ? preparationStates.value[runId] : undefined
    if (!runId || !runState || runState === 'COMPLETED') return []
    if (dismissedImportIds.value.has(job.id) && playerJourneyRunIsTerminal(runState)) return []
    const failed = playerJourneyRunIsTerminal(runState)
    return [{
      id: `teaching-preparation:${runId}`,
      kind: 'lesson',
      title: job.title,
      status: failed
        ? workStatus('NEEDS_ACTION', 'rulebook', 'usable', 'terminal', 'needs-action')
        : workStatus('ORGANIZING_GUIDE', 'rulebook', 'usable', 'active'),
      detail: latestPreparationDetail(runId, runState),
      context: runState === 'FAILED'
        ? copy.value.failureCode(preparationRunDetails.value[runId]?.run.lastErrorCode ?? runState)
        : '',
      state: failed ? 'failed' : 'active',
      progress: null,
      target: { name: 'lessons' },
      updatedAt: job.updatedAt,
    }]
  })
  const uploadedTeachingItems = uploadedTeachingHandoffs.value
    .filter(handoff => !dismissedUploadedHandoffIds.value.has(handoff.id))
    .filter(handoff => !handoff.preparationRunId
      || preparationStates.value[handoff.preparationRunId] !== 'COMPLETED')
    .map((handoff): WorkItem => {
      const runState = handoff.preparationRunId
        ? preparationStates.value[handoff.preparationRunId]
        : undefined
      const document = documents.value.find(entry => entry.latestVersion.id === handoff.documentVersionId)
      const documentStatus = document?.latestVersion.status ?? 'UPLOADED'
      const documentSnapshot = documentProgress.value[handoff.documentVersionId]
      const documentFailed = documentStatus === 'FAILED'
      const failed = uploadedTeachingHandoffFailed(handoff)
      const detail = documentFailed || handoff.errorCode === 'DOCUMENT_PROCESSING_FAILED'
        ? copy.value.rulebookFailed
        : failed
          ? copy.value.preparationFailed
          : handoff.state === 'WAITING_FOR_DOCUMENT'
            ? documentStage(documentSnapshot, documentStatus)
            : handoff.state === 'LAUNCHING'
              ? copy.value.launchingTeaching
              : latestPreparationDetail(handoff.preparationRunId, runState ?? 'RECEIVED')
      return {
        id: `uploaded-teaching:${handoff.id}`,
        kind: handoff.state === 'WAITING_FOR_DOCUMENT' || documentFailed ? 'rulebook' : 'lesson',
        title: handoff.title,
        status: failed
          ? workStatus(
              'NEEDS_ACTION',
              documentStatus === 'READY' ? 'rulebook' : 'none',
              documentStatus === 'READY' ? 'usable' : 'unavailable',
              'terminal',
              'needs-action',
            )
          : handoff.state === 'WAITING_FOR_DOCUMENT'
            ? workStatus('READING_RULEBOOK', 'none', 'unavailable', 'active')
            : workStatus('ORGANIZING_GUIDE', 'rulebook', 'usable', 'active'),
        detail,
        context: handoff.rulebookTitle !== handoff.title ? handoff.rulebookTitle : '',
        state: failed ? 'failed' : 'active',
        progress: handoff.state === 'WAITING_FOR_DOCUMENT' ? documentSnapshot?.percentage ?? null : null,
        target: { name: handoff.state === 'WAITING_FOR_DOCUMENT' || documentFailed ? 'teach' : 'lessons' },
        updatedAt: handoff.updatedAt,
      }
    })
  return [
    ...importItems,
    ...documentItems,
    ...preparationItems,
    ...preparationTransitionItems,
    ...uploadedTeachingItems,
    ...teachingItems,
    ...finishedTeachingItems,
  ]
    .sort((left, right) => (left.state === 'active' ? 0 : 1) - (right.state === 'active' ? 0 : 1))
})
const activeCount = computed(() => workItems.value.filter(item => item.state === 'active').length)
const finishedCount = computed(() => workItems.value.filter(item => item.state !== 'active').length)
const firstActiveTitle = computed(() => workItems.value.find(item => item.state === 'active')?.title ?? '')
const firstFinishedTitle = computed(() => workItems.value.find(item => item.state !== 'active')?.title ?? '')

function clearTimer() {
  if (timer) clearTimeout(timer)
  timer = undefined
}

function schedule(generation: number, scheduledAccount: string) {
  clearTimer()
  if (!isCurrentRefresh(generation, scheduledAccount) || document.visibilityState === 'hidden') return
  timer = setTimeout(() => {
    if (isCurrentRefresh(generation, scheduledAccount)) void refresh()
  }, activeCount.value || unavailable.value ? 4_000 : 15_000)
}

async function responseJson<T>(path: string, signal: AbortSignal): Promise<T> {
  const response = await fetch(path, { credentials: 'include', signal })
  if (!response.ok) throw new Error('background work unavailable')
  return await response.json() as T
}

interface TeachingRefreshSnapshot {
  active: BackgroundTeachingItem[]
  completed: BackgroundTeachingItem[]
  states: Record<string, string>
  runDetails: Record<string, TeachingRunProgress>
  planDetails: Record<string, TeachingProgressPlan>
  resolvedTitles: Map<string, string>
  degraded: boolean
}

interface PreparationTeachingTransition {
  id: string
  source: 'import' | 'upload'
  sourceId: string
  documentVersionId: string
  planId: string | null
  title: string
}

interface DocumentRefreshSnapshot {
  imports: RulebookImportJob[]
  uploadedHandoffs: UploadedTeachingHandoff[]
  documents: DocumentSummary[]
  progress: Record<string, DocumentProgress>
  preparationStates: Record<string, string>
  preparationSubjects: Record<string, string>
  preparationRunDetails: Record<string, TeachingRunProgress>
  degraded: boolean
}

interface PreparationBridgeResult {
  teaching: TeachingRefreshSnapshot
  transitions: PreparationTeachingTransition[]
  planIds: Map<string, { documentVersionId: string; planId: string }>
  degraded: boolean
}

async function loadTeachingSnapshot(
  targetAccount: string,
  signal: AbortSignal,
): Promise<TeachingRefreshSnapshot> {
  const runPayload = await responseJson<unknown>('/api/v1/assistant-runs/active?mode=TEACHING', signal)
  const runs = parseActiveTeachingRuns(runPayload, targetAccount)
  const resolvedTitles = new Map(titles)
  const runDetails: Record<string, TeachingRunProgress> = { ...teachingRunDetails.value }
  const planDetails: Record<string, TeachingProgressPlan> = { ...teachingPlanDetails.value }
  let degraded = false
  await Promise.all(runs.map(async (run) => {
    try {
      const payload = await responseJson<unknown>(
        `/api/v1/assistant-runs/${encodeURIComponent(run.id)}`,
        signal,
      )
      runDetails[run.id] = mergeTeachingRunProgress(
        teachingRunDetails.value[run.id] ?? null,
        parseTeachingRunProgress(payload, {
          id: run.id, mode: 'TEACHING', subjectId: run.subjectId, ownerUsername: targetAccount,
        }),
      )!
    } catch {
      if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
      if (teachingRunDetails.value[run.id]) runDetails[run.id] = teachingRunDetails.value[run.id]!
    }
  }))
  if (runs.some(run => !resolvedTitles.has(run.subjectId) || !planDetails[run.subjectId])) {
    const planPayload = await responseJson<unknown>('/api/v1/teaching-plans', signal)
    const summaries = parseTeachingPlans(planPayload)
    const rawPlans = Array.isArray(planPayload) ? planPayload : []
    for (let index = 0; index < summaries.length; index++) {
      const plan = summaries[index]!
      resolvedTitles.set(plan.id, playerFacingTitle(plan.gameTitle))
      planDetails[plan.id] = parseTeachingProgressPlan(rawPlans[index], plan.id)
    }
  }
  const states = Object.fromEntries(runs.map(run => [run.id, run.state]))
  const active = runs.map(run => ({
    runId: run.id,
    planId: run.subjectId,
    gameTitle: resolvedTitles.get(run.subjectId) ?? (locale.value === 'zh-CN' ? '一份讲解' : 'A lesson'),
  }))
  const previous = activeTeaching.value
  const activePlanIds = new Set(active.map(item => item.planId))
  const missing = previous.filter(item => !activePlanIds.has(item.planId))
  const confirmedTerminalStates = new Map<string, BackgroundTeachingItem['terminalState']>()
  const confirmations = await Promise.all(missing.map(async (item) => {
    try {
      const details = await responseJson<unknown>(`/api/v1/assistant-runs/${encodeURIComponent(item.runId)}`, signal)
      const run = parseExpectedAssistantRun(details, {
        id: item.runId, mode: 'TEACHING', subjectId: item.planId, ownerUsername: targetAccount,
      })
      try {
        const progress = parseTeachingRunProgress(details, {
          id: item.runId, mode: 'TEACHING', subjectId: item.planId, ownerUsername: targetAccount,
        })
        runDetails[item.runId] = mergeTeachingRunProgress(
          teachingRunDetails.value[item.runId] ?? null,
          progress,
        )!
      } catch {
        // The authoritative run state remains usable when optional activity detail is unavailable.
      }
      states[item.runId] = run.state
      if (playerJourneyRunIsTerminal(run.state)) {
        confirmedTerminalStates.set(item.planId, run.state as BackgroundTeachingItem['terminalState'])
        return null
      }
      return item
    } catch {
      if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
      degraded = true
      const previousState = teachingStates.value[item.runId]
      if (previousState) states[item.runId] = previousState
      return item
    }
  }))
  const retained = confirmations.filter((item): item is BackgroundTeachingItem => item !== null)
  const transition = reconcileBackgroundTeaching(previous, [...active, ...retained])
  const notices = new Map(completedTeaching.value
    .filter(item => !dismissedTeachingRunIds.value.has(item.runId))
    .map(item => [item.planId, item]))
  for (const item of transition.finished) {
    if (dismissedTeachingRunIds.value.has(item.runId)) continue
    notices.set(item.planId, {
      ...item,
      terminalState: confirmedTerminalStates.get(item.planId) ?? item.terminalState,
    })
  }
  return {
    active: transition.active,
    completed: [...notices.values()],
    states,
    runDetails,
    planDetails,
    resolvedTitles,
    degraded,
  }
}

async function loadDocumentSnapshot(
  targetAccount: string,
  signal: AbortSignal,
): Promise<DocumentRefreshSnapshot> {
  const [importPayload, uploadedHandoffPayload, documentPayload] = await Promise.all([
    responseJson<unknown>('/api/v1/documents/official-imports', signal),
    responseJson<unknown>('/api/v1/documents/upload-teaching-handoffs', signal),
    responseJson<unknown>('/api/v1/documents', signal),
  ])
  const recentImports = parseRulebookImports(importPayload)
  const recentUploadedHandoffs = parseUploadedHandoffs(uploadedHandoffPayload)
  const documentList = parseOwnedDocuments(documentPayload, targetAccount)
  validateDocumentRelationships(recentImports, recentUploadedHandoffs, documentList)
  const expectedPreparationSubjects = new Map<string, string>()
  for (const [runId, versionId] of [
    ...recentImports.map(job => [job.teachingPreparationRunId, job.documentVersionId] as const),
    ...recentUploadedHandoffs.map(handoff => [handoff.preparationRunId, handoff.documentVersionId] as const),
  ]) {
    if (!runId || !versionId) continue
    const existing = expectedPreparationSubjects.get(runId)
    if (existing && existing !== versionId) throw new Error('background preparation relationship is invalid')
    expectedPreparationSubjects.set(runId, versionId)
  }
  let degraded = false
  const nextPreparationStates: Record<string, string> = {}
  const nextPreparationSubjects: Record<string, string> = {}
  const nextPreparationRunDetails: Record<string, TeachingRunProgress> = {}
  await Promise.all([...expectedPreparationSubjects].map(async ([runId, versionId]) => {
    const previousState = preparationSubjects.value[runId] === versionId
      ? preparationStates.value[runId]
      : undefined
    if (previousState && playerJourneyRunIsTerminal(previousState)) {
      nextPreparationStates[runId] = previousState
      nextPreparationSubjects[runId] = versionId
      if (preparationRunDetails.value[runId]) {
        nextPreparationRunDetails[runId] = preparationRunDetails.value[runId]!
      }
      return
    }
    try {
      const details = await responseJson<unknown>(`/api/v1/assistant-runs/${encodeURIComponent(runId)}`, signal)
      const run = parseExpectedAssistantRun(details, {
        id: runId, mode: 'TEACHING_PREPARATION', subjectId: versionId, ownerUsername: targetAccount,
      })
      nextPreparationStates[runId] = run.state
      nextPreparationSubjects[runId] = run.subjectId
      try {
        const progress = parseTeachingRunProgress(details, {
          id: runId, mode: 'TEACHING_PREPARATION', subjectId: versionId, ownerUsername: targetAccount,
        })
        nextPreparationRunDetails[runId] = mergeTeachingRunProgress(
          preparationRunDetails.value[runId] ?? null,
          progress,
        )!
      } catch {
        // State and ownership are still authoritative; detailed activities are optional enrichment.
      }
    } catch {
      if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
      degraded = true
      if (previousState) {
        nextPreparationStates[runId] = previousState
        nextPreparationSubjects[runId] = versionId
      }
      if (preparationRunDetails.value[runId]) {
        nextPreparationRunDetails[runId] = preparationRunDetails.value[runId]!
      }
    }
  }))
  const active = documentList.filter(entry => !['READY', 'FAILED'].includes(entry.latestVersion.status))
  const activeVersionIds = new Set(active.map(entry => entry.latestVersion.id))
  const nextProgress = Object.fromEntries(Object.entries(documentProgress.value)
    .filter(([versionId]) => activeVersionIds.has(versionId)))
  await Promise.all(active.map(async (entry) => {
    try {
      const payload = await responseJson<unknown>(
        `/api/v1/document-versions/${encodeURIComponent(entry.latestVersion.id)}/progress/snapshot`,
        signal,
      )
      const snapshot = parseDocumentProgress(payload)
      nextProgress[entry.latestVersion.id] = mergeDocumentProgress(
        nextProgress[entry.latestVersion.id],
        snapshot,
      )
    } catch {
      if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
      degraded = true
    }
  }))
  return {
    imports: recentImports,
    uploadedHandoffs: recentUploadedHandoffs,
    documents: documentList,
    progress: nextProgress,
    preparationStates: nextPreparationStates,
    preparationSubjects: nextPreparationSubjects,
    preparationRunDetails: nextPreparationRunDetails,
    degraded,
  }
}

async function bridgeCompletedPreparations(
  teaching: TeachingRefreshSnapshot,
  rulebooks: DocumentRefreshSnapshot,
  targetAccount: string,
  signal: AbortSignal,
): Promise<PreparationBridgeResult> {
  const nextPlanIds = new Map(preparationTeachingPlanIds)
  const candidatesByVersion = new Map<string, Omit<PreparationTeachingTransition, 'planId'>>()
  for (const job of rulebooks.imports) {
    const runId = job.teachingPreparationRunId
    const versionId = job.documentVersionId
    if (!runId || !versionId
      || job.teachingHandoffState === 'FAILED'
      || rulebooks.preparationStates[runId] !== 'COMPLETED'
      || dismissedImportIds.value.has(job.id)) continue
    candidatesByVersion.set(versionId, {
      id: `import:${job.id}`,
      source: 'import',
      sourceId: job.id,
      documentVersionId: versionId,
      title: job.title,
    })
  }
  for (const handoff of rulebooks.uploadedHandoffs) {
    const runId = handoff.preparationRunId
    if (!runId
      || rulebooks.preparationStates[runId] !== 'COMPLETED'
      || dismissedUploadedHandoffIds.value.has(handoff.id)
      || candidatesByVersion.has(handoff.documentVersionId)) continue
    candidatesByVersion.set(handoff.documentVersionId, {
      id: `upload:${handoff.id}`,
      source: 'upload',
      sourceId: handoff.id,
      documentVersionId: handoff.documentVersionId,
      title: handoff.title,
    })
  }
  if (candidatesByVersion.size === 0) {
    nextPlanIds.clear()
    return { teaching, transitions: [], planIds: nextPlanIds, degraded: false }
  }

  const candidateIds = new Set([...candidatesByVersion.values()].map(candidate => candidate.id))
  for (const id of nextPlanIds.keys()) {
    if (!candidateIds.has(id)) nextPlanIds.delete(id)
  }
  const transitions = [...candidatesByVersion.values()].map(candidate => ({
    ...candidate,
    planId: nextPlanIds.get(candidate.id)?.documentVersionId === candidate.documentVersionId
      ? nextPlanIds.get(candidate.id)?.planId ?? null
      : null,
  }))
  const unresolved = transitions.filter(transition => !transition.planId)
  let plans: ReturnType<typeof parsePreparationTeachingPlans> = []
  if (unresolved.length > 0) {
    try {
      plans = parsePreparationTeachingPlans(
        await responseJson<unknown>('/api/v1/teaching-plans', signal),
      )
    } catch {
      if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
      return { teaching, transitions, planIds: nextPlanIds, degraded: true }
    }
  }
  const planByVersion = new Map<string, (typeof plans)[number]>()
  for (const plan of plans) {
    const candidate = candidatesByVersion.get(plan.documentVersionId)
    if (!candidate) continue
    const existing = planByVersion.get(plan.documentVersionId)
    if (!existing || Date.parse(plan.createdAt) > Date.parse(existing.createdAt)) {
      planByVersion.set(plan.documentVersionId, plan)
    }
  }

  const activeByPlan = new Map(teaching.active.map(item => [item.planId, item]))
  const completedByPlan = new Map(teaching.completed.map(item => [item.planId, item]))
  const transitionById = new Map(transitions.map(item => [item.id, item]))
  let degraded = false
  await Promise.all(transitions.map(async (transition) => {
    const plan = planByVersion.get(transition.documentVersionId)
    if (plan) {
      transition.planId = plan.id
      nextPlanIds.set(transition.id, {
        documentVersionId: transition.documentVersionId,
        planId: plan.id,
      })
    }
    const planId = transition.planId
    if (!planId) return
    const gameTitle = plan ? playerFacingTitle(plan.gameTitle) : transition.title
    teaching.resolvedTitles.set(planId, gameTitle)
    if (activeByPlan.has(planId) || completedByPlan.has(planId)) {
      transitionById.delete(transition.id)
      return
    }
    try {
      const response = await fetch(
        `/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(planId)}`,
        { credentials: 'include', signal },
      )
      if (response.status === 404) return
      if (!response.ok) throw new Error('background Teaching transition is unavailable')
      const run = parseLatestTeachingRun(await response.json(), planId, targetAccount)
      teaching.states[run.id] = run.state
      transitionById.delete(transition.id)
      if (playerJourneyRunIsTerminal(run.state)) {
        if (dismissedTeachingRunIds.value.has(run.id)) return
        completedByPlan.set(planId, {
          runId: run.id,
          planId,
          gameTitle,
          terminalState: run.state as BackgroundTeachingItem['terminalState'],
        })
        return
      }
      activeByPlan.set(planId, {
        runId: run.id,
        planId,
        gameTitle,
      })
      completedByPlan.delete(planId)
    } catch {
      if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
      degraded = true
    }
  }))
  return {
    teaching: {
      ...teaching,
      active: [...activeByPlan.values()],
      completed: [...completedByPlan.values()],
      degraded: teaching.degraded || degraded,
    },
    transitions: [...transitionById.values()],
    planIds: nextPlanIds,
    degraded,
  }
}

async function refresh() {
  const targetAccount = account
  if (disposed || !targetAccount || document.visibilityState === 'hidden') return
  clearTimer()
  activeRefreshController?.abort()
  const controller = new AbortController()
  activeRefreshController = controller
  const generation = ++refreshGeneration
  const keys = backgroundWorkStorageKeys(targetAccount)
  try {
    const [teaching, rulebooks] = await Promise.all([
      loadTeachingSnapshot(targetAccount, controller.signal),
      loadDocumentSnapshot(targetAccount, controller.signal),
    ])
    if (!isCurrentRefresh(generation, targetAccount, controller)) return
    const bridge = await bridgeCompletedPreparations(teaching, rulebooks, targetAccount, controller.signal)
    if (!isCurrentRefresh(generation, targetAccount, controller)) return
    commitRefresh(bridge.teaching, rulebooks, bridge.transitions, bridge.planIds, keys)
    unavailable.value = bridge.teaching.degraded || rulebooks.degraded || bridge.degraded
  } catch {
    if (isCurrentRefresh(generation, targetAccount, controller) && !controller.signal.aborted) {
      unavailable.value = true
      controller.abort()
    }
  } finally {
    if (isCurrentRefresh(generation, targetAccount, controller)) {
      activeRefreshController = null
      loading.value = false
      schedule(generation, targetAccount)
    }
  }
}

function commitRefresh(
  teaching: TeachingRefreshSnapshot,
  rulebooks: DocumentRefreshSnapshot,
  transitions: PreparationTeachingTransition[],
  planIds: Map<string, { documentVersionId: string; planId: string }>,
  keys: BackgroundWorkStorageKeys,
) {
  activeTeaching.value = teaching.active
  completedTeaching.value = teaching.completed
  teachingStates.value = teaching.states
  teachingRunDetails.value = teaching.runDetails
  teachingPlanDetails.value = teaching.planDetails
  titles.clear()
  for (const [planId, title] of teaching.resolvedTitles) titles.set(planId, title)
  imports.value = rulebooks.imports
  uploadedTeachingHandoffs.value = rulebooks.uploadedHandoffs
  documents.value = rulebooks.documents
  documentProgress.value = rulebooks.progress
  preparationStates.value = rulebooks.preparationStates
  preparationSubjects.value = rulebooks.preparationSubjects
  preparationRunDetails.value = rulebooks.preparationRunDetails
  preparationTeachingTransitions.value = transitions
  preparationTeachingPlanIds.clear()
  for (const [id, plan] of planIds) preparationTeachingPlanIds.set(id, plan)
  safelyStore(keys.activeTeaching, teaching.active)
  safelyStore(keys.completedTeaching, teaching.completed)
}

function isCurrentRefresh(
  generation: number,
  targetAccount: string,
  controller?: AbortController,
) {
  return !disposed
    && generation === refreshGeneration
    && targetAccount === account
    && (!controller || activeRefreshController === controller)
}

function invalidateRefresh() {
  refreshGeneration += 1
  clearTimer()
  activeRefreshController?.abort()
  activeRefreshController = null
}

async function dismissFinished() {
  if (clearingFinished.value) return
  invalidateRefresh()
  const keys = backgroundWorkStorageKeys(account)
  const activeTransitionImportIds = new Set(preparationTeachingTransitions.value
    .filter(transition => transition.source === 'import')
    .map(transition => transition.sourceId))
  const activeTransitionUploadIds = new Set(preparationTeachingTransitions.value
    .filter(transition => transition.source === 'upload')
    .map(transition => transition.sourceId))
  const finishedImports = imports.value
    .filter(officialImportFinished)
    .filter(job => !activeTransitionImportIds.has(job.id))
  const finishedImportIds = finishedImports.map(job => job.id)
  const finishedUploadHandoffs = uploadedTeachingHandoffs.value
    .filter(uploadedTeachingHandoffFinished)
    .filter(handoff => !activeTransitionUploadIds.has(handoff.id))
  const finishedUploadIds = finishedUploadHandoffs.map(handoff => handoff.id)
  const finishedTeachingRunIds = completedTeaching.value.map(item => item.runId)
  const persistentTargets = [
    ...finishedImports
      .filter(officialImportNeedsPersistentDismissal)
      .map(job => ({ kind: 'import' as const, id: job.id })),
    ...finishedUploadHandoffs
      .filter(uploadedTeachingHandoffFailed)
      .map(handoff => ({ kind: 'upload' as const, id: handoff.id })),
  ]
  clearingFinished.value = true
  dismissError.value = ''
  try {
    const rejected = new Set<string>()
    if (persistentTargets.length > 0) {
      let csrf: { headerName: string; token: string } | null = null
      try {
        const response = await fetch('/api/auth/csrf', { credentials: 'include' })
        if (!response.ok) throw new Error('csrf unavailable')
        csrf = await response.json() as { headerName: string; token: string }
      } catch {
        for (const target of persistentTargets) rejected.add(`${target.kind}:${target.id}`)
      }
      if (csrf) {
        await Promise.all(persistentTargets.map(async (target) => {
          const source = target.kind === 'import' ? 'official-imports' : 'uploads'
          try {
            const response = await fetch(
              `/api/v1/teaching-preparation-failures/${source}/${encodeURIComponent(target.id)}`,
              { method: 'DELETE', credentials: 'include', headers: { [csrf.headerName]: csrf.token } },
            )
            if (!response.ok) rejected.add(`${target.kind}:${target.id}`)
          } catch {
            rejected.add(`${target.kind}:${target.id}`)
          }
        }))
      }
    }
    const dismissedImports = finishedImportIds
      .filter(id => !rejected.has(`import:${id}`))
    const dismissedUploads = finishedUploadIds
      .filter(id => !rejected.has(`upload:${id}`))
    dismissedImportIds.value = new Set([...dismissedImportIds.value, ...dismissedImports])
    dismissedUploadedHandoffIds.value = new Set([
      ...dismissedUploadedHandoffIds.value,
      ...dismissedUploads,
    ])
    dismissedTeachingRunIds.value = new Set([
      ...dismissedTeachingRunIds.value,
      ...finishedTeachingRunIds,
    ])
    safelyStoreDismissed(keys.dismissedTeachingRuns, [...dismissedTeachingRunIds.value])
    safelyStoreDismissed(keys.dismissedImports, [...dismissedImportIds.value])
    safelyStoreDismissed(keys.dismissedUploadHandoffs, [...dismissedUploadedHandoffIds.value])
    for (const importId of dismissedImports) preparationTeachingPlanIds.delete(`import:${importId}`)
    for (const handoffId of dismissedUploads) preparationTeachingPlanIds.delete(`upload:${handoffId}`)
    if (rejected.size > 0) dismissError.value = copy.value.clearFailed
    completedTeaching.value = []
    sessionStorage.removeItem(keys.completedTeaching)
  } finally {
    clearingFinished.value = false
    schedule(refreshGeneration, account)
  }
}

function officialImportNeedsPersistentDismissal(job: RulebookImportJob) {
  if (job.stage === 'FAILED'
    || job.teachingHandoffState === 'FAILED'
    || job.teachingErrorCode === 'DOCUMENT_PROCESSING_FAILED') return true
  const document = job.documentVersionId
    ? documents.value.find(entry => entry.latestVersion.id === job.documentVersionId)
    : undefined
  if (document?.latestVersion.status === 'FAILED') return true
  const runState = job.teachingPreparationRunId
    ? preparationStates.value[job.teachingPreparationRunId]
    : undefined
  return Boolean(runState && playerJourneyRunIsTerminal(runState) && runState !== 'COMPLETED')
}

function handleVisibility() {
  if (document.visibilityState === 'hidden') invalidateRefresh()
  else void refresh()
}

function handleTeachingLaunched(event: Event) {
  const detail = teachingLaunchDetail(event)
  if (!detail || !account) return
  invalidateRefresh()
  const gameTitle = detail.gameTitle ?? titles.get(detail.planId) ?? (locale.value === 'zh-CN' ? '一份讲解' : 'A lesson')
  if (detail.gameTitle) titles.set(detail.planId, detail.gameTitle)
  const items = new Map(activeTeaching.value.map(item => [item.planId, item]))
  items.set(detail.planId, { runId: detail.runId, planId: detail.planId, gameTitle })
  activeTeaching.value = [...items.values()]
  safelyStore(backgroundWorkStorageKeys(account).activeTeaching, activeTeaching.value)
  void refresh()
}

function handleBackgroundWorkChanged(event: Event) {
  if (!account) return
  invalidateRefresh()
  if (event instanceof CustomEvent && event.detail && typeof event.detail === 'object') {
    const detail = event.detail as Record<string, unknown>
    acceptOptimisticImport(detail.importJob)
    dismissedImportIds.value = withDismissedIds(
      dismissedImportIds.value,
      detail.dismissedImportIds,
    )
    dismissedUploadedHandoffIds.value = withDismissedIds(
      dismissedUploadedHandoffIds.value,
      detail.dismissedUploadedHandoffIds,
    )
    dismissedTeachingRunIds.value = withDismissedIds(
      dismissedTeachingRunIds.value,
      detail.dismissedTeachingRunIds,
    )
    const keys = backgroundWorkStorageKeys(account)
    safelyStoreDismissed(keys.dismissedTeachingRuns, [...dismissedTeachingRunIds.value])
    safelyStoreDismissed(keys.dismissedImports, [...dismissedImportIds.value])
    safelyStoreDismissed(keys.dismissedUploadHandoffs, [...dismissedUploadedHandoffIds.value])
  }
  void refresh()
}

function acceptOptimisticImport(candidate: unknown) {
  if (candidate === undefined) return
  try {
    const [incoming] = parseRulebookImports([candidate])
    if (!incoming) return
    const byId = new Map(imports.value.map(job => [job.id, job]))
    byId.set(incoming.id, incoming)
    imports.value = [...byId.values()]
    if (!officialImportFinished(incoming) && dismissedImportIds.value.has(incoming.id)) {
      dismissedImportIds.value = new Set([...dismissedImportIds.value]
        .filter(id => id !== incoming.id))
      safelyStoreDismissed(
        backgroundWorkStorageKeys(account).dismissedImports,
        [...dismissedImportIds.value],
      )
    }
  } catch {
    // The durable snapshot remains the authority when an internal event carries an invalid or stale payload.
  }
}

function withDismissedIds(current: Set<string>, candidate: unknown) {
  if (!Array.isArray(candidate)) return current
  const accepted = candidate
    .filter((value): value is string => typeof value === 'string' && value.trim().length > 0)
    .map(value => value.trim())
  return new Set([...current, ...accepted])
}

function openCenter(trigger?: HTMLElement | null) {
  requestedRestoreTarget.value = trigger ?? null
  open.value = true
}

defineExpose({ openCenter })

watch(
  [activeCount, finishedCount, firstActiveTitle, firstFinishedTitle],
  ([active, finished, activeTitle, finishedTitle]) => emit(
    'status',
    active,
    finished,
    activeTitle,
    finishedTitle,
  ),
  { immediate: true },
)

onMounted(() => {
  document.addEventListener('visibilitychange', handleVisibility)
  window.addEventListener(BACKGROUND_WORK_CHANGED_EVENT, handleBackgroundWorkChanged)
  window.addEventListener(TEACHING_LAUNCHED_EVENT, handleTeachingLaunched)
  switchAccount(props.username)
})
watch(() => props.username, switchAccount)
onBeforeUnmount(() => {
  disposed = true
  invalidateRefresh()
  document.removeEventListener('visibilitychange', handleVisibility)
  window.removeEventListener(BACKGROUND_WORK_CHANGED_EVENT, handleBackgroundWorkChanged)
  window.removeEventListener(TEACHING_LAUNCHED_EVENT, handleTeachingLaunched)
})

function switchAccount(nextUsername: string) {
  const nextAccount = nextUsername.trim()
  if (nextAccount === account) return
  invalidateRefresh()
  account = nextAccount
  open.value = false
  unavailable.value = false
  activeTeaching.value = []
  completedTeaching.value = []
  teachingStates.value = {}
  teachingRunDetails.value = {}
  teachingPlanDetails.value = {}
  imports.value = []
  uploadedTeachingHandoffs.value = []
  documents.value = []
  documentProgress.value = {}
  preparationStates.value = {}
  preparationSubjects.value = {}
  preparationRunDetails.value = {}
  preparationTeachingTransitions.value = []
  preparationTeachingPlanIds.clear()
  dismissedImportIds.value = new Set()
  dismissedUploadedHandoffIds.value = new Set()
  dismissedTeachingRunIds.value = new Set()
  dismissError.value = ''
  titles.clear()
  clearLegacyBackgroundWorkStorage(sessionStorage)
  if (!account) {
    loading.value = false
    return
  }
  const keys = backgroundWorkStorageKeys(account)
  activeTeaching.value = parseBackgroundTeachingItems(sessionStorage.getItem(keys.activeTeaching))
  dismissedTeachingRunIds.value = readStoredIds(keys.dismissedTeachingRuns)
  completedTeaching.value = parseBackgroundTeachingItems(sessionStorage.getItem(keys.completedTeaching))
    .filter(item => !dismissedTeachingRunIds.value.has(item.runId))
  dismissedImportIds.value = readStoredIds(keys.dismissedImports)
  dismissedUploadedHandoffIds.value = readStoredIds(keys.dismissedUploadHandoffs)
  for (const item of [...activeTeaching.value, ...completedTeaching.value]) {
    titles.set(item.planId, item.gameTitle)
  }
  loading.value = activeTeaching.value.length === 0 && completedTeaching.value.length === 0
  void refresh()
}

function readStoredIds(key: string) {
  try {
    const stored = [localStorage.getItem(key), sessionStorage.getItem(key)]
      .flatMap((value) => {
        if (!value) return []
        const parsed = JSON.parse(value) as unknown
        return Array.isArray(parsed) ? parsed : []
      })
    const ids = new Set(stored
      .filter((value): value is string => typeof value === 'string' && value.trim().length > 0))
    safelyStoreDismissed(key, [...ids])
    return ids
  } catch {
    return new Set<string>()
  }
}

function safelyStoreDismissed(key: string, value: unknown) {
  safelyStore(key, value)
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // Durable server state remains authoritative when browser storage is unavailable or full.
  }
}

function safelyStore(key: string, value: unknown) {
  try {
    sessionStorage.setItem(key, JSON.stringify(value))
  } catch {
    // The server remains authoritative when tab storage is unavailable or full.
  }
}
</script>

<template>
  <div>
    <div v-if="open" class="fixed inset-0 z-50 bg-ink/35 backdrop-blur-[2px]" @click.self="open = false">
      <aside ref="dialog" tabindex="-1" class="absolute inset-y-0 right-0 flex w-full max-w-md flex-col border-l border-gold/25 bg-canvas outline-none elevation-lg-ink" role="dialog" aria-modal="true" :aria-label="copy.title">
        <header class="flex items-start justify-between border-b border-ink/10 bg-paper px-5 py-5">
          <div>
            <p class="tabletop-kicker">RulePilot</p>
            <h2 class="mt-1 font-display text-2xl font-semibold">{{ copy.title }}</h2>
            <p class="mt-1 text-sm leading-6 text-ink/55">{{ copy.safe }}</p>
          </div>
          <button type="button" data-modal-initial-focus class="grid min-h-11 min-w-11 place-items-center rounded-lg text-2xl text-ink/45 hover:bg-ink/5" :aria-label="copy.close" @click="open = false">×</button>
        </header>

        <div class="flex-1 overflow-y-auto px-4 py-5 sm:px-5">
          <p v-if="unavailable" class="rounded-lg bg-amber-50 px-4 py-3 text-sm text-amber-900" role="status">{{ copy.retrying }}</p>
          <p v-if="dismissError" class="mb-3 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-800" role="alert">{{ dismissError }}</p>
          <p v-if="loading" class="py-8 text-center text-sm text-ink/45">{{ copy.title }}…</p>
          <p v-else-if="workItems.length === 0" class="rounded-xl border border-dashed border-ink/18 bg-paper px-5 py-10 text-center text-sm text-ink/50">{{ copy.empty }}</p>
          <ol v-else class="stack-y-md">
            <li v-for="item in workItems" :key="item.id" class="tabletop-panel p-4">
              <div class="flex items-start gap-3">
                <span class="mt-0.5 grid size-9 shrink-0 place-items-center rounded-full" :class="item.state === 'failed' ? 'bg-red-100 text-red-700' : item.state === 'complete' ? 'bg-emerald-100 text-emerald-800' : 'bg-copper/12 text-copper'">
                  <TabletopGlyph :name="item.kind === 'download' ? 'arrow' : item.kind === 'rulebook' ? 'rulebook' : 'cards'" :size="18" />
                </span>
                <div class="min-w-0 flex-1">
                  <p class="text-xs font-bold uppercase tracking-[0.1em] text-ink/40">{{ item.kind === 'download' ? copy.download : item.kind === 'rulebook' ? copy.rulebook : copy.lesson }}</p>
                  <p class="mt-1 truncate font-semibold">{{ item.title }}</p>
                  <PlayerWorkStatusText
                    :status="item.status"
                    class="mt-1 text-sm font-semibold text-ink/70"
                  />
                  <p v-if="item.detail" class="mt-1 text-xs leading-5 text-ink/50">{{ item.detail }}</p>
                  <p v-if="item.context" class="mt-1 text-xs text-ink/45">{{ item.context }}</p>
                  <div v-if="item.progress !== null" class="mt-3 h-1.5 overflow-hidden rounded-full bg-ink/10" :aria-label="`${item.progress}%`">
                    <div class="h-full rounded-full bg-copper transition-[width]" :style="{ width: `${item.progress}%` }" />
                  </div>
                  <RouterLink :to="item.target" class="mt-3 inline-flex min-h-11 items-center text-sm font-semibold text-indigo" @click="open = false">{{ item.kind === 'lesson' ? copy.openLessons : copy.openRulebooks }} →</RouterLink>
                </div>
              </div>
            </li>
          </ol>
        </div>
        <footer v-if="finishedCount" class="border-t border-ink/10 bg-paper px-5 py-3 text-right">
          <button type="button" class="min-h-11 text-sm font-semibold text-ink/55 hover:text-ink disabled:cursor-wait disabled:opacity-55" :disabled="clearingFinished" @click="dismissFinished">{{ clearingFinished ? copy.clearing : locale === 'zh-CN' ? '清除已结束任务' : 'Clear finished work' }}</button>
        </footer>
      </aside>
    </div>
  </div>
</template>
