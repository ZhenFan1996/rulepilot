import { describe, expect, it } from 'vitest'

import {
  mergeTeachingRunProgress,
  processedTeachingChapterCount,
  recentTeachingActivitySteps,
  recentTeachingPreparationActivitySteps,
  rejectedTeachingChapterCount,
  summarizeTeachingVisualPageRuleGroups,
  supportedTeachingChapterCount,
  terminalTeachingIssueSteps,
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
      activity(2, 'publishTeachingSection|1', 'SUCCEEDED', 'Teaching section published: SUPPORTED_SECTION_PUBLISHED'),
      activity(3, 'publishTeachingSection|1', 'SUCCEEDED', 'Published chapter'),
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
    expect(teachingRemainingTimeText(plan, snapshot, Date.parse('2026-07-21T00:02:00Z')))
      .not.toContain('完整基础讲解已经可读')
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

  it('uses the latest publication outcome when a newer attempt succeeds', () => {
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
      activity(1, 'settleTeachingVisualPageCandidate|3|16|candidate-1|accepted|NONE', 'SUCCEEDED'),
      activity(2, 'persistTeachingVisualPage|3|16', 'SUCCEEDED'),
      activity(3, 'organizeTeachingOutline', 'RUNNING'),
      activity(4, 'organizeTeachingOutline', 'SUCCEEDED'),
      activity(5, 'internalOutlineTelemetry', 'SUCCEEDED'),
    ]

    const steps = recentTeachingPreparationActivitySteps(activities)

    expect(steps.map(step => step.text)).toEqual([
      '图像规则页第 3 / 16 页的第 1 个完整候选已通过校验，正在保存结构化规则组',
      '图像规则页第 3 / 16 页的规则组已经保存',
      '正在通读规则书，先形成整局认识再规划讲解章节',
      '章节规划候选已返回，正在校验规则依据、章节归属和结构',
    ])
    expect(JSON.stringify(steps)).not.toMatch(/organizeTeachingOutline|internalOutlineTelemetry/)

    const validationActivities = [
      activity(
        8,
        'organizeTeachingOutline|validation|whole|candidate-1',
        'REJECTED',
        'private whole-plan validation error',
        'VALIDATION',
      ),
      activity(
        9,
        'organizeTeachingOutline|validation|local-2|candidate-2',
        'REJECTED',
        'private local-shard validation error',
        'VALIDATION',
      ),
      activity(
        10,
        'organizeTeachingOutline|validation|local-2|no-progress',
        'REJECTED',
        'private local no-progress error',
        'VALIDATION',
      ),
      activity(
        11,
        'organizeTeachingOutline|validation|whole|no-progress',
        'REJECTED',
        'private whole-plan no-progress error',
        'VALIDATION',
      ),
      activity(
        12,
        'organizeTeachingOutline|validation|global|no-progress',
        'REJECTED',
        'private global-plan no-progress error',
        'VALIDATION',
      ),
    ]
    const validationSteps = recentTeachingPreparationActivitySteps(validationActivities)
    const englishValidationSteps = recentTeachingPreparationActivitySteps(validationActivities, 'en')

    expect(validationSteps.map(step => step.text)).toEqual([
      '章节规划候选没有通过校验；完整 JSON、准确错误、输出契约和允许身份已退回同一个 Agent，只要 observation 仍在变化就会继续修正',
      '章节规划候选没有通过校验；完整 JSON、准确错误、输出契约和允许身份已退回同一个 Agent，只要 observation 仍在变化就会继续修正',
      '局部规则分组 Agent 重复了完全相同的无效 observation；该分片已回退为逐条来源单元，兄弟分片和全局规划继续',
      '整份章节规划 Agent 重复了完全相同的完整候选和校验 observation；准备因无进展停止，不会发布不合格规划',
      '全局章节规划 Agent 重复了完全相同的完整候选和校验 observation；准备因无进展停止，不会发布不合格规划',
    ])
    expect(englishValidationSteps.map(step => step.text)).toEqual([
      'The chapter-plan candidate did not pass validation; its complete JSON, exact error, output contract, and allowed identities were returned to the same Agent, which may continue while the observation changes',
      'The chapter-plan candidate did not pass validation; its complete JSON, exact error, output contract, and allowed identities were returned to the same Agent, which may continue while the observation changes',
      'The local rule-group Agent repeated the same rejected observation, so that shard fell back to independent source-owned units; sibling shards and global planning continue',
      'The whole chapter plan Agent repeated the same complete candidate and validation observation, so preparation stopped for no progress; no invalid plan is published',
      'The global chapter plan Agent repeated the same complete candidate and validation observation, so preparation stopped for no progress; no invalid plan is published',
    ])
    expect(JSON.stringify([...validationSteps, ...englishValidationSteps]))
      .not.toMatch(/organizeTeachingOutline|private .* validation error/)
  })

  it('keeps provider failure and no-progress local without inventing a task retry', () => {
    const activities = [
      activity(1, 'settleTeachingVisualPageCandidate|7|16|candidate-1|local-unavailable|PROVIDER_FAILURE', 'FAILED'),
      activity(2, 'settleTeachingVisualPageCandidate|8|16|candidate-3|no-progress|SCHEMA_MISMATCH', 'REJECTED'),
    ]

    expect(recentTeachingPreparationActivitySteps(activities).map(step => step.text)).toEqual([
      '模型服务没有完成图像规则页第 7 / 16 页的第 1 个完整候选；这不是格式校验失败，仅本页暂不可用',
      '图像规则页第 8 / 16 页的第 3 个完整候选与此前已经拒绝的一份完整结果完全相同；为避免重复消耗，本页因无进展停止，其他成功页面继续保留',
    ])
    expect(recentTeachingPreparationActivitySteps(activities, 'en').map(step => step.text)).toEqual([
      'The provider did not complete candidate 1 for visual rulebook page 7 of 16; this is a transport failure, not a JSON correction, and only this page is unavailable',
      'candidate 3 for visual rulebook page 8 of 16 repeated an earlier complete rejected observation; this page stopped for no progress, while successful sibling pages remain available',
    ])
    expect(JSON.stringify(recentTeachingPreparationActivitySteps(activities))).not.toContain('需要重试')
  })

  it('shows every explicit adaptive candidate settlement', () => {
    const activities = [
      activity(1, 'settleTeachingVisualPageCandidate|7|16|candidate-1|correction-follows|MALFORMED_JSON', 'REJECTED'),
      activity(2, 'settleTeachingVisualPageCandidate|8|16|candidate-2|accepted|NONE', 'SUCCEEDED'),
      activity(3, 'persistTeachingVisualPage|8|16', 'SUCCEEDED'),
      activity(4, 'settleTeachingVisualPageCandidate|9|16|candidate-2|local-unavailable|PROVIDER_FAILURE', 'FAILED'),
      activity(5, 'settleTeachingVisualPageCandidate|10|16|candidate-3|no-progress|PAGE_BINDING_MISMATCH', 'REJECTED'),
    ]

    expect(recentTeachingPreparationActivitySteps(activities).map(step => step.text)).toEqual([
      '图像规则页第 7 / 16 页的第 1 个完整候选未通过JSON 语法校验；完整结果、具体错误、格式要求和可用页码已交回同一个 Agent，并要求返回完整替代结果',
      '图像规则页第 8 / 16 页的第 2 个完整候选已通过校验，正在保存结构化规则组',
      '图像规则页第 8 / 16 页的规则组已经保存',
      '模型服务没有完成图像规则页第 9 / 16 页的第 2 个完整候选；这不是格式校验失败，仅本页暂不可用',
      '图像规则页第 10 / 16 页的第 3 个完整候选与此前已经拒绝的一份完整结果完全相同；为避免重复消耗，本页因无进展停止，其他成功页面继续保留',
    ])
    expect(recentTeachingPreparationActivitySteps(activities, 'en').map(step => step.text)).toEqual([
      'candidate 1 for visual rulebook page 7 of 16 did not pass JSON syntax validation; its complete JSON, exact error, original contract, and allowed page IDs returned to the same Agent for a complete replacement',
      'candidate 2 for visual rulebook page 8 of 16 passed validation; saving its typed rule groups now',
      'Saved the typed rule groups for visual rulebook page 8 of 16',
      'The provider did not complete candidate 2 for visual rulebook page 9 of 16; this is a transport failure, not a JSON correction, and only this page is unavailable',
      'candidate 3 for visual rulebook page 10 of 16 repeated an earlier complete rejected observation; this page stopped for no progress, while successful sibling pages remain available',
    ])
  })

  it('explains validator-owned adaptive correction without presenting transport failure as JSON repair', () => {
    const activities = [
      activity(1, 'settleTeachingVisualPageCandidate|7|16|candidate-1|correction-follows|MALFORMED_JSON', 'REJECTED'),
      activity(2, 'settleTeachingVisualPageCandidate|8|16|candidate-2|accepted|NONE', 'SUCCEEDED'),
      activity(3, 'persistTeachingVisualPage|8|16', 'SUCCEEDED'),
      activity(4, 'settleTeachingVisualPageCandidate|9|16|candidate-2|local-unavailable|PROVIDER_FAILURE', 'FAILED'),
      activity(5, 'settleTeachingVisualPageCandidate|10|16|candidate-3|no-progress|SCHEMA_MISMATCH', 'REJECTED'),
    ]

    expect(recentTeachingPreparationActivitySteps(activities).map(step => step.text)).toEqual([
      expect.stringContaining('完整结果、具体错误、格式要求和可用页码已交回同一个 Agent'),
      '图像规则页第 8 / 16 页的第 2 个完整候选已通过校验，正在保存结构化规则组',
      '图像规则页第 8 / 16 页的规则组已经保存',
      expect.stringContaining('这不是格式校验失败'),
      expect.stringContaining('与此前已经拒绝的一份完整结果完全相同'),
    ])
    expect(recentTeachingPreparationActivitySteps(activities, 'en').map(step => step.text)).toEqual([
      expect.stringContaining('complete JSON, exact error, original contract, and allowed page IDs'),
      'candidate 2 for visual rulebook page 8 of 16 passed validation; saving its typed rule groups now',
      'Saved the typed rule groups for visual rulebook page 8 of 16',
      expect.stringContaining('transport failure, not a JSON correction'),
      expect.stringContaining('repeated an earlier complete rejected observation'),
    ])
  })

  it('shows an unrecognized API activity outcome explicitly instead of treating it as progress or validation failure', () => {
    const activities = [{
      sequence: 1,
      operation: 'settleTeachingVisualPageCandidate|4|10|candidate-1|accepted|NONE',
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
        state: 'local-unavailable',
      }),
    ])
  })

  it('keeps the complete real preparation history instead of hiding early pages behind a display cap', () => {
    const activities = Array.from({ length: 16 }, (_, index) => index + 1)
      .flatMap(page => [
        activity(page * 2 - 1, `settleTeachingVisualPageCandidate|${page}|16|candidate-1|accepted|NONE`, 'SUCCEEDED'),
        activity(page * 2, `persistTeachingVisualPage|${page}|16`, 'SUCCEEDED'),
      ])

    const steps = recentTeachingPreparationActivitySteps(activities)

    expect(steps).toHaveLength(32)
    expect(steps[0]?.text).toBe('图像规则页第 1 / 16 页的第 1 个完整候选已通过校验，正在保存结构化规则组')
    expect(steps.at(-1)?.text).toBe('图像规则页第 16 / 16 页的规则组已经保存')
  })

  it('summarizes each page from its latest typed rule-group activity while preserving recovered attempts', () => {
    const activities = [
      activity(1, 'settleTeachingVisualPageCandidate|1|6|candidate-1|accepted|NONE', 'SUCCEEDED'),
      activity(7, 'settleTeachingVisualPageCandidate|2|6|candidate-2|accepted|NONE', 'SUCCEEDED'),
      activity(2, 'settleTeachingVisualPageCandidate|2|6|candidate-1|correction-follows|SCHEMA_MISMATCH', 'REJECTED'),
      activity(3, 'settleTeachingVisualPageCandidate|3|6|candidate-1|correction-follows|MALFORMED_JSON', 'REJECTED'),
      activity(8, 'settleTeachingVisualPageCandidate|3|6|candidate-2|accepted|NONE', 'SUCCEEDED'),
      activity(9, 'settleTeachingVisualPageCandidate|4|6|candidate-1|correction-follows|MALFORMED_JSON', 'REJECTED'),
      activity(5, 'settleTeachingVisualPageCandidate|5|6|candidate-1|local-unavailable|PROVIDER_FAILURE', 'FAILED'),
      activity(13, 'inspectTeachingVisualPage|5|6|unexpected', 'SUCCEEDED'),
      activity(11, 'settleTeachingVisualPageCandidate|6|6|candidate-3|no-progress|PAGE_BINDING_MISMATCH', 'REJECTED'),
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
        latestAttempt: 'correction',
        latestOutcome: 'SUCCEEDED',
        state: 'completed-after-correction',
      },
      {
        pageNumber: 3,
        totalPages: 6,
        latestSequence: 16,
        latestStage: 'persistence',
        latestAttempt: 'correction',
        latestOutcome: 'SUCCEEDED',
        state: 'completed-after-correction',
      },
      {
        pageNumber: 4,
        totalPages: 6,
        latestSequence: 9,
        latestStage: 'grouping',
        latestAttempt: 'direct',
        latestOutcome: 'REJECTED',
        state: 'processing',
      },
      {
        pageNumber: 5,
        totalPages: 6,
        latestSequence: 5,
        latestStage: 'grouping',
        latestAttempt: 'direct',
        latestOutcome: 'FAILED',
        state: 'local-unavailable',
      },
      {
        pageNumber: 6,
        totalPages: 6,
        latestSequence: 11,
        latestStage: 'grouping',
        latestAttempt: 'correction',
        latestOutcome: 'REJECTED',
        state: 'local-unavailable',
      },
    ])
  })

  it('does not call a generated page complete when durable storage failed', () => {
    const generatedOnly = [
      activity(1, 'settleTeachingVisualPageCandidate|4|12|candidate-1|accepted|NONE', 'SUCCEEDED'),
    ]

    expect(recentTeachingPreparationActivitySteps(generatedOnly).map(step => step.text)).toEqual([
      '图像规则页第 4 / 12 页的第 1 个完整候选已通过校验，正在保存结构化规则组',
    ])
    expect(summarizeTeachingVisualPageRuleGroups(generatedOnly, 'FAILED')).toEqual([
      expect.objectContaining({
        pageNumber: 4,
        latestStage: 'grouping',
        state: 'local-unavailable',
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
      activity(3, 'continueTeachingSectionAfterRejection|2|1', 'RUNNING'),
    ]

    expect(recentTeachingActivitySteps(plan, activities).map(step => step.text)).toEqual([
      '正在依据规则书编写第 2 章“走完第一轮”',
      '第 2 章“走完第一轮”的候选未通过校验；完整候选和准确原因会返回同一 Agent',
      '同一章节 Agent 正在依据完整候选和准确校验原因，重新生成第 2 章“走完第一轮”',
    ])

    const noProgress = activity(4, 'settleTeachingSectionNoProgress|2|2', 'REJECTED')
    expect(teachingActivityText(plan, [...activities, noProgress], noProgress)).toBe(
      '同一章节 Agent 连续返回完全相同的无效候选，第 2 章“走完第一轮”已局部停止；其他已发布章节仍然保留',
    )
  })

  it('distinguishes adaptive visual correction, no progress, and final local unavailability', () => {
    const activities = [
      activity(1, 'settleVisualCandidateSelection|1|1|UNSUPPORTED_SCOPE|correction-follows', 'REJECTED'),
      activity(2, 'settleVisualCandidateSelection|1|2|MALFORMED_JSON|correction-follows', 'REJECTED'),
      activity(3, 'settleVisualCandidateSelection|1|3|UNSUPPORTED_SCOPE|no-progress', 'REJECTED'),
      activity(4, 'enrichTeachingSectionVisual|1', 'REJECTED'),
    ]

    expect(recentTeachingActivitySteps(plan, activities).map(step => step.text)).toEqual([
      '所选候选或依据归属超出了本次提供范围；完整候选、准确错误、JSON 合同和允许身份已返回同一个视觉 Agent。只要 observation 仍在变化且资源尚未耗尽，它可以继续返回新的完整候选。',
      '返回的候选选择结构没有通过校验；完整候选、准确错误、JSON 合同和允许身份已返回同一个视觉 Agent。只要 observation 仍在变化且资源尚未耗尽，它可以继续返回新的完整候选。',
      '所选候选或依据归属超出了本次提供范围；视觉 Agent 重复了相同完整候选和准确错误，这个批次因无进展停止。仅省略这张可选配图，已校验正文仍可阅读。',
      '第 1 章“完成开局设置”经过有限选择后仍没有可用配图；仅省略图片，已校验正文仍可阅读',
    ])
    expect(recentTeachingActivitySteps(plan, activities, 'en').map(step => step.text)).toEqual([
      'The selected candidate or evidence binding was outside the offered scope; the complete candidate, exact error, JSON contract, and allowed identities returned to the same visual Agent. It may produce another complete candidate while the observation changes and resources remain.',
      'The returned selection structure did not pass validation; the complete candidate, exact error, JSON contract, and allowed identities returned to the same visual Agent. It may produce another complete candidate while the observation changes and resources remain.',
      'The selected candidate or evidence binding was outside the offered scope; the visual Agent repeated the same complete candidate and exact error, so this batch stopped for no progress. Only this optional visual is omitted and the cited text remains readable.',
      'chapter 1 “完成开局设置”\'s visual is unavailable; only the visual is omitted and its cited text remains readable',
    ])
  })

  it('treats typed NO_VISUAL as a valid result rather than a retry or failure', () => {
    const steps = recentTeachingActivitySteps(plan, [
      activity(1, 'settleVisualCandidateSelection|1|1|EXPLICIT_NO_REGION|no-visual', 'SUCCEEDED'),
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
    ['AGENT_TIMEOUT', '本轮到达后端记录的截止时间'],
    ['TEACHING_COMPLETION_FAILED', '讲解无法标记为完成'],
    ['APPLICATION_RESTARTED', '服务重启后无法安全续跑本轮任务'],
    ['TEACHING_QUEUE_FULL', '服务未能调度下一个工作单元'],
    ['TEACHING_WORKFLOW_FAILED', '讲解服务或持久化步骤失败'],
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

    expect(teachingRunStopReasonText(failed)).toContain('后端队列截止')
    expect(teachingRunStopReasonText(failed)).toContain('模型工作尚未开始')
    expect(teachingRunStopReasonText(failed, 'en')).toContain('No model work started')
  })

  it('explains that a direct teaching queue timeout happened before model work started', () => {
    const failed = run('run-teaching-queue-timeout', [])
    failed.run.state = 'FAILED'
    failed.run.lastErrorCode = 'TEACHING_QUEUE_TIMEOUT'

    expect(teachingRunStopReasonText(failed)).toContain('后端队列截止')
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

  it('shows only unresolved terminal chapter and visual issues, not recovered attempts', () => {
    const activities = [
      activity(1, 'publishTeachingSection|1', 'REJECTED', 'Teaching section withheld: BASE_DRAFT_WITHHELD'),
      activity(2, 'publishTeachingSection|1', 'SUCCEEDED', 'Teaching section published: CITED_BASE_SECTION_PUBLISHED'),
      activity(3, 'settleVisualCandidateSelection|2|1|UNSUPPORTED_SCOPE|no-progress', 'REJECTED'),
      activity(4, 'enrichTeachingSectionVisual|2', 'REJECTED'),
    ]

    expect(terminalTeachingIssueSteps(plan, activities)).toEqual([
      expect.objectContaining({
        sequence: 4,
        text: '第 2 章“走完第一轮”经过有限选择后仍没有可用配图；仅省略图片，已校验正文仍可阅读',
      }),
    ])
  })

  it('names the exact chapter stage when a terminal activity failed before publication', () => {
    const failedComposition = activity(8, 'composeTeachingSection|2', 'FAILED')

    expect(teachingActivityText(plan, [failedComposition], failedComposition))
      .toBe('第 2 章“走完第一轮”在正文发布前停止；先前已发布章节继续保留')
    expect(teachingActivityText(plan, [failedComposition], failedComposition, 'en'))
      .toBe('Writing chapter 2 “走完第一轮” stopped before publication; earlier published chapters remain available')
  })

})

function run(id: string, activities: TeachingActivity[]): TeachingRunProgress {
  return {
    run: {
      id, subjectId: 'plan-1', state: 'RETRIEVING', createdAt: '2026-07-21T00:00:00Z', updatedAt: '2026-07-21T00:01:00Z',
      completedAt: null, lastErrorCode: null,
    },
    budget: { usedModelCalls: 1 },
    activities,
  }
}

function activity(
  sequence: number,
  operation: string,
  outcome: TeachingActivity['outcome'],
  summary = 'internal summary',
  type: TeachingActivity['type'] = 'MODEL',
): TeachingActivity {
  return {
    sequence, type, operation, summary, outcome, latencyMs: 0,
    occurredAt: '2026-07-21T00:01:00Z',
  }
}
