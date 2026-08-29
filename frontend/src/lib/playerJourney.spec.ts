import { describe, expect, it } from 'vitest'

import {
  acceptImportJob,
  acceptJourneyRun,
  derivePlayerJourney,
  playerJourneyPollDelay,
  typedFailurePolicy,
  type PlayerJourneyImportJob,
  type PlayerJourneyRun,
} from './playerJourney'

type PlayerJourneyInput = Parameters<typeof derivePlayerJourney>[0]

describe('playerJourneyPollDelay', () => {
  it('checks quickly only while a plan is waiting for its first readable chapter', () => {
    expect(playerJourneyPollDelay(false, false)).toBe(1_250)
    expect(playerJourneyPollDelay(false, true)).toBe(500)
  })

  it('keeps transient failures on the slower retry cadence', () => {
    expect(playerJourneyPollDelay(true, true)).toBe(3_000)
  })
})

describe('typedFailurePolicy', () => {
  it.each([
    ['TEACHING_PREPARATION_INVALID_PLAN', 'external-repair', 'manual-repair', null],
    ['TEACHING_RECOVERY_EXHAUSTED', 'preserved-stop', 'retry-step', 'PREPARE_TEACHING'],
    ['INSUFFICIENT_EVIDENCE', 'preserved-stop', null, null],
    ['TEACHING_PREPARATION_QUEUE_TIMEOUT', 'preserved-stop', 'retry-step', 'PREPARE_TEACHING'],
    ['TEACHING_MODEL_PROVIDER_FAILED', 'preserved-stop', 'restart-from-completed', 'GENERATE_LESSON'],
  ] as const)('owns retry authorization for %s', (
    errorCode,
    failureClassification,
    failureRecovery,
    retryAction,
  ) => {
    const requestedRetryAction = errorCode === 'TEACHING_MODEL_PROVIDER_FAILED'
      ? 'GENERATE_LESSON'
      : 'PREPARE_TEACHING'

    expect(typedFailurePolicy(errorCode, requestedRetryAction, true)).toEqual({
      errorCode,
      retryAction,
      failureClassification,
      failureRecovery,
    })
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
    run: { id: 'run-1', subjectId: 'subject-1', state, revision, updatedAt: `2026-08-10T10:00:0${revision}Z`, lastErrorCode: null },
    activities: [],
  }
}

describe('derivePlayerJourney', () => {
  it('keeps source review distinct from work that has actually started', () => {
    expect(derivePlayerJourney(input({ gameBound: true, discovery: 'review' }))).toMatchObject({
      phase: 'SOURCE_REVIEW', state: 'waiting', progress: null,
    })
  })

  it('reports byte-backed download progress without calling it complete', () => {
    expect(derivePlayerJourney(input({
      gameBound: true,
      discovery: 'review',
      importJob: importJob({ stage: 'DOWNLOADING', downloadedBytes: 50, totalBytes: 100 }),
    }))).toMatchObject({ phase: 'IMPORT_DOWNLOADING', state: 'active', progress: 50 })
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

  it('counts only cited chapters as readable and not evidence-insufficient placeholders', () => {
    const journey = {
      gameBound: true,
      importJob: importJob({
        stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'LAUNCHED',
        teachingPreparationRunId: 'preparation-1',
      }),
      plan: { id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Example', premise: 'Learn', sections: [
        { position: 1, title: 'Setup' },
      ] },
    }
    expect(derivePlayerJourney(input({
      ...journey,
      lesson: { id: 'lesson-1', status: 'DRAFT_READY', sections: [
        { position: 1, title: 'Setup', evidenceStatus: 'SUPPORTED' },
      ] },
    }))).toMatchObject({
      canReadRulebook: false, canReadLesson: true, canAskQuestions: false,
    })
    expect(derivePlayerJourney(input({
      ...journey,
      lesson: { id: 'lesson-1', status: 'INCOMPLETE', sections: [
        { position: 1, title: 'Setup', evidenceStatus: 'INSUFFICIENT_EVIDENCE' },
      ] },
    }))).toMatchObject({
      canReadRulebook: false, canReadLesson: false, canAskQuestions: false, availableSections: 0,
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
      failureClassification: 'preserved-stop', failureRecovery: 'retry-step',
    })
  })

  it.each([
    'TEACHING_PREPARATION_PLAN_RESOLUTION_FAILED',
    'TEACHING_PREPARATION_FIRST_SECTION_STARTUP_FAILED',
  ])('preserves the ready rulebook and retries only the failed preparation phase for %s', (errorCode) => {
    const preparation = run('FAILED')
    preparation.run.lastErrorCode = errorCode

    expect(derivePlayerJourney(input({
      gameBound: true,
      importJob: importJob({
        stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'LAUNCHED',
        teachingPreparationRunId: 'preparation-1',
      }),
      documentProgress: { stage: 'READY', percentage: 100, processedPages: 16, totalPages: 16, complete: true },
      preparationRun: preparation,
    }))).toMatchObject({
      phase: 'FAILED', state: 'failed', retryAction: 'PREPARE_TEACHING',
      errorCode, canReadRulebook: true, canReadLesson: false,
      failureClassification: 'preserved-stop', failureRecovery: 'retry-step',
    })
  })

  it('recovers a historical generic teaching failure with a fresh preparation run', () => {
    const preparation = run('FAILED')
    preparation.run.lastErrorCode = 'TEACHING_WORKFLOW_FAILED'
    const teaching = run('FAILED')
    teaching.run.lastErrorCode = 'TEACHING_WORKFLOW_FAILED'

    expect(derivePlayerJourney(input({
      gameBound: true,
      importJob: importJob({
        stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'LAUNCHED',
        teachingPreparationRunId: 'preparation-1',
      }),
      documentProgress: { stage: 'READY', percentage: 100, processedPages: 16, totalPages: 16, complete: true },
      preparationRun: preparation,
      teachingRun: teaching,
    }))).toMatchObject({
      phase: 'FAILED', state: 'failed', retryAction: 'PREPARE_TEACHING',
      errorCode: 'TEACHING_WORKFLOW_FAILED', canReadRulebook: true, canReadLesson: false,
      failureClassification: 'preserved-stop', failureRecovery: 'restart-from-completed',
    })
  })

  it('keeps the bounded restart policy when preparation carries a teaching timeout', () => {
    const preparation = run('FAILED')
    preparation.run.lastErrorCode = 'AGENT_TIMEOUT'

    expect(derivePlayerJourney(input({
      gameBound: true,
      importJob: importJob({
        stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'LAUNCHED',
        teachingPreparationRunId: 'preparation-1',
      }),
      documentProgress: { stage: 'READY', percentage: 100, processedPages: 16, totalPages: 16, complete: true },
      preparationRun: preparation,
    }))).toMatchObject({
      phase: 'FAILED', state: 'failed', retryAction: 'PREPARE_TEACHING',
      errorCode: 'AGENT_TIMEOUT', canReadRulebook: true, canReadLesson: false,
      failureClassification: 'preserved-stop', failureRecovery: 'restart-from-completed',
    })
  })

  it('rejects a server-suggested blind retry for a typed invalid teaching plan', () => {
    expect(derivePlayerJourney(input({
      gameBound: true,
      importJob: importJob({
        stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'FAILED',
        teachingErrorCode: 'TEACHING_PREPARATION_INVALID_PLAN', teachingNextAction: 'RETRY_TEACHING',
      }),
    }))).toMatchObject({
      phase: 'FAILED', state: 'failed', retryAction: null,
      errorCode: 'TEACHING_PREPARATION_INVALID_PLAN',
      failureClassification: 'external-repair', failureRecovery: 'manual-repair',
    })
  })

  it('requires storage repair without offering another teaching run', () => {
    const preparation = run('FAILED')
    preparation.run.lastErrorCode = 'TEACHING_PREPARATION_STORAGE_FAILED'

    expect(derivePlayerJourney(input({
      gameBound: true,
      importJob: importJob({
        stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'FAILED',
        teachingErrorCode: 'TEACHING_PREPARATION_STORAGE_FAILED', teachingNextAction: 'RETRY_TEACHING',
      }),
      preparationRun: preparation,
    }))).toMatchObject({
      phase: 'FAILED', state: 'failed', retryAction: null,
      errorCode: 'TEACHING_PREPARATION_STORAGE_FAILED',
      failureClassification: 'external-repair', failureRecovery: 'manual-repair',
    })
  })

  it('keeps deterministic invalid teaching input in manual repair even when the server suggests retry', () => {
    expect(derivePlayerJourney(input({
      gameBound: true,
      importJob: importJob({
        stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'FAILED',
        teachingErrorCode: 'TEACHING_PLAN_INVALID', teachingNextAction: 'RETRY_TEACHING',
      }),
    }))).toMatchObject({
      phase: 'FAILED', state: 'failed', retryAction: null,
      errorCode: 'TEACHING_PLAN_INVALID',
      failureClassification: 'external-repair', failureRecovery: 'manual-repair',
    })
  })

  it('classifies a user cancellation as a preserved stop with an explicit new run', () => {
    const cancelled = run('FAILED')
    cancelled.run.lastErrorCode = 'AGENT_CANCELLED'
    expect(derivePlayerJourney(input({
      gameBound: true,
      importJob: importJob({
        stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'LAUNCHED',
      }),
      plan: { id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Example', premise: 'Learn', sections: [
        { position: 1, title: 'Setup' }, { position: 2, title: 'Turns' },
      ] },
      teachingRun: cancelled,
      lesson: { id: 'lesson-1', status: 'DRAFT_READY', sections: [{ position: 1, title: 'Setup', evidenceStatus: 'SUPPORTED' }] },
    }))).toMatchObject({
      phase: 'LESSON_READABLE', state: 'ready', retryAction: 'GENERATE_LESSON',
      errorCode: 'AGENT_CANCELLED', failureClassification: 'preserved-stop',
      failureRecovery: 'restart-from-completed', canReadLesson: true,
    })
  })

  it('keeps the first cited section readable when continuation admission times out', () => {
    const timedOut = run('FAILED')
    timedOut.run.lastErrorCode = 'TEACHING_CONTINUATION_QUEUE_TIMEOUT'
    expect(derivePlayerJourney(input({
      gameBound: true,
      importJob: importJob({
        stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'LAUNCHED',
      }),
      plan: { id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Example', premise: 'Learn', sections: [
        { position: 1, title: 'Setup' }, { position: 2, title: 'Turns' },
      ] },
      teachingRun: timedOut,
      lesson: { id: 'lesson-1', status: 'DRAFT_READY', sections: [{ position: 1, title: 'Setup', evidenceStatus: 'SUPPORTED' }] },
    }))).toMatchObject({
      phase: 'LESSON_READABLE', state: 'ready', retryAction: 'GENERATE_LESSON',
      errorCode: 'TEACHING_CONTINUATION_QUEUE_TIMEOUT', failureClassification: 'preserved-stop',
      failureRecovery: 'retry-step', canReadLesson: true,
    })
  })

  it.each([
    'TEACHING_PLAN_RETRIEVAL_FAILED',
    'TEACHING_EVIDENCE_RETRIEVAL_FAILED',
    'TEACHING_MODEL_PROVIDER_FAILED',
    'TEACHING_PERSISTENCE_FAILED',
    'TEACHING_CONTINUATION_FAILED',
    'TEACHING_COMPLETION_FAILED',
  ])('restarts %s from the already published chapter', (errorCode) => {
    const stopped = run('FAILED')
    stopped.run.lastErrorCode = errorCode

    expect(derivePlayerJourney(input({
      gameBound: true,
      importJob: importJob({
        stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'LAUNCHED',
      }),
      documentProgress: { stage: 'READY', percentage: 100, processedPages: 16, totalPages: 16, complete: true },
      plan: { id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Example', premise: 'Learn', sections: [
        { position: 1, title: 'Setup' }, { position: 2, title: 'Turns' },
      ] },
      teachingRun: stopped,
      lesson: { id: 'lesson-1', status: 'DRAFT_READY', sections: [{ position: 1, title: 'Setup', evidenceStatus: 'SUPPORTED' }] },
    }))).toMatchObject({
      phase: 'LESSON_READABLE', state: 'ready', retryAction: 'GENERATE_LESSON',
      errorCode, canReadRulebook: true, canReadLesson: true, availableSections: 1,
      failureClassification: 'preserved-stop', failureRecovery: 'restart-from-completed',
    })
  })

  it('makes a historical generic teaching workflow failure safely restartable', () => {
    const failed = run('FAILED')
    failed.run.lastErrorCode = 'TEACHING_WORKFLOW_FAILED'
    expect(derivePlayerJourney(input({
      gameBound: true,
      importJob: importJob({
        stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'LAUNCHED',
      }),
      teachingRun: failed,
    }))).toMatchObject({
      phase: 'FAILED', state: 'failed', retryAction: 'GENERATE_LESSON',
      errorCode: 'TEACHING_WORKFLOW_FAILED',
      failureClassification: 'preserved-stop', failureRecovery: 'restart-from-completed',
    })
  })

  it('keeps an active preparation run active when individual page attempts fail', () => {
    const preparation = run('LESSON_PLANNING')
    preparation.activities = Array.from({ length: 8 }, (_, index) => ({
      sequence: index + 1,
      operation: `inspectTeachingVisualPage|${index + 1}|20`,
      summary: `Page ${index + 1} attempt did not complete`,
      outcome: index % 2 === 0 ? 'FAILED' : 'REJECTED',
    }))

    expect(derivePlayerJourney(input({
      gameBound: true,
      discovery: 'review',
      importJob: importJob({
        stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'LAUNCHED',
        teachingPreparationRunId: 'run-1',
      }),
      documentProgress: { stage: 'READY', percentage: 100, processedPages: 20, totalPages: 20, complete: true },
      preparationRun: preparation,
    }))).toMatchObject({
      phase: 'TEACHING_PREPARING', state: 'active', retryAction: null, errorCode: null,
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
      lesson: { id: 'lesson-1', status: 'DRAFT_READY', sections: [{ position: 1, title: 'Setup', evidenceStatus: 'SUPPORTED' }] },
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
      lesson: { id: 'lesson-1', status: 'COMPLETE' as const, sections: [{ position: 1, title: 'Setup', evidenceStatus: 'SUPPORTED' as const }] },
    }
    expect(derivePlayerJourney(input({ ...common, teachingRun: run('CRITIQUING') })).phase)
      .toBe('LESSON_READABLE')
    expect(derivePlayerJourney(input({ ...common, teachingRun: run('COMPLETED') }))).toMatchObject({
      phase: 'LESSON_COMPLETE', state: 'complete', progress: 100,
    })
  })

  it('keeps a completed task complete even when its activity history contains failed attempts', () => {
    const teaching = run('COMPLETED')
    teaching.activities = [
      {
        sequence: 1,
        operation: 'inspectTeachingVisualPage|7|20',
        summary: 'First page attempt failed',
        outcome: 'FAILED',
      },
      {
        sequence: 2,
        operation: 'inspectTeachingVisualRetry|7|20',
        summary: 'Page retry completed',
        outcome: 'SUCCEEDED',
      },
    ]

    expect(derivePlayerJourney(input({
      gameBound: true,
      importJob: importJob({
        stage: 'COMPLETED', documentVersionId: 'version-1', teachingHandoffState: 'LAUNCHED',
        teachingPreparationRunId: 'preparation-1',
      }),
      preparationRun: run('COMPLETED'),
      plan: { id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Example', premise: 'Learn', sections: [
        { position: 1, title: 'Setup' },
      ] },
      teachingRun: teaching,
      lesson: { id: 'lesson-1', status: 'COMPLETE', sections: [{ position: 1, title: 'Setup', evidenceStatus: 'SUPPORTED' }] },
    }))).toMatchObject({
      phase: 'LESSON_COMPLETE', state: 'complete', retryAction: null, errorCode: null,
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
        teachingPreparationRunId: 'preparation-1', teachingNextAction: 'OPEN_PROGRESS',
      }),
      preparationRun: run('COMPLETED'),
      plan: { id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'Example', premise: 'Learn', sections: [
        { position: 1, title: 'Setup' },
      ] },
      teachingRun: teaching,
      lesson: { id: 'lesson-1', status: 'DRAFT_READY', sections: [{ position: 1, title: 'Setup', evidenceStatus: 'SUPPORTED' }] },
    }))).toMatchObject({
      phase: 'LESSON_READABLE', state: 'ready', retryAction: null,
      errorCode: 'REVIEW_UNAVAILABLE', canReadLesson: true,
      failureClassification: 'local-degradation', failureRecovery: null,
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
    const previous: PlayerJourneyRun = {
      ...run('LESSON_COMPOSITION', 3),
      activities: [{ sequence: 1, operation: 'search', summary: 'Found rules', outcome: 'SUCCEEDED' }],
    }
    const incoming: PlayerJourneyRun = {
      ...run('CRITIQUING', 4),
      activities: [{ sequence: 2, operation: 'review', summary: 'Reviewing', outcome: 'RUNNING' }],
    }
    expect(acceptJourneyRun(previous, incoming)).toMatchObject({
      run: { state: 'CRITIQUING', revision: 4 },
      activities: [{ sequence: 1 }, { sequence: 2 }],
    })
    expect(acceptJourneyRun(incoming, previous)).toEqual(incoming)
  })

  it('normalizes first and replacement run activities with the last incoming sequence winning', () => {
    const first: PlayerJourneyRun = {
      ...run('LESSON_COMPOSITION'),
      activities: [
        { sequence: 3, operation: 'compose', summary: 'Composing', outcome: 'RUNNING' },
        { sequence: 1, operation: 'search', summary: 'Searching', outcome: 'RUNNING' },
        { sequence: 1, operation: 'search', summary: 'Found', outcome: 'SUCCEEDED' },
      ],
    }
    expect(acceptJourneyRun(null, first).activities).toMatchObject([
      { sequence: 1, summary: 'Found', outcome: 'SUCCEEDED' },
      { sequence: 3, summary: 'Composing', outcome: 'RUNNING' },
    ])

    const replacement: PlayerJourneyRun = {
      ...run('LESSON_PLANNING'),
      run: { ...run('LESSON_PLANNING').run, id: 'run-2' },
      activities: [
        { sequence: 5, operation: 'outline', summary: 'Planning', outcome: 'RUNNING' },
        { sequence: 2, operation: 'read', summary: 'Reading', outcome: 'RUNNING' },
        { sequence: 2, operation: 'read', summary: 'Read', outcome: 'SUCCEEDED' },
      ],
    }
    expect(acceptJourneyRun(first, replacement).activities).toMatchObject([
      { sequence: 2, summary: 'Read', outcome: 'SUCCEEDED' },
      { sequence: 5, summary: 'Planning', outcome: 'RUNNING' },
    ])
  })

  it('normalizes an unrecognized API activity outcome to explicit UNKNOWN', () => {
    const incoming = {
      ...run('LESSON_PLANNING'),
      activities: [{
        sequence: 1,
        operation: 'inspectTeachingVisualPage|4|10',
        summary: 'Provider added a new outcome',
        outcome: 'PAUSED_BY_PROVIDER',
      }],
    } as unknown as PlayerJourneyRun

    expect(acceptJourneyRun(null, incoming).activities).toEqual([{
      sequence: 1,
      operation: 'inspectTeachingVisualPage|4|10',
      summary: 'Provider added a new outcome',
      outcome: 'UNKNOWN',
    }])
  })
})
