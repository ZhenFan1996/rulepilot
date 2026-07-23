<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import PublicLessonCover from '@/components/PublicLessonCover.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import {
  deduplicatePublicLessons,
  groupPlansForReading,
  playerFacingTitle,
} from '@/lib/lessonPresentation'

interface TeachingPlan {
  id: string
  gameTitle: string
  premise: string
  playerCount: number
  durationMinutes: number
  createdAt: string
}

interface HotGame {
  rank: number
  bggId: number
  name: string
  publicationYear: number | null
  thumbnailUrl: string
  bggUrl: string
}

interface PublicLessonPreview {
  teachingPlanId: string
  rulebookTitle: string
  gameCover: { gameName: string; imageUrl: string } | null
  sectionCount: number
  stepCount: number
}

const username = ref('')
const plans = ref<TeachingPlan[]>([])
const latestPlan = computed(() => plans.value[0] ?? null)
const latestPlanTitle = computed(() => latestPlan.value ? playerFacingTitle(latestPlan.value.gameTitle) : '')
const recentPlans = computed(() => groupPlansForReading(plans.value).slice(0, 3))
const hotGames = ref<HotGame[]>([])
const hotGamesLoading = ref(true)
const hotGamesUnavailable = ref(false)
const showingPersonalShelf = ref(false)
const publicLessons = ref<PublicLessonPreview[]>([])
const featuredPublicLessons = computed(() => deduplicatePublicLessons(publicLessons.value).slice(0, 3))

function createdLabel(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric' }).format(date)
}

function hideBrokenImage(event: Event) {
  const image = event.currentTarget as HTMLImageElement
  image.hidden = true
}

async function loadPersonalHome() {
  try {
    const sessionResponse = await fetch('/api/auth/session', { credentials: 'include' })
    if (!sessionResponse.ok) return
    username.value = ((await sessionResponse.json()) as { username: string }).username
    const plansResponse = await fetch('/api/v1/teaching-plans', { credentials: 'include' })
    if (plansResponse.ok) plans.value = await plansResponse.json() as TeachingPlan[]
  } catch {
    username.value = ''
  }
}

async function loadHotGames() {
  try {
    const response = await fetch('/api/v1/bgg/hot', { credentials: 'include' })
    if (response.ok) {
      hotGames.value = await response.json() as HotGame[]
    } else {
      const catalogResponse = await fetch('/api/v1/games', { credentials: 'include' })
      if (catalogResponse.ok) {
        const catalog = await catalogResponse.json() as Array<{
          game: { name: string }
          bggMetadata: null | { bggId: number; thumbnailUrl: string; bggUrl: string }
        }>
        hotGames.value = catalog
          .filter(entry => Boolean(entry.bggMetadata?.thumbnailUrl))
          .map((entry, index) => ({
            rank: index + 1,
            bggId: entry.bggMetadata!.bggId,
            name: entry.game.name,
            publicationYear: null,
            thumbnailUrl: entry.bggMetadata!.thumbnailUrl,
            bggUrl: entry.bggMetadata!.bggUrl,
          }))
        showingPersonalShelf.value = hotGames.value.length > 0
      }
    }
    hotGamesUnavailable.value = hotGames.value.length === 0
  } catch {
    hotGamesUnavailable.value = true
  } finally {
    hotGamesLoading.value = false
  }
}

async function loadPublicLessons() {
  try {
    const response = await fetch('/api/public/lessons?limit=12')
    if (response.ok) publicLessons.value = await response.json() as PublicLessonPreview[]
  } catch {
    publicLessons.value = []
  }
}

onMounted(() => Promise.all([loadPersonalHome(), loadHotGames(), loadPublicLessons()]))
</script>

<template>
  <AppShell>
    <div class="mx-auto max-w-7xl px-5 py-6 sm:px-8 sm:py-10 lg:px-12 lg:py-12">
      <section class="home-table relative overflow-hidden rounded-[2rem] bg-ink px-6 py-8 text-canvas shadow-xl shadow-ink/10 sm:px-10 sm:py-10 lg:px-14">
        <div class="pointer-events-none absolute -right-6 -top-8 text-canvas/[0.07] sm:right-12"><TabletopGlyph name="meeple" :size="180" /></div>
        <div class="relative max-w-3xl">
          <p class="text-sm font-semibold tracking-wide text-copper">{{ username ? `${username} 的规则桌` : '今晚玩什么？' }}</p>
          <h1 v-if="latestPlan" class="mt-3 font-display text-4xl font-semibold leading-tight tracking-[-0.03em] sm:text-5xl">继续这一局</h1>
          <h1 v-else class="mt-3 font-display text-4xl font-semibold leading-tight tracking-[-0.03em] sm:text-5xl">把规则讲清楚，<br>再开桌。</h1>
          <p class="mt-4 max-w-xl text-base leading-8 text-canvas/70">{{ latestPlan ? '从你上次读到的地方接着讲；卡住时，随时打开原规则书核对。' : '选一个 PDF，就能先看到设置、第一轮、结束和计分。' }}</p>

          <div v-if="latestPlan" class="mt-7 flex flex-col gap-4 rounded-2xl border border-canvas/15 bg-canvas/[0.06] p-5 sm:flex-row sm:items-center sm:justify-between">
            <div class="min-w-0">
              <p class="text-xs font-bold uppercase tracking-[0.14em] text-copper">上次打开</p>
              <h2 class="mt-2 truncate font-display text-2xl font-semibold">{{ latestPlanTitle }}</h2>
              <p class="mt-1 text-sm text-canvas/60">{{ latestPlan.playerCount }} 人 · 约 {{ latestPlan.durationMinutes }} 分钟讲解</p>
            </div>
            <RouterLink :to="{ name: 'lesson', params: { planId: latestPlan.id } }" class="inline-flex min-h-12 shrink-0 items-center justify-center gap-2 rounded-xl bg-copper px-5 font-semibold text-white hover:bg-copper-dark">继续阅读 <TabletopGlyph name="arrow" :size="18" /></RouterLink>
          </div>

          <div v-else class="mt-7 flex flex-wrap gap-3">
            <RouterLink :to="{ name: 'teach' }" class="inline-flex min-h-12 items-center gap-2 rounded-xl bg-copper px-5 font-semibold text-white hover:bg-copper-dark"><TabletopGlyph name="plus" :size="18" /> 上传规则书</RouterLink>
            <RouterLink :to="{ name: 'public-library' }" class="inline-flex min-h-12 items-center gap-2 rounded-xl border border-canvas/25 px-5 font-semibold text-canvas hover:bg-canvas/10"><TabletopGlyph name="library" :size="18" /> 读公开讲解</RouterLink>
          </div>

          <div v-if="latestPlan" class="mt-4 flex flex-wrap gap-3">
            <RouterLink :to="{ name: 'teach' }" class="inline-flex min-h-11 items-center gap-2 rounded-xl border border-canvas/25 px-4 text-sm font-semibold text-canvas hover:bg-canvas/10"><TabletopGlyph name="plus" :size="17" /> 上传新规则书</RouterLink>
            <RouterLink :to="{ name: 'public-library' }" class="inline-flex min-h-11 items-center gap-2 rounded-xl border border-canvas/25 px-4 text-sm font-semibold text-canvas hover:bg-canvas/10"><TabletopGlyph name="library" :size="17" /> 读公开讲解</RouterLink>
          </div>
          <p class="mt-5 text-sm text-canvas/50">{{ latestPlan ? '讲解与原规则书都保留在这里。' : '不用先创建游戏；标题也可以稍后再改。' }}</p>
        </div>
      </section>

      <section v-if="featuredPublicLessons.length" class="border-b border-ink/10 py-12">
        <div class="flex items-end justify-between gap-4">
          <div>
            <p class="text-sm font-medium text-copper">先看看别人怎么开桌</p>
            <h2 class="mt-2 font-display text-3xl font-semibold">公开讲解</h2>
          </div>
          <RouterLink :to="{ name: 'public-library' }" class="shrink-0 text-sm font-semibold text-indigo">全部讲解 →</RouterLink>
        </div>
        <div class="-mx-5 mt-6 flex gap-4 overflow-x-auto px-5 pb-3 sm:mx-0 sm:grid sm:grid-cols-3 sm:overflow-visible sm:px-0">
          <RouterLink v-for="(entry, index) in featuredPublicLessons" :key="entry.lesson.teachingPlanId" :to="{ name: 'public-lesson', params: { planId: entry.lesson.teachingPlanId } }" class="group w-52 shrink-0 sm:w-auto">
            <div class="relative aspect-[16/10] overflow-hidden rounded-xl border border-ink/10 bg-paper shadow-sm transition group-hover:-translate-y-1 group-hover:shadow-lg">
              <PublicLessonCover :title="entry.title" :image-url="entry.lesson.gameCover?.imageUrl" :alt="`${entry.title} 的游戏封面`" :index="index" />
            </div>
            <h3 class="mt-3 line-clamp-2 font-semibold leading-5">{{ entry.title }}</h3>
            <p class="mt-1 text-xs text-ink/45">{{ entry.lesson.sectionCount }} 章 · {{ entry.lesson.stepCount }} 步</p>
          </RouterLink>
        </div>
      </section>

      <section class="border-b border-ink/10 py-12">
        <div class="flex items-end justify-between gap-4">
          <div>
            <p class="text-sm font-medium text-copper">{{ showingPersonalShelf ? '你的收藏' : '还没决定玩什么？' }}</p>
            <h2 class="mt-2 font-display text-3xl font-semibold">{{ showingPersonalShelf ? '你的游戏架' : '看看热门桌游' }}</h2>
          </div>
          <a v-if="!showingPersonalShelf" href="https://boardgamegeek.com/hotness" target="_blank" rel="noreferrer" class="shrink-0 text-xs font-semibold text-ink/45 hover:text-indigo">Powered by BGG ↗</a>
        </div>

        <div v-if="hotGamesLoading" class="mt-6 grid grid-cols-2 gap-4 sm:grid-cols-4 lg:grid-cols-6" aria-label="正在读取热门桌游">
          <div v-for="index in 6" :key="index" class="animate-pulse">
            <div class="aspect-[4/5] rounded-xl bg-ink/8" />
            <div class="mt-3 h-4 w-3/4 rounded bg-ink/8" />
          </div>
        </div>
        <div v-else-if="hotGames.length" class="-mx-5 mt-6 flex snap-x gap-4 overflow-x-auto px-5 pb-3 sm:mx-0 sm:grid sm:grid-cols-4 sm:overflow-visible sm:px-0 lg:grid-cols-6">
          <a v-for="game in hotGames.slice(0, 6)" :key="game.bggId" :href="game.bggUrl" target="_blank" rel="noreferrer" class="group w-36 shrink-0 snap-start sm:w-auto">
            <div class="relative aspect-[4/5] overflow-hidden rounded-xl border border-ink/10 bg-paper p-2 shadow-sm transition group-hover:-translate-y-1 group-hover:shadow-lg">
              <img :src="game.thumbnailUrl" :alt="`${game.name} 封面`" loading="lazy" class="h-full w-full rounded-lg object-contain" @error="hideBrokenImage">
              <span v-if="!showingPersonalShelf" class="absolute left-2 top-2 grid h-7 min-w-7 place-items-center rounded-full bg-ink px-2 text-xs font-bold text-canvas">{{ game.rank }}</span>
            </div>
            <h3 class="mt-3 line-clamp-2 text-sm font-semibold leading-5">{{ game.name }}</h3>
            <p class="mt-1 text-xs text-ink/40">{{ game.publicationYear ?? '年份未知' }}</p>
          </a>
        </div>
        <div v-else-if="hotGamesUnavailable" class="mt-6 flex flex-col gap-3 rounded-xl border border-dashed border-ink/15 bg-paper px-5 py-6 sm:flex-row sm:items-center sm:justify-between">
          <p class="text-sm leading-6 text-ink/55">暂时没有热门桌游资料。你仍然可以直接上传规则书，或从 BGG 搜索游戏。</p>
          <RouterLink :to="{ name: 'catalog' }" class="shrink-0 text-sm font-semibold text-indigo">搜索桌游 →</RouterLink>
        </div>
      </section>

      <section v-if="recentPlans.length" class="border-b border-ink/10 py-10">
        <div class="flex items-center justify-between gap-4">
          <h2 class="font-display text-2xl font-semibold">最近准备的讲解</h2>
          <RouterLink :to="{ name: 'lessons' }" class="text-sm font-semibold text-indigo">查看全部</RouterLink>
        </div>
        <ol class="mt-5 divide-y divide-ink/10 border-y border-ink/10">
          <li v-for="entry in recentPlans" :key="entry.plan.id">
            <RouterLink :to="{ name: 'lesson', params: { planId: entry.plan.id } }" class="group grid gap-1 py-4 sm:grid-cols-[1fr_auto] sm:items-center">
              <span class="font-semibold">{{ entry.title }}<span v-if="entry.count > 1" class="ml-2 text-xs font-medium text-ink/45">· {{ entry.count }} 份讲解</span></span>
              <span class="text-sm text-ink/45">{{ entry.plan.playerCount }} 人 · {{ entry.plan.durationMinutes }} 分钟 · {{ createdLabel(entry.plan.createdAt) }} →</span>
            </RouterLink>
          </li>
        </ol>
      </section>

      <footer class="flex flex-col gap-2 border-t border-ink/10 py-7 text-xs leading-5 text-ink/40 sm:flex-row sm:items-center sm:justify-between">
        <p>讲解中的规则结论可以回到原始页码核对。</p>
        <RouterLink :to="{ name: 'catalog' }" class="hover:text-ink">整理我的游戏</RouterLink>
      </footer>
    </div>
  </AppShell>
</template>

<style scoped>
.home-table {
  background-image: radial-gradient(rgba(245, 240, 232, 0.08) 0.7px, transparent 0.7px);
  background-size: 13px 13px;
}
</style>
