<script setup lang="ts">
import type { NarrationScript, SpeechCue } from '@/composables/lessonSupportingContent'

type NarrationChapter = NarrationScript['chapters'][number]

defineProps<{
  visible: boolean
  chapter: NarrationChapter | null
  activeCue: SpeechCue | null
  durationMillis: number
  playbackMillis: number
  playing: boolean
  playbackRate: number
  formatDuration: (millis: number) => string
}>()

const emit = defineEmits<{
  seekSegment: [position: number]
  seek: [millis: number]
  togglePlayback: []
  replay: []
  cycleRate: []
}>()

function seek(event: Event) {
  emit('seek', Number((event.target as HTMLInputElement).value))
}
</script>

<template>
  <details v-if="chapter" v-show="visible" open class="mt-7 rounded-2xl border border-indigo/15 bg-indigo/5 p-4 sm:p-5">
    <summary class="cursor-pointer list-none font-semibold text-indigo">
      <span class="flex items-center justify-between gap-3">
        <span>本节解说稿</span>
        <span class="text-xs">{{ chapter.supported ? '可回到原文核对' : '原文不足，跳过这一段' }}</span>
      </span>
    </summary>
    <ol class="mt-4 space-y-3 border-t border-indigo/10 pt-4" aria-label="同步字幕">
      <li
        v-for="segment in chapter.segments"
        :key="segment.position"
        class="rounded-xl border px-3 py-2 text-sm leading-7 transition"
        :class="activeCue?.chapterPosition === chapter.position && activeCue?.segmentPosition === segment.position ? 'border-copper/40 bg-copper/10 text-ink' : 'border-transparent text-ink/70'"
      >
        <p>{{ segment.text }}</p>
        <p v-if="segment.sourcePages.length" class="mt-1 text-xs font-semibold text-indigo">规则书第 {{ segment.sourcePages.join('、') }} 页</p>
        <button class="mt-1 text-xs font-semibold text-copper" @click="emit('seekSegment', segment.position)">从本段播放</button>
      </li>
    </ol>
    <div class="mt-5 border-t border-indigo/10 pt-4">
      <p class="text-xs leading-5 text-ink/50">音频与字幕共用同一份已验证解说稿；如有听不清的内容，请以文字和规则书原页为准。</p>
      <input class="mt-4 w-full accent-copper" type="range" min="0" :max="durationMillis" :value="playbackMillis" aria-label="解说播放位置" @input="seek">
      <div class="mt-1 flex justify-between text-xs tabular-nums text-ink/45">
        <span>{{ formatDuration(playbackMillis) }}</span>
        <span>{{ formatDuration(durationMillis) }}</span>
      </div>
      <div class="mt-3 grid grid-cols-3 gap-2">
        <button class="min-h-10 rounded-xl bg-indigo px-3 text-sm font-semibold text-white" @click="emit('togglePlayback')">{{ playing ? '暂停' : '播放' }}</button>
        <button class="min-h-10 rounded-xl border border-indigo/20 px-3 text-sm font-semibold text-indigo" @click="emit('replay')">重播本段</button>
        <button class="min-h-10 rounded-xl border border-indigo/20 px-3 text-sm font-semibold text-indigo" @click="emit('cycleRate')">{{ playbackRate }}×</button>
      </div>
    </div>
  </details>
</template>
