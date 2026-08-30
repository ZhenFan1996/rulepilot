import { describe, expect, it } from 'vitest'

import { playerJourneyFailurePresentation, typedFailurePolicy } from './playerJourney'

describe('typed player journey failure policy', () => {
  it.each([
    ['CHAPTER_LOCALLY_UNAVAILABLE', 'local-degradation', null],
    ['TEACHING_QUEUE_TIMEOUT', 'retry-preserved', 'retry-step'],
    ['TEACHING_MODEL_PROVIDER_FAILED', 'retry-preserved', 'restart-from-completed'],
    ['TEACHING_PERSISTENCE_FAILED', 'repair-required', 'manual-repair'],
    ['SOURCE_UNAVAILABLE', 'repair-required', 'manual-repair'],
    ['TEACHING_PLAN_INVALID', 'internal-correction', null],
  ] as const)('maps %s to %s', (code, category, recovery) => {
    const policy = typedFailurePolicy(code, 'GENERATE_LESSON', false)
    expect(policy.failureClassification).toBe(category)
    expect(policy.failureRecovery).toBe(recovery)
  })

  it('does not let server-authorized retry override a persistence repair boundary', () => {
    const policy = typedFailurePolicy('TEACHING_PREPARATION_STORAGE_FAILED', 'PREPARE_TEACHING', true)
    expect(policy).toMatchObject({
      retryAction: null,
      failureClassification: 'repair-required',
      failureRecovery: 'manual-repair',
    })
  })

  it.each([
    ['local-degradation', '只影响对应页面、章节或配图'],
    ['retry-preserved', '没有丢弃已确认内容'],
    ['repair-required', '需要先修复后再继续'],
    ['internal-correction', '不是玩家输入被拒绝'],
  ] as const)('explains %s in player language', (failureClassification, expected) => {
    const presentation = playerJourneyFailurePresentation({ failureClassification, failureRecovery: null })
    expect(`${presentation.title} ${presentation.detail}`).toContain(expected)
  })
})
