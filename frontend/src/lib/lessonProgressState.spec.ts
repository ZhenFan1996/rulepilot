import { describe, expect, it } from 'vitest'

import { hasReadableLesson, mergeLessonProgress } from './lessonProgressState'

describe('lesson progress continuity', () => {
  it('keeps a known lesson across a transient not-found response', () => {
    const lesson = { id: 'lesson-1', status: 'DRAFT_READY' as const, sections: [{ evidenceStatus: 'CITED_DRAFT' as const }] }
    expect(mergeLessonProgress(lesson, null)).toEqual(lesson)
  })

  it('does not regress the status or discard already published sections', () => {
    const previous = {
      id: 'lesson-1', status: 'COMPLETE' as const,
      sections: [{ evidenceStatus: 'SUPPORTED' as const }, { evidenceStatus: 'SUPPORTED' as const }],
    }
    const stale = { id: 'lesson-1', status: 'INCOMPLETE' as const, sections: [{ evidenceStatus: 'SUPPORTED' as const }] }
    expect(mergeLessonProgress(previous, stale)).toEqual(previous)
  })

  it('does not call an empty or unsupported artifact readable', () => {
    expect(hasReadableLesson({ id: 'empty', status: 'COMPLETE', sections: [] })).toBe(false)
    expect(hasReadableLesson({
      id: 'unsupported', status: 'INCOMPLETE', sections: [{ evidenceStatus: 'INSUFFICIENT_EVIDENCE' }],
    })).toBe(false)
    expect(hasReadableLesson({
      id: 'draft', status: 'DRAFT_READY', sections: [{ evidenceStatus: 'CITED_DRAFT' }],
    })).toBe(true)
  })
})
