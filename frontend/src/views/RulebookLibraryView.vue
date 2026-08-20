<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import { notifyLoginRequired } from '@/lib/authSession'
import { useLocale } from '@/lib/locale'

interface RulebookDocument {
  document: {
    id: string
    title: string
    sourceType: string
    officialCoverUrl: string
    createdAt: string
  }
  latestVersion: {
    id: string
    versionNumber: number
    originalFilename: string
    size: number
    status: string
    createdAt: string
  }
}

const { locale } = useLocale()
const documents = ref<RulebookDocument[]>([])
const loading = ref(true)
const error = ref('')
const controller = new AbortController()

const readyCount = computed(() => documents.value.filter(item => item.latestVersion.status === 'READY').length)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const response = await fetch('/api/v1/documents', { credentials: 'include', signal: controller.signal })
    if (response.status === 401) {
      notifyLoginRequired()
      throw new Error(locale.value === 'zh-CN' ? '登录后才能查看你的规则书。' : 'Sign in to see your rulebooks.')
    }
    if (!response.ok) throw new Error(locale.value === 'zh-CN' ? '规则书暂时没有加载出来。' : 'Your rulebooks could not be loaded.')
    documents.value = await response.json() as RulebookDocument[]
  } catch (failure) {
    if (failure instanceof DOMException && failure.name === 'AbortError') return
    error.value = failure instanceof Error ? failure.message : String(failure)
  } finally {
    loading.value = false
  }
}

function sizeLabel(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0) return ''
  return bytes >= 1_048_576 ? `${(bytes / 1_048_576).toFixed(1)} MB` : `${Math.ceil(bytes / 1_024)} KB`
}

onMounted(load)
onBeforeUnmount(() => controller.abort())
</script>

<template>
  <AppShell>
    <section class="tabletop-page max-w-6xl">
      <header class="relative overflow-hidden rounded-[2rem] border border-copper/20 bg-ink px-6 py-9 text-canvas shadow-xl sm:px-9">
        <div class="absolute -right-16 -top-24 size-72 rounded-full border border-copper/20" aria-hidden="true" />
        <p class="relative text-xs font-bold uppercase tracking-[0.18em] text-copper">Personal rulebook shelf</p>
        <div class="relative mt-3 flex flex-wrap items-end justify-between gap-6">
          <div>
            <h1 class="font-display text-4xl font-semibold tracking-tight sm:text-5xl">{{ locale === 'zh-CN' ? '我的规则书' : 'My rulebooks' }}</h1>
            <p class="mt-3 max-w-2xl text-sm leading-6 text-canvas/65">{{ locale === 'zh-CN' ? '从这里直接回到原文、查看处理状态，或把一册规则书交给讲解。关掉任何小窗，都不会丢失这里的记录。' : 'Return to the source, check processing status, or start a guide. Closing a dialog never removes a rulebook from this shelf.' }}</p>
          </div>
          <RouterLink :to="{ name: 'teach' }" class="inline-flex min-h-12 items-center rounded-xl bg-copper px-5 font-semibold text-white shadow-lg hover:bg-copper/90">＋ {{ locale === 'zh-CN' ? '添加规则书' : 'Add rulebook' }}</RouterLink>
        </div>
        <p v-if="documents.length" class="relative mt-7 text-sm text-canvas/55">{{ locale === 'zh-CN' ? `${documents.length} 册 · ${readyCount} 册可阅读` : `${documents.length} rulebooks · ${readyCount} ready to read` }}</p>
      </header>

      <div v-if="loading" class="mt-7 grid gap-4 sm:grid-cols-2 lg:grid-cols-3" role="status" :aria-label="locale === 'zh-CN' ? '正在加载规则书' : 'Loading rulebooks'">
        <div v-for="index in 6" :key="index" class="h-72 animate-pulse rounded-2xl border border-ink/8 bg-paper" />
      </div>
      <div v-else-if="error" class="mt-7 rounded-2xl border border-red-200 bg-red-50 p-6 text-red-800" role="alert"><p>{{ error }}</p><button type="button" class="mt-4 min-h-11 font-semibold underline" @click="load">{{ locale === 'zh-CN' ? '重新加载' : 'Try again' }}</button></div>
      <section v-else-if="documents.length" class="mt-7 grid gap-4 sm:grid-cols-2 lg:grid-cols-3" :aria-label="locale === 'zh-CN' ? '规则书列表' : 'Rulebook list'">
        <article v-for="item in documents" :key="item.document.id" class="group flex min-h-72 flex-col overflow-hidden rounded-2xl border border-ink/10 bg-paper shadow-sm transition hover:-translate-y-0.5 hover:border-copper/35 hover:shadow-lg">
          <div class="relative h-28 overflow-hidden bg-gradient-to-br from-ink to-[#44504a] p-5 text-canvas">
            <img v-if="item.document.officialCoverUrl" :src="item.document.officialCoverUrl" alt="" class="absolute inset-0 size-full object-cover opacity-30 blur-[1px]" referrerpolicy="no-referrer">
            <span class="relative rounded-full border border-white/15 bg-black/15 px-2.5 py-1 text-[0.65rem] font-bold uppercase tracking-[0.12em]">{{ item.document.sourceType.replaceAll('_', ' ') }}</span>
          </div>
          <div class="flex flex-1 flex-col p-5">
            <h2 class="line-clamp-2 font-display text-2xl font-semibold leading-tight">{{ item.document.title }}</h2>
            <p class="mt-2 text-xs text-ink/45">{{ item.latestVersion.originalFilename }}<span v-if="sizeLabel(item.latestVersion.size)"> · {{ sizeLabel(item.latestVersion.size) }}</span></p>
            <div class="mt-auto pt-6">
              <RouterLink v-if="item.latestVersion.status === 'READY'" :to="{ name: 'rulebook-reader', params: { versionId: item.latestVersion.id } }" class="inline-flex min-h-11 w-full items-center justify-center rounded-xl bg-indigo px-4 font-semibold text-white">{{ locale === 'zh-CN' ? '打开阅读' : 'Open reader' }} →</RouterLink>
              <div v-else class="rounded-xl bg-canvas px-4 py-3 text-sm"><p class="font-semibold text-ink/70">{{ locale === 'zh-CN' ? '规则书仍在处理中' : 'Rulebook is still processing' }}</p><p class="mt-1 text-xs text-ink/45">{{ item.latestVersion.status }}</p></div>
            </div>
          </div>
        </article>
      </section>
      <section v-else class="mt-7 rounded-[1.75rem] border border-dashed border-copper/35 bg-paper p-10 text-center sm:p-16">
        <p class="font-display text-3xl font-semibold">{{ locale === 'zh-CN' ? '书架还是空的' : 'Your shelf is empty' }}</p>
        <p class="mx-auto mt-3 max-w-md text-sm leading-6 text-ink/50">{{ locale === 'zh-CN' ? '添加 PDF、图片规则书，或从桌游资料页导入官方规则书。处理完成后会一直保存在这里。' : 'Add a PDF, photographed rulebook, or import an official source from a game page. It remains here when processing finishes.' }}</p>
        <RouterLink :to="{ name: 'teach' }" class="mt-6 inline-flex min-h-12 items-center rounded-xl bg-copper px-5 font-semibold text-white">{{ locale === 'zh-CN' ? '添加第一册规则书' : 'Add your first rulebook' }}</RouterLink>
      </section>
    </section>
  </AppShell>
</template>
