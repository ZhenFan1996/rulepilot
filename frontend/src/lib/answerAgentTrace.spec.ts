import { describe, expect, it } from 'vitest'

import { streamedAnswerTraceItem } from './answerAgentTrace'

describe('current native answer Agent trace', () => {
  it('presents the server-owned activity without exposing internal operation data', () => {
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
  })
})
