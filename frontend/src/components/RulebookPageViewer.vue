<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'

import { useLocale } from '@/lib/locale'

interface RulebookPageSummary {
  pageNumber: number
  characterCount: number
}

interface PageImageRequest {
  token: number
  versionId: string
  pageNumber: number
  url: string
}

const props = withDefaults(defineProps<{
  versionId: string
  pages: RulebookPageSummary[]
  hint?: string
  dialogMode?: boolean
  initialPage?: number
  pageImageUrl?: (page: number) => string
}>(), {
  hint: '',
  dialogMode: false,
})

const { locale } = useLocale()
const pageStage = ref<HTMLElement | null>(null)
const readingMode = ref(window.matchMedia?.('(min-width: 768px)').matches ? 'fit' : 'width')
const requestedImage = shallowRef<PageImageRequest | null>(null)
const displayedImage = shallowRef<PageImageRequest | null>(null)
const failedRequestToken = ref<number | null>(null)
let requestSequence = 0
let disposed = false

const copy = computed(() => locale.value === 'zh-CN' ? {
  page: (value: number) => `第 ${value} 页`,
  image: (value: number) => `规则书第 ${value} 页`,
  loading: (value: number) => `正在加载第 ${value} 页…`,
  loadingWithPreserved: (value: number, preserved: number) => `正在加载第 ${value} 页；第 ${preserved} 页继续显示。`,
  displayed: (value: number) => `第 ${value} 页已显示`,
  failed: (value: number, preserved: number | null) => `第 ${value} 页暂时无法加载。${preserved === null ? '你可以重试或查看其他页。' : `仍显示第 ${preserved} 页。`}`,
  next: (value: number) => `下一步：只重试第 ${value} 页；若再次失败，可在新标签页打开这一页核对。`,
  retry: (value: number) => `重试第 ${value} 页`,
  openOriginal: (value: number) => `在新标签页打开第 ${value} 页`,
  openingState: '正在打开',
  displayedState: '已显示',
  failedState: '加载失败',
  pagesLabel: '规则书页面', previous: '上一页', following: '下一页', mode: '阅读尺寸',
  width: '适合宽度', fit: '整页预览', actual: '原始尺寸',
} : {
  page: (value: number) => `Page ${value}`,
  image: (value: number) => `Rulebook page ${value}`,
  loading: (value: number) => `Loading page ${value}…`,
  loadingWithPreserved: (value: number, preserved: number) => `Loading page ${value}; page ${preserved} remains displayed.`,
  displayed: (value: number) => `Page ${value} displayed`,
  failed: (value: number, preserved: number | null) => `Page ${value} could not be loaded. ${preserved === null ? 'Retry or choose another page.' : `Page ${preserved} remains displayed.`}`,
  next: (value: number) => `Next: retry only page ${value}. If it fails again, open this page in a new tab to inspect it.`,
  retry: (value: number) => `Retry page ${value}`,
  openOriginal: (value: number) => `Open page ${value} in a new tab`,
  openingState: 'Opening',
  displayedState: 'Displayed',
  failedState: 'Failed to load',
  pagesLabel: 'Rulebook pages', previous: 'Previous page', following: 'Next page', mode: 'Reading size',
  width: 'Fit width', fit: 'Whole page', actual: 'Original size',
})

const pageSignature = computed(() => props.pages.map(page => page.pageNumber).join(','))
const imageLoading = computed(() => requestedImage.value !== null
  && displayedImage.value?.token !== requestedImage.value.token
  && failedRequestToken.value !== requestedImage.value.token)
const imageFailed = computed(() => requestedImage.value !== null
  && failedRequestToken.value === requestedImage.value.token)

function resolvePageImageUrl(versionId: string, pageNumber: number) {
  return props.pageImageUrl?.(pageNumber) ?? `/api/v1/document-versions/${encodeURIComponent(versionId)}/pages/${pageNumber}/image`
}

function hasPage(pageNumber: number) {
  return props.pages.some(page => page.pageNumber === pageNumber)
}

function invalidateImageRequest() {
  requestSequence += 1
  requestedImage.value = null
  displayedImage.value = null
  failedRequestToken.value = null
}

function requestPage(pageNumber: number) {
  if (disposed || !props.versionId || !hasPage(pageNumber)) return
  const current = requestedImage.value
  if (current?.versionId === props.versionId && current.pageNumber === pageNumber
    && (imageLoading.value || displayedImage.value?.token === current.token)) {
    return
  }
  const token = ++requestSequence
  requestedImage.value = {
    token,
    versionId: props.versionId,
    pageNumber,
    url: resolvePageImageUrl(props.versionId, pageNumber),
  }
  failedRequestToken.value = null
}

function retryPage() {
  const pageNumber = requestedImage.value?.pageNumber
  if (pageNumber !== undefined) requestPage(pageNumber)
}

function requestFromImageEvent(event: Event) {
  const image = event.currentTarget
  if (!(image instanceof HTMLImageElement)) return null
  const token = Number(image.dataset.requestToken)
  const pageNumber = Number(image.dataset.pageNumber)
  const versionId = image.dataset.versionId ?? ''
  const current = requestedImage.value
  if (disposed || !Number.isSafeInteger(token) || !Number.isSafeInteger(pageNumber)
    || current === null || current.token !== token || current.pageNumber !== pageNumber
    || current.versionId !== versionId || props.versionId !== versionId
    || image.getAttribute('src') !== current.url) return null
  return current
}

function commitDisplayedImage(event: Event) {
  const current = requestFromImageEvent(event)
  if (!current) return
  failedRequestToken.value = null
  displayedImage.value = current
  if (pageStage.value) { pageStage.value.scrollTop = 0; pageStage.value.scrollLeft = 0 }
}

function failRequestedImage(event: Event) {
  const current = requestFromImageEvent(event)
  if (!current) return
  failedRequestToken.value = current.token
}

const currentIndex = computed(() => props.pages.findIndex(page => page.pageNumber === requestedImage.value?.pageNumber))

function movePage(direction: -1 | 1) {
  const page = props.pages[currentIndex.value + direction]
  if (page) requestPage(page.pageNumber)
}

function choosePage(event: Event) {
  if (event.target instanceof HTMLSelectElement) requestPage(Number(event.target.value))
}

watch([() => props.versionId, pageSignature, () => props.initialPage], () => {
  invalidateImageRequest()
  const firstPage = props.pages.find(page => page.pageNumber === props.initialPage) ?? props.pages[0]
  if (firstPage && props.versionId) requestPage(firstPage.pageNumber)
}, { immediate: true })

onBeforeUnmount(() => {
  disposed = true
  invalidateImageRequest()
})
</script>

<template>
  <div class="grid scroll-mt-20 gap-3">
    <nav class="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-ink/10 bg-paper px-3 py-2" :aria-label="copy.pagesLabel">
      <div class="flex items-center gap-1 text-ink">
        <button type="button" :disabled="currentIndex <= 0" :aria-label="copy.previous" class="reader-control" @click="movePage(-1)">{{ copy.previous }}</button>
        <label class="flex items-center gap-2 text-sm font-semibold">
          <span class="sr-only">{{ copy.pagesLabel }}</span>
          <select :value="requestedImage?.pageNumber" class="min-h-11 max-w-40 rounded-lg bg-canvas px-3 text-ink" @change="choosePage">
            <option v-for="page in pages" :key="page.pageNumber" :value="page.pageNumber">{{ copy.page(page.pageNumber) }}</option>
          </select>
        </label>
        <button type="button" :disabled="currentIndex >= pages.length - 1" :aria-label="copy.following" class="reader-control" @click="movePage(1)">{{ copy.following }}</button>
      </div>
      <label class="text-sm">
        <span class="sr-only">{{ copy.mode }}</span>
        <select v-model="readingMode" class="min-h-11 rounded-lg border border-ink/10 bg-paper px-3 text-ink">
          <option value="width">{{ copy.width }}</option>
          <option value="fit">{{ copy.fit }}</option>
          <option value="actual">{{ copy.actual }}</option>
        </select>
      </label>
    </nav>

    <section class="min-w-0">
      <div
        ref="pageStage"
        data-testid="rulebook-page-stage"
        class="relative mx-auto w-full overflow-auto rounded-lg border border-ink/10 bg-ink/5 p-1 sm:p-3"
        :class="[imageLoading || imageFailed ? 'min-h-72' : '', dialogMode ? 'max-h-[calc(100dvh-11rem)]' : 'max-h-[calc(100dvh-15rem)]']"
        tabindex="0"
        :aria-label="copy.pagesLabel"
        :aria-busy="imageLoading ? 'true' : 'false'"
        @keydown.left.self.prevent="movePage(-1)"
        @keydown.right.self.prevent="movePage(1)"
      >
        <img
          v-if="displayedImage"
          data-testid="rulebook-page-image"
          :data-request-token="displayedImage.token"
          :data-version-id="displayedImage.versionId"
          :data-page-number="displayedImage.pageNumber"
          :src="displayedImage.url"
          :alt="copy.image(displayedImage.pageNumber)"
          class="mx-auto block h-auto object-contain"
          :class="readingMode === 'actual' ? 'w-auto max-w-none' : readingMode === 'width' ? 'w-full max-w-full' : 'w-auto max-w-full max-h-[calc(100dvh-16rem)]'"
        >

        <img
          v-if="imageLoading && requestedImage"
          :key="requestedImage.token"
          data-testid="rulebook-page-loader"
          :data-request-token="requestedImage.token"
          :data-version-id="requestedImage.versionId"
          :data-page-number="requestedImage.pageNumber"
          :src="requestedImage.url"
          alt=""
          aria-hidden="true"
          class="invisible absolute inset-0 size-px"
          @load="commitDisplayedImage"
          @error="failRequestedImage"
        >

        <p
          v-if="imageLoading && requestedImage"
          data-testid="rulebook-page-status"
          class="rounded-xl bg-canvas/95 px-5 py-4 text-center text-sm font-semibold text-muted"
          :class="displayedImage ? 'absolute bottom-3 left-3 right-3 shadow-lg' : ''"
          role="status"
        >
          {{ displayedImage
            ? copy.loadingWithPreserved(requestedImage.pageNumber, displayedImage.pageNumber)
            : copy.loading(requestedImage.pageNumber) }}
        </p>

        <div
          v-else-if="imageFailed && requestedImage"
          data-testid="rulebook-page-status"
          class="max-w-lg rounded-xl border border-red-200 bg-red-50/95 px-5 py-5 text-center text-red-800"
          :class="displayedImage ? 'absolute bottom-3 left-3 right-3 mx-auto shadow-lg' : ''"
          role="alert"
        >
          <p class="font-semibold">{{ copy.failed(requestedImage.pageNumber, displayedImage?.pageNumber ?? null) }}</p>
          <p class="mt-2 text-xs leading-5">{{ copy.next(requestedImage.pageNumber) }}</p>
          <div class="mt-4 flex flex-col justify-center gap-2 sm:flex-row">
            <button type="button" class="min-h-11 rounded-lg bg-indigo px-5 text-sm font-semibold text-white" @click="retryPage">
              {{ copy.retry(requestedImage.pageNumber) }}
            </button>
            <a :href="requestedImage.url" target="_blank" rel="noopener noreferrer" class="inline-flex min-h-11 items-center justify-center rounded-lg border border-red-300 bg-white px-5 text-sm font-semibold text-red-800">
              {{ copy.openOriginal(requestedImage.pageNumber) }}
            </a>
          </div>
        </div>
      </div>

      <p
        v-if="displayedImage && !imageLoading && !imageFailed"
        data-testid="rulebook-page-status"
        class="mx-auto mt-3 max-w-5xl text-center text-xs text-muted"
        role="status"
      >
        {{ copy.displayed(displayedImage.pageNumber) }}<template v-if="hint"> · {{ hint }}</template>
      </p>
    </section>
  </div>
</template>

<style scoped>
.reader-control { min-width: 2.75rem; min-height: 2.75rem; border-radius: .5rem; padding: 0 .5rem; font-size: .875rem; }
.reader-control:hover:not(:disabled) { background: var(--color-canvas); }
.reader-control:disabled { opacity: .3; }
</style>
