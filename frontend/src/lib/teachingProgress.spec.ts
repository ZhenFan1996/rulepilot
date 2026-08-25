import { describe, expect, it } from 'vitest'

import {
  mergeTeachingRunProgress,
  processedTeachingChapterCount,
  recentTeachingActivitySteps,
  recentTeachingPreparationActivitySteps,
  rejectedTeachingChapterCount,
  supportedTeachingChapterCount,
  teachingActivityCursor,
  teachingActivityText,
  teachingChapterFailureText,
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

  it('normalizes first and replacement run snapshots with the last incoming sequence winning', () => {
    const first = run('run-1', [
      activity(3, 'composeTeachingSection|1', 'RUNNING'),
      activity(1, 'searchRuleEvidence|1', 'RUNNING', 'older sequence state'),
      activity(1, 'searchRuleEvidence|1', 'SUCCEEDED', 'newer sequence state'),
    ])

    const acceptedFirst = mergeTeachingRunProgress(null, first)!
    expect(acceptedFirst.activities.map(item => item.sequence)).toEqual([1, 3])
    expect(acceptedFirst.activities[0]).toMatchObject({ outcome: 'SUCCEEDED', summary: 'newer sequence state' })

    const replacement = run('run-2', [
      activity(4, 'composeTeachingSection|1', 'RUNNING'),
      activity(2, 'searchRuleEvidence|1', 'RUNNING'),
      activity(2, 'searchRuleEvidence|1', 'SUCCEEDED'),
    ])
    expect(mergeTeachingRunProgress(acceptedFirst, replacement)!.activities).toMatchObject([
      { sequence: 2, outcome: 'SUCCEEDED' },
      { sequence: 4, outcome: 'RUNNING' },
    ])
  })

  it('keeps the newer run metadata when an older snapshot arrives late', () => {
    const current = run('run-1', [activity(2, 'publishTeachingSection|1', 'SUCCEEDED')])
    current.run.state = 'COMPLETED'
    current.run.updatedAt = '2026-07-21T00:03:00Z'
    current.run.completedAt = '2026-07-21T00:03:00Z'
    const stale = run('run-1', [activity(1, 'composeTeachingSection|1', 'RUNNING')])

    const merged = mergeTeachingRunProgress(current, stale)!

    expect(merged.run.state).toBe('COMPLETED')
    expect(merged.run.updatedAt).toBe('2026-07-21T00:03:00Z')
    expect(merged.activities.map((item) => item.sequence)).toEqual([1, 2])
    expect(mergeTeachingRunProgress(current, null)).toEqual(current)
  })

  it('uses safe player language and derives chapter outcomes from publication activities', () => {
    const activities = [
      activity(1, 'composeTeachingSection|1', 'RUNNING'),
      activity(2, 'publishTeachingSection|1', 'SUCCEEDED', 'Teaching section published: CITED_BASE_SECTION_PUBLISHED'),
      activity(3, 'reviewPublishedTeachingSection', 'RUNNING'),
      activity(4, 'publishTeachingSection|1', 'SUCCEEDED', 'Teaching section published: POST_PUBLICATION_REVIEW_ACCEPTED'),
      activity(5, 'publishTeachingSection|2', 'REJECTED'),
    ]
    const snapshot = run('run-1', activities)

    expect(teachingActivityText(plan, activities, activities[0])).toBe('正在依据规则书编写第 1 章“完成开局设置”')
    expect(processedTeachingChapterCount(snapshot)).toBe(2)
    expect(supportedTeachingChapterCount(snapshot)).toBe(1)
    expect(rejectedTeachingChapterCount(snapshot)).toBe(1)
    expect(teachingRemainingTimeText(plan, run('run-1', []), Date.parse('2026-07-21T00:02:00Z')))
      .toContain('第一节完成后')
    expect(teachingActivityText(plan, activities, activities[0], 'en'))
      .toBe('Writing chapter 1 “完成开局设置” from the rulebook')
    expect(teachingRemainingTimeText(plan, run('run-1', []), Date.parse('2026-07-21T00:02:00Z'), 'en'))
      .toContain('After the first chapter is ready')
  })

  it('reports exact processed and rejected chapter counts with a player-facing failure category', () => {
    const activities = [1, 2].map(position => activity(
      position,
      `publishTeachingSection|${position}`,
      'REJECTED',
      'Teaching section withheld: NO_VALID_BASE_EVIDENCE',
    ))
    const snapshot = run('run-visual-failed', activities)

    expect(processedTeachingChapterCount(snapshot)).toBe(2)
    expect(supportedTeachingChapterCount(snapshot)).toBe(0)
    expect(rejectedTeachingChapterCount(snapshot)).toBe(2)
    expect(teachingChapterFailureText(snapshot)).toBe('引用页没有形成可供这些章节发布的规则依据。')
    expect(teachingChapterFailureText(snapshot, 'en'))
      .toBe('The cited pages did not yield publishable rule evidence for these chapters.')
  })

  it('distinguishes an invalid evidence identity from an empty page read', () => {
    const snapshot = run('run-invalid-evidence', [activity(
      1,
      'publishTeachingSection|1',
      'REJECTED',
      'Teaching section withheld: BASE_EVIDENCE_IDENTITY_INVALID',
    )])

    expect(teachingChapterFailureText(snapshot))
      .toBe('引用页依据没有通过来源或规则书版本校验。')
    expect(teachingChapterFailureText(snapshot, 'en'))
      .toBe('The cited page evidence did not pass source or rulebook-version validation.')
  })

  it('uses the latest publication outcome when one bounded retry succeeds', () => {
    const snapshot = run('run-recovered', [
      activity(1, 'publishTeachingSection|1', 'REJECTED', 'Teaching section withheld: BASE_DRAFT_WITHHELD'),
      activity(2, 'publishTeachingSection|1', 'SUCCEEDED', 'Teaching section published: CITED_BASE_SECTION_PUBLISHED'),
    ])

    expect(processedTeachingChapterCount(snapshot)).toBe(1)
    expect(supportedTeachingChapterCount(snapshot)).toBe(1)
    expect(rejectedTeachingChapterCount(snapshot)).toBe(0)
    expect(teachingChapterFailureText(snapshot)).toBe('')
  })

  it('explains that remaining visual pages are read only after the starter chapter is ready', () => {
    const activities = [
      activity(1, 'readRuleEvidencePages|1', 'SUCCEEDED'),
      activity(2, 'publishTeachingSection|1', 'SUCCEEDED', 'Teaching section published: CITED_BASE_SECTION_PUBLISHED'),
      activity(3, 'readProgressiveVisualPages|2|1', 'SUCCEEDED'),
      activity(4, 'prefetchProgressiveVisualPages|2', 'RUNNING'),
    ]

    expect(teachingActivityText(plan, activities, activities[0]))
      .toBe('正在读取第 1 章“完成开局设置”引用的规则书页面')
    expect(teachingActivityText(plan, activities, activities[2]))
      .toContain('基础讲解已可读')
    expect(teachingActivityText(plan, activities, activities[3]))
      .toBe('基础讲解已可读，正在一次性读取后续页面要点')
    expect(teachingActivityText(plan, activities, activities[3], 'en'))
      .toContain('Starter guide ready')
  })

  it('turns real chapter operations into a concrete player-visible generation sequence', () => {
    const activities = [
      activity(1, 'readTeachingSourcePages|1', 'SUCCEEDED'),
      activity(2, 'composeTeachingSection|1', 'SUCCEEDED'),
      activity(3, 'validateTeachingSection|1|0', 'SUCCEEDED'),
      activity(4, 'publishTeachingSection|1', 'SUCCEEDED', 'Teaching section published: CITED_BASE_SECTION_PUBLISHED'),
      activity(5, 'reviewGeneratedContent', 'SUCCEEDED'),
    ]

    const steps = recentTeachingActivitySteps(plan, activities)

    expect(steps.map(step => step.text)).toEqual([
      '正在读取第 1 章“完成开局设置”引用的规则书页面',
      '正在依据规则书编写第 1 章“完成开局设置”',
      '第 1 章“完成开局设置”已完成引用归属、规则书版本与结构校验',
      '第 1 章“完成开局设置”已经可以阅读',
    ])
    expect(steps.map(step => step.outcome)).toEqual([
      'SUCCEEDED', 'SUCCEEDED', 'SUCCEEDED', 'SUCCEEDED',
    ])
    expect(JSON.stringify(steps)).not.toMatch(/readTeachingSourcePages|composeTeachingSection|reviewGeneratedContent/)
  })

  it('shows real chapter-planning work before the first teaching run exists', () => {
    const activities = [
      activity(1, 'transcribeTeachingVisualPage|3|16', 'SUCCEEDED'),
      activity(2, 'inspectTeachingVisualPage|3|16', 'SUCCEEDED'),
      activity(3, 'organizeTeachingOutline', 'RUNNING'),
      activity(4, 'refineTeachingOutlineCoverage', 'SUCCEEDED'),
      activity(5, 'internalOutlineTelemetry', 'SUCCEEDED'),
    ]

    const steps = recentTeachingPreparationActivitySteps(activities)

    expect(steps.map(step => step.text)).toEqual([
      '图像规则页第 3 / 16 页的逐字识别完成',
      '图像规则页第 3 / 16 页的规则整理完成',
      '正在通读规则书，先形成整局认识再规划讲解章节',
      '正在检查章节规划有没有漏掉规则内容',
    ])
    expect(JSON.stringify(steps)).not.toMatch(/organizeTeachingOutline|refineTeachingOutlineCoverage|internalOutlineTelemetry/)
  })

  it('keeps unsuccessful first page attempts neutral without declaring a task retry', () => {
    const activities = [
      activity(1, 'inspectTeachingVisualPage|7|16', 'FAILED'),
      activity(2, 'inspectTeachingVisualPage|8|16', 'REJECTED'),
    ]

    expect(recentTeachingPreparationActivitySteps(activities).map(step => step.text)).toEqual([
      '图像规则页第 7 / 16 页的规则整理本次未完成',
      '图像规则页第 8 / 16 页的规则整理本次校验未通过',
    ])
    expect(recentTeachingPreparationActivitySteps(activities, 'en').map(step => step.text)).toEqual([
      'Rule grouping for visual rulebook page 7 of 16 did not complete this time',
      'Rule grouping for visual rulebook page 8 of 16 did not pass validation this time',
    ])
    expect(JSON.stringify(recentTeachingPreparationActivitySteps(activities))).not.toContain('需要重试')
  })

  it('names reprocessing only when a retry activity was actually emitted', () => {
    const activities = [
      activity(1, 'inspectTeachingVisualRetry|7|16', 'RUNNING'),
      activity(2, 'inspectTeachingVisualRetry|8|16', 'SUCCEEDED'),
      activity(3, 'inspectTeachingVisualRetry|9|16', 'FAILED'),
      activity(4, 'inspectTeachingVisualRetry|10|16', 'REJECTED'),
    ]

    expect(recentTeachingPreparationActivitySteps(activities).map(step => step.text)).toEqual([
      '临时服务异常，正在重试图像规则页第 7 / 16 页的规则整理',
      '图像规则页第 8 / 16 页的规则整理在临时服务异常后重试完成',
      '图像规则页第 9 / 16 页的规则整理在临时服务异常后重试仍未完成',
      '图像规则页第 10 / 16 页的规则整理在临时服务异常后重试仍未通过校验',
    ])
    expect(recentTeachingPreparationActivitySteps(activities, 'en').map(step => step.text)).toEqual([
      'A temporary service error occurred; retrying rule grouping for visual rulebook page 7 of 16',
      'Rule grouping retry completed after a temporary service error for visual rulebook page 8 of 16',
      'Rule grouping retry for visual rulebook page 9 of 16 still did not complete after a temporary service error',
      'Rule grouping retry for visual rulebook page 10 of 16 did not pass validation after a temporary service error',
    ])
  })

  it('explains validator-owned page repair without presenting it as a transport retry', () => {
    const activities = [
      activity(1, 'inspectTeachingVisualRepair|7|16|MALFORMED_JSON', 'RUNNING'),
      activity(2, 'inspectTeachingVisualRepair|8|16|DUPLICATE_RULE_GROUP', 'SUCCEEDED'),
      activity(3, 'inspectTeachingVisualRepair|9|16|PAGE_BINDING_MISMATCH', 'FAILED'),
      activity(4, 'inspectTeachingVisualRepair|10|16|SCHEMA_MISMATCH', 'REJECTED'),
    ]

    expect(recentTeachingPreparationActivitySteps(activities).map(step => step.text)).toEqual([
      '返回格式没有通过校验，正在修正图像规则页第 7 / 16 页的规则整理',
      '图像规则页第 8 / 16 页的规则整理修正完成',
      '图像规则页第 9 / 16 页的规则整理经过一次修正后仍未完成',
      '图像规则页第 10 / 16 页的规则整理经过一次修正后仍未通过校验',
    ])
    expect(recentTeachingPreparationActivitySteps(activities, 'en').map(step => step.text)).toEqual([
      'The returned format did not pass validation; correcting the rule grouping for visual rulebook page 7 of 16',
      'Rule grouping correction completed for visual rulebook page 8 of 16',
      'Rule grouping for visual rulebook page 9 of 16 still did not complete after one correction',
      'Rule grouping for visual rulebook page 10 of 16 still did not pass validation after one correction',
    ])
  })

  it('shows OCR and semantic grouping as two real visual-page steps', () => {
    const activities = [
      activity(1, 'transcribeTeachingVisualPage|7|16', 'RUNNING'),
      activity(2, 'inspectTeachingVisualPage|7|16', 'RUNNING'),
    ]

    expect(recentTeachingPreparationActivitySteps(activities).map(step => step.text)).toEqual([
      '正在逐字识别图像规则页第 7 / 16 页',
      '正在整理图像规则页第 7 / 16 页的规则组',
    ])
  })

  it('keeps the complete real preparation history instead of hiding early pages behind a display cap', () => {
    const activities = Array.from({ length: 16 }, (_, index) => index + 1)
      .flatMap(page => [
        activity(page * 2 - 1, `transcribeTeachingVisualPage|${page}|16`, 'SUCCEEDED'),
        activity(page * 2, `inspectTeachingVisualPage|${page}|16`, 'SUCCEEDED'),
      ])

    const steps = recentTeachingPreparationActivitySteps(activities)

    expect(steps).toHaveLength(32)
    expect(steps[0]?.text).toBe('图像规则页第 1 / 16 页的逐字识别完成')
    expect(steps.at(-1)?.text).toBe('图像规则页第 16 / 16 页的规则整理完成')
  })

  it('shows a repair only when the backend actually emitted one', () => {
    const activities = [
      activity(1, 'composeTeachingSection|2', 'SUCCEEDED'),
      activity(2, 'validateTeachingSection|2|0', 'REJECTED'),
      activity(3, 'reviseTeachingSection|2', 'RUNNING'),
    ]

    expect(recentTeachingActivitySteps(plan, activities).map(step => step.text)).toEqual([
      '正在依据规则书编写第 2 章“走完第一轮”',
      '第 2 章“走完第一轮”需要局部修正后再发布',
      '校验发现局部问题，正在修正第 2 章“走完第一轮”',
    ])
  })

  it('shows the one bounded unfinished-chapter retry as real progress', () => {
    const activities = [
      activity(1, 'publishTeachingSection|2', 'REJECTED', 'Teaching section withheld: BASE_DRAFT_WITHHELD'),
      activity(2, 'retryIncompleteTeachingSections', 'SUCCEEDED'),
      activity(3, 'composeTeachingSection|2', 'RUNNING'),
    ]

    expect(recentTeachingActivitySteps(plan, activities).map(step => step.text)).toEqual([
      '第 2 章“走完第一轮”本次未发布，其他已校验章节不受影响',
      '有章节草稿没有通过校验，正在只重试未完成章节一次；已发布内容不会重做',
      '正在依据规则书编写第 2 章“走完第一轮”',
    ])
    expect(recentTeachingActivitySteps(plan, activities, 'en').map(step => step.text)).toEqual([
      'chapter 2 “走完第一轮” was not published; other validated chapters remain available',
      'A chapter draft did not pass its checks; retrying only the unfinished chapters once',
      'Writing chapter 2 “走完第一轮” from the rulebook',
    ])
  })
})

function run(id: string, activities: TeachingActivity[]): TeachingRunProgress {
  return {
    run: {
      id, subjectId: 'plan-1', state: 'RETRIEVING', createdAt: '2026-07-21T00:00:00Z', updatedAt: '2026-07-21T00:01:00Z',
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
  summary = 'internal summary',
): TeachingActivity {
  return {
    sequence, type: 'MODEL', operation, summary, outcome, latencyMs: 0,
    occurredAt: '2026-07-21T00:01:00Z',
  }
}
