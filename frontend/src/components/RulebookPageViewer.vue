<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue'

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
  eyebrow?: string
  hint?: string
  dialogMode?: boolean
}>(), {
  eyebrow: '',
  hint: '',
  dialogMode: false,
})

const { locale } = useLocale()
const viewerTop = ref<HTMLElement | null>(null)
const zoomed = ref(false)
const pageList = ref<HTMLOListElement | null>(null)
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
  failed: (value: number, preserved: number | null) => `第 ${value} 页在页面图像加载阶段失败；浏览器没有得到可显示的图片。${preserved === null ? '规则书文字索引和其他页面不受影响。' : `当前仍保留第 ${preserved} 页，不会用失败结果替换。`}`,
  next: (value: number) => `下一步：只重试第 ${value} 页；若再次失败，可在新标签页打开这一页核对。`,
  retry: (value: number) => `重试第 ${value} 页`,
  openOriginal: (value: number) => `在新标签页打开第 ${value} 页`,
  openingState: '正在打开',
  displayedState: '已显示',
  failedState: '加载失败',
  pagesLabel: '规则书页面', zoom: '放大阅读', fit: '适合页面',
} : {
  page: (value: number) => `Page ${value}`,
  image: (value: number) => `Rulebook page ${value}`,
  loading: (value: number) => `Loading page ${value}…`,
  loadingWithPreserved: (value: number, preserved: number) => `Loading page ${value}; page ${preserved} remains displayed.`,
  displayed: (value: number) => `Page ${value} displayed`,
  failed: (value: number, preserved: number | null) => `Page ${value} failed while loading its page image; the browser did not receive a displayable image. ${preserved === null ? 'The indexed rulebook text and other pages are unaffected.' : `Page ${preserved} remains displayed and is not replaced by this failure.`}`,
  next: (value: number) => `Next: retry only page ${value}. If it fails again, open this page in a new tab to inspect it.`,
  retry: (value: number) => `Retry page ${value}`,
  openOriginal: (value: number) => `Open page ${value} in a new tab`,
  openingState: 'Opening',
  displayedState: 'Displayed',
  failedState: 'Failed to load',
  pagesLabel: 'Rulebook pages', zoom: 'Enlarge page', fit: 'Fit page',
})

const pageSignature = computed(() => props.pages.map(page => page.pageNumber).join(','))
const imageLoading = computed(() => requestedImage.value !== null
  && displayedImage.value?.token !== requestedImage.value.token
  && failedRequestToken.value !== requestedImage.value.token)
const imageFailed = computed(() => requestedImage.value !== null
  && failedRequestToken.value === requestedImage.value.token)

function pageImageUrl(versionId: string, pageNumber: number) {
  return `/api/v1/document-versions/${encodeURIComponent(versionId)}/pages/${pageNumber}/image`
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

function requestPage(pageNumber: number, scroll = true) {
  if (disposed || !props.versionId || !hasPage(pageNumber)) return
  const current = requestedImage.value
  if (current?.versionId === props.versionId && current.pageNumber === pageNumber
    && (imageLoading.value || displayedImage.value?.token === current.token)) {
    if (scroll) scrollToViewer()
    return
  }
  const token = ++requestSequence
  requestedImage.value = {
    token,
    versionId: props.versionId,
    pageNumber,
    url: pageImageUrl(props.versionId, pageNumber),
  }
  failedRequestToken.value = null
  if (scroll) scrollToViewer()
}

function retryPage() {
  const pageNumber = requestedImage.value?.pageNumber
  if (pageNumber !== undefined) requestPage(pageNumber, false)
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
}

function failRequestedImage(event: Event) {
  const current = requestFromImageEvent(event)
  if (!current) return
  failedRequestToken.value = current.token
}

function pageButtonClass(pageNumber: number) {
  if (requestedImage.value?.pageNumber !== pageNumber) return 'border-ink/10 hover:border-indigo/30'
  if (imageFailed.value) return 'border-red-200 ring-2 ring-copper/20'
  return 'border-copper ring-2 ring-copper/20'
}

function pageState(pageNumber: number) {
  if (imageLoading.value && requestedImage.value?.pageNumber === pageNumber) return copy.value.openingState
  if (imageFailed.value && requestedImage.value?.pageNumber === pageNumber) return copy.value.failedState
  if (displayedImage.value?.pageNumber === pageNumber) return copy.value.displayedState
  return ''
}

function scrollToViewer() {
  void nextTick(() => {
    viewerTop.value?.scrollIntoView?.({
      behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
      block: 'start',
    })
  })
}

function focusPageButton(pageNumber: number) {
  void nextTick(() => {
    pageList.value?.querySelector<HTMLButtonElement>(`button[data-page-number="${pageNumber}"]`)?.focus()
  })
}

function navigatePages(event: KeyboardEvent, currentPage: number) {
  const currentIndex = props.pages.findIndex(page => page.pageNumber === currentPage)
  if (currentIndex < 0) return
  let targetIndex: number | null = null
  if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
    targetIndex = Math.min(currentIndex + 1, props.pages.length - 1)
  } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
    targetIndex = Math.max(currentIndex - 1, 0)
  } else if (event.key === 'Home') {
    targetIndex = 0
  } else if (event.key === 'End') {
    targetIndex = props.pages.length - 1
  }
  if (targetIndex === null || targetIndex === currentIndex) return
  const target = props.pages[targetIndex]
  if (!target) return
  event.preventDefault()
  requestPage(target.pageNumber)
  focusPageButton(target.pageNumber)
}

watch([() => props.versionId, pageSignature], () => {
  invalidateImageRequest()
  const firstPage = props.pages[0]
  if (firstPage && props.versionId) requestPage(firstPage.pageNumber, false)
}, { immediate: true })

onBeforeUnmount(() => {
  disposed = true
  invalidateImageRequest()
})
</script>

<template>
  <div ref="viewerTop" class="grid scroll-mt-20 gap-3">
    <nav class="min-w-0">
      <p v-if="eyebrow" class="tabletop-kicker px-1">{{ eyebrow }}</p>
      <ol
        ref="pageList"
        class="flex gap-2 overflow-x-auto p-1"
        :class="eyebrow ? 'mt-3' : ''"
        :aria-label="copy.pagesLabel"
      >
        <li v-for="page in pages" :key="page.pageNumber" class="shrink-0">
          <button
            type="button"
            class="min-h-11 rounded-lg border bg-paper px-3 py-2 text-left text-xs transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo focus-visible:ring-offset-2"
            :class="pageButtonClass(page.pageNumber)"
            :data-page-number="page.pageNumber"
            :aria-current="displayedImage?.pageNumber === page.pageNumber ? 'page' : undefined"
            :aria-busy="imageLoading && requestedImage?.pageNumber === page.pageNumber ? 'true' : undefined"
            @click="requestPage(page.pageNumber)"
            @keydown="navigatePages($event, page.pageNumber)"
          >
            <span class="block font-bold">{{ copy.page(page.pageNumber) }}</span>
            <span v-if="pageState(page.pageNumber)" class="sr-only">{{ pageState(page.pageNumber) }}</span>
          </button>
        </li>
      </ol>
    </nav>

    <section class="min-w-0">
      <div class="mb-2 flex justify-end">
        <button type="button" :disabled="!displayedImage" :aria-pressed="zoomed" class="min-h-11 rounded-lg border border-ink/15 bg-paper px-4 text-sm font-semibold text-indigo disabled:opacity-40" @click="zoomed = !zoomed">{{ zoomed ? copy.fit : copy.zoom }}</button>
      </div>
      <div
        data-testid="rulebook-page-stage"
        class="relative mx-auto w-full max-w-6xl rounded-lg bg-ink/5 p-1 shadow-2xl shadow-ink/20 sm:p-2"
        :class="[imageLoading || imageFailed ? 'min-h-72' : '', zoomed ? 'max-h-[calc(100dvh-12rem)] overflow-auto' : 'grid place-items-center overflow-hidden']"
        :aria-busy="imageLoading ? 'true' : 'false'"
      >
        <img
          v-if="displayedImage"
          data-testid="rulebook-page-image"
          :data-request-token="displayedImage.token"
          :data-version-id="displayedImage.versionId"
          :data-page-number="displayedImage.pageNumber"
          :src="displayedImage.url"
          :alt="copy.image(displayedImage.pageNumber)"
          class="h-auto w-auto object-contain"
          :class="zoomed ? 'max-w-none' : ['mx-auto max-w-full', dialogMode ? 'max-h-[calc(100vh-9rem)]' : 'max-h-[calc(100vh-8rem)]']"
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
          class="rounded-xl bg-canvas/95 px-5 py-4 text-center text-sm font-semibold text-ink/60"
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
        class="mx-auto mt-3 max-w-5xl text-center text-xs text-ink/50"
        role="status"
      >
        {{ copy.displayed(displayedImage.pageNumber) }}<template v-if="hint"> · {{ hint }}</template>
      </p>
    </section>
  </div>
</template>
