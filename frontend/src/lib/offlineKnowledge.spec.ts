import { beforeEach, describe, expect, it } from 'vitest'

import {
  cacheOfflineAnswer,
  cacheOfflineRuling,
  loadOfflineKnowledge,
  type OfflineAnswer,
} from './offlineKnowledge'

const answer: OfflineAnswer = {
  status: 'ANSWERED',
  shortVerdict: '每枚剩余硬币计一分。',
  explanation: '终局时计算玩家剩余硬币。',
  citations: [{ heading: '计分', excerpt: '每枚硬币一分。', pageFrom: 5, pageTo: 5 }],
  exceptions: [],
  confidence: 'HIGH',
  source: 'UPLOADED',
  clarification: null,
}

describe('offline knowledge cache', () => {
  beforeEach(() => localStorage.clear())

  it('keeps a recent answer and upgrades it with a confirmed ruling', () => {
    cacheOfflineAnswer('plan-1', '硬币如何计分？', answer)
    cacheOfflineRuling('plan-1', '硬币如何计分？', {
      id: 'ruling-1',
      shortVerdict: answer.shortVerdict,
      explanation: answer.explanation,
      citations: answer.citations.map(citation => ({ ...citation, chunkId: 'chunk-1', sectionType: 'SCORING' })),
      exceptions: [],
      confidence: 'HIGH',
      status: 'CONFIRMED',
      version: 1,
    })

    const entries = loadOfflineKnowledge('plan-1')
    expect(entries).toHaveLength(1)
    expect(entries[0]?.ruling?.id).toBe('ruling-1')
  })

  it('keeps a complete verdict when no additional explanation is needed', () => {
    const conciseAnswer = { ...answer, explanation: '' }
    cacheOfflineAnswer('plan-concise', '硬币如何计分？', conciseAnswer)
    cacheOfflineRuling('plan-concise', '硬币如何计分？', {
      id: 'ruling-concise',
      shortVerdict: conciseAnswer.shortVerdict,
      explanation: '',
      citations: conciseAnswer.citations.map(citation => ({
        ...citation,
        chunkId: 'chunk-concise',
        sectionType: 'SCORING',
      })),
      exceptions: [],
      confidence: 'HIGH',
      status: 'CONFIRMED',
      version: 1,
    })

    expect(loadOfflineKnowledge('plan-concise')[0]).toMatchObject({
      answer: { shortVerdict: conciseAnswer.shortVerdict, explanation: '' },
      ruling: { id: 'ruling-concise', explanation: '' },
    })
  })

  it('keeps a detailed cited answer offline without prose or citation-count rejection', () => {
    const detailedQuestion = '请结合当前完整局面逐步解释：'.repeat(80)
    const detailedAnswer: OfflineAnswer = {
      ...answer,
      explanation: '这是一段需要完整保留的规则解释。'.repeat(1_000),
      citations: Array.from({ length: 21 }, (_, index) => ({
        heading: `规则依据 ${index + 1}`,
        excerpt: '对应原文。'.repeat(1_000),
        pageFrom: index + 1,
        pageTo: index + 1,
      })),
    }

    cacheOfflineAnswer('plan-detailed', detailedQuestion, detailedAnswer)

    expect(loadOfflineKnowledge('plan-detailed')[0]).toMatchObject({
      question: detailedQuestion,
      answer: { explanation: detailedAnswer.explanation, citations: expect.any(Array) },
    })
    expect(loadOfflineKnowledge('plan-detailed')[0]?.answer.citations).toHaveLength(21)
  })

  it('migrates validated version 1 knowledge without losing a confirmed ruling', () => {
    const key = 'rulepilot:offline-knowledge:plan-legacy'
    localStorage.setItem(key, JSON.stringify({
      version: 1,
      entries: [{
        question: '硬币如何计分？',
        cachedAt: '2026-07-18T10:00:00.000Z',
        answer: {
          status: 'ANSWERED',
          shortVerdict: answer.shortVerdict,
          explanation: answer.explanation,
          citations: [{
            ...answer.citations[0],
            chunkId: 'chunk-1',
            sectionType: 'SCORING',
          }],
          exceptions: [],
          confidence: 'HIGH',
          official: false,
          confirmedRulingId: 'ruling-1',
          confirmedRulingVersion: 1,
          clarification: null,
        },
        ruling: {
          id: 'ruling-1',
          shortVerdict: answer.shortVerdict,
          explanation: answer.explanation,
          citations: [{
            ...answer.citations[0],
            chunkId: 'chunk-1',
            sectionType: 'SCORING',
          }],
          exceptions: [],
          confidence: 'HIGH',
          status: 'CONFIRMED',
          version: 1,
        },
      }],
    }))

    const entries = loadOfflineKnowledge('plan-legacy')

    expect(entries).toHaveLength(1)
    expect(entries[0]?.answer.source).toBe('CONFIRMED')
    expect(entries[0]?.answer.citations[0]).toEqual(answer.citations[0])
    expect(entries[0]?.answer).not.toHaveProperty('official')
    expect(entries[0]?.ruling?.id).toBe('ruling-1')
    const migrated = JSON.parse(localStorage.getItem(key) ?? 'null') as { version?: number }
    expect(migrated.version).toBe(2)
  })

  it('rejects malformed browser storage instead of trusting it', () => {
    localStorage.setItem('rulepilot:offline-knowledge:plan-1', JSON.stringify({ version: 2, entries: [{ question: '<script>' }] }))

    expect(loadOfflineKnowledge('plan-1')).toEqual([])
  })
})
