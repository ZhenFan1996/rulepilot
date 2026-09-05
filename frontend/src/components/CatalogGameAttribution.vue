<script setup lang="ts">
import { computed } from 'vue'

import type { CatalogGamePresentation } from '@/lib/catalogGamePresentation'
import { useLocale } from '@/lib/locale'

const props = defineProps<{ presentation: CatalogGamePresentation }>()
const { t } = useLocale()

const playerCount = computed(() => {
  const minimum = props.presentation.minPlayers
  const maximum = props.presentation.maxPlayers
  if (minimum === null || maximum === null) return ''
  return minimum === maximum
    ? t('lesson.catalog.playersExact', { players: minimum })
    : t('lesson.catalog.playersRange', { min: minimum, max: maximum })
})
</script>

<template>
  <aside data-testid="catalog-game-presentation" class="mt-4 border-b border-ink/10 px-1 pb-4 text-sm text-muted">
    <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
      <span v-if="presentation.publicationYear" class="font-semibold text-ink/75">{{ presentation.publicationYear }}</span>
      <span v-if="playerCount">{{ playerCount }}</span>
      <span v-if="presentation.playingTimeMinutes !== null">{{ t('lesson.catalog.minutes', { minutes: presentation.playingTimeMinutes }) }}</span>
      <span v-if="presentation.minimumAge !== null">{{ t('lesson.catalog.age', { age: presentation.minimumAge }) }}</span>
      <a :href="presentation.bggUrl" target="_blank" rel="noopener noreferrer" class="font-semibold text-indigo underline underline-offset-2">{{ t('lesson.catalog.attribution') }}</a>
    </div>
    <p class="mt-1 text-xs leading-5 text-muted">{{ t('lesson.catalog.evidenceBoundary') }}</p>
  </aside>
</template>
