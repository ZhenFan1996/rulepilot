<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import tabletopPrint from '@/assets/illustrations/tabletop-print.webp'
import AppShell from '@/components/AppShell.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import ProgressiveCatalogCover from '@/components/ProgressiveCatalogCover.vue'
import { useLocale, type AppLocale } from '@/lib/locale'

interface HotGame {
  rank: number
  bggId: number
  name: string
  originalName: string
  nameLocalized: boolean
  publicationYear: number | null
  minPlayers: number | null
  maxPlayers: number | null
  playingTimeMinutes: number | null
  thumbnailUrl: string
  bggUrl: string
}

const { locale, t } = useLocale()
const hotGames = ref<HotGame[]>([])
const gameError = ref<boolean | null>(null)
const randomOffset = ref(0)
let activeGamesController: AbortController | null = null

const browseTypes = [
  { type: 'family', zh: '家庭游戏', en: 'Family' },
  { type: 'party', zh: '聚会游戏', en: 'Party' },
  { type: 'strategy', zh: '策略游戏', en: 'Strategy' },
  { type: 'children', zh: '儿童游戏', en: 'Children’s' },
  { type: 'thematic', zh: '主题游戏', en: 'Thematic' },
  { type: 'war', zh: '战争游戏', en: 'War' },
]

const featuredGames = computed(() => hotGames.value.slice(0, 7))
const randomSource = computed(() => hotGames.value.slice(7).length >= 3 ? hotGames.value.slice(7) : hotGames.value)
const randomGames = computed(() => {
  if (!hotGames.value.length) return []
  const source = randomSource.value
  return Array.from(
    { length: Math.min(3, source.length) },
    (_, index) => source[(randomOffset.value + index) % source.length]!,
  )
})

function shuffleRandomGames() {
  const size = randomSource.value.length
  if (size <= 1) return
  randomOffset.value = (randomOffset.value + 1 + Math.floor(Math.random() * (size - 1))) % size
}

function isCurrentGamesRequest(requestedLocale: AppLocale, controller: AbortController) {
  return activeGamesController === controller
    && locale.value === requestedLocale
}

async function loadGames() {
  const requestedLocale = locale.value
  activeGamesController?.abort()
  const controller = new AbortController()
  activeGamesController = controller
  gameError.value = null
  hotGames.value = []
  try {
    const hotResponse = await fetch(
      `/api/v1/bgg/recommendations?locale=${requestedLocale}`,
      { credentials: 'include', signal: controller.signal },
    )
    if (!hotResponse.ok) throw new Error('hot games unavailable')
    const responseGames = (await hotResponse.json() as HotGame[])
      .filter(game => game.thumbnailUrl)
      .slice(0, 12)
    if (!isCurrentGamesRequest(requestedLocale, controller)) return
    hotGames.value = responseGames
    randomOffset.value = Math.floor(Math.random() * randomSource.value.length) || 0
    gameError.value = false
  } catch {
    if (!isCurrentGamesRequest(requestedLocale, controller)) return
    gameError.value = true
  }
}

watch(locale, loadGames, { immediate: true })
onBeforeUnmount(() => {
  activeGamesController?.abort()
  activeGamesController = null
})
</script>

<template>
  <AppShell>
    <div class="tabletop-page home-page max-w-7xl">
      <section class="home-intro" aria-labelledby="home-title">
        <div class="home-intro__copy">
          <h1 id="home-title" class="home-intro__title">{{ t('home.title') }}</h1>

        </div>
        <img :src="tabletopPrint" alt="" aria-hidden="true" class="home-intro__illustration" width="300" height="200">
        <div class="home-intro__actions">
            <RouterLink
              :to="{ name: 'teach' }"
              class="home-primary-action inline-flex min-h-12 items-center justify-center gap-2 rounded-xl bg-copper px-6 font-semibold text-on-accent transition hover:bg-copper-dark focus-visible:outline-offset-4"
            >
              <TabletopGlyph name="rulebook" :size="18" />
              {{ t('home.rulebookAction') }}
            </RouterLink>
            <RouterLink
              :to="{ name: 'game-recommendations' }"
              class="inline-flex min-h-12 items-center justify-center gap-2 rounded-xl border border-ink/15 bg-paper px-6 font-semibold text-indigo transition hover:border-indigo/45 hover:bg-canvas/70"
            >
              {{ t('home.discoverAction') }}
            </RouterLink>
        </div>
      </section>

      <nav class="home-types" :aria-label="locale === 'zh-CN' ? '按类型浏览桌游' : 'Browse games by type'">
        <span>{{ locale === 'zh-CN' ? '按类型逛' : 'Browse by type' }}</span>
        <RouterLink v-for="category in browseTypes" :key="category.type" :to="{ name: 'game-catalog-browse', query: { type: category.type } }">
          {{ locale === 'zh-CN' ? category.zh : category.en }}
        </RouterLink>
      </nav>

      <section class="home-hot" aria-labelledby="hot-games-title">
        <header class="home-hot__heading">
          <div class="tabletop-heading">
            <h2 id="hot-games-title" class="home-section-title">{{ t('home.hotTitle') }}</h2>
            <p class="tabletop-lede">{{ t('home.hotHint') }}</p>
          </div>
          <a
            href="https://boardgamegeek.com/hotness"
            target="_blank"
            rel="noopener noreferrer"
            class="home-bgg-mark"
          >
            <span>{{ t('home.source') }}</span>
            <img src="/powered-by-bgg-rgb.svg" alt="Powered by BoardGameGeek" width="342" height="76">
          </a>
        </header>

        <div
          v-if="gameError !== false || !hotGames.length"
          :class="gameError === null ? 'sr-only' : 'home-hot__state'"
          :role="gameError === true ? 'alert' : 'status'"
        >
          <p>{{ t(gameError === null ? 'home.hotLoading' : gameError ? 'home.hotMissing' : 'home.hotEmpty') }}</p>
          <button v-if="gameError === true" type="button" class="mt-3 min-h-11 font-semibold text-indigo underline" @click="loadGames">{{ t('account.retry') }}</button>
        </div>
        <div v-if="gameError === null" class="home-game-grid" aria-hidden="true">
          <div v-for="slot in 7" :key="slot" class="home-game-card">
            <div class="home-game-card__cover bg-ink/5" />
            <div class="home-game-card__body"><div class="h-4 w-3/4 rounded bg-ink/10" /><div class="mt-2 h-3 w-1/2 rounded bg-ink/5" /></div>
          </div>
        </div>
        <ol v-else-if="featuredGames.length" class="home-game-grid">
          <li v-for="game in featuredGames" :key="game.bggId">
            <RouterLink :to="{ name: 'game-discovery', params: { bggId: game.bggId } }" class="home-game-card group">
              <span class="home-game-card__rank">{{ game.rank }}</span>
              <span class="home-game-card__cover">
                <ProgressiveCatalogCover :bgg-id="game.bggId" :alt="game.name" :source-thumbnail="game.thumbnailUrl" class="absolute inset-0 size-full" />
              </span>
              <span class="home-game-card__body">
                <strong class="home-game-card__title">{{ game.name }}</strong>
                <span v-if="game.nameLocalized && game.originalName !== game.name" class="home-game-card__original">{{ game.originalName }}</span>
                <span class="home-game-card__meta">{{ game.publicationYear ?? t('home.unknownYear') }}</span>
                <span class="home-game-card__facts">
                  <span v-if="game.minPlayers && game.maxPlayers"><TabletopGlyph name="players" :size="15" />{{ game.minPlayers }}–{{ game.maxPlayers }} {{ locale === 'zh-CN' ? '人' : 'players' }}</span>
                  <span v-if="game.playingTimeMinutes"><TabletopGlyph name="timer" :size="15" />{{ game.playingTimeMinutes }} {{ locale === 'zh-CN' ? '分钟' : 'min' }}</span>
                </span>
              </span>
            </RouterLink>
          </li>
        </ol>

        <RouterLink :to="{ name: 'game-catalog-browse' }" class="home-catalog-link">
          {{ t('home.browseCatalog') }} <TabletopGlyph name="arrow" :size="16" />
        </RouterLink>
      </section>

      <section v-if="randomGames.length || gameError === null" class="home-random" aria-labelledby="random-games-title">
        <div class="home-random__copy">
          <h2 id="random-games-title" class="home-random__title">{{ t('home.randomTitle') }}</h2>
          <p class="home-random__hint">{{ t('home.randomHint') }}</p>
          <button type="button" :disabled="!randomGames.length" class="home-random__shuffle disabled:opacity-50" @click="shuffleRandomGames">
            <span aria-hidden="true">↻</span> {{ t('home.randomShuffle') }}
          </button>
        </div>

        <div v-if="gameError === null" class="home-random__games" aria-hidden="true">
          <div v-for="slot in 3" :key="slot" class="home-random-card">
            <div class="h-22 w-18 shrink-0 rounded bg-ink/5" /><div class="h-4 w-1/2 rounded bg-ink/10" />
          </div>
        </div>
        <ul v-else class="home-random__games">
          <li v-for="game in randomGames" :key="`${randomOffset}-${game.bggId}`">
            <RouterLink :to="{ name: 'game-discovery', params: { bggId: game.bggId } }" class="home-random-card">
              <img :src="game.thumbnailUrl" :alt="game.name" loading="lazy" referrerpolicy="no-referrer">
              <span class="min-w-0">
                <strong>{{ game.name }}</strong>
                <small>#{{ game.rank }} · {{ game.publicationYear ?? t('home.unknownYear') }}</small>
              </span>
              <TabletopGlyph name="arrow" :size="16" class="ml-auto shrink-0 text-gold" />
            </RouterLink>
          </li>
        </ul>
      </section>
    </div>
  </AppShell>
</template>

<style scoped>
.home-page { display: grid; gap: 1.5rem; }
.home-intro__illustration { display: none; width: 100%; height: 100px; object-fit: contain; }
.home-intro { display: grid; align-items: center; gap: 2rem; padding-block: 0.25rem 1rem; }
.home-intro__title { margin: 0; font-size: clamp(2.2rem, 4.4vw, 3.75rem); font-weight: 750; letter-spacing: -0.03em; line-height: 1.3; }
.home-intro__actions { display: flex; flex-wrap: wrap; gap: 0.75rem; }
.home-types { display: flex; flex-wrap: wrap; align-items: center; gap: 0; border-block: 1px solid var(--color-border); font-size: 0.875rem; }
.home-types > span { color: var(--color-muted); padding: 0.75rem 1rem; }
.home-types a { display: inline-flex; align-items: center; min-height: 3rem; flex: 1; justify-content: center; gap: 0.55rem; padding: 0.35rem 0.75rem; color: var(--color-ink); font-weight: 600; }
.home-types a:hover { color: var(--color-indigo); text-decoration: underline; text-underline-offset: 5px; }
.home-section-title, .home-random__title { margin: 0; font-size: 1.4rem; font-weight: 650; line-height: 1.4; }
.home-hot { display: grid; gap: 1.5rem; }
.home-hot__heading { display: flex; flex-wrap: wrap; align-items: end; justify-content: space-between; gap: 1rem; }
.home-bgg-mark { display: inline-flex; align-items: center; gap: 0.75rem; min-height: 2.75rem; font-size: 0.7rem; color: var(--color-muted); }
.home-bgg-mark img { width: 7.4rem; height: auto; border-radius: 0.25rem; background: #fff; padding: 0.25rem; }
.home-hot__state { border: 1px solid var(--color-border); border-radius: 0.5rem; padding: 1.5rem; color: var(--color-muted); font-size: 0.9rem; line-height: 1.7; }
.home-game-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1.5rem; margin: 0; padding: 0; list-style: none; }
.home-game-card { position: relative; padding-left: 1.5rem; display: grid; height: 100%; min-width: 0; color: inherit; }
.home-game-card__cover { position: relative; display: grid; aspect-ratio: 1; place-items: center; overflow: hidden; border-radius: 0.35rem; background: var(--color-paper); }
.home-game-card__cover :deep(img) { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: contain; padding: 1.2rem; filter: drop-shadow(0 9px 6px rgb(20 45 31 / 16%)); }
.home-game-card__rank { position: absolute; top: 0.2rem; left: 0; color: var(--color-ink); font-size: 0.9rem; font-weight: 600; font-variant-numeric: tabular-nums; }
.home-game-card__body { display: flex; min-height: 5.5rem; min-width: 0; flex-direction: column; padding-top: 0.9rem; }
.home-game-card__title { font-size: 1rem; line-height: 1.5; font-weight: 650; }
.home-game-card:hover .home-game-card__title { color: var(--color-indigo); text-decoration: underline; text-underline-offset: 3px; }
.home-game-card__original, .home-game-card__meta { margin-top: 0.25rem; color: var(--color-muted); font-size: 0.75rem; line-height: 1.5; }
.home-game-card__facts { display: flex; flex-wrap: wrap; gap: 0.8rem; margin-top: 0.75rem; font-size: 0.8rem; color: var(--color-muted); }
.home-game-card__facts > span { display: inline-flex; align-items: center; gap: 0.35rem; }
.home-catalog-link { display: inline-flex; align-items: center; gap: 0.3rem; width: fit-content; min-height: 2.75rem; color: var(--color-indigo); font-size: 0.875rem; font-weight: 600; }
.home-random { display: grid; gap: 1.5rem; padding: 1.75rem 0; border-top: 1px solid var(--color-border); }
.home-random__hint { margin-top: 0.5rem; max-width: 30rem; color: var(--color-muted); font-size: 0.875rem; line-height: 1.7; }
.home-random__shuffle { display: inline-flex; align-items: center; gap: 0.5rem; min-height: 2.75rem; margin-top: 0.5rem; color: var(--color-indigo); font-size: 0.875rem; font-weight: 600; }
.home-random__games { display: grid; gap: 1rem; margin: 0; padding: 0; list-style: none; }
.home-random-card { display: flex; align-items: center; gap: 1rem; min-width: 0; height: 100%; color: inherit; }
.home-random-card img { flex: none; width: 4.5rem; height: 5.5rem; object-fit: contain; border-radius: 0.25rem; background: var(--color-paper); }
.home-random-card strong { display: block; font-size: 0.9rem; line-height: 1.5; overflow-wrap: anywhere; }
.home-random-card small { display: block; margin-top: 0.3rem; font-size: 0.75rem; color: var(--color-muted); }
.home-random-card:hover strong { color: var(--color-indigo); }
@media (min-width: 1024px) {
  .home-intro { grid-template-columns: minmax(0, 1fr) 150px auto; gap: 2rem; padding-bottom: 0; }
  .home-intro__illustration { display: block; }
  .home-intro__actions { flex-direction: row; }
  .home-random { grid-template-columns: 14rem minmax(0, 1fr); align-items: center; }
  .home-game-grid { grid-template-columns: minmax(0, 1.15fr) repeat(2, minmax(0, 1fr)); column-gap: 1.75rem; row-gap: 1rem; }
  .home-game-grid > li:first-child { grid-row: span 3; }
  .home-game-grid > li:first-child .home-game-card__cover { aspect-ratio: 1; }
  .home-game-grid > li:first-child .home-game-card__cover :deep(img) { padding: 1.4rem 2rem; }
  .home-game-grid > li:first-child .home-game-card { align-content: start; }
  .home-game-grid > li:not(:first-child) .home-game-card__facts { gap: 0.35rem 0.6rem; margin-top: 0.4rem; }
  .home-game-grid > li:not(:first-child) .home-game-card__title { font-size: 0.9rem; }
  .home-game-grid > li:first-child .home-game-card__title { font-size: 1.3rem; }
  .home-game-grid > li:not(:first-child) .home-game-card { grid-template-columns: 6.25rem minmax(0, 1fr); align-items: center; gap: 0.8rem; padding-bottom: 1rem; }
  .home-game-grid > li:not(:first-child) .home-game-card__body { min-height: 0; padding-top: 0; }
  .home-game-grid > li:not(:first-child) .home-game-card__cover :deep(img) { padding: 0.5rem; }
  .home-game-grid > li:not(:first-child) .home-game-card__rank { top: 0; left: 0; }

  .home-random__games { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 1.5rem; }
}
@media (max-width: 639px) {
  .home-page { gap: 1.25rem; }
  .home-types { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); font-size: 0.75rem; }
  .home-types > span { display: none; }
  .home-types a { border-left: 0; padding: 0.25rem; gap: 0.25rem; min-height: 2.75rem; }
  .home-types svg { width: 17px; height: 17px; }
  .home-hot__heading { flex-wrap: nowrap; align-items: center; gap: 0.75rem; }
  .home-hot__heading .tabletop-lede { font-size: 0.75rem; }
  .home-bgg-mark { flex: none; }
  .home-bgg-mark > span { display: none; }
  .home-bgg-mark img { width: 5.75rem; }
  .home-intro { padding-top: 0; gap: 1.25rem; }
  .home-random { padding: 1.25rem 0; }
  .home-intro__actions > a { flex: 1; padding-inline: 0.75rem; }
  .home-game-grid { gap: 1rem; }
  }
</style>
