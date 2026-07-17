import { describe, expect, it } from 'vitest'

import { finishSection, restoreLessonProgress } from './lessonProgress'

describe('lesson progress', () => {
  it('restores safe progress and advances completed sections', () => {
    const restored = restoreLessonProgress('{"currentIndex":1,"completed":[0,99],"paused":true}', 3)
    expect(restored).toEqual({ currentIndex: 1, completed: [0], skipped: [], paused: true })

    expect(finishSection(restored, 3, 'completed')).toEqual({
      currentIndex: 2,
      completed: [0, 1],
      skipped: [],
      paused: false,
    })
  })
})
