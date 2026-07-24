import { ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { useLessonReaderProgress } from '@/composables/useLessonReaderProgress'

function createProgress() {
  const lesson = ref({ id: 'lesson-1', sections: [{}, {}] })
  const onSectionSelected = vi.fn()
  const readerProgress = useLessonReaderProgress({
    lesson,
    onSectionSelected,
  })
  return { lesson, onSectionSelected, readerProgress }
}

describe('useLessonReaderProgress', () => {
  afterEach(() => {
    localStorage.clear()
  })

  it('selects a chapter through its owner and persists the current reader position', () => {
    const fixture = createProgress()

    fixture.readerProgress.selectSection(1)

    expect(fixture.readerProgress.progress.value.currentIndex).toBe(1)
    expect(fixture.onSectionSelected).toHaveBeenCalledWith(1)
    expect(JSON.parse(localStorage.getItem('rulepilot:lesson-progress:lesson-1') ?? '{}')).toMatchObject({ currentIndex: 1 })
  })

  it('waits at an active final chapter and clears that state when a published chapter is opened', () => {
    const fixture = createProgress()
    fixture.readerProgress.selectSection(1)

    fixture.readerProgress.finish('completed', true)
    expect(fixture.readerProgress.waitingForNextChapter.value).toBe(true)
    expect(fixture.readerProgress.progress.value.completed).toEqual([1])

    fixture.readerProgress.selectSection(0)
    expect(fixture.readerProgress.waitingForNextChapter.value).toBe(false)
  })

  it('restores an unpaused local position and lets media synchronize without a selection side effect', () => {
    localStorage.setItem('rulepilot:lesson-progress:lesson-1', JSON.stringify({
      currentIndex: 0, completed: [0], skipped: [], paused: true,
    }))
    const fixture = createProgress()

    fixture.readerProgress.restore()
    fixture.readerProgress.synchronizeChapter(1)

    expect(fixture.readerProgress.progress.value).toMatchObject({ currentIndex: 1, completed: [0], paused: false })
    expect(fixture.onSectionSelected).not.toHaveBeenCalled()
    expect(JSON.parse(localStorage.getItem('rulepilot:lesson-progress:lesson-1') ?? '{}')).toMatchObject({ currentIndex: 1 })
  })
})
