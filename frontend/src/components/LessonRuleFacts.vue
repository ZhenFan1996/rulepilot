<script setup lang="ts">
import { useLocale } from '@/lib/locale'

export type RuleFactRole =
  | 'PREREQUISITE'
  | 'CHOICE'
  | 'ACTION'
  | 'COST_OR_GAIN'
  | 'TIMING'
  | 'LIMIT'
  | 'RESULT'
  | 'EXCEPTION'
  | 'TABLE_STATE'
  | 'EXAMPLE_STATE'

export interface LessonRuleFact {
  position: number
  role: RuleFactRole
  text: string
  sourcePages?: number[]
}

defineProps<{ facts: LessonRuleFact[] }>()

const { t } = useLocale()

function roleLabel(role: RuleFactRole) {
  return t(`lesson.ruleFact.${role}`)
}
</script>

<template>
  <dl v-if="facts.length" data-testid="lesson-rule-facts" class="mt-4 grid gap-2 sm:grid-cols-2">
    <div v-for="fact in facts" :key="`${fact.position}-${fact.role}`" class="rounded-xl border border-ink/10 bg-canvas/75 px-3 py-3">
      <dt class="text-[11px] font-bold uppercase tracking-[0.1em] text-copper">{{ roleLabel(fact.role) }}</dt>
      <dd class="mt-1 text-sm leading-6 text-ink/72">{{ fact.text }}</dd>
    </div>
  </dl>
</template>
