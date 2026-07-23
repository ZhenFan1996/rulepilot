<script setup lang="ts">
export interface LessonGenerationActivity {
  sequence: number
  outcome: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'REJECTED'
  text: string
}

defineProps<{
  active: boolean
  statusUnknown: boolean
  statusText: string
  draftReady: boolean
  availableSectionCount: number
  totalSectionCount: number | null
  elapsed: string
  processedChapterCount: number
  supportedChapterCount: number
  modelCallCount: number
  progressWidth: string
  remainingTime: string
  activities: LessonGenerationActivity[]
  refreshFailed: boolean
  finishedMessage: string
}>()
</script>

<template>
  <section v-if="active" class="border-b border-indigo/15 bg-indigo/5 px-5 py-4">
    <div class="mx-auto max-w-4xl">
      <div class="flex items-start justify-between gap-4">
        <div>
          <p class="text-sm font-semibold text-indigo" role="status" aria-live="polite" aria-atomic="true">{{ statusUnknown ? '正在确认后台生成状态' : statusText }}</p>
          <p class="mt-1 text-xs leading-5 text-ink/55">{{ draftReady ? `完整基础讲解已经可用，共 ${availableSectionCount} 节；后台只是在核对和修正细节。` : `整本仍在后台生成 · 当前已有 ${availableSectionCount} 节可以阅读。停在这里不会丢失进度。` }}</p>
        </div>
        <span v-if="!statusUnknown" class="shrink-0 font-mono text-sm font-semibold text-indigo" aria-label="已用时">{{ elapsed }}</span>
      </div>
      <template v-if="!statusUnknown && totalSectionCount !== null">
        <div class="mt-3 h-1.5 overflow-hidden rounded-full bg-indigo/10" role="progressbar" :aria-valuemin="0" :aria-valuemax="totalSectionCount" :aria-valuenow="processedChapterCount" :aria-label="`已处理 ${processedChapterCount} 个章节，共 ${totalSectionCount} 个`">
          <div class="h-full rounded-full bg-indigo transition-[width] duration-500" :style="{ width: progressWidth }" />
        </div>
        <div class="mt-2 flex flex-wrap justify-between gap-2 text-xs text-ink/55">
          <span>后台已处理 {{ processedChapterCount }}/{{ totalSectionCount }} 节，其中 {{ supportedChapterCount }} 节通过核对</span>
          <span>{{ modelCallCount }} 次模型调用</span>
        </div>
        <p class="mt-2 text-xs leading-5 text-ink/50">{{ remainingTime }} {{ draftReady ? '你现在就可以从第一节开始。' : '新章节完成后会自动出现在目录里。' }}</p>
        <ol v-if="activities.length" class="mt-3 grid gap-1.5 border-t border-indigo/10 pt-3 sm:grid-cols-3" aria-label="最近生成进度">
          <li v-for="activity in activities" :key="activity.sequence" class="flex items-start gap-2 text-xs leading-5 text-ink/55">
            <span class="mt-1.5 size-1.5 shrink-0 rounded-full" :class="activity.outcome === 'RUNNING' ? 'animate-pulse bg-copper' : activity.outcome === 'SUCCEEDED' ? 'bg-emerald-600' : 'bg-amber-600'" />
            <span>{{ activity.text }}</span>
          </li>
        </ol>
      </template>
      <p v-if="refreshFailed" class="mt-2 text-xs font-semibold text-amber-800" role="status">暂时没有取得最新章节，正在自动重试。现有内容不受影响。</p>
    </div>
  </section>
  <p v-else-if="finishedMessage" class="border-b border-emerald-200 bg-emerald-50 px-5 py-3 text-center text-sm font-semibold text-emerald-800" role="status">{{ finishedMessage }}</p>
</template>
