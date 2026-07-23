<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import PublicLessonCover from '@/components/PublicLessonCover.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import { deduplicatePublicLessons } from '@/lib/lessonPresentation'

interface PublicLessonEntry {
  teachingPlanId: string
  rulebookTitle: string
  officialSourceUrl: string
  gameCover: { gameName: string; imageUrl: string; attributionUrl: string; attributionLabel: string } | null
  sectionCount: number
  stepCount: number
}

const lessons = ref<PublicLessonEntry[]>([])
const loading = ref(true)
const errorMessage = ref('')
const search = ref('')
const presentedLessons = computed(() => deduplicatePublicLessons(lessons.value))
const visibleLessons = computed(() => {
  const keyword = search.value.trim().toLocaleLowerCase()
  if (!keyword) return presentedLessons.value
  return presentedLessons.value.filter((entry) => entry.title.toLocaleLowerCase().includes(keyword))
})

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await fetch('/api/public/lessons?limit=30')
    if (!response.ok) throw new Error('公开讲解暂时无法读取。')
    lessons.value = await response.json() as PublicLessonEntry[]
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '公开讲解暂时无法读取。'
  } finally {
    loading.value = false
  }
}

onMounted(() => { void load() })
</script>

<template>
  <main class="min-h-screen bg-canvas text-ink">
    <header class="border-b border-ink/10 bg-paper/95">
      <div class="mx-auto flex max-w-6xl items-center justify-between gap-4 px-5 py-4 sm:px-8">
        <RouterLink :to="{ name: 'home' }" class="font-display text-xl font-semibold tracking-tight">RulePilot</RouterLink>
        <div class="flex items-center gap-4">
          <RouterLink :to="{ name: 'teach' }" class="text-sm font-semibold text-indigo">上传规则书</RouterLink>
          <span class="hidden text-sm text-ink/50 sm:inline">公开讲解库</span>
        </div>
      </div>
    </header>

    <section class="mx-auto max-w-6xl px-5 py-12 sm:px-8 lg:py-16">
      <div class="flex flex-col gap-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p class="text-sm font-semibold text-copper">挑一本，今晚开桌</p>
          <h1 class="mt-2 max-w-3xl font-display text-4xl font-semibold tracking-tight sm:text-5xl">从规则书到第一局</h1>
          <p class="mt-4 max-w-2xl leading-7 text-ink/60">每份讲解保留来源页，原规则书始终回到出版方。先按步骤开局；卡住时再核对原文。</p>
        </div>
        <label class="relative block w-full sm:w-72">
          <span class="sr-only">搜索公开讲解</span>
          <TabletopGlyph name="compass" :size="18" class="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-ink/45" />
          <input v-model="search" type="search" placeholder="搜索游戏" class="min-h-11 w-full rounded-xl border border-ink/12 bg-paper py-3 pl-11 pr-4 text-sm outline-none transition placeholder:text-ink/40 focus:border-indigo focus:ring-2 focus:ring-indigo/15">
        </label>
      </div>
      <p v-if="!loading && !errorMessage && presentedLessons.length" class="mt-6 text-sm text-ink/45">{{ presentedLessons.length }} 款游戏可直接阅读</p>

      <div v-if="loading" class="mt-10 grid grid-cols-2 gap-5 sm:grid-cols-3 lg:grid-cols-5" aria-label="正在读取公开讲解">
        <div v-for="index in 10" :key="index" class="animate-pulse">
          <div class="aspect-[3/4] rounded-xl bg-ink/8" />
          <div class="mt-3 h-4 w-3/4 rounded bg-ink/8" />
        </div>
      </div>

      <div v-else-if="errorMessage" class="mt-10 rounded-xl border border-red-200 bg-red-50 p-6 text-red-800">
        <p>{{ errorMessage }}</p>
        <button type="button" class="mt-4 min-h-11 rounded-lg border border-red-300 px-4 font-semibold" @click="load">重新尝试</button>
      </div>

      <div v-else-if="visibleLessons.length" class="mt-8 grid grid-cols-2 gap-5 sm:grid-cols-3 lg:grid-cols-5">
        <RouterLink v-for="(entry, index) in visibleLessons" :key="entry.lesson.teachingPlanId" :to="{ name: 'public-lesson', params: { planId: entry.lesson.teachingPlanId } }" class="group min-w-0">
          <div class="relative aspect-[3/4] overflow-hidden rounded-xl border border-ink/10 bg-paper shadow-sm transition duration-200 group-hover:-translate-y-1 group-hover:shadow-lg">
            <PublicLessonCover :title="entry.title" :image-url="entry.lesson.gameCover?.imageUrl" :alt="`${entry.title} 的游戏封面`" :index="index" />
          </div>
          <h2 class="mt-3 line-clamp-2 font-semibold leading-5">{{ entry.title }}</h2>
          <p class="mt-1 text-xs text-ink/45">{{ entry.lesson.sectionCount }} 章 · {{ entry.lesson.stepCount }} 步</p>
        </RouterLink>
      </div>

      <div v-else-if="lessons.length" class="mt-10 rounded-xl border border-dashed border-ink/20 bg-paper p-8 text-center">
        <TabletopGlyph name="compass" :size="30" class="mx-auto text-indigo" />
        <h2 class="mt-4 font-display text-2xl font-semibold">没有找到这款游戏</h2>
        <p class="mt-3 text-ink/60">试试英文原名，或清空搜索看看全部讲解。</p>
        <button type="button" class="mt-4 min-h-11 text-sm font-semibold text-indigo" @click="search = ''">清空搜索</button>
      </div>

      <div v-else class="mt-10 rounded-xl border border-dashed border-ink/15 bg-paper p-8 text-center">
        <h2 class="font-display text-2xl font-semibold">讲解库正在整理</h2>
        <p class="mt-3 text-ink/60">首批经过来源与可用性核验的桌游讲解会陆续出现在这里。</p>
      </div>
    </section>
  </main>
</template>
