export type OfficialImportStage =
  | 'QUEUED'
  | 'CONNECTING'
  | 'DOWNLOADING'
  | 'COMPRESSING'
  | 'VERIFYING_FILE'
  | 'SAVING'
  | 'COMPLETED'
  | 'FAILED'

export type TeachingHandoffState =
  | 'NOT_REQUESTED'
  | 'WAITING_FOR_DOCUMENT'
  | 'LAUNCHING'
  | 'LAUNCHED'
  | 'FAILED'

export type OfficialImportRecoveryState = 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export type OfficialImportFailureKind =
  | 'NONE'
  | 'TEMPORARY_SOURCE'
  | 'BROWSER_HANDOFF'
  | 'INVALID_SOURCE'
  | 'CAPACITY'
  | 'INTERRUPTED'
  | 'OTHER'

export type TeachingRecoveryAction =
  | 'WAIT'
  | 'OPEN_PROGRESS'
  | 'RETRY_TEACHING'
  | 'RETRY_DOCUMENT'
  | 'NONE'

export interface OfficialImportRecovery {
  state: OfficialImportRecoveryState
  failureKind: OfficialImportFailureKind
  busy: boolean
  canChooseAnotherSource: boolean
  canUseLocalUpload: boolean
  canRetryOriginalSource: boolean
  canOpenSourceInBrowser: boolean
}

export interface PlayerJourneyImportJob {
  id: string
  stage: OfficialImportStage
  downloadedBytes: number
  totalBytes: number | null
  documentVersionId: string | null
  errorCode: string | null
  teachingHandoffState: TeachingHandoffState
  teachingPreparationRunId: string | null
  teachingErrorCode?: string | null
  teachingNextAction?: TeachingRecoveryAction
  recovery?: OfficialImportRecovery
  updatedAt?: string
}

export interface PlayerJourneyDocumentProgress {
  stage: string
  percentage: number
  processedPages: number
  totalPages: number
  complete: boolean
}

export interface PlayerJourneyRun {
  run: {
    id: string
    subjectId: string
    state: string
    revision?: number
    updatedAt?: string
    lastErrorCode: string | null
  }
  activities?: Array<{
    sequence: number
    operation: string
    summary: string
    outcome: string
  }>
}

export interface PlayerJourneyPlan {
  id: string
  documentVersionId: string
  gameTitle: string
  premise: string
  sections: Array<{ position: number; title: string; visualEvidenceRecommended?: boolean }>
}

export interface PlayerJourneyLesson {
  id: string
  status: 'COMPLETE' | 'DRAFT_READY' | 'INCOMPLETE'
  sections: Array<{ position: number; title: string }>
  createdAt?: string
}

export type PlayerJourneyPhase =
  | 'GAME_BINDING'
  | 'RULEBOOK_DISCOVERY'
  | 'SOURCE_REVIEW'
  | 'IMPORT_QUEUED'
  | 'IMPORT_CONNECTING'
  | 'IMPORT_DOWNLOADING'
  | 'IMPORT_COMPRESSING'
  | 'IMPORT_VERIFYING'
  | 'IMPORT_SAVING'
  | 'DOCUMENT_PROCESSING'
  | 'TEACHING_PREPARATION_QUEUED'
  | 'TEACHING_PREPARING'
  | 'LESSON_GENERATION_QUEUED'
  | 'LESSON_GENERATING'
  | 'LESSON_READABLE'
  | 'LESSON_COMPLETE'
  | 'FAILED'

export type PlayerJourneyRetryAction =
  | 'BIND_GAME'
  | 'DISCOVER_RULEBOOK'
  | 'IMPORT_RULEBOOK'
  | 'PREPARE_TEACHING'
  | 'GENERATE_LESSON'
  | null

export interface PlayerJourneyInput {
  gameBound: boolean
  discovery: 'idle' | 'loading' | 'review' | 'unavailable' | 'failed'
  importJob: PlayerJourneyImportJob | null
  documentProgress: PlayerJourneyDocumentProgress | null
  preparationRun: PlayerJourneyRun | null
  plan: PlayerJourneyPlan | null
  teachingRun: PlayerJourneyRun | null
  lesson: PlayerJourneyLesson | null
}

export interface PlayerJourneyProjection {
  phase: PlayerJourneyPhase
  state: 'waiting' | 'active' | 'ready' | 'complete' | 'failed'
  progress: number
  retryAction: PlayerJourneyRetryAction
  errorCode: string | null
  canReadRulebook: boolean
  canReadLesson: boolean
  canAskQuestions: boolean
  availableSections: number
  totalSections: number | null
  latestActivity: string | null
}

export function playerJourneyPollDelay(
  pollingWarning: boolean,
  waitingForFirstReadableSection: boolean,
) {
  if (pollingWarning) return 3_000
  if (waitingForFirstReadableSection) return 500
  return 1_250
}

const FAILED_RUN_STATES = new Set(['FAILED', 'INSUFFICIENT_EVIDENCE', 'DEGRADED'])

export function derivePlayerJourney(input: PlayerJourneyInput): PlayerJourneyProjection {
  const availableSections = input.lesson?.sections.length ?? 0
  const totalSections = input.plan?.sections.length ?? null
  const canReadLesson = Boolean(input.plan && availableSections > 0)
  const canReadRulebook = Boolean(input.importJob?.documentVersionId && (
    input.documentProgress?.complete
    || input.documentProgress?.stage === 'READY'
  ))
  const teachingState = input.teachingRun?.run.state ?? null
  const latestActivity = input.teachingRun?.activities?.at(-1)?.summary
    ?? input.preparationRun?.activities?.at(-1)?.summary
    ?? null

  if (canReadLesson) {
    const fullyComplete = input.lesson?.status === 'COMPLETE' && teachingState === 'COMPLETED'
    const teachingRunStopped = FAILED_RUN_STATES.has(teachingState ?? '')
    const retryTeaching = !fullyComplete && (
      teachingRunStopped || input.importJob?.teachingNextAction === 'RETRY_TEACHING'
    )
    return projection({
      phase: fullyComplete ? 'LESSON_COMPLETE' : 'LESSON_READABLE',
      state: fullyComplete ? 'complete' : 'ready',
      progress: fullyComplete ? 100 : lessonProgress(availableSections, totalSections),
      retryAction: retryTeaching ? 'GENERATE_LESSON' : null,
      errorCode: retryTeaching
        ? input.teachingRun?.run.lastErrorCode
          ?? input.importJob?.teachingErrorCode
          ?? teachingState
          ?? 'TEACHING_RUN_FAILED'
        : null,
      canReadRulebook,
      canReadLesson: true,
      availableSections,
      totalSections,
      latestActivity,
    })
  }

  if (input.importJob?.stage === 'FAILED') {
    return failed(
      input.importJob.recovery?.canRetryOriginalSource ? 'IMPORT_RULEBOOK' : null,
      input.importJob.errorCode,
      availableSections,
      totalSections,
      latestActivity,
      canReadRulebook,
    )
  }
  if (input.importJob?.teachingHandoffState === 'FAILED') {
    if (input.importJob.teachingNextAction === 'WAIT') {
      return projection({
        phase: 'TEACHING_PREPARATION_QUEUED', state: 'active', progress: 76, retryAction: null,
        errorCode: null, canReadRulebook, canReadLesson: false, availableSections, totalSections, latestActivity,
      })
    }
    const retryAction = input.importJob.teachingNextAction === 'RETRY_DOCUMENT'
      ? 'IMPORT_RULEBOOK'
      : input.importJob.teachingNextAction === 'NONE' || input.importJob.teachingNextAction === 'OPEN_PROGRESS'
        ? null
        : 'PREPARE_TEACHING'
    return failed(
      retryAction,
      input.importJob.teachingErrorCode ?? 'TEACHING_HANDOFF_FAILED',
      availableSections,
      totalSections,
      latestActivity,
      canReadRulebook,
    )
  }
  if (input.documentProgress?.stage === 'FAILED') {
    return failed('IMPORT_RULEBOOK', 'DOCUMENT_PROCESSING_FAILED', availableSections, totalSections, latestActivity, canReadRulebook)
  }
  if (input.preparationRun && FAILED_RUN_STATES.has(input.preparationRun.run.state)) {
    return failed(
      'PREPARE_TEACHING',
      input.preparationRun.run.lastErrorCode ?? input.preparationRun.run.state,
      availableSections,
      totalSections,
      latestActivity,
      canReadRulebook,
    )
  }
  if (input.teachingRun && FAILED_RUN_STATES.has(input.teachingRun.run.state)) {
    return failed(
      'GENERATE_LESSON',
      input.teachingRun.run.lastErrorCode ?? input.teachingRun.run.state,
      availableSections,
      totalSections,
      latestActivity,
      canReadRulebook,
    )
  }

  if (!input.gameBound) {
    return projection({
      phase: 'GAME_BINDING', state: 'active', progress: 5, retryAction: null,
      errorCode: null, canReadRulebook, canReadLesson: false, availableSections, totalSections, latestActivity,
    })
  }
  if (!input.importJob) {
    if (input.discovery === 'failed') {
      return failed('DISCOVER_RULEBOOK', 'RULEBOOK_DISCOVERY_FAILED', availableSections, totalSections, latestActivity, canReadRulebook)
    }
    const phase = input.discovery === 'review' || input.discovery === 'unavailable'
      ? 'SOURCE_REVIEW'
      : 'RULEBOOK_DISCOVERY'
    return projection({
      phase,
      state: input.discovery === 'review' || input.discovery === 'unavailable' ? 'waiting' : 'active',
      progress: phase === 'SOURCE_REVIEW' ? 18 : 12,
      retryAction: input.discovery === 'unavailable' ? 'DISCOVER_RULEBOOK' : null,
      errorCode: null,
      canReadRulebook,
      canReadLesson: false,
      availableSections,
      totalSections,
      latestActivity,
    })
  }

  if (input.importJob.stage !== 'COMPLETED') {
    const [phase, progress] = importProjection(input.importJob)
    return projection({
      phase, state: 'active', progress, retryAction: null, errorCode: null,
      canReadRulebook, canReadLesson: false, availableSections, totalSections, latestActivity,
    })
  }

  const documentStillProcessing = input.importJob.teachingHandoffState === 'WAITING_FOR_DOCUMENT'
    || input.documentProgress !== null && !input.documentProgress.complete
  if (documentStillProcessing) {
    return projection({
      phase: 'DOCUMENT_PROCESSING',
      state: 'active',
      progress: Math.max(62, Math.min(75, 62 + Math.round((input.documentProgress?.percentage ?? 0) * 0.13))),
      retryAction: null,
      errorCode: null,
      canReadRulebook,
      canReadLesson: false,
      availableSections,
      totalSections,
      latestActivity,
    })
  }

  if (!input.preparationRun || input.importJob.teachingHandoffState === 'LAUNCHING') {
    return projection({
      phase: 'TEACHING_PREPARATION_QUEUED', state: 'active', progress: 76, retryAction: null,
      errorCode: null, canReadRulebook, canReadLesson: false, availableSections, totalSections, latestActivity,
    })
  }
  if (input.preparationRun.run.state !== 'COMPLETED') {
    const progress = input.preparationRun.run.state === 'LESSON_PLANNING'
      ? 84
      : input.preparationRun.run.state === 'DOCUMENT_READINESS' ? 80 : 77
    return projection({
      phase: 'TEACHING_PREPARING', state: 'active', progress, retryAction: null,
      errorCode: null, canReadRulebook, canReadLesson: false, availableSections, totalSections, latestActivity,
    })
  }
  if (!input.plan || !input.teachingRun) {
    return projection({
      phase: 'LESSON_GENERATION_QUEUED', state: 'active', progress: 87, retryAction: null,
      errorCode: null, canReadRulebook, canReadLesson: false, availableSections, totalSections, latestActivity,
    })
  }

  return projection({
    phase: 'LESSON_GENERATING', state: 'active',
    progress: lessonProgress(availableSections, totalSections), retryAction: null,
    errorCode: null, canReadRulebook, canReadLesson: false, availableSections, totalSections, latestActivity,
  })
}

function projection(input: Omit<PlayerJourneyProjection, 'canAskQuestions'>): PlayerJourneyProjection {
  return { ...input, canAskQuestions: input.canReadLesson && input.canReadRulebook }
}

function failed(
  retryAction: PlayerJourneyRetryAction,
  errorCode: string | null,
  availableSections: number,
  totalSections: number | null,
  latestActivity: string | null,
  canReadRulebook: boolean,
) {
  return projection({
    phase: 'FAILED', state: 'failed', progress: 0, retryAction,
    errorCode: errorCode ?? 'PLAYER_JOURNEY_FAILED', canReadRulebook, canReadLesson: false,
    availableSections, totalSections, latestActivity,
  })
}

function importProjection(job: PlayerJourneyImportJob): [PlayerJourneyPhase, number] {
  if (job.stage === 'QUEUED') return ['IMPORT_QUEUED', 24]
  if (job.stage === 'CONNECTING') return ['IMPORT_CONNECTING', 29]
  if (job.stage === 'DOWNLOADING') {
    const downloadRatio = job.totalBytes && job.totalBytes > 0
      ? Math.min(1, Math.max(0, job.downloadedBytes / job.totalBytes))
      : 0
    return ['IMPORT_DOWNLOADING', 32 + Math.round(downloadRatio * 16)]
  }
  if (job.stage === 'COMPRESSING') return ['IMPORT_COMPRESSING', 51]
  if (job.stage === 'VERIFYING_FILE') return ['IMPORT_VERIFYING', 55]
  return ['IMPORT_SAVING', 59]
}

function lessonProgress(availableSections: number, totalSections: number | null) {
  if (!totalSections || totalSections < 1) return availableSections > 0 ? 94 : 89
  return Math.min(99, 88 + Math.round(Math.min(1, availableSections / totalSections) * 11))
}

export function acceptImportJob(
  previous: PlayerJourneyImportJob | null,
  incoming: PlayerJourneyImportJob,
) {
  if (!previous || previous.id !== incoming.id) return incoming
  if (isTerminalImport(previous) && !isTerminalImport(incoming)) return previous
  const previousTime = Date.parse(previous.updatedAt ?? '')
  const incomingTime = Date.parse(incoming.updatedAt ?? '')
  if (!Number.isNaN(previousTime) && !Number.isNaN(incomingTime) && previousTime > incomingTime) return previous
  return incoming
}

export function acceptJourneyRun(previous: PlayerJourneyRun | null, incoming: PlayerJourneyRun) {
  if (!previous || previous.run.id !== incoming.run.id) return normalizeJourneyRun(incoming)
  const previousRevision = previous.run.revision ?? 0
  const incomingRevision = incoming.run.revision ?? 0
  if (previousRevision > incomingRevision) return previous
  if (isTerminalRun(previous.run.state) && !isTerminalRun(incoming.run.state)) return previous
  return {
    ...incoming,
    activities: normalizeJourneyActivities([
      ...(previous.activities ?? []),
      ...(incoming.activities ?? []),
    ]),
  }
}

function normalizeJourneyRun(run: PlayerJourneyRun): PlayerJourneyRun {
  return {
    ...run,
    activities: run.activities ? normalizeJourneyActivities(run.activities) : run.activities,
  }
}

function normalizeJourneyActivities(activities: NonNullable<PlayerJourneyRun['activities']>) {
  return Array.from(new Map(
    activities.map(activity => [activity.sequence, activity]),
  ).values()).sort((left, right) => left.sequence - right.sequence)
}

function isTerminalImport(job: PlayerJourneyImportJob) {
  return job.stage === 'FAILED'
    || job.stage === 'COMPLETED' && ['LAUNCHED', 'FAILED', 'NOT_REQUESTED'].includes(job.teachingHandoffState)
}

function isTerminalRun(state: string) {
  return state === 'COMPLETED' || FAILED_RUN_STATES.has(state)
}
