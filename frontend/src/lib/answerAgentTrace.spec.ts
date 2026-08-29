import { describe, expect, it } from 'vitest'

import { answerAgentTrace, type AnswerAgentActivity } from './answerAgentTrace'

describe('player-facing answer progress', () => {
  it('shows concrete lookup and verification work without exposing tool choice or raw operation details', () => {
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
      '根据当前证据判断下一步',
      '查找规则依据',
      '查找例外与覆盖条款',
      '读取原文页',
      '查看页面图示信息',
      '核算规则结果',
      '核对当前局面条件',
      '整理引用分步讲解',
      '整理条件分支',
      '核对查找结果与引用边界',
    ])
    expect(JSON.stringify(trace)).not.toContain('private-schema-hash')
    expect(trace.map(item => item.label).join(' ')).not.toMatch(/选择“|工具|tool/i)
  })

  it('shows citation-context expansion without exposing evidence handles', () => {
    const trace = answerAgentTrace([
      activity(1, 'TOOL', 'nativeTool|expand_rule_evidence_context|private-schema-hash'),
    ])

    expect(trace).toEqual([
      { sequence: 1, kind: 'tool', label: '展开引用前后文', status: 'done' },
    ])
    expect(JSON.stringify(trace)).not.toContain('private-schema-hash')
  })

  it('keeps a rejected-but-recoverable correction running instead of presenting a terminal failure', () => {
    expect(answerAgentTrace([activity(1, 'VALIDATION', 'nativeCompletionRequirement', 'REJECTED')]))
      .toEqual([{ sequence: 1, kind: 'verification', label: '还需要规则依据，继续查找', status: 'running' }])
  })

  it.each([
    'nativeEmptyCompletion',
    'nativeCompletionProtocol',
    'nativeActionProtocol',
    'nativeToolSchema',
    'nativeObs|read_rule_pages|private-schema-hash|private-call',
  ])('presents recoverable rejection %s as an in-progress correction', (operation) => {
    const trace = answerAgentTrace([activity(1, 'VALIDATION', operation, 'REJECTED')], 'en')

    expect(trace).toEqual([{
      sequence: 1,
      kind: 'verification',
      label: 'The returned content needs correction; checking again',
      status: 'running',
    }])
    expect(JSON.stringify(trace)).not.toContain('private-schema-hash')
  })

  it('distinguishes exhausted observation progress from a correction the agent can continue', () => {
    expect(answerAgentTrace([
      activity(1, 'VALIDATION', 'nativeObservationNoProgress|read_rule_pages', 'REJECTED'),
    ])).toEqual([{
      sequence: 1,
      kind: 'verification',
      label: '补充证据查找没有新增进展，已停止这一步；继续使用已有核验证据',
      status: 'stopped',
    }])

    expect(answerAgentTrace([
      activity(2, 'VALIDATION', 'nativeToolFallback|TIMEOUT', 'SUCCEEDED'),
    ], 'en')).toEqual([{
      sequence: 2,
      kind: 'verification',
      label: 'Supplementary evidence search timed out, so that step stopped; continuing with checked evidence',
      status: 'stopped',
    }])
  })

  it('shows structured exception validation as a player-readable tool choice', () => {
    expect(answerAgentTrace([activity(1, 'TOOL', 'buildRuleExceptionList')]))
      .toEqual([{ sequence: 1, kind: 'tool', label: '逐条核对例外和限制', status: 'done' }])
  })

  it('shows structured term definition validation and repair in player language', () => {
    expect(answerAgentTrace([activity(1, 'TOOL', 'defineRuleTerms')]))
      .toEqual([{ sequence: 1, kind: 'tool', label: '核对规则书术语定义', status: 'done' }])
    expect(answerAgentTrace([activity(2, 'MODEL', 'repairRuleTermDefinitions')]))
      .toEqual([{ sequence: 2, kind: 'verification', label: '修正术语定义后重新核对', status: 'done' }])
  })

  it('shows cited example validation and repair in player language', () => {
    expect(answerAgentTrace([activity(1, 'TOOL', 'illustrateRule')]))
      .toEqual([{ sequence: 1, kind: 'tool', label: '整理带出处的规则示例', status: 'done' }])
    expect(answerAgentTrace([activity(2, 'MODEL', 'repairRuleWorkedExamples')]))
      .toEqual([{ sequence: 2, kind: 'verification', label: '修正示例后重新核对规则出处', status: 'done' }])
  })

  it('shows rule priority validation and repair in player language', () => {
    expect(answerAgentTrace([activity(1, 'TOOL', 'resolveRulePriority')]))
      .toEqual([{ sequence: 1, kind: 'tool', label: '核对规则优先关系', status: 'done' }])
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
      { sequence: 1, kind: 'tool', label: '梳理规则前后关系', status: 'done' },
      { sequence: 2, kind: 'tool', label: '检查两条规则是否真冲突', status: 'done' },
      { sequence: 3, kind: 'tool', label: '展示最直接的原文依据', status: 'done' },
      { sequence: 4, kind: 'tool', label: '核对规则是否允许', status: 'done' },
    ])
  })

  it('shows simultaneous timing validation and repair in player language', () => {
    expect(answerAgentTrace([activity(1, 'TOOL', 'resolveRuleTiming')]))
      .toEqual([{ sequence: 1, kind: 'tool', label: '核对同时触发顺序', status: 'done' }])
    expect(answerAgentTrace([activity(2, 'MODEL', 'repairRuleTimingResolutions')]))
      .toEqual([{ sequence: 2, kind: 'verification', label: '修正时序关系后重新核对', status: 'done' }])
  })

  it('shows tie ladder validation and repair in player language', () => {
    expect(answerAgentTrace([activity(1, 'TOOL', 'resolveRuleTie')]))
      .toEqual([{ sequence: 1, kind: 'tool', label: '逐级核对平局判定', status: 'done' }])
    expect(answerAgentTrace([activity(2, 'MODEL', 'repairRuleTieResolutions')]))
      .toEqual([{ sequence: 2, kind: 'verification', label: '补全平局步骤和最终结果后重新核对', status: 'done' }])
  })

  it('shows rule applicability validation and repair in player language', () => {
    expect(answerAgentTrace([activity(1, 'TOOL', 'resolveRuleScope')]))
      .toEqual([{ sequence: 1, kind: 'tool', label: '核对规则适用范围', status: 'done' }])
    expect(answerAgentTrace([activity(2, 'MODEL', 'repairRuleScopeResolutions')]))
      .toEqual([{ sequence: 2, kind: 'verification', label: '修正适用条件与当前局面后重新核对', status: 'done' }])
  })

  it('shows concept comparison validation and repair in player language', () => {
    expect(answerAgentTrace([activity(1, 'TOOL', 'compareRuleConcepts')]))
      .toEqual([{ sequence: 1, kind: 'tool', label: '对比两个规则概念', status: 'done' }])
    expect(answerAgentTrace([activity(2, 'MODEL', 'repairRuleConceptComparisons')]))
      .toEqual([{ sequence: 2, kind: 'verification', label: '修正概念定义与使用边界后重新核对', status: 'done' }])
  })

  it('shows complete option-list validation and repair in player language', () => {
    expect(answerAgentTrace([activity(1, 'TOOL', 'listRuleOptions')]))
      .toEqual([{ sequence: 1, kind: 'tool', label: '列出完整规则选项', status: 'done' }])
    expect(answerAgentTrace([activity(2, 'MODEL', 'repairRuleOptions')]))
      .toEqual([{ sequence: 2, kind: 'verification', label: '补全规则选项后重新核对', status: 'done' }])
  })

  it('explains bounded fallback and repair in player language', () => {
    const trace = answerAgentTrace([
      activity(1, 'VALIDATION', 'nativeToolFallback|TOKEN_BUDGET', 'REJECTED'),
      activity(2, 'VALIDATION', 'nativeCompletionRequirement', 'REJECTED'),
      activity(3, 'MODEL', 'repairPublicationValidation'),
      activity(4, 'MODEL', 'repairRuleCalculation'),
      activity(5, 'MODEL', 'repairRuleSituationCheck'),
      activity(6, 'MODEL', 'repairRuleWalkthrough'),
      activity(7, 'MODEL', 'repairRuleDecisionTable'),
      activity(8, 'MODEL', 'repairRuleExceptionList'),
    ])

    expect(trace.map(item => item.label)).toEqual([
      '补充证据查找已达到本次容量边界，已停止这一步；继续使用已有核验证据',
      '还需要规则依据，继续查找',
      '修正引用后重新核对回答',
      '修正公式后重新核算结果',
      '修正局面条件后重新核对',
      '修正步骤顺序后重新核对',
      '修正条件分支后重新核对',
      '修正例外条款后重新核对',
    ])
    expect(trace.slice(0, 2).map(item => item.status)).toEqual(['stopped', 'running'])
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
