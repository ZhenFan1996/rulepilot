<script setup lang="ts">
import { RouterLink } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import { useLocale } from '@/lib/locale'

const { t } = useLocale()
</script>

<template>
  <AppShell>
    <section class="tabletop-page grid min-h-[calc(100vh-var(--app-top-inset)-5rem)] place-items-center" aria-labelledby="not-found-title">
      <div class="not-found-card player-board w-full max-w-3xl overflow-hidden border border-ink/10 bg-paper elevation-sm">
        <div class="min-w-0 flex-1 p-8 sm:p-12 lg:p-16">
          <p class="tabletop-kicker">404 · {{ t('notFound.eyebrow') }}</p>
          <h1 id="not-found-title" class="tabletop-title">{{ t('notFound.title') }}</h1>
          <p class="mt-5 max-w-2xl text-base leading-8 text-ink/65 sm:text-lg">{{ t('notFound.description') }}</p>
          <div class="mt-8 flex flex-col gap-3 sm:flex-row">
            <RouterLink :to="{ name: 'home' }" class="inline-flex min-h-12 items-center justify-center gap-2 rounded-xl bg-copper px-6 font-semibold text-on-accent transition hover:bg-copper-dark">
              {{ t('notFound.home') }}
              <TabletopGlyph name="arrow" :size="17" />
            </RouterLink>
            <RouterLink :to="{ name: 'game-recommendations' }" class="inline-flex min-h-12 items-center justify-center rounded-xl border border-ink/15 bg-paper px-6 font-semibold text-indigo transition hover:border-indigo/45 hover:bg-canvas/70">
              {{ t('notFound.discover') }}
            </RouterLink>
          </div>
        </div>
      </div>
    </section>
  </AppShell>
</template>
