<script setup lang="ts">
import { computed } from 'vue'

import PlayerWorkStatusText from '@/components/PlayerWorkStatusText.vue'
import { useLocale } from '@/lib/locale'
import { playerWorkStatus } from '@/lib/playerWorkStatus'
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
  retryingOfficialImport?: boolean
}>()

const emit = defineEmits<{
  'choose-source': []
  'use-local-upload': []
  'retry-original': []
}>()

const { locale, t } = useLocale()

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

const officialPlayerStatus = computed(() => {
  const job = props.officialImportJob
  if (!job) return null
  const rulebookUsable = Boolean(job.documentVersionId && job.stage === 'COMPLETED')
  const facts = {
    capability: rulebookUsable ? 'rulebook' as const : 'none' as const,
    readiness: rulebookUsable ? 'usable' as const : 'unavailable' as const,
  }
  if (job.stage === 'FAILED' || job.teachingHandoffState === 'FAILED') {
    return playerWorkStatus('NEEDS_ACTION', {
      ...facts, terminality: 'terminal', outcome: 'needs-action',
    }, locale.value)
  }
  if (job.stage !== 'COMPLETED') {
    return playerWorkStatus('ACQUIRING_RULEBOOK', {
      ...facts, terminality: 'active', outcome: 'none',
    }, locale.value)
  }
  if (job.teachingHandoffState === 'WAITING_FOR_DOCUMENT') {
    return playerWorkStatus('READING_RULEBOOK', {
      ...facts, terminality: 'active', outcome: 'none',
    }, locale.value)
  }
  if (job.teachingHandoffState === 'LAUNCHING' || job.teachingHandoffState === 'LAUNCHED') {
    return playerWorkStatus('ORGANIZING_GUIDE', {
      ...facts, terminality: 'active', outcome: 'none',
    }, locale.value)
  }
  return playerWorkStatus('RULEBOOK_READY', {
    ...facts, terminality: 'terminal', outcome: 'none',
  }, locale.value)
})

const preparationPlayerStatus = computed(() => playerWorkStatus('ORGANIZING_GUIDE', {
  capability: 'rulebook', readiness: 'usable', terminality: 'active', outcome: 'none',
}, locale.value))

const failedRecovery = computed(() => {
  const job = props.officialImportJob
  return job?.stage === 'FAILED' ? job.recovery ?? null : null
})

const failureKind = computed(() => failedRecovery.value?.failureKind ?? 'OTHER')
</script>

<template>
  <section v-if="officialImportJob" class="mt-5 rounded-xl border bg-paper p-5 text-left" :class="officialImportJob.stage === 'FAILED' ? 'border-red-200' : 'border-copper/20'" role="status" aria-live="polite">
    <div class="flex items-start justify-between gap-4">
      <div class="min-w-0">
        <p class="tabletop-kicker">{{ officialImportJob.stage === 'FAILED' ? officialImportCopy.failureTitle : officialImportCopy.title }}</p>
        <h2 class="mt-1 truncate font-display text-xl font-semibold">{{ officialImportJob.title }}</h2>
        <PlayerWorkStatusText
          v-if="officialPlayerStatus"
          :status="officialPlayerStatus"
          class="mt-2 text-sm font-semibold text-copper"
        />
        <p class="mt-1 text-xs leading-5 text-muted">{{ officialImportStage }}</p>
        <p v-if="officialImportJob.stage !== 'FAILED'" class="mt-1 text-xs leading-5 text-muted">{{ officialImportCopy.safe }}</p>
      </div>
      <span v-if="officialImportBytes" class="shrink-0 text-xs font-semibold text-indigo">{{ officialImportBytes }}</span>
    </div>
    <div v-if="officialImportProgress !== null" class="mt-4 h-2 overflow-hidden rounded-full bg-ink/10" :aria-label="`${officialImportProgress}%`">
      <div class="h-full rounded-full bg-copper transition-[width]" :style="{ width: `${officialImportProgress}%` }" />
    </div>
    <div v-else-if="officialImportJob.stage !== 'COMPLETED' && officialImportJob.stage !== 'FAILED'" class="mt-4 flex gap-1.5" aria-hidden="true">
      <span v-for="index in 6" :key="index" class="h-1.5 flex-1 rounded-full" :class="index <= ['QUEUED', 'CONNECTING', 'DOWNLOADING', 'COMPRESSING', 'VERIFYING_FILE', 'SAVING'].indexOf(officialImportJob.stage) + 1 ? 'bg-copper' : 'bg-ink/10'" />
    </div>
    <div v-if="officialImportJob.stage === 'FAILED'" class="mt-4 rounded-lg bg-red-50 px-4 py-3 text-sm leading-6 text-red-800" role="alert">
      <p>{{ officialImportCopy.failureDetail[failureKind] }}</p>
      <p class="mt-1 text-xs text-red-700/75">{{ officialImportJob.sourceDomain }}</p>
      <div class="mt-3 flex flex-wrap gap-3">
        <button v-if="failedRecovery?.canChooseAnotherSource !== false" type="button" class="min-h-11 rounded-lg bg-indigo px-4 font-semibold text-white" @click="emit('choose-source')">{{ officialImportCopy.chooseAnotherSource }}</button>
        <button v-if="failedRecovery?.canUseLocalUpload !== false" type="button" class="min-h-11 rounded-lg border border-indigo/25 px-4 font-semibold text-indigo" @click="emit('use-local-upload')">{{ officialImportCopy.useLocalUpload }}</button>
        <button v-if="failedRecovery?.canRetryOriginalSource" type="button" :disabled="retryingOfficialImport" class="min-h-11 rounded-lg border border-red-300 px-4 font-semibold text-red-800 disabled:opacity-40" @click="emit('retry-original')">{{ officialImportCopy.retryOriginalSource }}</button>
        <a v-if="failedRecovery?.canOpenSourceInBrowser && officialImportJob.officialSourceUrl" :href="officialImportJob.officialSourceUrl" target="_blank" rel="noopener noreferrer" class="inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ officialImportCopy.openOriginalSource }} ↗</a>
      </div>
    </div>
    <p class="mt-3 border-t border-ink/8 pt-3 text-xs text-muted">{{ officialImportCopy.background }}</p>
  </section>

  <p v-if="message && !preparingVersionId" class="mt-5 rounded-lg bg-indigo/5 px-4 py-3 text-sm text-indigo" aria-live="polite">{{ message }}</p>
  <div v-if="preparingVersionId" class="mt-5 rounded-xl border border-indigo/15 bg-indigo/5 p-4 text-left" role="status" aria-live="polite">
    <div class="flex items-start justify-between gap-4">
      <div>
        <PlayerWorkStatusText
          :status="preparationPlayerStatus"
          class="font-semibold text-ink"
        />
        <p class="mt-1 text-sm leading-6 text-muted">{{ message }}</p>
      </div>
      <span class="shrink-0 text-xs font-medium text-indigo">{{ preparationElapsedLabel }}</span>
    </div>
    <p class="mt-3 border-t border-indigo/10 pt-3 text-xs leading-5 text-muted">{{ t('documents.background') }}</p>
  </div>
  <p v-if="errorMessage" class="mt-5 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ errorMessage }}</p>
  <div v-if="processingVersionId" class="mx-auto mt-4 h-1.5 max-w-md overflow-hidden rounded-full bg-ink/10">
    <div class="h-full bg-copper transition-all" :style="{ width: `${processingPercentage}%` }" />
  </div>
</template>
