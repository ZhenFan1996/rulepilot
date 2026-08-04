import { describe, expect, it } from 'vitest'

import { answerAgentTrace, type AnswerAgentActivity } from './answerAgentTrace'

describe('answer Agent trace', () => {
  it('shows safe tool choices and verification without exposing raw operation details', () => {
    const activities: AnswerAgentActivity[] = [
      activity(1, 'MODEL', 'nativeModelTurn|1'),
      activity(2, 'TOOL', 'nativeTool|search_rule_evidence|private-schema-hash'),
      activity(3, 'TOOL', 'nativeTool|search_rule_relationships|private-schema-hash'),
      activity(4, 'TOOL', 'nativeTool|read_rule_pages|private-schema-hash'),
      activity(5, 'TOOL', 'nativeTool|read_visual_page_facts|private-schema-hash'),
      activity(6, 'TOOL', 'calculateRuleMath'),
      activity(7, 'TOOL', 'checkRuleSituation'),
      activity(8, 'TOOL', 'buildRuleWalkthrough'),
      activity(9, 'TOOL', 'buildRuleDecisionTable'),
      activity(10, 'VALIDATION', 'nativeObs|read_rule_pages|private-schema-hash|private-call'),
    ]

    const trace = answerAgentTrace(activities)

    expect(trace.map(item => item.label)).toEqual([
      '选择“查找例外与覆盖条款”',
      '选择“读取原文页”',
      '选择“查看页面图示信息”',
      '选择“核算规则结果”',
      '选择“核对当前局面条件”',
      '选择“整理引用分步讲解”',
      '选择“整理条件分支”',
      '核对工具结果与引用边界',
    ])
    expect(JSON.stringify(trace)).not.toContain('private-schema-hash')
  })

  it('shows citation-context expansion without exposing evidence handles', () => {
    const trace = answerAgentTrace([
      activity(1, 'TOOL', 'nativeTool|expand_rule_evidence_context|private-schema-hash'),
    ])

    expect(trace).toEqual([
      { sequence: 1, kind: 'tool', label: '选择“展开引用前后文”', status: 'done' },
    ])
    expect(JSON.stringify(trace)).not.toContain('private-schema-hash')
  })

  it('maps rejected work to a stopped state instead of presenting success', () => {
    expect(answerAgentTrace([activity(1, 'VALIDATION', 'nativeCompletionRequirement', 'REJECTED')]))
      .toEqual([{ sequence: 1, kind: 'verification', label: '证据工具尚未调用，继续查找依据', status: 'stopped' }])
  })

  it('shows structured exception validation as a player-readable tool choice', () => {
    expect(answerAgentTrace([activity(1, 'TOOL', 'buildRuleExceptionList')]))
      .toEqual([{ sequence: 1, kind: 'tool', label: '选择“逐条核对例外和限制”', status: 'done' }])
  })

  it('shows structured term definition validation and repair in player language', () => {
    expect(answerAgentTrace([activity(1, 'TOOL', 'defineRuleTerms')]))
      .toEqual([{ sequence: 1, kind: 'tool', label: '选择“核对规则书术语定义”', status: 'done' }])
    expect(answerAgentTrace([activity(2, 'MODEL', 'repairRuleTermDefinitions')]))
      .toEqual([{ sequence: 2, kind: 'verification', label: '修正术语定义后重新核对', status: 'done' }])
  })

  it('shows cited example validation and repair in player language', () => {
    expect(answerAgentTrace([activity(1, 'TOOL', 'illustrateRule')]))
      .toEqual([{ sequence: 1, kind: 'tool', label: '选择“整理带出处的规则示例”', status: 'done' }])
    expect(answerAgentTrace([activity(2, 'MODEL', 'repairRuleWorkedExamples')]))
      .toEqual([{ sequence: 2, kind: 'verification', label: '修正示例后重新核对规则出处', status: 'done' }])
  })

  it('shows rule priority validation and repair in player language', () => {
    expect(answerAgentTrace([activity(1, 'TOOL', 'resolveRulePriority')]))
      .toEqual([{ sequence: 1, kind: 'tool', label: '选择“核对规则优先关系”', status: 'done' }])
    expect(answerAgentTrace([activity(2, 'MODEL', 'repairRulePriorityResolutions')]))
      .toEqual([{ sequence: 2, kind: 'verification', label: '修正规则优先关系后重新核对', status: 'done' }])
  })

  it('shows dependency, conflict, source, and permission checks as distinct tool choices', () => {
    expect(answerAgentTrace([
      activity(1, 'TOOL', 'traceRuleDependencies'),
      activity(2, 'TOOL', 'checkRuleConflicts'),
      activity(3, 'TOOL', 'showRuleEvidence'),
      activity(4, 'TOOL', 'checkRulePermission'),
    ])).toEqual([
      { sequence: 1, kind: 'tool', label: '选择“梳理规则前后关系”', status: 'done' },
      { sequence: 2, kind: 'tool', label: '选择“检查两条规则是否真冲突”', status: 'done' },
      { sequence: 3, kind: 'tool', label: '选择“展示最直接的原文依据”', status: 'done' },
      { sequence: 4, kind: 'tool', label: '选择“核对规则是否允许”', status: 'done' },
    ])
  })

  it('shows simultaneous timing validation and repair in player language', () => {
    expect(answerAgentTrace([activity(1, 'TOOL', 'resolveRuleTiming')]))
      .toEqual([{ sequence: 1, kind: 'tool', label: '选择“核对同时触发顺序”', status: 'done' }])
    expect(answerAgentTrace([activity(2, 'MODEL', 'repairRuleTimingResolutions')]))
      .toEqual([{ sequence: 2, kind: 'verification', label: '修正时序关系后重新核对', status: 'done' }])
  })

  it('shows tie ladder validation and repair in player language', () => {
    expect(answerAgentTrace([activity(1, 'TOOL', 'resolveRuleTie')]))
      .toEqual([{ sequence: 1, kind: 'tool', label: '选择“逐级核对平局判定”', status: 'done' }])
    expect(answerAgentTrace([activity(2, 'MODEL', 'repairRuleTieResolutions')]))
      .toEqual([{ sequence: 2, kind: 'verification', label: '补全平局步骤和最终结果后重新核对', status: 'done' }])
  })

  it('shows rule applicability validation and repair in player language', () => {
    expect(answerAgentTrace([activity(1, 'TOOL', 'resolveRuleScope')]))
      .toEqual([{ sequence: 1, kind: 'tool', label: '选择“核对规则适用范围”', status: 'done' }])
    expect(answerAgentTrace([activity(2, 'MODEL', 'repairRuleScopeResolutions')]))
      .toEqual([{ sequence: 2, kind: 'verification', label: '修正适用条件与当前局面后重新核对', status: 'done' }])
  })

  it('shows concept comparison validation and repair in player language', () => {
    expect(answerAgentTrace([activity(1, 'TOOL', 'compareRuleConcepts')]))
      .toEqual([{ sequence: 1, kind: 'tool', label: '选择“对比两个规则概念”', status: 'done' }])
    expect(answerAgentTrace([activity(2, 'MODEL', 'repairRuleConceptComparisons')]))
      .toEqual([{ sequence: 2, kind: 'verification', label: '修正概念定义与使用边界后重新核对', status: 'done' }])
  })

  it('shows complete option-list validation and repair in player language', () => {
    expect(answerAgentTrace([activity(1, 'TOOL', 'listRuleOptions')]))
      .toEqual([{ sequence: 1, kind: 'tool', label: '选择“列出完整规则选项”', status: 'done' }])
    expect(answerAgentTrace([activity(2, 'MODEL', 'repairRuleOptions')]))
      .toEqual([{ sequence: 2, kind: 'verification', label: '补全规则选项后重新核对', status: 'done' }])
  })

  it('explains bounded fallback and repair in player language', () => {
    const trace = answerAgentTrace([
      activity(1, 'VALIDATION', 'nativeToolFallback', 'REJECTED'),
      activity(2, 'VALIDATION', 'nativeCompletionRequirement', 'REJECTED'),
      activity(3, 'MODEL', 'repairPublicationValidation'),
      activity(4, 'MODEL', 'repairRuleCalculation'),
      activity(5, 'MODEL', 'repairRuleSituationCheck'),
      activity(6, 'MODEL', 'repairRuleWalkthrough'),
      activity(7, 'MODEL', 'repairRuleDecisionTable'),
      activity(8, 'MODEL', 'repairRuleExceptionList'),
    ])

    expect(trace.map(item => item.label)).toEqual([
      '工具检索未补到新证据，保留已验证结果',
      '证据工具尚未调用，继续查找依据',
      '修正引用后重新核对回答',
      '修正公式后重新核算结果',
      '修正局面条件后重新核对',
      '修正步骤顺序后重新核对',
      '修正条件分支后重新核对',
      '修正例外条款后重新核对',
    ])
  })
})

function activity(
  sequence: number,
  type: AnswerAgentActivity['type'],
  operation: string,
  outcome: AnswerAgentActivity['outcome'] = 'SUCCEEDED',
): AnswerAgentActivity {
  return { sequence, type, operation, outcome, latencyMs: 10, occurredAt: '2026-08-03T00:00:00Z' }
}
