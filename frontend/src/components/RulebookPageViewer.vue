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
const pageList = ref<HTMLOListElement | null>(null)
const requestedImage = shallowRef<PageImageRequest | null>(null)
const displayedImage = shallowRef<PageImageRequest | null>(null)
const failedRequestToken = ref<number | null>(null)
let requestSequence = 0
let disposed = false

const copy = computed(() => locale.value === 'zh-CN' ? {
  page: (value: number) => `第 ${value} 页`,
  image: (value: number) => `规则书第 ${value} 页`,
  extracted: (value: number) => `已识别 ${value} 个字符`,
  loading: (value: number) => `正在加载第 ${value} 页…`,
  displayed: (value: number) => `第 ${value} 页已显示`,
  failed: (value: number) => `第 ${value} 页暂时无法显示。`,
  retry: '重试这一页',
  openOriginal: '在新标签页打开原页',
  openingState: '正在打开',
  displayedState: '已显示',
  failedState: '加载失败',
  pagesLabel: '规则书页面',
} : {
  page: (value: number) => `Page ${value}`,
  image: (value: number) => `Rulebook page ${value}`,
  extracted: (value: number) => `${value} characters indexed`,
  loading: (value: number) => `Loading page ${value}…`,
  displayed: (value: number) => `Page ${value} displayed`,
  failed: (value: number) => `Page ${value} cannot be displayed right now.`,
  retry: 'Retry this page',
  openOriginal: 'Open original page in a new tab',
  openingState: 'Opening',
  displayedState: 'Displayed',
  failedState: 'Failed to load',
  pagesLabel: 'Rulebook pages',
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
  displayedImage.value = null
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
  displayedImage.value = null
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
  <div ref="viewerTop" class="grid scroll-mt-20 gap-4 lg:grid-cols-[12rem_minmax(0,1fr)]">
    <aside class="order-2 lg:order-1">
      <p v-if="eyebrow" class="tabletop-kicker px-1">{{ eyebrow }}</p>
      <ol
        ref="pageList"
        class="grid grid-cols-4 gap-2 lg:grid-cols-2"
        :class="[eyebrow ? 'mt-3' : '', dialogMode ? 'sm:grid-cols-8' : 'sm:grid-cols-6']"
        :aria-label="copy.pagesLabel"
      >
        <li v-for="page in pages" :key="page.pageNumber">
          <button
            type="button"
            class="w-full rounded-lg border bg-paper px-2 py-3 text-left text-xs transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo focus-visible:ring-offset-2"
            :class="pageButtonClass(page.pageNumber)"
            :data-page-number="page.pageNumber"
            :aria-current="displayedImage?.pageNumber === page.pageNumber ? 'page' : undefined"
            :aria-busy="imageLoading && requestedImage?.pageNumber === page.pageNumber ? 'true' : undefined"
            @click="requestPage(page.pageNumber)"
            @keydown="navigatePages($event, page.pageNumber)"
          >
            <span class="block font-bold">{{ copy.page(page.pageNumber) }}</span>
            <span class="mt-1 block text-[0.65rem] text-ink/40">{{ copy.extracted(page.characterCount) }}</span>
            <span v-if="pageState(page.pageNumber)" class="sr-only">{{ pageState(page.pageNumber) }}</span>
          </button>
        </li>
      </ol>
    </aside>

    <section class="order-1 min-w-0 lg:order-2">
      <div
        data-testid="rulebook-page-stage"
        class="relative mx-auto grid w-full max-w-6xl place-items-center overflow-hidden rounded-lg bg-ink/5 p-1 shadow-2xl shadow-ink/20 sm:p-2"
        :class="imageLoading || imageFailed ? 'min-h-72' : ''"
        :aria-busy="imageLoading ? 'true' : 'false'"
      >
        <img
          v-if="requestedImage && !imageFailed"
          :key="requestedImage.token"
          :data-testid="imageLoading ? 'rulebook-page-loader' : 'rulebook-page-image'"
          :data-request-token="requestedImage.token"
          :data-version-id="requestedImage.versionId"
          :data-page-number="requestedImage.pageNumber"
          :src="requestedImage.url"
          :alt="imageLoading ? '' : copy.image(displayedImage?.pageNumber ?? requestedImage.pageNumber)"
          :aria-hidden="imageLoading ? 'true' : undefined"
          class="mx-auto h-auto w-auto max-w-full object-contain"
          :class="[
            dialogMode ? 'max-h-[calc(100vh-9rem)]' : 'max-h-[calc(100vh-8rem)]',
            imageLoading ? 'invisible absolute inset-0' : '',
          ]"
          @load="commitDisplayedImage"
          @error="failRequestedImage"
        >

        <p
          v-if="imageLoading && requestedImage"
          data-testid="rulebook-page-status"
          class="rounded-xl bg-canvas px-5 py-4 text-center text-sm font-semibold text-ink/60"
          role="status"
        >
          {{ copy.loading(requestedImage.pageNumber) }}
        </p>

        <div
          v-else-if="imageFailed && requestedImage"
          data-testid="rulebook-page-status"
          class="max-w-lg rounded-xl border border-red-200 bg-red-50 px-5 py-5 text-center text-red-800"
          role="alert"
        >
          <p class="font-semibold">{{ copy.failed(requestedImage.pageNumber) }}</p>
          <div class="mt-4 flex flex-col justify-center gap-2 sm:flex-row">
            <button type="button" class="min-h-11 rounded-lg bg-indigo px-5 text-sm font-semibold text-white" @click="retryPage">
              {{ copy.retry }}
            </button>
            <a :href="requestedImage.url" target="_blank" rel="noopener noreferrer" class="inline-flex min-h-11 items-center justify-center rounded-lg border border-red-300 bg-white px-5 text-sm font-semibold text-red-800">
              {{ copy.openOriginal }}
            </a>
          </div>
        </div>
      </div>

      <p
        v-if="displayedImage"
        data-testid="rulebook-page-status"
        class="mx-auto mt-3 max-w-5xl text-center text-xs text-ink/50"
        role="status"
      >
        {{ copy.displayed(displayedImage.pageNumber) }}<template v-if="hint"> · {{ hint }}</template>
      </p>
    </section>
  </div>
</template>
