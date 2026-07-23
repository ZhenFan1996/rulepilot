<script setup lang="ts">
import type {
  LessonQualityReport,
  MediaConsistencyReport,
} from '@/composables/lessonSupportingContent'

type MediaMode = 'TEXT' | 'AUDIO' | 'VIDEO'

interface LessonDirectorySection {
  position: number
  topicKey: string
  title: string
}

defineProps<{
  lessonStatus: 'COMPLETE' | 'DRAFT_READY' | 'INCOMPLETE'
  sections: LessonDirectorySection[]
  currentIndex: number
  completed: number[]
  skipped: number[]
  progressPercent: number
  supportedSectionCount: number
  lessonStillGrowing: boolean
  generationActive: boolean
  quality: LessonQualityReport | null
  visualEnrichmentSummary: string
  visualEnrichmentActive: boolean
  mediaConsistency: MediaConsistencyReport | null
  mediaMode: MediaMode
  online: boolean
  resuming: boolean
  mediaModeAvailable: (mode: MediaMode) => boolean
}>()

const emit = defineEmits<{
  selectSection: [index: number]
  selectMediaMode: [mode: MediaMode]
  resume: []
}>()
</script>

<template>
  <aside class="min-w-0 max-w-full overflow-hidden lg:sticky lg:top-28 lg:h-[calc(100vh-8rem)] lg:overflow-auto" aria-label="讲解章节">
    <div class="hidden items-end justify-between lg:flex">
      <div>
        <p class="text-xs font-medium text-copper">讲解目录</p>
        <h1 class="mt-2 font-display text-2xl font-semibold">{{ lessonStillGrowing ? '已完成章节' : lessonStatus === 'DRAFT_READY' ? '完整基础讲解' : '完整规则讲解' }}</h1>
      </div>
      <span class="text-sm font-semibold text-copper">{{ progressPercent }}%</span>
    </div>

    <details v-if="quality && !generationActive" class="mt-4 hidden rounded-2xl border border-ink/10 bg-paper/70 p-3 lg:block">
      <summary class="cursor-pointer list-none text-sm font-semibold">
        <span class="flex items-center justify-between gap-3">
          <span>讲解有问题？查看诊断</span>
          <span :class="quality.status === 'READY' ? 'text-emerald-700' : quality.status === 'BLOCKED' ? 'text-red-700' : 'text-amber-700'">{{ quality.status === 'READY' ? '可交付' : quality.status === 'BLOCKED' ? '有阻塞项' : '需要复核' }}</span>
        </span>
      </summary>
      <ul class="mt-3 space-y-2 border-t border-ink/10 pt-3">
        <li v-for="check in quality.checks" :key="check.type" class="text-xs leading-5">
          <p class="font-semibold"><span aria-hidden="true">{{ check.status === 'PASS' ? '✓' : check.status === 'FAIL' ? '×' : '?' }}</span> {{ check.summary }}</p>
          <p class="mt-0.5 text-ink/50">{{ check.detail }}</p>
        </li>
      </ul>
    </details>

    <div v-if="lessonStatus === 'DRAFT_READY' && !generationActive" class="mt-3 hidden rounded-2xl border border-indigo/20 bg-indigo/5 p-3 text-sm text-indigo lg:block">
      <p class="font-semibold">完整基础讲解可以使用</p>
      <p class="mt-1 text-xs leading-5 text-ink/60">全部章节已有原文引用，{{ supportedSectionCount }} / {{ sections.length }} 节完成细节核对。你可以先开桌，也可以继续后台核对。</p>
      <button
        class="mt-3 min-h-10 rounded-xl bg-indigo px-3 py-2 text-xs font-semibold text-white disabled:cursor-not-allowed disabled:opacity-40"
        :disabled="resuming || !online"
        @click="emit('resume')"
      >
        {{ resuming ? '正在继续…' : '继续核对细节' }}
      </button>
    </div>

    <div v-if="visualEnrichmentSummary" class="mt-3 block rounded-2xl border border-indigo/15 bg-paper/70 p-3 text-sm" :class="visualEnrichmentActive ? 'text-indigo' : 'text-ink/65'">
      <p class="font-semibold">{{ visualEnrichmentActive ? '正在补入局部图示' : '局部图示处理完成' }}</p>
      <p class="mt-1 text-xs leading-5 text-ink/60">{{ visualEnrichmentSummary }}</p>
    </div>

    <div v-if="lessonStatus === 'INCOMPLETE' && !generationActive" class="mt-3 hidden rounded-2xl border border-amber-300/70 bg-amber-50 p-3 text-sm text-amber-950 lg:block">
      <p class="font-semibold">已验证 {{ supportedSectionCount }} / {{ sections.length }} 节</p>
      <p class="mt-1 text-xs leading-5 text-amber-900/75">继续时会保留已经通过引用与事实检查的章节，只补尚未通过的部分。</p>
      <button
        class="mt-3 min-h-10 rounded-xl bg-amber-900 px-3 py-2 text-xs font-semibold text-white disabled:cursor-not-allowed disabled:opacity-40"
        :disabled="resuming || !online"
        @click="emit('resume')"
      >
        {{ resuming ? '正在补全…' : '继续补全讲解' }}
      </button>
    </div>

    <details v-if="mediaConsistency && !generationActive" class="mt-3 hidden rounded-2xl border border-ink/10 bg-paper/70 p-3 lg:block">
      <summary class="cursor-pointer list-none text-sm font-semibold">
        <span class="flex items-center justify-between gap-3">
          <span>图文与音视频状态</span>
          <span :class="mediaConsistency.status === 'CONSISTENT' ? 'text-emerald-700' : 'text-red-700'">{{ mediaConsistency.status === 'CONSISTENT' ? '一致' : '需检查' }}</span>
        </span>
      </summary>
      <ul class="mt-3 space-y-2 border-t border-ink/10 pt-3">
        <li v-for="check in mediaConsistency.checks" :key="check.type" class="text-xs leading-5">
          <p class="font-semibold">{{ check.status === 'PASS' ? '✓' : '×' }} {{ check.summary }}</p>
          <p class="text-ink/50">{{ check.detail }}</p>
        </li>
      </ul>
    </details>

    <div v-if="!generationActive" class="grid grid-cols-3 rounded-2xl border border-ink/10 bg-paper/70 p-1 lg:mt-4" aria-label="讲解形式">
      <button
        v-for="mode in ([['TEXT', '图文'], ['AUDIO', '语音'], ['VIDEO', '视频']] as const)"
        :key="mode[0]"
        class="min-h-10 rounded-xl px-2 text-xs font-semibold transition"
        :disabled="!mediaModeAvailable(mode[0])"
        :class="mediaMode === mode[0] ? 'bg-ink-panel text-panel-text' : 'text-ink/55 hover:text-ink disabled:cursor-not-allowed disabled:opacity-35'"
        :aria-pressed="mediaMode === mode[0]"
        @click="emit('selectMediaMode', mode[0])"
      >
        {{ mode[1] }}
      </button>
    </div>

    <ol class="mt-3 flex max-w-full gap-2 overflow-x-auto pb-2 lg:mt-5 lg:block lg:space-y-2 lg:overflow-visible">
      <li v-for="(section, index) in sections" :key="section.topicKey" class="shrink-0 lg:shrink">
        <button
          class="flex min-h-12 w-52 items-center gap-3 rounded-2xl px-3 py-2 text-left text-sm transition lg:w-full"
          :class="index === currentIndex ? 'bg-ink-panel text-panel-text' : 'bg-paper/60 text-ink hover:bg-paper'"
          :aria-current="index === currentIndex ? 'step' : undefined"
          @click="emit('selectSection', index)"
        >
          <span class="grid size-7 shrink-0 place-items-center rounded-full border text-xs font-bold">{{ completed.includes(index) ? '✓' : skipped.includes(index) ? '–' : section.position }}</span>
          <span class="truncate">{{ section.title }}</span>
        </button>
      </li>
    </ol>
  </aside>
</template>
