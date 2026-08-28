import { describe, expect, it } from 'vitest'

import {
  mergeTeachingRunProgress,
  processedTeachingChapterCount,
  recentTeachingActivitySteps,
  recentTeachingPreparationActivitySteps,
  rejectedTeachingChapterCount,
  summarizeTeachingVisualPageRuleGroups,
  supportedTeachingChapterCount,
  teachingActivityCursor,
  teachingActivityText,
  teachingChapterFailureText,
  teachingRemainingTimeText,
  teachingRunStopReasonText,
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
      activity(3, 'publishTeachingSection|1', 'SUCCEEDED', 'Teaching section published: POST_PUBLICATION_REVIEW_ACCEPTED'),
      activity(4, 'publishTeachingSection|2', 'REJECTED'),
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
      activity(1, 'inspectTeachingVisualPage|3|16', 'SUCCEEDED'),
      activity(2, 'persistTeachingVisualPage|3|16', 'SUCCEEDED'),
      activity(3, 'organizeTeachingOutline', 'RUNNING'),
      activity(4, 'refineTeachingOutlineCoverage', 'SUCCEEDED'),
      activity(5, 'internalOutlineTelemetry', 'SUCCEEDED'),
    ]

    const steps = recentTeachingPreparationActivitySteps(activities)

    expect(steps.map(step => step.text)).toEqual([
      '图像规则页第 3 / 16 页的规则整理已生成结果，正在保存',
      '图像规则页第 3 / 16 页的规则组已经保存',
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
      activity(3, 'persistTeachingVisualPage|8|16', 'SUCCEEDED'),
      activity(4, 'inspectTeachingVisualRetry|9|16', 'FAILED'),
      activity(5, 'inspectTeachingVisualRetry|10|16', 'REJECTED'),
    ]

    expect(recentTeachingPreparationActivitySteps(activities).map(step => step.text)).toEqual([
      '临时服务异常，正在重试图像规则页第 7 / 16 页的规则整理',
      '图像规则页第 8 / 16 页的规则整理在临时服务异常后已生成结果，正在保存',
      '图像规则页第 8 / 16 页的规则组已经保存',
      '图像规则页第 9 / 16 页的规则整理在临时服务异常后重试仍未完成',
      '图像规则页第 10 / 16 页的规则整理在临时服务异常后重试仍未通过校验',
    ])
    expect(recentTeachingPreparationActivitySteps(activities, 'en').map(step => step.text)).toEqual([
      'A temporary service error occurred; retrying rule grouping for visual rulebook page 7 of 16',
      'Rule grouping retry generated a result after a temporary service error for visual rulebook page 8 of 16; saving it now',
      'Saved the typed rule groups for visual rulebook page 8 of 16',
      'Rule grouping retry for visual rulebook page 9 of 16 still did not complete after a temporary service error',
      'Rule grouping retry for visual rulebook page 10 of 16 did not pass validation after a temporary service error',
    ])
  })

  it('explains validator-owned page repair without presenting it as a transport retry', () => {
    const activities = [
      activity(1, 'inspectTeachingVisualRepair|7|16|MALFORMED_JSON', 'RUNNING'),
      activity(2, 'inspectTeachingVisualRepair|8|16|DUPLICATE_RULE_GROUP', 'SUCCEEDED'),
      activity(3, 'persistTeachingVisualPage|8|16', 'SUCCEEDED'),
      activity(4, 'inspectTeachingVisualRepair|9|16|PAGE_BINDING_MISMATCH', 'FAILED'),
      activity(5, 'inspectTeachingVisualRepair|10|16|SCHEMA_MISMATCH', 'REJECTED'),
    ]

    expect(recentTeachingPreparationActivitySteps(activities).map(step => step.text)).toEqual([
      '返回格式没有通过校验，正在修正图像规则页第 7 / 16 页的规则整理',
      '图像规则页第 8 / 16 页的规则整理修正结果已生成，正在保存',
      '图像规则页第 8 / 16 页的规则组已经保存',
      '图像规则页第 9 / 16 页的规则整理经过一次修正后仍未完成；仅本页暂不可用，其他页面继续保留',
      '图像规则页第 10 / 16 页的规则整理经过一次修正后仍未通过校验；仅本页暂不可用，其他页面继续保留',
    ])
    expect(recentTeachingPreparationActivitySteps(activities, 'en').map(step => step.text)).toEqual([
      'The returned format did not pass validation; correcting the rule grouping for visual rulebook page 7 of 16',
      'Rule grouping correction generated for visual rulebook page 8 of 16; saving it now',
      'Saved the typed rule groups for visual rulebook page 8 of 16',
      'Rule grouping for visual rulebook page 9 of 16 still did not complete after one correction; only this page stays unavailable',
      'Rule grouping for visual rulebook page 10 of 16 still did not pass validation after one correction; only this page stays unavailable',
    ])
  })

  it('shows an unrecognized API activity outcome explicitly instead of treating it as progress or validation failure', () => {
    const activities = [{
      sequence: 1,
      operation: 'inspectTeachingVisualPage|4|10',
      summary: 'provider added a new outcome',
      outcome: 'UNKNOWN' as const,
    }]

    expect(recentTeachingPreparationActivitySteps(activities).map(step => step.text)).toEqual([
      '图像规则页第 4 / 10 页的最新活动状态无法识别，请以整条任务状态为准',
    ])
    expect(recentTeachingPreparationActivitySteps(activities, 'en').map(step => step.text)).toEqual([
      'The latest activity status for visual rulebook page 4 of 10 is unrecognized; use the overall task state',
    ])
    expect(summarizeTeachingVisualPageRuleGroups(activities, 'LESSON_PLANNING')).toEqual([
      expect.objectContaining({
        pageNumber: 4,
        latestOutcome: 'UNKNOWN',
        state: 'no-rule-groups',
      }),
    ])
  })

  it('keeps the complete real preparation history instead of hiding early pages behind a display cap', () => {
    const activities = Array.from({ length: 16 }, (_, index) => index + 1)
      .flatMap(page => [
        activity(page * 2 - 1, `inspectTeachingVisualPage|${page}|16`, 'SUCCEEDED'),
        activity(page * 2, `persistTeachingVisualPage|${page}|16`, 'SUCCEEDED'),
      ])

    const steps = recentTeachingPreparationActivitySteps(activities)

    expect(steps).toHaveLength(32)
    expect(steps[0]?.text).toBe('图像规则页第 1 / 16 页的规则整理已生成结果，正在保存')
    expect(steps.at(-1)?.text).toBe('图像规则页第 16 / 16 页的规则组已经保存')
  })

  it('summarizes each page from its latest typed rule-group activity while preserving recovered attempts', () => {
    const activities = [
      activity(1, 'inspectTeachingVisualPage|1|6', 'SUCCEEDED'),
      activity(7, 'inspectTeachingVisualRepair|2|6|SCHEMA_MISMATCH', 'SUCCEEDED'),
      activity(2, 'inspectTeachingVisualPage|2|6', 'REJECTED'),
      activity(3, 'inspectTeachingVisualPage|3|6', 'FAILED'),
      activity(8, 'inspectTeachingVisualRetry|3|6', 'SUCCEEDED'),
      activity(4, 'inspectTeachingVisualPage|4|6', 'REJECTED'),
      activity(9, 'inspectTeachingVisualRepair|4|6|MALFORMED_JSON', 'RUNNING'),
      activity(5, 'inspectTeachingVisualPage|5|6', 'FAILED'),
      activity(13, 'inspectTeachingVisualPage|5|6|unexpected', 'SUCCEEDED'),
      activity(10, 'inspectTeachingVisualRepair|6|6', 'SUCCEEDED'),
      activity(11, 'inspectTeachingVisualRepair|6|6|PAGE_BINDING_MISMATCH', 'REJECTED'),
      activity(12, 'internalVisualPageNote|5|6', 'SUCCEEDED', 'inspectTeachingVisualRetry|5|6 succeeded'),
      activity(14, 'persistTeachingVisualPage|1|6', 'SUCCEEDED'),
      activity(15, 'persistTeachingVisualPage|2|6', 'SUCCEEDED'),
      activity(16, 'persistTeachingVisualPage|3|6', 'SUCCEEDED'),
    ]

    expect(summarizeTeachingVisualPageRuleGroups(activities)).toEqual([
      {
        pageNumber: 1,
        totalPages: 6,
        latestSequence: 14,
        latestStage: 'persistence',
        latestAttempt: 'direct',
        latestOutcome: 'SUCCEEDED',
        state: 'directly-completed',
      },
      {
        pageNumber: 2,
        totalPages: 6,
        latestSequence: 15,
        latestStage: 'persistence',
        latestAttempt: 'repair',
        latestOutcome: 'SUCCEEDED',
        state: 'completed-after-recovery',
      },
      {
        pageNumber: 3,
        totalPages: 6,
        latestSequence: 16,
        latestStage: 'persistence',
        latestAttempt: 'temporary-retry',
        latestOutcome: 'SUCCEEDED',
        state: 'completed-after-recovery',
      },
      {
        pageNumber: 4,
        totalPages: 6,
        latestSequence: 9,
        latestStage: 'grouping',
        latestAttempt: 'repair',
        latestOutcome: 'RUNNING',
        state: 'processing',
      },
      {
        pageNumber: 5,
        totalPages: 6,
        latestSequence: 5,
        latestStage: 'grouping',
        latestAttempt: 'direct',
        latestOutcome: 'FAILED',
        state: 'no-rule-groups',
      },
      {
        pageNumber: 6,
        totalPages: 6,
        latestSequence: 11,
        latestStage: 'grouping',
        latestAttempt: 'repair',
        latestOutcome: 'REJECTED',
        state: 'no-rule-groups',
      },
    ])
  })

  it('does not call a generated page complete when durable storage failed', () => {
    const generatedOnly = [
      activity(1, 'inspectTeachingVisualPage|4|12', 'SUCCEEDED'),
    ]

    expect(recentTeachingPreparationActivitySteps(generatedOnly).map(step => step.text)).toEqual([
      '图像规则页第 4 / 12 页的规则整理已生成结果，正在保存',
    ])
    expect(summarizeTeachingVisualPageRuleGroups(generatedOnly, 'FAILED')).toEqual([
      expect.objectContaining({
        pageNumber: 4,
        latestStage: 'grouping',
        state: 'no-rule-groups',
      }),
    ])

    const stored = [
      ...generatedOnly,
      activity(2, 'persistTeachingVisualPage|4|12', 'SUCCEEDED'),
    ]
    expect(summarizeTeachingVisualPageRuleGroups(stored, 'COMPLETED')).toEqual([
      expect.objectContaining({
        pageNumber: 4,
        latestStage: 'persistence',
        state: 'directly-completed',
      }),
    ])
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

  it('distinguishes a rejected visual attempt, one complete replacement, and final local unavailability', () => {
    const activities = [
      activity(1, 'settleVisualCandidateSelection|1|1|UNSUPPORTED_SCOPE', 'REJECTED'),
      activity(2, 'settleVisualCandidateSelection|1|2|UNSUPPORTED_SCOPE', 'REJECTED'),
      activity(3, 'enrichTeachingSectionVisual|1', 'REJECTED'),
    ]

    expect(recentTeachingActivitySteps(plan, activities).map(step => step.text)).toEqual([
      '所选候选或依据归属超出了本次提供范围；同一个视觉 Agent 正在进行一次有限的完整重选或重试，这还不是最终配图失败。',
      '经过一次有限的完整重选或重试后，所选候选或依据归属超出了本次提供范围；仅省略这张可选配图，已校验正文仍可阅读。',
      '第 1 章“完成开局设置”经过有限选择后仍没有可用配图；仅省略图片，已校验正文仍可阅读',
    ])
    expect(recentTeachingActivitySteps(plan, activities, 'en').map(step => step.text)).toEqual([
      'The selected candidate or evidence binding was outside the offered scope; the same visual Agent is making one bounded complete replacement or retry. This is not a final visual failure.',
      'The selected candidate or evidence binding was outside the offered scope after the one bounded complete replacement or retry; only this optional visual is omitted and the cited text remains readable.',
      'chapter 1 “完成开局设置”\'s bounded visual selection is unavailable; only the visual is omitted and its cited text remains readable',
    ])
  })

  it('treats typed NO_VISUAL as a valid result rather than a retry or failure', () => {
    const steps = recentTeachingActivitySteps(plan, [
      activity(1, 'settleVisualCandidateSelection|1|1|EXPLICIT_NO_REGION', 'SUCCEEDED'),
      activity(2, 'enrichTeachingSectionVisual|1', 'REJECTED'),
    ])

    expect(steps).toEqual([
      expect.objectContaining({
        outcome: 'SUCCEEDED',
        text: '视觉 Agent 明确选择 NO_VISUAL；这是有效的局部结果，引用正文保持不变',
      }),
      expect.objectContaining({
        outcome: 'REJECTED',
        text: '视觉 Agent 为第 1 章“完成开局设置”选择了 NO_VISUAL；这是有效的局部结果，已校验正文仍可阅读',
      }),
    ])
  })

  it.each([
    ['AGENT_TIMEOUT', '本轮在有限恢复后到达总时限'],
    ['AGENT_MODEL_BUDGET', '本轮用完了预先限定的步骤、工具、模型调用或令牌预算'],
    ['TEACHING_COMPLETION_FAILED', '讲解在有限持久化恢复后仍无法标记完成'],
    ['APPLICATION_RESTARTED', '服务重启后无法安全续跑本轮任务'],
    ['TEACHING_QUEUE_FULL', '服务未能调度下一个有限工作单元'],
    ['TEACHING_WORKFLOW_FAILED', '讲解服务或持久化步骤在有限恢复后仍失败'],
  ])('explains authoritative whole-run stop %s without calling a local visual omission fatal', (errorCode, expected) => {
    const failed = run('run-failed', [])
    failed.run.state = 'FAILED'
    failed.run.lastErrorCode = errorCode

    expect(teachingRunStopReasonText(failed)).toContain(expected)
    expect(teachingRunStopReasonText(failed)).toContain('保留')
  })

  it('explains that a preparation queue timeout happened before model work started', () => {
    const failed = run('run-queue-timeout', [])
    failed.run.state = 'FAILED'
    failed.run.lastErrorCode = 'TEACHING_PREPARATION_QUEUE_TIMEOUT'

    expect(teachingRunStopReasonText(failed)).toContain('限定排队时间')
    expect(teachingRunStopReasonText(failed)).toContain('模型工作尚未开始')
    expect(teachingRunStopReasonText(failed, 'en')).toContain('No model work started')
  })

  it('explains that a direct teaching queue timeout happened before model work started', () => {
    const failed = run('run-teaching-queue-timeout', [])
    failed.run.state = 'FAILED'
    failed.run.lastErrorCode = 'TEACHING_QUEUE_TIMEOUT'

    expect(teachingRunStopReasonText(failed)).toContain('限定排队时间')
    expect(teachingRunStopReasonText(failed)).toContain('模型工作尚未开始')
    expect(teachingRunStopReasonText(failed, 'en')).toContain('No model work started')
  })

  it('explains that continuation admission timeout preserves the first cited section', () => {
    const failed = run('run-continuation-timeout', [])
    failed.run.state = 'FAILED'
    failed.run.lastErrorCode = 'TEACHING_CONTINUATION_QUEUE_TIMEOUT'

    expect(teachingRunStopReasonText(failed)).toContain('第一段带引用讲解仍可阅读')
    expect(teachingRunStopReasonText(failed)).toContain('没有获得并持久接管 worker')
    expect(teachingRunStopReasonText(failed, 'en')).toContain('first cited section remains readable')
  })

  it('explains cancellation separately from service failure', () => {
    const cancelled = run('run-cancelled', [])
    cancelled.run.state = 'FAILED'
    cancelled.run.lastErrorCode = 'AGENT_CANCELLED'

    expect(teachingRunStopReasonText(cancelled)).toBe('本轮由用户取消；已经发布的章节仍然保留。')
    expect(teachingRunStopReasonText(cancelled, 'en')).toContain('player cancelled')
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
