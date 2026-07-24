import { computed, ref, type Ref } from 'vue'

import type { SpeechCue } from '@/composables/lessonSupportingContent'

export type LessonMediaMode = 'TEXT' | 'AUDIO' | 'VIDEO'

interface LessonNarrationPlaybackOptions {
  lessonId: Readonly<Ref<string | null>>
  durationMillis: Ref<number>
  cues: Ref<SpeechCue[]>
  narrationMillis: Ref<number>
  narrationPlaying: Ref<boolean>
  narrationRestoreTarget: Ref<number | null>
  audioAvailable: Ref<boolean>
  mediaMode: Ref<LessonMediaMode>
  currentSectionIndex: Readonly<Ref<number>>
  synchronizeChapter: (chapterIndex: number) => void
  addWarning: (message: string) => void
  audioFailureMessage: () => string
}

export function useLessonNarrationPlayback(options: LessonNarrationPlaybackOptions) {
  const narrationPlayer = ref<HTMLAudioElement | null>(null)
  const narrationRate = ref(1)
  const activeCue = computed(() =>
    options.cues.value.find(
      (cue) => options.narrationMillis.value >= cue.startMillis && options.narrationMillis.value < cue.endMillis,
    ) ?? null,
  )

  function narrationPositionKey() {
    return options.lessonId.value ? `rulepilot:narration-position:${options.lessonId.value}` : ''
  }

  function saveNarrationPosition() {
    const key = narrationPositionKey()
    if (key) localStorage.setItem(key, String(Math.round(options.narrationMillis.value)))
  }

  function onNarrationLoaded() {
    const player = narrationPlayer.value
    if (!player) return
    player.playbackRate = narrationRate.value
    const restored = Number(localStorage.getItem(narrationPositionKey()))
    if (Number.isFinite(restored) && restored >= 0 && restored < options.durationMillis.value) {
      options.narrationRestoreTarget.value = restored
      options.narrationMillis.value = restored
      player.currentTime = restored / 1_000
    }
  }

  function onNarrationTimeUpdate() {
    const player = narrationPlayer.value
    if (!player || options.narrationRestoreTarget.value !== null) return
    options.narrationMillis.value = Math.round(player.currentTime * 1_000)
    const cue = activeCue.value
    if (options.mediaMode.value === 'VIDEO' && cue && options.currentSectionIndex.value !== cue.chapterPosition - 1) {
      options.synchronizeChapter(cue.chapterPosition - 1)
    }
    if (options.narrationPlaying.value) saveNarrationPosition()
  }

  function onNarrationSeeked() {
    const player = narrationPlayer.value
    const target = options.narrationRestoreTarget.value
    if (!player || target === null) return
    const current = Math.round(player.currentTime * 1_000)
    if (Math.abs(current - target) > 200) return
    options.narrationRestoreTarget.value = null
    options.narrationMillis.value = current
  }

  function onNarrationPaused() {
    if (options.narrationPlaying.value) {
      onNarrationTimeUpdate()
      saveNarrationPosition()
    }
    options.narrationPlaying.value = false
  }

  function onNarrationError() {
    options.audioAvailable.value = false
    narrationPlayer.value?.pause()
    if (options.mediaMode.value === 'AUDIO') options.mediaMode.value = 'TEXT'
    options.addWarning(options.audioFailureMessage())
  }

  async function toggleNarration() {
    const player = narrationPlayer.value
    if (!player || !options.audioAvailable.value) return
    if (player.paused) await player.play()
    else player.pause()
  }

  function seekToChapter(index: number) {
    seekToCue(options.cues.value.find((candidate) => candidate.chapterPosition === index + 1))
  }

  function seekToSegment(segmentPosition: number) {
    seekToCue(options.cues.value.find(
      (candidate) =>
        candidate.chapterPosition === options.currentSectionIndex.value + 1 &&
        candidate.segmentPosition === segmentPosition,
    ), true)
  }

  function seekToCue(cue: SpeechCue | undefined, play = false) {
    const player = narrationPlayer.value
    if (!player || !cue) return
    options.narrationRestoreTarget.value = cue.startMillis
    player.currentTime = cue.startMillis / 1_000
    options.narrationMillis.value = cue.startMillis
    saveNarrationPosition()
    if (play) void player.play()
  }

  function replayCurrentSegment() {
    const fallback = options.cues.value.find(
      (cue) => cue.chapterPosition === options.currentSectionIndex.value + 1,
    )
    seekToCue(activeCue.value ?? fallback, true)
  }

  function cycleNarrationRate() {
    const rates = [0.75, 1, 1.25, 1.5, 2]
    const currentIndex = rates.indexOf(narrationRate.value)
    narrationRate.value = rates[(currentIndex + 1) % rates.length] ?? 1
    if (narrationPlayer.value) narrationPlayer.value.playbackRate = narrationRate.value
  }

  function seekNarration(millis: number) {
    const player = narrationPlayer.value
    if (!player) return
    options.narrationRestoreTarget.value = null
    player.currentTime = millis / 1_000
    options.narrationMillis.value = millis
    saveNarrationPosition()
  }

  function formatDuration(millis: number) {
    const seconds = Math.floor(millis / 1_000)
    return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
  }

  return {
    narrationPlayer,
    narrationRate,
    activeCue,
    narrationPositionKey,
    onNarrationLoaded,
    onNarrationTimeUpdate,
    onNarrationSeeked,
    onNarrationPaused,
    onNarrationError,
    toggleNarration,
    seekToChapter,
    seekToSegment,
    replayCurrentSegment,
    cycleNarrationRate,
    seekNarration,
    formatDuration,
  }
}
