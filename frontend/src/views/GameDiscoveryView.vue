<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import { notifyLoginRequired } from '@/lib/authSession'
import { useLocale } from '@/lib/locale'

interface BggGameDetails {
  bggId: number
  name: string
  originalName?: string
  officialNameLocalized?: boolean
  description: string
  thumbnailUrl: string
  publicationYear: number | null
  minPlayers: number | null
  maxPlayers: number | null
  playingTimeMinutes: number | null
  minimumAge: number | null
  imageUrl: string
  averageRating: number | null
  averageWeight: number | null
  categories: string[]
  mechanics: string[]
  designers: string[]
  publishers: string[]
  descriptionTranslated?: boolean
  categoriesTranslated?: boolean
  mechanicsTranslated?: boolean
  bggUrl: string
}

interface ImportedGame {
  game: { id: string; name: string }
  edition: { id: string; name: string }
  alreadyImported: boolean
}

interface CsrfResponse { headerName: string; token: string }

const route = useRoute()
const router = useRouter()
const { locale } = useLocale()
const copy = computed(() => locale.value === 'zh-CN' ? {
  back: '返回推荐', loading: '正在读取桌游资料', error: '暂时无法读取这款桌游。你仍然可以返回并直接添加规则书。',
  login: '登录后即可保留这款桌游，并继续查找规则书。当前选择不会丢失。', selectError: '暂时无法保存这款桌游，请稍后重试。',
  eyebrow: '桌游推荐 · BGG 资料', unknownYear: '发行年份未知', stats: '游戏信息',
  evidenceBoundary: 'BGG 资料仅用于推荐、识别游戏和展示封面。后续讲解与答疑只会引用你确认的规则书。',
  select: '选择这款桌游并找规则书', selecting: '正在准备…', source: '查看 BGG 原始资料', retry: '重新读取',
  translation: 'AI 翻译 · 基于 BGG 原文',
  officialName: '官方中文名 · BGG 版本资料', metadataTranslation: 'AI 翻译',
  cover: (gameName: string) => `${gameName} 封面`, players: (min: number, max: number) => `${min}–${max} 人`,
  minutes: (minutes: number) => `约 ${minutes} 分钟`, age: (age: number) => `${age} 岁以上`,
  rating: (rating: number) => `BGG 评分 ${rating.toFixed(1)}`, weight: (weight: number) => `复杂度 ${weight.toFixed(1)} / 5`,
  designers: '设计师', publishers: '出版社', mechanics: '机制', categories: '类别',
} : {
  back: 'Back to recommendations', loading: 'Loading game details', error: 'This game is unavailable right now. You can still go back and add a rulebook directly.',
  login: 'Sign in to keep this game and continue to its rulebook. Your selection is preserved.', selectError: 'This game could not be saved. Please try again shortly.',
  eyebrow: 'Game recommendation · BGG data', unknownYear: 'Publication year unavailable', stats: 'Game details',
  evidenceBoundary: 'BGG data is used only for recommendations, game identification, and cover art. Teaching and Q&A cite only the rulebook you confirm.',
  select: 'Choose this game and find its rulebook', selecting: 'Preparing…', source: 'View original BGG data', retry: 'Try again',
  translation: 'AI translation · based on the BGG source',
  officialName: 'Official Chinese name · BGG edition data', metadataTranslation: 'AI translation',
  cover: (gameName: string) => `${gameName} cover`, players: (min: number, max: number) => `${min}–${max} players`,
  minutes: (minutes: number) => `About ${minutes} min`, age: (age: number) => `Ages ${age}+`,
  rating: (rating: number) => `BGG rating ${rating.toFixed(1)}`, weight: (weight: number) => `Weight ${weight.toFixed(1)} / 5`,
  designers: 'Designers', publishers: 'Publishers', mechanics: 'Mechanics', categories: 'Categories',
})
const game = ref<BggGameDetails | null>(null)
const loading = ref(true)
const selecting = ref(false)
const errorMessage = ref('')
const bggId = computed(() => Number(route.params.bggId))

const stats = computed(() => {
  if (!game.value) return []
  const values: string[] = []
  if (game.value.minPlayers !== null && game.value.maxPlayers !== null) {
    values.push(copy.value.players(game.value.minPlayers, game.value.maxPlayers))
  }
  if (game.value.playingTimeMinutes !== null) values.push(copy.value.minutes(game.value.playingTimeMinutes))
  if (game.value.minimumAge !== null) values.push(copy.value.age(game.value.minimumAge))
  if (game.value.averageRating !== null) values.push(copy.value.rating(game.value.averageRating))
  if (game.value.averageWeight !== null) values.push(copy.value.weight(game.value.averageWeight))
  return values
})

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    if (!Number.isInteger(bggId.value) || bggId.value <= 0) throw new Error(copy.value.error)
    const response = await fetch(`/api/v1/bgg/games/${bggId.value}?locale=${encodeURIComponent(locale.value)}`, { credentials: 'include' })
    if (!response.ok) throw new Error(copy.value.error)
    const parsed = await response.json() as BggGameDetails
    game.value = {
      ...parsed,
      imageUrl: parsed.imageUrl ?? '',
      averageRating: parsed.averageRating ?? null,
      averageWeight: parsed.averageWeight ?? null,
      categories: parsed.categories ?? [],
      mechanics: parsed.mechanics ?? [],
      designers: parsed.designers ?? [],
      publishers: parsed.publishers ?? [],
      descriptionTranslated: parsed.descriptionTranslated ?? false,
      originalName: parsed.originalName ?? parsed.name,
      officialNameLocalized: parsed.officialNameLocalized ?? false,
      categoriesTranslated: parsed.categoriesTranslated ?? false,
      mechanicsTranslated: parsed.mechanicsTranslated ?? false,
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : copy.value.error
  } finally {
    loading.value = false
  }
}

async function selectGame() {
  selecting.value = true
  errorMessage.value = ''
  try {
    const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'include' })
    if (csrfResponse.status === 401) {
      notifyLoginRequired()
      errorMessage.value = copy.value.login
      return
    }
    if (!csrfResponse.ok) throw new Error(copy.value.selectError)
    const csrf = await csrfResponse.json() as CsrfResponse
    const response = await fetch(`/api/v1/bgg/games/${bggId.value}/import`, {
      method: 'POST', credentials: 'include', headers: { [csrf.headerName]: csrf.token },
    })
    if (response.status === 401) {
      notifyLoginRequired()
      errorMessage.value = copy.value.login
      return
    }
    if (!response.ok) throw new Error(copy.value.selectError)
    const imported = await response.json() as ImportedGame
    await router.push({ name: 'teach', query: { editionId: imported.edition.id, onboarding: 'selected-game' } })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : copy.value.selectError
  } finally {
    selecting.value = false
  }
}

onMounted(load)
watch(locale, load)
</script>

<template>
  <AppShell>
    <main class="mx-auto max-w-5xl px-5 py-8 sm:px-8 lg:px-12 lg:py-14">
      <RouterLink :to="{ name: 'home' }" class="text-sm font-semibold text-indigo">← {{ copy.back }}</RouterLink>

      <div v-if="loading" class="mt-8 animate-pulse rounded-2xl border border-ink/10 bg-paper p-6" :aria-label="copy.loading">
        <div class="h-72 rounded-xl bg-ink/8 sm:h-96" />
      </div>

      <section v-else-if="game" class="mt-8 grid gap-8 rounded-2xl border border-ink/10 bg-paper p-5 shadow-sm sm:p-8 lg:grid-cols-[18rem_1fr]">
        <div class="rounded-xl bg-canvas p-4">
          <img v-if="game.imageUrl || game.thumbnailUrl" :src="game.imageUrl || game.thumbnailUrl" :alt="copy.cover(game.name)" class="mx-auto aspect-[4/5] h-auto w-full object-contain" referrerpolicy="no-referrer">
        </div>
        <div class="min-w-0 self-center">
          <p class="text-sm font-semibold text-copper">{{ copy.eyebrow }}</p>
          <h1 class="mt-2 font-display text-4xl font-semibold tracking-tight sm:text-5xl">{{ game.name }}</h1>
          <p v-if="game.officialNameLocalized" class="mt-2 text-sm font-medium text-copper">{{ game.originalName }} · {{ copy.officialName }}</p>
          <p class="mt-2 text-sm text-ink/45">{{ game.publicationYear ?? copy.unknownYear }}</p>
          <ul v-if="stats.length" class="mt-5 flex flex-wrap gap-2" :aria-label="copy.stats">
            <li v-for="stat in stats" :key="stat" class="rounded-full bg-canvas px-3 py-1.5 text-sm font-medium text-ink/65">{{ stat }}</li>
          </ul>
          <p v-if="game.descriptionTranslated" class="mt-6 text-xs font-semibold text-copper">{{ copy.translation }}</p>
          <p v-if="game.description" :class="game.descriptionTranslated ? 'mt-2' : 'mt-6'" class="line-clamp-6 leading-7 text-ink/65">{{ game.description }}</p>
          <dl v-if="game.designers.length || game.publishers.length || game.mechanics.length || game.categories.length" class="mt-6 grid gap-3 border-t border-ink/10 pt-5 text-sm sm:grid-cols-2">
            <div v-if="game.designers.length"><dt class="font-semibold text-ink/45">{{ copy.designers }}</dt><dd class="mt-1">{{ game.designers.join('、') }}</dd></div>
            <div v-if="game.publishers.length"><dt class="font-semibold text-ink/45">{{ copy.publishers }}</dt><dd class="mt-1">{{ game.publishers.join('、') }}</dd></div>
            <div v-if="game.mechanics.length"><dt class="font-semibold text-ink/45">{{ copy.mechanics }}<span v-if="game.mechanicsTranslated" class="font-medium text-copper"> · {{ copy.metadataTranslation }}</span></dt><dd class="mt-1">{{ game.mechanics.join('、') }}</dd></div>
            <div v-if="game.categories.length"><dt class="font-semibold text-ink/45">{{ copy.categories }}<span v-if="game.categoriesTranslated" class="font-medium text-copper"> · {{ copy.metadataTranslation }}</span></dt><dd class="mt-1">{{ game.categories.join('、') }}</dd></div>
          </dl>
          <p class="mt-5 text-xs leading-5 text-ink/45">{{ copy.evidenceBoundary }}</p>
          <div class="mt-6 flex flex-col gap-3 sm:flex-row sm:items-center">
            <button type="button" :disabled="selecting" class="min-h-12 rounded-xl bg-copper px-6 font-semibold text-white disabled:cursor-not-allowed disabled:opacity-50" @click="selectGame">
              {{ selecting ? copy.selecting : copy.select }}
            </button>
            <a :href="game.bggUrl" target="_blank" rel="noopener noreferrer" class="min-h-12 rounded-xl border border-ink/15 px-5 py-3 text-center text-sm font-semibold text-indigo">{{ copy.source }} ↗</a>
          </div>
        </div>
      </section>

      <div v-if="errorMessage" class="mt-6 rounded-xl border border-red-200 bg-red-50 p-5 text-sm text-red-700" role="alert">
        <p>{{ errorMessage }}</p>
        <button v-if="!game" type="button" class="mt-3 font-semibold underline" @click="load">{{ copy.retry }}</button>
      </div>
    </main>
  </AppShell>
</template>
