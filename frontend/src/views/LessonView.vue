<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import {
  finishSection,
  initialLessonProgress,
  restoreLessonProgress,
  type LessonProgress,
} from '@/lib/lessonProgress'

interface TeachingPlan {
  id: string
  documentVersionId: string
  playerCount: number
  beginnerCount: number
  durationMinutes: number
}

interface IllustratedLesson {
  id: string
  status: 'COMPLETE' | 'INCOMPLETE'
  sections: LessonSection[]
}

interface LessonSection {
  position: number
  type: string
  title: string
  required: boolean
  evidenceStatus: 'SUPPORTED' | 'INSUFFICIENT_EVIDENCE'
  visualKind: 'REFERENCE_CARD' | 'TABLE_LAYOUT' | 'FLOW_DIAGRAM' | 'SCOREBOARD'
  visualCaption: string
  steps: Array<{ position: number; text: string; sourcePages: number[] }>
}

interface LessonQualityReport {
  status: 'READY' | 'NEEDS_REVIEW' | 'BLOCKED'
  score: number
  checks: Array<{
    type: string
    status: 'PASS' | 'FAIL' | 'NOT_EVALUATED'
    summary: string
    detail: string
  }>
}

interface NarrationScript {
  id: string
  status: 'READY' | 'INCOMPLETE'
  chapters: Array<{
    position: number
    type: string
    title: string
    supported: boolean
    segments: Array<{ position: number; text: string; sourcePages: number[] }>
  }>
}

interface NarrationPlayback {
  script: NarrationScript
  provider: string
  durationMillis: number
  cues: SpeechCue[]
}

interface SpeechCue {
  chapterPosition: number
  segmentPosition: number
  startMillis: number
  endMillis: number
}

interface ChapterVideo {
  id: string
  status: 'READY' | 'INCOMPLETE'
  durationMillis: number
  chapters: VideoChapter[]
}

interface VideoChapter {
  position: number
  type: string
  title: string
  evidenceStatus: 'SUPPORTED' | 'INSUFFICIENT_EVIDENCE'
  visualKind: 'REFERENCE_CARD' | 'TABLE_LAYOUT' | 'FLOW_DIAGRAM' | 'SCOREBOARD'
  visualCaption: string
  startMillis: number
  endMillis: number
  frames: Array<{
    segmentPosition: number
    startMillis: number
    endMillis: number
    subtitle: string
    sourcePages: number[]
  }>
}

type MediaMode = 'TEXT' | 'AUDIO' | 'VIDEO'

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const errorMessage = ref('')
const online = ref(navigator.onLine)
const plan = ref<TeachingPlan | null>(null)
const lesson = ref<IllustratedLesson | null>(null)
const quality = ref<LessonQualityReport | null>(null)
const narration = ref<NarrationScript | null>(null)
const video = ref<ChapterVideo | null>(null)
const mediaMode = ref<MediaMode>('TEXT')
const narrationPlayer = ref<HTMLAudioElement | null>(null)
const narrationProvider = ref('')
const narrationDurationMillis = ref(0)
const narrationCues = ref<SpeechCue[]>([])
const narrationMillis = ref(0)
const narrationPlaying = ref(false)
const narrationRate = ref(1)
const narrationRestoreTarget = ref<number | null>(null)
const progress = ref<LessonProgress>(initialLessonProgress())

const planId = computed(() => String(route.params.planId ?? ''))
const currentSection = computed(() => lesson.value?.sections[progress.value.currentIndex] ?? null)
const currentNarration = computed(() => narration.value?.chapters[progress.value.currentIndex] ?? null)
const currentVideoChapter = computed(() => video.value?.chapters[progress.value.currentIndex] ?? null)
const narrationAudioUrl = computed(() => `/api/v1/teaching-plans/${planId.value}/narration/audio`)
const activeCue = computed(() =>
  narrationCues.value.find(
    (cue) => narrationMillis.value >= cue.startMillis && narrationMillis.value < cue.endMillis,
  ),
)
const activeVideoFrame = computed(() => {
  const chapter = currentVideoChapter.value
  if (!chapter) return null
  return (
    chapter.frames.find(
      (frame) => narrationMillis.value >= frame.startMillis && narrationMillis.value < frame.endMillis,
    ) ?? chapter.frames[0] ?? null
  )
})
const completedCount = computed(() => new Set([...progress.value.completed, ...progress.value.skipped]).size)
const progressPercent = computed(() =>
  lesson.value?.sections.length ? Math.round((completedCount.value / lesson.value.sections.length) * 100) : 0,
)

function progressKey() {
  return lesson.value ? `rulepilot:lesson-progress:${lesson.value.id}` : ''
}

function saveProgress() {
  const key = progressKey()
  if (key) localStorage.setItem(key, JSON.stringify(progress.value))
}

async function loadLesson() {
  loading.value = true
  errorMessage.value = ''
  let targetPlanId = planId.value
  if (!targetPlanId) {
    const remembered = localStorage.getItem('rulepilot:last-plan-id')
    if (remembered) {
      await router.replace({ name: 'lesson', params: { planId: remembered } })
      targetPlanId = remembered
    } else {
      loading.value = false
      return
    }
  }
  try {
    const [planResponse, lessonResponse, qualityResponse, narrationResponse, videoResponse] = await Promise.all([
      fetch(`/api/v1/teaching-plans/${targetPlanId}`, { credentials: 'include' }),
      fetch(`/api/v1/teaching-plans/${targetPlanId}/illustrated-lessons/latest`, { credentials: 'include' }),
      fetch(`/api/v1/teaching-plans/${targetPlanId}/illustrated-lessons/latest/quality`, { credentials: 'include' }),
      fetch(`/api/v1/teaching-plans/${targetPlanId}/narration/playback`, { credentials: 'include' }),
      fetch(`/api/v1/teaching-plans/${targetPlanId}/video`, { credentials: 'include' }),
    ])
    if (
      planResponse.status === 401 ||
      lessonResponse.status === 401 ||
      qualityResponse.status === 401 ||
      narrationResponse.status === 401 ||
      videoResponse.status === 401
    ) {
      await router.push({ name: 'login' })
      return
    }
    if (
      !planResponse.ok ||
      !lessonResponse.ok ||
      !qualityResponse.ok ||
      !narrationResponse.ok ||
      !videoResponse.ok
    ) {
      throw new Error('无法读取这份讲解，请重新生成。')
    }
    plan.value = (await planResponse.json()) as TeachingPlan
    lesson.value = (await lessonResponse.json()) as IllustratedLesson
    quality.value = (await qualityResponse.json()) as LessonQualityReport
    const playback = (await narrationResponse.json()) as NarrationPlayback
    narration.value = playback.script
    narrationProvider.value = playback.provider
    narrationDurationMillis.value = playback.durationMillis
    narrationCues.value = playback.cues
    video.value = (await videoResponse.json()) as ChapterVideo
    localStorage.setItem('rulepilot:last-plan-id', targetPlanId)
    progress.value = restoreLessonProgress(
      localStorage.getItem(`rulepilot:lesson-progress:${lesson.value.id}`),
      lesson.value.sections.length,
    )
    const restoredNarration = Number(localStorage.getItem(narrationPositionKey()))
    if (
      Number.isFinite(restoredNarration) &&
      restoredNarration >= 0 &&
      restoredNarration < narrationDurationMillis.value
    ) {
      narrationRestoreTarget.value = restoredNarration
      narrationMillis.value = restoredNarration
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '讲解加载失败。'
  } finally {
    loading.value = false
  }
}

function selectSection(index: number) {
  progress.value = { ...progress.value, currentIndex: index }
  saveProgress()
  seekToChapter(index)
}

function previousSection() {
  if (progress.value.currentIndex === 0) return
  selectSection(progress.value.currentIndex - 1)
}

function finish(outcome: 'completed' | 'skipped') {
  if (!lesson.value || progress.value.paused) return
  progress.value = finishSection(progress.value, lesson.value.sections.length, outcome)
  saveProgress()
}

function togglePause() {
  progress.value = { ...progress.value, paused: !progress.value.paused }
  if (progress.value.paused) narrationPlayer.value?.pause()
  saveProgress()
}

function narrationPositionKey() {
  return lesson.value ? `rulepilot:narration-position:${lesson.value.id}` : ''
}

function saveNarrationPosition() {
  const key = narrationPositionKey()
  if (key) localStorage.setItem(key, String(Math.round(narrationMillis.value)))
}

function onNarrationLoaded() {
  const player = narrationPlayer.value
  if (!player) return
  player.playbackRate = narrationRate.value
  const restored = Number(localStorage.getItem(narrationPositionKey()))
  if (Number.isFinite(restored) && restored >= 0 && restored < narrationDurationMillis.value) {
    narrationRestoreTarget.value = restored
    narrationMillis.value = restored
    player.currentTime = restored / 1_000
  }
}

function onNarrationTimeUpdate() {
  const player = narrationPlayer.value
  if (!player || narrationRestoreTarget.value !== null) return
  narrationMillis.value = Math.round(player.currentTime * 1_000)
  const cue = activeCue.value
  if (mediaMode.value === 'VIDEO' && cue && progress.value.currentIndex !== cue.chapterPosition - 1) {
    progress.value = { ...progress.value, currentIndex: cue.chapterPosition - 1 }
    saveProgress()
  }
  if (narrationPlaying.value) saveNarrationPosition()
}

function onNarrationSeeked() {
  const player = narrationPlayer.value
  const target = narrationRestoreTarget.value
  if (!player || target === null) return
  const current = Math.round(player.currentTime * 1_000)
  if (Math.abs(current - target) > 200) return
  narrationRestoreTarget.value = null
  narrationMillis.value = current
}

function onNarrationPaused() {
  if (narrationPlaying.value) {
    onNarrationTimeUpdate()
    saveNarrationPosition()
  }
  narrationPlaying.value = false
}

async function toggleNarration() {
  const player = narrationPlayer.value
  if (!player) return
  if (player.paused) await player.play()
  else player.pause()
}

function seekToChapter(index: number) {
  const cue = narrationCues.value.find((candidate) => candidate.chapterPosition === index + 1)
  seekToCue(cue)
}

function seekToSegment(segmentPosition: number) {
  const cue = narrationCues.value.find(
    (candidate) =>
      candidate.chapterPosition === progress.value.currentIndex + 1 &&
      candidate.segmentPosition === segmentPosition,
  )
  seekToCue(cue, true)
}

function seekToCue(cue: SpeechCue | undefined, play = false) {
  const player = narrationPlayer.value
  if (!player || !cue) return
  narrationRestoreTarget.value = null
  player.currentTime = cue.startMillis / 1_000
  narrationMillis.value = cue.startMillis
  saveNarrationPosition()
  if (play) void player.play()
}

function replayCurrentSegment() {
  const fallback = narrationCues.value.find(
    (cue) => cue.chapterPosition === progress.value.currentIndex + 1,
  )
  seekToCue(activeCue.value ?? fallback, true)
}

function cycleNarrationRate() {
  const rates = [0.75, 1, 1.25, 1.5, 2]
  const currentIndex = rates.indexOf(narrationRate.value)
  narrationRate.value = rates[(currentIndex + 1) % rates.length] ?? 1
  if (narrationPlayer.value) narrationPlayer.value.playbackRate = narrationRate.value
}

function seekNarration(event: Event) {
  const player = narrationPlayer.value
  if (!player) return
  const millis = Number((event.target as HTMLInputElement).value)
  narrationRestoreTarget.value = null
  player.currentTime = millis / 1_000
  narrationMillis.value = millis
  saveNarrationPosition()
}

function formatDuration(millis: number) {
  const seconds = Math.floor(millis / 1_000)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

function selectMediaMode(mode: MediaMode) {
  mediaMode.value = mode
  if (mode === 'TEXT') narrationPlayer.value?.pause()
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'ArrowLeft') previousSection()
  if (event.key === 'ArrowRight') finish('completed')
}

function updateOnlineStatus() {
  online.value = navigator.onLine
}

onMounted(() => {
  void loadLesson()
  window.addEventListener('online', updateOnlineStatus)
  window.addEventListener('offline', updateOnlineStatus)
  window.addEventListener('keydown', handleKeydown)
})

onUnmounted(() => {
  window.removeEventListener('online', updateOnlineStatus)
  window.removeEventListener('offline', updateOnlineStatus)
  window.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <main class="min-h-screen overflow-x-hidden bg-canvas pb-28 text-ink lg:pb-8">
    <header class="sticky top-0 z-20 border-b border-ink/10 bg-canvas/90 backdrop-blur">
      <div class="mx-auto flex max-w-7xl items-center justify-between gap-4 px-5 py-4 sm:px-8">
        <RouterLink :to="{ name: 'home' }" class="font-display text-xl font-semibold">RulePilot</RouterLink>
        <div v-if="plan" class="text-right text-xs text-ink/50">
          <p class="font-semibold text-ink/75">规则版本 {{ plan.documentVersionId.slice(0, 8) }}</p>
          <p>{{ plan.playerCount }} 人 · {{ plan.beginnerCount }} 位新手 · {{ plan.durationMinutes }} 分钟</p>
        </div>
      </div>
      <div v-if="lesson" class="h-1 bg-ink/8"><div class="h-full bg-copper transition-all" :style="{ width: `${progressPercent}%` }" /></div>
    </header>

    <p v-if="!online" class="bg-amber-100 px-5 py-3 text-center text-sm font-semibold text-amber-900" role="status">当前离线；已加载的讲解和本地进度仍可使用。</p>

    <div v-if="loading" class="mx-auto max-w-7xl px-5 py-16 sm:px-8" aria-live="polite">
      <div class="h-7 w-44 animate-pulse rounded bg-ink/10" />
      <div class="mt-6 h-80 animate-pulse rounded-3xl bg-paper" />
    </div>

    <section v-else-if="errorMessage" class="mx-auto max-w-xl px-5 py-20 text-center">
      <p class="font-display text-2xl font-semibold">讲解暂时无法打开</p>
      <p class="mt-3 text-ink/60" role="alert">{{ errorMessage }}</p>
      <button class="mt-6 rounded-xl bg-copper px-5 py-3 font-semibold text-white" @click="loadLesson">重新加载</button>
    </section>

    <section v-else-if="!lesson" class="mx-auto max-w-xl px-5 py-20 text-center">
      <p class="eyebrow">NO LESSON YET</p>
      <h1 class="mt-4 font-display text-4xl font-semibold">还没有可以继续的讲解</h1>
      <p class="mt-4 leading-7 text-ink/60">先导入规则书，创建教学计划并生成图文讲解。</p>
      <RouterLink :to="{ name: 'teach' }" class="mt-7 inline-flex rounded-xl bg-copper px-5 py-3 font-semibold text-white">开始导入</RouterLink>
    </section>

    <div v-else class="mx-auto grid min-w-0 max-w-7xl gap-6 px-5 py-7 sm:px-8 lg:grid-cols-[18rem_1fr] lg:py-10">
      <aside class="min-w-0 max-w-full overflow-hidden lg:sticky lg:top-28 lg:h-[calc(100vh-8rem)] lg:overflow-auto" aria-label="讲解章节">
        <div class="flex items-end justify-between">
          <div><p class="eyebrow">GUIDED LESSON</p><h1 class="mt-2 font-display text-2xl font-semibold">完整规则讲解</h1></div>
          <span class="text-sm font-semibold text-copper">{{ progressPercent }}%</span>
        </div>
        <details v-if="quality" class="mt-4 rounded-2xl border border-ink/10 bg-paper/70 p-3">
          <summary class="cursor-pointer list-none text-sm font-semibold">
            <span class="flex items-center justify-between gap-3">
              <span>讲解质量 {{ quality.score }} 分</span>
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
        <div class="mt-4 grid grid-cols-3 rounded-2xl border border-ink/10 bg-paper/70 p-1" aria-label="讲解形式">
          <button
            v-for="mode in ([['TEXT', '图文'], ['AUDIO', '语音'], ['VIDEO', '视频']] as const)"
            :key="mode[0]"
            class="min-h-10 rounded-xl px-2 text-xs font-semibold transition"
            :class="mediaMode === mode[0] ? 'bg-ink-panel text-panel-text' : 'text-ink/55 hover:text-ink'"
            :aria-pressed="mediaMode === mode[0]"
            @click="selectMediaMode(mode[0])"
          >
            {{ mode[1] }}
          </button>
        </div>
        <ol class="mt-5 flex max-w-full gap-2 overflow-x-auto pb-2 lg:block lg:space-y-2 lg:overflow-visible">
          <li v-for="(section, index) in lesson.sections" :key="section.type" class="shrink-0 lg:shrink">
            <button
              class="flex min-h-12 w-52 items-center gap-3 rounded-2xl px-3 py-2 text-left text-sm transition lg:w-full"
              :class="index === progress.currentIndex ? 'bg-ink-panel text-panel-text' : 'bg-paper/60 text-ink hover:bg-paper'"
              :aria-current="index === progress.currentIndex ? 'step' : undefined"
              @click="selectSection(index)"
            >
              <span class="grid size-7 shrink-0 place-items-center rounded-full border text-xs font-bold">{{ progress.completed.includes(index) ? '✓' : progress.skipped.includes(index) ? '–' : section.position }}</span>
              <span class="truncate">{{ section.title }}</span>
            </button>
          </li>
        </ol>
      </aside>

      <section v-if="currentSection" class="min-w-0" aria-live="polite">
        <div class="rounded-[2rem] border border-ink/10 bg-paper p-5 shadow-sm sm:p-8">
          <div class="flex flex-wrap items-start justify-between gap-4">
            <div><p class="text-xs font-semibold text-ink/45">第 {{ currentSection.position }} / {{ lesson.sections.length }} 节</p><h2 class="mt-2 font-display text-3xl font-semibold sm:text-4xl">{{ currentSection.title }}</h2></div>
            <span v-if="currentSection.evidenceStatus === 'INSUFFICIENT_EVIDENCE'" class="rounded-full bg-amber-100 px-3 py-1.5 text-xs font-semibold text-amber-900">证据不足</span>
            <span v-else class="rounded-full bg-emerald-100 px-3 py-1.5 text-xs font-semibold text-emerald-900">有规则书依据</span>
          </div>

          <audio
            ref="narrationPlayer"
            class="hidden"
            preload="metadata"
            :src="narrationAudioUrl"
            @loadedmetadata="onNarrationLoaded"
            @seeked="onNarrationSeeked"
            @timeupdate="onNarrationTimeUpdate"
            @play="narrationPlaying = true"
            @pause="onNarrationPaused"
            @ended="narrationPlaying = false"
          >浏览器不支持音频播放。</audio>

          <section v-if="mediaMode === 'VIDEO' && currentVideoChapter && activeVideoFrame" class="mt-7" aria-label="分章节视频">
            <div class="relative aspect-[4/3] overflow-hidden rounded-3xl bg-ink-panel text-panel-text shadow-xl sm:aspect-video">
              <div class="absolute inset-0 bg-[radial-gradient(circle_at_20%_20%,rgba(199,111,57,0.35),transparent_35%),radial-gradient(circle_at_80%_75%,rgba(84,91,157,0.4),transparent_38%)]" />
              <div class="relative flex h-full flex-col justify-between p-5 sm:p-8">
                <div class="flex items-start justify-between gap-4">
                  <div>
                    <p class="text-xs font-semibold uppercase tracking-[0.2em] text-panel-text/55">{{ currentVideoChapter.visualKind.replaceAll('_', ' ') }}</p>
                    <h3 class="mt-2 font-display text-2xl font-semibold sm:text-4xl">{{ currentVideoChapter.title }}</h3>
                  </div>
                  <span class="rounded-full border border-panel-text/20 px-3 py-1 text-xs">第 {{ currentVideoChapter.position }} 章</span>
                </div>
                <div class="mx-auto flex w-full max-w-md items-center justify-center gap-3" aria-hidden="true">
                  <span v-for="frame in Math.min(currentVideoChapter.frames.length, 5)" :key="frame" class="grid size-12 place-items-center rounded-2xl border border-panel-text/25 bg-panel-text/8 font-display text-lg font-semibold">{{ frame }}</span>
                </div>
                <div>
                  <p class="rounded-2xl bg-black/35 px-4 py-3 text-center text-sm leading-6 sm:text-base">{{ activeVideoFrame.subtitle }}</p>
                  <p v-if="activeVideoFrame.sourcePages.length" class="mt-2 text-center text-xs text-panel-text/60">规则书第 {{ activeVideoFrame.sourcePages.join('、') }} 页</p>
                </div>
              </div>
            </div>
            <p class="mt-3 text-sm text-ink/55">{{ currentVideoChapter.visualCaption }}</p>
            <input class="mt-4 w-full accent-copper" type="range" min="0" :max="video?.durationMillis ?? 0" :value="narrationMillis" aria-label="视频播放位置" @input="seekNarration">
            <div class="mt-1 flex justify-between text-xs tabular-nums text-ink/45"><span>{{ formatDuration(narrationMillis) }}</span><span>{{ formatDuration(video?.durationMillis ?? 0) }}</span></div>
            <div class="mt-3 grid grid-cols-3 gap-2">
              <button class="min-h-11 rounded-xl bg-copper px-3 text-sm font-semibold text-white" @click="toggleNarration">{{ narrationPlaying ? '暂停视频' : '播放视频' }}</button>
              <button class="min-h-11 rounded-xl border border-ink/15 px-3 text-sm font-semibold" @click="replayCurrentSegment">重播画面</button>
              <button class="min-h-11 rounded-xl border border-ink/15 px-3 text-sm font-semibold" @click="cycleNarrationRate">{{ narrationRate }}×</button>
            </div>
            <nav class="mt-4 flex gap-2 overflow-x-auto pb-2" aria-label="视频章节跳转">
              <button
                v-for="(chapter, index) in video?.chapters"
                :key="chapter.position"
                class="shrink-0 rounded-full border px-3 py-2 text-xs font-semibold"
                :class="index === progress.currentIndex ? 'border-copper bg-copper/10 text-copper' : 'border-ink/10 text-ink/50'"
                @click="selectSection(index)"
              >
                {{ chapter.position }}. {{ chapter.title }}
              </button>
            </nav>
          </section>

          <div v-if="mediaMode !== 'VIDEO'" class="mt-7 rounded-3xl bg-indigo/8 p-5">
            <p class="text-xs font-semibold uppercase tracking-[0.18em] text-indigo">{{ currentSection.visualKind.replaceAll('_', ' ') }}</p>
            <div class="my-6 flex items-center gap-2" aria-hidden="true">
              <span v-for="step in Math.min(currentSection.steps.length, 5)" :key="step" class="grid size-11 place-items-center rounded-full border-2 border-indigo/25 bg-paper font-display font-semibold text-indigo">{{ step }}</span>
              <span v-if="currentSection.steps.length > 1" class="h-0.5 flex-1 bg-indigo/20" />
            </div>
            <p class="text-sm text-ink/60">{{ currentSection.visualCaption }}</p>
          </div>

          <ol v-if="mediaMode !== 'VIDEO'" class="mt-7 space-y-5">
            <li v-for="step in currentSection.steps" :key="step.position" class="rounded-2xl border border-ink/8 p-4 sm:p-5">
              <p class="text-base leading-8 text-ink/75">{{ step.text }}</p>
              <details v-if="step.sourcePages.length" class="mt-3">
                <summary class="cursor-pointer text-sm font-semibold text-indigo">查看规则证据</summary>
                <p class="mt-2 rounded-xl bg-indigo/8 px-3 py-2 text-sm text-indigo">来源：规则书第 {{ step.sourcePages.join('、') }} 页</p>
              </details>
            </li>
          </ol>

          <details v-if="currentNarration" v-show="mediaMode === 'AUDIO'" open class="mt-7 rounded-2xl border border-indigo/15 bg-indigo/5 p-4 sm:p-5">
            <summary class="cursor-pointer list-none font-semibold text-indigo">
              <span class="flex items-center justify-between gap-3">
                <span>本节解说稿</span>
                <span class="text-xs">{{ currentNarration.supported ? '引用已保留' : '证据不足，跳过讲解' }}</span>
              </span>
            </summary>
            <ol class="mt-4 space-y-3 border-t border-indigo/10 pt-4" aria-label="同步字幕">
              <li
                v-for="segment in currentNarration.segments"
                :key="segment.position"
                class="rounded-xl border px-3 py-2 text-sm leading-7 transition"
                :class="activeCue?.chapterPosition === currentNarration.position && activeCue?.segmentPosition === segment.position ? 'border-copper/40 bg-copper/10 text-ink' : 'border-transparent text-ink/70'"
              >
                <p>{{ segment.text }}</p>
                <p v-if="segment.sourcePages.length" class="mt-1 text-xs font-semibold text-indigo">规则书第 {{ segment.sourcePages.join('、') }} 页</p>
                <button class="mt-1 text-xs font-semibold text-copper" @click="seekToSegment(segment.position)">从本段播放</button>
              </li>
            </ol>
            <div class="mt-5 border-t border-indigo/10 pt-4">
              <p class="text-xs leading-5 text-ink/50">当前为 {{ narrationProvider }} 媒体管线测试音轨（非语音）；字幕和音轨共用同一份已验证稿件。</p>
              <input
                class="mt-4 w-full accent-copper"
                type="range"
                min="0"
                :max="narrationDurationMillis"
                :value="narrationMillis"
                aria-label="解说播放位置"
                @input="seekNarration"
              >
              <div class="mt-1 flex justify-between text-xs tabular-nums text-ink/45">
                <span>{{ formatDuration(narrationMillis) }}</span>
                <span>{{ formatDuration(narrationDurationMillis) }}</span>
              </div>
              <div class="mt-3 grid grid-cols-3 gap-2">
                <button class="min-h-10 rounded-xl bg-indigo px-3 text-sm font-semibold text-white" @click="toggleNarration">{{ narrationPlaying ? '暂停' : '播放' }}</button>
                <button class="min-h-10 rounded-xl border border-indigo/20 px-3 text-sm font-semibold text-indigo" @click="replayCurrentSegment">重播本段</button>
                <button class="min-h-10 rounded-xl border border-indigo/20 px-3 text-sm font-semibold text-indigo" @click="cycleNarrationRate">{{ narrationRate }}×</button>
              </div>
            </div>
          </details>

          <div v-if="progress.paused" class="mt-7 rounded-2xl border border-copper/25 bg-copper/8 p-4 text-sm font-semibold" role="status">讲解已暂停。继续后才能完成或跳过当前章节。</div>
        </div>
      </section>
    </div>

    <nav v-if="lesson" class="fixed inset-x-0 bottom-0 z-30 border-t border-ink/10 bg-canvas/95 p-3 backdrop-blur lg:sticky lg:bottom-0 lg:mx-auto lg:max-w-4xl lg:rounded-2xl lg:border" aria-label="讲解控制">
      <div class="mx-auto grid max-w-3xl grid-cols-4 gap-2">
        <button :disabled="progress.currentIndex === 0" class="min-h-12 rounded-xl border border-ink/15 px-3 text-sm font-semibold disabled:opacity-35" @click="previousSection">上一节</button>
        <button class="min-h-12 rounded-xl border border-copper/30 px-3 text-sm font-semibold text-copper" @click="togglePause">{{ progress.paused ? '继续' : '暂停' }}</button>
        <button :disabled="progress.paused" class="min-h-12 rounded-xl border border-ink/15 px-3 text-sm font-semibold disabled:opacity-35" @click="finish('skipped')">跳过</button>
        <button :disabled="progress.paused" class="min-h-12 rounded-xl bg-copper px-3 text-sm font-semibold text-white disabled:opacity-35" @click="finish('completed')">{{ progress.currentIndex === lesson.sections.length - 1 ? '完成' : '下一节' }}</button>
      </div>
    </nav>
  </main>
</template>
