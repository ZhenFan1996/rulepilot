<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import { useLocale } from '@/lib/locale'

type CatalogSort = 'hot' | 'rating' | 'rank'
type CatalogType = 'all' | 'abstract' | 'customizable' | 'children' | 'family' | 'party' | 'strategy' | 'thematic' | 'war' | 'expansion'

interface CatalogGame {
  bggId: number
  name: string
  originalName: string
  nameLocalized: boolean
  publicationYear: number | null
  overallRank: number | null
  hotRank: number | null
  geekRating: number
  averageRating: number
  usersRated: number
  expansion: boolean
  types: CatalogType[]
  detailsAvailable: boolean
  thumbnailUrl: string
  minPlayers: number | null
  maxPlayers: number | null
  playingTimeMinutes: number | null
  averageWeight: number | null
  categories: string[]
  mechanics: string[]
  bggUrl: string
}

interface CatalogResponse {
  ready: boolean
  sourceCount: number
  total: number
  page: number
  size: number
  totalPages: number
  sort: CatalogSort
  type: CatalogType
  sourceDate: string | null
  taxonomyTranslated: boolean
  games: CatalogGame[]
}

const { locale } = useLocale()
const copy = {
  'zh-CN': {
    eyebrow: 'BGG 全量桌游目录',
    title: '从整个 BGG 目录找到下一款桌游',
    description: '按当前热度、评分或总榜排名浏览，使用 BGG 类型榜筛选，也可以直接在全量快照中搜索名称。进入详情后会补齐人数、时长、机制、类型和可核验的官方中文名。',
    searchLabel: '搜索整个目录', searchPlaceholder: '输入英文名或原版名', search: '搜索', searching: '搜索中…', searchValidation: '请至少输入 2 个字符。',
    browseEyebrow: '服务端分页目录', browseTitle: '不止当前热门的 11 款',
    sortLabel: '排序', sortHot: '当前热榜优先', sortRating: '玩家评分优先', sortRank: 'BGG 总榜优先',
    typeLabel: 'BGG 类型榜', apply: '应用', clear: '重置',
    all: '全部基础游戏', abstract: '抽象策略', customizable: '可定制游戏', children: '儿童游戏', family: '家庭游戏', party: '聚会游戏', strategy: '策略游戏', thematic: '主题游戏', war: '战争游戏', expansion: '扩展',
    scope: 'BGG 快照共 {sourceCount} 条记录，当前条件匹配 {total} 条。', sourceDate: '快照日期 {date}',
    loading: '正在读取 BGG 全量目录', unavailableTitle: '全量目录还没有导入', unavailableDescription: '需要先导入 BGG 官方 boardgames_ranks.zip；系统不会用 11 款热榜数据伪装成全量目录。',
    errorTitle: '全量目录暂时不可用', errorDescription: '筛选条件仍然保留，可以稍后重试。', retry: '再试一次',
    players: '{min}–{max} 人', minutes: '约 {minutes} 分钟', rating: '玩家评分 {rating}', geekRating: 'Geek 评分 {rating}', votes: '{count} 人评分', weight: '复杂度 {weight} / 5',
    rank: '总榜 #{rank}', hotRank: '热榜 #{rank}', noRank: '尚未进入总榜', detailPending: '详细资料将在打开游戏时继续读取',
    categoriesAria: '游戏类型和机制', coverAlt: '{game} 的 BGG 封面', emptyTitle: '没有匹配的桌游', emptyDescription: '试试减少搜索词或选择其他 BGG 类型榜。',
    previous: '上一页', next: '下一页', page: '第 {page} / {pages} 页', officialSource: '数据由 BoardGameGeek 提供', taxonomyFallback: '机制和类型暂时保留 BGG 原文',
  },
  en: {
    eyebrow: 'Full BGG game catalog', title: 'Find your next game across the BGG catalog',
    description: 'Browse by current heat, rating, or overall rank, filter with BGG ranking families, or search the complete server snapshot by title. Game details add player fit, mechanisms, categories, and verified official localized titles.',
    searchLabel: 'Search the full catalog', searchPlaceholder: 'Enter a title or original name', search: 'Search', searching: 'Searching…', searchValidation: 'Enter at least 2 characters.',
    browseEyebrow: 'Server-paginated catalog', browseTitle: 'Beyond the current 11 hot games',
    sortLabel: 'Sort', sortHot: 'Current heat first', sortRating: 'Player rating first', sortRank: 'BGG rank first', typeLabel: 'BGG ranking family', apply: 'Apply', clear: 'Reset',
    all: 'All base games', abstract: 'Abstract', customizable: 'Customizable', children: "Children's", family: 'Family', party: 'Party', strategy: 'Strategy', thematic: 'Thematic', war: 'War', expansion: 'Expansions',
    scope: 'The BGG snapshot contains {sourceCount} records; {total} match these filters.', sourceDate: 'Snapshot dated {date}', loading: 'Loading the full BGG catalog',
    unavailableTitle: 'The full catalog has not been imported', unavailableDescription: 'Import the official BGG boardgames_ranks.zip first. RulePilot will not present an 11-game hot list as a full catalog.',
    errorTitle: 'The full catalog is unavailable', errorDescription: 'Your filters are still here. Try again later.', retry: 'Try again',
    players: '{min}–{max} players', minutes: 'About {minutes} min', rating: 'Player rating {rating}', geekRating: 'Geek rating {rating}', votes: '{count} ratings', weight: 'Complexity {weight} / 5',
    rank: 'Overall #{rank}', hotRank: 'Hot #{rank}', noRank: 'Not yet ranked', detailPending: 'Rich details will continue loading when you open the game', categoriesAria: 'Game types and mechanisms', coverAlt: '{game} BGG cover',
    emptyTitle: 'No games match', emptyDescription: 'Try fewer title words or another BGG ranking family.', previous: 'Previous', next: 'Next', page: 'Page {page} of {pages}', officialSource: 'Data provided by BoardGameGeek', taxonomyFallback: 'Showing BGG source taxonomy',
  },
} as const
type CopyKey = keyof typeof copy['zh-CN']

function t(key: CopyKey, parameters: Record<string, string | number> = {}) {
  return copy[locale.value][key].replace(/\{(\w+)\}/g, (placeholder, name: string) =>
    parameters[name] === undefined ? placeholder : String(parameters[name]))
}

const typeOptions: CatalogType[] = ['all', 'abstract', 'customizable', 'children', 'family', 'party', 'strategy', 'thematic', 'war', 'expansion']
const games = ref<CatalogGame[]>([])
const ready = ref(false)
const sourceCount = ref(0)
const total = ref(0)
const totalPages = ref(0)
const sourceDate = ref<string | null>(null)
const taxonomyTranslated = ref(false)
const loading = ref(true)
const loadFailed = ref(false)
const sort = ref<CatalogSort>('hot')
const type = ref<CatalogType>('all')
const page = ref(0)
const searchQuery = ref('')
const submittedQuery = ref('')
const searchValidation = ref(false)
let requestSequence = 0

const filterActive = computed(() => sort.value !== 'hot' || type.value !== 'all' || Boolean(submittedQuery.value))

async function loadCatalog() {
  const request = ++requestSequence
  loading.value = true
  loadFailed.value = false
  const parameters = new URLSearchParams({ sort: sort.value, type: type.value, page: String(page.value), size: '20', locale: locale.value })
  if (submittedQuery.value) parameters.set('q', submittedQuery.value)
  try {
    const response = await fetch(`/api/v1/bgg/catalog?${parameters.toString()}`, { credentials: 'include' })
    if (!response.ok) throw new Error('catalog unavailable')
    const data = await response.json() as CatalogResponse
    if (request !== requestSequence) return
    ready.value = data.ready
    games.value = data.games
    sourceCount.value = data.sourceCount
    total.value = data.total
    totalPages.value = data.totalPages
    sourceDate.value = data.sourceDate
    taxonomyTranslated.value = data.taxonomyTranslated
  } catch {
    if (request !== requestSequence) return
    games.value = []
    loadFailed.value = true
  } finally {
    if (request === requestSequence) loading.value = false
  }
}

function applyFilters() {
  page.value = 0
  void loadCatalog()
}

function searchGames() {
  const checked = searchQuery.value.trim().replace(/\s+/g, ' ')
  searchValidation.value = checked.length > 0 && checked.length < 2
  if (searchValidation.value) return
  submittedQuery.value = checked
  page.value = 0
  void loadCatalog()
}

function clearFilters() {
  sort.value = 'hot'
  type.value = 'all'
  page.value = 0
  searchQuery.value = ''
  submittedQuery.value = ''
  searchValidation.value = false
  void loadCatalog()
}

function changePage(nextPage: number) {
  if (nextPage < 0 || nextPage >= totalPages.value || loading.value) return
  page.value = nextPage
  void loadCatalog()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function playerTime(game: CatalogGame) {
  const values: string[] = []
  if (game.minPlayers !== null && game.maxPlayers !== null) values.push(t('players', { min: game.minPlayers, max: game.maxPlayers }))
  if (game.playingTimeMinutes !== null) values.push(t('minutes', { minutes: game.playingTimeMinutes }))
  return values.join(' · ')
}

function hideBrokenImage(event: Event) {
  ;(event.currentTarget as HTMLImageElement).hidden = true
}

onMounted(loadCatalog)
watch(locale, () => void loadCatalog())
</script>

<template>
  <AppShell>
    <main class="mx-auto max-w-7xl px-5 py-8 sm:px-8 sm:py-12 lg:px-12">
      <header class="grid gap-6 border-b border-ink/10 pb-8 lg:grid-cols-[1fr_22rem] lg:items-end">
        <div class="max-w-3xl">
          <p class="text-sm font-semibold text-copper">{{ t('eyebrow') }}</p>
          <h1 class="mt-2 font-display text-4xl font-semibold tracking-[-0.03em] sm:text-5xl">{{ t('title') }}</h1>
          <p class="mt-4 max-w-3xl text-base leading-8 text-ink/60">{{ t('description') }}</p>
        </div>
        <form class="rounded-2xl border border-ink/10 bg-paper p-4 shadow-sm" role="search" @submit.prevent="searchGames">
          <label for="bgg-catalog-search" class="text-xs font-bold uppercase tracking-[0.12em] text-ink/55">{{ t('searchLabel') }}</label>
          <div class="mt-2 flex gap-2">
            <input id="bgg-catalog-search" v-model="searchQuery" type="search" maxlength="120" :placeholder="t('searchPlaceholder')" class="min-h-12 min-w-0 flex-1 rounded-xl border border-ink/15 bg-canvas px-3 text-sm outline-none focus:border-copper">
            <button type="submit" :disabled="loading" class="min-h-12 shrink-0 rounded-xl bg-indigo px-4 text-sm font-semibold text-white disabled:opacity-50">{{ loading ? t('searching') : t('search') }}</button>
          </div>
          <p v-if="searchValidation" class="mt-2 text-xs text-danger" role="alert">{{ t('searchValidation') }}</p>
        </form>
      </header>

      <section class="py-8">
        <div class="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <p class="text-sm font-semibold text-copper">{{ t('browseEyebrow') }}</p>
            <h2 class="mt-1 font-display text-3xl font-semibold">{{ t('browseTitle') }}</h2>
          </div>
          <a href="https://boardgamegeek.com" target="_blank" rel="noopener noreferrer" class="inline-flex min-h-12 items-center" :aria-label="t('officialSource')">
            <img src="/powered-by-bgg-rgb.svg" alt="Powered by BoardGameGeek" class="h-auto w-[171px]" width="342" height="76">
          </a>
        </div>

        <form class="mt-6 grid gap-3 rounded-2xl border border-ink/10 bg-paper p-4 sm:grid-cols-[1fr_1fr_auto] sm:items-end" @submit.prevent="applyFilters">
          <label class="grid gap-1.5 text-xs font-semibold text-ink/60">
            {{ t('sortLabel') }}
            <select v-model="sort" class="min-h-11 rounded-lg border border-ink/15 bg-canvas px-3 text-sm outline-none focus:border-copper">
              <option value="hot">{{ t('sortHot') }}</option><option value="rating">{{ t('sortRating') }}</option><option value="rank">{{ t('sortRank') }}</option>
            </select>
          </label>
          <label class="grid gap-1.5 text-xs font-semibold text-ink/60">
            {{ t('typeLabel') }}
            <select v-model="type" class="min-h-11 rounded-lg border border-ink/15 bg-canvas px-3 text-sm outline-none focus:border-copper">
              <option v-for="item in typeOptions" :key="item" :value="item">{{ t(item) }}</option>
            </select>
          </label>
          <div class="flex gap-2">
            <button type="submit" :disabled="loading" class="min-h-11 rounded-lg bg-ink px-5 text-sm font-semibold text-canvas disabled:opacity-50">{{ t('apply') }}</button>
            <button v-if="filterActive" type="button" class="min-h-11 rounded-lg border border-ink/15 px-4 text-sm font-semibold text-ink/60" @click="clearFilters">{{ t('clear') }}</button>
          </div>
        </form>

        <p v-if="ready" class="mt-4 text-sm text-ink/50">
          {{ t('scope', { sourceCount: sourceCount.toLocaleString(), total: total.toLocaleString() }) }}
          <span v-if="sourceDate"> · {{ t('sourceDate', { date: sourceDate }) }}</span>
          <span v-if="locale === 'zh-CN' && !taxonomyTranslated"> · {{ t('taxonomyFallback') }}</span>
        </p>

        <div v-if="loading" class="mt-7 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4" :aria-label="t('loading')">
          <div v-for="index in 8" :key="index" class="animate-pulse rounded-2xl border border-ink/10 bg-paper p-3"><div class="aspect-[4/3] rounded-xl bg-ink/8" /><div class="mt-3 h-4 w-2/3 rounded bg-ink/8" /></div>
        </div>
        <div v-else-if="loadFailed" class="mt-7 rounded-2xl border border-danger/20 bg-danger/5 p-6" role="alert">
          <h3 class="font-display text-2xl font-semibold">{{ t('errorTitle') }}</h3><p class="mt-2 text-sm text-ink/60">{{ t('errorDescription') }}</p><button type="button" class="mt-4 min-h-11 rounded-lg bg-ink px-5 text-sm font-semibold text-canvas" @click="loadCatalog">{{ t('retry') }}</button>
        </div>
        <div v-else-if="!ready" class="mt-7 rounded-2xl border border-copper/25 bg-copper/5 p-7" role="status">
          <h3 class="font-display text-2xl font-semibold">{{ t('unavailableTitle') }}</h3><p class="mt-2 max-w-2xl text-sm leading-6 text-ink/60">{{ t('unavailableDescription') }}</p>
        </div>
        <div v-else-if="games.length" class="mt-7 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
          <article v-for="game in games" :key="game.bggId" class="group min-w-0 rounded-2xl border border-ink/10 bg-paper p-3 shadow-sm transition hover:-translate-y-1 hover:shadow-lg">
            <RouterLink :to="{ name: 'game-discovery', params: { bggId: game.bggId } }" class="block">
              <div class="relative flex aspect-[4/3] items-center justify-center overflow-hidden rounded-xl bg-canvas p-3 text-ink/25">
                <img v-if="game.thumbnailUrl" :src="game.thumbnailUrl" :alt="t('coverAlt', { game: game.name })" loading="lazy" class="h-full w-full object-contain" @error="hideBrokenImage">
                <TabletopGlyph v-else name="cards" :size="48" />
                <span v-if="game.hotRank" class="absolute left-2 top-2 rounded-full bg-copper px-2.5 py-1 text-xs font-bold text-white">{{ t('hotRank', { rank: game.hotRank }) }}</span>
              </div>
              <h3 class="mt-3 line-clamp-2 font-display text-lg font-semibold leading-6">{{ game.name }}</h3>
              <p v-if="game.nameLocalized" class="mt-1 line-clamp-1 text-xs text-ink/45">{{ game.originalName }}</p>
            </RouterLink>
            <p class="mt-2 text-xs leading-5 text-ink/55">{{ game.overallRank ? t('rank', { rank: game.overallRank }) : t('noRank') }} · {{ t('rating', { rating: game.averageRating.toFixed(2) }) }}</p>
            <p class="text-xs leading-5 text-ink/45">{{ t('geekRating', { rating: game.geekRating.toFixed(2) }) }} · {{ t('votes', { count: game.usersRated.toLocaleString() }) }}</p>
            <p v-if="playerTime(game)" class="mt-1 text-xs leading-5 text-ink/55">{{ playerTime(game) }}<span v-if="game.averageWeight !== null"> · {{ t('weight', { weight: game.averageWeight.toFixed(1) }) }}</span></p>
            <p v-else class="mt-1 text-xs leading-5 text-ink/40">{{ t('detailPending') }}</p>
            <ul v-if="game.categories.length || game.mechanics.length" class="mt-3 flex flex-wrap gap-1.5" :aria-label="t('categoriesAria')">
              <li v-for="item in [...game.categories, ...game.mechanics].slice(0, 3)" :key="item" class="rounded-full bg-indigo/8 px-2 py-1 text-[0.68rem] font-medium text-indigo">{{ item }}</li>
            </ul>
          </article>
        </div>
        <div v-else class="mt-7 rounded-2xl border border-dashed border-ink/15 bg-paper p-7 text-center"><h3 class="font-display text-2xl font-semibold">{{ t('emptyTitle') }}</h3><p class="mt-2 text-sm text-ink/55">{{ t('emptyDescription') }}</p><button type="button" class="mt-4 min-h-11 rounded-lg border border-ink/15 px-5 text-sm font-semibold" @click="clearFilters">{{ t('clear') }}</button></div>

        <nav v-if="ready && totalPages > 1" class="mt-8 flex items-center justify-center gap-3" :aria-label="t('page', { page: page + 1, pages: totalPages })">
          <button type="button" :disabled="page === 0 || loading" class="min-h-11 rounded-lg border border-ink/15 px-4 text-sm font-semibold disabled:opacity-35" @click="changePage(page - 1)">{{ t('previous') }}</button>
          <span class="text-sm text-ink/55">{{ t('page', { page: page + 1, pages: totalPages }) }}</span>
          <button type="button" :disabled="page + 1 >= totalPages || loading" class="min-h-11 rounded-lg border border-ink/15 px-4 text-sm font-semibold disabled:opacity-35" @click="changePage(page + 1)">{{ t('next') }}</button>
        </nav>
      </section>
    </main>
  </AppShell>
</template>
