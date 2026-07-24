<script setup lang="ts">
import { useLocale } from '@/lib/locale'

type EvidenceStatus = 'SUPPORTED' | 'CITED_DRAFT' | 'INSUFFICIENT_EVIDENCE'

interface ReaderChapter {
  position: number
  title: string
  evidenceStatus: EvidenceStatus
}

defineProps<{
  section: ReaderChapter
  sectionCount: number
  outcome: string
  lessonStillGrowing: boolean
  readingCurrentLastChapter: boolean
}>()

const emit = defineEmits<{
  askQuestion: []
}>()

const { t } = useLocale()

function evidenceLabel(status: EvidenceStatus) {
  if (status === 'INSUFFICIENT_EVIDENCE') return t('lesson.reader.evidence.insufficient.label')
  if (status === 'CITED_DRAFT') return t('lesson.reader.evidence.draft.label')
  return t('lesson.reader.evidence.supported.label')
}

function evidenceDetail(status: EvidenceStatus) {
  if (status === 'INSUFFICIENT_EVIDENCE') return t('lesson.reader.evidence.insufficient.detail')
  if (status === 'CITED_DRAFT') return t('lesson.reader.evidence.draft.detail')
  return t('lesson.reader.evidence.supported.detail')
}
</script>

<template>
  <div>
    <div class="flex flex-wrap items-start justify-between gap-4 border-b border-ink/8 pb-5">
      <div class="max-w-3xl">
        <p class="text-xs font-semibold text-copper">{{ t('lesson.reader.chapter.position', { position: section.position, total: sectionCount }) }}</p>
        <h2 class="mt-2 font-display text-3xl font-semibold leading-tight sm:text-4xl">{{ section.title }}</h2>
        <p class="mt-3 hidden max-w-2xl text-sm leading-6 text-ink/55 sm:block">{{ t('lesson.reader.chapter.outcome', { outcome }) }}</p>
        <button
          type="button"
          class="mt-4 inline-flex min-h-11 items-center rounded-xl bg-indigo px-4 text-sm font-semibold text-white shadow-sm transition hover:-translate-y-0.5 hover:bg-indigo/90"
          @click="emit('askQuestion')"
        >
          {{ t('lesson.reader.chapter.ask') }}
        </button>
      </div>
      <details class="relative hidden text-xs sm:block">
        <summary class="cursor-pointer list-none rounded-full border border-ink/10 px-3 py-2 font-semibold text-ink/55">
          {{ evidenceLabel(section.evidenceStatus) }}
        </summary>
        <div class="absolute right-0 z-10 mt-2 w-56 rounded-xl border border-ink/10 bg-paper p-3 leading-5 text-ink/60 shadow-lg">
          {{ evidenceDetail(section.evidenceStatus) }}
        </div>
      </details>
    </div>
    <div v-if="lessonStillGrowing && readingCurrentLastChapter" class="mt-5 rounded-2xl border border-indigo/15 bg-indigo/5 p-4 text-sm leading-6 text-indigo" role="status">
      <p class="font-semibold">{{ t('lesson.reader.chapter.growing.title') }}</p>
      <p class="mt-1 text-ink/55">{{ t('lesson.reader.chapter.growing.detail') }}</p>
    </div>
  </div>
</template>
