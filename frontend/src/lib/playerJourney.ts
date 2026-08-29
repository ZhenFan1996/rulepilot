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
    outcome: PlayerJourneyActivityOutcome
  }>
}

export type PlayerJourneyActivityOutcome =
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'REJECTED'
  | 'UNKNOWN'

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
  sections: Array<{
    position: number
    title: string
    evidenceStatus: 'SUPPORTED' | 'CITED_DRAFT' | 'INSUFFICIENT_EVIDENCE'
  }>
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

export type PlayerJourneyFailureClassification =
  | 'local-degradation'
  | 'preserved-stop'
  | 'external-repair'

export type PlayerJourneyFailureRecovery =
  | 'retry-step'
  | 'restart-from-completed'
  | 'choose-source'
  | 'manual-repair'
  | null

interface PlayerJourneyInput {
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
  progress: number | null
  retryAction: PlayerJourneyRetryAction
  errorCode: string | null
  failureClassification: PlayerJourneyFailureClassification | null
  failureRecovery: PlayerJourneyFailureRecovery
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

const FAILED_RUN_STATES = new Set(['FAILED', 'INSUFFICIENT_EVIDENCE', 'DEGRADED', 'CANCELLED'])
const KNOWN_ACTIVITY_OUTCOMES = new Set<PlayerJourneyActivityOutcome>([
  'RUNNING', 'SUCCEEDED', 'FAILED', 'REJECTED',
])

const LOCAL_DEGRADATION_CODES = new Set([
  'DEGRADED',
  'REVIEW_UNAVAILABLE',
  'VISUAL_ENRICHMENT_FAILED',
  'LOCALIZATION_FAILED',
])

const PRESERVED_WITHOUT_RETRY_CODES = new Set([
  'INSUFFICIENT_EVIDENCE',
])

const RESTART_FROM_COMPLETED_CODES = new Set([
  'AGENT_CANCELLED',
  'AGENT_STEP_BUDGET',
  'AGENT_TOOL_BUDGET',
  'AGENT_MODEL_BUDGET',
  'AGENT_TOKEN_BUDGET',
  'AGENT_TIMEOUT',
  'APPLICATION_RESTARTED',
  'TEACHING_PLAN_RETRIEVAL_FAILED',
  'TEACHING_EVIDENCE_RETRIEVAL_FAILED',
  'TEACHING_MODEL_PROVIDER_FAILED',
  'TEACHING_PERSISTENCE_FAILED',
  'TEACHING_CONTINUATION_FAILED',
  'TEACHING_COMPLETION_FAILED',
  // Older runs used one generic code for all of these boundaries. A fresh run is still safe because published
  // chapters are immutable progress inputs, so legacy history must not strand the player in manual repair.
  'TEACHING_WORKFLOW_FAILED',
])

const SAFE_RETRY_CODES = new Set([
  'RULEBOOK_DISCOVERY_FAILED',
  'SOURCE_UNAVAILABLE',
  'IMPORT_QUEUE_FULL',
  'DOCUMENT_PROCESSING_FAILED',
  'TEACHING_PREPARATION_FAILED',
  'TEACHING_PREPARATION_PLAN_RESOLUTION_FAILED',
  'TEACHING_PREPARATION_FIRST_SECTION_STARTUP_FAILED',
  'TEACHING_PREPARATION_QUEUE_FULL',
  'TEACHING_PREPARATION_QUEUE_TIMEOUT',
  'TEACHING_PREPARATION_WORKER_ADMISSION_FAILED',
  'TEACHING_HANDOFF_LAUNCH_FAILED',
  'TEACHING_QUEUE_FULL',
  'TEACHING_QUEUE_TIMEOUT',
  'TEACHING_WORKER_ADMISSION_FAILED',
  'TEACHING_CONTINUATION_QUEUE_FULL',
  'TEACHING_CONTINUATION_QUEUE_TIMEOUT',
  'TEACHING_CONTINUATION_ADMISSION_FAILED',
])

const EXTERNAL_REPAIR_CODES = new Set([
  'INVALID_PDF_SOURCE',
  'SOURCE_BROWSER_REQUIRED',
  'TEACHING_PLAN_INVALID',
  'TEACHING_PREPARATION_INVALID_PLAN',
  'TEACHING_PREPARATION_STORAGE_FAILED',
  'TEACHING_HANDOFF_INVALID',
])

export interface PlayerJourneyFailurePolicy {
  errorCode: string
  retryAction: PlayerJourneyRetryAction
  failureClassification: PlayerJourneyFailureClassification
  failureRecovery: PlayerJourneyFailureRecovery
}

export interface PlayerJourneyFailurePresentation {
  title: string
  detail: string
  actionLabel: string | null
}

export function derivePlayerJourney(input: PlayerJourneyInput): PlayerJourneyProjection {
  const availableSections = input.lesson?.sections.filter(section => (
    section.evidenceStatus === 'SUPPORTED' || section.evidenceStatus === 'CITED_DRAFT'
  )).length ?? 0
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
    const serverRetryTeaching = input.importJob?.teachingNextAction === 'RETRY_TEACHING'
    const failurePolicy = fullyComplete
      ? null
      : teachingRunStopped && input.teachingRun
        ? runFailurePolicy(input.teachingRun, 'GENERATE_LESSON')
        : serverRetryTeaching
          ? typedFailurePolicy(
              input.importJob?.teachingErrorCode ?? 'TEACHING_RUN_FAILED',
              'GENERATE_LESSON',
              true,
            )
          : null
    return projection({
      phase: fullyComplete ? 'LESSON_COMPLETE' : 'LESSON_READABLE',
      state: fullyComplete ? 'complete' : 'ready',
      progress: fullyComplete ? 100 : lessonProgress(availableSections, totalSections),
      retryAction: failurePolicy?.retryAction ?? null,
      errorCode: failurePolicy?.errorCode ?? null,
      failureClassification: failurePolicy?.failureClassification,
      failureRecovery: failurePolicy?.failureRecovery,
      canReadRulebook,
      canReadLesson: true,
      availableSections,
      totalSections,
      latestActivity,
    })
  }

  if (input.importJob?.stage === 'FAILED') {
    return failed(
      importFailurePolicy(input.importJob),
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
    const requestedRetryAction = input.importJob.teachingNextAction === 'RETRY_DOCUMENT'
      ? 'IMPORT_RULEBOOK'
      : input.importJob.teachingNextAction === 'NONE' || input.importJob.teachingNextAction === 'OPEN_PROGRESS'
        ? null
        : 'PREPARE_TEACHING'
    return failed(
      typedFailurePolicy(
        input.importJob.teachingErrorCode ?? 'TEACHING_HANDOFF_FAILED',
        requestedRetryAction,
        requestedRetryAction !== null,
      ),
      availableSections,
      totalSections,
      latestActivity,
      canReadRulebook,
    )
  }
  if (input.documentProgress?.stage === 'FAILED') {
    return failed(
      typedFailurePolicy('DOCUMENT_PROCESSING_FAILED', 'IMPORT_RULEBOOK', true),
      availableSections,
      totalSections,
      latestActivity,
      canReadRulebook,
    )
  }
  if (input.preparationRun && FAILED_RUN_STATES.has(input.preparationRun.run.state)) {
    return failed(
      runFailurePolicy(input.preparationRun, 'PREPARE_TEACHING'),
      availableSections,
      totalSections,
      latestActivity,
      canReadRulebook,
    )
  }
  if (input.teachingRun && FAILED_RUN_STATES.has(input.teachingRun.run.state)) {
    return failed(
      runFailurePolicy(input.teachingRun, 'GENERATE_LESSON'),
      availableSections,
      totalSections,
      latestActivity,
      canReadRulebook,
    )
  }

  if (!input.gameBound) {
    return projection({
      phase: 'GAME_BINDING', state: 'active', progress: null, retryAction: null,
      errorCode: null, canReadRulebook, canReadLesson: false, availableSections, totalSections, latestActivity,
    })
  }
  if (!input.importJob) {
    if (input.discovery === 'failed') {
      return failed(
        typedFailurePolicy('RULEBOOK_DISCOVERY_FAILED', 'DISCOVER_RULEBOOK', true),
        availableSections,
        totalSections,
        latestActivity,
        canReadRulebook,
      )
    }
    const phase = input.discovery === 'review' || input.discovery === 'unavailable'
      ? 'SOURCE_REVIEW'
      : 'RULEBOOK_DISCOVERY'
    return projection({
      phase,
      state: input.discovery === 'review' || input.discovery === 'unavailable' ? 'waiting' : 'active',
      progress: null,
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
      progress: input.documentProgress
        ? Math.max(0, Math.min(100, Math.round(input.documentProgress.percentage)))
        : null,
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
      phase: 'TEACHING_PREPARATION_QUEUED', state: 'active', progress: null, retryAction: null,
      errorCode: null, canReadRulebook, canReadLesson: false, availableSections, totalSections, latestActivity,
    })
  }
  if (input.preparationRun.run.state !== 'COMPLETED') {
    return projection({
      phase: 'TEACHING_PREPARING', state: 'active', progress: null, retryAction: null,
      errorCode: null, canReadRulebook, canReadLesson: false, availableSections, totalSections, latestActivity,
    })
  }
  if (!input.plan || !input.teachingRun) {
    return projection({
      phase: 'LESSON_GENERATION_QUEUED', state: 'active', progress: null, retryAction: null,
      errorCode: null, canReadRulebook, canReadLesson: false, availableSections, totalSections, latestActivity,
    })
  }

  return projection({
    phase: 'LESSON_GENERATING', state: 'active',
    progress: lessonProgress(availableSections, totalSections), retryAction: null,
    errorCode: null, canReadRulebook, canReadLesson: false, availableSections, totalSections, latestActivity,
  })
}

type ProjectionInput = Omit<
  PlayerJourneyProjection,
  'canAskQuestions' | 'failureClassification' | 'failureRecovery'
> & Partial<Pick<PlayerJourneyProjection, 'failureClassification' | 'failureRecovery'>>

function projection(input: ProjectionInput): PlayerJourneyProjection {
  return {
    failureClassification: null,
    failureRecovery: null,
    ...input,
    canAskQuestions: input.canReadLesson && input.canReadRulebook,
  }
}

function failed(
  policy: PlayerJourneyFailurePolicy,
  availableSections: number,
  totalSections: number | null,
  latestActivity: string | null,
  canReadRulebook: boolean,
) {
  return projection({
    phase: 'FAILED', state: 'failed', progress: null, retryAction: policy.retryAction,
    errorCode: policy.errorCode,
    failureClassification: policy.failureClassification,
    failureRecovery: policy.failureRecovery,
    canReadRulebook, canReadLesson: false,
    availableSections, totalSections, latestActivity,
  })
}

function importProjection(job: PlayerJourneyImportJob): [PlayerJourneyPhase, number | null] {
  if (job.stage === 'QUEUED') return ['IMPORT_QUEUED', null]
  if (job.stage === 'CONNECTING') return ['IMPORT_CONNECTING', null]
  if (job.stage === 'DOWNLOADING') {
    if (!job.totalBytes || job.totalBytes < 1) return ['IMPORT_DOWNLOADING', null]
    const downloadRatio = Math.min(1, Math.max(0, job.downloadedBytes / job.totalBytes))
    return ['IMPORT_DOWNLOADING', Math.round(downloadRatio * 100)]
  }
  if (job.stage === 'COMPRESSING') return ['IMPORT_COMPRESSING', null]
  if (job.stage === 'VERIFYING_FILE') return ['IMPORT_VERIFYING', null]
  return ['IMPORT_SAVING', null]
}

function lessonProgress(availableSections: number, totalSections: number | null) {
  if (!totalSections || totalSections < 1) return null
  return Math.min(100, Math.round(Math.min(1, availableSections / totalSections) * 100))
}

function importFailurePolicy(job: PlayerJourneyImportJob): PlayerJourneyFailurePolicy {
  const errorCode = job.errorCode ?? 'RULEBOOK_IMPORT_FAILED'
  if (job.recovery?.canRetryOriginalSource) {
    return preservedFailure(errorCode, 'IMPORT_RULEBOOK', 'retry-step')
  }
  const canChooseSource = Boolean(
    job.recovery?.canChooseAnotherSource
    || job.recovery?.canUseLocalUpload
    || job.recovery?.canOpenSourceInBrowser,
  )
  return {
    errorCode,
    retryAction: null,
    failureClassification: 'external-repair',
    failureRecovery: canChooseSource ? 'choose-source' : 'manual-repair',
  }
}

function runFailurePolicy(
  run: PlayerJourneyRun,
  retryAction: Exclude<PlayerJourneyRetryAction, null>,
): PlayerJourneyFailurePolicy {
  const errorCode = run.run.lastErrorCode ?? run.run.state
  return typedFailurePolicy(errorCode, retryAction, false)
}

export function typedFailurePolicy(
  errorCode: string,
  requestedRetryAction: PlayerJourneyRetryAction,
  serverAuthorizedRetry: boolean,
): PlayerJourneyFailurePolicy {
  if (LOCAL_DEGRADATION_CODES.has(errorCode)) {
    return {
      errorCode,
      retryAction: null,
      failureClassification: 'local-degradation',
      failureRecovery: null,
    }
  }
  if (PRESERVED_WITHOUT_RETRY_CODES.has(errorCode)) {
    return preservedFailure(errorCode, null, null)
  }
  if (EXTERNAL_REPAIR_CODES.has(errorCode)) {
    return {
      errorCode,
      retryAction: null,
      failureClassification: 'external-repair',
      failureRecovery: 'manual-repair',
    }
  }
  if (RESTART_FROM_COMPLETED_CODES.has(errorCode)) {
    return preservedFailure(errorCode, requestedRetryAction, 'restart-from-completed')
  }
  if (requestedRetryAction && (serverAuthorizedRetry || SAFE_RETRY_CODES.has(errorCode))) {
    return preservedFailure(errorCode, requestedRetryAction, 'retry-step')
  }
  return {
    errorCode,
    retryAction: null,
    failureClassification: 'external-repair',
    failureRecovery: 'manual-repair',
  }
}

function preservedFailure(
  errorCode: string,
  retryAction: PlayerJourneyRetryAction,
  failureRecovery: PlayerJourneyFailureRecovery,
): PlayerJourneyFailurePolicy {
  return {
    errorCode,
    retryAction,
    failureClassification: 'preserved-stop',
    failureRecovery,
  }
}

/** Keeps recovery guidance aligned with the same typed policy used to authorize retry actions. */
export function playerJourneyFailurePresentation(
  policy: Pick<PlayerJourneyFailurePolicy, 'failureClassification' | 'failureRecovery'>,
  locale: 'zh-CN' | 'en' = 'zh-CN',
): PlayerJourneyFailurePresentation {
  const english = locale === 'en'
  if (policy.failureClassification === 'local-degradation') {
    return {
      title: english ? 'Only local content is unavailable' : '只有局部内容不可用',
      detail: english
        ? 'Only the affected page, chapter, or visual is unavailable. Other confirmed pages and published chapters remain; the whole guide does not need to be regenerated.'
        : '只有对应页面、章节或配图不可用；其他已确认页面和已发布章节仍然保留，不需要重新生成整份讲解。',
      actionLabel: null,
    }
  }
  if (policy.failureRecovery === 'retry-step') {
    return {
      title: english ? 'This step can be retried' : '可以重试当前步骤',
      detail: english
        ? 'This step did not finish, but confirmed work remains. Retrying this step creates a fresh attempt without discarding completed content.'
        : '当前步骤没有完成，但已确认内容仍然保留；可以安全地为这一步启动一次新任务，不会丢弃已完成内容。',
      actionLabel: english ? 'Retry this step' : '重试当前步骤',
    }
  }
  if (policy.failureRecovery === 'restart-from-completed') {
    return {
      title: english ? 'The run stopped with completed work preserved' : '本轮已停止，完成内容已保留',
      detail: english
        ? 'The run stopped at a whole-run boundary. Confirmed pages and published chapters remain, and a new run can continue from that durable work.'
        : '本轮在整任务边界停止；已确认页面和已发布章节仍然保留，可以从这些持久化内容启动新一轮。',
      actionLabel: english ? 'Restart from completed work' : '从已完成内容重新开始',
    }
  }
  if (policy.failureRecovery === 'choose-source') {
    return {
      title: english ? 'Choose a usable rulebook source' : '需要选择可用的规则书来源',
      detail: english
        ? 'The current source cannot continue safely. Choose another source, upload the rulebook, or use the offered browser handoff.'
        : '当前来源无法安全继续；请换一个来源、上传规则书，或使用界面提供的浏览器操作。',
      actionLabel: null,
    }
  }
  if (policy.failureClassification === 'external-repair') {
    return {
      title: english ? 'Repair is required before continuing' : '需要先修复后再继续',
      detail: english
        ? 'Retrying unchanged is not safe. Repair the invalid input, source, authorization, service, or persistence boundary first; the rulebook and published chapters remain available.'
        : '这不是可以安全原样重试的失败；请先修复无效输入、来源、权限、服务或保存边界，再继续。规则书和已发布章节仍然保留。',
      actionLabel: null,
    }
  }
  return {
    title: english ? 'The run stopped with verified work preserved' : '本轮已停止，已核验内容保留',
    detail: english
      ? 'The available evidence was not enough to publish more guidance. Retrying unchanged will not add evidence; provide a better source or more specific input before continuing.'
      : '现有依据不足以发布更多讲解；原样重试不会增加依据，请补充更合适的来源或更具体的信息后再继续。',
    actionLabel: null,
  }
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
    activities.map(activity => [activity.sequence, {
      ...activity,
      outcome: normalizeJourneyActivityOutcome(activity.outcome),
    }]),
  ).values()).sort((left, right) => left.sequence - right.sequence)
}

function normalizeJourneyActivityOutcome(outcome: unknown): PlayerJourneyActivityOutcome {
  return KNOWN_ACTIVITY_OUTCOMES.has(outcome as PlayerJourneyActivityOutcome)
    ? outcome as PlayerJourneyActivityOutcome
    : 'UNKNOWN'
}

function isTerminalImport(job: PlayerJourneyImportJob) {
  return job.stage === 'FAILED'
    || job.stage === 'COMPLETED' && ['LAUNCHED', 'FAILED', 'NOT_REQUESTED'].includes(job.teachingHandoffState)
}

export function playerJourneyRunIsTerminal(state: string | null | undefined) {
  if (!state) return false
  return state === 'COMPLETED' || FAILED_RUN_STATES.has(state)
}

function isTerminalRun(state: string) {
  return playerJourneyRunIsTerminal(state)
}
