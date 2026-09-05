<script setup lang="ts">
import { computed } from 'vue'

import PlayerWorkStatusText from '@/components/PlayerWorkStatusText.vue'
import { useLocale } from '@/lib/locale'
import { guideWorkStatus, type PlayerWorkStatus } from '@/lib/playerWorkStatus'

export interface LessonGenerationActivity {
  sequence: number
  outcome: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'REJECTED'
  text: string
}

const props = defineProps<{
  active: boolean
  statusUnknown: boolean
  statusText: string
  availableSectionCount: number
  totalSectionCount: number | null
  elapsed: string
  processedChapterCount: number
  supportedChapterCount: number
  progressWidth: string
  remainingTime: string
  activities: LessonGenerationActivity[]
  terminalIssues: LessonGenerationActivity[]
  refreshFailed: boolean
  finishedMessage: string
  finishedStatus: PlayerWorkStatus | null
}>()

const { locale, t } = useLocale()
const workStatus = computed(() => guideWorkStatus(
  props.availableSectionCount > 0 ? 'readable' : 'organizing',
  props.availableSectionCount,
  locale.value,
))
</script>

<template>
  <section v-if="active" class="border-b border-indigo/15 bg-indigo/5 px-5 py-4">
    <div class="mx-auto max-w-4xl">
      <div class="flex items-start justify-between gap-4">
        <div>
          <PlayerWorkStatusText
            :status="workStatus"
            class="text-sm font-semibold text-indigo"
            role="status"
            aria-live="polite"
            aria-atomic="true"
          />
          <p class="mt-1 text-xs leading-5 text-ink/60">{{ statusUnknown ? t('lesson.generation.statusUnknown') : statusText }}</p>
          <p class="mt-1 text-xs leading-5 text-ink/55">{{ t('lesson.generation.inProgress', { count: availableSectionCount }) }}</p>
        </div>
        <span v-if="!statusUnknown" class="shrink-0 font-mono text-sm font-semibold text-indigo" :aria-label="t('lesson.generation.elapsed')">{{ elapsed }}</span>
      </div>
      <template v-if="!statusUnknown && totalSectionCount !== null">
        <div class="mt-3 h-1.5 overflow-hidden rounded-full bg-indigo/10" role="progressbar" :aria-valuemin="0" :aria-valuemax="totalSectionCount" :aria-valuenow="processedChapterCount" :aria-label="t('lesson.generation.progressAria', { processed: processedChapterCount, total: totalSectionCount })">
          <div class="h-full rounded-full bg-indigo transition-[width] duration-500" :style="{ width: progressWidth }" />
        </div>
        <div class="mt-2 text-xs text-ink/55">
          <span>{{ t('lesson.generation.processed', { processed: processedChapterCount, total: totalSectionCount, supported: supportedChapterCount }) }}</span>
        </div>
        <p class="mt-2 text-xs leading-5 text-ink/50">{{ remainingTime }} {{ t('lesson.generation.progressHint') }}</p>
        <ol v-if="activities.length" class="mt-3 grid gap-1.5 border-t border-indigo/10 pt-3 sm:grid-cols-3" :aria-label="t('lesson.generation.activitiesAria')">
          <li v-for="activity in activities" :key="activity.sequence" class="flex items-start gap-2 text-xs leading-5 text-ink/55">
            <span class="mt-1.5 size-1.5 shrink-0 rounded-full" :class="activity.outcome === 'RUNNING' ? 'animate-pulse bg-copper' : activity.outcome === 'SUCCEEDED' ? 'bg-emerald-600' : 'bg-amber-600'" />
            <span>{{ activity.text }}</span>
          </li>
        </ol>
      </template>
      <p v-if="refreshFailed" class="mt-2 text-xs font-semibold text-amber-800" role="status">{{ t('lesson.generation.refreshFailed') }}</p>
    </div>
  </section>
  <div
    v-else-if="finishedMessage && finishedStatus"
    class="border-b px-5 py-3 text-center"
    :class="finishedStatus.readiness === 'complete' ? 'border-emerald-200 bg-emerald-50 text-emerald-800' : 'border-amber-200 bg-amber-50 text-amber-900'"
    role="status"
    aria-live="polite"
    aria-atomic="true"
  >
    <PlayerWorkStatusText
      :status="finishedStatus"
      class="text-sm font-semibold"
    />
    <p class="mt-0.5 text-xs leading-5">{{ finishedMessage }}</p>
    <ol
      v-if="terminalIssues.length"
      data-testid="lesson-generation-terminal-issues"
      class="mx-auto mt-2 grid max-w-4xl gap-1.5 border-t border-current/10 pt-2 text-left"
      :aria-label="t('lesson.generation.activitiesAria')"
    >
      <li v-for="issue in terminalIssues" :key="issue.sequence" class="flex items-start gap-2 text-xs leading-5">
        <span class="mt-1.5 size-1.5 shrink-0 rounded-full bg-amber-600" aria-hidden="true" />
        <span>{{ issue.text }}</span>
      </li>
    </ol>
  </div>
</template>
