<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import PublicLessonCover from '@/components/PublicLessonCover.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import { useLocale } from '@/lib/locale'
import {
  deduplicatePublicLessons,
  groupPlansForReading,
  playerFacingTitle,
} from '@/lib/lessonPresentation'
import { publicCoverUrl } from '@/lib/publicCover'

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
  originalName?: string
  nameLocalized?: boolean
  publicationYear: number | null
  thumbnailUrl: string
  bggUrl: string
  minPlayers: number | null
  maxPlayers: number | null
  playingTimeMinutes: number | null
  averageRating: number | null
  averageWeight: number | null
  categories: string[]
  mechanics: string[]
}

interface PublicLessonPreview {
  teachingPlanId: string
  rulebookTitle: string
  gameCover: { gameName: string; imageUrl: string } | null
  sectionCount: number
  stepCount: number
}

const username = ref('')
const { locale, t } = useLocale()
const plans = ref<TeachingPlan[]>([])
const latestPlan = computed(() => plans.value[0] ?? null)
const latestPlanTitle = computed(() => latestPlan.value ? playerFacingTitle(latestPlan.value.gameTitle) : '')
const recentPlans = computed(() => groupPlansForReading(plans.value).slice(0, 3))
const hotGames = ref<HotGame[]>([])
const hotGamesLoading = ref(true)
const hotGamesUnavailable = ref(false)
const showingPersonalShelf = ref(false)
const playerFilter = ref('')
const durationFilter = ref('')
const weightFilter = ref('')
const filtersActive = computed(() => Boolean(playerFilter.value || durationFilter.value || weightFilter.value))
const publicLessons = ref<PublicLessonPreview[]>([])
const featuredPublicLessons = computed(() => deduplicatePublicLessons(publicLessons.value).slice(0, 3))

function createdLabel(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : new Intl.DateTimeFormat(locale.value, { month: 'short', day: 'numeric' }).format(date)
}

function hideBrokenImage(event: Event) {
  const image = event.currentTarget as HTMLImageElement
  image.hidden = true
}

function playerTimeLabel(game: HotGame) {
  const parts: string[] = []
  if (game.minPlayers !== null && game.maxPlayers !== null) {
    parts.push(t('home.hotPlayers', { min: game.minPlayers, max: game.maxPlayers }))
  }
  if (game.playingTimeMinutes !== null) {
    parts.push(t('home.hotMinutes', { minutes: game.playingTimeMinutes }))
  }
  return parts.join(' · ')
}

function ratingWeightLabel(game: HotGame) {
  const parts: string[] = []
  if (game.averageRating !== null) parts.push(t('home.hotRating', { rating: game.averageRating.toFixed(1) }))
  if (game.averageWeight !== null) parts.push(t('home.hotWeight', { weight: game.averageWeight.toFixed(1) }))
  return parts.join(' · ')
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
  hotGamesLoading.value = true
  hotGamesUnavailable.value = false
  showingPersonalShelf.value = false
  try {
    const parameters = new URLSearchParams()
    if (playerFilter.value) parameters.set('players', playerFilter.value)
    if (durationFilter.value) parameters.set('maxMinutes', durationFilter.value)
    if (weightFilter.value) parameters.set('maxWeight', weightFilter.value)
    parameters.set('locale', locale.value)
    const query = `?${parameters.toString()}`
    const response = await fetch(`/api/v1/bgg/recommendations${query}`, { credentials: 'include' })
    if (response.ok) {
      hotGames.value = await response.json() as HotGame[]
      hotGamesUnavailable.value = hotGames.value.length === 0 && !filtersActive.value
    } else if (!filtersActive.value) {
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
            minPlayers: null,
            maxPlayers: null,
            playingTimeMinutes: null,
            averageRating: null,
            averageWeight: null,
            categories: [],
            mechanics: [],
          }))
        showingPersonalShelf.value = hotGames.value.length > 0
      }
      hotGamesUnavailable.value = hotGames.value.length === 0
    } else {
      hotGames.value = []
      hotGamesUnavailable.value = true
    }
  } catch {
    hotGames.value = []
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
watch(locale, loadHotGames)
</script>

<template>
  <AppShell>
    <div class="mx-auto max-w-7xl px-5 py-6 sm:px-8 sm:py-10 lg:px-12 lg:py-12">
      <section class="home-table relative overflow-hidden rounded-[2rem] bg-ink px-6 py-8 text-canvas shadow-xl shadow-ink/10 sm:px-10 sm:py-10 lg:px-14">
        <div class="pointer-events-none absolute -right-6 -top-8 text-canvas/[0.07] sm:right-12"><TabletopGlyph name="meeple" :size="180" /></div>
        <div class="relative max-w-3xl">
          <p class="text-sm font-semibold tracking-wide text-copper">{{ username ? t('home.personal', { username }) : t('home.greeting') }}</p>
          <h1 v-if="latestPlan" class="mt-3 font-display text-4xl font-semibold leading-tight tracking-[-0.03em] sm:text-5xl">{{ t('home.continueTitle') }}</h1>
          <h1 v-else class="mt-3 font-display text-4xl font-semibold leading-tight tracking-[-0.03em] sm:text-5xl">{{ t('home.title') }}</h1>
          <p class="mt-4 max-w-xl text-base leading-8 text-canvas/70">{{ latestPlan ? t('home.continueDescription') : t('home.description') }}</p>

          <div v-if="latestPlan" class="mt-7 flex flex-col gap-4 rounded-2xl border border-canvas/15 bg-canvas/[0.06] p-5 sm:flex-row sm:items-center sm:justify-between">
            <div class="min-w-0">
              <p class="text-xs font-bold uppercase tracking-[0.14em] text-copper">{{ t('home.lastOpened') }}</p>
              <h2 class="mt-2 truncate font-display text-2xl font-semibold">{{ latestPlanTitle }}</h2>
              <p class="mt-1 text-sm text-canvas/60">{{ t('home.duration', { players: latestPlan.playerCount, minutes: latestPlan.durationMinutes }) }}</p>
            </div>
            <RouterLink :to="{ name: 'lesson', params: { planId: latestPlan.id } }" class="inline-flex min-h-12 shrink-0 items-center justify-center gap-2 rounded-xl bg-copper px-5 font-semibold text-white hover:bg-copper-dark">{{ t('home.continue') }} <TabletopGlyph name="arrow" :size="18" /></RouterLink>
          </div>

          <div v-else class="mt-7 flex flex-wrap gap-3">
            <RouterLink :to="{ name: 'teach' }" class="inline-flex min-h-12 items-center gap-2 rounded-xl bg-copper px-5 font-semibold text-white hover:bg-copper-dark"><TabletopGlyph name="plus" :size="18" /> {{ t('home.upload') }}</RouterLink>
            <RouterLink :to="{ name: 'public-library' }" class="inline-flex min-h-12 items-center gap-2 rounded-xl border border-canvas/25 px-5 font-semibold text-canvas hover:bg-canvas/10"><TabletopGlyph name="library" :size="18" /> {{ t('home.public') }}</RouterLink>
          </div>

          <div v-if="latestPlan" class="mt-4 flex flex-wrap gap-3">
            <RouterLink :to="{ name: 'teach' }" class="inline-flex min-h-11 items-center gap-2 rounded-xl border border-canvas/25 px-4 text-sm font-semibold text-canvas hover:bg-canvas/10"><TabletopGlyph name="plus" :size="17" /> {{ t('home.uploadNew') }}</RouterLink>
            <RouterLink :to="{ name: 'public-library' }" class="inline-flex min-h-11 items-center gap-2 rounded-xl border border-canvas/25 px-4 text-sm font-semibold text-canvas hover:bg-canvas/10"><TabletopGlyph name="library" :size="17" /> {{ t('home.public') }}</RouterLink>
          </div>
          <p class="mt-5 text-sm text-canvas/50">{{ latestPlan ? t('home.keep') : t('home.noSetup') }}</p>
        </div>
      </section>

      <section v-if="featuredPublicLessons.length" class="border-b border-ink/10 py-12">
        <div class="flex items-end justify-between gap-4">
          <div>
            <p class="text-sm font-medium text-copper">{{ t('home.publicEyebrow') }}</p>
            <h2 class="mt-2 font-display text-3xl font-semibold">{{ t('home.publicTitle') }}</h2>
          </div>
          <RouterLink :to="{ name: 'public-library' }" class="shrink-0 text-sm font-semibold text-indigo">{{ t('home.allGuides') }}</RouterLink>
        </div>
        <div class="-mx-5 mt-6 flex gap-4 overflow-x-auto px-5 pb-3 sm:mx-0 sm:grid sm:grid-cols-3 sm:overflow-visible sm:px-0">
          <RouterLink v-for="(entry, index) in featuredPublicLessons" :key="entry.lesson.teachingPlanId" :to="{ name: 'public-lesson', params: { planId: entry.lesson.teachingPlanId } }" class="group w-52 shrink-0 sm:w-auto">
            <div class="relative aspect-[16/10] overflow-hidden rounded-xl border border-ink/10 bg-paper shadow-sm transition group-hover:-translate-y-1 group-hover:shadow-lg">
              <PublicLessonCover :title="entry.title" :image-url="publicCoverUrl(entry.lesson.teachingPlanId)" :alt="t('home.cover', { title: entry.title })" :index="index" />
            </div>
            <h3 class="mt-3 line-clamp-2 font-semibold leading-5">{{ entry.title }}</h3>
            <p class="mt-1 text-xs text-ink/45">{{ t('home.guideSize', { sections: entry.lesson.sectionCount, steps: entry.lesson.stepCount }) }}</p>
          </RouterLink>
        </div>
      </section>

      <section class="border-b border-ink/10 py-12">
        <div class="flex items-end justify-between gap-4">
          <div>
            <p class="text-sm font-medium text-copper">{{ showingPersonalShelf ? t('home.shelfEyebrow') : t('home.hotEyebrow') }}</p>
            <h2 class="mt-2 font-display text-3xl font-semibold">{{ showingPersonalShelf ? t('home.shelfTitle') : t('home.hotTitle') }}</h2>
          </div>
          <div v-if="!showingPersonalShelf" class="flex shrink-0 flex-col items-end gap-1">
            <RouterLink :to="{ name: 'game-recommendations' }" class="text-sm font-semibold text-indigo">{{ t('home.exploreGames') }} →</RouterLink>
            <a href="https://boardgamegeek.com/hotness" target="_blank" rel="noreferrer" class="text-xs font-semibold text-ink/45 hover:text-indigo">Powered by BGG ↗</a>
          </div>
        </div>

        <form v-if="!showingPersonalShelf" class="mt-5 grid gap-3 rounded-xl border border-ink/10 bg-paper p-4 sm:grid-cols-[1fr_1fr_1fr_auto] sm:items-end" @submit.prevent="loadHotGames">
          <label class="grid gap-1.5 text-xs font-semibold text-ink/60">
            {{ t('home.hotPlayerFilter') }}
            <select v-model="playerFilter" class="min-h-11 rounded-lg border border-ink/15 bg-canvas px-3 text-sm text-ink outline-none focus:border-copper">
              <option value="">{{ t('home.hotAnyPlayers') }}</option>
              <option v-for="players in [1, 2, 3, 4, 5, 6]" :key="players" :value="String(players)">{{ t('home.hotPlayersExact', { players }) }}</option>
            </select>
          </label>
          <label class="grid gap-1.5 text-xs font-semibold text-ink/60">
            {{ t('home.hotDurationFilter') }}
            <select v-model="durationFilter" class="min-h-11 rounded-lg border border-ink/15 bg-canvas px-3 text-sm text-ink outline-none focus:border-copper">
              <option value="">{{ t('home.hotAnyDuration') }}</option>
              <option value="30">{{ t('home.hotWithinMinutes', { minutes: 30 }) }}</option>
              <option value="60">{{ t('home.hotWithinMinutes', { minutes: 60 }) }}</option>
              <option value="90">{{ t('home.hotWithinMinutes', { minutes: 90 }) }}</option>
              <option value="120">{{ t('home.hotWithinMinutes', { minutes: 120 }) }}</option>
            </select>
          </label>
          <label class="grid gap-1.5 text-xs font-semibold text-ink/60">
            {{ t('home.hotWeightFilter') }}
            <select v-model="weightFilter" class="min-h-11 rounded-lg border border-ink/15 bg-canvas px-3 text-sm text-ink outline-none focus:border-copper">
              <option value="">{{ t('home.hotAnyWeight') }}</option>
              <option value="2">{{ t('home.hotMaxWeight', { weight: '2.0' }) }}</option>
              <option value="3">{{ t('home.hotMaxWeight', { weight: '3.0' }) }}</option>
              <option value="4">{{ t('home.hotMaxWeight', { weight: '4.0' }) }}</option>
            </select>
          </label>
          <button type="submit" :disabled="hotGamesLoading" class="min-h-11 rounded-lg bg-ink px-5 text-sm font-semibold text-canvas disabled:opacity-50">{{ t('home.hotApply') }}</button>
        </form>

        <div v-if="hotGamesLoading" class="mt-6 grid grid-cols-2 gap-4 sm:grid-cols-4 lg:grid-cols-6" :aria-label="t('home.hotLoading')">
          <div v-for="index in 6" :key="index" class="animate-pulse">
            <div class="aspect-[4/5] rounded-xl bg-ink/8" />
            <div class="mt-3 h-4 w-3/4 rounded bg-ink/8" />
          </div>
        </div>
        <div v-else-if="hotGames.length" class="-mx-5 mt-6 flex snap-x gap-4 overflow-x-auto px-5 pb-3 sm:mx-0 sm:grid sm:grid-cols-4 sm:overflow-visible sm:px-0 lg:grid-cols-6">
          <article v-for="game in hotGames.slice(0, 6)" :key="game.bggId" class="group w-36 shrink-0 snap-start sm:w-auto">
            <RouterLink :to="{ name: 'game-discovery', params: { bggId: game.bggId } }" :aria-label="t('home.hotInspect', { game: game.name })">
              <div class="relative aspect-[4/5] overflow-hidden rounded-xl border border-ink/10 bg-paper p-2 shadow-sm transition group-hover:-translate-y-1 group-hover:shadow-lg">
                <img :src="game.thumbnailUrl" :alt="t('home.coverShort', { title: game.name })" loading="lazy" class="h-full w-full rounded-lg object-contain" @error="hideBrokenImage">
                <span v-if="!showingPersonalShelf" class="absolute left-2 top-2 grid h-7 min-w-7 place-items-center rounded-full bg-ink px-2 text-xs font-bold text-canvas">{{ game.rank }}</span>
              </div>
              <h3 class="mt-3 line-clamp-2 text-sm font-semibold leading-5">{{ game.name }}</h3>
            </RouterLink>
            <p v-if="game.nameLocalized && game.originalName" class="mt-1 line-clamp-1 text-xs text-ink/40">{{ game.originalName }}</p>
            <p class="mt-1 text-xs text-ink/40">{{ game.publicationYear ?? t('home.unknownYear') }}</p>
            <p v-if="playerTimeLabel(game)" class="mt-1 text-xs leading-5 text-ink/55">{{ playerTimeLabel(game) }}</p>
            <p v-if="ratingWeightLabel(game)" class="text-xs leading-5 text-ink/45">{{ ratingWeightLabel(game) }}</p>
            <a :href="game.bggUrl" target="_blank" rel="noopener noreferrer" class="mt-2 inline-block text-xs font-semibold text-indigo">{{ t('home.hotSource') }} ↗</a>
          </article>
        </div>
        <div v-else-if="filtersActive && !hotGamesUnavailable" class="mt-6 rounded-xl border border-dashed border-ink/15 bg-paper px-5 py-6">
          <p class="text-sm leading-6 text-ink/55">{{ t('home.hotNoMatch') }}</p>
        </div>
        <div v-else-if="hotGamesUnavailable" class="mt-6 flex flex-col gap-3 rounded-xl border border-dashed border-ink/15 bg-paper px-5 py-6 sm:flex-row sm:items-center sm:justify-between">
          <p class="text-sm leading-6 text-ink/55">{{ t('home.hotMissing') }}</p>
          <RouterLink :to="{ name: 'catalog' }" class="shrink-0 text-sm font-semibold text-indigo">{{ t('home.searchGames') }}</RouterLink>
        </div>
      </section>

      <section v-if="recentPlans.length" class="border-b border-ink/10 py-10">
        <div class="flex items-center justify-between gap-4">
          <h2 class="font-display text-2xl font-semibold">{{ t('home.recent') }}</h2>
          <RouterLink :to="{ name: 'lessons' }" class="text-sm font-semibold text-indigo">{{ t('home.viewAll') }}</RouterLink>
        </div>
        <ol class="mt-5 divide-y divide-ink/10 border-y border-ink/10">
          <li v-for="entry in recentPlans" :key="entry.plan.id">
            <RouterLink :to="{ name: 'lesson', params: { planId: entry.plan.id } }" class="group grid gap-1 py-4 sm:grid-cols-[1fr_auto] sm:items-center">
              <span class="font-semibold">{{ entry.title }}<span v-if="entry.count > 1" class="ml-2 text-xs font-medium text-ink/45">· {{ t('home.guideCount', { count: entry.count }) }}</span></span>
              <span class="text-sm text-ink/45">{{ t('home.meta', { players: entry.plan.playerCount, minutes: entry.plan.durationMinutes, date: createdLabel(entry.plan.createdAt) }) }}</span>
            </RouterLink>
          </li>
        </ol>
      </section>

      <footer class="flex flex-col gap-2 border-t border-ink/10 py-7 text-xs leading-5 text-ink/40 sm:flex-row sm:items-center sm:justify-between">
        <p>{{ t('home.footer') }}</p>
        <RouterLink :to="{ name: 'catalog' }" class="hover:text-ink">{{ t('home.organize') }}</RouterLink>
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
