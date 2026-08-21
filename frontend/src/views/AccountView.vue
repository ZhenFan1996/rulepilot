<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import { notifyLoginRequired } from '@/lib/authSession'
import { useLocale } from '@/lib/locale'

interface Session { username: string; roles: string[] }
interface TeachingPlan { id: string; gameTitle: string; createdAt: string }
interface ModelSnapshot {
  providers: Array<{ configured: boolean }>
  assignments: { recommendation: string; teaching: string; visual: string; answer: string; critic: string }
}
interface AccountUsage {
  platformAccessEnabled: boolean
  monthlyTokenLimit: number
  platformTokensCharged: number
  platformTokensReserved: number
  personalTokensUsed: number
}
type GridSlot = 'FAVORITE_GAME' | 'FAVORITE_ART' | 'FAVORITE_DESIGNER' | 'FAVORITE_MECHANISM' | 'FAVORITE_THEME' | 'FAVORITE_PUBLISHER' | 'FAVORITE_EXPANSION' | 'FAVORITE_COMPONENT' | 'WISHLIST_GAME'
interface GridSelection { slot: GridSlot; bggId: number; gameName: string; chineseName: string; thumbnailUrl: string; imageUrl?: string }
interface CatalogGame { bggId: number; name: string; chineseName: string; thumbnailUrl: string; imageUrl: string; publicationYear: number | null }

const { locale, t } = useLocale()
const session = ref<Session | null>(null)
const plans = ref<TeachingPlan[]>([])
const models = ref<ModelSnapshot | null>(null)
const usage = ref<AccountUsage | null>(null)
const grid = ref<GridSelection[]>([])
const loading = ref(true)
const gridLoading = ref(false)
const secondaryLoading = ref(false)
const errorMessage = ref('')
const gridDialogOpen = ref(false)
const activeSlot = ref<GridSlot | null>(null)
const searchInput = ref<HTMLInputElement | null>(null)
const searchQuery = ref('')
const searchResults = ref<CatalogGame[]>([])
const searchLoading = ref(false)
const gridSaving = ref(false)
const gridError = ref('')
let searchTimer: number | undefined
let searchController: AbortController | null = null
let coverController: AbortController | null = null
let disposed = false
const searchDebounceMs = 60
const searchCache = new Map<string, CatalogGame[]>()

const slotDefinitions = computed<Array<{ slot: GridSlot; zh: string; en: string; hintZh: string; hintEn: string }>>(() => [
  { slot: 'FAVORITE_GAME', zh: '最爱的桌游', en: 'Favorite game', hintZh: '总会想再开一局', hintEn: 'The one you always return to' },
  { slot: 'FAVORITE_ART', zh: '最喜欢的美术', en: 'Favorite art', hintZh: '一眼就爱上的视觉', hintEn: 'The visual world you love most' },
  { slot: 'FAVORITE_DESIGNER', zh: '最喜欢的设计师', en: 'Favorite designer', hintZh: '用一款代表作来表达', hintEn: 'Choose their defining game' },
  { slot: 'FAVORITE_MECHANISM', zh: '最喜欢的机制', en: 'Favorite mechanism', hintZh: '用最能代表它的游戏', hintEn: 'Pick the game that embodies it' },
  { slot: 'FAVORITE_THEME', zh: '最喜欢的主题', en: 'Favorite theme', hintZh: '最想沉浸进去的世界', hintEn: 'The world you want to inhabit' },
  { slot: 'FAVORITE_PUBLISHER', zh: '最喜欢的出版社', en: 'Favorite publisher', hintZh: '挑一款代表作品', hintEn: 'Choose a representative title' },
  { slot: 'FAVORITE_EXPANSION', zh: '最爱的扩展', en: 'Favorite expansion', hintZh: '让本体变得更好的那盒', hintEn: 'The box that made the base better' },
  { slot: 'FAVORITE_COMPONENT', zh: '最喜欢的配件', en: 'Favorite component', hintZh: '最想拿在手里的那套组件', hintEn: 'Components you love handling' },
  { slot: 'WISHLIST_GAME', zh: '最想玩的桌游', en: 'Wishlist game', hintZh: '下一款最想上桌的游戏', hintEn: 'The next game you want to table' },
])

const connectedModels = computed(() => models.value?.providers.filter((item) => item.configured).length ?? 0)
const initial = computed(() => session.value?.username.slice(0, 1).toUpperCase() ?? '?')
const platformTokensRemaining = computed(() => usage.value
  ? Math.max(0, usage.value.monthlyTokenLimit - usage.value.platformTokensCharged - usage.value.platformTokensReserved)
  : 0)
const selectionBySlot = computed(() => new Map(grid.value.map(selection => [selection.slot, selection])))
const activeSlotDefinition = computed(() => slotDefinitions.value.find(item => item.slot === activeSlot.value) ?? null)

function slotLabel(definition: { zh: string; en: string }) {
  return locale.value === 'zh-CN' ? definition.zh : definition.en
}

function slotHint(definition: { hintZh: string; hintEn: string }) {
  return locale.value === 'zh-CN' ? definition.hintZh : definition.hintEn
}

function displayName(selection: GridSelection) {
  return locale.value === 'zh-CN' && selection.chineseName ? selection.chineseName : selection.gameName
}

function coverImage(game: Pick<GridSelection, 'bggId' | 'imageUrl' | 'thumbnailUrl'>) {
  return game.imageUrl || game.thumbnailUrl ? `/api/v1/bgg/catalog/covers/${game.bggId}/image` : ''
}

function catalogDisplayName(game: CatalogGame) {
  return locale.value === 'zh-CN' && game.chineseName ? game.chineseName : game.name
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await fetch('/api/auth/session', { credentials: 'include' })
    if (response.status === 401) {
      notifyLoginRequired()
      errorMessage.value = t('account.loginRequired')
      return
    }
    if (!response.ok) throw new Error(t('account.error'))
    session.value = await response.json() as Session
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('account.error')
  } finally {
    loading.value = false
  }
  if (session.value && !disposed) {
    void loadGrid()
    void loadSecondaryAccountData()
  }
}

async function loadGrid() {
  gridLoading.value = true
  gridError.value = ''
  try {
    const response = await fetch('/api/v1/account/board-game-grid', { credentials: 'include' })
    if (!response.ok) throw new Error(locale.value === 'zh-CN' ? '九宫格暂时无法读取，请稍后再试。' : 'Your board game nine could not be loaded.')
    const selections = await response.json() as GridSelection[]
    if (disposed) return
    grid.value = selections
    void enrichMissingGridCovers(selections)
  } catch (error) {
    if (disposed) return
    gridError.value = error instanceof Error ? error.message : String(error)
  } finally {
    if (!disposed) gridLoading.value = false
  }
}

async function enrichMissingGridCovers(selections: GridSelection[]) {
  const missingIds = selections.filter(item => !coverImage(item)).map(item => item.bggId)
  if (!missingIds.length) return
  coverController?.abort()
  const controller = new AbortController()
  coverController = controller
  const parameters = new URLSearchParams()
  missingIds.forEach(id => parameters.append('bggId', String(id)))
  try {
    const response = await fetch(`/api/v1/bgg/catalog/covers?${parameters.toString()}`, {
      credentials: 'include',
      signal: controller.signal,
    })
    if (!response.ok) return
    const covers = await response.json() as Array<{ bggId: number; thumbnailUrl: string; imageUrl: string }>
    if (disposed || coverController !== controller) return
    const byId = new Map(covers.map(cover => [cover.bggId, cover]))
    grid.value = grid.value.map(selection => {
      const cover = byId.get(selection.bggId)
      return cover ? { ...selection, thumbnailUrl: cover.thumbnailUrl, imageUrl: cover.imageUrl } : selection
    })
  } catch (error) {
    if (!(error instanceof Error && error.name === 'AbortError')) return
  } finally {
    if (coverController === controller) coverController = null
  }
}

async function loadSecondaryAccountData() {
  secondaryLoading.value = true
  try {
    const responses = await Promise.all([
      fetch('/api/v1/teaching-plans', { credentials: 'include' }),
      fetch('/api/v1/model-configuration', { credentials: 'include' }),
      fetch('/api/v1/model-configuration/usage', { credentials: 'include' }),
    ])
    if (responses.some(response => !response.ok)) return
    const [nextPlans, nextModels, nextUsage] = await Promise.all([
      responses[0]!.json() as Promise<TeachingPlan[]>,
      responses[1]!.json() as Promise<ModelSnapshot>,
      responses[2]!.json() as Promise<AccountUsage>,
    ])
    if (disposed) return
    plans.value = nextPlans
    models.value = nextModels
    usage.value = nextUsage
  } catch {
    // Secondary account data must not hold the signed-in shell or identity grid hostage.
  } finally {
    if (!disposed) secondaryLoading.value = false
  }
}

async function openGridPicker(slot: GridSlot) {
  activeSlot.value = slot
  searchQuery.value = ''
  searchResults.value = []
  gridError.value = ''
  gridDialogOpen.value = true
  await nextTick()
  searchInput.value?.focus()
}

function closeGridPicker() {
  if (gridSaving.value) return
  gridDialogOpen.value = false
  searchController?.abort()
  activeSlot.value = null
}

function clearSearch() {
  window.clearTimeout(searchTimer)
  searchController?.abort()
  searchController = null
  searchQuery.value = ''
  searchResults.value = []
  searchLoading.value = false
  gridError.value = ''
}

function scheduleSearch() {
  window.clearTimeout(searchTimer)
  searchController?.abort()
  const query = searchQuery.value.trim()
  if (!query) {
    searchResults.value = []
    searchLoading.value = false
    return
  }
  searchTimer = window.setTimeout(() => void searchGames(query), searchDebounceMs)
}

async function searchGames(query: string) {
  const cacheKey = query.toLocaleLowerCase(locale.value)
  const cached = searchCache.get(cacheKey)
  if (cached) {
    searchResults.value = cached
    searchLoading.value = false
    return
  }
  const controller = new AbortController()
  searchController = controller
  searchLoading.value = true
  gridError.value = ''
  try {
    const params = new URLSearchParams({ q: query, limit: '12' })
    const response = await fetch(`/api/v1/account/board-game-grid/search?${params.toString()}`, { credentials: 'include', signal: controller.signal })
    if (!response.ok) throw new Error(locale.value === 'zh-CN' ? '暂时搜不到桌游，请稍后再试。' : 'Games could not be searched right now.')
    const games = await response.json() as CatalogGame[]
    searchCache.set(cacheKey, games)
    searchResults.value = games
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return
    gridError.value = error instanceof Error ? error.message : String(error)
  } finally {
    if (searchController === controller) {
      searchController = null
      searchLoading.value = false
    }
  }
}

async function csrfToken() {
  const response = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (!response.ok) throw new Error(locale.value === 'zh-CN' ? '登录状态已失效。' : 'Your session has expired.')
  return await response.json() as { headerName: string; token: string }
}

async function chooseGame(game: CatalogGame) {
  if (!activeSlot.value || gridSaving.value) return
  gridSaving.value = true
  gridError.value = ''
  try {
    const csrf = await csrfToken()
    const response = await fetch(`/api/v1/account/board-game-grid/${activeSlot.value}`, {
      method: 'PUT', credentials: 'include',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({ bggId: game.bggId }),
    })
    if (!response.ok) throw new Error(locale.value === 'zh-CN' ? '这款桌游暂时无法保存。' : 'This game could not be saved.')
    const saved = await response.json() as GridSelection
    grid.value = [...grid.value.filter(item => item.slot !== saved.slot), saved]
    gridSaving.value = false
    closeGridPicker()
  } catch (error) {
    gridError.value = error instanceof Error ? error.message : String(error)
  } finally {
    gridSaving.value = false
  }
}

onMounted(load)
onBeforeUnmount(() => {
  disposed = true
  window.clearTimeout(searchTimer)
  searchController?.abort()
  coverController?.abort()
})
</script>

<template>
  <AppShell>
    <section class="tabletop-page max-w-5xl">
      <p class="text-sm font-medium text-copper">{{ t('account.title') }}</p>
      <div v-if="loading" class="mt-8 rounded-xl border border-ink/10 bg-paper p-8 text-ink/50" role="status">{{ t('account.loading') }}</div>
      <div v-else-if="errorMessage" class="mt-8 rounded-xl bg-red-50 p-6 text-red-800" role="alert">
        <p>{{ errorMessage }}</p>
        <button class="mt-4 font-semibold underline" @click="load">{{ t('account.retry') }}</button>
      </div>
      <template v-else-if="session">
        <header class="mt-5 flex items-center gap-5 border-b border-ink/10 pb-8">
          <span class="grid h-16 w-16 place-items-center rounded-full bg-ink font-display text-2xl font-semibold text-canvas" aria-hidden="true">{{ initial }}</span>
          <div>
            <h1 class="font-display text-4xl font-semibold tracking-tight">{{ session.username }}</h1>
            <p class="mt-2 text-sm text-ink/45">{{ session.roles.join(' · ') }}</p>
          </div>
        </header>

        <div class="mt-8 grid gap-5 sm:grid-cols-2">
          <RouterLink :to="{ name: 'work-status' }" class="rounded-xl border border-ink/10 bg-paper p-6 hover:border-copper/40">
            <p class="text-sm text-ink/45">{{ t('account.guides') }}</p>
            <p class="mt-2 font-display text-3xl font-semibold">{{ secondaryLoading ? '…' : t('account.guideCount', { count: plans.length }) }}</p>
            <p class="mt-4 text-sm text-indigo">{{ t('account.guideAction') }}</p>
          </RouterLink>
          <RouterLink :to="{ name: 'model-settings' }" class="rounded-xl border border-ink/10 bg-paper p-6 hover:border-copper/40">
            <p class="text-sm text-ink/45">{{ t('account.models') }}</p>
            <p class="mt-2 font-display text-3xl font-semibold">{{ secondaryLoading ? '…' : t('account.modelCount', { count: connectedModels }) }}</p>
            <p class="mt-4 text-sm text-indigo">{{ t('account.modelAction') }}</p>
          </RouterLink>
        </div>

        <section class="mt-8 rounded-[1.5rem] border border-copper/20 bg-paper p-5 shadow-sm sm:p-7">
          <div class="flex flex-wrap items-end justify-between gap-4">
            <div>
              <p class="text-xs font-bold uppercase tracking-[0.18em] text-copper">Board game nine</p>
              <h2 class="mt-2 font-display text-3xl font-semibold">{{ locale === 'zh-CN' ? '我的桌游九宫格' : 'My board game nine' }}</h2>
              <p class="mt-2 max-w-2xl text-sm leading-6 text-ink/55">{{ locale === 'zh-CN' ? '九款游戏，九个角度。这些都是你亲自选择的桌游身份，不会从人数、难度或一次对话里替你猜。' : 'Nine games, nine facets of your taste. Every answer is chosen by you—never inferred from group size, weight, or a single chat.' }}</p>
            </div>
            <span class="rounded-full bg-canvas px-3 py-1.5 text-xs font-semibold text-ink/55">{{ gridLoading ? '… / 9' : `${grid.length} / 9` }}</span>
          </div>
          <div v-if="gridLoading" class="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3" role="status" :aria-label="locale === 'zh-CN' ? '正在读取桌游九宫格' : 'Loading board game nine'">
            <div v-for="index in 9" :key="index" class="aspect-[4/3] min-h-48 animate-pulse rounded-[1.35rem] border border-ink/10 bg-canvas" />
          </div>
          <div v-else class="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            <button
              v-for="(definition, index) in slotDefinitions"
              :key="definition.slot"
              type="button"
              class="group relative aspect-[4/3] min-h-48 overflow-hidden rounded-[1.35rem] border border-ink/10 bg-canvas text-left shadow-[0_1px_0_rgba(42,42,34,0.04)] transition duration-200 hover:-translate-y-1 hover:border-copper/45 hover:shadow-xl focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo"
              :aria-label="`${slotLabel(definition)}：${selectionBySlot.get(definition.slot) ? displayName(selectionBySlot.get(definition.slot)!) : (locale === 'zh-CN' ? '未选择' : 'Not selected')}`"
              @click="openGridPicker(definition.slot)"
            >
              <template v-if="selectionBySlot.get(definition.slot)">
                <img v-if="coverImage(selectionBySlot.get(definition.slot)!)" :src="coverImage(selectionBySlot.get(definition.slot)!)" :alt="displayName(selectionBySlot.get(definition.slot)!)" class="absolute inset-0 size-full object-cover object-center transition duration-500 group-hover:scale-[1.025]" :loading="index < 3 ? 'eager' : 'lazy'" :fetchpriority="index < 3 ? 'high' : 'auto'" decoding="async" referrerpolicy="no-referrer">
                <div class="absolute inset-0 bg-gradient-to-t from-ink/95 via-ink/10 to-transparent" />
                <div class="absolute inset-x-0 bottom-0 p-5 text-white">
                  <p class="text-[0.67rem] font-bold uppercase tracking-[0.16em] text-white/75">{{ slotLabel(definition) }}</p>
                  <p class="mt-1.5 line-clamp-2 font-display text-xl font-semibold leading-tight drop-shadow-sm">{{ displayName(selectionBySlot.get(definition.slot)!) }}</p>
                </div>
              </template>
              <div v-else class="flex h-full min-h-48 flex-col items-start justify-between bg-[radial-gradient(circle_at_top_right,rgba(185,82,57,0.08),transparent_50%)] p-5">
                <span class="grid size-11 place-items-center rounded-full border border-dashed border-copper/45 bg-paper text-2xl text-copper transition group-hover:border-copper group-hover:bg-copper group-hover:text-white">＋</span>
                <div class="max-w-[15rem]">
                  <p class="font-display text-xl font-semibold">{{ slotLabel(definition) }}</p>
                  <p class="mt-1.5 text-sm leading-5 text-ink/45">{{ slotHint(definition) }}</p>
                </div>
              </div>
            </button>
          </div>
        </section>

        <section v-if="usage" class="mt-8 rounded-xl border border-ink/10 bg-paper p-6">
          <div class="flex items-center justify-between gap-4">
            <div><h2 class="font-display text-2xl font-semibold">{{ locale === 'zh-CN' ? '本月模型额度' : 'Monthly model allowance' }}</h2><p class="mt-1 text-sm text-ink/50">{{ usage.platformAccessEnabled ? (locale === 'zh-CN' ? '正在使用平台额度；自己的 API Key 不扣这里。' : 'Platform allowance is active; BYOK usage is tracked separately.') : (locale === 'zh-CN' ? '平台额度已暂停，可使用自己的 API Key。' : 'Platform allowance is paused; BYOK remains available.') }}</p></div>
            <p class="font-display text-2xl font-semibold">{{ platformTokensRemaining.toLocaleString() }}</p>
          </div>
          <div class="mt-4 h-2 overflow-hidden rounded-full bg-canvas"><div class="h-full rounded-full bg-copper" :style="{ width: `${Math.min(100, usage.monthlyTokenLimit ? (usage.platformTokensCharged + usage.platformTokensReserved) / usage.monthlyTokenLimit * 100 : 100)}%` }" /></div>
          <div class="mt-3 flex justify-between text-xs text-ink/45"><span>{{ locale === 'zh-CN' ? `已用 ${usage.platformTokensCharged.toLocaleString()}` : `${usage.platformTokensCharged.toLocaleString()} used` }}</span><span>{{ locale === 'zh-CN' ? `总额 ${usage.monthlyTokenLimit.toLocaleString()}` : `${usage.monthlyTokenLimit.toLocaleString()} total` }}</span></div>
        </section>

        <section v-if="models" class="mt-8 rounded-xl border border-ink/10 bg-paper p-6">
          <h2 class="font-display text-2xl font-semibold">{{ t('account.assignments') }}</h2>
          <dl class="mt-5 grid gap-4 text-sm sm:grid-cols-2 lg:grid-cols-5">
            <div><dt class="text-ink/45">{{ t('account.recommendation') }}</dt><dd class="mt-1 font-semibold">{{ models?.assignments.recommendation }}</dd></div>
            <div><dt class="text-ink/45">{{ t('account.teaching') }}</dt><dd class="mt-1 font-semibold">{{ models?.assignments.teaching }}</dd></div>
            <div><dt class="text-ink/45">{{ t('account.visual') }}</dt><dd class="mt-1 font-semibold">{{ models?.assignments.visual }}</dd></div>
            <div><dt class="text-ink/45">{{ t('account.answer') }}</dt><dd class="mt-1 font-semibold">{{ models?.assignments.answer }}</dd></div>
            <div><dt class="text-ink/45">{{ t('account.critic') }}</dt><dd class="mt-1 font-semibold">{{ models?.assignments.critic }}</dd></div>
          </dl>
        </section>
      </template>
    </section>

    <div v-if="gridDialogOpen" class="fixed inset-0 z-50 grid place-items-end bg-ink/60 p-0 sm:place-items-center sm:p-6" @click.self="closeGridPicker">
      <section role="dialog" aria-modal="true" :aria-label="activeSlotDefinition ? slotLabel(activeSlotDefinition) : ''" class="max-h-[88vh] w-full overflow-y-auto rounded-t-[1.75rem] bg-paper p-5 shadow-2xl sm:max-w-4xl sm:rounded-[1.75rem] sm:p-7">
        <div class="flex items-start justify-between gap-4">
          <div><p class="text-xs font-bold uppercase tracking-[0.16em] text-copper">{{ activeSlotDefinition ? slotLabel(activeSlotDefinition) : '' }}</p><h2 class="mt-2 font-display text-3xl font-semibold">{{ locale === 'zh-CN' ? '选一款代表你的桌游' : 'Choose the game that represents you' }}</h2></div>
          <button type="button" class="grid size-11 place-items-center rounded-full border border-ink/10 text-xl" :aria-label="locale === 'zh-CN' ? '关闭' : 'Close'" @click="closeGridPicker">×</button>
        </div>
        <label class="relative mt-6 block"><span class="sr-only">{{ locale === 'zh-CN' ? '搜索桌游' : 'Search games' }}</span><span class="pointer-events-none absolute inset-y-0 left-4 grid place-items-center text-ink/35" aria-hidden="true">⌕</span><input ref="searchInput" v-model="searchQuery" type="search" autocomplete="off" class="min-h-14 w-full rounded-2xl border border-ink/15 bg-canvas py-3 pl-11 pr-12 text-base outline-none transition focus:border-indigo focus:bg-white focus:ring-4 focus:ring-indigo/10" :placeholder="locale === 'zh-CN' ? '输入简称、中文片段或英文名，例如：方舟、国家公园、Dune' : 'Type any part of an English or Chinese title'" @input="scheduleSearch"><button v-if="searchQuery" type="button" class="absolute inset-y-0 right-2 my-auto grid size-10 place-items-center rounded-full text-lg text-ink/45 hover:bg-ink/5" :aria-label="locale === 'zh-CN' ? '清空搜索' : 'Clear search'" @click="clearSearch">×</button></label>
        <p class="mt-2 text-xs leading-5 text-ink/45">{{ locale === 'zh-CN' ? '不必输入全称；中文简称、任意连续片段和英文部分名称都可以。结果只查本地索引。' : 'No full title needed. Partial Chinese aliases and English fragments search the local index only.' }}</p>
        <p v-if="searchLoading" class="mt-5 text-sm text-ink/50" role="status">{{ locale === 'zh-CN' ? '正在匹配…' : 'Matching…' }}</p>
        <p v-if="gridError" class="mt-5 rounded-xl bg-red-50 p-4 text-sm text-red-800" role="alert">{{ gridError }}</p>
        <p v-if="searchResults.length && !searchLoading" class="mt-5 text-xs font-semibold text-ink/45" aria-live="polite">{{ locale === 'zh-CN' ? `找到 ${searchResults.length} 款` : `${searchResults.length} matches` }}</p>
        <div v-if="searchResults.length" class="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4">
          <button v-for="game in searchResults" :key="game.bggId" type="button" class="overflow-hidden rounded-xl border border-ink/10 bg-canvas text-left transition hover:border-copper/45 hover:shadow-md disabled:opacity-50" :disabled="gridSaving" @click="chooseGame(game)">
            <div class="aspect-[4/5] bg-ink/5"><img v-if="coverImage(game)" :src="coverImage(game)" :alt="catalogDisplayName(game)" class="size-full object-contain" loading="lazy" decoding="async" referrerpolicy="no-referrer"><div v-else class="grid size-full place-items-center px-3 text-center text-xs text-ink/35">{{ locale === 'zh-CN' ? '暂无封面' : 'No cover' }}</div></div>
            <div class="p-3"><p class="line-clamp-2 font-semibold leading-tight">{{ catalogDisplayName(game) }}</p><p v-if="game.name && game.name !== catalogDisplayName(game)" class="mt-1 line-clamp-1 text-xs text-ink/45">{{ game.name }}</p><p v-if="game.publicationYear" class="mt-1 text-[0.7rem] text-ink/35">{{ game.publicationYear }}</p></div>
          </button>
        </div>
        <p v-else-if="searchQuery.trim() && !searchLoading && !gridError" class="mt-6 rounded-xl border border-dashed border-ink/15 p-6 text-center text-sm text-ink/45">{{ locale === 'zh-CN' ? '没有找到。可以换一个中文名、英文名或简称。' : 'No match yet. Try another title or alias.' }}</p>
      </section>
    </div>
  </AppShell>
</template>
