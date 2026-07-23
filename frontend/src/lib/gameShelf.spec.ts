import { describe, expect, it } from 'vitest'

import { buildPersonalShelf } from './gameShelf'

describe('buildPersonalShelf', () => {
  it('only shows games reached through the current users rulebooks or lessons', () => {
    const shelf = buildPersonalShelf(
      [
        {
          game: { id: 'root', name: 'Root' },
          editions: [{ id: 'root-en', gameId: 'root', name: '基础版', language: 'en', publicationYear: 2018 }],
          expansions: [{ id: 'riverfolk', gameId: 'root', name: '河民扩展' }],
          bggMetadata: { thumbnailUrl: 'https://image.example/root.jpg', bggUrl: 'https://boardgamegeek.com/boardgame/root', minPlayers: 2, maxPlayers: 4, playingTimeMinutes: 90, minimumAge: 10 },
        },
        {
          game: { id: 'noise', name: '不属于我的测试游戏' },
          editions: [{ id: 'noise-en', gameId: 'noise', name: '测试版', language: 'en', publicationYear: null }],
          expansions: [],
          bggMetadata: null,
        },
      ],
      [{ document: { id: 'doc-root', gameEditionId: 'root-en', title: 'Root rules' }, latestVersion: { id: 'version-root', status: 'READY' } }],
      [{ id: 'plan-root', documentVersionId: 'version-root', gameTitle: 'Root', createdAt: '2026-07-23T12:00:00Z' }],
    )

    expect(shelf).toEqual([expect.objectContaining({
      title: 'Root', documentCount: 1, lessonCount: 1, latestPlanId: 'plan-root', expansionCount: 1, players: '2–4 人', coverAttributionUrl: 'https://boardgamegeek.com/boardgame/root',
    })])
  })

  it('keeps an unassigned rulebook usable instead of dropping it from the shelf', () => {
    const shelf = buildPersonalShelf(
      [],
      [{ document: { id: 'doc-alone', gameEditionId: null, title: 'My small game' }, latestVersion: { id: 'version-alone', status: 'EXTRACTING' } }],
      [],
    )

    expect(shelf).toEqual([expect.objectContaining({ title: 'My small game', documentStatus: 'READING', documentCount: 1 })])
  })
})
