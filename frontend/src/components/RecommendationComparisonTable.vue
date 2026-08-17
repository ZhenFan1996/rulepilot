<script setup lang="ts">
import { computed } from 'vue'

import type { CandidateComparison } from '@/components/gameRecommendationTypes'
import SafeMarkdown from '@/components/SafeMarkdown.vue'
import { useLocale } from '@/lib/locale'

const props = defineProps<{
  comparison: CandidateComparison
  responseLocale?: 'zh-CN' | 'en'
}>()

const { locale } = useLocale()
const displayLocale = computed(() => props.responseLocale ?? locale.value)
const labels = computed(() => displayLocale.value === 'zh-CN'
  ? {
      title: '并排核对',
      unknown: '现有资料不足，暂不判断',
      taxonomy: '仅为 BGG 分类，不能单独证明实际桌感或规则',
      attributed_report: '有来源的玩家体验',
      rulebook_fact: '规则书事实',
      structured_metadata: '结构化资料',
    }
  : {
      title: 'Side-by-side check',
      unknown: 'Unknown from the available evidence',
      taxonomy: 'BGG classification only; it does not by itself prove play feel or rules',
      attributed_report: 'Sourced player report',
      rulebook_fact: 'Rulebook fact',
      structured_metadata: 'Structured metadata',
    })

function candidateName(bggId: number) {
  return props.comparison.candidates.find(candidate => candidate.game.bggId === bggId)?.game.name ?? `BGG ${bggId}`
}
</script>

<template>
  <section class="recommendation-comparison" data-testid="candidate-comparison">
    <div class="comparison-heading">
      <p class="tabletop-rule comparison-title">{{ labels.title }}</p>
    </div>
    <div class="comparison-scroll">
      <table class="comparison-table">
        <thead>
          <tr class="comparison-head-row">
            <th scope="col" class="comparison-axis-heading" />
            <th v-for="candidate in comparison.candidates" :key="candidate.game.bggId" scope="col" class="comparison-candidate-heading">
              {{ candidate.game.name }}
              <span v-if="candidate.game.nameLocalized" class="comparison-original-name">{{ candidate.game.originalName }}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="axis in comparison.axes" :key="axis.subject" class="comparison-axis-row">
            <th scope="row" class="comparison-axis-name">
              <SafeMarkdown :source="axis.label" class="comparison-axis-label" />
              <span class="comparison-capability">{{ labels[axis.capability] }}</span>
            </th>
            <td v-for="cell in axis.cells" :key="`${axis.subject}-${cell.bggId}`" class="comparison-cell" :aria-label="`${candidateName(cell.bggId)} · ${axis.label}`">
              <SafeMarkdown v-if="cell.status === 'observed'" :source="cell.value" class="comparison-observation" />
              <span v-else class="comparison-unknown"><span aria-hidden="true" class="comparison-unknown-mark">?</span>{{ labels.unknown }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.recommendation-comparison {
  margin-top: 0.75rem;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--color-ink) 10%, transparent);
  border-radius: 1rem;
  background: color-mix(in srgb, var(--color-canvas) 55%, transparent);
}

.comparison-heading {
  padding: 0.75rem 1rem;
  border-bottom: 1px solid color-mix(in srgb, var(--color-ink) 8%, transparent);
}

.comparison-title {
  color: var(--color-copper);
}

.comparison-scroll {
  overflow-x: auto;
}

.comparison-table {
  width: 100%;
  min-width: 42rem;
  border-collapse: collapse;
  font-size: 0.875rem;
  text-align: left;
}

.comparison-head-row {
  background: color-mix(in srgb, var(--color-paper) 70%, transparent);
}

.comparison-axis-heading,
.comparison-candidate-heading,
.comparison-axis-name,
.comparison-cell {
  padding: 0.75rem 1rem;
  border-color: color-mix(in srgb, var(--color-ink) 8%, transparent);
}

.comparison-axis-heading {
  width: 12rem;
  border-right-width: 1px;
  border-bottom-width: 1px;
  font-size: 0.75rem;
  font-weight: 700;
  color: color-mix(in srgb, var(--color-ink) 55%, transparent);
}

.comparison-candidate-heading {
  min-width: 13rem;
  border-bottom-width: 1px;
  font-family: var(--font-display);
  font-size: 1rem;
  font-weight: 600;
  color: var(--color-ink);
}

.comparison-original-name {
  display: block;
  margin-top: 0.125rem;
  font-family: var(--font-sans);
  font-size: 0.6875rem;
  font-weight: 400;
  color: color-mix(in srgb, var(--color-ink) 45%, transparent);
}

.comparison-axis-row {
  vertical-align: top;
}

.comparison-axis-name {
  border-top-width: 1px;
  border-right-width: 1px;
}

.comparison-axis-label,
.comparison-capability {
  display: block;
}

.comparison-axis-label {
  font-weight: 600;
  color: color-mix(in srgb, var(--color-ink) 72%, transparent);
}

.comparison-capability {
  margin-top: 0.25rem;
  font-size: 0.6875rem;
  font-weight: 400;
  line-height: 1rem;
  color: color-mix(in srgb, var(--color-ink) 43%, transparent);
}

.comparison-cell {
  border-top-width: 1px;
  line-height: 1.5rem;
}

.comparison-observation {
  color: color-mix(in srgb, var(--color-ink) 72%, transparent);
}

.comparison-unknown {
  display: inline-flex;
  align-items: flex-start;
  gap: 0.375rem;
  color: color-mix(in srgb, var(--color-ink) 48%, transparent);
}

.comparison-unknown-mark {
  font-weight: 700;
}

@media (min-width: 40rem) {
  .comparison-heading {
    padding-inline: 1.25rem;
  }
}
</style>
