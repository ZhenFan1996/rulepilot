<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'

import LessonChapterList from '@/components/LessonChapterList.vue'
import { useModalFocus } from '@/composables/useModalFocus'
import {
  acceptProgressiveLesson,
  teachingLessonNeedsFinalSnapshot,
  teachingRunIsActive,
} from '@/lib/liveLesson'
import { notifyLoginRequired } from '@/lib/authSession'
import { useLocale } from '@/lib/locale'
import { mergeTeachingRunProgress, teachingActivityText, type TeachingRunProgress } from '@/lib/teachingProgress'
import {
  fetchVisualStatusWithDeadline,
  VISUAL_LESSON_SETTLING_READS,
  VISUAL_REFRESH_FAILURE_LIMIT,
  VISUAL_RUN_DISCOVERY_LIMIT,
  visualEnrichmentResult,
  visualRunIsTerminal,
} from '@/lib/visualEnrichment'

interface TeachingPlan {
  id: string
  documentVersionId: string
  gameTitle: string
  premise: string
  sections: Array<{ position: number; title: string; visualEvidenceRecommended: boolean }>
}

interface TeachingPlanSeed {
  id: string
  documentVersionId: string
  gameTitle: string
  premise: string
  sections: Array<{ position: number; title: string; visualEvidenceRecommended?: boolean }>
}

interface VisualFocus {
  pageNumber: number
  label: string
  visibleDescription?: string
  x: number
  y: number
  width: number
  height: number
  sourceKind?: 'FULL_PAGE' | 'PAGE_REGION' | 'EMBEDDED_AUTHOR_IMAGE'
}

interface LessonSection {
  position: number
  topicKey: string
  coverageTags: string[]
  title: string
  required: boolean
  evidenceStatus: 'SUPPORTED' | 'CITED_DRAFT' | 'INSUFFICIENT_EVIDENCE'
  visualKind: string
  visualCaption: string
  visualSourcePages: number[]
  visualSourceChunkIds: string[]
  steps: Array<{
    position: number
    heading: string
    kind: string
    text: string
    sourcePages: number[]
    visualFocus: VisualFocus | null
    visualFoci?: VisualFocus[]
  }>
}

interface IllustratedLesson {
  id: string
  teachingPlanId: string
  status: 'COMPLETE' | 'DRAFT_READY' | 'INCOMPLETE'
  sections: LessonSection[]
}

const props = defineProps<{
  open: boolean
  planId: string
  initialPlan?: TeachingPlanSeed | null
  initialLesson?: IllustratedLesson | null
  restoreFocus?: () => HTMLElement | null
}>()
const emit = defineEmits<{ close: []; 'ask-questions': [] }>()
const { locale } = useLocale()
const plan = ref<TeachingPlan | null>(null)
const lesson = ref<IllustratedLesson | null>(null)
const run = ref<TeachingRunProgress | null>(null)
const visualRun = ref<TeachingRunProgress | null>(null)
const loading = ref(false)
const error = ref(false)
const refreshWarning = ref(false)
const refreshStopped = ref(false)
const visualRefreshStopped = ref(false)
const dialog = ref<HTMLElement | null>(null)
let requestSequence = 0
let timer: ReturnType<typeof setTimeout> | null = null
let disposed = false
let activeController: AbortController | null = null
let activeVisualController: AbortController | null = null
let missingRunRefreshes = 0
let missingVisualRunRefreshes = 0
let refreshFailures = 0
let visualRefreshFailures = 0
let visualRefreshDelay = 1_500
let visualLessonSettlingReads = 0
let visualLessonUnacceptedSnapshots = 0
let visualDiscoveryScope: string | null = null
let observedTerminalVisualRunId: string | null = null
let visualIdentityBlocked = false

const MIN_REFRESH_DELAY_MS = 250
const REFRESH_FAILURE_LIMIT = 3
const MISSING_RUN_REFRESH_LIMIT = 2
const VISUAL_LESSON_UNACCEPTED_LIMIT = 4

class IdentityBoundaryError extends Error {}
class ResponseIdentityError extends Error {}

type VisualRunRead =
  | { outcome: 'PRESENT'; value: TeachingRunProgress }
  | { outcome: 'ABSENT' }
  | { outcome: 'IDENTITY' }
  | { outcome: 'FAILED' }

useModalFocus({
  dialog,
  open: () => props.open,
  requestClose: () => emit('close'),
  restoreFocus: props.restoreFocus,
})

const copy = computed(() => locale.value === 'zh-CN' ? {
  dialog: '生成讲解阅读器', close: '关闭讲解', eyebrow: '规则书讲解', updates: '讲解更新', loading: '正在打开已生成的讲解…', error: '讲解暂时无法打开。', retry: '重试',
  draft: '已有 {done} / {total} 章完成引用归属、规则书版本与结构校验；其余章节仍在后台生成。', complete: '完整讲解已经生成。', incomplete: '当前讲解只发布了具备可用规则依据的章节。',
  syncing: '已有 {done} / {total} 章完成引用归属、规则书版本与结构校验；正在确认后台任务状态。', settledDraft: '已有 {done} / {total} 章完成引用归属、规则书版本与结构校验；未发布章节仍缺少可用规则依据。',
  citedDraft: (count: number) => `另有 ${count} 章已通过确定性引用与结构校验，现在可以阅读；额外内容复核尚未完成。`,
  reviewedDraft: (count: number) => `本轮生成已经结束；已保留 ${count} 章可读讲解草稿，额外内容复核尚未完成。`,
  terminalIncomplete: (count: number) => `本轮生成已经结束；已保留 ${count} 章可读内容，证据不足的章节未作为完整规则讲解发布。`,
  failedReadable: (count: number) => `本轮讲解生成失败；已发布的 ${count} 章可读草稿仍然保留。`, failedEmpty: '本轮讲解生成失败，目前还没有可读章节。',
  cancelledReadable: (count: number) => `本轮讲解生成已取消；已发布的 ${count} 章可读草稿仍然保留。`, cancelledEmpty: '本轮讲解生成已取消，目前还没有可读章节。',
  noReadable: '本轮生成已经结束，但还没有具备可用规则依据的章节。',
  progressAria: (done: number, total: number) => `${done} / ${total} 章已通过独立规则依据核对`,
  refresh: '暂时无法刷新最新章节，已显示的内容仍可继续阅读。', ask: '切换到规则答疑', source: '每个步骤都保留原规则书页码；答疑只使用同一份规则书。',
  visualActive: '正在从规则书中挑选能帮助上桌的局部图示。', visualAdded: (count: number) => `已有 ${count} 节具备图示。`, visualNone: '这次没有找到可靠的局部图示；文字讲解仍可完整阅读。', visualFailed: '局部配图没有完成；已发布的文字讲解仍可完整阅读。', visualPartial: (count: number) => `已有 ${count} 节具备图示；后续配图没有完成，文字讲解仍可完整阅读。`, visualRefresh: '暂时无法确认最新配图状态；文字讲解仍可阅读。',
} : {
  dialog: 'Generated guide reader', close: 'Close guide', eyebrow: 'Rulebook guide', updates: 'Guide updates', loading: 'Opening generated guide content…', error: 'The guide cannot be opened right now.', retry: 'Retry',
  draft: '{done} / {total} chapters passed citation-ownership, rulebook-version, and structure checks; the remaining chapters are still being generated.', complete: 'The complete guide is ready.', incomplete: 'This guide publishes only chapters with usable rulebook support.',
  syncing: '{done} / {total} chapters passed citation-ownership, rulebook-version, and structure checks while the background task status is confirmed.', settledDraft: '{done} / {total} chapters passed citation-ownership, rulebook-version, and structure checks; unpublished chapters still lack usable rulebook support.',
  citedDraft: (count: number) => `${count} more ${count === 1 ? 'chapter has' : 'chapters have'} passed deterministic citation and structure checks and can be read now; additional content review is not complete.`,
  reviewedDraft: (count: number) => `This generation run has finished with ${count} readable guide ${count === 1 ? 'chapter draft' : 'chapter drafts'} preserved. Additional content review is not complete.`,
  terminalIncomplete: (count: number) => `This generation run has finished with ${count} readable ${count === 1 ? 'chapter' : 'chapters'} preserved. Chapters without enough evidence were not published as complete rules guidance.`,
  failedReadable: (count: number) => `This guide generation run failed. ${count} readable ${count === 1 ? 'chapter draft remains' : 'chapter drafts remain'} available.`, failedEmpty: 'This guide generation run failed without a readable chapter.',
  cancelledReadable: (count: number) => `This guide generation run was cancelled. ${count} readable ${count === 1 ? 'chapter draft remains' : 'chapter drafts remain'} available.`, cancelledEmpty: 'This guide generation run was cancelled before a readable chapter was ready.',
  noReadable: 'This generation run finished without a chapter backed by usable rulebook evidence.',
  progressAria: (done: number, total: number) => `${done} of ${total} chapters independently supported by rulebook evidence`,
  refresh: 'The latest chapter update is unavailable. Confirmed content remains readable.', ask: 'Switch to rules Q&A', source: 'Every step retains original rulebook page references; Q&A uses the same rulebook.',
  visualActive: 'Selecting focused rulebook visuals that help at the table.', visualAdded: (count: number) => `${count} ${count === 1 ? 'chapter now includes' : 'chapters now include'} visuals.`, visualNone: 'No reliable focused visual was found; the text guide remains fully readable.', visualFailed: 'Focused visual enrichment did not finish. The published text guide remains fully readable.', visualPartial: (count: number) => `${count} ${count === 1 ? 'chapter now includes' : 'chapters now include'} visuals; later visual enrichment did not finish, and the text guide remains fully readable.`, visualRefresh: 'The latest visual status is unavailable. The text guide remains readable.',
})

const active = computed(() => teachingRunIsActive(run.value?.run.state))
const visualState = computed(() => visualEnrichmentResult(
  visualRun.value,
  teachingRunIsActive(visualRun.value?.run.state),
))
const visualActive = computed(() => visualState.value.outcome === 'ACTIVE')
const visualFailed = computed(() => ['FAILED', 'PARTIAL'].includes(visualState.value.outcome))
const visualStatusText = computed(() => {
  if (visualRefreshStopped.value) return copy.value.visualRefresh
  const current = visualState.value
  if (current.outcome === 'ABSENT') return ''
  if (current.outcome === 'ACTIVE') return copy.value.visualActive
  if (current.outcome === 'FAILED') return copy.value.visualFailed
  if (current.outcome === 'PARTIAL') return copy.value.visualPartial(current.addedSectionCount)
  if (current.outcome === 'EMPTY') return copy.value.visualNone
  return copy.value.visualAdded(current.addedSectionCount)
})
const visualEvidenceExpected = computed(() => plan.value?.sections.some(
  section => section.visualEvidenceRecommended,
) === true)
const teachingRunTerminal = computed(() => visualRunIsTerminal(run.value?.run.state))
const supportedChapterCount = computed(() => lesson.value?.sections
  .filter(section => section.evidenceStatus === 'SUPPORTED').length ?? 0)
const citedDraftChapterCount = computed(() => lesson.value?.sections
  .filter(section => section.evidenceStatus === 'CITED_DRAFT').length ?? 0)
const readableChapterCount = computed(() => supportedChapterCount.value + citedDraftChapterCount.value)
const teachingStatusPresentation = computed(() => {
  if (!plan.value || !lesson.value) return { text: '', tone: 'active' as const }
  const state = run.value?.run.state
  const interpolate = (template: string) => template
    .replace('{done}', String(supportedChapterCount.value))
    .replace('{total}', String(plan.value!.sections.length))

  if (!run.value) return { text: interpolate(copy.value.syncing), tone: 'active' as const }
  if (state === 'FAILED') {
    return {
      text: readableChapterCount.value
        ? copy.value.failedReadable(readableChapterCount.value)
        : copy.value.failedEmpty,
      tone: 'failed' as const,
    }
  }
  if (state === 'CANCELLED') {
    return {
      text: readableChapterCount.value
        ? copy.value.cancelledReadable(readableChapterCount.value)
        : copy.value.cancelledEmpty,
      tone: 'cancelled' as const,
    }
  }
  if (active.value) {
    return {
      text: lesson.value.status === 'INCOMPLETE' ? copy.value.incomplete : interpolate(copy.value.draft),
      tone: 'active' as const,
    }
  }

  const fullySupported = state === 'COMPLETED'
    && lesson.value.status === 'COMPLETE'
    && lesson.value.sections.length > 0
    && lesson.value.sections.every(section => section.evidenceStatus === 'SUPPORTED')
  if (fullySupported) return { text: copy.value.complete, tone: 'complete' as const }
  if (!readableChapterCount.value) return { text: copy.value.noReadable, tone: 'partial' as const }
  if (state === 'COMPLETED') {
    return { text: copy.value.reviewedDraft(readableChapterCount.value), tone: 'partial' as const }
  }
  if (state === 'DEGRADED' || state === 'INSUFFICIENT_EVIDENCE') {
    return { text: copy.value.terminalIncomplete(readableChapterCount.value), tone: 'partial' as const }
  }
  return { text: interpolate(copy.value.settledDraft), tone: 'partial' as const }
})
const teachingStatusClass = computed(() => teachingStatusPresentation.value.tone === 'active'
  ? 'text-indigo'
  : teachingStatusPresentation.value.tone === 'complete' ? 'text-emerald-700' : 'text-amber-800')
const progress = computed(() => {
  const total = plan.value?.sections.length ?? 0
  if (!total) return 0
  return Math.min(100, Math.round(supportedChapterCount.value / total * 100))
})
const activityText = computed(() => {
  if (!plan.value || !run.value?.activities.length) return ''
  return teachingActivityText(plan.value, run.value.activities, run.value.activities.at(-1), locale.value)
})

function pageImageUrl(page: number) {
  return plan.value ? `/api/v1/document-versions/${encodeURIComponent(plan.value.documentVersionId)}/pages/${page}/image` : ''
}

function pagePreviewImageUrl(page: number) {
  return plan.value ? `/api/v1/document-versions/${encodeURIComponent(plan.value.documentVersionId)}/pages/${page}/image/preview` : ''
}

function focusedPageImageUrl(focus: VisualFocus) {
  if (!plan.value) return ''
  const query = new URLSearchParams({
    x: String(focus.x), y: String(focus.y), width: String(focus.width), height: String(focus.height),
  })
  return `/api/v1/document-versions/${encodeURIComponent(plan.value.documentVersionId)}/pages/${focus.pageNumber}/image/crop?${query}`
}

function isAbortError(error: unknown) {
  return (error as { name?: unknown } | null)?.name === 'AbortError'
}

function isCurrentGeneration(request: number, planId: string) {
  return !disposed
    && props.open
    && request === requestSequence
    && props.planId === planId
}

function isCurrentRequest(request: number, planId: string, controller: AbortController) {
  return isCurrentGeneration(request, planId)
    && activeController === controller
}

function responseMatchesPlan(
  planId: string,
  incomingPlan: TeachingPlan | null,
  incomingLesson: IllustratedLesson | null,
  incomingRun: TeachingRunProgress | null,
) {
  return (!incomingPlan || incomingPlan.id === planId)
    && (!incomingLesson || incomingLesson.teachingPlanId === planId)
    && (!incomingRun || incomingRun.run.subjectId === planId)
}

function acceptInitialSnapshot(planId: string) {
  const incomingPlan = props.initialPlan
  const incomingLesson = props.initialLesson
  if (incomingPlan?.id !== planId
    || incomingLesson?.teachingPlanId !== planId
    || incomingLesson.sections.length === 0) return false
  const normalizedPlan: TeachingPlan = {
    ...incomingPlan,
    sections: incomingPlan.sections.map(section => ({
      ...section,
      visualEvidenceRecommended: section.visualEvidenceRecommended ?? false,
    })),
  }
  plan.value = normalizedPlan
  lesson.value = acceptProgressiveLesson(
    lesson.value?.id === incomingLesson.id ? lesson.value : null,
    incomingLesson,
  )
  return true
}

async function optionalJson<T>(path: string, signal: AbortSignal): Promise<T | null> {
  const response = await fetch(path, { credentials: 'include', signal })
  if (response.status === 401 || response.status === 403) throw new IdentityBoundaryError('login required')
  if (response.status === 404) return null
  if (!response.ok) throw new Error('request failed')
  return await response.json() as T
}

async function readVisualRun(path: string, signal: AbortSignal): Promise<VisualRunRead> {
  try {
    const response = await fetchVisualStatusWithDeadline(path, { credentials: 'include', signal })
    if (response.status === 401 || response.status === 403) return { outcome: 'IDENTITY' }
    if (response.status === 404) return { outcome: 'ABSENT' }
    if (!response.ok) return { outcome: 'FAILED' }
    return { outcome: 'PRESENT', value: await response.json() as TeachingRunProgress }
  } catch (caught) {
    if (isAbortError(caught)) throw caught
    return { outcome: 'FAILED' }
  }
}

function resetVisualLifecycle(clearRun = true) {
  activeVisualController?.abort()
  activeVisualController = null
  if (clearRun) visualRun.value = null
  missingVisualRunRefreshes = 0
  visualRefreshFailures = 0
  visualRefreshDelay = 1_500
  visualLessonSettlingReads = 0
  visualLessonUnacceptedSnapshots = 0
  visualDiscoveryScope = null
  observedTerminalVisualRunId = null
  visualIdentityBlocked = false
  visualRefreshStopped.value = false
}

function syncVisualDiscoveryScope(planId: string) {
  const nextScope = run.value ? `${planId}:${run.value.run.id}` : null
  if (visualDiscoveryScope === nextScope) return
  activeVisualController?.abort()
  activeVisualController = null
  const replacingRun = visualDiscoveryScope !== null && visualDiscoveryScope !== nextScope
  visualDiscoveryScope = nextScope
  missingVisualRunRefreshes = 0
  visualRefreshFailures = 0
  visualRefreshDelay = 1_500
  visualLessonSettlingReads = 0
  visualLessonUnacceptedSnapshots = 0
  observedTerminalVisualRunId = null
  visualIdentityBlocked = false
  visualRefreshStopped.value = false
  if (replacingRun) visualRun.value = null
}

function acceptVisualRun(incoming: TeachingRunProgress) {
  visualRun.value = mergeTeachingRunProgress(visualRun.value, incoming)
  missingVisualRunRefreshes = 0
  visualRefreshFailures = 0
  visualRefreshDelay = 1_500
  if (visualRunIsTerminal(visualRun.value?.run.state)
    && observedTerminalVisualRunId !== visualRun.value?.run.id) {
    observedTerminalVisualRunId = visualRun.value!.run.id
    visualLessonSettlingReads = VISUAL_LESSON_SETTLING_READS
    visualLessonUnacceptedSnapshots = 0
  }
}

function visualDiscoveryPending() {
  return teachingRunTerminal.value
    && visualEvidenceExpected.value
    && !visualRun.value
    && missingVisualRunRefreshes < VISUAL_RUN_DISCOVERY_LIMIT
}

function visualRunReadPending() {
  if (visualRefreshStopped.value
    || visualIdentityBlocked
    || visualRefreshFailures >= VISUAL_REFRESH_FAILURE_LIMIT) return false
  if (visualRun.value) {
    return visualActive.value && missingVisualRunRefreshes < VISUAL_RUN_DISCOVERY_LIMIT
  }
  return visualDiscoveryPending()
}

function isCurrentVisualRequest(
  request: number,
  planId: string,
  scope: string,
  controller: AbortController,
) {
  return isCurrentGeneration(request, planId)
    && activeVisualController === controller
    && visualDiscoveryScope === scope
    && teachingRunTerminal.value
}

async function refreshVisualRun(
  request: number,
  planId: string,
  scope: string,
  coreSnapshotAccepted: Promise<boolean>,
) {
  const controller = new AbortController()
  activeVisualController = controller
  let nextDelay = 1_500
  try {
    const visualRunRead = await readVisualRun(
      `/api/v1/assistant-runs/latest?mode=VISUAL_ENRICHMENT&subjectId=${encodeURIComponent(planId)}`,
      controller.signal,
    )
    if (!await coreSnapshotAccepted) return
    if (!isCurrentVisualRequest(request, planId, scope, controller)) return

    if (visualRunRead.outcome === 'PRESENT' && visualRunRead.value.run.subjectId !== planId) {
      visualIdentityBlocked = true
      visualRefreshStopped.value = true
      return
    }
    if (visualRunRead.outcome === 'IDENTITY') {
      visualIdentityBlocked = true
      visualRefreshStopped.value = true
      notifyLoginRequired()
      return
    }
    if (visualRunRead.outcome === 'PRESENT') {
      acceptVisualRun(visualRunRead.value)
      return
    }
    if (visualRunRead.outcome === 'ABSENT') {
      missingVisualRunRefreshes += 1
      visualRefreshFailures = 0
      visualRefreshDelay = 1_500
      if (missingVisualRunRefreshes >= VISUAL_RUN_DISCOVERY_LIMIT) {
        visualRefreshStopped.value = true
      }
      return
    }

    visualRefreshFailures += 1
    const bodySnapshotPending = active.value || teachingLessonNeedsFinalSnapshot(
      run.value?.run.state,
      lesson.value?.status,
    )
    nextDelay = bodySnapshotPending
      ? 1_500
      : Math.min(12_000, 3_000 * 2 ** (visualRefreshFailures - 1))
    visualRefreshDelay = nextDelay
    if (visualRefreshFailures >= VISUAL_REFRESH_FAILURE_LIMIT) {
      visualRefreshStopped.value = true
    }
  } catch (caught) {
    if (!isAbortError(caught) && isCurrentVisualRequest(request, planId, scope, controller)) {
      visualRefreshFailures += 1
      if (visualRefreshFailures >= VISUAL_REFRESH_FAILURE_LIMIT) {
        visualRefreshStopped.value = true
      }
    }
  } finally {
    if (activeVisualController === controller) activeVisualController = null
    if (isCurrentGeneration(request, planId)
      && visualDiscoveryScope === scope
      && activeController === null
      && timer === null) {
      scheduleRefresh(request, planId, nextDelay)
    }
  }
}

function startVisualRefresh(request: number, planId: string, coreSnapshotAccepted: Promise<boolean>) {
  if (activeVisualController || !visualRunReadPending() || !visualDiscoveryScope) return
  void refreshVisualRun(request, planId, visualDiscoveryScope, coreSnapshotAccepted)
}

function resetRefreshBudgets() {
  refreshFailures = 0
  refreshStopped.value = false
}

function retryRefresh() {
  if (!props.open || !props.planId) return
  resetRefreshBudgets()
  missingRunRefreshes = 0
  missingVisualRunRefreshes = 0
  visualRefreshFailures = 0
  visualRefreshDelay = MIN_REFRESH_DELAY_MS
  visualLessonUnacceptedSnapshots = 0
  visualIdentityBlocked = false
  visualRefreshStopped.value = false
  refreshWarning.value = false
  scheduleRefresh(requestSequence, props.planId, MIN_REFRESH_DELAY_MS)
}

async function load() {
  if (!props.open || !props.planId) return
  const planId = props.planId
  const request = ++requestSequence
  if (plan.value?.id !== planId) {
    plan.value = null
    lesson.value = null
    run.value = null
    missingRunRefreshes = 0
    resetVisualLifecycle()
  }
  resetRefreshBudgets()
  const readableSnapshot = acceptInitialSnapshot(planId)
  clearTimer()
  activeController?.abort()
  const controller = new AbortController()
  activeController = controller
  loading.value = !readableSnapshot
  error.value = false
  refreshWarning.value = false
  let loaded = false
  try {
    const [incomingPlan, incomingLesson, incomingRun] = await Promise.all([
      optionalJson<TeachingPlan>(`/api/v1/teaching-plans/${encodeURIComponent(planId)}`, controller.signal),
      optionalJson<IllustratedLesson>(`/api/v1/teaching-plans/${encodeURIComponent(planId)}/illustrated-lessons/latest`, controller.signal),
      optionalJson<TeachingRunProgress>(`/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(planId)}`, controller.signal),
    ])
    if (!isCurrentRequest(request, planId, controller)) return
    if (!incomingPlan || !incomingLesson || !responseMatchesPlan(planId, incomingPlan, incomingLesson, incomingRun)) {
      throw new ResponseIdentityError('guide response identity mismatch')
    }
    plan.value = incomingPlan
    lesson.value = acceptProgressiveLesson(lesson.value?.id === incomingLesson.id ? lesson.value : null, incomingLesson)
    run.value = incomingRun
      ? mergeTeachingRunProgress(run.value?.run.id === incomingRun.run.id ? run.value : null, incomingRun)
      : null
    syncVisualDiscoveryScope(planId)
    if (incomingRun) missingRunRefreshes = 0
    loaded = true
  } catch (caught) {
    if (!isAbortError(caught) && isCurrentRequest(request, planId, controller)) {
      if (caught instanceof IdentityBoundaryError) notifyLoginRequired()
      if (readableSnapshot) refreshWarning.value = true
      else error.value = true
      refreshStopped.value = caught instanceof IdentityBoundaryError || caught instanceof ResponseIdentityError
      controller.abort()
    }
  } finally {
    if (isCurrentRequest(request, planId, controller)) {
      activeController = null
      loading.value = false
      if (loaded || readableSnapshot) {
        if (visualDiscoveryPending()) visualRefreshDelay = MIN_REFRESH_DELAY_MS
        scheduleRefresh(request, planId, refreshWarning.value ? 4_000 : visualDiscoveryPending() ? MIN_REFRESH_DELAY_MS : 1_500)
      }
    }
  }
}

async function refresh(request: number, planId: string) {
  if (!isCurrentGeneration(request, planId)) return
  const controller = new AbortController()
  activeController = controller
  const settlingLessonRead = visualLessonSettlingReads > 0
  let acceptedSettlingLesson = false
  let nextDelay = 1_500
  let resolveCoreSnapshot!: (accepted: boolean) => void
  let coreSnapshotResolved = false
  const coreSnapshotAccepted = new Promise<boolean>((resolve) => { resolveCoreSnapshot = resolve })
  const settleCoreSnapshot = (accepted: boolean) => {
    if (coreSnapshotResolved) return
    coreSnapshotResolved = true
    resolveCoreSnapshot(accepted)
  }
  try {
    startVisualRefresh(request, planId, coreSnapshotAccepted)
    const [incomingLesson, incomingRun] = await Promise.all([
      optionalJson<IllustratedLesson>(`/api/v1/teaching-plans/${encodeURIComponent(planId)}/illustrated-lessons/latest`, controller.signal),
      optionalJson<TeachingRunProgress>(`/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(planId)}`, controller.signal),
    ])
    if (!isCurrentRequest(request, planId, controller)) return
    if (!responseMatchesPlan(planId, null, incomingLesson, incomingRun)) {
      throw new ResponseIdentityError('guide response identity mismatch')
    }
    if (incomingLesson) {
      lesson.value = acceptProgressiveLesson(lesson.value, incomingLesson)
      if (settlingLessonRead && visualLessonSettlingReads > 0) {
        visualLessonSettlingReads -= 1
        visualLessonUnacceptedSnapshots = 0
        acceptedSettlingLesson = true
      }
    } else if (settlingLessonRead) {
      visualLessonUnacceptedSnapshots += 1
      if (visualLessonUnacceptedSnapshots >= VISUAL_LESSON_UNACCEPTED_LIMIT) {
        visualRefreshStopped.value = true
      }
    }
    run.value = mergeTeachingRunProgress(run.value, incomingRun)
    syncVisualDiscoveryScope(planId)
    if (incomingRun) missingRunRefreshes = 0
    settleCoreSnapshot(true)
    refreshFailures = 0
    refreshWarning.value = settlingLessonRead && !acceptedSettlingLesson
  } catch (caught) {
    settleCoreSnapshot(false)
    activeVisualController?.abort()
    if (!isAbortError(caught) && isCurrentRequest(request, planId, controller)) {
      if (caught instanceof IdentityBoundaryError || caught instanceof ResponseIdentityError) {
        if (caught instanceof IdentityBoundaryError) notifyLoginRequired()
        refreshStopped.value = true
      } else if (settlingLessonRead && !acceptedSettlingLesson) {
        visualLessonUnacceptedSnapshots += 1
        nextDelay = Math.min(16_000, 4_000 * 2 ** (visualLessonUnacceptedSnapshots - 1))
        if (visualLessonUnacceptedSnapshots >= VISUAL_LESSON_UNACCEPTED_LIMIT) {
          visualRefreshStopped.value = true
        }
      } else {
        refreshFailures += 1
        nextDelay = Math.min(16_000, 4_000 * 2 ** (refreshFailures - 1))
        if (refreshFailures >= REFRESH_FAILURE_LIMIT) refreshStopped.value = true
      }
      refreshWarning.value = true
      controller.abort()
    }
  } finally {
    settleCoreSnapshot(false)
    if (isCurrentRequest(request, planId, controller)) {
      activeController = null
      scheduleRefresh(request, planId, nextDelay)
    }
  }
}

function scheduleRefresh(request: number, planId: string, delay = 1_500) {
  clearTimer()
  const finalSnapshotPending = teachingLessonNeedsFinalSnapshot(
    run.value?.run.state,
    lesson.value?.status,
  )
  const retryMissingRun = !run.value && missingRunRefreshes < MISSING_RUN_REFRESH_LIMIT
  if (!run.value && missingRunRefreshes >= MISSING_RUN_REFRESH_LIMIT) {
    refreshWarning.value = true
    refreshStopped.value = true
  }
  const bodyWorkPending = retryMissingRun || active.value || finalSnapshotPending
  const visualWorkPending = activeVisualController === null
    && !visualRefreshStopped.value
    && (visualLessonSettlingReads > 0 || visualRunReadPending())
  const effectiveDelay = bodyWorkPending ? delay : visualRefreshDelay
  if (isCurrentGeneration(request, planId)
    && !refreshStopped.value
    && refreshFailures < REFRESH_FAILURE_LIMIT
    && document.visibilityState !== 'hidden'
    && (bodyWorkPending || visualWorkPending)) {
    if (retryMissingRun) missingRunRefreshes += 1
    timer = setTimeout(() => {
      timer = null
      void refresh(request, planId)
    }, Math.max(MIN_REFRESH_DELAY_MS, effectiveDelay))
  }
}

function clearTimer() {
  if (timer) clearTimeout(timer)
  timer = null
}

function cancelRequests() {
  requestSequence += 1
  clearTimer()
  activeController?.abort()
  activeController = null
  activeVisualController?.abort()
  activeVisualController = null
  loading.value = false
  missingRunRefreshes = 0
  resetRefreshBudgets()
  resetVisualLifecycle()
}

watch(() => [props.open, props.planId] as const, ([open]) => {
  if (open) void load()
  else cancelRequests()
}, { immediate: true })
onBeforeUnmount(() => {
  disposed = true
  cancelRequests()
})
</script>

<template>
  <Teleport to="body">
    <div v-if="open" data-testid="recommendation-lesson-backdrop" class="fixed inset-0 z-[100] overflow-y-auto bg-ink/45 backdrop-blur-[2px]" @click.self="emit('close')">
      <section ref="dialog" data-testid="recommendation-lesson-surface" tabindex="-1" class="relative isolate mx-auto min-h-screen w-full max-w-[100rem] text-ink outline-none sm:my-5 sm:min-h-0 sm:overflow-hidden sm:rounded-3xl sm:border sm:border-gold/25 sm:shadow-2xl" style="background-color: var(--color-canvas); opacity: 1" role="dialog" aria-modal="true" :aria-label="copy.dialog">
        <header class="app-sticky-top sticky z-20 border-b border-ink/10 bg-paper/95 px-4 py-4 backdrop-blur sm:px-6">
          <div class="flex items-start justify-between gap-4">
            <div class="min-w-0"><p class="tabletop-kicker">{{ copy.eyebrow }}</p><h2 class="mt-1 truncate font-display text-2xl font-semibold">{{ plan?.gameTitle ?? copy.dialog }}</h2><p v-if="plan?.premise" class="mt-1 max-w-3xl text-xs leading-5 text-ink/50">{{ plan.premise }}</p></div>
            <div class="flex shrink-0 items-center gap-2"><button v-if="lesson" type="button" class="min-h-11 rounded-lg bg-indigo px-4 text-sm font-semibold text-white" @click="emit('ask-questions')">{{ copy.ask }}</button><button type="button" data-modal-initial-focus class="grid min-h-11 min-w-11 place-items-center rounded-lg text-2xl text-ink/45 hover:bg-ink/5" :aria-label="copy.close" @click="emit('close')">×</button></div>
          </div>
          <div v-if="plan && lesson" class="mt-3">
            <div data-testid="recommendation-lesson-teaching-status" role="status" aria-live="polite" aria-atomic="true">
              <div class="flex items-center justify-between gap-3 text-xs"><p data-testid="recommendation-lesson-teaching-status-text" class="font-semibold" :class="teachingStatusClass">{{ teachingStatusPresentation.text }}</p><span class="font-mono font-semibold text-ink/50">{{ supportedChapterCount }} / {{ plan.sections.length }}</span></div>
              <p v-if="citedDraftChapterCount" data-testid="recommendation-lesson-cited-draft-status" class="mt-2 text-xs leading-5 text-ink/55">{{ copy.citedDraft(citedDraftChapterCount) }}</p>
            </div>
            <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-indigo/10" role="progressbar" :aria-valuemin="0" :aria-valuemax="plan.sections.length" :aria-valuenow="supportedChapterCount" :aria-label="copy.progressAria(supportedChapterCount, plan.sections.length)"><div data-testid="recommendation-lesson-progress" class="h-full rounded-full bg-indigo transition-[width] duration-500" :style="{ width: `${progress}%` }" /></div>
            <p v-if="activityText" class="mt-2 text-xs text-ink/50">{{ activityText }}</p>
          </div>
        </header>

        <section
          v-if="visualStatusText || refreshWarning"
          data-testid="recommendation-lesson-runtime-notices"
          class="border-b border-ink/10 bg-canvas px-4 py-3 sm:px-6"
          :aria-label="copy.updates"
        >
          <div class="mx-auto flex max-w-7xl min-w-0 flex-col gap-2">
            <div
              v-if="visualStatusText"
              data-testid="recommendation-lesson-visual-status"
              class="flex min-w-0 flex-wrap items-center justify-between gap-2 break-words rounded-lg px-3 py-2 text-xs font-semibold leading-5"
              :class="visualFailed || visualRefreshStopped ? 'bg-amber-50 text-amber-900' : 'bg-indigo/5 text-indigo'"
              role="status"
            >
              <p class="min-w-0 flex-1 break-words">{{ visualStatusText }}</p>
              <button v-if="visualRefreshStopped" type="button" class="min-h-10 shrink-0 rounded-lg border border-current/30 px-3" @click="retryRefresh">{{ copy.retry }}</button>
            </div>
            <div v-if="refreshWarning" class="flex min-w-0 flex-wrap items-center justify-between gap-2 rounded-lg bg-amber-50 px-3 py-2 text-amber-900" role="status">
              <p class="min-w-0 flex-1 break-words text-xs font-semibold">{{ copy.refresh }}</p>
              <button v-if="refreshStopped" type="button" class="min-h-10 shrink-0 rounded-lg border border-amber-300 px-3 text-xs font-semibold" @click="retryRefresh">{{ copy.retry }}</button>
            </div>
          </div>
        </section>

        <div class="mx-auto max-w-7xl px-4 py-5 sm:px-7 sm:py-7">
          <p v-if="loading" class="rounded-xl bg-paper p-10 text-center text-sm text-ink/55" role="status">{{ copy.loading }}</p>
          <section v-else-if="error || !plan || !lesson" class="rounded-xl border border-red-200 bg-paper p-10 text-center" role="alert"><p>{{ copy.error }}</p><button type="button" class="mt-4 min-h-11 rounded-lg bg-indigo px-5 font-semibold text-white" @click="load">{{ copy.retry }}</button></section>
          <template v-else>
            <p class="rounded-xl border border-indigo/10 bg-indigo/5 px-4 py-3 text-xs leading-5 text-ink/55">{{ copy.source }}</p>
            <LessonChapterList :sections="lesson.sections" :id-prefix="`journey-lesson-${lesson.id}`" :page-image-url="pageImageUrl" :page-preview-image-url="pagePreviewImageUrl" :focused-page-image-url="focusedPageImageUrl" />
          </template>
        </div>
      </section>
    </div>
  </Teleport>
</template>
