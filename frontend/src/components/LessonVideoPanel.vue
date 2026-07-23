<script setup lang="ts">
import type { VideoChapter } from '@/composables/lessonSupportingContent'

type VideoFrame = VideoChapter['frames'][number]

defineProps<{
  chapter: VideoChapter | null
  activeFrame: VideoFrame | null
  chapters: VideoChapter[]
  activeChapterIndex: number
  durationMillis: number
  playbackMillis: number
  playing: boolean
  playbackRate: number
  audioAvailable: boolean
  formatDuration: (millis: number) => string
  visualKindLabel: (kind: VideoChapter['visualKind']) => string
}>()

const emit = defineEmits<{
  seek: [millis: number]
  togglePlayback: []
  replay: []
  cycleRate: []
  selectChapter: [index: number]
}>()

function seek(event: Event) {
  emit('seek', Number((event.target as HTMLInputElement).value))
}
</script>

<template>
  <section v-if="chapter && activeFrame" class="mt-7" aria-label="分章节视频">
    <div class="relative aspect-[4/3] overflow-hidden rounded-3xl bg-ink-panel text-panel-text shadow-xl sm:aspect-video">
      <div class="relative flex h-full flex-col justify-between p-5 sm:p-8">
        <div class="flex items-start justify-between gap-4">
          <div>
            <p class="text-xs font-semibold text-panel-text/55">{{ visualKindLabel(chapter.visualKind) }}</p>
            <h3 class="mt-2 font-display text-2xl font-semibold sm:text-4xl">{{ chapter.title }}</h3>
          </div>
          <span class="rounded-full border border-panel-text/20 px-3 py-1 text-xs">第 {{ chapter.position }} 章</span>
        </div>
        <div class="mx-auto flex w-full max-w-md items-center justify-center gap-3" aria-hidden="true">
          <span v-for="frame in Math.min(chapter.frames.length, 5)" :key="frame" class="grid size-12 place-items-center rounded-2xl border border-panel-text/25 bg-panel-text/8 font-display text-lg font-semibold">{{ frame }}</span>
        </div>
        <div>
          <p class="rounded-2xl bg-black/35 px-4 py-3 text-center text-sm leading-6 sm:text-base">{{ activeFrame.subtitle }}</p>
          <p v-if="activeFrame.sourcePages.length" class="mt-2 text-center text-xs text-panel-text/60">规则书第 {{ activeFrame.sourcePages.join('、') }} 页</p>
        </div>
      </div>
    </div>
    <p class="mt-3 text-sm text-ink/55">{{ chapter.visualCaption }}</p>
    <input class="mt-4 w-full accent-copper" type="range" min="0" :max="durationMillis" :value="playbackMillis" aria-label="视频播放位置" @input="seek">
    <div class="mt-1 flex justify-between text-xs tabular-nums text-ink/45"><span>{{ formatDuration(playbackMillis) }}</span><span>{{ formatDuration(durationMillis) }}</span></div>
    <div class="mt-3 grid grid-cols-3 gap-2">
      <button :disabled="!audioAvailable" class="min-h-11 rounded-xl bg-copper px-3 text-sm font-semibold text-white disabled:opacity-35" @click="emit('togglePlayback')">{{ audioAvailable ? (playing ? '暂停视频' : '播放视频') : '音轨不可用' }}</button>
      <button :disabled="!audioAvailable" class="min-h-11 rounded-xl border border-ink/15 px-3 text-sm font-semibold disabled:opacity-35" @click="emit('replay')">重播画面</button>
      <button :disabled="!audioAvailable" class="min-h-11 rounded-xl border border-ink/15 px-3 text-sm font-semibold disabled:opacity-35" @click="emit('cycleRate')">{{ playbackRate }}×</button>
    </div>
    <nav class="mt-4 flex gap-2 overflow-x-auto pb-2" aria-label="视频章节跳转">
      <button
        v-for="(videoChapter, index) in chapters"
        :key="videoChapter.position"
        class="shrink-0 rounded-full border px-3 py-2 text-xs font-semibold"
        :class="index === activeChapterIndex ? 'border-copper bg-copper/10 text-copper' : 'border-ink/10 text-ink/50'"
        @click="emit('selectChapter', index)"
      >
        {{ videoChapter.position }}. {{ videoChapter.title }}
      </button>
    </nav>
  </section>
</template>
