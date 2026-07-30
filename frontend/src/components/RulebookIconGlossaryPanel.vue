<script setup lang="ts">
import { computed } from 'vue'

import { useLocale } from '@/lib/locale'
import type {
  IconGlossaryStatus,
  IconGlossaryWarning,
  RulebookIconGlossary,
} from '@/lib/rulebookIconGlossary'

const props = defineProps<{
  glossary: RulebookIconGlossary | null
  loading: boolean
  errorMessage: string
  canGenerate: boolean
  generating: boolean
  online: boolean
  imageUrl: (occurrenceId: string) => string
}>()

const emit = defineEmits<{
  generate: []
  retry: []
}>()

const { t } = useLocale()

const statusText = computed(() => {
  const status = props.glossary?.status
  if (!status) return ''
  return statusLabel(status)
})

const progressText = computed(() => {
  const glossary = props.glossary
  if (!glossary || glossary.totalPages === 0) return ''
  return t('iconGlossary.progress', {
    complete: glossary.completePages,
    inspected: glossary.inspectedPages,
    total: glossary.totalPages,
  })
})

function statusLabel(status: IconGlossaryStatus) {
  return {
    NOT_STARTED: t('iconGlossary.status.notStarted'),
    GENERATING: t('iconGlossary.status.generating'),
    READY: t('iconGlossary.status.ready'),
    PARTIAL: t('iconGlossary.status.partial'),
    UNAVAILABLE: t('iconGlossary.status.unavailable'),
  }[status]
}

function warningMessage(warning: IconGlossaryWarning) {
  return {
    INCOMPLETE_PAGE_SCAN: t('iconGlossary.warning.incomplete'),
    UNEXPLAINED_ICONS: t('iconGlossary.warning.unexplained'),
    CONFLICTING_EXPLANATIONS: t('iconGlossary.warning.conflict'),
  }[warning]
}

function pageLabel(pageNumbers: number[]) {
  return pageNumbers.length === 1
    ? t('iconGlossary.page', { page: pageNumbers[0] ?? '' })
    : t('iconGlossary.pages', { pages: pageNumbers.join('、') })
}
</script>

<template>
  <section class="mt-8 overflow-hidden rounded-3xl border border-ink/10 bg-paper shadow-sm" aria-labelledby="icon-glossary-title">
    <details :open="Boolean(glossary?.icons.length) || loading || generating || Boolean(errorMessage)">
      <summary class="flex min-h-14 cursor-pointer list-none items-center justify-between gap-4 px-5 py-4 marker:hidden sm:px-7">
        <span class="min-w-0">
          <span class="block text-xs font-bold uppercase tracking-[0.13em] text-copper">{{ t('iconGlossary.eyebrow') }}</span>
          <span id="icon-glossary-title" class="mt-1 block font-display text-2xl font-semibold">{{ t('iconGlossary.title') }}</span>
        </span>
        <span v-if="statusText" class="shrink-0 rounded-full bg-canvas px-3 py-1.5 text-xs font-semibold text-indigo">{{ statusText }}</span>
      </summary>

      <div class="border-t border-ink/10 px-5 py-5 sm:px-7 sm:py-6">
        <p class="max-w-2xl text-sm leading-6 text-ink/60">{{ t('iconGlossary.description') }}</p>

        <div v-if="loading && !glossary" class="mt-5 rounded-2xl bg-canvas p-4" role="status" aria-live="polite">
          <p class="font-semibold">{{ t('iconGlossary.loading') }}</p>
          <p class="mt-1 text-sm leading-6 text-ink/55">{{ t('iconGlossary.loadingDetail') }}</p>
        </div>

        <div v-else-if="errorMessage" class="mt-5 rounded-2xl border border-red-200 bg-red-50 p-4" role="alert">
          <p class="font-semibold text-red-800">{{ t('iconGlossary.error.title') }}</p>
          <p class="mt-1 text-sm leading-6 text-red-700">{{ errorMessage }}</p>
          <button v-if="online" type="button" class="mt-3 min-h-11 rounded-xl border border-red-300 px-4 text-sm font-semibold text-red-800" @click="emit('retry')">{{ t('iconGlossary.retry') }}</button>
        </div>

        <template v-else-if="glossary">
          <div v-if="glossary.status === 'GENERATING' || generating" class="mt-5 rounded-2xl border border-indigo/15 bg-indigo/[0.045] p-4" role="status" aria-live="polite">
            <div class="flex items-center gap-3">
              <span class="size-3 shrink-0 animate-pulse rounded-full bg-copper" />
              <p class="font-semibold">{{ t('iconGlossary.generating.title') }}</p>
            </div>
            <p class="mt-2 text-sm leading-6 text-ink/60">{{ progressText || t('iconGlossary.generating.detail') }}</p>
            <p class="mt-1 text-xs leading-5 text-ink/45">{{ t('iconGlossary.generating.safeToLeave') }}</p>
          </div>

          <div v-if="glossary.warnings.length" class="mt-5 space-y-2" role="status">
            <p v-for="warning in glossary.warnings" :key="warning" class="rounded-2xl border border-amber-300 bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-950">
              {{ warningMessage(warning) }}
            </p>
          </div>

          <div v-if="glossary.icons.length" class="mt-6 grid gap-4 sm:grid-cols-2">
            <article v-for="icon in glossary.icons" :key="icon.id" class="overflow-hidden rounded-2xl border border-ink/10 bg-canvas">
              <div class="flex min-h-36 items-center justify-center border-b border-ink/10 bg-white/70 p-4">
                <img
                  :src="imageUrl(icon.representativeOccurrenceId)"
                  :alt="t('iconGlossary.iconAlt', { name: icon.name })"
                  class="max-h-32 max-w-full object-contain"
                  loading="lazy"
                >
              </div>
              <div class="p-4">
                <div class="flex flex-wrap items-start justify-between gap-2">
                  <h3 class="font-display text-xl font-semibold">{{ icon.name }}</h3>
                  <span class="rounded-full px-2.5 py-1 text-xs font-semibold" :class="icon.meaningStatus === 'EXPLICIT' ? 'bg-indigo/10 text-indigo' : 'bg-amber-100 text-amber-950'">
                    {{ icon.meaningStatus === 'EXPLICIT' ? t('iconGlossary.meaning.explicit') : t('iconGlossary.meaning.unexplained') }}
                  </span>
                </div>
                <p class="mt-2 text-sm leading-6 text-ink/55">{{ icon.visualDescription }}</p>
                <p v-if="icon.explanation" class="mt-3 leading-7 text-ink/80">{{ icon.explanation }}</p>
                <p v-else class="mt-3 rounded-xl bg-amber-50 px-3 py-2 text-sm leading-6 text-amber-950">{{ t('iconGlossary.unexplainedDetail') }}</p>
                <blockquote v-if="icon.evidenceText" class="mt-3 border-l-2 border-indigo/35 pl-3 text-sm leading-6 text-ink/60">
                  {{ icon.evidenceText }}
                </blockquote>
                <p class="mt-3 text-xs font-medium text-ink/45">
                  {{ pageLabel([...new Set(icon.occurrences.map((occurrence) => occurrence.pageNumber))]) }}
                  · {{ t('iconGlossary.occurrences', { count: icon.occurrences.length }) }}
                </p>
              </div>
            </article>
          </div>

          <div v-else-if="glossary.status === 'READY'" class="mt-5 rounded-2xl bg-canvas p-4">
            <p class="font-semibold">{{ t('iconGlossary.empty.title') }}</p>
            <p class="mt-1 text-sm leading-6 text-ink/55">{{ t('iconGlossary.empty.detail') }}</p>
          </div>

          <div v-else-if="glossary.status === 'UNAVAILABLE'" class="mt-5 rounded-2xl border border-amber-300 bg-amber-50 p-4">
            <p class="font-semibold text-amber-950">{{ t('iconGlossary.unavailable.title') }}</p>
            <p class="mt-1 text-sm leading-6 text-amber-950/80">{{ t('iconGlossary.unavailable.detail') }}</p>
          </div>

          <div v-else-if="glossary.status === 'NOT_STARTED' && !generating" class="mt-5 rounded-2xl bg-canvas p-4">
            <p class="font-semibold">{{ t('iconGlossary.notStarted.title') }}</p>
            <p class="mt-1 text-sm leading-6 text-ink/55">{{ t('iconGlossary.notStarted.detail') }}</p>
          </div>

          <div v-if="canGenerate && glossary.status !== 'UNAVAILABLE' && glossary.status !== 'READY'" class="mt-5 flex flex-wrap items-center gap-3">
            <button type="button" :disabled="generating || glossary.status === 'GENERATING' || !online" class="min-h-11 rounded-xl bg-copper px-4 text-sm font-semibold text-white transition hover:-translate-y-0.5 disabled:cursor-not-allowed disabled:opacity-50" @click="emit('generate')">
              {{ generating || glossary.status === 'GENERATING' ? t('iconGlossary.generating.action') : glossary.status === 'NOT_STARTED' ? t('iconGlossary.generate') : t('iconGlossary.regenerate') }}
            </button>
            <p v-if="!online" class="text-sm text-amber-900">{{ t('iconGlossary.offline') }}</p>
            <p v-else-if="progressText" class="text-xs text-ink/45">{{ progressText }}</p>
          </div>
        </template>
      </div>
    </details>
  </section>
</template>
