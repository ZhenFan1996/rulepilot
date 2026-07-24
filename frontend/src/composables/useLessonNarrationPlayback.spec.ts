import { ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { useLessonNarrationPlayback, type LessonMediaMode } from '@/composables/useLessonNarrationPlayback'

function createPlayer({ currentTime = 0, paused = true } = {}) {
  const player = document.createElement('audio')
  Object.defineProperties(player, {
    currentTime: { configurable: true, writable: true, value: currentTime },
    paused: { configurable: true, get: () => paused },
    playbackRate: { configurable: true, writable: true, value: 1 },
    play: { configurable: true, value: vi.fn(async () => undefined) },
    pause: { configurable: true, value: vi.fn() },
  })
  return player
}

function createPlayback({
  currentSectionIndex = 0,
  mediaMode = 'TEXT' as LessonMediaMode,
  restoreTarget = null as number | null,
} = {}) {
  const lessonId = ref('lesson-1')
  const durationMillis = ref(20_000)
  const cues = ref([
    { chapterPosition: 1, segmentPosition: 1, startMillis: 0, endMillis: 9_999 },
    { chapterPosition: 2, segmentPosition: 1, startMillis: 10_000, endMillis: 20_000 },
  ])
  const narrationMillis = ref(0)
  const narrationPlaying = ref(false)
  const narrationRestoreTarget = ref(restoreTarget)
  const audioAvailable = ref(true)
  const activeMediaMode = ref(mediaMode)
  const activeSection = ref(currentSectionIndex)
  const synchronizeChapter = vi.fn((chapterIndex: number) => { activeSection.value = chapterIndex })
  const addWarning = vi.fn()
  const playback = useLessonNarrationPlayback({
    lessonId,
    durationMillis,
    cues,
    narrationMillis,
    narrationPlaying,
    narrationRestoreTarget,
    audioAvailable,
    mediaMode: activeMediaMode,
    currentSectionIndex: activeSection,
    synchronizeChapter,
    addWarning,
    audioFailureMessage: () => 'Audio could not load.',
  })

  return {
    playback,
    narrationMillis,
    narrationRestoreTarget,
    audioAvailable,
    activeMediaMode,
    activeSection,
    synchronizeChapter,
    addWarning,
  }
}

describe('useLessonNarrationPlayback', () => {
  afterEach(() => {
    localStorage.clear()
  })

  it('restores a valid position and waits for the matching seek before accepting time updates', () => {
    localStorage.setItem('rulepilot:narration-position:lesson-1', '4500')
    const fixture = createPlayback()
    const player = createPlayer()
    fixture.playback.narrationPlayer.value = player

    fixture.playback.onNarrationLoaded()

    expect(player.currentTime).toBe(4.5)
    expect(fixture.narrationMillis.value).toBe(4_500)
    expect(fixture.narrationRestoreTarget.value).toBe(4_500)

    player.currentTime = 4.5
    fixture.playback.onNarrationSeeked()
    expect(fixture.narrationRestoreTarget.value).toBeNull()

    player.currentTime = 12
    fixture.playback.onNarrationTimeUpdate()
    expect(fixture.narrationMillis.value).toBe(12_000)
  })

  it('keeps a selected video chapter stable while a restored stale cue arrives', () => {
    const fixture = createPlayback({ currentSectionIndex: 1, mediaMode: 'VIDEO', restoreTarget: 10_000 })
    const player = createPlayer({ currentTime: 0 })
    fixture.playback.narrationPlayer.value = player

    fixture.playback.onNarrationTimeUpdate()

    expect(fixture.activeSection.value).toBe(1)
    expect(fixture.synchronizeChapter).not.toHaveBeenCalled()
  })

  it('seeks by chapter, persists the cue, and falls back to text after an audio error', () => {
    const fixture = createPlayback({ mediaMode: 'AUDIO' })
    const player = createPlayer()
    fixture.playback.narrationPlayer.value = player

    fixture.playback.seekToChapter(1)
    expect(player.currentTime).toBe(10)
    expect(fixture.narrationMillis.value).toBe(10_000)
    expect(localStorage.getItem('rulepilot:narration-position:lesson-1')).toBe('10000')

    fixture.playback.onNarrationError()
    expect(fixture.audioAvailable.value).toBe(false)
    expect(fixture.activeMediaMode.value).toBe('TEXT')
    expect(fixture.addWarning).toHaveBeenCalledWith('Audio could not load.')
    expect(player.pause).toHaveBeenCalledOnce()
  })
})
