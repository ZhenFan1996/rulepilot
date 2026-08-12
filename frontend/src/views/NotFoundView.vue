<script setup lang="ts">
import { RouterLink } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import { useLocale } from '@/lib/locale'

const { t } = useLocale()
</script>

<template>
  <AppShell>
    <section class="not-found-page tabletop-page" aria-labelledby="not-found-title">
      <div class="not-found-card player-board">
        <div class="not-found-copy">
          <p class="tabletop-kicker">404 · {{ t('notFound.eyebrow') }}</p>
          <h1 id="not-found-title" class="tabletop-title">{{ t('notFound.title') }}</h1>
          <p class="mt-5 max-w-2xl text-base leading-8 text-ink/65 sm:text-lg">{{ t('notFound.description') }}</p>
          <div class="mt-8 flex flex-col gap-3 sm:flex-row">
            <RouterLink :to="{ name: 'home' }" class="inline-flex min-h-12 items-center justify-center gap-2 rounded-xl bg-copper px-6 font-semibold text-white transition hover:bg-copper-dark">
              {{ t('notFound.home') }}
              <TabletopGlyph name="arrow" :size="17" />
            </RouterLink>
            <RouterLink :to="{ name: 'game-recommendations' }" class="inline-flex min-h-12 items-center justify-center rounded-xl border border-ink/15 bg-paper px-6 font-semibold text-indigo transition hover:border-indigo/45 hover:bg-canvas/70">
              {{ t('notFound.discover') }}
            </RouterLink>
          </div>
        </div>
        <figure class="not-found-art" aria-hidden="true">
          <img src="/illustrations/rulebook-reading.webp" alt="" width="1536" height="1024">
        </figure>
      </div>
    </section>
  </AppShell>
</template>

<style scoped>
.not-found-page {
  display: grid;
  min-height: min(46rem, calc(100vh - 5rem));
  place-items: center;
}

.not-found-card {
  display: grid;
  width: min(100%, 68rem);
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--color-ink) 12%, transparent);
  background: var(--color-paper);
  box-shadow: 0 30px 70px -54px rgb(24 35 34 / 70%);
}

.not-found-copy {
  padding: clamp(2rem, 6vw, 5rem);
}

.not-found-art {
  min-height: 16rem;
  margin: 0;
  border-top: 1px solid color-mix(in srgb, var(--color-ink) 12%, transparent);
  background: var(--color-felt-deep);
}

.not-found-art img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0.82;
}

@media (min-width: 768px) {
  .not-found-card {
    grid-template-columns: minmax(0, 1.1fr) minmax(18rem, 0.9fr);
  }

  .not-found-art {
    min-height: 30rem;
    border-top: 0;
    border-left: 1px solid color-mix(in srgb, var(--color-ink) 12%, transparent);
  }
}
</style>
