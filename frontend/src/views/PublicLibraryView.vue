<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

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
      <p class="text-sm font-semibold text-copper">挑一本，今晚开桌</p>
      <h1 class="mt-2 max-w-3xl font-display text-4xl font-semibold tracking-tight sm:text-5xl">从规则书到第一局</h1>
      <p class="mt-4 max-w-2xl leading-7 text-ink/60">每份讲解保留来源页，原规则书始终回到出版方。先按步骤开局；卡住时再核对原文。</p>

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

      <div v-else-if="lessons.length" class="mt-10 grid grid-cols-2 gap-5 sm:grid-cols-3 lg:grid-cols-5">
        <RouterLink v-for="lesson in lessons" :key="lesson.teachingPlanId" :to="{ name: 'public-lesson', params: { planId: lesson.teachingPlanId } }" class="group min-w-0">
          <div class="relative aspect-[3/4] overflow-hidden rounded-xl border border-ink/10 bg-paper shadow-sm transition duration-200 group-hover:-translate-y-1 group-hover:shadow-lg">
            <img v-if="lesson.gameCover" :src="lesson.gameCover.imageUrl" :alt="`${lesson.gameCover.gameName} 的游戏封面`" class="h-full w-full object-cover" loading="lazy" referrerpolicy="no-referrer">
            <div v-else class="flex h-full items-end bg-[linear-gradient(145deg,#282018,#604531)] p-4 text-paper">
              <span class="font-display text-2xl font-semibold leading-tight">{{ lesson.rulebookTitle }}</span>
            </div>
          </div>
          <h2 class="mt-3 line-clamp-2 font-semibold leading-5">{{ lesson.gameCover?.gameName ?? lesson.rulebookTitle }}</h2>
          <p class="mt-1 text-xs text-ink/45">{{ lesson.sectionCount }} 章 · {{ lesson.stepCount }} 步</p>
        </RouterLink>
      </div>

      <div v-else class="mt-10 rounded-xl border border-dashed border-ink/15 bg-paper p-8 text-center">
        <h2 class="font-display text-2xl font-semibold">讲解库正在整理</h2>
        <p class="mt-3 text-ink/60">首批经过来源与可用性核验的桌游讲解会陆续出现在这里。</p>
      </div>
    </section>
  </main>
</template>
