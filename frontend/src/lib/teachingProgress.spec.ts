import { describe, expect, it } from 'vitest'

import {
  mergeTeachingRunProgress,
  processedTeachingChapterCount,
  supportedTeachingChapterCount,
  teachingActivityCursor,
  teachingActivityText,
  teachingRemainingTimeText,
  type TeachingActivity,
  type TeachingRunProgress,
} from './teachingProgress'

const plan = {
  sections: [
    { position: 1, title: '完成开局设置', visualEvidenceRecommended: true },
    { position: 2, title: '走完第一轮', visualEvidenceRecommended: false },
  ],
}

describe('teaching progress', () => {
  it('merges only new activity sequences and resets for a replacement run', () => {
    const first = run('run-1', [activity(1, 'composeTeachingSection|1', 'RUNNING')])
    const next = run('run-1', [activity(2, 'publishTeachingSection|1', 'SUCCEEDED')])
    const merged = mergeTeachingRunProgress(first, next)!

    expect(merged.activities.map((item) => item.sequence)).toEqual([1, 2])
    expect(teachingActivityCursor(merged)).toContain('activityRunId=run-1&afterActivitySequence=2')
    expect(mergeTeachingRunProgress(merged, run('run-2', [activity(1, 'searchRuleEvidence|1', 'RUNNING')]))!
      .activities).toHaveLength(1)
  })

  it('uses safe player language and derives chapter outcomes from publication activities', () => {
    const activities = [
      activity(1, 'composeTeachingSection|1', 'RUNNING'),
      activity(2, 'publishTeachingSection|1', 'SUCCEEDED'),
      activity(3, 'publishTeachingSection|2', 'REJECTED'),
    ]
    const snapshot = run('run-1', activities)

    expect(teachingActivityText(plan, activities, activities[0])).toBe('正在阅读规则书图片并编写“完成开局设置”')
    expect(processedTeachingChapterCount(snapshot)).toBe(2)
    expect(supportedTeachingChapterCount(snapshot)).toBe(1)
    expect(teachingRemainingTimeText(plan, run('run-1', []), Date.parse('2026-07-21T00:02:00Z')))
      .toContain('第一节完成后')
  })
})

function run(id: string, activities: TeachingActivity[]): TeachingRunProgress {
  return {
    run: {
      id, state: 'RETRIEVING', createdAt: '2026-07-21T00:00:00Z', updatedAt: '2026-07-21T00:01:00Z',
      completedAt: null, lastErrorCode: null,
    },
    budget: { usedModelCalls: 1, maxModelCalls: 144 },
    activities,
  }
}

function activity(
  sequence: number,
  operation: string,
  outcome: TeachingActivity['outcome'],
): TeachingActivity {
  return {
    sequence, type: 'MODEL', operation, summary: 'internal summary', outcome, latencyMs: 0,
    occurredAt: '2026-07-21T00:01:00Z',
  }
}
