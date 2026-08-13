<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

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
import {
  parseActiveTeachingRuns,
  parseDocumentProgress,
  parseExpectedAssistantRun,
  parseOwnedDocuments,
  parseRulebookImports,
  parseTeachingPlans,
  parseUploadedHandoffs,
  terminalAssistantRunStates,
  validateDocumentRelationships,
  type DocumentProgress,
  type DocumentSummary,
  type RulebookImportJob,
  type UploadedTeachingHandoff,
} from '@/lib/backgroundWorkSnapshot'
import { mergeDocumentProgress } from '@/lib/documentProgress'
import { playerFacingTitle } from '@/lib/lessonPresentation'
import { useLocale } from '@/lib/locale'
import { TEACHING_LAUNCHED_EVENT, teachingLaunchDetail } from '@/lib/teachingLaunch'

const props = defineProps<{ username: string }>()
const emit = defineEmits<{
  status: [activeCount: number, finishedCount: number]
}>()

const dialog = ref<HTMLElement | null>(null)
const requestedRestoreTarget = ref<HTMLElement | null>(null)

type WorkState = 'active' | 'complete' | 'failed'
interface WorkItem {
  id: string
  kind: 'download' | 'rulebook' | 'lesson'
  title: string
  stage: string
  detail: string
  state: WorkState
  progress: number | null
  target: { name: string; query?: Record<string, string> }
  updatedAt?: string
}

const { locale } = useLocale()
const open = ref(false)
const loading = ref(true)
const unavailable = ref(false)
const activeTeaching = ref<BackgroundTeachingItem[]>([])
const completedTeaching = ref<BackgroundTeachingItem[]>([])
const teachingStates = ref<Record<string, string>>({})
const imports = ref<RulebookImportJob[]>([])
const uploadedTeachingHandoffs = ref<UploadedTeachingHandoff[]>([])
const documents = ref<DocumentSummary[]>([])
const documentProgress = ref<Record<string, DocumentProgress>>({})
const preparationStates = ref<Record<string, string>>({})
const preparationSubjects = ref<Record<string, string>>({})
const dismissedImportIds = ref<Set<string>>(new Set())
const dismissedUploadedHandoffIds = ref<Set<string>>(new Set())
const titles = new Map<string, string>()
const terminalTeachingStates = terminalAssistantRunStates
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
  chunking: '正在建立可检索规则段落', embedding: '正在建立规则语义索引', indexing: '正在完成规则索引', teaching: '正在组织讲解',
  rulebookFailed: '规则书读取失败，讲解无法开始',
  waitingForTeaching: '规则书已保存，读取完成后会自动开始讲解', launchingTeaching: '规则书已就绪，正在启动讲解任务',
  teachingLaunched: '规则书已保存，讲解任务已交给后台', teachingLaunchFailed: '规则书已保存，但自动讲解没有启动',
  preparationReceived: '讲解任务已接收', preparationReading: '正在确认规则书可以用于讲解',
  preparationPlanning: '正在读取规则并建立讲解结构', preparationFailed: '讲解准备失败，可在讲解中心重试',
  bytes: (done: string, total: string) => `${done} / ${total}`, pages: (done: number, total: number) => `第 ${done} / ${total} 页`,
  browserRequired: '需要在来源网站刷新链接或登录',
  openRulebooks: '打开规则书', openLessons: '打开讲解中心',
} : {
  trigger: 'Background work', title: 'Background work', close: 'Close background work', empty: 'No background work right now.',
  safe: 'You can keep browsing. Leaving this page will not interrupt these tasks.', retrying: 'Progress is temporarily unavailable; retrying automatically.',
  download: 'Get rulebook', rulebook: 'Read rulebook', lesson: 'Generate lesson', done: 'Complete', failed: 'Needs attention',
  queued: 'Waiting to download', connecting: 'Connecting to rulebook source', downloading: 'Downloading rulebook content', compressing: 'Compressing the oversized PDF', verifying: 'Verifying PDF',
  saving: 'Saving and handing off for reading', uploaded: 'Waiting to read', extracting: 'Extracting searchable rules',
  validating: 'Validating the rulebook file', rendering: 'Rendering rulebook pages', structuring: 'Organizing chapters and visual references',
  chunking: 'Building searchable rule passages', embedding: 'Building the rule meaning index', indexing: 'Finishing the rule index', teaching: 'Organizing the lesson',
  rulebookFailed: 'Rulebook reading failed, so the guide could not start',
  waitingForTeaching: 'Rulebook saved; the guide will start automatically when reading completes', launchingTeaching: 'Rulebook ready; starting the guide task',
  teachingLaunched: 'Rulebook saved; guide work was handed to the background', teachingLaunchFailed: 'Rulebook saved, but the automatic guide did not start',
  preparationReceived: 'Guide task received', preparationReading: 'Confirming that the rulebook is ready for a guide',
  preparationPlanning: 'Reading the rules and building the guide structure', preparationFailed: 'Guide preparation failed; retry from the lesson center',
  bytes: (done: string, total: string) => `${done} / ${total}`, pages: (done: number, total: number) => `Page ${done} / ${total}`,
  browserRequired: 'Refresh the link or sign in on the source site',
  openRulebooks: 'Open rulebooks', openLessons: 'Open lesson center',
})

function formatBytes(value: number) {
  if (value < 1024 * 1024) return `${Math.max(1, Math.round(value / 1024))} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

function importStage(job: RulebookImportJob) {
  if (job.stage === 'COMPLETED') {
    if (job.teachingErrorCode === 'DOCUMENT_PROCESSING_FAILED') return copy.value.rulebookFailed
    if (job.teachingHandoffState === 'WAITING_FOR_DOCUMENT') return copy.value.waitingForTeaching
    if (job.teachingHandoffState === 'LAUNCHING') return copy.value.launchingTeaching
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
    return Boolean(runState && terminalTeachingStates.has(runState))
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
    || Boolean(runState && terminalTeachingStates.has(runState) && runState !== 'COMPLETED')
}

function uploadedTeachingHandoffFinished(handoff: UploadedTeachingHandoff) {
  const runState = handoff.preparationRunId
    ? preparationStates.value[handoff.preparationRunId]
    : undefined
  return uploadedTeachingHandoffFailed(handoff)
    || Boolean(runState && terminalTeachingStates.has(runState))
}

function preparationStage(state: string) {
  return {
    RECEIVED: copy.value.preparationReceived,
    DOCUMENT_READINESS: copy.value.preparationReading,
    LESSON_PLANNING: copy.value.preparationPlanning,
    FAILED: copy.value.preparationFailed,
  }[state] ?? copy.value.teaching
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
      || job.stage !== 'COMPLETED'
      || Date.now() - Date.parse(job.updatedAt) < 15 * 60_000)
    .filter((job) => {
      const runId = job.teachingPreparationRunId
      return !runId || !preparationStates.value[runId]
    })
    .map((job): WorkItem => {
      const document = job.documentVersionId
        ? documents.value.find(entry => entry.latestVersion.id === job.documentVersionId)
        : undefined
      const documentFailed = document?.latestVersion.status === 'FAILED'
      const progress = job.stage === 'DOWNLOADING' && job.totalBytes
        ? Math.min(100, Math.round(job.downloadedBytes / job.totalBytes * 100))
        : job.stage === 'COMPLETED' ? 100 : null
      const detail = job.stage === 'FAILED' && job.errorCode === 'SOURCE_BROWSER_REQUIRED'
        ? copy.value.browserRequired
        : job.stage === 'DOWNLOADING' && job.downloadedBytes > 0
        ? job.totalBytes
          ? copy.value.bytes(formatBytes(job.downloadedBytes), formatBytes(job.totalBytes))
          : formatBytes(job.downloadedBytes)
        : job.sourceDomain
      return {
        id: `import:${job.id}`, kind: 'download', title: job.title,
        stage: documentFailed ? copy.value.rulebookFailed : importStage(job), detail,
        state: importState(job, documentFailed),
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
        stage: documentStage(progress, entry.latestVersion.status),
        detail: progress?.stage === 'RENDERING' && progress.totalPages > 0
          ? copy.value.pages(progress.processedPages, progress.totalPages) : '',
        state: 'active', progress: progress?.percentage ?? null, target: { name: 'teach' },
      }
    })
  const teachingItems = activeTeaching.value.map((item): WorkItem => ({
    id: `teaching:${item.runId}`, kind: 'lesson', title: item.gameTitle,
    stage: copy.value.teaching, detail: teachingStates.value[item.runId] ?? copy.value.safe,
    state: 'active', progress: null, target: { name: 'lessons' },
  }))
  const finishedTeachingItems = completedTeaching.value.map((item): WorkItem => ({
    id: `teaching-finished:${item.runId}`, kind: 'lesson', title: item.gameTitle,
    stage: copy.value.done, detail: '', state: 'complete', progress: 100, target: { name: 'lessons' },
  }))
  const preparationItems = imports.value.flatMap((job): WorkItem[] => {
    const runId = job.teachingPreparationRunId
    const runState = runId ? preparationStates.value[runId] : undefined
    if (!runId || !runState || runState === 'COMPLETED') return []
    if (dismissedImportIds.value.has(job.id) && terminalTeachingStates.has(runState)) return []
    return [{
      id: `teaching-preparation:${runId}`,
      kind: 'lesson',
      title: job.title,
      stage: preparationStage(runState),
      detail: '',
      state: terminalTeachingStates.has(runState) ? 'failed' : 'active',
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
      const stage = documentFailed || handoff.errorCode === 'DOCUMENT_PROCESSING_FAILED'
        ? copy.value.rulebookFailed
        : failed
          ? copy.value.preparationFailed
          : handoff.state === 'WAITING_FOR_DOCUMENT'
            ? documentStage(documentSnapshot, documentStatus)
            : handoff.state === 'LAUNCHING'
              ? copy.value.launchingTeaching
              : preparationStage(runState ?? 'RECEIVED')
      return {
        id: `uploaded-teaching:${handoff.id}`,
        kind: handoff.state === 'WAITING_FOR_DOCUMENT' || documentFailed ? 'rulebook' : 'lesson',
        title: handoff.title,
        stage,
        detail: handoff.rulebookTitle !== handoff.title ? handoff.rulebookTitle : '',
        state: failed ? 'failed' : 'active',
        progress: handoff.state === 'WAITING_FOR_DOCUMENT' ? documentSnapshot?.percentage ?? null : null,
        target: { name: handoff.state === 'WAITING_FOR_DOCUMENT' || documentFailed ? 'teach' : 'lessons' },
        updatedAt: handoff.updatedAt,
      }
    })
  return [...importItems, ...documentItems, ...preparationItems, ...uploadedTeachingItems, ...teachingItems, ...finishedTeachingItems]
    .sort((left, right) => (left.state === 'active' ? 0 : 1) - (right.state === 'active' ? 0 : 1))
})
const activeCount = computed(() => workItems.value.filter(item => item.state === 'active').length)
const finishedCount = computed(() => workItems.value.filter(item => item.state !== 'active').length)

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
  resolvedTitles: Map<string, string>
  degraded: boolean
}

interface DocumentRefreshSnapshot {
  imports: RulebookImportJob[]
  uploadedHandoffs: UploadedTeachingHandoff[]
  documents: DocumentSummary[]
  progress: Record<string, DocumentProgress>
  preparationStates: Record<string, string>
  preparationSubjects: Record<string, string>
  degraded: boolean
}

async function loadTeachingSnapshot(
  targetAccount: string,
  signal: AbortSignal,
): Promise<TeachingRefreshSnapshot> {
  const runPayload = await responseJson<unknown>('/api/v1/assistant-runs/active?mode=TEACHING', signal)
  const runs = parseActiveTeachingRuns(runPayload, targetAccount)
  const resolvedTitles = new Map(titles)
  if (runs.some(run => !resolvedTitles.has(run.subjectId))) {
    const planPayload = await responseJson<unknown>('/api/v1/teaching-plans', signal)
    for (const plan of parseTeachingPlans(planPayload)) {
      resolvedTitles.set(plan.id, playerFacingTitle(plan.gameTitle))
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
  let degraded = false
  const confirmations = await Promise.all(missing.map(async (item) => {
    try {
      const details = await responseJson<unknown>(`/api/v1/assistant-runs/${encodeURIComponent(item.runId)}`, signal)
      const run = parseExpectedAssistantRun(details, {
        id: item.runId, mode: 'TEACHING', subjectId: item.planId, ownerUsername: targetAccount,
      })
      states[item.runId] = run.state
      return terminalTeachingStates.has(run.state) ? null : item
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
  const notices = new Map(completedTeaching.value.map(item => [item.planId, item]))
  for (const item of transition.finished) notices.set(item.planId, item)
  return {
    active: transition.active,
    completed: [...notices.values()],
    states,
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
  await Promise.all([...expectedPreparationSubjects].map(async ([runId, versionId]) => {
    const previousState = preparationSubjects.value[runId] === versionId
      ? preparationStates.value[runId]
      : undefined
    if (previousState && terminalTeachingStates.has(previousState)) {
      nextPreparationStates[runId] = previousState
      nextPreparationSubjects[runId] = versionId
      return
    }
    try {
      const details = await responseJson<unknown>(`/api/v1/assistant-runs/${encodeURIComponent(runId)}`, signal)
      const run = parseExpectedAssistantRun(details, {
        id: runId, mode: 'TEACHING_PREPARATION', subjectId: versionId, ownerUsername: targetAccount,
      })
      nextPreparationStates[runId] = run.state
      nextPreparationSubjects[runId] = run.subjectId
    } catch {
      if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
      degraded = true
      if (previousState) {
        nextPreparationStates[runId] = previousState
        nextPreparationSubjects[runId] = versionId
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
    commitRefresh(teaching, rulebooks, keys)
    unavailable.value = teaching.degraded || rulebooks.degraded
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
  keys: BackgroundWorkStorageKeys,
) {
  activeTeaching.value = teaching.active
  completedTeaching.value = teaching.completed
  teachingStates.value = teaching.states
  titles.clear()
  for (const [planId, title] of teaching.resolvedTitles) titles.set(planId, title)
  imports.value = rulebooks.imports
  uploadedTeachingHandoffs.value = rulebooks.uploadedHandoffs
  documents.value = rulebooks.documents
  documentProgress.value = rulebooks.progress
  preparationStates.value = rulebooks.preparationStates
  preparationSubjects.value = rulebooks.preparationSubjects
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

function dismissFinished() {
  invalidateRefresh()
  completedTeaching.value = []
  const keys = backgroundWorkStorageKeys(account)
  sessionStorage.removeItem(keys.completedTeaching)
  const finishedImports = imports.value
    .filter(officialImportFinished)
    .map(job => job.id)
  dismissedImportIds.value = new Set([...dismissedImportIds.value, ...finishedImports])
  safelyStore(keys.dismissedImports, [...dismissedImportIds.value])
  const finishedUploadHandoffs = uploadedTeachingHandoffs.value
    .filter(uploadedTeachingHandoffFinished)
    .map(handoff => handoff.id)
  dismissedUploadedHandoffIds.value = new Set([
    ...dismissedUploadedHandoffIds.value,
    ...finishedUploadHandoffs,
  ])
  safelyStore(keys.dismissedUploadHandoffs, [...dismissedUploadedHandoffIds.value])
  schedule(refreshGeneration, account)
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

function openCenter(trigger?: HTMLElement | null) {
  requestedRestoreTarget.value = trigger ?? null
  open.value = true
}

defineExpose({ openCenter })

watch([activeCount, finishedCount], ([active, finished]) => emit('status', active, finished), { immediate: true })

onMounted(() => {
  document.addEventListener('visibilitychange', handleVisibility)
  window.addEventListener(TEACHING_LAUNCHED_EVENT, handleTeachingLaunched)
  switchAccount(props.username)
})
watch(() => props.username, switchAccount)
onBeforeUnmount(() => {
  disposed = true
  invalidateRefresh()
  document.removeEventListener('visibilitychange', handleVisibility)
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
  imports.value = []
  uploadedTeachingHandoffs.value = []
  documents.value = []
  documentProgress.value = {}
  preparationStates.value = {}
  preparationSubjects.value = {}
  dismissedImportIds.value = new Set()
  dismissedUploadedHandoffIds.value = new Set()
  titles.clear()
  clearLegacyBackgroundWorkStorage(sessionStorage)
  if (!account) {
    loading.value = false
    return
  }
  const keys = backgroundWorkStorageKeys(account)
  activeTeaching.value = parseBackgroundTeachingItems(sessionStorage.getItem(keys.activeTeaching))
  completedTeaching.value = parseBackgroundTeachingItems(sessionStorage.getItem(keys.completedTeaching))
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
    const stored = JSON.parse(sessionStorage.getItem(key) ?? '[]') as unknown
    if (!Array.isArray(stored)) return new Set<string>()
    return new Set(stored.slice(0, 100)
      .filter((value): value is string => typeof value === 'string' && value.trim().length > 0 && value.length <= 128))
  } catch {
    return new Set<string>()
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
                  <p class="mt-1 text-sm text-ink/60">{{ item.stage }}</p>
                  <p v-if="item.detail" class="mt-1 text-xs text-ink/45">{{ item.detail }}</p>
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
          <button type="button" class="min-h-11 text-sm font-semibold text-ink/55 hover:text-ink" @click="dismissFinished">{{ locale === 'zh-CN' ? '清除已结束任务' : 'Clear finished work' }}</button>
        </footer>
      </aside>
    </div>
  </div>
</template>
