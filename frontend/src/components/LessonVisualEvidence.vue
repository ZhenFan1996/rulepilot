<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import type { VisualFocus } from '@/composables/lessonSupportingContent'
import { useLocale } from '@/lib/locale'

const props = defineProps<{
  focus: VisualFocus
  narrationId?: string
  ordinal?: number
  total?: number
  compact?: boolean
  pageImageUrl: (page: number) => string
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

const detailFailure = ref<DetailFailure | null>(null)
const detailRetryReason = ref<RetryableDetailFailure | null>(null)
const detailLoading = ref(false)
const loadedDetailImageUrl = ref('')
const detailViewport = ref<HTMLElement | null>(null)
const originalPageUrl = computed(() => props.pageImageUrl(props.focus.pageNumber))
const detailIsReliable = computed(() => isReliableDetailViewport(props.focus))
const detailImageUrl = computed(() => detailIsReliable.value ? props.focusedPageImageUrl(props.focus) : '')
const visualOrdinal = computed(() => Math.max(1, props.ordinal ?? 1))
const visualTotal = computed(() => Math.max(visualOrdinal.value, props.total ?? 1))
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
    data-testid="lesson-visual-evidence"
    :aria-describedby="narrationId || undefined"
    class="overflow-hidden rounded-2xl border border-indigo/15 bg-paper elevation-sm"
  >
    <div v-if="detailIsReliable" ref="detailViewport" class="bg-canvas">
      <a
        v-if="loadedDetailImageUrl && !detailFailure"
        data-testid="lesson-visual-image"
        :href="originalPageUrl"
        target="_blank"
        rel="noopener noreferrer"
        class="block focus:outline-none focus:ring-4 focus:ring-inset focus:ring-indigo/15"
      >
        <img
          :src="loadedDetailImageUrl"
          :alt="t('lesson.visualEvidence.alt', { page: focus.pageNumber, label: focus.label })"
          class="block w-full object-contain"
          :class="compact ? 'aspect-[4/3] max-h-64 min-h-36' : 'max-h-[32rem] min-h-40'"
          loading="lazy"
          decoding="async"
          @error="handleDetailDecodeFailure"
        >
      </a>
      <div
        v-else-if="detailLoading && detailRetryReason"
        data-testid="lesson-visual-image-retrying"
        role="status"
        class="px-4 py-10 text-center"
        :class="compact ? 'min-h-36' : 'min-h-48'"
      >
        <p class="text-xs leading-5 text-ink/65">{{ detailRetryMessage }}</p>
      </div>
      <div
        v-else-if="detailLoading"
        data-testid="lesson-visual-image-loading"
        role="status"
        :aria-label="t('lesson.visualEvidence.loading')"
        class="animate-pulse bg-indigo/[0.035]"
        :class="compact ? 'min-h-36' : 'min-h-48'"
      />
      <div
        v-else
        data-testid="lesson-visual-image-failure"
        class="min-h-40 border-b border-dashed border-indigo/15 px-4 py-10 text-center"
      >
        <p class="text-xs leading-5 text-ink/60">{{ detailFailureMessage }}</p>
        <button
          v-if="detailCanRetry"
          type="button"
          data-testid="lesson-visual-image-retry"
          class="mt-3 min-h-10 rounded-lg border border-indigo/20 bg-paper px-4 text-xs font-semibold text-indigo"
          @click="retryDetailImage"
        >
          {{ t('lesson.visualStoryboard.detail.failure.retryAction') }}
        </button>
      </div>
    </div>

    <div v-else data-testid="lesson-visual-image-unreliable" class="border-b border-dashed border-copper/25 bg-canvas px-4 py-8 text-center">
      <p class="text-sm font-semibold text-ink">{{ t('lesson.visualEvidence.protectedTitle') }}</p>
      <p class="mt-2 text-xs leading-5 text-ink/60">{{ t('lesson.visualEvidence.protectedBody') }}</p>
    </div>

    <figcaption class="border-t border-indigo/10 px-4 py-4 sm:px-5">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <p class="text-[11px] font-bold uppercase tracking-[0.12em] text-indigo">
          {{ t('lesson.visualEvidence.figure', { current: visualOrdinal, total: visualTotal }) }}
        </p>
        <span class="text-xs font-semibold text-ink/45">{{ t('lesson.chapter.page', { page: focus.pageNumber }) }}</span>
      </div>
      <p class="mt-1 font-display text-lg font-semibold text-ink">{{ focus.label }}</p>
      <p class="mt-2 text-sm leading-6 text-ink/70">
        <span class="font-semibold text-copper">{{ t('lesson.visualEvidence.lookFor') }}</span>
        {{ focus.visibleDescription || focus.label }}
      </p>
      <div class="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs leading-5 text-ink/50">
        <span>{{ t('lesson.visualEvidence.boundary') }}</span>
        <a :href="originalPageUrl" target="_blank" rel="noopener noreferrer" class="font-semibold text-indigo hover:underline">{{ t('lesson.visualEvidence.openOriginal') }} ↗</a>
      </div>
    </figcaption>
  </figure>
</template>
