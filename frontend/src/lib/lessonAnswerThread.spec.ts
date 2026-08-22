import { beforeEach, describe, expect, it } from 'vitest'

import type { LearningIntent } from '@/composables/useLessonAnswers'

import {
  forgetLessonAnswerThread,
  readLessonAnswerThread,
  rememberLessonAnswerThread,
  type LessonAnswerThreadScope,
} from './lessonAnswerThread'

const alice: LessonAnswerThreadScope = {
  username: 'Alice', planId: 'plan-1', documentVersionId: 'version-1', locale: 'zh-CN',
}

describe('lessonAnswerThread', () => {
  beforeEach(() => sessionStorage.clear())

  it('restores only the matching account, plan, version, and language', () => {
    rememberLessonAnswerThread(sessionStorage, alice, [turn('什么时候结算？')])

    expect(readLessonAnswerThread(sessionStorage, alice)).toHaveLength(1)
    expect(readLessonAnswerThread(sessionStorage, { ...alice, username: 'Bob' })).toEqual([])
    expect(readLessonAnswerThread(sessionStorage, { ...alice, planId: 'plan-2' })).toEqual([])
    expect(readLessonAnswerThread(sessionStorage, { ...alice, documentVersionId: 'version-2' })).toEqual([])
    expect(readLessonAnswerThread(sessionStorage, { ...alice, locale: 'en' })).toEqual([])
  })

  it('preserves a grounded terminology follow-up in the bounded browser thread', () => {
    const base = turn('解释里程碑', 'DEFINE')
    const definitionTurn = { ...base, answer: { ...base.answer, termDefinitions: [{
      term: '里程碑', definition: '满足规则书条件时获得的目标。', boundary: '普通得分不是里程碑。',
    }] } }
    rememberLessonAnswerThread(sessionStorage, alice, [definitionTurn])

    expect(readLessonAnswerThread(sessionStorage, alice)).toEqual([
      expect.objectContaining({
        question: '解释里程碑', learningIntent: 'DEFINE',
        answer: expect.objectContaining({ termDefinitions: definitionTurn.answer.termDefinitions }),
      }),
    ])
  })

  it('preserves a direct-source follow-up in the bounded browser thread', () => {
    const sourceTurn = turn('给我看这条结论最直接的原文依据', 'SOURCE')
    rememberLessonAnswerThread(sessionStorage, alice, [sourceTurn])

    expect(readLessonAnswerThread(sessionStorage, alice)).toEqual([
      expect.objectContaining({
        question: '给我看这条结论最直接的原文依据',
        learningIntent: 'SOURCE',
      }),
    ])
  })

  it('keeps the complete valid thread and rejects malformed browser data', () => {
    rememberLessonAnswerThread(
      sessionStorage,
      alice,
      Array.from({ length: 15 }, (_, index) => turn(`问题 ${index + 1}`)),
    )

    expect(readLessonAnswerThread(sessionStorage, alice).map(item => item.question))
      .toEqual(Array.from({ length: 15 }, (_, index) => `问题 ${index + 1}`))

    const key = sessionStorage.key(0)!
    sessionStorage.setItem(key, JSON.stringify([{ question: '<script>', answer: { status: 'ANSWERED' } }]))
    expect(readLessonAnswerThread(sessionStorage, alice)).toEqual([])
  })

  it('projects browser turns onto the player contract before storing or restoring them', () => {
    const internalId = '11111111-1111-4111-8111-111111111111'
    const base = turn('When does the cobalt spindle resolve?')
    const unsafeEnvelope = {
      ...base,
      answer: {
        ...base.answer,
        documentVersionId: internalId,
        citations: [{
          heading: 'Timing', excerpt: 'Resolve after the gate closes.', pageFrom: 3, pageTo: 3,
          chunkId: internalId, sectionType: 'TIMING',
        }],
      },
    }

    rememberLessonAnswerThread(sessionStorage, alice, [unsafeEnvelope])

    const stored = sessionStorage.getItem(sessionStorage.key(0)!) ?? ''
    const restored = readLessonAnswerThread(sessionStorage, alice)
    expect(stored).not.toContain('documentVersionId')
    expect(stored).not.toContain('chunkId')
    expect(stored).not.toContain('sectionType')
    expect(stored).not.toContain(internalId)
    expect(restored[0]?.answer.citations).toEqual([{
      heading: 'Timing', excerpt: 'Resolve after the gate closes.', pageFrom: 3, pageTo: 3,
    }])
  })

  it('round-trips bounded verified calculation displays', () => {
    const scope = { ...alice, documentVersionId: 'version-calc' }
    const base = turn('我有8个资源，能得多少分？')
    const calculated = {
      ...base,
      answer: {
        ...base.answer,
        answerBasis: 'GROUNDED_APPLICATION' as const,
        calculations: [{ expression: 'floor(8 / 3) * 5', result: '10' }],
      },
    }

    rememberLessonAnswerThread(sessionStorage, scope, [calculated])

    expect(readLessonAnswerThread(sessionStorage, scope)[0]?.answer.calculations)
      .toEqual([{ expression: 'floor(8 / 3) * 5', result: '10' }])
  })

  it('round-trips validated situation facts without accepting an invented missing fact', () => {
    const scope = { ...alice, documentVersionId: 'version-situation' }
    const base = turn('我已完成前置条件，现在可以结算吗？')
    const checked = {
      ...base,
      answer: {
        ...base.answer,
        answerBasis: 'GROUNDED_APPLICATION' as const,
        situationChecks: [
          { requirement: '前置条件必须完成', status: 'CONFIRMED' as const, playerFact: '我已完成前置条件' },
          { requirement: '行动仍在可结算窗口', status: 'NOT_PROVIDED' as const, playerFact: '' },
        ],
      },
    }

    rememberLessonAnswerThread(sessionStorage, scope, [checked])
    expect(readLessonAnswerThread(sessionStorage, scope)[0]?.answer.situationChecks).toEqual(checked.answer.situationChecks)

    const key = sessionStorage.key(0)!
    sessionStorage.setItem(key, JSON.stringify([{
      ...checked,
      answer: { ...checked.answer, situationChecks: [{ requirement: '窗口开放', status: 'NOT_PROVIDED', playerFact: '我猜它开放' }] },
    }]))
    expect(readLessonAnswerThread(sessionStorage, scope)).toEqual([])
  })

  it('round-trips bounded walkthrough steps with explicit ordering provenance', () => {
    const scope = { ...alice, documentVersionId: 'version-walkthrough' }
    const base = turn('冲突结算的具体步骤是什么？')
    const walked = {
      ...base,
      answer: {
        ...base.answer,
        walkthroughSteps: [
          { instruction: '先比较数值。', explanation: '规则明确先比较双方数值。', orderBasis: 'RULE_ORDER' as const },
          { instruction: '再应用结果。', explanation: '随后执行比较产生的结果。', orderBasis: 'RULE_ORDER' as const },
        ],
      },
    }

    rememberLessonAnswerThread(sessionStorage, scope, [walked])

    expect(readLessonAnswerThread(sessionStorage, scope)[0]?.answer.walkthroughSteps)
      .toEqual(walked.answer.walkthroughSteps)
  })

  it('round-trips cited decision branches with explicit source provenance', () => {
    const scope = { ...alice, documentVersionId: 'version-decision-table' }
    const base = turn('不同情况下分别会怎样？')
    const branched = {
      ...base,
      answer: {
        ...base.answer,
        decisionBranches: [
          { condition: '供应区有对应物品。', outcome: '拿取该物品。', basis: 'EXPLICIT_RULE' as const },
          { condition: '规则书示例中的蓝色玩家。', outcome: '等待下一步。', basis: 'RULEBOOK_EXAMPLE' as const },
        ],
      },
    }

    rememberLessonAnswerThread(sessionStorage, scope, [branched])

    expect(readLessonAnswerThread(sessionStorage, scope)[0]?.answer.decisionBranches)
      .toEqual(branched.answer.decisionBranches)
  })

  it('round-trips bounded exception conditions and effects', () => {
    const scope = { ...alice, documentVersionId: 'version-exceptions' }
    const base = turn('有哪些例外和限制？')
    const qualified = {
      ...base,
      answer: {
        ...base.answer,
        exceptionClauses: [
          { condition: '供应区为空。', effect: '不能取得对应物品。' },
          { condition: '已有同名效果。', effect: '不能再创建一个。' },
        ],
      },
    }

    rememberLessonAnswerThread(sessionStorage, scope, [qualified])

    expect(readLessonAnswerThread(sessionStorage, scope)[0]?.answer.exceptionClauses)
      .toEqual(qualified.answer.exceptionClauses)
  })

  it('round-trips bounded worked examples with explicit provenance', () => {
    const scope = { ...alice, documentVersionId: 'version-worked-example' }
    const base = turn('给我走一个负修正的例子。')
    const illustrated = {
      ...base,
      learningIntent: 'EXAMPLE' as const,
      answer: {
        ...base.answer,
        workedExamples: [{
          setup: '基础值是 1，并受到 -4 修正。',
          action: '应用这个修正。',
          outcome: '结果是 -3。',
          basis: 'RULEBOOK_EXAMPLE' as const,
        }],
      },
    }

    rememberLessonAnswerThread(sessionStorage, scope, [illustrated])

    expect(readLessonAnswerThread(sessionStorage, scope)[0]?.answer.workedExamples)
      .toEqual(illustrated.answer.workedExamples)
  })

  it('round-trips cited rule priority resolutions', () => {
    const scope = { ...alice, documentVersionId: 'version-rule-priority' }
    const base = turn('允许和禁止同时出现时哪边优先？')
    const resolved = {
      ...base,
      answer: {
        ...base.answer,
        priorityResolutions: [{
          baseRule: '一个效果允许执行动作。',
          competingRule: '另一个效果禁止执行动作。',
          resolution: '禁止效果优先，因此不能执行。',
          basis: 'IMPOSSIBILITY_PRIORITY' as const,
        }],
      },
    }

    rememberLessonAnswerThread(sessionStorage, scope, [resolved])

    expect(readLessonAnswerThread(sessionStorage, scope)[0]?.answer.priorityResolutions)
      .toEqual(resolved.answer.priorityResolutions)
  })

  it('round-trips cited simultaneous timing resolutions', () => {
    const scope = { ...alice, documentVersionId: 'version-rule-timing' }
    const base = turn('两个效果同时发生时谁决定顺序？')
    const resolved = {
      ...base,
      answer: {
        ...base.answer,
        timingResolutions: [{
          timingContext: '两个效果在同一时点发生。',
          resolutionOrder: '由当前玩家选择结算顺序。',
          orderSource: '正在进行回合的玩家。',
          basis: 'CURRENT_PLAYER_CHOOSES' as const,
        }],
      },
    }

    rememberLessonAnswerThread(sessionStorage, scope, [resolved])

    expect(readLessonAnswerThread(sessionStorage, scope)[0]?.answer.timingResolutions)
      .toEqual(resolved.answer.timingResolutions)
  })

  it('round-trips cited tie-resolution ladders', () => {
    const scope = { ...alice, documentVersionId: 'version-rule-ties' }
    const base = turn('平局怎么判？')
    const resolved = {
      ...base,
      answer: {
        ...base.answer,
        tieResolutions: [{
          tieContext: '玩家总分相同。',
          resolutionSteps: ['先比较地点牌数量。', '仍平则比较船只与强盗分。'],
          finalOutcome: '仍平时，最接近起始玩家者获胜。',
          basis: 'POSITIONAL_PRIORITY' as const,
        }],
      },
    }

    rememberLessonAnswerThread(sessionStorage, scope, [resolved])

    expect(readLessonAnswerThread(sessionStorage, scope)[0]?.answer.tieResolutions)
      .toEqual(resolved.answer.tieResolutions)
  })

  it('round-trips cited rule-applicability rulings', () => {
    const scope = { ...alice, documentVersionId: 'version-rule-scope' }
    const base = turn('两人局能用这组卡吗？')
    const resolved = {
      ...base,
      answer: {
        ...base.answer,
        scopeResolutions: [{
          ruleContext: '两人局组件限制。',
          governingCondition: '两名玩家时不使用统治卡。',
          currentSituation: '当前是两人局。',
          matchStatus: 'MATCHES_SCOPE' as const,
          effect: '不使用统治卡。',
          basis: 'PLAYER_COUNT' as const,
        }],
      },
    }

    rememberLessonAnswerThread(sessionStorage, scope, [resolved])

    expect(readLessonAnswerThread(sessionStorage, scope)[0]?.answer.scopeResolutions)
      .toEqual(resolved.answer.scopeResolutions)
  })

  it('round-trips cited rule concept comparisons', () => {
    const scope = { ...alice, documentVersionId: 'version-concept-comparison' }
    const base = turn('白天规则和夜晚规则冲突吗？')
    const resolved = {
      ...base,
      answer: {
        ...base.answer,
        conceptComparisons: [{
          leftConcept: '白天进入规则', leftDefinition: '只在白天适用。',
          rightConcept: '夜晚进入规则', rightDefinition: '只在夜晚适用。',
          commonGround: '两者都约束进入动作。',
          keyDifference: '适用时段不同，因此并不互相覆盖。',
          practicalBoundary: '白天使用前者，夜晚使用后者。',
          basis: 'RULE_SCOPE' as const,
        }],
      },
    }

    rememberLessonAnswerThread(sessionStorage, scope, [resolved])

    expect(readLessonAnswerThread(sessionStorage, scope)[0]?.answer.conceptComparisons)
      .toEqual(resolved.answer.conceptComparisons)
  })

  it('round-trips complete cited rule option lists', () => {
    const scope = { ...alice, documentVersionId: 'version-rule-options' }
    const base = turn('可以从哪里招募？')
    const resolved = {
      ...base,
      answer: {
        ...base.answer,
        ruleOptions: [
          { decisionContext: '招募', selectionRule: '必须三选一', optionName: 'Park', availabilityCondition: 'Park 有牌', result: '拿牌并补牌', basis: 'SOURCE_SELECTION' as const },
          { decisionContext: '招募', selectionRule: '必须三选一', optionName: 'Yard', availabilityCondition: 'Yard 有牌', result: '拿牌且不补牌', basis: 'SOURCE_SELECTION' as const },
          { decisionContext: '招募', selectionRule: '必须三选一', optionName: 'Park deck', availabilityCondition: '牌库可抽', result: '抽取顶牌', basis: 'SOURCE_SELECTION' as const },
        ],
      },
    }

    rememberLessonAnswerThread(sessionStorage, scope, [resolved])

    expect(readLessonAnswerThread(sessionStorage, scope)[0]?.answer.ruleOptions)
      .toEqual(resolved.answer.ruleOptions)
  })

  it('forgets only the selected thread', () => {
    const bob = { ...alice, username: 'Bob' }
    rememberLessonAnswerThread(sessionStorage, alice, [turn('Alice 的问题')])
    rememberLessonAnswerThread(sessionStorage, bob, [turn('Bob 的问题')])

    forgetLessonAnswerThread(sessionStorage, alice)

    expect(readLessonAnswerThread(sessionStorage, alice)).toEqual([])
    expect(readLessonAnswerThread(sessionStorage, bob)).toHaveLength(1)
  })
})

function turn(question: string, learningIntent: LearningIntent | null = null) {
  return {
    question,
    learningIntent,
    answer: {
      language: 'zh-CN' as const,
      status: 'ANSWERED' as const,
      shortVerdict: '先完成结算。',
      explanation: '规则书这样规定。',
      citations: [{ heading: '结算顺序', excerpt: '先完成结算。', pageFrom: 2, pageTo: 2 }],
      exceptions: [],
      confidence: 'HIGH' as const,
      answerBasis: 'DIRECT_RULE' as const,
      source: 'UPLOADED' as const,
      clarification: null,
      recovery: null,
      warnings: [],
    },
  }
}
