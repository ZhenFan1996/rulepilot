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
  citations: [{ chunkId: 'chunk-1', sectionType: 'SCORING', heading: '计分', excerpt: '每枚硬币一分。', pageFrom: 5, pageTo: 5 }],
  exceptions: [],
  confidence: 'HIGH',
  official: false,
  confirmedRulingId: null,
  confirmedRulingVersion: null,
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
      citations: answer.citations,
      exceptions: [],
      confidence: 'HIGH',
      status: 'CONFIRMED',
      version: 1,
    })

    const entries = loadOfflineKnowledge('plan-1')
    expect(entries).toHaveLength(1)
    expect(entries[0]?.ruling?.id).toBe('ruling-1')
  })

  it('rejects malformed browser storage instead of trusting it', () => {
    localStorage.setItem('rulepilot:offline-knowledge:plan-1', JSON.stringify({ version: 1, entries: [{ question: '<script>' }] }))

    expect(loadOfflineKnowledge('plan-1')).toEqual([])
  })
})
