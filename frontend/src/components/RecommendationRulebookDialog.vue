<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'

import { useModalFocus } from '@/composables/useModalFocus'
import { useLocale } from '@/lib/locale'

interface RulebookPage {
  pageNumber: number
  text: string
  characterCount: number
}

const props = defineProps<{
  open: boolean
  versionId: string
  title: string
  restoreFocus?: () => HTMLElement | null
}>()
const emit = defineEmits<{ close: [] }>()
const { locale } = useLocale()
const pages = ref<RulebookPage[]>([])
const selectedPage = ref(1)
const loading = ref(false)
const error = ref(false)
const readerTop = ref<HTMLElement | null>(null)
const dialog = ref<HTMLElement | null>(null)
let requestSequence = 0
let disposed = false
let activeController: AbortController | null = null

useModalFocus({
  dialog,
  open: () => props.open,
  requestClose: () => emit('close'),
  restoreFocus: props.restoreFocus,
})

const copy = computed(() => locale.value === 'zh-CN' ? {
  dialog: '原规则书阅读器', eyebrow: '已下载的原规则书', close: '关闭规则书', loading: '正在打开规则书页面…', error: '规则书已经下载，但页面暂时无法打开。', retry: '重试',
  waiting: '你可以先阅读原规则书；讲解仍在后台生成，关闭这里不会中断。', page: (value: number) => `第 ${value} 页`, pages: (value: number) => `共 ${value} 页`, image: (value: number) => `规则书第 ${value} 页`, extracted: (value: number) => `已识别 ${value} 个字符`,
} : {
  dialog: 'Original rulebook reader', eyebrow: 'Downloaded original rulebook', close: 'Close rulebook', loading: 'Opening rulebook pages…', error: 'The rulebook is downloaded, but its pages cannot be opened right now.', retry: 'Retry',
  waiting: 'Read the original rulebook now while the guide continues in the background. Closing this does not interrupt it.', page: (value: number) => `Page ${value}`, pages: (value: number) => `${value} pages`, image: (value: number) => `Rulebook page ${value}`, extracted: (value: number) => `${value} characters indexed`,
})

function isAbortError(error: unknown) {
  return (error as { name?: unknown } | null)?.name === 'AbortError'
}

function isCurrentRequest(request: number, versionId: string, controller: AbortController) {
  return !disposed
    && props.open
    && request === requestSequence
    && activeController === controller
    && props.versionId === versionId
}

function cancelRequest() {
  requestSequence += 1
  activeController?.abort()
  activeController = null
  loading.value = false
}

async function load() {
  if (!props.open || !props.versionId) return
  const versionId = props.versionId
  const request = ++requestSequence
  activeController?.abort()
  const controller = new AbortController()
  activeController = controller
  loading.value = true
  error.value = false
  pages.value = []
  selectedPage.value = 1
  try {
    const response = await fetch(`/api/v1/document-versions/${encodeURIComponent(versionId)}/pages`, {
      credentials: 'include',
      signal: controller.signal,
    })
    if (!response.ok) throw new Error('pages unavailable')
    const incoming = await response.json() as RulebookPage[]
    if (!isCurrentRequest(request, versionId, controller)) return
    if (!incoming.length) throw new Error('pages unavailable')
    pages.value = incoming
    selectedPage.value = Math.min(Math.max(selectedPage.value, 1), incoming.length)
  } catch (caught) {
    if (!isAbortError(caught) && isCurrentRequest(request, versionId, controller)) error.value = true
  } finally {
    if (isCurrentRequest(request, versionId, controller)) {
      loading.value = false
      activeController = null
    }
  }
}

async function selectPage(pageNumber: number) {
  selectedPage.value = pageNumber
  await nextTick()
  readerTop.value?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
}

watch(() => [props.open, props.versionId] as const, ([open]) => {
  if (open) void load()
  else cancelRequest()
}, { immediate: true })
onBeforeUnmount(() => {
  disposed = true
  cancelRequest()
})
</script>

<template>
  <div v-if="open" class="fixed inset-0 z-50 overflow-y-auto bg-ink/45 backdrop-blur-[2px]" @click.self="emit('close')">
    <section ref="dialog" tabindex="-1" class="mx-auto min-h-screen w-full max-w-[100rem] bg-[#ddd8cf] text-ink outline-none sm:my-5 sm:min-h-0 sm:overflow-hidden sm:rounded-3xl sm:border sm:border-gold/25 sm:shadow-2xl" role="dialog" aria-modal="true" :aria-label="copy.dialog">
      <header class="app-sticky-top sticky z-20 flex items-start justify-between gap-4 border-b border-ink/10 bg-paper/95 px-4 py-4 backdrop-blur sm:px-6">
        <div class="min-w-0"><p class="tabletop-kicker">{{ copy.eyebrow }}</p><div class="mt-1 flex min-w-0 items-baseline gap-3"><h2 class="truncate font-display text-xl font-semibold sm:text-2xl">{{ title }}</h2><span v-if="pages.length" class="shrink-0 text-xs text-ink/45">{{ copy.pages(pages.length) }}</span></div><p class="mt-1 text-xs leading-5 text-ink/50">{{ copy.waiting }}</p></div>
        <button type="button" data-modal-initial-focus class="grid min-h-11 min-w-11 shrink-0 place-items-center rounded-lg text-2xl text-ink/45 hover:bg-ink/5" :aria-label="copy.close" @click="emit('close')">×</button>
      </header>

      <div ref="readerTop" class="grid scroll-mt-20 gap-4 px-3 py-4 sm:px-6 lg:grid-cols-[12rem_minmax(0,1fr)]">
        <p v-if="loading" class="col-span-full rounded-xl bg-paper p-10 text-center text-sm text-ink/55" role="status">{{ copy.loading }}</p>
        <section v-else-if="error" class="col-span-full rounded-xl border border-red-200 bg-paper p-10 text-center" role="alert"><p>{{ copy.error }}</p><button type="button" class="mt-4 min-h-11 rounded-lg bg-indigo px-5 font-semibold text-white" @click="load">{{ copy.retry }}</button></section>
        <template v-else>
          <aside class="order-2 lg:order-1">
            <ol class="grid grid-cols-4 gap-2 sm:grid-cols-8 lg:grid-cols-2">
              <li v-for="page in pages" :key="page.pageNumber"><button type="button" class="w-full rounded-lg border bg-paper px-2 py-3 text-left text-xs transition" :class="selectedPage === page.pageNumber ? 'border-copper ring-2 ring-copper/20' : 'border-ink/10 hover:border-indigo/30'" @click="selectPage(page.pageNumber)"><span class="block font-bold">{{ copy.page(page.pageNumber) }}</span><span class="mt-1 block text-[0.65rem] text-ink/40">{{ copy.extracted(page.characterCount) }}</span></button></li>
            </ol>
          </aside>
          <section class="order-1 min-w-0 lg:order-2" aria-live="polite">
            <div class="mx-auto max-w-5xl rounded-lg bg-white p-2 shadow-2xl shadow-ink/20 sm:p-4"><img :src="`/api/v1/document-versions/${encodeURIComponent(versionId)}/pages/${selectedPage}/image`" :alt="copy.image(selectedPage)" class="mx-auto h-auto max-h-[calc(100vh-10rem)] w-auto max-w-full object-contain"></div>
            <p class="mt-3 text-center text-xs text-ink/50">{{ copy.page(selectedPage) }}</p>
          </section>
        </template>
      </div>
    </section>
  </div>
</template>
