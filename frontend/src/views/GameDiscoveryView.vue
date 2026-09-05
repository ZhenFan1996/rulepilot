<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import ProgressiveCatalogCover from '@/components/ProgressiveCatalogCover.vue'
import { notifyLoginRequired } from '@/lib/authSession'
import { useLocale, type AppLocale } from '@/lib/locale'

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
  editionImages: BggEditionImage[]
  descriptionTranslated?: boolean
  categoriesTranslated?: boolean
  mechanicsTranslated?: boolean
  bggUrl: string
}

interface BggEditionImage {
  versionId: number
  name: string
  imageUrl: string
  publicationYear: number | null
  languages: string[]
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
  eyebrow: '桌游资料', unknownYear: '发行年份未知', stats: '游戏信息',
  evidenceBoundary: 'BGG 资料仅用于推荐、识别游戏和展示封面。后续讲解与答疑只会引用你确认的规则书。',
  select: '选择这款桌游并找规则书', selecting: '正在准备…', source: '查看 BGG 原始资料', retry: '重新读取',
  translation: '译自 BGG 原文', translating: '正在读取中文简介…', untranslated: '暂无中文译文，以下为 BGG 原文。',
  officialName: 'BGG 版本资料收录的官方中文名', metadataTranslation: '中文对照',
  cover: (gameName: string) => `${gameName} 封面`, players: (min: number, max: number) => `${min}–${max} 人`,
  minutes: (minutes: number) => `约 ${minutes} 分钟`, age: (age: number) => `${age} 岁以上`,
  rating: (rating: number) => `BGG 评分 ${rating.toFixed(1)}`, weight: (weight: number) => `复杂度 ${weight.toFixed(1)} / 5`,
  designers: '设计师', publishers: '出版社', mechanics: '机制', categories: '类别',
  editionImages: 'BGG 版本图片', editionImagesHint: '公开 XML API 提供的版本包装图，不代表实拍图集。',
  imageGallery: '前往 BGG 查看实拍与组件图', communityFiles: 'BGG 社区文件（用户上传，非官方）',
  communityFilesHint: '社区文件可能包含翻译或玩家辅助；RulePilot 不会把它们自动当作官方规则书。',
} : {
  back: 'Back to recommendations', loading: 'Loading game details', error: 'This game is unavailable right now. You can still go back and add a rulebook directly.',
  login: 'Sign in to keep this game and continue to its rulebook. Your selection is preserved.', selectError: 'This game could not be saved. Please try again shortly.',
  eyebrow: 'Game details', unknownYear: 'Publication year unavailable', stats: 'Game details',
  evidenceBoundary: 'BGG data is used only for recommendations, game identification, and cover art. Teaching and Q&A cite only the rulebook you confirm.',
  select: 'Choose this game and find its rulebook', selecting: 'Preparing…', source: 'View original BGG data', retry: 'Try again',
  translation: 'Translated from the BGG source', translating: 'Loading localized details…', untranslated: 'A translation is unavailable. The BGG source is shown below.',
  officialName: 'Official Chinese name recorded by a BGG edition', metadataTranslation: 'Localized terms',
  cover: (gameName: string) => `${gameName} cover`, players: (min: number, max: number) => `${min}–${max} players`,
  minutes: (minutes: number) => `About ${minutes} min`, age: (age: number) => `Ages ${age}+`,
  rating: (rating: number) => `BGG rating ${rating.toFixed(1)}`, weight: (weight: number) => `Weight ${weight.toFixed(1)} / 5`,
  designers: 'Designers', publishers: 'Publishers', mechanics: 'Mechanics', categories: 'Categories',
  editionImages: 'BGG edition images', editionImagesHint: 'Edition packaging from the public XML API; this is not a physical-photo gallery.',
  imageGallery: 'See physical and component photos on BGG', communityFiles: 'BGG community files (user-contributed, unofficial)',
  communityFilesHint: 'Community files may include translations or player aids; RulePilot never treats them as official rulebooks automatically.',
})
const game = ref<BggGameDetails | null>(null)
const loading = ref(true)
const translating = ref(false)
const selecting = ref(false)
const errorMessage = ref('')
const bggId = computed(() => Number(route.params.bggId))
const validBggId = computed(() => Number.isSafeInteger(bggId.value) && bggId.value > 0)
const displayName = computed(() => game.value?.name ?? `BGG #${bggId.value}`)
let requestSequence = 0
let selectionSequence = 0
let disposed = false
let activeDetailsController: AbortController | null = null
let activeSelectionController: AbortController | null = null

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

function normalizeDetails(parsed: BggGameDetails): BggGameDetails {
  return {
    ...parsed,
    imageUrl: parsed.imageUrl ?? '',
    averageRating: parsed.averageRating ?? null,
    averageWeight: parsed.averageWeight ?? null,
    categories: parsed.categories ?? [],
    mechanics: parsed.mechanics ?? [],
    designers: parsed.designers ?? [],
    publishers: parsed.publishers ?? [],
    editionImages: parsed.editionImages ?? [],
    descriptionTranslated: parsed.descriptionTranslated ?? false,
    originalName: parsed.originalName ?? parsed.name,
    officialNameLocalized: parsed.officialNameLocalized ?? false,
    categoriesTranslated: parsed.categoriesTranslated ?? false,
    mechanicsTranslated: parsed.mechanicsTranslated ?? false,
  }
}

function isAbortError(error: unknown) {
  return error instanceof Error && error.name === 'AbortError'
}

function isCurrentDetailsRequest(
  request: number,
  targetBggId: number,
  targetLocale: AppLocale,
  controller: AbortController,
) {
  return !disposed
    && request === requestSequence
    && activeDetailsController === controller
    && Object.is(bggId.value, targetBggId)
    && locale.value === targetLocale
}

async function loadLocalized(
  request: number,
  targetBggId: number,
  targetLocale: AppLocale,
  controller: AbortController,
) {
  if (targetLocale !== 'zh-CN' || !isCurrentDetailsRequest(request, targetBggId, targetLocale, controller)) return
  translating.value = true
  try {
    const response = await fetch(`/api/v1/bgg/games/${targetBggId}?locale=${encodeURIComponent(targetLocale)}&translate=true`, {
      credentials: 'include',
      signal: controller.signal,
    })
    if (!response.ok) return
    const localized = normalizeDetails(await response.json() as BggGameDetails)
    if (!isCurrentDetailsRequest(request, targetBggId, targetLocale, controller)) return
    if (localized.bggId !== targetBggId) throw new Error('mismatched game details')
    game.value = localized
  } catch (error) {
    if (!isAbortError(error) && isCurrentDetailsRequest(request, targetBggId, targetLocale, controller)) {
      translating.value = false
    }
  } finally {
    if (isCurrentDetailsRequest(request, targetBggId, targetLocale, controller)) translating.value = false
  }
}

async function load() {
  const targetBggId = bggId.value
  const targetLocale = locale.value
  const request = ++requestSequence
  activeDetailsController?.abort()
  const controller = new AbortController()
  activeDetailsController = controller
  loading.value = true
  translating.value = false
  errorMessage.value = ''
  game.value = null
  try {
    if (!Number.isInteger(targetBggId) || targetBggId <= 0) throw new Error(copy.value.error)
    const response = await fetch(`/api/v1/bgg/games/${targetBggId}?locale=${encodeURIComponent(targetLocale)}&translate=false`, {
      credentials: 'include',
      signal: controller.signal,
    })
    if (!response.ok) throw new Error(copy.value.error)
    const parsed = normalizeDetails(await response.json() as BggGameDetails)
    if (!isCurrentDetailsRequest(request, targetBggId, targetLocale, controller)) return
    if (parsed.bggId !== targetBggId) throw new Error(copy.value.error)
    game.value = parsed
    loading.value = false
    void loadLocalized(request, targetBggId, targetLocale, controller)
  } catch (error) {
    if (!isAbortError(error) && isCurrentDetailsRequest(request, targetBggId, targetLocale, controller)) {
      errorMessage.value = error instanceof Error ? error.message : copy.value.error
    }
  } finally {
    if (isCurrentDetailsRequest(request, targetBggId, targetLocale, controller)) loading.value = false
  }
}

function isCurrentSelection(request: number, targetBggId: number, controller: AbortController) {
  return !disposed
    && request === selectionSequence
    && activeSelectionController === controller
    && bggId.value === targetBggId
}

function cancelSelection() {
  selectionSequence += 1
  activeSelectionController?.abort()
  activeSelectionController = null
  selecting.value = false
}

async function selectGame() {
  if (selecting.value) return
  const targetBggId = game.value?.bggId
  if (!targetBggId || targetBggId !== bggId.value) return
  const request = ++selectionSequence
  activeSelectionController?.abort()
  const controller = new AbortController()
  activeSelectionController = controller
  selecting.value = true
  errorMessage.value = ''
  try {
    const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'include', signal: controller.signal })
    if (!isCurrentSelection(request, targetBggId, controller)) return
    if (csrfResponse.status === 401) {
      notifyLoginRequired()
      errorMessage.value = copy.value.login
      return
    }
    if (!csrfResponse.ok) throw new Error(copy.value.selectError)
    const csrf = await csrfResponse.json() as CsrfResponse
    if (!isCurrentSelection(request, targetBggId, controller)) return
    const response = await fetch(`/api/v1/bgg/games/${targetBggId}/import`, {
      method: 'POST', credentials: 'include', headers: { [csrf.headerName]: csrf.token }, signal: controller.signal,
    })
    if (!isCurrentSelection(request, targetBggId, controller)) return
    if (response.status === 401) {
      notifyLoginRequired()
      errorMessage.value = copy.value.login
      return
    }
    if (!response.ok) throw new Error(copy.value.selectError)
    const imported = await response.json() as ImportedGame
    if (!isCurrentSelection(request, targetBggId, controller)) return
    await router.push({ name: 'teach', query: { editionId: imported.edition.id, onboarding: 'selected-game' } })
  } catch (error) {
    if (!isAbortError(error) && isCurrentSelection(request, targetBggId, controller)) {
      errorMessage.value = error instanceof Error ? error.message : copy.value.selectError
    }
  } finally {
    if (request === selectionSequence && activeSelectionController === controller) {
      activeSelectionController = null
      selecting.value = false
    }
  }
}

onMounted(load)
watch([bggId, locale], ([nextBggId], [previousBggId]) => {
  if (nextBggId !== previousBggId) cancelSelection()
  void load()
})
onBeforeUnmount(() => {
  disposed = true
  requestSequence += 1
  selectionSequence += 1
  activeDetailsController?.abort()
  activeSelectionController?.abort()
  activeDetailsController = null
  activeSelectionController = null
})
</script>

<template>
  <AppShell>
    <div class="tabletop-page max-w-6xl">
      <RouterLink to="/discover" class="inline-flex min-h-11 items-center text-sm font-semibold text-indigo">← {{ copy.back }}</RouterLink>

      <section v-if="validBggId" class="tabletop-panel player-board mt-5 grid min-w-0 gap-6 p-4 sm:gap-8 sm:p-7">
        <div data-testid="game-detail-hero" class="game-detail-hero grid min-w-0">
          <div data-testid="game-cover-column" class="game-detail-cover min-w-0 self-start rounded-xl border border-ink/8 bg-canvas p-2 sm:p-3 lg:p-4">
            <ProgressiveCatalogCover :bgg-id="bggId" :alt="copy.cover(displayName)" class="mx-auto aspect-[4/5] w-full max-w-full" />
            <a href="https://boardgamegeek.com" target="_blank" rel="noopener noreferrer" class="mt-3 flex justify-center border-t border-ink/8 pt-3 lg:mt-4 lg:pt-4"><img src="/powered-by-bgg-rgb.svg" alt="Powered by BoardGameGeek" class="h-auto w-24 lg:w-[137px]" width="342" height="76"></a>
          </div>
          <div class="game-detail-summary">
            <div data-testid="game-detail-identity" class="game-detail-identity min-w-0 self-center">
              <p class="tabletop-kicker">{{ copy.eyebrow }}</p>
              <h1 class="mt-2 font-display text-[1.85rem] font-semibold leading-[1.05] tracking-tight [overflow-wrap:anywhere] sm:text-4xl lg:text-5xl">{{ displayName }}</h1>
              <p v-if="game?.officialNameLocalized" class="mt-2 text-xs font-medium leading-5 text-copper sm:text-sm">{{ game.originalName }} · {{ copy.officialName }}</p>
              <p v-if="game" class="mt-2 text-sm text-muted">{{ game.publicationYear ?? copy.unknownYear }}</p>
              <p v-if="loading" data-testid="game-detail-loading" class="mt-3 text-sm text-muted" role="status">{{ copy.loading }}</p>
            </div>
            <ul v-if="game && stats.length" data-testid="game-detail-stats" class="game-detail-stats flex min-w-0 flex-wrap gap-2" :aria-label="copy.stats">
              <li v-for="stat in stats" :key="stat" class="tabletop-chip min-h-8 px-3 text-xs sm:min-h-9 sm:text-sm">{{ stat }}</li>
            </ul>
            <div v-if="game" data-testid="game-detail-actions" class="game-detail-actions flex min-w-0 flex-col gap-3 sm:flex-row sm:items-center">
              <button type="button" :disabled="selecting" class="min-h-12 w-full rounded-xl bg-felt px-5 py-3 font-semibold leading-5 text-white disabled:cursor-not-allowed disabled:opacity-50 sm:w-auto sm:px-6" @click="selectGame">
                {{ selecting ? copy.selecting : copy.select }}
              </button>
              <a :href="game.bggUrl" target="_blank" rel="noopener noreferrer" class="min-h-12 w-full rounded-xl border border-ink/15 px-5 py-3 text-center text-sm font-semibold text-indigo sm:w-auto">{{ copy.source }} ↗</a>
            </div>
          </div>
        </div>
        <div v-if="game" data-testid="game-long-details" class="min-w-0 border-t border-ink/10 pt-6">
          <p v-if="translating" class="text-xs font-semibold text-copper" role="status">{{ copy.translating }}</p>
          <p v-else-if="game.descriptionTranslated" class="text-xs font-semibold text-copper">{{ copy.translation }}</p>
          <p v-else-if="locale === 'zh-CN' && game.description" class="text-xs font-semibold text-copper" role="status">{{ copy.untranslated }}</p>
          <p v-if="game.description" :class="locale === 'zh-CN' ? 'mt-2' : ''" class="max-w-5xl whitespace-pre-line text-[0.95rem] leading-8 text-ink/68 [overflow-wrap:anywhere]">{{ game.description }}</p>
          <dl v-if="game.designers.length || game.publishers.length || game.mechanics.length || game.categories.length" class="mt-6 grid gap-4 border-t border-ink/10 pt-5 text-sm sm:grid-cols-2 lg:grid-cols-4">
            <div v-if="game.designers.length"><dt class="font-semibold text-muted">{{ copy.designers }}</dt><dd class="mt-1 leading-6">{{ game.designers.join('、') }}</dd></div>
            <div v-if="game.publishers.length"><dt class="font-semibold text-muted">{{ copy.publishers }}</dt><dd class="mt-1 leading-6">{{ game.publishers.join('、') }}</dd></div>
            <div v-if="game.mechanics.length"><dt class="font-semibold text-muted">{{ copy.mechanics }}<span v-if="game.mechanicsTranslated" class="font-medium text-copper"> · {{ copy.metadataTranslation }}</span></dt><dd class="mt-1 leading-6">{{ game.mechanics.join('、') }}</dd></div>
            <div v-if="game.categories.length"><dt class="font-semibold text-muted">{{ copy.categories }}<span v-if="game.categoriesTranslated" class="font-medium text-copper"> · {{ copy.metadataTranslation }}</span></dt><dd class="mt-1 leading-6">{{ game.categories.join('、') }}</dd></div>
          </dl>
          <p class="mt-5 text-xs leading-5 text-muted">{{ copy.evidenceBoundary }}</p>
        </div>
        <div v-if="game" class="min-w-0 border-t border-ink/10 pt-6">
          <div class="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
            <div>
              <h2 class="font-display text-2xl font-semibold">{{ copy.editionImages }}</h2>
              <p class="mt-1 text-sm text-muted">{{ copy.editionImagesHint }}</p>
            </div>
            <div class="flex flex-col items-start gap-2 sm:items-end">
              <a :href="`${game.bggUrl}/images`" target="_blank" rel="noopener noreferrer" class="text-sm font-semibold text-indigo">{{ copy.imageGallery }} ↗</a>
              <a href="https://boardgamegeek.com" target="_blank" rel="noopener noreferrer"><img src="/powered-by-bgg-rgb.svg" alt="Powered by BoardGameGeek" class="h-auto w-[137px]" width="342" height="76"></a>
            </div>
          </div>
          <ul v-if="game.editionImages.length" class="-mx-4 mt-5 flex snap-x gap-4 overflow-x-auto px-4 pb-3 sm:mx-0 sm:px-0">
            <li v-for="editionImage in game.editionImages" :key="editionImage.versionId" class="game-tile w-40 shrink-0 snap-start bg-canvas p-3 sm:w-44">
              <img :src="editionImage.imageUrl" :alt="editionImage.name" loading="lazy" referrerpolicy="no-referrer" class="aspect-[4/5] w-full object-contain">
              <p class="mt-2 truncate text-sm font-semibold" :title="editionImage.name">{{ editionImage.name }}</p>
              <p v-if="editionImage.publicationYear || editionImage.languages.length" class="mt-1 text-xs text-muted">
                {{ [editionImage.publicationYear, ...editionImage.languages].filter(Boolean).join(' · ') }}
              </p>
            </li>
          </ul>
          <div class="mt-5 rounded-xl border border-ink/10 bg-canvas px-4 py-3 text-sm">
            <a :href="`${game.bggUrl}/files`" target="_blank" rel="noopener noreferrer" class="font-semibold text-indigo">{{ copy.communityFiles }} ↗</a>
            <p class="mt-1 text-xs leading-5 text-muted">{{ copy.communityFilesHint }}</p>
          </div>
        </div>
      </section>

      <div v-if="errorMessage" class="mt-6 rounded-xl border border-red-200 bg-red-50 p-5 text-sm text-red-700" role="alert">
        <p>{{ errorMessage }}</p>
        <button v-if="!game" type="button" class="mt-3 font-semibold underline" @click="load">{{ copy.retry }}</button>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.game-detail-hero {
  grid-template-areas:
    'cover identity'
    'stats stats'
    'actions actions';
  grid-template-columns: minmax(0, 7.25rem) minmax(0, 1fr);
  column-gap: 1rem;
  row-gap: 1rem;
}

.game-detail-summary {
  display: contents;
}

.game-detail-cover {
  grid-area: cover;
}

.game-detail-identity {
  grid-area: identity;
}

.game-detail-stats {
  grid-area: stats;
}

.game-detail-actions {
  grid-area: actions;
}

@media (min-width: 640px) {
  .game-detail-hero {
    grid-template-columns: minmax(0, 10rem) minmax(0, 1fr);
    column-gap: 1.5rem;
    row-gap: 1.25rem;
  }
}

@media (min-width: 1024px) {
  .game-detail-hero {
    grid-template-areas: 'cover summary';
    grid-template-columns: 19rem minmax(0, 1fr);
    gap: 2rem;
  }

  .game-detail-summary {
    display: flex;
    grid-area: summary;
    min-width: 0;
    flex-direction: column;
    justify-content: center;
  }

  .game-detail-identity {
    align-self: stretch;
  }

  .game-detail-stats {
    margin-top: 1.25rem;
  }

  .game-detail-actions {
    margin-top: 1.5rem;
  }
}
</style>
