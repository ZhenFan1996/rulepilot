import { describe, expect, it } from 'vitest'

import {
  isPlayerFacingRuleAnswer,
  parsePlayerFacingRuleAnswer,
  type PlayerFacingRuleAnswer,
} from './playerAnswerContract'

const answered: PlayerFacingRuleAnswer = {
  language: 'en',
  status: 'ANSWERED',
  shortVerdict: 'Move after the amber gate closes.',
  explanation: 'The cited timing clause gives that order.',
  citations: [{
    heading: 'Amber gate', excerpt: 'Move after the amber gate closes.', pageFrom: 7, pageTo: 7,
  }],
  exceptions: [],
  confidence: 'HIGH',
  answerBasis: 'DIRECT_RULE',
  source: 'UPLOADED',
  clarification: null,
  recovery: null,
  warnings: [],
}

describe('playerAnswerContract', () => {
  it('projects unknown operational fields out of an otherwise valid answer', () => {
    const internalId = '11111111-1111-4111-8111-111111111111'
    const raw = {
      ...answered,
      assistantRunId: internalId,
      citations: answered.citations.map(citation => ({
        ...citation, chunkId: internalId, sectionType: 'TIMING',
      })),
      schemaDiagnostic: 'not part of the player contract',
    }

    const parsed = parsePlayerFacingRuleAnswer(raw)

    expect(parsed).toEqual(answered)
    expect(JSON.stringify(parsed)).not.toMatch(/assistantRunId|chunkId|sectionType|schemaDiagnostic|11111111/)
  })

  it('requires cited conclusions without rejecting useful prose for warning bookkeeping', () => {
    expect(isPlayerFacingRuleAnswer({ ...answered, citations: [] })).toBe(false)
    expect(isPlayerFacingRuleAnswer({ ...answered, answerBasis: null })).toBe(false)
    expect(isPlayerFacingRuleAnswer({
      ...answered,
      status: 'ANSWERED_WITH_WARNING',
      warnings: [],
    })).toBe(true)
    expect(isPlayerFacingRuleAnswer({
      ...answered,
      warnings: [{ type: 'LOW_CONFIDENCE' }],
    })).toBe(true)
  })

  it('preserves evidence-backed partial answers instead of requiring an empty fallback shape', () => {
    const insufficient: PlayerFacingRuleAnswer = {
      language: 'zh-CN',
      status: 'INSUFFICIENT_EVIDENCE',
      shortVerdict: '现有依据还不足以可靠回答这个问题。',
      explanation: '',
      citations: [{ heading: '候选页', excerpt: '本页可能包含相关时机。', pageFrom: 3, pageTo: 3 }],
      exceptions: [],
      confidence: 'LOW',
      answerBasis: null,
      source: 'UPLOADED',
      clarification: null,
      recovery: { message: '请补充具体对象或时机。', actionLabel: '补充细节', draft: '' },
      warnings: [],
    }

    expect(isPlayerFacingRuleAnswer(insufficient)).toBe(true)
    expect(isPlayerFacingRuleAnswer({
      ...insufficient,
      explanation: '这页能确认行动发生在移动之后，但没有说明并列目标如何选择。',
      recovery: null,
      walkthroughSteps: Array.from({ length: 9 }, (_, index) => ({
        instruction: `已确认步骤 ${index + 1}`,
        explanation: '保留模型基于引用给出的逐步说明。'.repeat(30),
        orderBasis: 'RULE_ORDER' as const,
      })),
    })).toBe(true)
    expect(isPlayerFacingRuleAnswer({
      ...insufficient,
      status: 'MODEL_TIMEOUT',
    })).toBe(true)
  })

  it('does not reinterpret valid published prose with a browser-side keyword blacklist', () => {
    const naturalPublishedAnswer = {
      ...answered,
      explanation: '规则书把这段写成 JSON 示例；引用 E1 只是正文里的玩家标签，不是浏览器协议。',
      citations: [{
        ...answered.citations[0],
        excerpt: 'The assistant token and source marker are names printed on the card.',
      }],
    }

    expect(parsePlayerFacingRuleAnswer(naturalPublishedAnswer)).toEqual(naturalPublishedAnswer)
  })

  it('drops only malformed optional enrichment while preserving the cited answer core', () => {
    const parsed = parsePlayerFacingRuleAnswer({
      ...answered,
      citations: [
        ...answered.citations,
        { heading: 'Broken citation', excerpt: 'No usable page identity.', pageFrom: 'seven', pageTo: 7 },
      ],
      calculations: [
        { expression: '3 × 4', result: '12' },
        { expression: 'missing result' },
      ],
      walkthroughSteps: { unexpected: 'object instead of a list' },
      warnings: [{ type: 'LOW_CONFIDENCE' }, { type: 'OLD_BROWSER_ONLY_WARNING' }],
    })

    expect(parsed).toMatchObject({
      shortVerdict: answered.shortVerdict,
      citations: answered.citations,
      calculations: [{ expression: '3 × 4', result: '12' }],
      warnings: [{ type: 'LOW_CONFIDENCE' }],
    })
    expect(parsed?.walkthroughSteps).toBeUndefined()
    expect(parsePlayerFacingRuleAnswer({
      ...answered,
      citations: [{ heading: 'Broken', excerpt: '', pageFrom: 0, pageTo: 0 }],
    })).toBeNull()
  })
})
