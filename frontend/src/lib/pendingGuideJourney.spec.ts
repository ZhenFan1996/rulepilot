import { describe, expect, it } from 'vitest'

import { buildPendingGuideJourneys } from './pendingGuideJourney'

describe('buildPendingGuideJourneys', () => {
  it('shows a selected catalog game in My Guides before its plan exists', () => {
    const journeys = buildPendingGuideJourneys([], [{
      id: 'import-1', title: '花砖物语', rulebookTitle: 'azul_rules_cn_final.pdf', stage: 'DOWNLOADING',
      downloadedBytes: 50, totalBytes: 100, documentVersionId: null, errorCode: null,
      teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null,
      updatedAt: '2026-08-10T10:00:00Z',
    }], [], [], [])

    expect(journeys).toEqual([expect.objectContaining({
      id: 'import:import-1', title: '花砖物语', rulebookTitle: 'azul_rules_cn_final.pdf',
      phase: 'DOWNLOADING', state: 'active', progress: 50,
    })])
  })

  it('replaces the pending card with the persisted teaching plan', () => {
    expect(buildPendingGuideJourneys(
      [{ documentVersionId: 'version-1' }],
      [{
        id: 'import-1', title: '花砖物语', stage: 'COMPLETED', downloadedBytes: 100,
        totalBytes: 100, documentVersionId: 'version-1', errorCode: null,
        teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'prep-1',
        updatedAt: '2026-08-10T10:00:00Z',
      }],
      [], [], [],
    )).toEqual([])
  })

  it('covers preparation started from a local-upload or rulebook entry and resolves its catalog game name', () => {
    const journeys = buildPendingGuideJourneys([], [], [{
      id: 'prep-1', subjectId: 'version-1', state: 'LESSON_PLANNING', updatedAt: '2026-08-10T10:01:00Z',
    }], [{
      document: { gameEditionId: 'edition-1', title: 'rules_v4_final.pdf' },
      latestVersion: { id: 'version-1' },
    }], [{ game: { name: '星际探险' }, editions: [{ id: 'edition-1' }] }])

    expect(journeys).toEqual([expect.objectContaining({
      title: '星际探险', rulebookTitle: 'rules_v4_final.pdf', phase: 'PREPARING_GUIDE',
    })])
  })

  it('shows a server-persisted local upload while the rulebook is still being read', () => {
    const journeys = buildPendingGuideJourneys(
      [],
      [],
      [],
      [{
        document: { gameEditionId: 'edition-1', title: 'rules_v4_final.pdf' },
        latestVersion: { id: 'version-1' },
      }],
      [{ game: { name: '星际探险' }, editions: [{ id: 'edition-1' }] }],
      [{
        id: 'handoff-1', documentVersionId: 'version-1', editionId: 'edition-1',
        rulebookTitle: 'rules_v4_final.pdf', state: 'WAITING_FOR_DOCUMENT', preparationRunId: null,
        errorCode: null, updatedAt: '2026-08-10T10:00:00Z',
      }],
    )

    expect(journeys).toEqual([expect.objectContaining({
      id: 'upload:handoff-1', title: '星际探险', rulebookTitle: 'rules_v4_final.pdf',
      phase: 'READING_RULEBOOK', state: 'active',
    })])
  })

  it('reports a persisted preparation failure instead of leaving the guide active forever', () => {
    const journeys = buildPendingGuideJourneys([], [{
      id: 'import-1', title: '花砖物语', stage: 'COMPLETED', downloadedBytes: 100,
      totalBytes: 100, documentVersionId: 'version-1', errorCode: null,
      teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'prep-1',
      updatedAt: '2026-08-10T10:00:00Z',
    }], [{
      id: 'prep-1', subjectId: 'version-1', state: 'FAILED',
      lastErrorCode: 'TEACHING_PREPARATION_FAILED', updatedAt: '2026-08-10T10:01:00Z',
    }], [], [])

    expect(journeys).toEqual([expect.objectContaining({
      title: '花砖物语', phase: 'FAILED', state: 'failed', retryAction: 'PREPARE_TEACHING',
    })])
  })

  it('keeps a newer retry run authoritative over an older failed handoff run', () => {
    const journeys = buildPendingGuideJourneys([], [{
      id: 'import-1', title: '花砖物语', stage: 'COMPLETED', downloadedBytes: 100,
      totalBytes: 100, documentVersionId: 'version-1', errorCode: null,
      teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'prep-failed',
      updatedAt: '2026-08-10T10:00:00Z',
    }], [
      {
        id: 'prep-retry', subjectId: 'version-1', state: 'LESSON_PLANNING',
        updatedAt: '2026-08-10T10:02:00Z',
      },
      {
        id: 'prep-failed', subjectId: 'version-1', state: 'FAILED',
        updatedAt: '2026-08-10T10:01:00Z',
      },
    ], [], [])

    expect(journeys).toEqual([expect.objectContaining({
      title: '花砖物语', phase: 'PREPARING_GUIDE', state: 'active', retryAction: null,
    })])
  })

  it('terminalizes a local-upload guide projection when document processing failed', () => {
    const journeys = buildPendingGuideJourneys([], [], [], [{
      document: { gameEditionId: null, title: 'unreadable.pdf' },
      latestVersion: { id: 'version-1', status: 'FAILED' },
    }], [], [{
      id: 'handoff-1', documentVersionId: 'version-1', editionId: null,
      rulebookTitle: 'unreadable.pdf', state: 'WAITING_FOR_DOCUMENT', preparationRunId: null,
      errorCode: null, updatedAt: '2026-08-10T10:00:00Z',
    }])

    expect(journeys).toEqual([expect.objectContaining({
      title: 'unreadable.pdf', phase: 'FAILED', state: 'failed', canReadRulebook: false, retryAction: null,
    })])
  })

  it('terminalizes an official-import guide projection when its bound document failed', () => {
    const journeys = buildPendingGuideJourneys([], [{
      id: 'import-1', title: '花砖物语', stage: 'COMPLETED', downloadedBytes: 100,
      totalBytes: 100, documentVersionId: 'version-1', errorCode: null,
      teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null,
      updatedAt: '2026-08-10T10:00:00Z',
    }], [], [{
      document: { gameEditionId: 'edition-1', title: 'azul.pdf' },
      latestVersion: { id: 'version-1', status: 'FAILED' },
    }], [])

    expect(journeys).toEqual([expect.objectContaining({
      title: '花砖物语', phase: 'FAILED', state: 'failed', canReadRulebook: false, retryAction: null,
    })])
  })
})
