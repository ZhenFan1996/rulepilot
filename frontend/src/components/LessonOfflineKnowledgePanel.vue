<script setup lang="ts">
import type { OfflineCitation, OfflineKnowledgeEntry } from '@/lib/offlineKnowledge'
import { useLocale } from '@/lib/locale'

defineProps<{
  entries: OfflineKnowledgeEntry[]
}>()

const { locale, t } = useLocale()

function citationPages(citation: OfflineCitation) {
  if (citation.pageFrom === citation.pageTo) {
    return t('lesson.reader.offline.pageSingle', { page: citation.pageFrom })
  }
  return t('lesson.reader.offline.pageRange', { from: citation.pageFrom, to: citation.pageTo })
}

function cachedAtLabel(value: string) {
  return new Intl.DateTimeFormat(locale.value === 'en' ? 'en-US' : 'zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function entryCountLabel(count: number) {
  return t(count === 1 ? 'lesson.reader.offline.count.one' : 'lesson.reader.offline.count.many', { count })
}
</script>

<template>
  <section class="mx-auto max-w-4xl px-5 pt-7 sm:px-8" aria-labelledby="offline-knowledge-title">
    <div class="rounded-3xl border border-amber-300 bg-amber-50 p-5 sm:p-6">
      <p class="text-xs font-semibold text-copper">{{ t('lesson.reader.offline.eyebrow') }}</p>
      <div class="mt-2 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 id="offline-knowledge-title" class="font-display text-2xl font-semibold">{{ t('lesson.reader.offline.title') }}</h2>
          <p class="mt-2 text-sm leading-6 text-amber-950/70">{{ t('lesson.reader.offline.description') }}</p>
        </div>
        <span class="rounded-full bg-amber-900 px-3 py-1.5 text-xs font-semibold text-white">{{ entryCountLabel(entries.length) }}</span>
      </div>
      <div class="mt-5 stack-y-md">
        <details v-for="entry in entries" :key="`${entry.question}-${entry.cachedAt}`" class="rounded-2xl border border-amber-200 bg-paper p-4">
          <summary class="cursor-pointer list-none">
            <span class="flex flex-wrap items-start justify-between gap-3">
              <span>
                <span class="block font-semibold leading-6">{{ entry.question }}</span>
              </span>
              <span class="text-xs font-semibold text-ink/45">{{ entry.ruling ? t('lesson.reader.offline.confirmedRuling') : t('lesson.reader.offline.recentAnswer') }} · {{ cachedAtLabel(entry.cachedAt) }}</span>
            </span>
          </summary>
          <p class="mt-4 border-t border-ink/10 pt-4 font-display text-lg font-semibold leading-7">{{ entry.ruling?.shortVerdict ?? entry.answer.shortVerdict }}</p>
          <p class="mt-3 text-sm leading-7 text-ink/70">{{ entry.ruling?.explanation ?? entry.answer.explanation }}</p>
          <ol class="mt-4 stack-y-sm">
            <li v-for="citation in (entry.ruling?.citations ?? entry.answer.citations)" :key="citation.chunkId" class="rounded-xl bg-indigo/5 p-3 text-sm">
              <p class="font-semibold text-indigo">{{ citation.heading }} · {{ citationPages(citation) }}</p>
              <p class="mt-1 leading-6 text-ink/60">{{ citation.excerpt }}</p>
            </li>
          </ol>
        </details>
      </div>
    </div>
  </section>
</template>
