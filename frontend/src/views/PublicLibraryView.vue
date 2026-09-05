<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import PublicLessonCover from '@/components/PublicLessonCover.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import { useLocale } from '@/lib/locale'
import { deduplicatePublicLessons } from '@/lib/lessonPresentation'
import { publicCoverUrl } from '@/lib/publicCover'

interface PublicLessonEntry {
  teachingPlanId: string
  rulebookTitle: string
  officialSourceUrl: string
  gameCover: { gameName: string; imageUrl: string; attributionUrl: string; attributionLabel: string } | null
  publicGame: { bggId: number; name: string; bggUrl: string } | null
  sectionCount: number
  stepCount: number
}

const lessons = ref<PublicLessonEntry[]>([])
const { locale, t } = useLocale()
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
    if (!response.ok) throw new Error(t('library.error'))
    lessons.value = await response.json() as PublicLessonEntry[]
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('library.error')
  } finally {
    loading.value = false
  }
}

onMounted(() => { void load() })
</script>

<template>
  <AppShell>
    <section class="tabletop-page max-w-6xl">
      <div class="flex flex-col gap-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 class="tabletop-title">{{ t('library.heading') }}</h1>
          <p class="mt-4 max-w-2xl leading-7 text-ink/60">{{ t('library.description') }}</p>
        </div>
        <label class="relative block w-full sm:w-72">
          <span class="sr-only">{{ t('library.search') }}</span>
          <TabletopGlyph name="compass" :size="18" class="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-ink/45" />
          <input v-model="search" type="search" :placeholder="t('library.search')" class="min-h-11 w-full rounded-xl border border-ink/12 bg-paper py-3 pl-11 pr-4 text-sm outline-none transition placeholder:text-ink/40 focus:border-indigo focus:ring-2 focus:ring-indigo/15">
        </label>
      </div>
      <p v-if="!loading && !errorMessage && presentedLessons.length" class="mt-6 text-sm text-ink/45">{{ t('library.count', { count: presentedLessons.length }) }}</p>

      <div v-if="loading" class="mt-10 grid grid-cols-2 gap-5 sm:grid-cols-3 lg:grid-cols-5" :aria-label="t('library.loading')">
        <div v-for="index in 10" :key="index" class="animate-pulse">
          <div class="aspect-[3/4] rounded-xl bg-ink/8" />
          <div class="mt-3 h-4 w-3/4 rounded bg-ink/8" />
        </div>
      </div>

      <div v-else-if="errorMessage" class="mt-10 rounded-xl border border-red-200 bg-red-50 p-6 text-red-800">
        <p>{{ errorMessage }}</p>
        <button type="button" class="mt-4 min-h-11 rounded-lg border border-red-300 px-4 font-semibold" @click="load">{{ t('library.retry') }}</button>
      </div>

      <div v-else-if="visibleLessons.length" class="mt-8 grid grid-cols-2 gap-5 sm:grid-cols-3 lg:grid-cols-5">
        <article v-for="(entry, index) in visibleLessons" :key="entry.lesson.teachingPlanId" class="group min-w-0">
          <RouterLink :to="{ name: 'public-lesson', params: { planId: entry.lesson.teachingPlanId } }" class="block">
            <div class="relative aspect-[3/4] overflow-hidden rounded-xl border border-ink/10 bg-paper elevation-sm transition duration-200 group-hover:-translate-y-1 group-hover-elevation-lg">
              <PublicLessonCover :title="entry.title" :image-url="publicCoverUrl(entry.lesson.teachingPlanId)" :alt="t('library.cover', { title: entry.title })" :index="index" />
            </div>
            <h2 class="mt-3 line-clamp-2 font-semibold leading-5">{{ entry.title }}</h2>
          </RouterLink>
          <p class="mt-1 text-xs text-ink/45">{{ t('library.size', { sections: entry.lesson.sectionCount, steps: entry.lesson.stepCount }) }}</p>
          <div v-if="entry.lesson.publicGame" class="mt-2 flex flex-wrap items-center gap-2">
            <RouterLink :to="{ name: 'game-discovery', params: { bggId: entry.lesson.publicGame.bggId } }" class="text-xs font-semibold text-indigo">{{ locale === 'zh-CN' ? '桌游资料' : 'Game details' }}</RouterLink>
            <a :href="entry.lesson.publicGame.bggUrl" target="_blank" rel="noopener noreferrer" class="inline-flex min-h-8 items-center" :aria-label="`${entry.lesson.publicGame.name} · BoardGameGeek`"><img src="/powered-by-bgg-rgb.svg" alt="Powered by BoardGameGeek" class="h-auto w-24" width="342" height="76"></a>
          </div>
        </article>
      </div>

      <div v-else-if="lessons.length" class="mt-10 rounded-xl border border-dashed border-ink/20 bg-paper p-8 text-center">
        <TabletopGlyph name="compass" :size="30" class="mx-auto text-indigo" />
        <h2 class="mt-4 font-display text-2xl font-semibold">{{ t('library.emptySearch.title') }}</h2>
        <p class="mt-3 text-ink/60">{{ t('library.emptySearch.detail') }}</p>
        <button type="button" class="mt-4 min-h-11 text-sm font-semibold text-indigo" @click="search = ''">{{ t('library.emptySearch.clear') }}</button>
      </div>

      <div v-else class="mt-10 rounded-xl border border-dashed border-ink/15 bg-paper p-8 text-center">
        <h2 class="font-display text-2xl font-semibold">{{ t('library.empty.title') }}</h2>
        <p class="mt-3 text-ink/60">{{ t('library.empty.detail') }}</p>
      </div>
    </section>
  </AppShell>
</template>
