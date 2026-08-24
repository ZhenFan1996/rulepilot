<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'

import LessonChapterList from '@/components/LessonChapterList.vue'
import { useModalFocus } from '@/composables/useModalFocus'
import {
  acceptProgressiveLesson,
  teachingLessonNeedsFinalSnapshot,
  teachingRunIsActive,
} from '@/lib/liveLesson'
import { useLocale } from '@/lib/locale'
import { mergeTeachingRunProgress, teachingActivityText, type TeachingRunProgress } from '@/lib/teachingProgress'

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
const loading = ref(false)
const error = ref(false)
const refreshWarning = ref(false)
const dialog = ref<HTMLElement | null>(null)
let requestSequence = 0
let timer: ReturnType<typeof setTimeout> | null = null
let disposed = false
let activeController: AbortController | null = null
let missingRunRefreshes = 0

useModalFocus({
  dialog,
  open: () => props.open,
  requestClose: () => emit('close'),
  restoreFocus: props.restoreFocus,
})

const copy = computed(() => locale.value === 'zh-CN' ? {
  dialog: '生成讲解阅读器', close: '关闭讲解', eyebrow: '规则书讲解', loading: '正在打开已生成的讲解…', error: '讲解暂时无法打开。', retry: '重试',
  draft: '已有 {done} / {total} 章完成引用归属、规则书版本与结构校验；其余章节仍在后台生成。', complete: '完整讲解已经生成。', incomplete: '当前讲解只发布了具备可用规则依据的章节。',
  syncing: '已有 {done} / {total} 章完成引用归属、规则书版本与结构校验；正在确认后台任务状态。', settledDraft: '已有 {done} / {total} 章完成引用归属、规则书版本与结构校验；未发布章节仍缺少可用规则依据。',
  refresh: '暂时无法刷新最新章节，已显示的内容仍可继续阅读。', ask: '切换到规则答疑', source: '每个步骤都保留原规则书页码；答疑只使用同一份规则书。',
} : {
  dialog: 'Generated guide reader', close: 'Close guide', eyebrow: 'Rulebook guide', loading: 'Opening generated guide content…', error: 'The guide cannot be opened right now.', retry: 'Retry',
  draft: '{done} / {total} chapters passed citation-ownership, rulebook-version, and structure checks; the remaining chapters are still being generated.', complete: 'The complete guide is ready.', incomplete: 'This guide publishes only chapters with usable rulebook support.',
  syncing: '{done} / {total} chapters passed citation-ownership, rulebook-version, and structure checks while the background task status is confirmed.', settledDraft: '{done} / {total} chapters passed citation-ownership, rulebook-version, and structure checks; unpublished chapters still lack usable rulebook support.',
  refresh: 'The latest chapter update is unavailable. Confirmed content remains readable.', ask: 'Switch to rules Q&A', source: 'Every step retains original rulebook page references; Q&A uses the same rulebook.',
})

const active = computed(() => teachingRunIsActive(run.value?.run.state))
const supportedChapterCount = computed(() => lesson.value?.sections
  .filter(section => section.evidenceStatus === 'SUPPORTED').length ?? 0)
const statusText = computed(() => {
  if (!plan.value || !lesson.value) return ''
  if (lesson.value.status === 'COMPLETE' && run.value?.run.state === 'COMPLETED') return copy.value.complete
  if (lesson.value.status === 'INCOMPLETE') return copy.value.incomplete
  const template = !run.value ? copy.value.syncing : active.value ? copy.value.draft : copy.value.settledDraft
  return template
    .replace('{done}', String(supportedChapterCount.value))
    .replace('{total}', String(plan.value.sections.length))
})
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
  if (response.status === 404) return null
  if (!response.ok) throw new Error('request failed')
  return await response.json() as T
}

async function load() {
  if (!props.open || !props.planId) return
  const planId = props.planId
  const request = ++requestSequence
  if (plan.value?.id !== planId) {
    plan.value = null
    lesson.value = null
    run.value = null
  }
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
      throw new Error('guide response identity mismatch')
    }
    plan.value = incomingPlan
    lesson.value = acceptProgressiveLesson(lesson.value?.id === incomingLesson.id ? lesson.value : null, incomingLesson)
    run.value = incomingRun
      ? mergeTeachingRunProgress(run.value?.run.id === incomingRun.run.id ? run.value : null, incomingRun)
      : null
    loaded = true
  } catch (caught) {
    if (!isAbortError(caught) && isCurrentRequest(request, planId, controller)) {
      if (readableSnapshot) refreshWarning.value = true
      else error.value = true
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
  try {
    const [incomingLesson, incomingRun] = await Promise.all([
      optionalJson<IllustratedLesson>(`/api/v1/teaching-plans/${encodeURIComponent(planId)}/illustrated-lessons/latest`, controller.signal),
      optionalJson<TeachingRunProgress>(`/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(planId)}`, controller.signal),
    ])
    if (!isCurrentRequest(request, planId, controller)) return
    if (!responseMatchesPlan(planId, null, incomingLesson, incomingRun)) throw new Error('guide response identity mismatch')
    if (incomingLesson) lesson.value = acceptProgressiveLesson(lesson.value, incomingLesson)
    run.value = mergeTeachingRunProgress(run.value, incomingRun)
    refreshWarning.value = false
  } catch (caught) {
    if (!isAbortError(caught) && isCurrentRequest(request, planId, controller)) {
      refreshWarning.value = true
      controller.abort()
    }
  } finally {
    if (isCurrentRequest(request, planId, controller)) {
      activeController = null
      scheduleRefresh(request, planId, refreshWarning.value ? 4_000 : 1_500)
    }
  }
}

function scheduleRefresh(request: number, planId: string, delay = 1_500) {
  clearTimer()
  const finalSnapshotPending = teachingLessonNeedsFinalSnapshot(
    run.value?.run.state,
    lesson.value?.status,
  )
  const retryMissingRun = !run.value && missingRunRefreshes < 2
  if (isCurrentGeneration(request, planId)
    && document.visibilityState !== 'hidden'
    && (retryMissingRun || active.value || finalSnapshotPending)) {
    if (retryMissingRun) missingRunRefreshes += 1
    timer = setTimeout(() => {
      timer = null
      void refresh(request, planId)
    }, delay)
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
            <div class="flex items-center justify-between gap-3 text-xs"><p class="font-semibold" :class="active || !run ? 'text-indigo' : 'text-emerald-700'" role="status">{{ statusText }}</p><span class="font-mono font-semibold text-ink/50">{{ supportedChapterCount }} / {{ plan.sections.length }}</span></div>
            <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-indigo/10"><div data-testid="recommendation-lesson-progress" class="h-full rounded-full bg-indigo transition-[width] duration-500" :style="{ width: `${progress}%` }" /></div>
            <p v-if="activityText" class="mt-2 text-xs text-ink/50">{{ activityText }}</p>
            <p v-if="refreshWarning" class="mt-2 text-xs font-semibold text-amber-800" role="status">{{ copy.refresh }}</p>
          </div>
        </header>

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
