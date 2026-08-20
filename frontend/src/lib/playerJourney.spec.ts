import { describe, expect, it } from 'vitest'

import {
  acceptImportJob,
  acceptJourneyRun,
  derivePlayerJourney,
  playerJourneyPollDelay,
  type PlayerJourneyImportJob,
  type PlayerJourneyInput,
  type PlayerJourneyRun,
} from './playerJourney'

describe('playerJourneyPollDelay', () => {
  it('checks quickly only while a plan is waiting for its first readable chapter', () => {
    expect(playerJourneyPollDelay(false, false)).toBe(1_250)
    expect(playerJourneyPollDelay(false, true)).toBe(500)
  })

  it('keeps transient failures on the slower retry cadence', () => {
    expect(playerJourneyPollDelay(true, true)).toBe(3_000)
  })
})

function input(overrides: Partial<PlayerJourneyInput> = {}): PlayerJourneyInput {
  return {
    gameBound: false,
    discovery: 'idle',
    importJob: null,
    documentProgress: null,
    preparationRun: null,
    plan: null,
    teachingRun: null,
    lesson: null,
    ...overrides,
  }
}

function importJob(overrides: Partial<PlayerJourneyImportJob> = {}): PlayerJourneyImportJob {
  return {
    id: 'import-1', stage: 'QUEUED', downloadedBytes: 0, totalBytes: null,
    documentVersionId: null, errorCode: null, teachingHandoffState: 'WAITING_FOR_DOCUMENT',
    teachingPreparationRunId: null, updatedAt: '2026-08-10T10:00:00Z', ...overrides,
  }
}

function run(state: string, revision = 1): PlayerJourneyRun {
  return {
    run: { id: 'run-1', state, revision, updatedAt: `2026-08-10T10:00:0${revision}Z`, lastErrorCode: null },
    activities: [],
  }
}

describe('derivePlayerJourney', () => {
  it('keeps source review distinct from work that has actually started', () => {
    expect(derivePlayerJourney(input({ gameBound: true, discovery: 'review' }))).toMatchObject({
      phase: 'SOURCE_REVIEW', state: 'waiting', progress: 18,
    })
  })

  it('reports byte-backed download progress without calling it complete', () => {
    expect(derivePlayerJourney(input({
      gameBound: true,
      discovery: 'review',
      importJob: importJob({ stage: 'DOWNLOADING', downloadedBytes: 50, totalBytes: 100 }),
    }))).toMatchObject({ phase: 'IMPORT_DOWNLOADING', state: 'active', progress: 40 })
  })

  it('does not equate the teaching handoff with a readable lesson', () => {
    expect(derivePlayerJourney(input({
      gameBound: true,
      discovery: 'review',
      importJob: importJob({
        stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'LAUNCHED',
        teachingPreparationRunId: 'preparation-1',
      }),
      documentProgress: { stage: 'READY', percentage: 100, processedPages: 16, totalPages: 16, complete: true },
      preparationRun: run('COMPLETED'),
    }))).toMatchObject({
      phase: 'LESSON_GENERATION_QUEUED', state: 'active', canReadRulebook: true,
      canReadLesson: false, canAskQuestions: false,
    })
  })

  it('does not mark the rulebook milestone from a teaching handoff without a readable document snapshot', () => {
    expect(derivePlayerJourney(input({
      gameBound: true,
      importJob: importJob({
        stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'LAUNCHED',
        teachingPreparationRunId: 'preparation-1',
      }),
      plan: { id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Example', premise: 'Learn', sections: [
        { position: 1, title: 'Setup' },
      ] },
      lesson: { id: 'lesson-1', status: 'DRAFT_READY', sections: [{ position: 1, title: 'Setup' }] },
    }))).toMatchObject({
      canReadRulebook: false, canReadLesson: true, canAskQuestions: false,
    })
  })

  it('makes the rendered rulebook readable while teaching is still preparing', () => {
    expect(derivePlayerJourney(input({
      gameBound: true,
      discovery: 'review',
      importJob: importJob({
        stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'WAITING_FOR_DOCUMENT',
      }),
      documentProgress: { stage: 'READY', percentage: 100, processedPages: 16, totalPages: 16, complete: true },
    }))).toMatchObject({
      phase: 'DOCUMENT_PROCESSING', canReadRulebook: true, canReadLesson: false,
    })
  })

  it('exposes preparation failure as a precise safe retry', () => {
    const preparation = run('FAILED')
    preparation.run.lastErrorCode = 'TEACHING_PREPARATION_FAILED'
    expect(derivePlayerJourney(input({
      gameBound: true,
      discovery: 'review',
      importJob: importJob({
        stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'LAUNCHED',
        teachingPreparationRunId: 'preparation-1',
      }),
      preparationRun: preparation,
    }))).toMatchObject({
      phase: 'FAILED', state: 'failed', retryAction: 'PREPARE_TEACHING',
      errorCode: 'TEACHING_PREPARATION_FAILED',
    })
  })

  it('uses the server recovery action instead of guessing from a failed handoff', () => {
    const recovering = importJob({
      stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'FAILED',
      teachingErrorCode: 'TEACHING_RUN_FAILED', teachingNextAction: 'WAIT',
    })
    expect(derivePlayerJourney(input({ gameBound: true, importJob: recovering }))).toMatchObject({
      phase: 'TEACHING_PREPARATION_QUEUED', state: 'active', retryAction: null, errorCode: null,
    })

    expect(derivePlayerJourney(input({
      gameBound: true,
      importJob: { ...recovering, teachingNextAction: 'RETRY_TEACHING' },
    }))).toMatchObject({
      phase: 'FAILED', state: 'failed', retryAction: 'PREPARE_TEACHING',
      errorCode: 'TEACHING_RUN_FAILED',
    })
  })

  it('offers original-source retry only when the server recovery policy allows it', () => {
    const baseFailure = importJob({
      stage: 'FAILED', errorCode: 'INVALID_PDF_SOURCE',
      recovery: {
        state: 'FAILED', failureKind: 'INVALID_SOURCE', busy: false,
        canChooseAnotherSource: true, canUseLocalUpload: true,
        canRetryOriginalSource: false, canOpenSourceInBrowser: false,
      },
    })
    expect(derivePlayerJourney(input({ gameBound: true, importJob: baseFailure }))).toMatchObject({
      phase: 'FAILED', state: 'failed', retryAction: null,
    })
    expect(derivePlayerJourney(input({
      gameBound: true,
      importJob: {
        ...baseFailure,
        errorCode: 'SOURCE_UNAVAILABLE',
        recovery: {
          ...baseFailure.recovery!, failureKind: 'TEMPORARY_SOURCE', canRetryOriginalSource: true,
        },
      },
    }))).toMatchObject({
      phase: 'FAILED', state: 'failed', retryAction: 'IMPORT_RULEBOOK',
    })
  })

  it('makes the first published chapter readable while generation continues', () => {
    expect(derivePlayerJourney(input({
      gameBound: true,
      discovery: 'review',
      importJob: importJob({
        stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'LAUNCHED',
        teachingPreparationRunId: 'preparation-1',
      }),
      documentProgress: { stage: 'READY', percentage: 100, processedPages: 16, totalPages: 16, complete: true },
      preparationRun: run('COMPLETED'),
      plan: { id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Example', premise: 'Learn', sections: [
        { position: 1, title: 'Setup' }, { position: 2, title: 'Turns' },
      ] },
      teachingRun: run('LESSON_COMPOSITION'),
      lesson: { id: 'lesson-1', status: 'DRAFT_READY', sections: [{ position: 1, title: 'Setup' }] },
    }))).toMatchObject({
      phase: 'LESSON_READABLE', state: 'ready', canReadLesson: true, canAskQuestions: true,
      availableSections: 1, totalSections: 2,
    })
  })

  it('requires both a complete lesson and completed run for final completion', () => {
    const common = {
      gameBound: true,
      discovery: 'review' as const,
      importJob: importJob({
        stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'LAUNCHED',
        teachingPreparationRunId: 'preparation-1',
      }),
      documentProgress: { stage: 'READY', percentage: 100, processedPages: 16, totalPages: 16, complete: true },
      preparationRun: run('COMPLETED'),
      plan: { id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Example', premise: 'Learn', sections: [
        { position: 1, title: 'Setup' },
      ] },
      lesson: { id: 'lesson-1', status: 'COMPLETE' as const, sections: [{ position: 1, title: 'Setup' }] },
    }
    expect(derivePlayerJourney(input({ ...common, teachingRun: run('CRITIQUING') })).phase)
      .toBe('LESSON_READABLE')
    expect(derivePlayerJourney(input({ ...common, teachingRun: run('COMPLETED') }))).toMatchObject({
      phase: 'LESSON_COMPLETE', state: 'complete', progress: 100,
    })
  })

  it('keeps a published draft readable when later review ends degraded', () => {
    const teaching = run('DEGRADED')
    teaching.run.lastErrorCode = 'REVIEW_UNAVAILABLE'
    expect(derivePlayerJourney(input({
      gameBound: true,
      discovery: 'review',
      importJob: importJob({
        stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'LAUNCHED',
        teachingPreparationRunId: 'preparation-1',
      }),
      preparationRun: run('COMPLETED'),
      plan: { id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Example', premise: 'Learn', sections: [
        { position: 1, title: 'Setup' },
      ] },
      teachingRun: teaching,
      lesson: { id: 'lesson-1', status: 'DRAFT_READY', sections: [{ position: 1, title: 'Setup' }] },
    }))).toMatchObject({
      phase: 'LESSON_READABLE', state: 'ready', retryAction: 'GENERATE_LESSON',
      errorCode: 'REVIEW_UNAVAILABLE', canReadLesson: true,
    })
  })
})

describe('journey snapshot acceptance', () => {
  it('does not let an older active import overwrite a terminal handoff', () => {
    const terminal = importJob({
      stage: 'COMPLETED', teachingHandoffState: 'LAUNCHED', updatedAt: '2026-08-10T10:00:05Z',
    })
    const stale = importJob({
      stage: 'DOWNLOADING', teachingHandoffState: 'WAITING_FOR_DOCUMENT', updatedAt: '2026-08-10T10:00:03Z',
    })
    expect(acceptImportJob(terminal, stale)).toBe(terminal)
  })

  it('keeps the newest run revision and merges activity history', () => {
    const previous = {
      ...run('LESSON_COMPOSITION', 3),
      activities: [{ sequence: 1, operation: 'search', summary: 'Found rules', outcome: 'SUCCEEDED' }],
    }
    const incoming = {
      ...run('CRITIQUING', 4),
      activities: [{ sequence: 2, operation: 'review', summary: 'Reviewing', outcome: 'RUNNING' }],
    }
    expect(acceptJourneyRun(previous, incoming)).toMatchObject({
      run: { state: 'CRITIQUING', revision: 4 },
      activities: [{ sequence: 1 }, { sequence: 2 }],
    })
    expect(acceptJourneyRun(incoming, previous)).toEqual(incoming)
  })
})
