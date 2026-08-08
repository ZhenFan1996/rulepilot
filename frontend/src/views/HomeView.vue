<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import { useLocale } from '@/lib/locale'
import { groupPlansForReading, playerFacingTitle } from '@/lib/lessonPresentation'

interface TeachingPlan {
  id: string
  gameTitle: string
  playerCount: number
  durationMinutes: number
  createdAt: string
}

const username = ref('')
const plans = ref<TeachingPlan[]>([])
const { t } = useLocale()
const latestPlan = computed(() => plans.value[0] ?? null)
const latestPlanTitle = computed(() => latestPlan.value ? playerFacingTitle(latestPlan.value.gameTitle) : '')
const recentPlanCount = computed(() => groupPlansForReading(plans.value).length)

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

onMounted(loadPersonalHome)
</script>

<template>
  <AppShell>
    <main class="home-stage tabletop-page">
      <section class="start-board" aria-labelledby="home-title">
        <div class="start-board__path" aria-hidden="true">
          <span class="path-token path-token--one">1</span>
          <span class="path-token path-token--two">2</span>
          <span class="path-token path-token--three">3</span>
        </div>

        <header class="relative z-10 max-w-3xl">
          <p class="home-kicker">{{ username ? t('home.personal', { username }) : t('home.greeting') }}</p>
          <h1 id="home-title" class="home-title">{{ latestPlan ? t('home.continueTitle') : t('home.startTitle') }}</h1>
          <p class="home-lede">{{ latestPlan ? t('home.continueDescription') : t('home.startDescription') }}</p>
        </header>

        <div class="relative z-10 mt-9 grid gap-4 lg:grid-cols-[minmax(0,1.45fr)_minmax(17rem,0.55fr)]">
          <RouterLink
            v-if="latestPlan"
            :to="{ name: 'lesson', params: { planId: latestPlan.id } }"
            class="primary-card group"
          >
            <span class="primary-card__number" aria-hidden="true">01</span>
            <span class="primary-card__label">{{ t('home.lastOpened') }}</span>
            <strong class="primary-card__title">{{ latestPlanTitle }}</strong>
            <span class="primary-card__meta">{{ t('home.duration', { players: latestPlan.playerCount, minutes: latestPlan.durationMinutes }) }}</span>
            <span class="primary-card__action">{{ t('home.continue') }} <TabletopGlyph name="arrow" :size="20" /></span>
          </RouterLink>

          <RouterLink v-else :to="{ name: 'game-recommendations' }" class="primary-card group">
            <span class="primary-card__number" aria-hidden="true">01</span>
            <span class="primary-card__label">{{ t('home.primaryEyebrow') }}</span>
            <strong class="primary-card__title">{{ t('home.primaryTitle') }}</strong>
            <span class="primary-card__meta">{{ t('home.primaryDescription') }}</span>
            <span class="primary-card__action">{{ t('home.primaryAction') }} <TabletopGlyph name="arrow" :size="20" /></span>
          </RouterLink>

          <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-1">
            <RouterLink :to="{ name: latestPlan ? 'game-recommendations' : 'game-catalog-browse' }" class="side-card side-card--blue group">
              <span class="side-card__token"><TabletopGlyph name="meeple" :size="22" /></span>
              <span>
                <strong>{{ latestPlan ? t('home.discoverAnother') : t('home.browseCatalog') }}</strong>
                <small>{{ latestPlan ? t('home.discoverHint') : t('home.browseCatalogHint') }}</small>
              </span>
              <TabletopGlyph name="arrow" :size="18" class="ml-auto transition-transform group-hover:translate-x-1" />
            </RouterLink>
            <RouterLink :to="{ name: 'teach' }" class="side-card side-card--yellow group">
              <span class="side-card__token"><TabletopGlyph name="rulebook" :size="22" /></span>
              <span>
                <strong>{{ t('home.upload') }}</strong>
                <small>{{ t('home.uploadHint') }}</small>
              </span>
              <TabletopGlyph name="arrow" :size="18" class="ml-auto transition-transform group-hover:translate-x-1" />
            </RouterLink>
          </div>
        </div>

        <footer class="relative z-10 mt-7 flex flex-col gap-3 border-t border-ink/12 pt-5 text-sm text-ink/55 sm:flex-row sm:items-center sm:justify-between">
          <p>{{ latestPlan ? t('home.keep') : t('home.noSetup') }}</p>
          <div class="flex flex-wrap gap-x-5 gap-y-2 font-semibold text-indigo">
            <RouterLink :to="{ name: 'public-library' }">{{ t('home.public') }} →</RouterLink>
            <RouterLink v-if="recentPlanCount" :to="{ name: 'lessons' }">{{ t('home.allMine', { count: recentPlanCount }) }} →</RouterLink>
          </div>
        </footer>
      </section>
    </main>
  </AppShell>
</template>

<style scoped>
.home-stage {
  display: grid;
  min-height: calc(100vh - 3rem);
  align-items: center;
}

.start-board {
  position: relative;
  isolation: isolate;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--color-ink) 14%, transparent);
  border-radius: 2rem;
  padding: clamp(1.5rem, 4.5vw, 4.75rem);
  background:
    linear-gradient(115deg, color-mix(in srgb, var(--color-paper) 98%, transparent) 0 64%, color-mix(in srgb, var(--color-indigo) 10%, var(--color-paper)) 64% 100%);
  box-shadow: 0 30px 90px -58px rgb(24 34 67 / 65%);
}

.start-board::before {
  position: absolute;
  top: 0;
  left: 0;
  width: 0.75rem;
  height: 100%;
  background: var(--color-copper);
  content: '';
}

.home-kicker {
  color: var(--color-copper);
  font-size: 0.75rem;
  font-weight: 800;
  letter-spacing: 0.15em;
  text-transform: uppercase;
}

.home-title {
  max-width: 13ch;
  margin-top: 0.75rem;
  font-family: var(--font-display);
  font-size: clamp(2.7rem, 6.5vw, 5.6rem);
  font-weight: 650;
  line-height: 0.94;
  letter-spacing: -0.055em;
}

.home-lede {
  max-width: 37rem;
  margin-top: 1.25rem;
  color: color-mix(in srgb, var(--color-ink) 62%, transparent);
  font-size: 1rem;
  line-height: 1.8;
}

.start-board__path {
  position: absolute;
  z-index: -1;
  top: -4rem;
  right: -2rem;
  width: min(38vw, 31rem);
  aspect-ratio: 1;
  border: 5rem solid color-mix(in srgb, var(--color-indigo) 10%, transparent);
  border-radius: 50%;
}

.path-token {
  position: absolute;
  display: grid;
  width: 3.25rem;
  aspect-ratio: 1;
  place-items: center;
  border: 4px solid var(--color-paper);
  border-radius: 50%;
  color: #20252c;
  background: var(--color-gold);
  box-shadow: 0 6px 0 rgb(32 37 44 / 12%);
  font-size: 0.75rem;
  font-weight: 900;
}

.path-token--one { bottom: -1.3rem; left: 2rem; }
.path-token--two { right: 1rem; bottom: 5rem; color: white; background: var(--color-copper); }
.path-token--three { top: 2rem; left: 1rem; color: white; background: var(--color-indigo); }

.primary-card {
  position: relative;
  display: flex;
  min-height: 20rem;
  flex-direction: column;
  overflow: hidden;
  border-radius: 1.5rem 1.5rem 3.5rem 1.5rem;
  padding: clamp(1.5rem, 3vw, 2.5rem);
  color: #fffaf2;
  background: var(--color-ink-panel);
  box-shadow: 0 18px 0 color-mix(in srgb, var(--color-indigo) 28%, transparent);
  transition: translate 180ms ease, box-shadow 180ms ease;
}

.primary-card:hover {
  box-shadow: 0 22px 0 color-mix(in srgb, var(--color-indigo) 38%, transparent);
  translate: 0 -4px;
}

.primary-card::after {
  position: absolute;
  right: -2.4rem;
  bottom: -3.2rem;
  width: 12rem;
  aspect-ratio: 1;
  border: 2.3rem solid var(--color-indigo);
  border-radius: 50%;
  content: '';
}

.primary-card__number {
  position: absolute;
  top: 1.4rem;
  right: 1.6rem;
  color: var(--color-gold);
  font-size: 0.72rem;
  font-weight: 900;
  letter-spacing: 0.14em;
}

.primary-card__label {
  color: var(--color-gold);
  font-size: 0.72rem;
  font-weight: 800;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.primary-card__title {
  position: relative;
  z-index: 1;
  max-width: 15ch;
  margin-top: 1.2rem;
  font-family: var(--font-display);
  font-size: clamp(2.1rem, 4vw, 3.8rem);
  font-weight: 650;
  line-height: 1;
  letter-spacing: -0.04em;
}

.primary-card__meta {
  position: relative;
  z-index: 1;
  max-width: 34rem;
  margin-top: 1rem;
  color: rgb(255 250 242 / 62%);
  line-height: 1.6;
}

.primary-card__action {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 0.6rem;
  margin-top: auto;
  padding-top: 2rem;
  font-weight: 750;
}

.side-card {
  display: flex;
  min-height: 9.5rem;
  align-items: center;
  gap: 1rem;
  border: 1px solid rgb(32 37 44 / 12%);
  border-radius: 1.25rem;
  padding: 1.25rem;
  color: #20252c;
  transition: translate 180ms ease, box-shadow 180ms ease;
}

.side-card:hover { box-shadow: -8px 8px 0 rgb(32 37 44 / 8%); translate: 4px 0; }
.side-card--blue { background: color-mix(in srgb, var(--color-indigo) 18%, #fffaf2); }
.side-card--yellow { background: color-mix(in srgb, var(--color-gold) 54%, #fffaf2); }
.side-card__token { display: grid; width: 3rem; aspect-ratio: 1; flex: none; place-items: center; border-radius: 50%; color: white; background: var(--color-indigo); }
.side-card--yellow .side-card__token { background: var(--color-copper); }
.side-card strong { display: block; font-family: var(--font-display); font-size: 1.2rem; }
.side-card small { display: block; margin-top: 0.35rem; color: rgb(32 37 44 / 58%); line-height: 1.45; }

@media (max-width: 47.99rem) {
  .start-board { border-radius: 1.3rem; }
  .start-board__path { width: 16rem; opacity: 0.65; }
  .primary-card { min-height: 17rem; }
}

@media (prefers-reduced-motion: reduce) {
  .primary-card,
  .side-card { transition: none; }
}
</style>
