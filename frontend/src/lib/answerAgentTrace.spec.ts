import { describe, expect, it } from 'vitest'

import { answerAgentTrace, streamedAnswerTraceItem, type AnswerAgentActivity } from './answerAgentTrace'

describe('current native answer Agent trace', () => {
  it('shows native model and allow-listed rulebook work without leaking operation payloads', () => {
    const trace = answerAgentTrace([
      activity(1, 'MODEL', 'nativeModelTurn|1'),
      activity(2, 'TOOL', 'nativeTool|search_rule_evidence|private-schema-hash'),
      activity(3, 'TOOL', 'nativeTool|read_rule_page_image|private-schema-hash'),
    ])

    expect(trace.map(entry => entry.label)).toEqual([
      '根据当前证据决定下一步',
      '查找规则依据',
      '查看规则书页面',
    ])
    expect(JSON.stringify(trace)).not.toContain('private-schema-hash')
  })

  it.each([
    'nativeEmptyCompletion',
    'nativeCompletionProtocol',
    'nativeActionProtocol',
    'nativeToolSchema',
    'nativeObservationEnvelope|read_rule_pages',
    'nativeObs|read_rule_pages|private-schema-hash|private-call',
  ])('keeps %s as an internal correction instead of a terminal player failure', (operation) => {
    expect(answerAgentTrace([activity(1, 'VALIDATION', operation, 'REJECTED')], 'en'))
      .toEqual([{
        sequence: 1,
        kind: 'verification',
        label: 'The typed result did not pass the boundary; the same Agent is returning a complete replacement',
        status: 'running',
      }])
  })

  it('shows exact stop reason and preserves checked work when a tool step stops', () => {
    expect(answerAgentTrace([
      activity(1, 'VALIDATION', 'nativeToolFallback|TIMEOUT'),
      activity(2, 'VALIDATION', 'nativeObservationNoProgress|read_rule_pages', 'REJECTED'),
    ], 'en')).toEqual([
      { sequence: 1, kind: 'verification', label: 'Tool step stopped (TIMEOUT); checked content remains', status: 'stopped' },
      { sequence: 2, kind: 'verification', label: 'No new evidence was found, so this step stopped; checked content remains', status: 'stopped' },
    ])
  })

  it('ignores the obsolete next-action hint on streamed activity', () => {
    const entry = streamedAnswerTraceItem({
      sequence: 1,
      actor: 'answer_agent',
      stage: 'model_decision',
      message: '正在组织回答',
      status: 'running',
      latencyMs: 0,
    })
    expect(entry).toEqual({
      sequence: 1,
      kind: 'decision',
      label: '正在组织回答',
      status: 'running',
      actor: '答疑 Agent',
      stage: 'model_decision',
    })
    expect(JSON.stringify(entry)).not.toContain('nextAction')
  })
})

function activity(
  sequence: number,
  type: AnswerAgentActivity['type'],
  operation: string,
  outcome: AnswerAgentActivity['outcome'] = 'SUCCEEDED',
): AnswerAgentActivity {
  return { sequence, type, operation, outcome, latencyMs: 1, occurredAt: '2026-08-30T00:00:00Z' }
}
