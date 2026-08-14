import { describe, expect, it } from 'vitest'

import { buildPersonalShelf, hasPendingShelfWork } from './gameShelf'

const catalog = [{
  game: { id: 'root', name: 'Root' },
  editions: [{ id: 'root-en', gameId: 'root', name: '基础版', language: 'en', publicationYear: 2018 }],
  expansions: [{ id: 'riverfolk', gameId: 'root', name: '河民扩展' }],
  bggMetadata: {
    bggId: 237182,
    thumbnailUrl: 'https://image.example/root.jpg',
    bggUrl: 'https://boardgamegeek.com/boardgame/237182',
    minPlayers: 2,
    maxPlayers: 4,
    playingTimeMinutes: 90,
    minimumAge: 10,
  },
}]

const rootDocument = {
  document: {
    id: 'doc-root', gameEditionId: 'root-en', title: 'Root rules', officialSourceUrl: null,
    officialCoverUrl: null, createdBy: 'player',
  },
  latestVersion: { id: 'version-root', status: 'READY' },
}

const rootPlan = {
  id: 'plan-root', documentVersionId: 'version-root', gameTitle: 'Root', createdBy: 'player',
  createdAt: '2026-07-23T12:00:00Z',
}

describe('buildPersonalShelf', () => {
  it('only shows games reached through the current users rulebooks or lessons', () => {
    const shelf = buildPersonalShelf(
      [
        ...catalog,
        {
          game: { id: 'noise', name: '不属于我的测试游戏' },
          editions: [{ id: 'noise-en', gameId: 'noise', name: '测试版', language: 'en', publicationYear: null }],
          expansions: [],
          bggMetadata: null,
        },
      ],
      [rootDocument],
      [rootPlan],
    )

    expect(shelf).toEqual([expect.objectContaining({
      title: 'Root', documentCount: 1, pendingImportCount: 0, lessonCount: 1,
      latestPlanId: 'plan-root', guideStatus: 'READY', expansionCount: 1,
      players: { min: 2, max: 4 }, playtimeMinutes: 90, minimumAge: 10,
      coverAttributionUrl: 'https://boardgamegeek.com/boardgame/237182',
    })])
  })

  it.each(['UPLOADED', 'VALIDATING', 'EXTRACTING', 'STRUCTURING', 'CHUNKING', 'EMBEDDING', 'INDEXING'])(
    'presents %s as active reading rather than a failed rulebook',
    (status) => {
      const shelf = buildPersonalShelf([], [{
        document: {
          id: 'doc-alone', gameEditionId: null, title: 'My small game', officialSourceUrl: null,
          officialCoverUrl: null, createdBy: 'player',
        },
        latestVersion: { id: 'version-alone', status },
      }], [])

      expect(shelf).toEqual([expect.objectContaining({
        title: 'My small game', documentStatus: 'READING', documentCount: 1,
      })])
    },
  )

  it('shows the selected game while its durable import has not created a document yet', () => {
    const shelf = buildPersonalShelf(catalog, [], [], {
      imports: [{
        id: 'import-root', title: 'Root', rulebookTitle: 'Root Rules', editionId: 'root-en', editionName: '基础版',
        sourceDomain: 'publisher.example', stage: 'DOWNLOADING', downloadedBytes: 1024, totalBytes: 4096,
        documentVersionId: null, errorCode: null, teachingHandoffState: 'WAITING_FOR_DOCUMENT',
        teachingPreparationRunId: null, teachingErrorCode: null, updatedAt: '2026-08-13T08:00:00Z',
      }],
      plansAvailability: 'LOADING',
    })

    expect(shelf).toEqual([expect.objectContaining({
      id: 'game:root', gameId: 'root', editionId: 'root-en', documentCount: 0, pendingImportCount: 1,
      documentStatus: 'IMPORTING', guideStatus: 'PREPARING',
    })])
  })

  it('upgrades an import placeholder in place after its exact document version and guide arrive', () => {
    const shelf = buildPersonalShelf(catalog, [rootDocument], [rootPlan], {
      imports: [{
        id: 'import-root', title: 'Root', rulebookTitle: 'Root Rules', editionId: 'root-en', editionName: '基础版',
        sourceDomain: 'publisher.example', stage: 'COMPLETED', downloadedBytes: 4096, totalBytes: 4096,
        documentVersionId: 'version-root', errorCode: null, teachingHandoffState: 'LAUNCHED',
        teachingPreparationRunId: 'preparation-root', teachingErrorCode: null, updatedAt: '2026-08-13T08:00:00Z',
      }],
    })

    expect(shelf).toHaveLength(1)
    expect(shelf[0]).toEqual(expect.objectContaining({
      id: 'game:root', documentCount: 1, pendingImportCount: 0, lessonCount: 1,
      documentStatus: 'READY', guideStatus: 'READY',
    }))
  })

  it('does not claim that no guide exists while the guide list is slow or unavailable', () => {
    expect(buildPersonalShelf(catalog, [rootDocument], [], { plansAvailability: 'LOADING' })[0]?.guideStatus)
      .toBe('LOADING')
    expect(buildPersonalShelf(catalog, [rootDocument], [], { plansAvailability: 'UNAVAILABLE' })[0]?.guideStatus)
      .toBe('UNAVAILABLE')
  })

  it('observes only work that can still advance a document or version-bound guide', () => {
    const pendingImport = {
      id: 'import-root', title: 'Root', rulebookTitle: 'Root Rules', editionId: 'root-en', editionName: '基础版',
      sourceDomain: 'publisher.example', stage: 'DOWNLOADING' as const, downloadedBytes: 1024, totalBytes: 4096,
      documentVersionId: null, errorCode: null, teachingHandoffState: 'WAITING_FOR_DOCUMENT' as const,
      teachingPreparationRunId: null, teachingErrorCode: null, updatedAt: '2026-08-13T08:00:00Z',
    }
    expect(hasPendingShelfWork([], [pendingImport], [], [])).toBe(true)
    expect(hasPendingShelfWork([{
      ...rootDocument, latestVersion: { ...rootDocument.latestVersion, status: 'INDEXING' },
    }], [], [], [])).toBe(true)
    expect(hasPendingShelfWork([rootDocument], [], [{
      id: 'handoff-root', documentVersionId: 'version-root', editionId: 'root-en', rulebookTitle: 'Root Rules',
      state: 'LAUNCHED', preparationRunId: 'preparation-root', errorCode: null, updatedAt: '2026-08-13T08:00:00Z',
    }], [])).toBe(true)
    expect(hasPendingShelfWork([rootDocument], [], [{
      id: 'handoff-root', documentVersionId: 'version-root', editionId: 'root-en', rulebookTitle: 'Root Rules',
      state: 'LAUNCHED', preparationRunId: 'preparation-root', errorCode: null, updatedAt: '2026-08-13T08:00:00Z',
    }], [], undefined, [{
      id: 'preparation-root', documentVersionId: 'version-root', state: 'FAILED',
    }])).toBe(false)
    expect(hasPendingShelfWork([rootDocument], [], [], [rootPlan])).toBe(false)
    expect(hasPendingShelfWork([], [pendingImport], [], [], new Set(['other-edition']))).toBe(false)
    expect(hasPendingShelfWork([], [{
      ...pendingImport, stage: 'COMPLETED', documentVersionId: 'version-root', teachingHandoffState: 'NOT_REQUESTED',
    }], [], [])).toBe(true)
    expect(hasPendingShelfWork([rootDocument], [{
      ...pendingImport, stage: 'COMPLETED', documentVersionId: 'older-root-version', teachingHandoffState: 'NOT_REQUESTED',
    }], [], [])).toBe(false)
    expect(hasPendingShelfWork([], [{
      ...pendingImport, stage: 'FAILED', errorCode: 'SOURCE_DOWNLOAD_FAILED', teachingHandoffState: 'FAILED',
      teachingErrorCode: 'IMPORT_FAILED',
    }], [], [])).toBe(false)
  })

  it('shows a failed exact preparation run instead of claiming background work is active', () => {
    const item = buildPersonalShelf(catalog, [rootDocument], [], {
      uploadHandoffs: [{
        id: 'handoff-root', documentVersionId: 'version-root', editionId: 'root-en', rulebookTitle: 'Root Rules',
        state: 'LAUNCHED', preparationRunId: 'preparation-root', errorCode: null, updatedAt: '2026-08-13T08:00:00Z',
      }],
      preparationRuns: [{ id: 'preparation-root', documentVersionId: 'version-root', state: 'FAILED' }],
    })[0]

    expect(item?.guideStatus).toBe('FAILED')
  })
})
