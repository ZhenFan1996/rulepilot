import { beforeEach, describe, expect, it } from 'vitest'

import {
  forgetRecommendationConversation,
  readRecommendationConversation,
  rememberRecommendationConversation,
  type RecommendationConversationSnapshot,
} from './recommendationConversationSession'

const snapshot: RecommendationConversationSnapshot = {
  conversationId: '2efc8376-883b-4ec0-b310-e1fc39a75473',
  revision: 3,
  responseLocale: 'zh-CN',
  profile: {
    type: 'all',
    interaction: 'any',
    playerCount: { minimum: 3, maximum: 4, strength: 'hard', sourceText: '3–4 人', confirmedTurn: 1 },
    durationMinutes: { minimum: 120, maximum: 180, strength: 'hard', sourceText: '120–180 分钟', confirmedTurn: 1 },
    complexity: { minimum: 2, maximum: 3.2, strength: 'hard', sourceText: '复杂度 2–3.2', confirmedTurn: 1 },
  },
  transcript: [
    { role: 'assistant', text: '今晚想玩什么？' },
    { role: 'user', text: '想找 4 人、90 分钟内的游戏' },
    { role: 'assistant', text: '我核对了几款候选。' },
  ],
  knownGames: [{ bggId: 1, name: '候选一', originalName: 'Candidate One' }],
  shownBggIds: [1],
  failed: true,
  pending: {
    message: '换一批',
    excludedBggIds: [1],
    focusedBggId: null,
    clientTurnId: '37d65d0d-c113-4ed0-af41-5da19c4e3bb8',
    responseLocale: 'zh-CN',
  },
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

  it('preserves a failed pending message losslessly instead of slicing it to the send limit', () => {
    const pendingMessage = '完整保留'.repeat(1_000)
    rememberRecommendationConversation(sessionStorage, 'alice', {
      ...snapshot,
      failed: true,
      pending: { ...snapshot.pending!, message: pendingMessage },
    })

    expect(readRecommendationConversation(sessionStorage, 'alice')?.pending?.message).toBe(pendingMessage)
  })

  it('migrates the previous browser snapshot without inventing a server identity', () => {
    rememberRecommendationConversation(sessionStorage, 'alice', snapshot)
    const key = sessionStorage.key(0)!
    const current = JSON.parse(sessionStorage.getItem(key)!) as Record<string, unknown>
    const pending = current.pending as Record<string, unknown>
    delete current.conversationId
    delete current.revision
    delete current.responseLocale
    delete pending.clientTurnId
    delete pending.responseLocale
    current.profile = {
      players: 4,
      maxMinutes: 90,
      maxWeight: 3,
      type: 'all',
      interaction: 'any',
    }
    current.version = 1
    sessionStorage.setItem(key, JSON.stringify(current))

    expect(readRecommendationConversation(sessionStorage, 'alice')).toMatchObject({
      conversationId: null,
      revision: 0,
      responseLocale: null,
      profile: {
        type: 'all',
        interaction: 'any',
        playerCount: { minimum: 4, maximum: 4, strength: 'hard' },
        durationMinutes: { minimum: null, maximum: 90, strength: 'hard' },
        complexity: { minimum: null, maximum: 3, strength: 'hard' },
      },
      pending: { message: '换一批', clientTurnId: null, responseLocale: null },
    })
  })

  it('migrates the server-identity snapshot that predates per-turn response language', () => {
    rememberRecommendationConversation(sessionStorage, 'alice', snapshot)
    const key = sessionStorage.key(0)!
    const previous = JSON.parse(sessionStorage.getItem(key)!) as Record<string, unknown>
    const pending = previous.pending as Record<string, unknown>
    previous.version = 2
    delete previous.responseLocale
    delete pending.responseLocale
    sessionStorage.setItem(key, JSON.stringify(previous))

    expect(readRecommendationConversation(sessionStorage, 'alice')).toMatchObject({
      conversationId: snapshot.conversationId,
      revision: snapshot.revision,
      responseLocale: null,
      pending: {
        message: snapshot.pending?.message,
        clientTurnId: snapshot.pending?.clientTurnId,
        responseLocale: null,
      },
    })
  })
})
