<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import AppShell from '@/components/AppShell.vue'

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
const recentPlans = computed(() => plans.value.slice(0, 3))
const latestPlan = computed(() => recentPlans.value[0] ?? null)
const hotGames = ref<HotGame[]>([])
const hotGamesLoading = ref(true)
const hotGamesUnavailable = ref(false)
const showingPersonalShelf = ref(false)
const publicLessons = ref<PublicLessonPreview[]>([])

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
    const response = await fetch('/api/public/lessons?limit=3')
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
      <section class="relative isolate min-h-[32rem] overflow-hidden rounded-2xl bg-[#1b130e] text-white shadow-xl shadow-ink/10 sm:min-h-[35rem]">
        <img src="/tabletop-hero.jpg" alt="木桌上的原创策略桌游组件" class="absolute inset-0 h-full w-full object-cover object-[62%_center] sm:object-center">
        <div class="absolute inset-0 bg-gradient-to-r from-black/90 via-black/60 to-black/10" />
        <div class="relative flex min-h-[32rem] max-w-2xl flex-col justify-end px-6 py-9 sm:min-h-[35rem] sm:px-10 sm:py-12 lg:px-14">
          <p class="text-sm font-semibold tracking-wide text-amber-300">{{ username ? `${username} 的规则桌` : '今晚玩什么？' }}</p>
          <h1 v-if="latestPlan" class="mt-4 font-display text-4xl font-semibold leading-tight tracking-[-0.03em] sm:text-6xl">继续玩<br>{{ latestPlan.gameTitle }}</h1>
          <h1 v-else class="mt-4 font-display text-4xl font-semibold leading-tight tracking-[-0.03em] sm:text-6xl">拿起规则书，<br>开始玩。</h1>
          <p class="mt-5 max-w-xl text-base leading-8 text-white/72">{{ latestPlan ? '从上次读到的地方继续；需要时，随时回到原规则书核对。' : '选一个 PDF，就能先看到设置、第一轮、结束和计分。' }}</p>
          <div class="mt-7 flex flex-wrap gap-3">
            <RouterLink v-if="latestPlan" :to="{ name: 'lesson', params: { planId: latestPlan.id } }" class="inline-flex min-h-12 items-center rounded-lg bg-copper px-5 font-semibold text-white hover:bg-copper-dark">继续阅读</RouterLink>
            <RouterLink v-else :to="{ name: 'teach' }" class="inline-flex min-h-12 items-center rounded-lg bg-copper px-5 font-semibold text-white hover:bg-copper-dark">上传规则书</RouterLink>
            <RouterLink v-if="latestPlan" :to="{ name: 'teach' }" class="inline-flex min-h-12 items-center rounded-lg border border-white/30 bg-black/20 px-5 font-semibold text-white backdrop-blur-sm hover:bg-black/35">上传新规则书</RouterLink>
            <RouterLink :to="{ name: 'public-library' }" class="inline-flex min-h-12 items-center rounded-lg border border-white/30 bg-black/20 px-5 font-semibold text-white backdrop-blur-sm hover:bg-black/35">读公开讲解</RouterLink>
          </div>
          <p class="mt-6 text-sm text-white/55">{{ latestPlan ? `${latestPlan.playerCount} 人 · 约 ${latestPlan.durationMinutes} 分钟讲解` : '不用先创建游戏；标题也可以稍后再改。' }}</p>
        </div>
      </section>

      <section v-if="publicLessons.length" class="border-b border-ink/10 py-12">
        <div class="flex items-end justify-between gap-4">
          <div>
            <p class="text-sm font-medium text-copper">先看看别人怎么开桌</p>
            <h2 class="mt-2 font-display text-3xl font-semibold">公开讲解</h2>
          </div>
          <RouterLink :to="{ name: 'public-library' }" class="shrink-0 text-sm font-semibold text-indigo">全部讲解 →</RouterLink>
        </div>
        <div class="-mx-5 mt-6 flex gap-4 overflow-x-auto px-5 pb-3 sm:mx-0 sm:grid sm:grid-cols-3 sm:overflow-visible sm:px-0">
          <RouterLink v-for="lesson in publicLessons" :key="lesson.teachingPlanId" :to="{ name: 'public-lesson', params: { planId: lesson.teachingPlanId } }" class="group w-52 shrink-0 sm:w-auto">
            <div class="relative aspect-[16/10] overflow-hidden rounded-xl border border-ink/10 bg-paper shadow-sm transition group-hover:-translate-y-1 group-hover:shadow-lg">
              <img v-if="lesson.gameCover" :src="lesson.gameCover.imageUrl" :alt="`${lesson.gameCover.gameName} 的游戏封面`" class="h-full w-full object-cover" loading="lazy" referrerpolicy="no-referrer" @error="hideBrokenImage">
              <div v-else class="flex h-full items-end bg-[linear-gradient(145deg,#282018,#604531)] p-4 text-paper"><span class="font-display text-xl font-semibold leading-tight">{{ lesson.rulebookTitle }}</span></div>
            </div>
            <h3 class="mt-3 line-clamp-2 font-semibold leading-5">{{ lesson.gameCover?.gameName ?? lesson.rulebookTitle }}</h3>
            <p class="mt-1 text-xs text-ink/45">{{ lesson.sectionCount }} 章 · {{ lesson.stepCount }} 步</p>
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
          <li v-for="plan in recentPlans" :key="plan.id">
            <RouterLink :to="{ name: 'lesson', params: { planId: plan.id } }" class="group grid gap-1 py-4 sm:grid-cols-[1fr_auto] sm:items-center">
              <span class="font-semibold">{{ plan.gameTitle }}</span>
              <span class="text-sm text-ink/45">{{ plan.playerCount }} 人 · {{ plan.durationMinutes }} 分钟 · {{ createdLabel(plan.createdAt) }} →</span>
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
