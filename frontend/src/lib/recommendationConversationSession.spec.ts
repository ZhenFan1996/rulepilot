import { beforeEach, describe, expect, it } from 'vitest'

import {
  forgetRecommendationConversation,
  readRecommendationConversation,
  rememberRecommendationConversation,
  type RecommendationConversationSnapshot,
} from './recommendationConversationSession'

const snapshot: RecommendationConversationSnapshot = {
  profile: { players: 4, maxMinutes: 90, maxWeight: 3.2, type: 'all', interaction: 'any' },
  transcript: [
    { role: 'assistant', text: '今晚想玩什么？' },
    { role: 'user', text: '想找 4 人、90 分钟内的游戏' },
    { role: 'assistant', text: '我核对了几款候选。' },
  ],
  knownGames: [{ bggId: 1, name: '候选一', originalName: 'Candidate One' }],
  shownBggIds: [1],
  failed: true,
  pending: { message: '换一批', excludedBggIds: [1], focusedBggId: null },
}

describe('recommendation conversation session', () => {
  beforeEach(() => sessionStorage.clear())

  it('round-trips bounded context for one normalized account without leaking it to another', () => {
    rememberRecommendationConversation(sessionStorage, ' Alice ', snapshot)

    expect(readRecommendationConversation(sessionStorage, 'alice')).toEqual(snapshot)
    expect(readRecommendationConversation(sessionStorage, 'bob')).toBeNull()
  })

  it('bounds growing context and rejects corrupt or oversized browser data', () => {
    rememberRecommendationConversation(sessionStorage, 'alice', {
      ...snapshot,
      transcript: Array.from({ length: 30 }, (_, index) => ({
        role: index % 2 ? 'user' as const : 'assistant' as const,
        text: `turn-${index}`,
      })),
      knownGames: Array.from({ length: 70 }, (_, index) => ({
        bggId: index + 1,
        name: `候选-${index}`,
        originalName: `Candidate-${index}`,
      })),
      shownBggIds: Array.from({ length: 70 }, (_, index) => index + 1),
    })

    expect(readRecommendationConversation(sessionStorage, 'alice')).toMatchObject({
      transcript: expect.arrayContaining([expect.objectContaining({ text: 'turn-29' })]),
      knownGames: expect.any(Array),
      shownBggIds: expect.any(Array),
    })
    expect(readRecommendationConversation(sessionStorage, 'alice')?.transcript).toHaveLength(24)
    expect(readRecommendationConversation(sessionStorage, 'alice')?.knownGames).toHaveLength(60)
    expect(readRecommendationConversation(sessionStorage, 'alice')?.shownBggIds).toHaveLength(60)

    const key = sessionStorage.key(0)!
    sessionStorage.setItem(key, JSON.stringify({ ...snapshot, transcript: [{ role: 'system', text: '<script>' }] }))
    expect(readRecommendationConversation(sessionStorage, 'alice')).toBeNull()
    expect(sessionStorage.getItem(key)).toBeNull()

    sessionStorage.setItem(key, 'x'.repeat(50_001))
    expect(readRecommendationConversation(sessionStorage, 'alice')).toBeNull()
    expect(sessionStorage.getItem(key)).toBeNull()
  })

  it('deletes only the requested account snapshot', () => {
    rememberRecommendationConversation(sessionStorage, 'alice', snapshot)
    rememberRecommendationConversation(sessionStorage, 'bob', snapshot)

    forgetRecommendationConversation(sessionStorage, 'alice')

    expect(readRecommendationConversation(sessionStorage, 'alice')).toBeNull()
    expect(readRecommendationConversation(sessionStorage, 'bob')).toEqual(snapshot)
  })
})
