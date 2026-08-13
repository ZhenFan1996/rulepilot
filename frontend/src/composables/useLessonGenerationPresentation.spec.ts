import { ref } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'

import { useLessonGenerationPresentation } from '@/composables/useLessonGenerationPresentation'
import { setLocale } from '@/lib/locale'
import type { TeachingRunProgress } from '@/lib/teachingProgress'

function run(state: string, activities: TeachingRunProgress['activities']): TeachingRunProgress {
  return {
    run: { id: 'run-1', subjectId: 'plan-1', state, createdAt: '2026-07-24T00:00:00Z', updatedAt: '2026-07-24T00:01:00Z', completedAt: null, lastErrorCode: null },
    budget: { usedModelCalls: 3, maxModelCalls: 48 },
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
      visualEnrichmentRun: ref(null),
      generationStatusUnknown: ref(false),
      now: ref(new Date('2026-07-24T00:03:30Z').getTime()),
    })

    expect(presentation.generationActive.value).toBe(true)
    expect(presentation.lessonStillGrowing.value).toBe(true)
    expect(presentation.readingCurrentLastChapter.value).toBe(true)
    expect(presentation.processedGenerationChapters.value).toBe(1)
    expect(presentation.generationProgressWidth.value).toBe('50%')
    expect(presentation.currentGenerationText.value).toContain('starter guide')
    expect(presentation.generationElapsed.value).toBe('3:30')
  })

  it('localizes deterministic visual-enrichment summaries without translating activity evidence', () => {
    setLocale('en')
    const presentation = useLessonGenerationPresentation({
      plan: ref({ sections: [] }),
      lesson: ref({ status: 'COMPLETE' as const, sections: [] }),
      currentSectionIndex: ref(0),
      generationRun: ref(null),
      visualEnrichmentRun: ref(run('COMPLETED', [{
        sequence: 1,
        type: 'VALIDATION',
        operation: 'visualSection|1',
        summary: 'source evidence',
        outcome: 'SUCCEEDED',
        latencyMs: 30,
        occurredAt: '2026-07-24T00:01:00Z',
      }])),
      generationStatusUnknown: ref(false),
      now: ref(Date.now()),
    })

    expect(presentation.visualEnrichmentActive.value).toBe(false)
    expect(presentation.visualEnrichmentSummary.value).toBe(
      'Added a checkable focused visual to 1 chapter; the remaining chapters keep only evidence-backed visuals.',
    )
  })
})
