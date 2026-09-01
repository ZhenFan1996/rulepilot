<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'

import type { RecommendationGame } from '@/components/gameRecommendationTypes'
import ProgressiveCatalogCover from '@/components/ProgressiveCatalogCover.vue'
import { useModalFocus } from '@/composables/useModalFocus'
import { useLocale, type AppLocale } from '@/lib/locale'

interface BggEditionImage {
  versionId: number
  name: string
  imageUrl: string
  publicationYear: number | null
  languages: string[]
}

interface BggGameDetails {
  bggId: number
  name: string
  originalName: string
  officialNameLocalized: boolean
  description: string
  thumbnailUrl: string
  imageUrl: string
  publicationYear: number | null
  minPlayers: number | null
  maxPlayers: number | null
  playingTimeMinutes: number | null
  minimumPlayTimeMinutes: number | null
  maximumPlayTimeMinutes: number | null
  minimumAge: number | null
  suggestedMinimumAge: number | null
  bestWith: string
  recommendedWith: string
  languageDependenceLevel: number | null
  averageRating: number | null
  averageWeight: number | null
  weightVotes: number | null
  categories: string[]
  mechanics: string[]
  families: string[]
  designers: string[]
  publishers: string[]
  editionImages: BggEditionImage[]
  descriptionTranslated: boolean
  categoriesTranslated: boolean
  mechanicsTranslated: boolean
  bggUrl: string
}

const props = defineProps<{ game: RecommendationGame; open: boolean }>()
const emit = defineEmits<{ close: []; select: [game: RecommendationGame] }>()
const { locale } = useLocale()
const details = ref<BggGameDetails | null>(null)
const loading = ref(false)
const translating = ref(false)
const error = ref(false)
const dialog = ref<HTMLElement | null>(null)
let requestSequence = 0
let disposed = false
let activeController: AbortController | null = null

useModalFocus({ dialog, open: () => props.open, requestClose: () => emit('close') })

const copy = computed(() => locale.value === 'zh-CN' ? {
  dialog: '桌游详细资料', close: '关闭桌游资料', eyebrow: 'BGG 桌游资料', loading: '正在读取详细资料…', error: '暂时无法读取详细资料。', retry: '重试',
  select: '选这款，继续找规则书', source: '查看 BGG 原始资料', translated: '译自 BGG 原文', translating: '原文已显示，中文资料正在补齐…',
  unknownYear: '发行年份未知', designers: '设计师', publishers: '出版社', mechanics: '机制', categories: '类别', families: '系列与主题', communityFit: 'BGG 玩家投票', editions: '版本包装图',
  evidence: '这些资料用于识别和选游戏；后续讲解与答疑只引用你确认的规则书。',
  players: (min: number, max: number) => `${min}–${max} 人`, minutes: (value: number) => `约 ${value} 分钟`, minutesRange: (min: number, max: number) => `${min}–${max} 分钟`, age: (value: number) => `官方 ${value} 岁以上`, suggestedAge: (value: number) => `玩家建议 ${value} 岁以上`, language: (value: number) => `语言依赖 ${value} / 5`, rating: (value: number) => `BGG 评分 ${value.toFixed(1)}`, weight: (value: number, votes: number | null) => `复杂度 ${value.toFixed(1)} / 5${votes ? `（${votes} 票）` : ''}`,
} : {
  dialog: 'Game details', close: 'Close game details', eyebrow: 'BoardGameGeek details', loading: 'Loading full details…', error: 'Full details are temporarily unavailable.', retry: 'Retry',
  select: 'Choose and find its rulebook', source: 'View original BGG data', translated: 'Translated from BGG', translating: 'Source details are visible while localization finishes…',
  unknownYear: 'Publication year unavailable', designers: 'Designers', publishers: 'Publishers', mechanics: 'Mechanics', categories: 'Categories', families: 'Families and themes', communityFit: 'BGG player polls', editions: 'Edition packaging',
  evidence: 'These details identify and help select the game. Teaching and Q&A cite only the rulebook you confirm.',
  players: (min: number, max: number) => `${min}–${max} players`, minutes: (value: number) => `About ${value} min`, minutesRange: (min: number, max: number) => `${min}–${max} min`, age: (value: number) => `Official age ${value}+`, suggestedAge: (value: number) => `Players suggest age ${value}+`, language: (value: number) => `Language dependence ${value} / 5`, rating: (value: number) => `BGG rating ${value.toFixed(1)}`, weight: (value: number, votes: number | null) => `Weight ${value.toFixed(1)} / 5${votes ? ` (${votes} votes)` : ''}`,
})

const stats = computed(() => {
  const value = details.value
  if (!value) return []
  const result: string[] = []
  if (value.minPlayers !== null && value.maxPlayers !== null) result.push(copy.value.players(value.minPlayers, value.maxPlayers))
  if (value.minimumPlayTimeMinutes !== null && value.maximumPlayTimeMinutes !== null && value.minimumPlayTimeMinutes !== value.maximumPlayTimeMinutes) result.push(copy.value.minutesRange(value.minimumPlayTimeMinutes, value.maximumPlayTimeMinutes))
  else if (value.playingTimeMinutes !== null) result.push(copy.value.minutes(value.playingTimeMinutes))
  if (value.minimumAge !== null) result.push(copy.value.age(value.minimumAge))
  if (value.suggestedMinimumAge !== null && value.suggestedMinimumAge !== value.minimumAge) result.push(copy.value.suggestedAge(value.suggestedMinimumAge))
  if (value.languageDependenceLevel !== null) result.push(copy.value.language(value.languageDependenceLevel))
  if (value.averageRating !== null) result.push(copy.value.rating(value.averageRating))
  if (value.averageWeight !== null) result.push(copy.value.weight(value.averageWeight, value.weightVotes))
  return result
})

function normalize(value: Partial<BggGameDetails>, fallback: RecommendationGame): BggGameDetails {
  return {
    bggId: value.bggId ?? fallback.bggId,
    name: value.name ?? fallback.name,
    originalName: value.originalName ?? fallback.originalName,
    officialNameLocalized: value.officialNameLocalized ?? false,
    description: value.description ?? '',
    thumbnailUrl: value.thumbnailUrl ?? fallback.thumbnailUrl,
    imageUrl: value.imageUrl ?? '',
    publicationYear: value.publicationYear ?? fallback.publicationYear,
    minPlayers: value.minPlayers ?? fallback.minPlayers,
    maxPlayers: value.maxPlayers ?? fallback.maxPlayers,
    playingTimeMinutes: value.playingTimeMinutes ?? fallback.playingTimeMinutes,
    minimumPlayTimeMinutes: value.minimumPlayTimeMinutes ?? fallback.minimumPlayTimeMinutes ?? null,
    maximumPlayTimeMinutes: value.maximumPlayTimeMinutes ?? fallback.maximumPlayTimeMinutes ?? null,
    minimumAge: value.minimumAge ?? fallback.minimumAge ?? null,
    suggestedMinimumAge: value.suggestedMinimumAge ?? fallback.suggestedMinimumAge ?? null,
    bestWith: value.bestWith ?? fallback.bestWith ?? '',
    recommendedWith: value.recommendedWith ?? fallback.recommendedWith ?? '',
    languageDependenceLevel: value.languageDependenceLevel ?? fallback.languageDependenceLevel ?? null,
    averageRating: value.averageRating ?? fallback.averageRating,
    averageWeight: value.averageWeight ?? fallback.averageWeight,
    weightVotes: value.weightVotes ?? fallback.weightVotes ?? null,
    categories: value.categories ?? fallback.categories,
    mechanics: value.mechanics ?? fallback.mechanics,
    families: value.families ?? fallback.families ?? [],
    designers: value.designers ?? fallback.designers ?? [],
    publishers: value.publishers ?? fallback.publishers ?? [],
    editionImages: value.editionImages ?? [],
    descriptionTranslated: value.descriptionTranslated ?? false,
    categoriesTranslated: value.categoriesTranslated ?? false,
    mechanicsTranslated: value.mechanicsTranslated ?? false,
    bggUrl: value.bggUrl ?? fallback.bggUrl,
  }
}

function isAbortError(value: unknown) {
  return value instanceof Error && value.name === 'AbortError'
}

function isCurrentRequest(
  request: number,
  targetBggId: number,
  targetLocale: AppLocale,
  controller: AbortController,
) {
  return !disposed
    && props.open
    && request === requestSequence
    && activeController === controller
    && props.game.bggId === targetBggId
    && locale.value === targetLocale
}

async function load() {
  if (!props.open) return
  const fallback = { ...props.game }
  const targetBggId = fallback.bggId
  const targetLocale = locale.value
  const request = ++requestSequence
  activeController?.abort()
  const controller = new AbortController()
  activeController = controller
  loading.value = true
  translating.value = false
  error.value = false
  details.value = null
  try {
    const response = await fetch(`/api/v1/bgg/games/${targetBggId}?locale=${encodeURIComponent(targetLocale)}&translate=false`, {
      credentials: 'include',
      signal: controller.signal,
    })
    if (!response.ok) throw new Error('details unavailable')
    const source = normalize(await response.json() as Partial<BggGameDetails>, fallback)
    if (!isCurrentRequest(request, targetBggId, targetLocale, controller)) return
    if (source.bggId !== targetBggId) throw new Error('mismatched game details')
    details.value = source
    loading.value = false
    if (targetLocale === 'zh-CN') void loadLocalized(request, fallback, targetLocale, controller)
  } catch (caught) {
    if (!isAbortError(caught) && isCurrentRequest(request, targetBggId, targetLocale, controller)) error.value = true
  } finally {
    if (isCurrentRequest(request, targetBggId, targetLocale, controller)) loading.value = false
  }
}

async function loadLocalized(
  request: number,
  fallback: RecommendationGame,
  targetLocale: AppLocale,
  controller: AbortController,
) {
  if (!isCurrentRequest(request, fallback.bggId, targetLocale, controller)) return
  translating.value = true
  try {
    const response = await fetch(`/api/v1/bgg/games/${fallback.bggId}?locale=${encodeURIComponent(targetLocale)}&translate=true`, {
      credentials: 'include',
      signal: controller.signal,
    })
    if (!response.ok) return
    const localized = normalize(await response.json() as Partial<BggGameDetails>, fallback)
    if (!isCurrentRequest(request, fallback.bggId, targetLocale, controller)) return
    if (localized.bggId !== fallback.bggId) return
    details.value = localized
  } catch (caught) {
    if (!isAbortError(caught) && isCurrentRequest(request, fallback.bggId, targetLocale, controller)) {
      translating.value = false
    }
  } finally {
    if (isCurrentRequest(request, fallback.bggId, targetLocale, controller)) translating.value = false
  }
}

watch(() => [props.open, props.game.bggId, locale.value] as const, ([open]) => {
  if (open) void load()
  else {
    requestSequence += 1
    activeController?.abort()
    activeController = null
    loading.value = false
    translating.value = false
  }
}, { immediate: true })
onBeforeUnmount(() => {
  disposed = true
  requestSequence += 1
  activeController?.abort()
  activeController = null
})
</script>

<template>
  <div v-if="open" class="fixed inset-0 z-50 overflow-y-auto bg-ink/40 px-3 py-6 backdrop-blur-[2px] sm:px-6" @click.self="emit('close')">
    <section ref="dialog" tabindex="-1" class="mx-auto w-full max-w-6xl overflow-y-auto rounded-3xl border border-gold/25 bg-canvas shadow-2xl outline-none sm:max-h-[calc(100vh-3rem)]" role="dialog" aria-modal="true" :aria-label="copy.dialog">
      <header class="app-sticky-top sticky z-10 flex items-start justify-between border-b border-ink/10 bg-paper/95 px-5 py-4 backdrop-blur sm:px-7">
        <div><p class="tabletop-kicker">{{ copy.eyebrow }}</p><h2 class="mt-1 font-display text-2xl font-semibold">{{ details?.name ?? game.name }}</h2></div>
        <button type="button" data-modal-initial-focus class="grid min-h-11 min-w-11 place-items-center rounded-lg text-2xl text-ink/45 hover:bg-ink/5" :aria-label="copy.close" @click="emit('close')">×</button>
      </header>

      <div class="grid items-start gap-6 p-4 sm:p-6 lg:grid-cols-[minmax(15rem,21rem)_minmax(0,1fr)] lg:gap-8">
        <div class="self-start rounded-2xl border border-ink/8 bg-paper p-3 sm:p-4">
          <ProgressiveCatalogCover :bgg-id="game.bggId" :alt="details?.name ?? game.name" class="mx-auto aspect-[3/4] max-h-[min(62vh,42rem)] w-full" />
          <div v-if="details" class="mt-4 flex flex-col gap-2">
            <button type="button" class="min-h-12 rounded-xl bg-felt px-5 text-sm font-semibold text-white" @click="emit('select', game)">{{ copy.select }}</button>
            <a :href="details.bggUrl" target="_blank" rel="noopener noreferrer" class="inline-flex min-h-11 items-center justify-center text-sm font-semibold text-indigo">{{ copy.source }} ↗</a>
          </div>
        </div>
        <div class="min-w-0">
          <div v-if="loading" data-testid="recommendation-details-loading" class="grid min-h-80 place-items-center text-sm text-ink/50" role="status">{{ copy.loading }}</div>
          <div v-else-if="error" class="grid min-h-80 place-items-center text-center" role="alert"><div><p>{{ copy.error }}</p><button type="button" class="mt-4 min-h-11 rounded-lg bg-indigo px-5 font-semibold text-white" @click="load">{{ copy.retry }}</button></div></div>
          <template v-else-if="details">
            <p v-if="details.officialNameLocalized" class="text-sm font-semibold text-copper">{{ details.originalName }}</p>
            <p class="mt-1 text-sm text-ink/45">{{ details.publicationYear ?? copy.unknownYear }}</p>
            <ul v-if="stats.length" class="mt-4 flex flex-wrap gap-2"><li v-for="stat in stats" :key="stat" class="tabletop-chip min-h-9 px-3 text-sm">{{ stat }}</li></ul>
            <p v-if="translating" class="mt-5 text-xs font-semibold text-copper" role="status">{{ copy.translating }}</p>
            <p v-else-if="details.descriptionTranslated" class="mt-5 text-xs font-semibold text-copper">{{ copy.translated }}</p>
            <p v-if="details.description" class="mt-2 max-h-72 overflow-y-auto whitespace-pre-line pr-2 text-sm leading-7 text-ink/68">{{ details.description }}</p>
            <dl class="mt-6 grid gap-4 border-t border-ink/10 pt-5 text-sm sm:grid-cols-2">
              <div v-if="details.designers.length"><dt class="font-semibold text-ink/45">{{ copy.designers }}</dt><dd class="mt-1 leading-6">{{ details.designers.join('、') }}</dd></div>
              <div v-if="details.publishers.length"><dt class="font-semibold text-ink/45">{{ copy.publishers }}</dt><dd class="mt-1 leading-6">{{ details.publishers.join('、') }}</dd></div>
              <div v-if="details.mechanics.length"><dt class="font-semibold text-ink/45">{{ copy.mechanics }}</dt><dd class="mt-1 leading-6">{{ details.mechanics.join('、') }}</dd></div>
              <div v-if="details.categories.length"><dt class="font-semibold text-ink/45">{{ copy.categories }}</dt><dd class="mt-1 leading-6">{{ details.categories.join('、') }}</dd></div>
              <div v-if="details.families.length"><dt class="font-semibold text-ink/45">{{ copy.families }}</dt><dd class="mt-1 leading-6">{{ details.families.join('、') }}</dd></div>
              <div v-if="details.bestWith || details.recommendedWith"><dt class="font-semibold text-ink/45">{{ copy.communityFit }}</dt><dd class="mt-1 leading-6">{{ [details.bestWith, details.recommendedWith].filter(Boolean).join(' · ') }}</dd></div>
            </dl>
            <div v-if="details.editionImages.length" class="mt-6 border-t border-ink/10 pt-5">
              <h3 class="font-display text-xl font-semibold">{{ copy.editions }}</h3>
              <ul class="mt-3 flex gap-3 overflow-x-auto pb-2"><li v-for="edition in details.editionImages" :key="edition.versionId" class="w-28 shrink-0"><img :src="edition.imageUrl" :alt="edition.name" loading="lazy" class="h-32 w-full rounded-lg bg-paper object-contain"><p class="mt-1 line-clamp-2 text-xs text-ink/55">{{ edition.name }}</p></li></ul>
            </div>
            <p class="mt-6 rounded-xl bg-indigo/5 px-4 py-3 text-xs leading-5 text-ink/50">{{ copy.evidence }}</p>
          </template>
        </div>
      </div>
    </section>
  </div>
</template>
