import { describe, expect, it } from 'vitest'

import {
  acceptProgressiveLesson,
  teachingLessonNeedsFinalSnapshot,
  teachingRunIsActive,
} from './liveLesson'

describe('live lesson snapshots', () => {
  it('accepts added chapters without allowing a stale response to remove readable content', () => {
    const first = { id: 'lesson-1', status: 'INCOMPLETE', sections: ['setup'] }
    const expanded = { id: 'lesson-1', status: 'INCOMPLETE', sections: ['setup', 'turn'] }

    expect(acceptProgressiveLesson(first, expanded)).toBe(expanded)
    expect(acceptProgressiveLesson(expanded, first)).toBe(expanded)
    expect(acceptProgressiveLesson(expanded, { id: 'lesson-2', status: 'INCOMPLETE', sections: ['new'] }).id)
      .toBe('lesson-2')
  })

  it('polls only non-terminal teaching runs', () => {
    expect(teachingRunIsActive('RETRIEVING')).toBe(true)
    expect(teachingRunIsActive('LESSON_COMPOSITION')).toBe(true)
    expect(teachingRunIsActive('COMPLETED')).toBe(false)
    expect(teachingRunIsActive('INSUFFICIENT_EVIDENCE')).toBe(false)
    expect(teachingRunIsActive('CANCELLED')).toBe(false)
    expect(teachingRunIsActive(null)).toBe(false)
  })

  it('reconciles a completed run until the lesson reaches a final persisted status', () => {
    expect(teachingLessonNeedsFinalSnapshot('COMPLETED', null)).toBe(true)
    expect(teachingLessonNeedsFinalSnapshot('COMPLETED', 'DRAFT_READY')).toBe(true)
    expect(teachingLessonNeedsFinalSnapshot('COMPLETED', 'COMPLETE')).toBe(false)
    expect(teachingLessonNeedsFinalSnapshot('COMPLETED', 'INCOMPLETE')).toBe(false)
    expect(teachingLessonNeedsFinalSnapshot('FAILED', 'DRAFT_READY')).toBe(false)
    expect(teachingLessonNeedsFinalSnapshot('LESSON_COMPOSITION', 'DRAFT_READY')).toBe(false)
  })
})
