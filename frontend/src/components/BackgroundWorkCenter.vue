<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import TabletopGlyph from '@/components/TabletopGlyph.vue'
import {
  parseBackgroundTeachingItems,
  reconcileBackgroundTeaching,
  type BackgroundTeachingItem,
} from '@/lib/backgroundTeachingStatus'
import { playerFacingTitle } from '@/lib/lessonPresentation'
import { useLocale } from '@/lib/locale'
import { TEACHING_LAUNCHED_EVENT, teachingLaunchDetail } from '@/lib/teachingLaunch'

const props = defineProps<{ username: string }>()
const emit = defineEmits<{
  status: [activeCount: number, finishedCount: number]
}>()

interface TeachingPlanSummary { id: string; gameTitle: string }
interface ActiveTeachingRun { id: string; subjectId: string; state: string; updatedAt: string }
interface TeachingRunDetails { run: { id: string; state: string } }
interface RulebookImportJob {
  id: string
  title: string
  sourceDomain: string
  stage: 'QUEUED' | 'CONNECTING' | 'DOWNLOADING' | 'COMPRESSING' | 'VERIFYING_FILE' | 'SAVING' | 'COMPLETED' | 'FAILED'
  downloadedBytes: number
  totalBytes: number | null
  documentVersionId: string | null
  errorCode: string | null
  teachingHandoffState?: 'NOT_REQUESTED' | 'WAITING_FOR_DOCUMENT' | 'LAUNCHING' | 'LAUNCHED' | 'FAILED'
  teachingPreparationRunId?: string | null
  teachingErrorCode?: string | null
  updatedAt: string
}
interface DocumentSummary {
  document: { id: string; title: string }
  latestVersion: { id: string; status: string }
}
interface DocumentProgress {
  stage: string
  percentage: number
  processedPages: number
  totalPages: number
  complete: boolean
}

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
const documents = ref<DocumentSummary[]>([])
const documentProgress = ref<Record<string, DocumentProgress>>({})
const preparationStates = ref<Record<string, string>>({})
const dismissedImportIds = ref<Set<string>>(new Set())
const titles = new Map<string, string>()
const ACTIVE_TEACHING_KEY = 'rulepilot:active-teaching-runs'
const COMPLETED_TEACHING_KEY = 'rulepilot:completed-teaching-runs'
const DISMISSED_IMPORTS_KEY = 'rulepilot:dismissed-official-imports'
const terminalTeachingStates = new Set(['COMPLETED', 'INSUFFICIENT_EVIDENCE', 'DEGRADED', 'FAILED'])
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false

const copy = computed(() => locale.value === 'zh-CN' ? {
  trigger: '后台任务', title: '后台任务', close: '关闭后台任务', empty: '当前没有后台任务。',
  safe: '可以继续浏览，离开页面不会中断这些任务。', retrying: '暂时没有拿到最新进度，正在自动重试。',
  download: '获取规则书', rulebook: '读取规则书', lesson: '生成讲解', done: '已完成', failed: '需要处理',
  queued: '等待下载', connecting: '正在连接规则书来源', downloading: '正在下载规则书内容', compressing: '文件较大，正在压缩 PDF', verifying: '正在核验 PDF',
  saving: '正在保存并交给规则书读取', uploaded: '等待开始读取', extracting: '正在提取规则文字',
  rendering: '正在生成规则书页面', structuring: '正在整理章节与图例', teaching: '正在组织讲解',
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
  rendering: 'Rendering rulebook pages', structuring: 'Organizing chapters and visual references', teaching: 'Organizing the lesson',
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

function importState(job: RulebookImportJob): WorkState {
  if (job.stage === 'FAILED' || job.teachingHandoffState === 'FAILED') return 'failed'
  if (job.stage !== 'COMPLETED'
    || job.teachingHandoffState === 'WAITING_FOR_DOCUMENT'
    || job.teachingHandoffState === 'LAUNCHING') return 'active'
  return 'complete'
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
    EXTRACTING: copy.value.extracting,
    RENDERING: copy.value.rendering,
    STRUCTURING: copy.value.structuring,
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
    .map((job): WorkItem => {
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
        id: `import:${job.id}`, kind: 'download', title: job.title, stage: importStage(job), detail,
        state: importState(job),
        progress, target: { name: 'teach', query: { importJob: job.id } }, updatedAt: job.updatedAt,
      }
    })
  const importVersionIds = new Set(imports.value
    .filter(job => !['COMPLETED', 'FAILED'].includes(job.stage))
    .map(job => job.documentVersionId)
    .filter(Boolean))
  const documentItems = documents.value
    .filter(entry => !['READY', 'FAILED'].includes(entry.latestVersion.status))
    .filter(entry => !importVersionIds.has(entry.latestVersion.id))
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
  return [...importItems, ...documentItems, ...preparationItems, ...teachingItems, ...finishedTeachingItems]
    .sort((left, right) => (left.state === 'active' ? 0 : 1) - (right.state === 'active' ? 0 : 1))
})
const activeCount = computed(() => workItems.value.filter(item => item.state === 'active').length)
const finishedCount = computed(() => workItems.value.filter(item => item.state !== 'active').length)

function clearTimer() {
  if (timer) clearTimeout(timer)
  timer = undefined
}

function schedule() {
  clearTimer()
  if (disposed || document.visibilityState === 'hidden') return
  timer = setTimeout(refresh, activeCount.value || unavailable.value ? 4_000 : 15_000)
}

async function responseJson<T>(path: string): Promise<T> {
  const response = await fetch(path, { credentials: 'include' })
  if (!response.ok) throw new Error('background work unavailable')
  return await response.json() as T
}

async function refreshTeaching() {
  const runPayload = await responseJson<unknown>('/api/v1/assistant-runs/active?mode=TEACHING')
  if (!Array.isArray(runPayload)) throw new Error('background teaching status is invalid')
  const runs = runPayload as ActiveTeachingRun[]
  if (runs.some(run => !titles.has(run.subjectId))) {
    const planPayload = await responseJson<unknown>('/api/v1/teaching-plans')
    if (!Array.isArray(planPayload)) throw new Error('background teaching plans are invalid')
    const plans = planPayload as TeachingPlanSummary[]
    for (const plan of plans) titles.set(plan.id, playerFacingTitle(plan.gameTitle))
  }
  teachingStates.value = Object.fromEntries(runs.map(run => [run.id, run.state]))
  const active = runs.map(run => ({
    runId: run.id, planId: run.subjectId,
    gameTitle: titles.get(run.subjectId) ?? (locale.value === 'zh-CN' ? '一份讲解' : 'A lesson'),
  }))
  const previous = parseBackgroundTeachingItems(sessionStorage.getItem(ACTIVE_TEACHING_KEY))
  const activePlanIds = new Set(active.map(item => item.planId))
  const missing = previous.filter(item => !activePlanIds.has(item.planId))
  const confirmations = await Promise.all(missing.map(async (item) => {
    try {
      const details = await responseJson<TeachingRunDetails>(`/api/v1/assistant-runs/${encodeURIComponent(item.runId)}`)
      return terminalTeachingStates.has(details.run.state) ? null : item
    } catch {
      return item
    }
  }))
  const retained = confirmations.filter((item): item is BackgroundTeachingItem => item !== null)
  const transition = reconcileBackgroundTeaching(previous, [...active, ...retained])
  activeTeaching.value = transition.active
  sessionStorage.setItem(ACTIVE_TEACHING_KEY, JSON.stringify(transition.active))
  if (transition.finished.length) {
    const notices = new Map(completedTeaching.value.map(item => [item.planId, item]))
    for (const item of transition.finished) notices.set(item.planId, item)
    completedTeaching.value = [...notices.values()]
    sessionStorage.setItem(COMPLETED_TEACHING_KEY, JSON.stringify(completedTeaching.value))
  }
}

async function refreshDocuments() {
  const [importPayload, documentPayload] = await Promise.all([
    responseJson<unknown>('/api/v1/documents/official-imports'),
    responseJson<unknown>('/api/v1/documents'),
  ])
  if (!Array.isArray(importPayload) || !Array.isArray(documentPayload)) {
    throw new Error('background rulebook status is invalid')
  }
  const recentImports = importPayload as RulebookImportJob[]
  const documentList = documentPayload as DocumentSummary[]
  imports.value = recentImports
  documents.value = documentList
  const preparationSnapshots = await Promise.all(recentImports.flatMap(job => {
    const runId = job.teachingPreparationRunId
    if (!runId) return []
    return [responseJson<TeachingRunDetails>(`/api/v1/assistant-runs/${encodeURIComponent(runId)}`)
      .then(details => [runId, details.run.state] as const)
      .catch(() => preparationStates.value[runId]
        ? [runId, preparationStates.value[runId]] as const
        : null)]
  }))
  preparationStates.value = Object.fromEntries(preparationSnapshots.filter(
    (entry): entry is readonly [string, string] => entry !== null,
  ))
  const active = documentList.filter(entry => !['READY', 'FAILED'].includes(entry.latestVersion.status))
  const snapshots = await Promise.all(active.map(async (entry) => {
    try {
      return [entry.latestVersion.id, await responseJson<DocumentProgress>(
        `/api/v1/document-versions/${encodeURIComponent(entry.latestVersion.id)}/progress/snapshot`,
      )] as const
    } catch {
      return null
    }
  }))
  documentProgress.value = Object.fromEntries(snapshots.filter((entry): entry is readonly [string, DocumentProgress] => entry !== null))
}

async function refresh() {
  if (disposed || !props.username || document.visibilityState === 'hidden') return
  try {
    await Promise.all([refreshTeaching(), refreshDocuments()])
    unavailable.value = false
  } catch {
    unavailable.value = true
  } finally {
    loading.value = false
    schedule()
  }
}

function dismissFinished() {
  completedTeaching.value = []
  sessionStorage.removeItem(COMPLETED_TEACHING_KEY)
  const finishedImports = imports.value
    .filter(job => job.stage === 'FAILED'
      || job.teachingHandoffState === 'FAILED'
      || job.stage === 'COMPLETED'
        && !['WAITING_FOR_DOCUMENT', 'LAUNCHING'].includes(job.teachingHandoffState ?? 'NOT_REQUESTED'))
    .map(job => job.id)
  dismissedImportIds.value = new Set([...dismissedImportIds.value, ...finishedImports])
  sessionStorage.setItem(DISMISSED_IMPORTS_KEY, JSON.stringify([...dismissedImportIds.value]))
}

function handleVisibility() {
  if (document.visibilityState === 'hidden') clearTimer()
  else void refresh()
}

function handleTeachingLaunched(event: Event) {
  const detail = teachingLaunchDetail(event)
  if (!detail) return
  const gameTitle = detail.gameTitle ?? titles.get(detail.planId) ?? (locale.value === 'zh-CN' ? '一份讲解' : 'A lesson')
  if (detail.gameTitle) titles.set(detail.planId, detail.gameTitle)
  const items = new Map(activeTeaching.value.map(item => [item.planId, item]))
  items.set(detail.planId, { runId: detail.runId, planId: detail.planId, gameTitle })
  activeTeaching.value = [...items.values()]
  sessionStorage.setItem(ACTIVE_TEACHING_KEY, JSON.stringify(activeTeaching.value))
  void refresh()
}

function openCenter() {
  open.value = true
}

defineExpose({ openCenter })

watch([activeCount, finishedCount], ([active, finished]) => emit('status', active, finished), { immediate: true })

onMounted(() => {
  completedTeaching.value = parseBackgroundTeachingItems(sessionStorage.getItem(COMPLETED_TEACHING_KEY))
  try {
    const stored = JSON.parse(sessionStorage.getItem(DISMISSED_IMPORTS_KEY) ?? '[]')
    dismissedImportIds.value = new Set(Array.isArray(stored) ? stored.filter(value => typeof value === 'string') : [])
  } catch {
    dismissedImportIds.value = new Set()
  }
  document.addEventListener('visibilitychange', handleVisibility)
  window.addEventListener(TEACHING_LAUNCHED_EVENT, handleTeachingLaunched)
  void refresh()
})
watch(() => props.username, refresh)
onBeforeUnmount(() => {
  disposed = true
  clearTimer()
  document.removeEventListener('visibilitychange', handleVisibility)
  window.removeEventListener(TEACHING_LAUNCHED_EVENT, handleTeachingLaunched)
})
</script>

<template>
  <div>
    <div v-if="open" class="fixed inset-0 z-50 bg-ink/35 backdrop-blur-[2px]" @click.self="open = false">
      <aside class="absolute inset-y-0 right-0 flex w-full max-w-md flex-col border-l border-gold/25 bg-canvas elevation-lg-ink" role="dialog" aria-modal="true" :aria-label="copy.title">
        <header class="flex items-start justify-between border-b border-ink/10 bg-paper px-5 py-5">
          <div>
            <p class="tabletop-kicker">RulePilot</p>
            <h2 class="mt-1 font-display text-2xl font-semibold">{{ copy.title }}</h2>
            <p class="mt-1 text-sm leading-6 text-ink/55">{{ copy.safe }}</p>
          </div>
          <button type="button" class="grid min-h-11 min-w-11 place-items-center rounded-lg text-2xl text-ink/45 hover:bg-ink/5" :aria-label="copy.close" @click="open = false">×</button>
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
