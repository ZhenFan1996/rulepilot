<script setup lang="ts">
import { RouterLink } from 'vue-router'

import { useLocale } from '@/lib/locale'

const props = defineProps<{
  planId: string
  guideRoute: string
  questionsRoute: string
  active: 'guide' | 'questions'
}>()

const { t } = useLocale()
</script>

<template>
  <nav
    class="flex shrink-0 items-center rounded-xl border border-ink/10 bg-paper/75 p-1 elevation-sm"
    :aria-label="t('questions.modeNav')"
  >
    <RouterLink
      :to="{ name: props.guideRoute, params: { planId } }"
      data-testid="lesson-guide-mode"
      class="inline-flex min-h-9 items-center rounded-lg px-3 text-xs font-bold transition focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-gold/40 sm:text-sm"
      :class="active === 'guide' ? 'bg-ink-panel text-panel-text' : 'text-muted hover:bg-canvas hover:text-ink'"
      :aria-current="active === 'guide' ? 'page' : undefined"
    >
      {{ t('questions.guideTab') }}
    </RouterLink>
    <RouterLink
      :to="{ name: props.questionsRoute, params: { planId } }"
      data-testid="lesson-questions-entry"
      class="inline-flex min-h-9 items-center gap-1.5 rounded-lg px-3 text-xs font-bold transition focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-gold/40 sm:text-sm"
      :class="active === 'questions' ? 'bg-ink-panel text-panel-text' : 'text-muted hover:bg-canvas hover:text-ink'"
      :aria-current="active === 'questions' ? 'page' : undefined"
    >
      <span class="grid size-5 place-items-center rounded-full bg-gold text-[11px] text-ink" aria-hidden="true">?</span>
      {{ t('questions.questionTab') }}
    </RouterLink>
  </nav>
</template>
