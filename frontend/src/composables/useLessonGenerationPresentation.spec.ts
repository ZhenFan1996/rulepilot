import { ref } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'

import { useLessonGenerationPresentation } from '@/composables/useLessonGenerationPresentation'
import { setLocale } from '@/lib/locale'
import type { TeachingRunProgress } from '@/lib/teachingProgress'

function run(state: string, activities: TeachingRunProgress['activities']): TeachingRunProgress {
  return {
    run: { id: 'run-1', subjectId: 'plan-1', state, createdAt: '2026-07-24T00:00:00Z', updatedAt: '2026-07-24T00:01:00Z', completedAt: null, lastErrorCode: null },
    budget: { usedModelCalls: 3 },
    activities,
  }
}

const publishActivity: TeachingRunProgress['activities'][number] = {
  sequence: 1,
  type: 'VALIDATION',
  operation: 'publishTeachingSection|1',
  summary: 'CITED_DRAFT_PUBLISHED',
  outcome: 'SUCCEEDED',
  latencyMs: 120,
  occurredAt: '2026-07-24T00:01:00Z',
}

describe('useLessonGenerationPresentation', () => {
  afterEach(() => setLocale('zh-CN'))

  it('derives readable in-progress teaching status from the active run', () => {
    setLocale('en')
    const presentation = useLessonGenerationPresentation({
      plan: ref({ sections: [{ position: 1, title: 'Setup', visualEvidenceRecommended: true }, { position: 2, title: 'Scoring', visualEvidenceRecommended: true }] }),
      lesson: ref({ status: 'INCOMPLETE' as const, sections: [{}] }),
      currentSectionIndex: ref(0),
      generationRun: ref(run('COMPOSING', [publishActivity])),
      generationStatusUnknown: ref(false),
      now: ref(new Date('2026-07-24T00:03:30Z').getTime()),
    })

    expect(presentation.generationActive.value).toBe(true)
    expect(presentation.lessonStillGrowing.value).toBe(true)
    expect(presentation.readingCurrentLastChapter.value).toBe(true)
    expect(presentation.processedGenerationChapters.value).toBe(1)
    expect(presentation.generationProgressWidth.value).toBe('50%')
    expect(presentation.currentGenerationText.value).toContain('chapter 1 “Setup” is now readable')
    expect(presentation.generationElapsed.value).toBe('3:30')
  })

  it('keeps only unresolved player-facing local failures for the terminal explanation', () => {
    const presentation = useLessonGenerationPresentation({
      plan: ref({ sections: [{ position: 1, title: '开局', visualEvidenceRecommended: true }, { position: 2, title: '计分', visualEvidenceRecommended: true }] }),
      lesson: ref({ status: 'DRAFT_READY' as const, sections: [{}] }),
      currentSectionIndex: ref(0),
      generationRun: ref(run('FAILED', [
        { ...publishActivity, sequence: 1, operation: 'publishTeachingSection|1', outcome: 'REJECTED' },
        { ...publishActivity, sequence: 2, operation: 'publishTeachingSection|1', outcome: 'SUCCEEDED' },
        { ...publishActivity, sequence: 3, operation: 'enrichTeachingSectionVisual|2', outcome: 'REJECTED' },
      ])),
      generationStatusUnknown: ref(false),
      now: ref(new Date('2026-07-24T00:03:30Z').getTime()),
    })

    expect(presentation.terminalGenerationIssues.value).toEqual([
      expect.objectContaining({
        sequence: 3,
        text: '第 2 章“计分”经过有限选择后仍没有可用配图；仅省略图片，已校验正文仍可阅读',
      }),
    ])
    expect(presentation.recentGenerationActivities.value.every(activity =>
      !activity.text.includes('正在整理并核对讲解'))).toBe(true)
  })
})
