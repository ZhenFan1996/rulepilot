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

const username = ref('')
const plans = ref<TeachingPlan[]>([])
const recentPlans = computed(() => plans.value.slice(0, 3))
const latestPlan = computed(() => recentPlans.value[0] ?? null)
const hotGames = ref<HotGame[]>([])
const hotGamesLoading = ref(true)
const hotGamesUnavailable = ref(false)
const showingPersonalShelf = ref(false)

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

onMounted(() => Promise.all([loadPersonalHome(), loadHotGames()]))
</script>

<template>
  <AppShell>
    <div class="mx-auto max-w-7xl px-5 py-6 sm:px-8 sm:py-10 lg:px-12 lg:py-12">
      <section class="relative isolate min-h-[32rem] overflow-hidden rounded-2xl bg-[#1b130e] text-white shadow-xl shadow-ink/10 sm:min-h-[35rem]">
        <img src="/tabletop-hero.jpg" alt="木桌上的原创策略桌游组件" class="absolute inset-0 h-full w-full object-cover object-[62%_center] sm:object-center">
        <div class="absolute inset-0 bg-gradient-to-r from-black/90 via-black/60 to-black/10" />
        <div class="relative flex min-h-[32rem] max-w-2xl flex-col justify-end px-6 py-9 sm:min-h-[35rem] sm:px-10 sm:py-12 lg:px-14">
          <p class="text-sm font-semibold tracking-wide text-amber-300">{{ username ? `${username} 的规则桌` : '今晚玩什么？' }}</p>
          <h1 v-if="latestPlan" class="mt-4 font-display text-4xl font-semibold leading-tight tracking-[-0.03em] sm:text-6xl">继续讲<br>{{ latestPlan.gameTitle }}</h1>
          <h1 v-else class="mt-4 font-display text-4xl font-semibold leading-tight tracking-[-0.03em] sm:text-6xl">规则讲明白，<br>今晚就开桌。</h1>
          <p class="mt-5 max-w-xl text-base leading-8 text-white/72">{{ latestPlan ? latestPlan.premise : '上传一本规则书，从摆桌、回合到计分，按真正教朋友玩游戏的顺序讲清楚。' }}</p>
          <div class="mt-7 flex flex-wrap gap-3">
            <RouterLink v-if="latestPlan" :to="{ name: 'lesson', params: { planId: latestPlan.id } }" class="inline-flex min-h-12 items-center rounded-lg bg-copper px-5 font-semibold text-white hover:bg-copper-dark">继续上次的讲解</RouterLink>
            <RouterLink v-else :to="{ name: 'teach' }" class="inline-flex min-h-12 items-center rounded-lg bg-copper px-5 font-semibold text-white hover:bg-copper-dark">添加规则书</RouterLink>
            <RouterLink :to="{ name: latestPlan ? 'teach' : 'lessons' }" class="inline-flex min-h-12 items-center rounded-lg border border-white/30 bg-black/20 px-5 font-semibold text-white backdrop-blur-sm hover:bg-black/35">{{ latestPlan ? '准备新讲解' : '我的讲解' }}</RouterLink>
          </div>
          <p class="mt-6 text-sm text-white/55">{{ latestPlan ? `${latestPlan.playerCount} 人 · 约 ${latestPlan.durationMinutes} 分钟讲完` : '支持规则书文字、页面图片与页码引用' }}</p>
        </div>
      </section>

      <section class="border-b border-ink/10 py-12">
        <div class="flex items-end justify-between gap-4">
          <div>
            <p class="text-sm font-medium text-copper">{{ showingPersonalShelf ? '你的收藏' : '桌游发现' }}</p>
            <h2 class="mt-2 font-display text-3xl font-semibold">{{ showingPersonalShelf ? '你的游戏架' : 'BGG 本周热门' }}</h2>
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
          <p class="text-sm leading-6 text-ink/55">配置 BGG Application Token 后，这里会出现当前热门桌游封面；你也可以先导入自己的游戏。</p>
          <RouterLink :to="{ name: 'catalog' }" class="shrink-0 text-sm font-semibold text-indigo">连接 BGG →</RouterLink>
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

      <section class="py-14">
        <div class="flex items-end justify-between gap-4">
          <div>
            <p class="text-sm font-medium text-ink/45">常用入口</p>
            <h2 class="mt-2 font-display text-3xl font-semibold">现在要做什么？</h2>
          </div>
        </div>

        <div class="mt-7 divide-y divide-ink/10 border-y border-ink/10">
          <RouterLink :to="{ name: 'catalog' }" class="group grid gap-2 py-5 sm:grid-cols-[10rem_1fr_auto] sm:items-center">
            <span class="font-semibold">找一款游戏</span>
            <span class="text-sm leading-6 text-ink/50">从 BGG 读取资料，或自己添加游戏和版本。</span>
            <span class="text-ink/35 transition-transform group-hover:translate-x-1" aria-hidden="true">→</span>
          </RouterLink>
          <RouterLink :to="{ name: 'teach' }" class="group grid gap-2 py-5 sm:grid-cols-[10rem_1fr_auto] sm:items-center">
            <span class="font-semibold">整理规则书</span>
            <span class="text-sm leading-6 text-ink/50">上传 PDF，确认内容是否足够支持一场完整讲解。</span>
            <span class="text-ink/35 transition-transform group-hover:translate-x-1" aria-hidden="true">→</span>
          </RouterLink>
          <RouterLink :to="{ name: 'lessons' }" class="group grid gap-2 py-5 sm:grid-cols-[10rem_1fr_auto] sm:items-center">
            <span class="font-semibold">回到讲解</span>
            <span class="text-sm leading-6 text-ink/50">从上次的位置继续，或换一种人数和时长重新准备。</span>
            <span class="text-ink/35 transition-transform group-hover:translate-x-1" aria-hidden="true">→</span>
          </RouterLink>
        </div>
      </section>

      <footer class="flex flex-col gap-2 border-t border-ink/10 py-7 text-xs leading-5 text-ink/40 sm:flex-row sm:items-center sm:justify-between">
        <p>讲解中的规则结论可以回到原始页码核对。</p>
        <RouterLink :to="{ name: 'model-settings' }" class="hover:text-ink">模型设置</RouterLink>
      </footer>
    </div>
  </AppShell>
</template>
