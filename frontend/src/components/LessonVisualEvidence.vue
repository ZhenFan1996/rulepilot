<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import type { VisualFocus } from '@/composables/lessonSupportingContent'
import { useLocale } from '@/lib/locale'

const props = defineProps<{
  focus: VisualFocus
  pageImageUrl: (page: number) => string
  pagePreviewImageUrl: (page: number) => string
  focusedPageImageUrl: (focus: VisualFocus) => string
}>()

const { t } = useLocale()
const DETAIL_RETRY_DEFAULT_MS = 250
const DETAIL_RETRY_MAX_MS = 1_000
const VISUAL_FAILURE_HEADER = 'X-RulePilot-Visual-Failure'
type RetryableDetailFailure = 'DECODE_CAPACITY_EXCEEDED' | 'PAGE_IMAGE_TEMPORARILY_UNAVAILABLE' | 'UNKNOWN'
type DetailFailure =
  | 'RETRY_EXHAUSTED_CAPACITY'
  | 'RETRY_EXHAUSTED_PAGE_IMAGE'
  | 'RETRY_EXHAUSTED_UNKNOWN'
  | 'PAGE_IMAGE_UNAVAILABLE'
  | 'NETWORK'
  | 'BROWSER_DECODE'

class DetailImageLoadError extends Error {
  constructor(readonly failure: DetailFailure) {
    super(failure)
  }
}

const contextFailed = ref(false)
const detailFailure = ref<DetailFailure | null>(null)
const detailRetryReason = ref<RetryableDetailFailure | null>(null)
const detailLoading = ref(false)
const loadedDetailImageUrl = ref('')
const detailViewport = ref<HTMLElement | null>(null)
const originalPageUrl = computed(() => props.pageImageUrl(props.focus.pageNumber))
const contextImageUrl = computed(() => props.pagePreviewImageUrl(props.focus.pageNumber))
const detailIsReliable = computed(() => isReliableDetailViewport(props.focus))
const detailImageUrl = computed(() => detailIsReliable.value ? props.focusedPageImageUrl(props.focus) : '')
const detailRetryMessage = computed(() => {
  if (detailRetryReason.value === 'DECODE_CAPACITY_EXCEEDED') {
    return t('lesson.visualStoryboard.detail.retry.decodeCapacity')
  }
  if (detailRetryReason.value === 'PAGE_IMAGE_TEMPORARILY_UNAVAILABLE') {
    return t('lesson.visualStoryboard.detail.retry.pageImage')
  }
  return t('lesson.visualStoryboard.detail.retry.temporary')
})
const detailFailureMessage = computed(() => {
  if (detailFailure.value === null) return t('lesson.visualStoryboard.detail.unavailable')
  if (detailFailure.value === 'RETRY_EXHAUSTED_CAPACITY') {
    return t('lesson.visualStoryboard.detail.failure.retryExhaustedCapacity')
  }
  if (detailFailure.value === 'RETRY_EXHAUSTED_PAGE_IMAGE') {
    return t('lesson.visualStoryboard.detail.failure.retryExhaustedPageImage')
  }
  if (detailFailure.value === 'RETRY_EXHAUSTED_UNKNOWN') {
    return t('lesson.visualStoryboard.detail.failure.retryExhaustedTemporary')
  }
  if (detailFailure.value === 'PAGE_IMAGE_UNAVAILABLE') {
    return t('lesson.visualStoryboard.detail.failure.permanent')
  }
  if (detailFailure.value === 'BROWSER_DECODE') {
    return t('lesson.visualStoryboard.detail.failure.browserDecode')
  }
  return t('lesson.visualStoryboard.detail.failure.network')
})
const detailCanRetry = computed(() => detailFailure.value === 'NETWORK'
  || detailFailure.value === 'RETRY_EXHAUSTED_CAPACITY'
  || detailFailure.value === 'RETRY_EXHAUSTED_PAGE_IMAGE'
  || detailFailure.value === 'RETRY_EXHAUSTED_UNKNOWN')
let detailRequest = 0
let detailRequestController: AbortController | null = null
let detailObserver: IntersectionObserver | null = null
let detailVisible = false
let pendingDetailImageUrl = ''
const focusStyle = computed(() => {
  const left = boundedPercent(props.focus.x)
  const top = boundedPercent(props.focus.y)
  return {
    left: `${left}%`,
    top: `${top}%`,
    width: `${Math.min(boundedPercent(props.focus.width), 100 - left)}%`,
    height: `${Math.min(boundedPercent(props.focus.height), 100 - top)}%`,
  }
})

watch(contextImageUrl, () => {
  contextFailed.value = false
})

watch(detailImageUrl, queueDetailImage, { immediate: true })

watch(detailViewport, (current, previous) => {
  if (previous) detailObserver?.unobserve(previous)
  if (current) detailObserver?.observe(current)
})

onMounted(() => {
  if (typeof IntersectionObserver === 'undefined') {
    detailVisible = true
    loadPendingDetailImage()
    return
  }
  detailObserver = new IntersectionObserver((entries) => {
    if (!entries.some(entry => entry.isIntersecting)) return
    detailVisible = true
    detailObserver?.disconnect()
    detailObserver = null
    loadPendingDetailImage()
  }, { rootMargin: '320px 0px' })
  if (detailViewport.value) detailObserver.observe(detailViewport.value)
})

onBeforeUnmount(() => {
  detailObserver?.disconnect()
  detailRequestController?.abort()
})

function queueDetailImage(url: string) {
  detailRequestController?.abort()
  detailRequest += 1
  loadedDetailImageUrl.value = ''
  detailFailure.value = null
  detailRetryReason.value = null
  detailLoading.value = Boolean(url)
  pendingDetailImageUrl = url
  if (url && detailVisible) loadPendingDetailImage()
}

function loadPendingDetailImage() {
  const url = pendingDetailImageUrl
  if (!url) return
  pendingDetailImageUrl = ''
  const request = detailRequest

  const controller = new AbortController()
  detailRequestController = controller
  void fetchDetailImage(url, controller.signal, (reason) => {
    if (!controller.signal.aborted && request === detailRequest) detailRetryReason.value = reason
  })
    .then((imageUrl) => {
      if (!imageUrl || controller.signal.aborted || request !== detailRequest) return
      loadedDetailImageUrl.value = imageUrl
      detailRetryReason.value = null
    })
    .catch((error: unknown) => {
      if (controller.signal.aborted || request !== detailRequest) return
      detailRetryReason.value = null
      detailFailure.value = error instanceof DetailImageLoadError ? error.failure : 'NETWORK'
    })
    .finally(() => {
      if (request === detailRequest) detailLoading.value = false
    })
}

function retryDetailImage() {
  if (!detailCanRetry.value || !detailImageUrl.value) return
  queueDetailImage(detailImageUrl.value)
}

async function fetchDetailImage(
  url: string,
  signal: AbortSignal,
  onRetry: (reason: RetryableDetailFailure) => void,
) {
  let response = await requestDetailImage(url, signal)
  if (response.status === 503) {
    onRetry(retryableFailureReason(response))
    await waitForRetry(retryDelay(response), signal)
    if (signal.aborted) return null
    response = await requestDetailImage(url, signal)
  }
  if (response.status === 503) {
    throw new DetailImageLoadError(exhaustedRetryFailure(retryableFailureReason(response)))
  }
  if (response.status === 502 && response.headers.get(VISUAL_FAILURE_HEADER) === 'PAGE_IMAGE_UNAVAILABLE') {
    throw new DetailImageLoadError('PAGE_IMAGE_UNAVAILABLE')
  }
  if (!response.ok) throw new DetailImageLoadError('NETWORK')
  try {
    return await imageDataUrl(await response.blob())
  } catch {
    throw new DetailImageLoadError('BROWSER_DECODE')
  }
}

async function requestDetailImage(url: string, signal: AbortSignal) {
  try {
    return await fetch(url, { credentials: 'include', signal })
  } catch (error) {
    if (signal.aborted) throw error
    throw new DetailImageLoadError('NETWORK')
  }
}

function retryableFailureReason(response: Response): RetryableDetailFailure {
  const reason = response.headers.get(VISUAL_FAILURE_HEADER)
  if (reason === 'DECODE_CAPACITY_EXCEEDED' || reason === 'PAGE_IMAGE_TEMPORARILY_UNAVAILABLE') return reason
  return 'UNKNOWN'
}

function exhaustedRetryFailure(reason: RetryableDetailFailure): DetailFailure {
  if (reason === 'DECODE_CAPACITY_EXCEEDED') return 'RETRY_EXHAUSTED_CAPACITY'
  if (reason === 'PAGE_IMAGE_TEMPORARILY_UNAVAILABLE') return 'RETRY_EXHAUSTED_PAGE_IMAGE'
  return 'RETRY_EXHAUSTED_UNKNOWN'
}

function imageDataUrl(image: Blob) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      if (typeof reader.result === 'string') resolve(reader.result)
      else reject(new Error('lesson visual could not be decoded'))
    }
    reader.onerror = () => reject(new Error('lesson visual could not be read'))
    reader.readAsDataURL(image)
  })
}

function retryDelay(response: Response) {
  const retryAfter = response.headers.get('Retry-After')
  if (retryAfter === null) return DETAIL_RETRY_DEFAULT_MS
  const seconds = Number(retryAfter)
  if (!Number.isFinite(seconds) || seconds < 0) return DETAIL_RETRY_DEFAULT_MS
  return Math.min(seconds * 1_000, DETAIL_RETRY_MAX_MS)
}

function waitForRetry(delay: number, signal: AbortSignal) {
  if (signal.aborted || delay <= 0) return Promise.resolve()
  return new Promise<void>((resolve) => {
    let timeout = 0
    const finish = () => {
      window.clearTimeout(timeout)
      signal.removeEventListener('abort', finish)
      resolve()
    }
    timeout = window.setTimeout(finish, delay)
    signal.addEventListener('abort', finish, { once: true })
  })
}

function handleDetailDecodeFailure() {
  loadedDetailImageUrl.value = ''
  detailFailure.value = 'BROWSER_DECODE'
}

function boundedPercent(value: number) {
  return Math.max(0, Math.min(100, value / 10))
}

function isReliableDetailViewport(focus: VisualFocus) {
  const touchesHorizontalTrim = focus.x <= 20 || focus.x + focus.width >= 980
  const touchesVerticalTrim = focus.y <= 20 || focus.y + focus.height >= 980
  const minorDimension = Math.max(1, Math.min(focus.width, focus.height))
  const aspectRatio = Math.max(focus.width, focus.height) / minorDimension

  // A thin strip clipped into a page corner is often a header, footer, or truncated model rectangle. The full-page
  // locator remains useful, but presenting that strip as a confident close-up would create false precision.
  return !(touchesHorizontalTrim && touchesVerticalTrim && minorDimension <= 140 && aspectRatio >= 4)
}
</script>

<template>
  <figure
    data-testid="lesson-visual-storyboard"
    class="mt-5 overflow-hidden rounded-2xl border border-indigo/15 bg-canvas"
  >
    <figcaption class="border-b border-indigo/10 bg-indigo/[0.045] px-4 py-3">
      <p class="text-xs font-bold uppercase tracking-[0.12em] text-indigo">{{ t('lesson.chapter.visual.observationEyebrow') }}</p>
      <p class="mt-1 text-sm leading-6 text-ink/70">{{ focus.visibleDescription || focus.label }}</p>
    </figcaption>

    <ol class="grid gap-3 p-3 md:p-4 lg:grid-cols-[12rem_minmax(0,1fr)] lg:items-start">
      <li class="min-w-0 rounded-xl border border-ink/10 bg-paper p-2.5">
        <div class="flex items-center justify-between gap-2 px-1 pb-2">
          <div>
            <p class="text-[11px] font-bold uppercase tracking-[0.1em] text-copper">{{ t('lesson.visualStoryboard.context.step') }}</p>
            <p class="mt-0.5 text-sm font-semibold text-ink">{{ t(detailIsReliable ? 'lesson.visualStoryboard.context.title' : 'lesson.visualStoryboard.contextOnly.title') }}</p>
          </div>
          <span class="shrink-0 text-xs font-semibold text-ink/45">{{ t('lesson.chapter.page', { page: focus.pageNumber }) }}</span>
        </div>

        <a
          v-if="contextImageUrl && !contextFailed"
          data-testid="lesson-visual-context"
          :href="originalPageUrl"
          target="_blank"
          rel="noopener noreferrer"
          class="relative block overflow-hidden rounded-lg border border-ink/10 bg-canvas focus:outline-none focus:ring-4 focus:ring-indigo/15"
        >
          <img
            :src="contextImageUrl"
            :alt="t(
              detailIsReliable
                ? 'lesson.visualStoryboard.context.alt'
                : 'lesson.visualStoryboard.contextOnly.alt',
              { page: focus.pageNumber, label: focus.label },
            )"
            class="block h-auto w-full"
            loading="lazy"
            decoding="async"
            @error="contextFailed = true"
          >
          <span
            v-if="detailIsReliable"
            data-testid="lesson-visual-context-focus"
            class="pointer-events-none absolute rounded border-2 border-copper bg-copper/10 ocr-focus-shadow"
            :style="focusStyle"
            aria-hidden="true"
          />
        </a>
        <div v-else class="rounded-lg border border-dashed border-ink/15 bg-canvas px-3 py-4 text-center">
          <p class="text-xs leading-5 text-ink/55">{{ t('lesson.visualStoryboard.context.unavailable') }}</p>
        </div>
      </li>

      <li v-if="detailIsReliable" ref="detailViewport" class="min-w-0 rounded-xl border border-indigo/12 bg-paper p-2.5">
        <div class="px-1 pb-2">
          <p class="text-[11px] font-bold uppercase tracking-[0.1em] text-indigo">{{ t('lesson.visualStoryboard.detail.step') }}</p>
          <p class="mt-0.5 text-sm font-semibold text-ink">{{ t('lesson.visualStoryboard.detail.title') }}</p>
        </div>

        <a
          v-if="loadedDetailImageUrl && !detailFailure"
          data-testid="lesson-visual-detail"
          :href="originalPageUrl"
          target="_blank"
          rel="noopener noreferrer"
          class="block overflow-hidden rounded-lg border border-indigo/10 bg-canvas focus:outline-none focus:ring-4 focus:ring-indigo/15"
        >
          <img
            :src="loadedDetailImageUrl"
            :alt="t('lesson.visualStoryboard.detail.alt', { page: focus.pageNumber, label: focus.label })"
            class="block max-h-[28rem] w-full object-contain"
            loading="lazy"
            decoding="async"
            @error="handleDetailDecodeFailure"
          >
        </a>
        <div
          v-else-if="detailLoading && detailRetryReason"
          data-testid="lesson-visual-detail-retrying"
          role="status"
          class="min-h-28 rounded-lg border border-copper/20 bg-copper/[0.045] px-3 py-6 text-center"
        >
          <p class="text-xs leading-5 text-ink/65">{{ detailRetryMessage }}</p>
        </div>
        <div
          v-else-if="detailLoading"
          data-testid="lesson-visual-detail-loading"
          role="status"
          :aria-label="t('lesson.visualStoryboard.detail.title')"
          class="min-h-28 rounded-lg border border-indigo/10 bg-canvas"
        />
        <div
          v-else
          data-testid="lesson-visual-detail-failure"
          class="rounded-lg border border-dashed border-indigo/15 bg-canvas px-3 py-6 text-center"
        >
          <p class="text-xs leading-5 text-ink/60">{{ detailFailureMessage }}</p>
          <button
            v-if="detailCanRetry"
            type="button"
            data-testid="lesson-visual-detail-retry"
            class="mt-3 min-h-10 rounded-lg border border-indigo/20 bg-paper px-4 text-xs font-semibold text-indigo"
            @click="retryDetailImage"
          >
            {{ t('lesson.visualStoryboard.detail.failure.retryAction') }}
          </button>
        </div>
      </li>

      <li v-else data-testid="lesson-visual-detail-unreliable" class="min-w-0 rounded-xl border border-dashed border-copper/25 bg-paper p-4">
        <p class="text-[11px] font-bold uppercase tracking-[0.1em] text-copper">{{ t('lesson.visualStoryboard.detail.protectedStep') }}</p>
        <p class="mt-2 text-sm font-semibold text-ink">{{ t('lesson.visualStoryboard.detail.protectedTitle') }}</p>
        <p class="mt-2 text-sm leading-6 text-ink/60">{{ t('lesson.visualStoryboard.detail.protectedBody') }}</p>
      </li>
    </ol>

    <div class="border-t border-indigo/10 px-4 py-3 text-xs leading-5 text-ink/55">
      <p>{{ t(detailIsReliable ? 'lesson.visualStoryboard.boundary' : 'lesson.visualStoryboard.boundaryProtected') }}</p>
      <a :href="originalPageUrl" target="_blank" rel="noopener noreferrer" class="mt-2 inline-flex font-semibold text-indigo hover:underline">{{ t('lesson.visualStoryboard.openOriginal') }} ↗</a>
    </div>
  </figure>
</template>
