import { describe, expect, it } from 'vitest'

import {
  parsePersonalShelfBase,
  parseRichBggDetails,
  parseShelfPreparationRun,
  parseShelfPlans,
  parseShelfUploadHandoffs,
  validateShelfUploadHandoffs,
} from './gameShelfSnapshot'

const catalog = [{
  game: { id: 'game-1', name: 'Catalog Game' },
  editions: [{ id: 'edition-1', gameId: 'game-1', name: 'First Edition', language: 'en', publicationYear: 2024 }],
  expansions: [],
  bggMetadata: {
    bggId: 42, thumbnailUrl: '', bggUrl: 'https://boardgamegeek.com/boardgame/42',
    minPlayers: 1, maxPlayers: 5, playingTimeMinutes: 60, minimumAge: 10,
  },
}]
const documents = [{
  document: {
    id: 'document-1', gameEditionId: 'edition-1', title: 'Official Rules',
    officialSourceUrl: null, officialCoverUrl: null, createdBy: 'player',
  },
  latestVersion: { id: 'version-1', status: 'READY' },
}]
const imports = [{
  id: 'import-1', title: 'Catalog Game', rulebookTitle: 'Official Rules', editionId: 'edition-1',
  editionName: 'First Edition', sourceDomain: 'publisher.example', stage: 'COMPLETED',
  downloadedBytes: 4096, totalBytes: 4096, documentVersionId: 'version-1', errorCode: null,
  teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'preparation-1', teachingErrorCode: null,
  downloadCompletedAt: '2026-08-13T07:59:50Z', importCompletedAt: '2026-08-13T07:59:55Z',
  teachingHandoffUpdatedAt: '2026-08-13T08:00:00Z',
  updatedAt: '2026-08-13T08:00:00Z',
}]

describe('personal shelf response boundaries', () => {
  it('accepts one owner-scoped game, edition, document version, and import relationship', () => {
    expect(parsePersonalShelfBase(catalog, documents, imports, 'player')).toEqual({ catalog, documents, imports })
    expect(parseShelfPlans([{
      id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Catalog Game', createdBy: 'player',
      createdAt: '2026-08-13T08:01:00Z',
    }], 'player')).toHaveLength(1)
  })

  it('rejects cross-account documents and plans', () => {
    expect(() => parsePersonalShelfBase(catalog, [{
      ...documents[0], document: { ...documents[0]!.document, createdBy: 'other' },
    }], [], 'player')).toThrow()
    expect(() => parseShelfPlans([{
      id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Catalog Game', createdBy: 'other',
      createdAt: '2026-08-13T08:01:00Z',
    }], 'player')).toThrow()
  })

  it('rejects an edition or import that points at a different game relationship', () => {
    expect(() => parsePersonalShelfBase([{
      ...catalog[0],
      editions: [{ ...catalog[0]!.editions[0]!, gameId: 'other-game' }],
    }], documents, imports, 'player')).toThrow()
    expect(() => parsePersonalShelfBase(catalog, documents, [{
      ...imports[0], editionId: 'missing-edition',
    }], 'player')).toThrow()
  })

  it('rejects malformed persisted import milestones', () => {
    expect(() => parsePersonalShelfBase(catalog, documents, [{
      ...imports[0], teachingHandoffUpdatedAt: 'not-a-time',
    }], 'player')).toThrow()
  })

  it('rejects a completed import whose resulting version is bound to another edition', () => {
    const secondCatalog = [{
      ...catalog[0],
      editions: [
        ...catalog[0]!.editions,
        { id: 'edition-2', gameId: 'game-1', name: 'Second Edition', language: 'zh-CN', publicationYear: 2025 },
      ],
    }]
    expect(() => parsePersonalShelfBase(secondCatalog, documents, [{
      ...imports[0], editionId: 'edition-2',
    }], 'player')).toThrow()
  })

  it('requires optional BGG details to return the exact requested identity', () => {
    const details = {
      bggId: 42, name: 'Catalog Game', description: 'Description', imageUrl: '', thumbnailUrl: '',
      averageRating: 7.8, averageWeight: 2.4, categories: ['Strategy'], mechanics: ['Drafting'],
      designers: ['Designer'], publishers: ['Publisher'], bggUrl: 'https://boardgamegeek.com/boardgame/42',
    }
    expect(parseRichBggDetails(details, 42).bggId).toBe(42)
    expect(() => parseRichBggDetails({ ...details, bggId: 43 }, 42)).toThrow()
  })

  it('requires upload teaching handoffs to bind the exact owner document version and edition', () => {
    const handoffs = parseShelfUploadHandoffs([{
      id: 'handoff-1', documentVersionId: 'version-1', editionId: 'edition-1', rulebookTitle: 'Official Rules',
      state: 'LAUNCHED', preparationRunId: 'preparation-1', errorCode: null,
      updatedAt: '2026-08-13T08:02:00Z',
    }])
    expect(() => validateShelfUploadHandoffs(handoffs, parsePersonalShelfBase(catalog, documents, [], 'player').documents))
      .not.toThrow()
    expect(() => validateShelfUploadHandoffs([{ ...handoffs[0]!, editionId: 'other-edition' }], documents))
      .toThrow()
  })

  it('requires a preparation run to match the exact run, version, owner, and mode', () => {
    const run = {
      id: 'preparation-1', mode: 'TEACHING_PREPARATION', subjectId: 'version-1', ownerUsername: 'player',
      state: 'LESSON_PLANNING', updatedAt: '2026-08-13T08:02:00Z',
    }
    expect(parseShelfPreparationRun({ run }, 'preparation-1', 'version-1', 'player')).toEqual({
      id: 'preparation-1', documentVersionId: 'version-1', state: 'ACTIVE',
    })
    expect(parseShelfPreparationRun({ run: { ...run, state: 'FAILED' } }, 'preparation-1', 'version-1', 'player').state)
      .toBe('FAILED')
    expect(() => parseShelfPreparationRun({ run: { ...run, ownerUsername: 'other' } }, 'preparation-1', 'version-1', 'player'))
      .toThrow()
    expect(() => parseShelfPreparationRun({ run: { ...run, subjectId: 'other-version' } }, 'preparation-1', 'version-1', 'player'))
      .toThrow()
  })

  it('rejects incoherent terminal import and upload handoff shapes', () => {
    expect(() => parsePersonalShelfBase(catalog, documents, [{
      ...imports[0], stage: 'COMPLETED', documentVersionId: null,
    }], 'player')).toThrow()
    expect(() => parseShelfUploadHandoffs([{
      id: 'handoff-1', documentVersionId: 'version-1', editionId: 'edition-1', rulebookTitle: 'Official Rules',
      state: 'LAUNCHED', preparationRunId: null, errorCode: null, updatedAt: '2026-08-13T08:02:00Z',
    }])).toThrow()
  })
})
