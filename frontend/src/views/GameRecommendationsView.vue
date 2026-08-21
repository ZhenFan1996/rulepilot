<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import { useLocale, type AppLocale } from '@/lib/locale'

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

interface CatalogCover {
  bggId: number
  thumbnailUrl: string
  imageUrl: string
}

const { locale } = useLocale()
const route = useRoute()
const router = useRouter()
const copy = {
  'zh-CN': {
    eyebrow: '桌游目录',
    title: '按自己的节奏慢慢挑',
    description: '搜索 BGG 收录的桌游，按热度、玩家评分和细分类型浏览。只有 BGG 版本资料明确收录的官方中文名才会显示为中文。',
    assistant: '让推荐助手帮我挑', assistantDescription: '如果还没有明确目标，可以回到对话里说人数、时间和想要的感觉。',
    searchLabel: '搜索桌游', searchPlaceholder: '输入桌游名或原版名', search: '搜索', searching: '搜索中…',
    browseEyebrow: '游戏目录', browseTitle: '浏览全部桌游',
    sortLabel: '排序', sortHot: '当前热榜优先', sortRating: '玩家评分优先', sortRank: 'BGG 总榜优先',
    typeLabel: 'BGG 类型榜', apply: '应用', clear: '重置',
    all: '全部基础游戏', abstract: '抽象策略', customizable: '可定制游戏', children: '儿童游戏', family: '家庭游戏', party: '聚会游戏', strategy: '策略游戏', thematic: '主题游戏', war: '战争游戏', expansion: '扩展',
    scope: 'BGG 收录 {sourceCount} 条，当前找到 {total} 条。', sourceDate: '资料更新于 {date}',
    loading: '正在读取 BGG 桌游目录', unavailableTitle: '桌游目录还在准备', unavailableDescription: '暂时可以继续使用推荐对话、个人游戏和规则书功能。',
    errorTitle: '桌游目录暂时打不开', errorDescription: '筛选条件已经保留，可以稍后重试。', retry: '再试一次', pageError: '这一页暂时没取到，当前页仍然保留。', retryPage: '重试这一页',
    players: '{min}–{max} 人', minutes: '约 {minutes} 分钟', rating: '玩家评分 {rating}', geekRating: 'Geek 评分 {rating}', votes: '{count} 人评分', weight: '复杂度 {weight} / 5',
    rank: '总榜 #{rank}', hotRank: '热榜 #{rank}', noRank: '尚未进入总榜', detailPending: '详细资料将在打开游戏时继续读取',
    coverAlt: '{game} 的 BGG 封面', emptyTitle: '没有匹配的桌游', emptyDescription: '试试减少搜索词或选择其他 BGG 类型榜。',
    shown: '本页 {shown} 款', officialSource: '数据由 BoardGameGeek 提供',
    pagination: '桌游目录分页', pageSummary: '第 {current} / {total} 页', previousPage: '上一页', nextPage: '下一页', goToPage: '前往第 {page} 页',
  },
  en: {
    eyebrow: 'Game catalog', title: 'Browse at your own pace',
    description: 'Search BGG games and browse by heat, player rating, and detailed type. Localized titles appear only when an official BGG edition records them.',
    assistant: 'Ask the recommendation assistant', assistantDescription: 'If you do not have a precise target yet, return to the conversation and share the group, time, and mood.',
    searchLabel: 'Search the full catalog', searchPlaceholder: 'Enter a title or original name', search: 'Search', searching: 'Searching…',
    browseEyebrow: 'Game catalog', browseTitle: 'Browse every game',
    sortLabel: 'Sort', sortHot: 'Current heat first', sortRating: 'Player rating first', sortRank: 'BGG rank first', typeLabel: 'BGG ranking family', apply: 'Apply', clear: 'Reset',
    all: 'All base games', abstract: 'Abstract', customizable: 'Customizable', children: "Children's", family: 'Family', party: 'Party', strategy: 'Strategy', thematic: 'Thematic', war: 'War', expansion: 'Expansions',
    scope: 'BGG lists {sourceCount} records; {total} match these filters.', sourceDate: 'Updated {date}', loading: 'Loading the BGG catalog',
    unavailableTitle: 'The game catalog is still being prepared', unavailableDescription: 'Recommendations, your games, and rulebooks are still available.',
    errorTitle: 'The game catalog is unavailable', errorDescription: 'Your filters are still here. Try again later.', retry: 'Try again', pageError: 'This page could not be loaded. Your current page is still here.', retryPage: 'Retry this page',
    players: '{min}–{max} players', minutes: 'About {minutes} min', rating: 'Player rating {rating}', geekRating: 'Geek rating {rating}', votes: '{count} ratings', weight: 'Complexity {weight} / 5',
    rank: 'Overall #{rank}', hotRank: 'Hot #{rank}', noRank: 'Not yet ranked', detailPending: 'Rich details will continue loading when you open the game', coverAlt: '{game} BGG cover',
    emptyTitle: 'No games match', emptyDescription: 'Try fewer title words or another BGG ranking family.', shown: '{shown} games on this page', officialSource: 'Data provided by BoardGameGeek',
    pagination: 'Game catalog pages', pageSummary: 'Page {current} of {total}', previousPage: 'Previous', nextPage: 'Next', goToPage: 'Go to page {page}',
  },
} as const
type CopyKey = keyof typeof copy['zh-CN']

function t(key: CopyKey, parameters: Record<string, string | number> = {}) {
  return copy[locale.value][key].replace(/\{(\w+)\}/g, (placeholder, name: string) =>
    parameters[name] === undefined ? placeholder : String(parameters[name]))
}

const typeOptions: CatalogType[] = ['all', 'abstract', 'customizable', 'children', 'family', 'party', 'strategy', 'thematic', 'war', 'expansion']
const catalogSorts = new Set<CatalogSort>(['hot', 'rating', 'rank'])
const catalogTypes = new Set<CatalogType>(typeOptions)
const games = ref<CatalogGame[]>([])
const ready = ref(false)
const sourceCount = ref(0)
const total = ref(0)
const totalPages = ref(0)
const sourceDate = ref<string | null>(null)
const loading = ref(true)
const loadFailed = ref(false)
const failedPage = ref<number | null>(null)
const sort = ref<CatalogSort>('rank')
const type = ref<CatalogType>('all')
const page = ref(0)
const searchQuery = ref('')
const submittedQuery = ref('')
let queryGeneration = 0
let disposed = false
let activeQuery: CatalogQuery | null = null
let activeBaseRequest: { generation: number; controller: AbortController } | null = null
let activeCoverRequest: { generation: number; controller: AbortController } | null = null
const prefetches = new Map<string, PrefetchRecord>()

interface PaginationItem {
  key: string
  page: number | null
  label: string
}

const paginationItems = computed<PaginationItem[]>(() => {
  if (totalPages.value <= 1) return []
  const selected = new Set<number>([0, totalPages.value - 1])
  for (let candidate = page.value - 1; candidate <= page.value + 1; candidate++) {
    if (candidate >= 0 && candidate < totalPages.value) selected.add(candidate)
  }
  if (page.value <= 2) {
    for (let candidate = 0; candidate < Math.min(4, totalPages.value); candidate++) selected.add(candidate)
  }
  if (page.value >= totalPages.value - 3) {
    for (let candidate = Math.max(0, totalPages.value - 4); candidate < totalPages.value; candidate++) {
      selected.add(candidate)
    }
  }
  const ordered = [...selected].sort((left, right) => left - right)
  const items: PaginationItem[] = []
  ordered.forEach((candidate, index) => {
    const previous = ordered[index - 1]
    if (previous !== undefined && candidate - previous > 1) {
      items.push({ key: `gap-${previous}-${candidate}`, page: null, label: '…' })
    }
    items.push({ key: `page-${candidate}`, page: candidate, label: String(candidate + 1) })
  })
  return items
})

interface CatalogQuery {
  sort: CatalogSort
  type: CatalogType
  search: string
  locale: AppLocale
}

interface PrefetchRecord {
  generation: number
  controller: AbortController
  promise: Promise<CatalogResponse | null>
}

const filterActive = computed(() => sort.value !== 'rank' || type.value !== 'all' || Boolean(submittedQuery.value))

function querySnapshot(): CatalogQuery {
  return { sort: sort.value, type: type.value, search: submittedQuery.value, locale: locale.value }
}

function firstQueryValue(value: unknown) {
  return Array.isArray(value) ? value[0] : value
}

function normalizedSearch(value: unknown) {
  return typeof value === 'string' ? value.trim().replace(/\s+/g, ' ') : ''
}

function routeCatalogState() {
  const routeSort = firstQueryValue(route.query.sort)
  const routeType = firstQueryValue(route.query.type)
  const routeSearch = normalizedSearch(firstQueryValue(route.query.q))
  const routePage = Number.parseInt(String(firstQueryValue(route.query.page) ?? '1'), 10)
  return {
    sort: typeof routeSort === 'string' && catalogSorts.has(routeSort as CatalogSort) ? routeSort as CatalogSort : 'rank',
    type: typeof routeType === 'string' && catalogTypes.has(routeType as CatalogType) ? routeType as CatalogType : 'all',
    search: routeSearch,
    page: Number.isSafeInteger(routePage) && routePage > 0 ? routePage - 1 : 0,
  }
}

function syncRoute(pageNumber: number) {
  const query: Record<string, string> = {}
  if (sort.value !== 'rank') query.sort = sort.value
  if (type.value !== 'all') query.type = type.value
  if (submittedQuery.value) query.q = submittedQuery.value
  if (pageNumber > 0) query.page = String(pageNumber + 1)
  const current = routeCatalogState()
  if (current.sort === sort.value
    && current.type === type.value
    && current.search === submittedQuery.value
    && current.page === pageNumber) return
  void router.replace({ query })
}

function catalogRequest(query: CatalogQuery, pageNumber: number) {
  const parameters = new URLSearchParams({ sort: query.sort, type: query.type, page: String(pageNumber), size: '20', locale: query.locale, enrich: 'false' })
  if (query.search) parameters.set('q', query.search)
  return `/api/v1/bgg/catalog?${parameters.toString()}`
}

function updateSummary(data: CatalogResponse) {
  ready.value = data.ready
  sourceCount.value = data.sourceCount
  total.value = data.total
  totalPages.value = data.totalPages
  sourceDate.value = data.sourceDate
}

function isExpectedPage(data: CatalogResponse, query: CatalogQuery, pageNumber: number) {
  return data.page === pageNumber && data.sort === query.sort && data.type === query.type
}

function isAbortError(error: unknown) {
  return error instanceof Error && error.name === 'AbortError'
}

function isCurrentQuery(generation: number, query: CatalogQuery) {
  return !disposed && generation === queryGeneration && activeQuery === query
}

function cancelQueryWork() {
  activeBaseRequest?.controller.abort()
  activeBaseRequest = null
  activeCoverRequest?.controller.abort()
  activeCoverRequest = null
  prefetches.forEach(entry => entry.controller.abort())
  prefetches.clear()
}

function beginReplacementQuery(query: CatalogQuery, targetPage: number) {
  queryGeneration += 1
  cancelQueryWork()
  activeQuery = query
  games.value = []
  ready.value = false
  sourceCount.value = 0
  total.value = 0
  totalPages.value = 0
  sourceDate.value = null
  page.value = targetPage
  loadFailed.value = false
  failedPage.value = null
  return queryGeneration
}

function preparePageNavigation() {
  activeBaseRequest?.controller.abort()
  activeBaseRequest = null
  activeCoverRequest?.controller.abort()
  activeCoverRequest = null
  loadFailed.value = false
  failedPage.value = null
}

function prefetchNextPage(generation: number, query: CatalogQuery, data: CatalogResponse) {
  const nextPage = data.page + 1
  if (nextPage >= data.totalPages || !isCurrentQuery(generation, query)) return
  const url = catalogRequest(query, nextPage)
  if (prefetches.has(url)) return
  const controller = new AbortController()
  const promise = fetch(url, { credentials: 'include', signal: controller.signal })
    .then(async response => response.ok ? await response.json() as CatalogResponse : null)
    .catch(() => null)
  prefetches.set(url, { generation, controller, promise })
}

async function loadBasePage(generation: number, query: CatalogQuery, pageNumber: number) {
  const url = catalogRequest(query, pageNumber)
  const prefetched = prefetches.get(url)
  if (prefetched?.generation === generation) {
    prefetches.delete(url)
    activeBaseRequest = { generation, controller: prefetched.controller }
    const data = await prefetched.promise
    if (data && isExpectedPage(data, query, pageNumber)) return data
    if (!isCurrentQuery(generation, query)) throw new DOMException('Aborted', 'AbortError')
  }
  const controller = new AbortController()
  activeBaseRequest = { generation, controller }
  const response = await fetch(url, { credentials: 'include', signal: controller.signal })
  if (!response.ok) throw new Error('catalog unavailable')
  const data = await response.json() as CatalogResponse
  if (!isExpectedPage(data, query, pageNumber)) throw new Error('catalog response identity mismatch')
  return data
}

async function enrichMissingCovers(generation: number, query: CatalogQuery, pageNumber: number, pageGames: CatalogGame[]) {
  const missingIds = pageGames.filter(game => !game.thumbnailUrl).map(game => game.bggId)
  if (!missingIds.length || !isCurrentQuery(generation, query)) return
  const controller = new AbortController()
  activeCoverRequest?.controller.abort()
  activeCoverRequest = { generation, controller }
  const parameters = new URLSearchParams()
  missingIds.forEach(id => parameters.append('bggId', String(id)))
  try {
    const response = await fetch(`/api/v1/bgg/catalog/covers?${parameters.toString()}`, {
      credentials: 'include',
      signal: controller.signal,
    })
    if (!response.ok) return
    const covers = await response.json() as CatalogCover[]
    if (!isCurrentQuery(generation, query) || page.value !== pageNumber || activeCoverRequest?.controller !== controller) return
    const byId = new Map(covers.map(cover => [cover.bggId, cover]))
    games.value = games.value.map(game => {
      const cover = byId.get(game.bggId)
      return cover ? { ...game, thumbnailUrl: cover.thumbnailUrl || cover.imageUrl } : game
    })
  } catch (error) {
    if (!isAbortError(error)) return
  } finally {
    if (activeCoverRequest?.controller === controller) activeCoverRequest = null
  }
}

async function loadPage(generation: number, query: CatalogQuery, targetPage: number) {
  loading.value = true
  loadFailed.value = false
  failedPage.value = null
  try {
    const data = await loadBasePage(generation, query, targetPage)
    if (!isCurrentQuery(generation, query)) return
    updateSummary(data)
    page.value = data.page
    games.value = data.games
    loading.value = false
    void enrichMissingCovers(generation, query, data.page, data.games)
    prefetchNextPage(generation, query, data)
    syncRoute(data.page)
  } catch (error) {
    if (isAbortError(error) || !isCurrentQuery(generation, query)) return
    loadFailed.value = true
    failedPage.value = targetPage
  } finally {
    if (isCurrentQuery(generation, query)) loading.value = false
    if (activeBaseRequest?.generation === generation) activeBaseRequest = null
  }
}

async function replaceCatalog(targetPage = 0) {
  const query = querySnapshot()
  const generation = beginReplacementQuery(query, targetPage)
  await loadPage(generation, query, targetPage)
}

async function navigateToPage(targetPage: number) {
  if (loading.value || targetPage < 0 || targetPage >= totalPages.value || targetPage === page.value) return
  const query = activeQuery ?? querySnapshot()
  const generation = activeQuery ? queryGeneration : beginReplacementQuery(query, targetPage)
  if (activeQuery) preparePageNavigation()
  await loadPage(generation, query, targetPage)
}

function retryFailedPage() {
  const targetPage = failedPage.value
  if (targetPage === null) return
  if (games.value.length) void navigateToPage(targetPage)
  else void replaceCatalog(targetPage)
}

function applyFilters() {
  void replaceCatalog(0)
}

function searchGames() {
  const checked = normalizedSearch(searchQuery.value)
  submittedQuery.value = checked
  void replaceCatalog(0)
}

function clearFilters() {
  sort.value = 'rank'
  type.value = 'all'
  searchQuery.value = ''
  submittedQuery.value = ''
  void replaceCatalog(0)
}

function playerTime(game: CatalogGame) {
  const values: string[] = []
  if (game.minPlayers !== null && game.maxPlayers !== null) values.push(t('players', { min: game.minPlayers, max: game.maxPlayers }))
  if (game.playingTimeMinutes !== null) values.push(t('minutes', { minutes: game.playingTimeMinutes }))
  return values.join(' · ')
}

function coverImageUrl(game: CatalogGame) {
  return `/api/v1/bgg/catalog/covers/${game.bggId}/image`
}

function hideBrokenImage(game: CatalogGame) {
  games.value = games.value.map(candidate => candidate.bggId === game.bggId ? { ...candidate, thumbnailUrl: '' } : candidate)
}

onMounted(() => {
  const state = routeCatalogState()
  sort.value = state.sort
  type.value = state.type
  searchQuery.value = state.search
  submittedQuery.value = state.search
  void replaceCatalog(state.page)
})
watch(locale, () => void replaceCatalog(page.value))
watch(() => route.fullPath, () => {
  if (disposed) return
  const state = routeCatalogState()
  if (state.sort === sort.value
    && state.type === type.value
    && state.search === submittedQuery.value
    && state.page === page.value) return
  sort.value = state.sort
  type.value = state.type
  searchQuery.value = state.search
  submittedQuery.value = state.search
  void replaceCatalog(state.page)
})
onBeforeUnmount(() => {
  disposed = true
  queryGeneration += 1
  cancelQueryWork()
})
</script>

<template>
  <AppShell>
    <div class="tabletop-page">
      <header class="grid gap-7 pb-8 xl:grid-cols-[minmax(0,1fr)_20rem] xl:items-end">
        <div class="tabletop-heading">
          <p class="tabletop-kicker">{{ t('eyebrow') }}</p>
          <h1 class="tabletop-title">{{ t('title') }}</h1>
          <p class="tabletop-lede">{{ t('description') }}</p>
        </div>
        <RouterLink :to="{ name: 'game-recommendations' }" class="game-tile player-board p-5">
          <strong class="font-display text-xl">{{ t('assistant') }}</strong>
          <span class="mt-2 block text-xs leading-5 text-ink/55">{{ t('assistantDescription') }}</span>
          <span class="mt-3 block text-sm font-semibold text-felt">← {{ t('assistant') }}</span>
        </RouterLink>
      </header>

      <section id="game-catalog" class="scroll-mt-6 border-t border-ink/10 pt-8">
        <div class="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <p class="tabletop-kicker">{{ t('browseEyebrow') }}</p>
            <h2 class="mt-1 font-display text-3xl font-semibold">{{ t('browseTitle') }}</h2>
          </div>
          <a href="https://boardgamegeek.com" target="_blank" rel="noopener noreferrer" class="inline-flex min-h-12 items-center" :aria-label="t('officialSource')">
            <img src="/powered-by-bgg-rgb.svg" alt="Powered by BoardGameGeek" class="h-auto w-[171px]" width="342" height="76">
          </a>
        </div>

        <div class="tabletop-panel player-board mt-6 grid gap-4 p-5 lg:grid-cols-[minmax(18rem,1.4fr)_minmax(11rem,0.7fr)_minmax(11rem,0.7fr)_auto] lg:items-end">
          <form class="lg:contents" role="search" @submit.prevent="searchGames">
            <label class="grid gap-2 text-xs font-semibold text-ink/55" for="bgg-catalog-search">{{ t('searchLabel') }}
              <input id="bgg-catalog-search" v-model="searchQuery" type="search" :placeholder="t('searchPlaceholder')" class="min-h-12 min-w-0 rounded-xl border border-ink/15 bg-canvas px-4 text-sm font-normal outline-none focus:border-copper">
            </label>
            <button type="submit" :disabled="loading" class="min-h-12 rounded-xl bg-felt px-5 text-sm font-semibold text-white disabled:opacity-50 lg:order-last">{{ t('search') }}</button>
          </form>
          <form data-testid="catalog-filter-form" class="contents" @submit.prevent="applyFilters">
            <label class="grid gap-2 text-xs font-semibold text-ink/55">{{ t('sortLabel') }}
              <select v-model="sort" class="min-h-12 rounded-xl border border-ink/15 bg-canvas px-3 text-sm font-normal outline-none" @change="applyFilters"><option value="hot">{{ t('sortHot') }}</option><option value="rating">{{ t('sortRating') }}</option><option value="rank">{{ t('sortRank') }}</option></select>
            </label>
            <label class="grid gap-2 text-xs font-semibold text-ink/55">{{ t('typeLabel') }}
              <select v-model="type" class="min-h-12 rounded-xl border border-ink/15 bg-canvas px-3 text-sm font-normal outline-none" @change="applyFilters"><option v-for="item in typeOptions" :key="item" :value="item">{{ t(item) }}</option></select>
            </label>
            <button type="submit" :disabled="loading" class="sr-only">{{ t('apply') }}</button>
          </form>
        </div>
        <button v-if="filterActive" type="button" class="mt-4 min-h-11 rounded-lg border border-ink/15 px-4 text-sm font-semibold text-ink/60" @click="clearFilters">{{ t('clear') }}</button>

        <p v-if="ready" class="mt-4 text-sm text-ink/50">
          {{ t('scope', { sourceCount: sourceCount.toLocaleString(), total: total.toLocaleString() }) }}
          <span v-if="sourceDate"> · {{ t('sourceDate', { date: sourceDate }) }}</span>
        </p>

        <div v-if="loading && !games.length" class="mt-7 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4" :aria-label="t('loading')">
          <div v-for="index in 8" :key="index" class="animate-pulse rounded-2xl border border-ink/10 bg-paper p-3"><div class="aspect-[4/3] rounded-xl bg-ink/8" /><div class="mt-3 h-4 w-2/3 rounded bg-ink/8" /></div>
        </div>
        <div v-else-if="loadFailed && !games.length" class="mt-7 rounded-2xl border border-danger/20 bg-danger/5 p-6" role="alert">
          <h3 class="font-display text-2xl font-semibold">{{ t('errorTitle') }}</h3><p class="mt-2 text-sm text-ink/60">{{ t('errorDescription') }}</p><button type="button" class="mt-4 min-h-11 rounded-lg bg-ink px-5 text-sm font-semibold text-canvas" @click="retryFailedPage">{{ t('retry') }}</button>
        </div>
        <div v-else-if="!ready" class="mt-7 rounded-2xl border border-copper/25 bg-copper/5 p-7" role="status">
          <h3 class="font-display text-2xl font-semibold">{{ t('unavailableTitle') }}</h3><p class="mt-2 max-w-2xl text-sm leading-6 text-ink/60">{{ t('unavailableDescription') }}</p>
        </div>
        <TransitionGroup v-if="games.length" tag="div" name="tile" class="mt-7 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4" :class="loading ? 'opacity-70' : ''">
          <article v-for="(game, index) in games" :key="game.bggId" class="game-tile group min-w-0 p-3">
            <RouterLink :to="{ name: 'game-discovery', params: { bggId: game.bggId } }" class="block">
              <div class="relative flex aspect-[4/3] items-center justify-center overflow-hidden rounded-lg border border-ink/6 bg-canvas p-3 text-ink/25">
                <img v-if="game.thumbnailUrl" :key="`${game.bggId}-${game.thumbnailUrl}`" :src="coverImageUrl(game)" :alt="t('coverAlt', { game: game.name })" :loading="index < 4 ? 'eager' : 'lazy'" :fetchpriority="index < 4 ? 'high' : 'auto'" decoding="async" class="h-full w-full object-contain" @error="hideBrokenImage(game)">
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
          </article>
        </TransitionGroup>
        <div v-else-if="ready && !loading" class="mt-7 rounded-2xl border border-dashed border-ink/15 bg-paper p-7 text-center"><h3 class="font-display text-2xl font-semibold">{{ t('emptyTitle') }}</h3><p class="mt-2 text-sm text-ink/55">{{ t('emptyDescription') }}</p><button type="button" class="mt-4 min-h-11 rounded-lg border border-ink/15 px-5 text-sm font-semibold" @click="clearFilters">{{ t('clear') }}</button></div>

        <nav v-if="ready && games.length" data-testid="catalog-pagination" class="player-board mt-8 flex flex-col gap-4 rounded-2xl border border-ink/10 bg-paper/80 px-4 py-5 sm:px-6" :aria-label="t('pagination')">
          <div class="flex flex-col gap-1 text-center sm:flex-row sm:items-center sm:justify-between sm:text-left">
            <strong class="font-display text-lg">{{ t('pageSummary', { current: page + 1, total: totalPages }) }}</strong>
            <span class="text-sm text-ink/55">{{ t('shown', { shown: games.length }) }}</span>
          </div>
          <div v-if="loadFailed" class="rounded-xl border border-danger/20 bg-danger/5 px-4 py-3 text-center text-sm text-danger" role="alert">
            <p>{{ t('pageError') }}</p>
            <button type="button" class="mt-2 min-h-11 font-semibold underline" @click="retryFailedPage">{{ t('retryPage') }}</button>
          </div>
          <div class="flex flex-wrap items-center justify-center gap-2">
            <button type="button" :disabled="loading || page === 0" class="min-h-11 rounded-xl border border-ink/15 bg-canvas px-4 text-sm font-semibold transition hover:border-copper hover:text-copper disabled:cursor-not-allowed disabled:opacity-35" @click="navigateToPage(page - 1)">{{ t('previousPage') }}</button>
            <template v-for="item in paginationItems" :key="item.key">
              <span v-if="item.page === null" class="flex min-h-11 min-w-9 items-center justify-center text-ink/40" aria-hidden="true">{{ item.label }}</span>
              <button
                v-else
                type="button"
                :data-testid="`catalog-page-${item.page + 1}`"
                :aria-current="item.page === page ? 'page' : undefined"
                :aria-label="t('goToPage', { page: item.page + 1 })"
                :disabled="loading"
                class="min-h-11 min-w-11 rounded-xl border px-3 text-sm font-bold transition disabled:cursor-wait disabled:opacity-50"
                :class="item.page === page ? 'border-felt bg-felt text-white elevation-sm' : 'border-ink/15 bg-canvas text-ink/65 hover:border-copper hover:text-copper'"
                @click="navigateToPage(item.page)"
              >
                {{ item.label }}
              </button>
            </template>
            <button type="button" :disabled="loading || page >= totalPages - 1" class="min-h-11 rounded-xl border border-ink/15 bg-canvas px-4 text-sm font-semibold transition hover:border-copper hover:text-copper disabled:cursor-not-allowed disabled:opacity-35" @click="navigateToPage(page + 1)">{{ t('nextPage') }}</button>
          </div>
        </nav>
      </section>
    </div>
  </AppShell>
</template>
