<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'

import LessonChapterList from '@/components/LessonChapterList.vue'
import PlayerFailureDetails from '@/components/PlayerFailureDetails.vue'
import { useModalFocus } from '@/composables/useModalFocus'
import {
  acceptProgressiveLesson,
  teachingLessonNeedsFinalSnapshot,
  teachingRunIsActive,
} from '@/lib/liveLesson'
import { notifyLoginRequired } from '@/lib/authSession'
import { useLocale } from '@/lib/locale'
import { teachingFailureOwner, type PlayerFailureDescriptor } from '@/lib/playerFailureSemantics'
import {
  playerJourneyFailurePresentation,
  playerJourneyRunIsTerminal,
  typedFailurePolicy,
} from '@/lib/playerJourney'
import {
  mergeTeachingRunProgress,
  teachingActivityText,
  teachingRunPresentationState,
  teachingRunStopReasonText,
  type TeachingRunProgress,
} from '@/lib/teachingProgress'

interface TeachingPlan {
  id: string
  documentVersionId: string
  gameTitle: string
  premise: string
  sections: Array<{ position: number; title: string; visualEvidenceRecommended: boolean }>
  unresolvedTopics?: string[]
}

interface TeachingPlanSeed {
  id: string
  documentVersionId: string
  gameTitle: string
  premise: string
  sections: Array<{ position: number; title: string; visualEvidenceRecommended?: boolean }>
  unresolvedTopics?: string[]
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
const loading = ref(false)
const error = ref(false)
const refreshWarning = ref(false)
const refreshStopped = ref(false)
const refreshStopReason = ref<'identity' | 'response-identity' | 'service' | null>(null)
const dialog = ref<HTMLElement | null>(null)
let requestSequence = 0
let timer: ReturnType<typeof setTimeout> | null = null
let disposed = false
let activeController: AbortController | null = null
let missingRunRefreshes = 0
let refreshFailures = 0

const MIN_REFRESH_DELAY_MS = 250
const REFRESH_FAILURE_LIMIT = 3
const MISSING_RUN_REFRESH_LIMIT = 2

class IdentityBoundaryError extends Error {}
class ResponseIdentityError extends Error {}

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
  refresh: '暂时无法刷新最新章节，已显示的内容仍可继续阅读。',
  refreshIdentity: '登录会话已失效，只停止当前页面刷新；已显示章节仍然保留，后台任务保持持久状态。重新登录后会重新绑定并刷新最新进度。',
  refreshResponseIdentity: '服务器返回了另一个讲解任务的状态，已停止合并；当前已显示章节仍然保留。',
  refreshService: '当前页面无法继续自动刷新；已显示章节仍然保留，后台任务状态未被改写，可以手动重新连接。',
  ask: '切换到规则答疑', source: '每个步骤都保留原规则书页码；答疑只使用同一份规则书。',
  failureBoundary: '单页、单章或配图失败只影响对应局部；模型服务、排队、超时、传输或取消可保留进度原样重试；认证、输入、来源、所有权、版本、保存、身份或引用问题需修复后重试。结构化格式错误由同一 Agent 接收完整候选和校验记录后内部修正，不会要求玩家改写问题；只有完全重复、无进展或资源停止才结束这一步。',
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
  refresh: 'The latest chapter update is unavailable. Confirmed content remains readable.',
  refreshIdentity: "The signed-in session expired. Only this page's refresh has stopped; displayed chapters remain available, and the background task keeps its durable state. Sign in again to rebind and refresh the latest progress.",
  refreshResponseIdentity: 'The server returned status for a different guide, so merging stopped. Displayed chapters remain available.',
  refreshService: 'This page cannot continue automatic refresh. Displayed chapters remain available, the background state was not rewritten, and you can reconnect manually.',
  ask: 'Switch to rules Q&A', source: 'Every step retains original rulebook page references; Q&A uses the same rulebook.',
  failureBoundary: 'A failed page, chapter, or visual affects only that item. Provider, queue, timeout, transport, or cancellation stops preserve progress for an unchanged retry. Authentication, input, source, ownership, version, persistence, identity, or citation errors require repair first. Typed-format errors are corrected internally by the same Agent with the complete candidate and validation record; players are not asked to rewrite the question. Only exact repetition, no progress, or a resource stop ends that step.',
})

const active = computed(() => teachingRunIsActive(run.value?.run.state))
const supportedChapterCount = computed(() => lesson.value?.sections
  .filter(section => section.evidenceStatus === 'SUPPORTED').length ?? 0)
const citedDraftChapterCount = computed(() => lesson.value?.sections
  .filter(section => section.evidenceStatus === 'CITED_DRAFT').length ?? 0)
const readableChapterCount = computed(() => supportedChapterCount.value + citedDraftChapterCount.value)
const teachingFailurePolicy = computed(() => {
  const current = run.value?.run
  if (!current || current.state === 'COMPLETED' || !playerJourneyRunIsTerminal(current.state)) return null
  return typedFailurePolicy(current.lastErrorCode ?? current.state, 'GENERATE_LESSON', false)
})
const latestRejectedActivity = computed(() => [...(run.value?.activities ?? [])]
  .reverse()
  .find(activity => activity.outcome === 'FAILED' || activity.outcome === 'REJECTED') ?? null)
const visibleFailureDetails = computed<PlayerFailureDescriptor | null>(() => {
  if (teachingFailurePolicy.value && run.value) {
    const code = run.value.run.lastErrorCode ?? run.value.run.state
    return {
      category: teachingFailurePolicy.value.failureClassification,
      owner: teachingFailureOwner(code, locale.value),
      code,
    }
  }
  const activity = latestRejectedActivity.value
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
function withFailureGuidance(text: string) {
  if (!teachingFailurePolicy.value) return text
  const guidance = playerJourneyFailurePresentation(teachingFailurePolicy.value, locale.value)
  const separator = locale.value === 'en' ? '. ' : '。'
  return `${text} ${guidance.title}${separator}${guidance.detail}`
}
const teachingStatusPresentation = computed(() => {
  if (!plan.value || !lesson.value) return { text: '', tone: 'active' as const }
  const state = teachingRunPresentationState(run.value)
  const interpolate = (template: string) => template
    .replace('{done}', String(supportedChapterCount.value))
    .replace('{total}', String(plan.value!.sections.length))

  if (!run.value) return { text: interpolate(copy.value.syncing), tone: 'active' as const }
  if (state === 'FAILED') {
    const reason = teachingRunStopReasonText(run.value, locale.value)
    return {
      text: withFailureGuidance(`${readableChapterCount.value
        ? copy.value.failedReadable(readableChapterCount.value)
        : copy.value.failedEmpty}${reason ? ` ${reason}` : ''}`),
      tone: 'failed' as const,
    }
  }
  if (state === 'CANCELLED') {
    const reason = teachingRunStopReasonText(run.value, locale.value)
    return {
      text: withFailureGuidance(`${readableChapterCount.value
        ? copy.value.cancelledReadable(readableChapterCount.value)
        : copy.value.cancelledEmpty}${reason ? ` ${reason}` : ''}`),
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
  if (!readableChapterCount.value) {
    return { text: withFailureGuidance(copy.value.noReadable), tone: 'partial' as const }
  }
  if (state === 'COMPLETED') {
    return { text: copy.value.reviewedDraft(readableChapterCount.value), tone: 'partial' as const }
  }
  if (state === 'DEGRADED' || state === 'INSUFFICIENT_EVIDENCE') {
    return {
      text: withFailureGuidance(copy.value.terminalIncomplete(readableChapterCount.value)),
      tone: 'partial' as const,
    }
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
const refreshMessage = computed(() => refreshStopReason.value === 'identity'
  ? copy.value.refreshIdentity
  : refreshStopReason.value === 'response-identity'
    ? copy.value.refreshResponseIdentity
    : refreshStopReason.value === 'service'
      ? copy.value.refreshService
      : copy.value.refresh)

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

function resetRefreshBudgets() {
  refreshFailures = 0
  refreshStopped.value = false
  refreshStopReason.value = null
}

function retryRefresh() {
  if (!props.open || !props.planId) return
  resetRefreshBudgets()
  missingRunRefreshes = 0
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
    if (incomingRun) missingRunRefreshes = 0
    loaded = true
  } catch (caught) {
    if (!isAbortError(caught) && isCurrentRequest(request, planId, controller)) {
      if (caught instanceof IdentityBoundaryError) notifyLoginRequired()
      if (readableSnapshot) refreshWarning.value = true
      else error.value = true
      refreshStopped.value = caught instanceof IdentityBoundaryError || caught instanceof ResponseIdentityError
      refreshStopReason.value = caught instanceof IdentityBoundaryError
        ? 'identity'
        : caught instanceof ResponseIdentityError ? 'response-identity' : null
      controller.abort()
    }
  } finally {
    if (isCurrentRequest(request, planId, controller)) {
      activeController = null
      loading.value = false
      if (loaded || readableSnapshot) {
        scheduleRefresh(request, planId, refreshWarning.value ? 4_000 : 1_500)
      }
    }
  }
}

async function refresh(request: number, planId: string) {
  if (!isCurrentGeneration(request, planId)) return
  const controller = new AbortController()
  activeController = controller
  let nextDelay = 1_500
  try {
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
    }
    run.value = mergeTeachingRunProgress(run.value, incomingRun)
    if (incomingRun) missingRunRefreshes = 0
    refreshFailures = 0
    refreshWarning.value = false
  } catch (caught) {
    if (!isAbortError(caught) && isCurrentRequest(request, planId, controller)) {
      if (caught instanceof IdentityBoundaryError || caught instanceof ResponseIdentityError) {
        if (caught instanceof IdentityBoundaryError) notifyLoginRequired()
        refreshStopped.value = true
        refreshStopReason.value = caught instanceof IdentityBoundaryError ? 'identity' : 'response-identity'
      } else {
        refreshFailures += 1
        nextDelay = Math.min(16_000, 4_000 * 2 ** (refreshFailures - 1))
        if (refreshFailures >= REFRESH_FAILURE_LIMIT) {
          refreshStopped.value = true
          refreshStopReason.value = 'service'
        }
      }
      refreshWarning.value = true
      controller.abort()
    }
  } finally {
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
    refreshStopReason.value = 'service'
  }
  const bodyWorkPending = retryMissingRun || active.value || finalSnapshotPending
  if (isCurrentGeneration(request, planId)
    && !refreshStopped.value
    && refreshFailures < REFRESH_FAILURE_LIMIT
    && document.visibilityState !== 'hidden'
    && bodyWorkPending) {
    if (retryMissingRun) missingRunRefreshes += 1
    timer = setTimeout(() => {
      timer = null
      void refresh(request, planId)
    }, Math.max(MIN_REFRESH_DELAY_MS, delay))
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
  loading.value = false
  missingRunRefreshes = 0
  resetRefreshBudgets()
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
            <div data-testid="recommendation-lesson-teaching-status" :data-failure-classification="teachingFailurePolicy?.failureClassification ?? undefined" :data-failure-recovery="teachingFailurePolicy?.failureRecovery ?? undefined" role="status" aria-live="polite" aria-atomic="true">
              <div class="flex items-center justify-between gap-3 text-xs"><p data-testid="recommendation-lesson-teaching-status-text" class="font-semibold" :class="teachingStatusClass">{{ teachingStatusPresentation.text }}</p><span class="font-mono font-semibold text-ink/50">{{ supportedChapterCount }} / {{ plan.sections.length }}</span></div>
              <p v-if="citedDraftChapterCount" data-testid="recommendation-lesson-cited-draft-status" class="mt-2 text-xs leading-5 text-ink/55">{{ copy.citedDraft(citedDraftChapterCount) }}</p>
            </div>
            <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-indigo/10" role="progressbar" :aria-valuemin="0" :aria-valuemax="plan.sections.length" :aria-valuenow="supportedChapterCount" :aria-label="copy.progressAria(supportedChapterCount, plan.sections.length)"><div data-testid="recommendation-lesson-progress" class="h-full rounded-full bg-indigo transition-[width] duration-500" :style="{ width: `${progress}%` }" /></div>
            <p v-if="activityText" class="mt-2 text-xs text-ink/50">{{ activityText }}</p>
            <p data-testid="recommendation-lesson-failure-boundary" class="mt-2 text-xs leading-5 text-ink/50">{{ copy.failureBoundary }}</p>
            <PlayerFailureDetails
              v-if="visibleFailureDetails"
              class="mt-3"
              :category="visibleFailureDetails.category"
              :owner="visibleFailureDetails.owner"
              :code="visibleFailureDetails.code"
            />
          </div>
        </header>

        <section
          v-if="refreshWarning"
          data-testid="recommendation-lesson-runtime-notices"
          class="border-b border-ink/10 bg-canvas px-4 py-3 sm:px-6"
          :aria-label="copy.updates"
        >
          <div class="mx-auto flex max-w-7xl min-w-0 flex-col gap-2">
            <div class="flex min-w-0 flex-wrap items-center justify-between gap-2 rounded-lg bg-amber-50 px-3 py-2 text-amber-900" role="status">
              <p class="min-w-0 flex-1 break-words text-xs font-semibold">{{ refreshMessage }}</p>
              <button v-if="refreshStopped" type="button" class="min-h-10 shrink-0 rounded-lg border border-amber-300 px-3 text-xs font-semibold" @click="retryRefresh">{{ copy.retry }}</button>
            </div>
          </div>
        </section>

        <div class="mx-auto max-w-7xl px-4 py-5 sm:px-7 sm:py-7">
          <p v-if="loading" class="rounded-xl bg-paper p-10 text-center text-sm text-ink/55" role="status">{{ copy.loading }}</p>
          <section v-else-if="error || !plan || !lesson" class="rounded-xl border border-red-200 bg-paper p-10 text-center" role="alert"><p>{{ copy.error }}</p><button type="button" class="mt-4 min-h-11 rounded-lg bg-indigo px-5 font-semibold text-white" @click="load">{{ copy.retry }}</button></section>
          <template v-else>
            <p class="rounded-xl border border-indigo/10 bg-indigo/5 px-4 py-3 text-xs leading-5 text-ink/55">{{ copy.source }}</p>
            <div v-if="plan.unresolvedTopics?.length" class="mt-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-xs leading-5 text-amber-950">
              <p class="font-semibold">{{ locale === 'en' ? 'Locally unavailable topics' : '局部未完成主题' }}</p>
              <ul class="mt-1 list-disc pl-5"><li v-for="topic in plan.unresolvedTopics" :key="topic">{{ topic }}</li></ul>
            </div>
            <LessonChapterList :sections="lesson.sections" :id-prefix="`journey-lesson-${lesson.id}`" :page-image-url="pageImageUrl" :page-preview-image-url="pagePreviewImageUrl" :focused-page-image-url="focusedPageImageUrl" />
          </template>
        </div>
      </section>
    </div>
  </Teleport>
</template>
