<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import { useLocale } from '@/lib/locale'

interface DiscoveryGame {
  rank: number
  bggId: number
  name: string
  originalName: string
  nameLocalized: boolean
  publicationYear: number | null
  thumbnailUrl: string
  minPlayers: number | null
  maxPlayers: number | null
  playingTimeMinutes: number | null
  averageRating: number | null
  averageWeight: number | null
  categories: string[]
  mechanics: string[]
  bggUrl: string
}

interface DiscoveryCategory { value: string; label: string }
interface DiscoveryResponse {
  sourceCount: number
  sort: 'hot' | 'rating'
  categoriesTranslated: boolean
  categories: DiscoveryCategory[]
  games: DiscoveryGame[]
}
interface SearchResult { bggId: number; name: string; publicationYear: number | null; bggUrl: string }

const { locale } = useLocale()
const copy = {
  'zh-CN': {
    eyebrow: '桌游发现',
    title: '找到今晚适合上桌的游戏',
    description: '先从当前 BGG 热门候选中按热度、评分和开桌条件筛选；已有目标时，也可以直接搜索 BGG 标题。',
    searchLabel: '直接搜索桌游',
    searchPlaceholder: '输入中文名、英文名或原版名',
    search: '搜索',
    searching: '正在搜索…',
    searchValidation: '请至少输入 2 个字符。',
    hotEyebrow: '当前 BGG 热门候选',
    hotTitle: '按你的开桌条件慢慢挑',
    bggSource: '查看 BGG 热门榜',
    sortLabel: '排序',
    sortHot: '热度优先',
    sortRating: '评分优先',
    categoryLabel: '游戏类型',
    categoryAny: '不限类型',
    playerLabel: '人数',
    playerAny: '不限人数',
    playerExact: '{count} 人',
    durationLabel: '最长时长',
    durationAny: '不限时长',
    durationWithin: '{minutes} 分钟内',
    weightLabel: '最高复杂度',
    weightAny: '不限复杂度',
    weightMax: '不高于 {weight}',
    apply: '应用',
    clear: '清空',
    scope: '排序与筛选范围为当前 {count} 款 BGG 热门桌游，不代表全站总榜。',
    categoryFallback: '分类翻译暂不可用，已保留 BGG 原文',
    loading: '正在读取桌游推荐',
    errorTitle: '暂时读不到 BGG 热门资料',
    errorDescription: '筛选条件没有丢失。可以稍后重试，或直接在上方搜索一款游戏。',
    retry: '再试一次',
    players: '{min}–{max} 人',
    minutes: '约 {minutes} 分钟',
    unknownFit: '人数与时长待确认',
    ratingWeight: '评分 {rating} · 复杂度 {weight} / 5',
    categoriesAria: '游戏类型',
    coverAlt: '{game} 的 BGG 封面',
    emptyTitle: '这一批暂时没有合适的结果',
    emptyDescription: '这只是当前热门候选集。放宽一个条件，或用上方搜索直接找目标游戏。',
    backToHot: '返回热门推荐',
    searchTitle: '“{query}”的 BGG 搜索结果',
    searchScope: '搜索结果用于识别游戏；打开详情后会读取人数、时长、评分、机制与类型。',
    searchErrorTitle: 'BGG 搜索暂时不可用',
    searchEmptyTitle: '没有找到匹配标题',
    searchEmptyDescription: '试试原版名称、英文名，或减少关键词。',
    unknownYear: '年份未知',
    inspect: '查看详情',
  },
  en: {
    eyebrow: 'Game discovery',
    title: 'Find a game that fits tonight',
    description: 'Browse the current BGG hot candidates by heat, rating, and table fit, or search BoardGameGeek directly when you already have a title in mind.',
    searchLabel: 'Search for a game',
    searchPlaceholder: 'Enter a title or original name',
    search: 'Search',
    searching: 'Searching…',
    searchValidation: 'Enter at least 2 characters.',
    hotEyebrow: 'Current BGG hot candidates',
    hotTitle: 'Narrow the table down at your pace',
    bggSource: 'View BGG hotness',
    sortLabel: 'Sort',
    sortHot: 'Heat first',
    sortRating: 'Rating first',
    categoryLabel: 'Category',
    categoryAny: 'Any category',
    playerLabel: 'Players',
    playerAny: 'Any player count',
    playerExact: '{count} players',
    durationLabel: 'Maximum duration',
    durationAny: 'Any duration',
    durationWithin: 'Within {minutes} min',
    weightLabel: 'Maximum complexity',
    weightAny: 'Any complexity',
    weightMax: 'At most {weight}',
    apply: 'Apply',
    clear: 'Clear',
    scope: 'Sorting and filters cover the current {count} BGG hot games, not the full-site ranking.',
    categoryFallback: 'Category translation is unavailable; showing BGG source terms',
    loading: 'Loading game recommendations',
    errorTitle: 'BGG hot-game data is unavailable',
    errorDescription: 'Your filters are still here. Try again later, or search for a specific game above.',
    retry: 'Try again',
    players: '{min}–{max} players',
    minutes: 'About {minutes} min',
    unknownFit: 'Players and duration need confirmation',
    ratingWeight: 'Rating {rating} · Complexity {weight} / 5',
    categoriesAria: 'Game categories',
    coverAlt: '{game} BGG cover',
    emptyTitle: 'Nothing in this batch fits yet',
    emptyDescription: 'This is only the current hot candidate set. Relax a filter or search for a specific game above.',
    backToHot: 'Back to hot recommendations',
    searchTitle: 'BGG results for “{query}”',
    searchScope: 'Search results identify games. Open one to load players, duration, rating, mechanics, and categories.',
    searchErrorTitle: 'BGG search is unavailable',
    searchEmptyTitle: 'No matching title found',
    searchEmptyDescription: 'Try the original title, an English name, or fewer keywords.',
    unknownYear: 'Year unknown',
    inspect: 'View details',
  },
} as const
type CopyKey = keyof typeof copy['zh-CN']

function t(key: `recommendations.${CopyKey}`, parameters: Record<string, string | number> = {}) {
  const copyKey = key.slice('recommendations.'.length) as CopyKey
  return copy[locale.value][copyKey].replace(/\{(\w+)\}/g, (placeholder, name: string) =>
    parameters[name] === undefined ? placeholder : String(parameters[name]))
}
const games = ref<DiscoveryGame[]>([])
const categories = ref<DiscoveryCategory[]>([])
const sourceCount = ref(0)
const categoriesTranslated = ref(false)
const loading = ref(true)
const loadFailed = ref(false)
const players = ref('')
const maxMinutes = ref('')
const maxWeight = ref('')
const category = ref('')
const sort = ref<'hot' | 'rating'>('hot')
const searchQuery = ref('')
const submittedQuery = ref('')
const searchResults = ref<SearchResult[]>([])
const searching = ref(false)
const searchFailed = ref(false)
const searchValidation = ref(false)
let discoveryRequest = 0
let searchRequest = 0

const browsing = computed(() => !submittedQuery.value)
const filterActive = computed(() => Boolean(players.value || maxMinutes.value || maxWeight.value || category.value))
const categoryLabels = computed(() => new Map(categories.value.map(item => [item.value, item.label])))

function gameCategoryLabel(value: string) {
  return categoryLabels.value.get(value) ?? value
}

function playerTimeLabel(game: DiscoveryGame) {
  const parts: string[] = []
  if (game.minPlayers !== null && game.maxPlayers !== null) {
    parts.push(t('recommendations.players', { min: game.minPlayers, max: game.maxPlayers }))
  }
  if (game.playingTimeMinutes !== null) {
    parts.push(t('recommendations.minutes', { minutes: game.playingTimeMinutes }))
  }
  return parts.join(' · ')
}

function hideBrokenImage(event: Event) {
  ;(event.currentTarget as HTMLImageElement).hidden = true
}

async function loadDiscovery() {
  const request = ++discoveryRequest
  loading.value = true
  loadFailed.value = false
  const parameters = new URLSearchParams({ sort: sort.value, locale: locale.value })
  if (players.value) parameters.set('players', players.value)
  if (maxMinutes.value) parameters.set('maxMinutes', maxMinutes.value)
  if (maxWeight.value) parameters.set('maxWeight', maxWeight.value)
  if (category.value) parameters.set('category', category.value)
  try {
    const response = await fetch(`/api/v1/bgg/discovery?${parameters.toString()}`, { credentials: 'include' })
    if (!response.ok) throw new Error('discovery unavailable')
    const data = await response.json() as DiscoveryResponse
    if (request !== discoveryRequest) return
    games.value = data.games
    categories.value = data.categories
    sourceCount.value = data.sourceCount
    categoriesTranslated.value = data.categoriesTranslated
  } catch {
    if (request !== discoveryRequest) return
    games.value = []
    loadFailed.value = true
  } finally {
    if (request === discoveryRequest) loading.value = false
  }
}

function clearFilters() {
  players.value = ''
  maxMinutes.value = ''
  maxWeight.value = ''
  category.value = ''
  sort.value = 'hot'
  void loadDiscovery()
}

async function searchGames() {
  const checked = searchQuery.value.trim().replace(/\s+/g, ' ')
  searchValidation.value = checked.length < 2
  if (searchValidation.value) return
  const request = ++searchRequest
  submittedQuery.value = checked
  searching.value = true
  searchFailed.value = false
  searchResults.value = []
  try {
    const response = await fetch(`/api/v1/bgg/search?q=${encodeURIComponent(checked)}`, { credentials: 'include' })
    if (!response.ok) throw new Error('search unavailable')
    const data = await response.json() as SearchResult[]
    if (request === searchRequest) searchResults.value = data
  } catch {
    if (request === searchRequest) searchFailed.value = true
  } finally {
    if (request === searchRequest) searching.value = false
  }
}

function returnToBrowse() {
  searchRequest++
  submittedQuery.value = ''
  searchResults.value = []
  searchFailed.value = false
  searchValidation.value = false
}

onMounted(loadDiscovery)
watch(locale, loadDiscovery)
</script>

<template>
  <AppShell>
    <div class="mx-auto max-w-7xl px-5 py-8 sm:px-8 sm:py-12 lg:px-12">
      <header class="grid gap-6 border-b border-ink/10 pb-8 lg:grid-cols-[1fr_22rem] lg:items-end">
        <div class="max-w-3xl">
          <p class="text-sm font-semibold text-copper">{{ t('recommendations.eyebrow') }}</p>
          <h1 class="mt-2 font-display text-4xl font-semibold tracking-[-0.03em] sm:text-5xl">{{ t('recommendations.title') }}</h1>
          <p class="mt-4 max-w-2xl text-base leading-8 text-ink/60">{{ t('recommendations.description') }}</p>
        </div>
        <form class="rounded-2xl border border-ink/10 bg-paper p-4 shadow-sm" role="search" @submit.prevent="searchGames">
          <label for="bgg-title-search" class="text-xs font-bold uppercase tracking-[0.12em] text-ink/55">{{ t('recommendations.searchLabel') }}</label>
          <div class="mt-2 flex gap-2">
            <input id="bgg-title-search" v-model="searchQuery" type="search" maxlength="120" :placeholder="t('recommendations.searchPlaceholder')" class="min-h-12 min-w-0 flex-1 rounded-xl border border-ink/15 bg-canvas px-3 text-sm outline-none focus:border-copper">
            <button type="submit" :disabled="searching" class="min-h-12 shrink-0 rounded-xl bg-indigo px-4 text-sm font-semibold text-white disabled:opacity-50">{{ searching ? t('recommendations.searching') : t('recommendations.search') }}</button>
          </div>
          <p v-if="searchValidation" class="mt-2 text-xs text-danger" role="alert">{{ t('recommendations.searchValidation') }}</p>
        </form>
      </header>

      <section v-if="browsing" class="py-8">
        <div class="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <p class="text-sm font-semibold text-copper">{{ t('recommendations.hotEyebrow') }}</p>
            <h2 class="mt-1 font-display text-3xl font-semibold">{{ t('recommendations.hotTitle') }}</h2>
          </div>
          <a href="https://boardgamegeek.com/hotness" target="_blank" rel="noopener noreferrer" class="text-sm font-semibold text-indigo">{{ t('recommendations.bggSource') }} ↗</a>
        </div>

        <form class="mt-6 grid gap-3 rounded-2xl border border-ink/10 bg-paper p-4 sm:grid-cols-2 lg:grid-cols-6 lg:items-end" @submit.prevent="loadDiscovery">
          <label class="grid gap-1.5 text-xs font-semibold text-ink/60">
            {{ t('recommendations.sortLabel') }}
            <select v-model="sort" class="min-h-11 rounded-lg border border-ink/15 bg-canvas px-3 text-sm outline-none focus:border-copper">
              <option value="hot">{{ t('recommendations.sortHot') }}</option>
              <option value="rating">{{ t('recommendations.sortRating') }}</option>
            </select>
          </label>
          <label class="grid gap-1.5 text-xs font-semibold text-ink/60">
            {{ t('recommendations.categoryLabel') }}
            <select v-model="category" class="min-h-11 rounded-lg border border-ink/15 bg-canvas px-3 text-sm outline-none focus:border-copper">
              <option value="">{{ t('recommendations.categoryAny') }}</option>
              <option v-for="item in categories" :key="item.value" :value="item.value">{{ item.label }}</option>
            </select>
          </label>
          <label class="grid gap-1.5 text-xs font-semibold text-ink/60">
            {{ t('recommendations.playerLabel') }}
            <select v-model="players" class="min-h-11 rounded-lg border border-ink/15 bg-canvas px-3 text-sm outline-none focus:border-copper">
              <option value="">{{ t('recommendations.playerAny') }}</option>
              <option v-for="count in [1, 2, 3, 4, 5, 6]" :key="count" :value="String(count)">{{ t('recommendations.playerExact', { count }) }}</option>
            </select>
          </label>
          <label class="grid gap-1.5 text-xs font-semibold text-ink/60">
            {{ t('recommendations.durationLabel') }}
            <select v-model="maxMinutes" class="min-h-11 rounded-lg border border-ink/15 bg-canvas px-3 text-sm outline-none focus:border-copper">
              <option value="">{{ t('recommendations.durationAny') }}</option>
              <option v-for="minutes in [30, 60, 90, 120]" :key="minutes" :value="String(minutes)">{{ t('recommendations.durationWithin', { minutes }) }}</option>
            </select>
          </label>
          <label class="grid gap-1.5 text-xs font-semibold text-ink/60">
            {{ t('recommendations.weightLabel') }}
            <select v-model="maxWeight" class="min-h-11 rounded-lg border border-ink/15 bg-canvas px-3 text-sm outline-none focus:border-copper">
              <option value="">{{ t('recommendations.weightAny') }}</option>
              <option v-for="weight in [2, 3, 4]" :key="weight" :value="String(weight)">{{ t('recommendations.weightMax', { weight: `${weight}.0` }) }}</option>
            </select>
          </label>
          <div class="flex gap-2">
            <button type="submit" :disabled="loading" class="min-h-11 flex-1 rounded-lg bg-ink px-4 text-sm font-semibold text-canvas disabled:opacity-50">{{ t('recommendations.apply') }}</button>
            <button v-if="filterActive || sort !== 'hot'" type="button" class="min-h-11 rounded-lg border border-ink/15 px-3 text-sm font-semibold text-ink/60" @click="clearFilters">{{ t('recommendations.clear') }}</button>
          </div>
        </form>

        <p v-if="sourceCount" class="mt-4 text-sm text-ink/45">{{ t('recommendations.scope', { count: sourceCount }) }}<span v-if="locale === 'zh-CN' && !categoriesTranslated"> · {{ t('recommendations.categoryFallback') }}</span></p>

        <div v-if="loading" class="mt-7 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4" :aria-label="t('recommendations.loading')">
          <div v-for="index in 8" :key="index" class="animate-pulse rounded-2xl border border-ink/10 bg-paper p-3">
            <div class="aspect-[4/3] rounded-xl bg-ink/8" />
            <div class="mt-3 h-4 w-2/3 rounded bg-ink/8" />
          </div>
        </div>
        <div v-else-if="loadFailed" class="mt-7 rounded-2xl border border-danger/20 bg-danger/5 p-6" role="alert">
          <h3 class="font-display text-2xl font-semibold">{{ t('recommendations.errorTitle') }}</h3>
          <p class="mt-2 text-sm leading-6 text-ink/60">{{ t('recommendations.errorDescription') }}</p>
          <button type="button" class="mt-4 min-h-11 rounded-lg bg-ink px-5 text-sm font-semibold text-canvas" @click="loadDiscovery">{{ t('recommendations.retry') }}</button>
        </div>
        <div v-else-if="games.length" class="mt-7 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
          <article v-for="game in games" :key="game.bggId" class="group min-w-0 rounded-2xl border border-ink/10 bg-paper p-3 shadow-sm transition hover:-translate-y-1 hover:shadow-lg">
            <RouterLink :to="{ name: 'game-discovery', params: { bggId: game.bggId } }" class="block">
              <div class="relative aspect-[4/3] overflow-hidden rounded-xl bg-canvas p-3">
                <img :src="game.thumbnailUrl" :alt="t('recommendations.coverAlt', { game: game.name })" loading="lazy" class="h-full w-full object-contain" @error="hideBrokenImage">
                <span class="absolute left-2 top-2 rounded-full bg-ink px-2.5 py-1 text-xs font-bold text-canvas">#{{ game.rank }}</span>
              </div>
              <h3 class="mt-3 line-clamp-2 font-display text-lg font-semibold leading-6">{{ game.name }}</h3>
              <p v-if="game.nameLocalized" class="mt-1 line-clamp-1 text-xs text-ink/45">{{ game.originalName }}</p>
            </RouterLink>
            <p class="mt-2 text-xs leading-5 text-ink/55">{{ playerTimeLabel(game) || t('recommendations.unknownFit') }}</p>
            <p class="text-xs leading-5 text-ink/45">{{ t('recommendations.ratingWeight', { rating: game.averageRating?.toFixed(1) ?? '—', weight: game.averageWeight?.toFixed(1) ?? '—' }) }}</p>
            <ul v-if="game.categories.length" class="mt-3 flex flex-wrap gap-1.5" :aria-label="t('recommendations.categoriesAria')">
              <li v-for="item in game.categories.slice(0, 3)" :key="item" class="rounded-full bg-indigo/8 px-2 py-1 text-[0.68rem] font-medium text-indigo">{{ gameCategoryLabel(item) }}</li>
            </ul>
          </article>
        </div>
        <div v-else class="mt-7 rounded-2xl border border-dashed border-ink/15 bg-paper p-7 text-center">
          <h3 class="font-display text-2xl font-semibold">{{ t('recommendations.emptyTitle') }}</h3>
          <p class="mx-auto mt-2 max-w-lg text-sm leading-6 text-ink/55">{{ t('recommendations.emptyDescription') }}</p>
          <button type="button" class="mt-4 min-h-11 rounded-lg border border-ink/15 px-5 text-sm font-semibold" @click="clearFilters">{{ t('recommendations.clear') }}</button>
        </div>
      </section>

      <section v-else class="py-8" aria-live="polite">
        <button type="button" class="inline-flex min-h-11 items-center gap-2 text-sm font-semibold text-indigo" @click="returnToBrowse"><TabletopGlyph name="arrow" :size="17" class="rotate-180" /> {{ t('recommendations.backToHot') }}</button>
        <h2 class="mt-3 font-display text-3xl font-semibold">{{ t('recommendations.searchTitle', { query: submittedQuery }) }}</h2>
        <p class="mt-2 text-sm leading-6 text-ink/50">{{ t('recommendations.searchScope') }}</p>
        <div v-if="searching" class="mt-7 rounded-2xl border border-ink/10 bg-paper p-7 text-sm text-ink/55" role="status">{{ t('recommendations.searching') }}</div>
        <div v-else-if="searchFailed" class="mt-7 rounded-2xl border border-danger/20 bg-danger/5 p-7" role="alert">
          <h3 class="font-display text-2xl font-semibold">{{ t('recommendations.searchErrorTitle') }}</h3>
          <button type="button" class="mt-4 min-h-11 rounded-lg bg-ink px-5 text-sm font-semibold text-canvas" @click="searchGames">{{ t('recommendations.retry') }}</button>
        </div>
        <ol v-else-if="searchResults.length" class="mt-7 divide-y divide-ink/10 rounded-2xl border border-ink/10 bg-paper px-5">
          <li v-for="result in searchResults" :key="result.bggId" class="flex flex-col gap-3 py-5 sm:flex-row sm:items-center sm:justify-between">
            <div class="min-w-0">
              <h3 class="font-display text-xl font-semibold">{{ result.name }}</h3>
              <p class="mt-1 text-sm text-ink/45">{{ result.publicationYear ?? t('recommendations.unknownYear') }} · BGG #{{ result.bggId }}</p>
            </div>
            <RouterLink :to="{ name: 'game-discovery', params: { bggId: result.bggId } }" class="inline-flex min-h-11 shrink-0 items-center justify-center rounded-lg bg-indigo px-4 text-sm font-semibold text-white">{{ t('recommendations.inspect') }}</RouterLink>
          </li>
        </ol>
        <div v-else class="mt-7 rounded-2xl border border-dashed border-ink/15 bg-paper p-7 text-center">
          <h3 class="font-display text-2xl font-semibold">{{ t('recommendations.searchEmptyTitle') }}</h3>
          <p class="mt-2 text-sm leading-6 text-ink/55">{{ t('recommendations.searchEmptyDescription') }}</p>
        </div>
      </section>
    </div>
  </AppShell>
</template>
