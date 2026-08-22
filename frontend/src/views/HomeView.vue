<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import { useLocale, type AppLocale } from '@/lib/locale'

interface HotGame {
  rank: number
  bggId: number
  name: string
  originalName: string
  nameLocalized: boolean
  publicationYear: number | null
  thumbnailUrl: string
  bggUrl: string
}

const { locale, t } = useLocale()
const hotGames = ref<HotGame[]>([])
const gameError = ref<boolean | null>(null)
const randomOffset = ref(0)
let activeGamesController: AbortController | null = null

const featuredGames = computed(() => hotGames.value.slice(0, 4))
const randomGames = computed(() => {
  if (!hotGames.value.length) return []
  const source = hotGames.value.slice(4).length >= 3 ? hotGames.value.slice(4) : hotGames.value
  return Array.from(
    { length: Math.min(3, source.length) },
    (_, index) => source[(randomOffset.value + index) % source.length]!,
  )
})

function shuffleRandomGames() {
  const size = hotGames.value.length > 4 ? hotGames.value.length - 4 : hotGames.value.length
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
    randomOffset.value = hotGames.value.length > 4
      ? Math.floor(Math.random() * (hotGames.value.length - 4))
      : 0
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
  <AppShell v-slot="{ username }">
    <div class="tabletop-page home-page max-w-7xl">
      <section class="home-intro tabletop-illustrated-hero player-board" aria-labelledby="home-title">
        <div class="home-intro__copy">
          <p class="tabletop-kicker">{{ username ? t('home.greetingNamed', { username }) : t('home.greeting') }}</p>
          <h1 id="home-title" class="home-intro__title">{{ t('home.title') }}</h1>
          <p class="home-intro__lede">{{ t('home.description') }}</p>

          <div class="home-intro__actions">
            <RouterLink
              :to="{ name: 'teach' }"
              class="home-primary-action inline-flex min-h-12 items-center justify-center gap-2 rounded-xl bg-copper px-6 font-semibold text-white transition hover:bg-copper-dark focus-visible:outline-offset-4"
            >
              <TabletopGlyph name="rulebook" :size="18" />
              {{ t('home.rulebookAction') }}
            </RouterLink>
            <RouterLink
              :to="{ name: 'game-recommendations' }"
              class="inline-flex min-h-12 items-center justify-center gap-2 rounded-xl border border-ink/15 bg-paper px-6 font-semibold text-indigo transition hover:border-indigo/45 hover:bg-canvas/70"
            >
              {{ t('home.discoverAction') }}
              <TabletopGlyph name="arrow" :size="17" />
            </RouterLink>
          </div>
        </div>

        <figure class="home-intro__art" aria-hidden="true">
          <img
            src="/illustrations/home-screenprint-friends.webp"
            alt=""
            width="1536"
            height="1024"
            fetchpriority="high"
          >
        </figure>
      </section>

      <section class="home-hot" aria-labelledby="hot-games-title">
        <header class="home-hot__heading">
          <div class="tabletop-heading">
            <p class="tabletop-kicker">{{ t('home.hotEyebrow') }}</p>
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
          class="home-hot__state"
          :class="{ 'home-hot__state--notice': gameError !== null }"
          :role="gameError === true ? 'alert' : 'status'"
        >
          <p>{{ t(gameError === null ? 'home.hotLoading' : gameError ? 'home.hotMissing' : 'home.hotEmpty') }}</p>
          <button v-if="gameError === true" type="button" class="mt-3 min-h-11 font-semibold text-indigo underline" @click="loadGames">{{ t('account.retry') }}</button>
        </div>
        <ol v-else class="home-game-grid">
          <li v-for="game in featuredGames" :key="game.bggId">
            <RouterLink :to="{ name: 'game-discovery', params: { bggId: game.bggId } }" class="home-game-card group">
              <span class="home-game-card__cover">
                <img :src="game.thumbnailUrl" :alt="game.name" loading="lazy" referrerpolicy="no-referrer">
                <span class="home-game-card__rank">#{{ game.rank }}</span>
              </span>
              <span class="home-game-card__body">
                <strong class="home-game-card__title">{{ game.name }}</strong>
                <span v-if="game.nameLocalized && game.originalName !== game.name" class="home-game-card__original">{{ game.originalName }}</span>
                <span class="home-game-card__meta">{{ game.publicationYear ?? t('home.unknownYear') }}</span>
                <span class="home-game-card__action">{{ t('home.hotInspect', { game: game.name }) }} <TabletopGlyph name="arrow" :size="15" /></span>
              </span>
            </RouterLink>
          </li>
        </ol>

        <RouterLink :to="{ name: 'game-catalog-browse' }" class="home-catalog-link">
          {{ t('home.browseCatalog') }} <TabletopGlyph name="arrow" :size="16" />
        </RouterLink>
      </section>

      <section v-if="randomGames.length" class="home-random" aria-labelledby="random-games-title">
        <div class="home-random__copy">
          <p class="tabletop-kicker !text-gold">{{ t('home.randomEyebrow') }}</p>
          <h2 id="random-games-title" class="home-random__title">{{ t('home.randomTitle') }}</h2>
          <p class="home-random__hint">{{ t('home.randomHint') }}</p>
          <button type="button" class="home-random__shuffle" @click="shuffleRandomGames">
            <span aria-hidden="true">↻</span> {{ t('home.randomShuffle') }}
          </button>
        </div>

        <ul class="home-random__games">
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
.home-page {
  display: grid;
  gap: clamp(3.75rem, 6.5vw, 6.5rem);
}

.home-intro {
  display: grid;
  box-shadow: 0 28px 74px -58px color-mix(in srgb, var(--color-ink-panel) 72%, transparent);
}

.home-intro__copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  justify-content: center;
  padding: clamp(1.55rem, 4vw, 3.5rem);
  background: var(--color-paper);
}

.home-intro__title {
  max-width: 11.5ch;
  margin: 0.8rem 0 0;
  font-family: var(--font-display);
  font-size: clamp(2.55rem, 4.2vw, 4.15rem);
  font-weight: 650;
  line-height: 1.03;
  letter-spacing: -0.042em;
  text-wrap: balance;
}

.home-intro__lede {
  max-width: 38rem;
  margin: 1.25rem 0 0;
  color: color-mix(in srgb, var(--color-ink) 64%, transparent);
  font-size: 0.97rem;
  line-height: 1.82;
}

.home-intro__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin-top: 1.5rem;
}

.home-intro__art {
  position: relative;
  min-height: clamp(15rem, 68vw, 17rem);
  margin: 0;
  overflow: hidden;
  border-top: 1px solid color-mix(in srgb, var(--color-ink) 12%, transparent);
  background: #efe2c9;
}

.home-intro__art img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: 70% center;
}

.home-section-title {
  margin: 0;
  font-family: var(--font-display);
  font-size: clamp(2rem, 3.5vw, 3.25rem);
  font-weight: 650;
  line-height: 1.08;
  letter-spacing: -0.035em;
  text-wrap: balance;
}

.home-hot {
  display: grid;
  gap: 1.6rem;
}

.home-hot__heading {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.home-bgg-mark {
  display: inline-flex;
  width: fit-content;
  min-height: 3rem;
  align-items: center;
  gap: 0.75rem;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  padding: 0.45rem 0.8rem;
  color: #5f5b52;
  background: #fffaf0;
  font-size: 0.7rem;
  font-weight: 700;
}

.home-bgg-mark img { width: 7.4rem; height: auto; }

.home-hot__state {
  margin: 0;
  border: 1px dashed color-mix(in srgb, var(--color-gold) 48%, var(--color-border));
  border-radius: 1rem;
  padding: 1.5rem;
  color: color-mix(in srgb, var(--color-ink) 56%, transparent);
  background: color-mix(in srgb, var(--color-paper) 84%, transparent);
  font-size: 0.9rem;
  line-height: 1.7;
}

.home-hot__state--notice { border-style: solid; }

.home-game-grid {
  display: grid;
  gap: 1rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.home-game-card {
  display: grid;
  height: 100%;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 1.15rem 1.15rem 0.75rem 0.75rem;
  color: inherit;
  background: var(--color-paper);
  box-shadow: 0 18px 42px -38px color-mix(in srgb, var(--color-ink-panel) 68%, transparent);
  transition: translate 180ms ease, border-color 180ms ease, box-shadow 180ms ease;
}

.home-game-card:hover {
  border-color: color-mix(in srgb, var(--color-copper) 52%, var(--color-border));
  box-shadow: 0 24px 46px -34px color-mix(in srgb, var(--color-ink-panel) 72%, transparent);
  translate: 0 -0.25rem;
}

.home-game-card__cover {
  position: relative;
  display: grid;
  aspect-ratio: 4 / 3;
  place-items: center;
  overflow: hidden;
  border-bottom: 1px solid var(--color-border);
  background: color-mix(in srgb, var(--color-canvas) 78%, var(--color-paper));
}

.home-game-card__cover img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  padding: 0.85rem;
  transition: scale 220ms ease;
}

.home-game-card:hover .home-game-card__cover img { scale: 1.035; }

.home-game-card__rank {
  position: absolute;
  top: 0.65rem;
  left: 0.65rem;
  display: grid;
  min-width: 2.3rem;
  height: 2.3rem;
  place-items: center;
  border: 2px solid var(--color-paper);
  border-radius: 999px;
  padding-inline: 0.3rem;
  color: white;
  background: var(--color-copper);
  font-size: 0.67rem;
  font-weight: 800;
}

.home-game-card__body {
  display: flex;
  min-height: 9.5rem;
  min-width: 0;
  flex-direction: column;
  padding: 1rem;
}

.home-game-card__title {
  font-family: var(--font-display);
  font-size: 1.12rem;
  line-height: 1.25;
}

.home-game-card__original,
.home-game-card__meta {
  margin-top: 0.25rem;
  color: color-mix(in srgb, var(--color-ink) 46%, transparent);
  font-size: 0.7rem;
  line-height: 1.45;
}

.home-game-card__action {
  display: inline-flex;
  align-items: center;
  gap: 0.2rem;
  margin-top: auto;
  padding-top: 0.8rem;
  color: var(--color-indigo);
  font-size: 0.73rem;
  font-weight: 700;
}

.home-catalog-link {
  display: inline-flex;
  width: fit-content;
  min-height: 2.75rem;
  align-items: center;
  gap: 0.3rem;
  color: var(--color-indigo);
  font-size: 0.88rem;
  font-weight: 700;
}

.home-random {
  position: relative;
  display: grid;
  gap: 1.75rem;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--color-gold) 24%, transparent);
  border-radius: 1.8rem 1.8rem 1rem 1rem;
  padding: clamp(1.5rem, 3.6vw, 3rem);
  color: #fffaf0;
  background:
    radial-gradient(circle at 1px 1px, rgb(255 250 240 / 7%) 0 0.6px, transparent 0.75px) 0 0 / 12px 12px,
    var(--color-ink-panel);
}

.home-random::after {
  position: absolute;
  right: -8rem;
  bottom: -10rem;
  width: 26rem;
  aspect-ratio: 1;
  border: 4rem solid rgb(196 154 80 / 8%);
  border-radius: 50%;
  content: '';
  pointer-events: none;
}

.home-random__copy,
.home-random__games { position: relative; z-index: 1; }

.home-random__title {
  margin: 0.7rem 0 0;
  font-family: var(--font-display);
  font-size: clamp(2rem, 3.3vw, 3rem);
  font-weight: 650;
  line-height: 1.08;
}

.home-random__hint {
  max-width: 28rem;
  margin: 0.9rem 0 0;
  color: rgb(255 250 240 / 60%);
  font-size: 0.86rem;
  line-height: 1.7;
}

.home-random__shuffle {
  display: inline-flex;
  min-height: 2.75rem;
  align-items: center;
  gap: 0.45rem;
  margin-top: 1.25rem;
  border-radius: 0.85rem;
  padding-inline: 1.1rem;
  color: #222b28;
  background: var(--color-gold);
  font-size: 0.84rem;
  font-weight: 750;
  transition: background-color 160ms ease, translate 160ms ease;
}

.home-random__shuffle:hover { background: #dfbd78; translate: 0 -0.1rem; }

.home-random__games {
  display: grid;
  gap: 0.8rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.home-random-card {
  display: flex;
  min-width: 0;
  height: 100%;
  min-height: 7.25rem;
  align-items: center;
  gap: 0.8rem;
  border: 1px solid rgb(255 250 240 / 12%);
  border-radius: 1rem;
  padding: 0.75rem;
  color: #fffaf0;
  background: rgb(255 250 240 / 6%);
  transition: border-color 160ms ease, background-color 160ms ease, translate 160ms ease;
}

.home-random-card:hover {
  border-color: rgb(211 172 98 / 42%);
  background: rgb(255 250 240 / 10%);
  translate: 0 -0.18rem;
}

.home-random-card img {
  width: 4.5rem;
  height: 5.5rem;
  flex: none;
  border-radius: 0.65rem;
  object-fit: contain;
  background: #fffaf0;
}

.home-random-card strong {
  display: block;
  overflow-wrap: anywhere;
  font-family: var(--font-display);
  font-size: 1rem;
  line-height: 1.25;
}

.home-random-card small {
  display: block;
  margin-top: 0.45rem;
  color: rgb(255 250 240 / 48%);
  font-size: 0.68rem;
}

@media (min-width: 640px) {
  .home-game-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (min-width: 1024px) {
  .home-intro {
    min-height: 24rem;
    grid-template-columns: minmax(0, 1fr) minmax(22rem, 0.95fr);
  }
  .home-intro__art {
    min-height: 100%;
    border-top: 0;
    border-left: 1px solid color-mix(in srgb, var(--color-ink) 12%, transparent);
  }
  .home-hot__heading { flex-direction: row; align-items: end; justify-content: space-between; }
  .home-game-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }
  .home-random { grid-template-columns: minmax(15rem, 0.7fr) minmax(0, 1.7fr); align-items: center; }
  .home-random__games { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .home-random-card { align-items: flex-start; flex-direction: column; }
  .home-random-card img { width: 100%; height: 8rem; }
  .home-random-card :deep(svg) { display: none; }
}

@media (max-width: 639px) {
  .home-page { gap: 3.75rem; }
  .home-intro {
    grid-template-areas:
      "art"
      "copy";
    background: var(--color-paper);
  }
  .home-intro__copy {
    grid-area: copy;
    padding: 1.35rem 1.25rem 1.4rem;
  }
  .home-intro__title { max-width: none; font-size: 2.32rem; line-height: 1.06; }
  .home-intro__lede { margin-top: 1rem; font-size: 0.91rem; line-height: 1.72; }
  .home-intro__actions { flex-direction: column; margin-top: 1rem; }
  .home-intro__art {
    grid-area: art;
    min-height: clamp(11.5rem, 48vw, 12.25rem);
    aspect-ratio: auto;
    margin: 0;
    border: 0;
    border-bottom: 1px solid color-mix(in srgb, var(--color-ink) 14%, transparent);
    border-radius: 0;
    box-shadow: none;
  }
  .home-game-card { grid-template-columns: 7.25rem minmax(0, 1fr); }
  .home-game-card__cover { aspect-ratio: auto; min-height: 9rem; border-right: 1px solid var(--color-border); border-bottom: 0; }
  .home-game-card__body { min-height: 9rem; }
}

@media (prefers-reduced-motion: reduce) {
  .home-game-card,
  .home-game-card__cover img,
  .home-random__shuffle,
  .home-random-card { transition: none; }
}
</style>
