<script setup lang="ts">
import { computed } from 'vue'

import { useLocale } from '@/lib/locale'
import type { OfficialImportCopy, OfficialRulebookImportJob } from './types'

const props = defineProps<{
  officialImportJob: OfficialRulebookImportJob | null
  officialImportCopy: OfficialImportCopy
  message: string
  preparingVersionId: string
  preparationElapsedLabel: string
  errorMessage: string
  processingVersionId: string
  processingPercentage: number
}>()

const { t } = useLocale()

const officialImportProgress = computed(() => {
  const job = props.officialImportJob
  if (!job || job.stage !== 'DOWNLOADING' || !job.totalBytes) return null
  return Math.min(100, Math.round(job.downloadedBytes / job.totalBytes * 100))
})

const officialImportBytes = computed(() => {
  const job = props.officialImportJob
  if (!job || job.downloadedBytes <= 0) return ''
  const format = (bytes: number) => bytes < 1024 * 1024
    ? `${Math.max(1, Math.round(bytes / 1024))} KB`
    : `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return job.totalBytes ? `${format(job.downloadedBytes)} / ${format(job.totalBytes)}` : format(job.downloadedBytes)
})

const officialImportStage = computed(() => {
  const job = props.officialImportJob
  if (!job) return ''
  if (job.stage === 'COMPLETED' && job.teachingHandoffState === 'FAILED') {
    return job.teachingErrorCode === 'DOCUMENT_PROCESSING_FAILED'
      ? props.officialImportCopy.DOCUMENT_FAILED
      : props.officialImportCopy.TEACHING_FAILED
  }
  if (job.stage === 'COMPLETED' && job.teachingHandoffState !== 'NOT_REQUESTED') {
    return props.officialImportCopy[job.teachingHandoffState]
  }
  return props.officialImportCopy[job.stage]
})
</script>

<template>
  <section v-if="officialImportJob" class="mt-5 rounded-xl border border-copper/20 bg-paper p-5 text-left" role="status" aria-live="polite">
    <div class="flex items-start justify-between gap-4">
      <div class="min-w-0">
        <p class="tabletop-kicker">{{ officialImportCopy.title }}</p>
        <h2 class="mt-1 truncate font-display text-xl font-semibold">{{ officialImportJob.title }}</h2>
        <p class="mt-2 text-sm font-semibold text-copper">{{ officialImportStage }}</p>
        <p class="mt-1 text-xs leading-5 text-ink/50">{{ officialImportCopy.safe }}</p>
      </div>
      <span v-if="officialImportBytes" class="shrink-0 text-xs font-semibold text-indigo">{{ officialImportBytes }}</span>
    </div>
    <div v-if="officialImportProgress !== null" class="mt-4 h-2 overflow-hidden rounded-full bg-ink/10" :aria-label="`${officialImportProgress}%`">
      <div class="h-full rounded-full bg-copper transition-[width]" :style="{ width: `${officialImportProgress}%` }" />
    </div>
    <div v-else-if="officialImportJob.stage !== 'COMPLETED' && officialImportJob.stage !== 'FAILED'" class="mt-4 flex gap-1.5" aria-hidden="true">
      <span v-for="index in 6" :key="index" class="h-1.5 flex-1 rounded-full" :class="index <= ['QUEUED', 'CONNECTING', 'DOWNLOADING', 'COMPRESSING', 'VERIFYING_FILE', 'SAVING'].indexOf(officialImportJob.stage) + 1 ? 'bg-copper' : 'bg-ink/10'" />
    </div>
    <p class="mt-3 border-t border-ink/8 pt-3 text-xs text-ink/45">{{ officialImportCopy.background }}</p>
  </section>

  <p v-if="message && !preparingVersionId" class="mt-5 rounded-lg bg-indigo/5 px-4 py-3 text-sm text-indigo" aria-live="polite">{{ message }}</p>
  <div v-if="preparingVersionId" class="mt-5 rounded-xl border border-indigo/15 bg-indigo/5 p-4 text-left" role="status" aria-live="polite">
    <div class="flex items-start justify-between gap-4">
      <div>
        <p class="font-semibold text-ink">{{ t('documents.organizing') }}</p>
        <p class="mt-1 text-sm leading-6 text-ink/60">{{ message }}</p>
      </div>
      <span class="shrink-0 text-xs font-medium text-indigo">{{ preparationElapsedLabel }}</span>
    </div>
    <p class="mt-3 border-t border-indigo/10 pt-3 text-xs leading-5 text-ink/45">{{ t('documents.background') }}</p>
  </div>
  <p v-if="errorMessage" class="mt-5 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ errorMessage }}</p>
  <div v-if="processingVersionId" class="mx-auto mt-4 h-1.5 max-w-md overflow-hidden rounded-full bg-ink/10">
    <div class="h-full bg-copper transition-all" :style="{ width: `${processingPercentage}%` }" />
  </div>
</template>
