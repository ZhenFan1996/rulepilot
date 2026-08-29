import type { TeachingProgressPlan, TeachingRunProgress } from './teachingProgress'
import { playerJourneyRunIsTerminal } from './playerJourney'

export interface TeachingPlanSummary {
  id: string
  gameTitle: string
}

export interface PreparationTeachingPlanSummary extends TeachingPlanSummary {
  documentVersionId: string
  createdAt: string
}

export interface AssistantRunSnapshot {
  id: string
  mode: 'TEACHING_PREPARATION' | 'TEACHING' | 'VISUAL_ENRICHMENT' | 'QUESTION_ANSWER'
  subjectId: string
  ownerUsername: string
  state: string
  updatedAt?: string
}

export interface RulebookImportJob {
  id: string
  title: string
  sourceDomain: string
  stage: 'QUEUED' | 'CONNECTING' | 'DOWNLOADING' | 'COMPRESSING' | 'VERIFYING_FILE' | 'SAVING' | 'COMPLETED' | 'FAILED'
  downloadedBytes: number
  totalBytes: number | null
  documentVersionId: string | null
  errorCode: string | null
  teachingHandoffState?: 'NOT_REQUESTED' | 'WAITING_FOR_DOCUMENT' | 'LAUNCHING' | 'LAUNCHED' | 'FAILED'
  teachingPreparationRunId?: string | null
  teachingErrorCode?: string | null
  teachingNextAction?: 'WAIT' | 'OPEN_PROGRESS' | 'RETRY_TEACHING' | 'RETRY_DOCUMENT' | 'NONE'
  downloadCompletedAt?: string | null
  importCompletedAt?: string | null
  teachingHandoffUpdatedAt?: string | null
  updatedAt: string
}

export interface UploadedTeachingHandoff {
  id: string
  documentVersionId: string
  title: string
  rulebookTitle: string
  state: 'WAITING_FOR_DOCUMENT' | 'LAUNCHING' | 'LAUNCHED' | 'FAILED'
  preparationRunId: string | null
  errorCode: string | null
  nextAction?: 'WAIT' | 'OPEN_PROGRESS' | 'RETRY_TEACHING' | 'RETRY_DOCUMENT' | 'NONE'
  updatedAt: string
}

export interface DocumentSummary {
  document: { id: string; title: string; createdBy: string }
  latestVersion: { id: string; status: string }
}

export interface DocumentProgress {
  stage: string
  percentage: number
  processedPages: number
  totalPages: number
  complete: boolean
}

const assistantRunStates = new Set([
  'RECEIVED', 'DOCUMENT_READINESS', 'LESSON_PLANNING', 'QUESTION_UNDERSTANDING', 'NEED_CLARIFICATION',
  'RETRIEVAL_PLANNING', 'RETRIEVING', 'VERIFYING_EVIDENCE', 'INSUFFICIENT_EVIDENCE',
  'LESSON_COMPOSITION', 'ANSWER_COMPOSITION', 'MEDIA_PACKAGING', 'CRITIQUING', 'COMPLETED', 'FAILED', 'DEGRADED',
  'CANCELLED',
])
const assistantRunModes = new Set(['TEACHING_PREPARATION', 'TEACHING', 'VISUAL_ENRICHMENT', 'QUESTION_ANSWER'])
const importStages = new Set(['QUEUED', 'CONNECTING', 'DOWNLOADING', 'COMPRESSING', 'VERIFYING_FILE', 'SAVING', 'COMPLETED', 'FAILED'])
const importHandoffStates = new Set(['NOT_REQUESTED', 'WAITING_FOR_DOCUMENT', 'LAUNCHING', 'LAUNCHED', 'FAILED'])
const uploadHandoffStates = new Set(['WAITING_FOR_DOCUMENT', 'LAUNCHING', 'LAUNCHED', 'FAILED'])
const teachingRecoveryActions = new Set(['WAIT', 'OPEN_PROGRESS', 'RETRY_TEACHING', 'RETRY_DOCUMENT', 'NONE'])
const documentStatuses = new Set(['UPLOADED', 'VALIDATING', 'EXTRACTING', 'STRUCTURING', 'CHUNKING', 'EMBEDDING', 'INDEXING', 'READY', 'FAILED'])
const documentProgressStages = new Set([...documentStatuses, 'RENDERING'])

export function parseActiveTeachingRuns(value: unknown, ownerUsername: string) {
  if (!Array.isArray(value)) throw new Error('background teaching status is invalid')
  const seenIds = new Set<string>()
  const seenSubjects = new Set<string>()
  return value.map((entry) => {
    const run = parseAssistantRun(entry)
    if (run.mode !== 'TEACHING'
      || run.ownerUsername !== ownerUsername
      || playerJourneyRunIsTerminal(run.state)) {
      throw new Error('background teaching identity is invalid')
    }
    if (seenIds.has(run.id) || seenSubjects.has(run.subjectId)) {
      throw new Error('background teaching status is duplicated')
    }
    seenIds.add(run.id)
    seenSubjects.add(run.subjectId)
    return run
  })
}

export function parseTeachingPlans(value: unknown): TeachingPlanSummary[] {
  if (!Array.isArray(value)) throw new Error('background teaching plans are invalid')
  const seenIds = new Set<string>()
  return value.map((entry) => {
    if (!isRecord(entry) || !boundedString(entry.id, 128) || !boundedString(entry.gameTitle, 160)) {
      throw new Error('background teaching plan is invalid')
    }
    if (seenIds.has(entry.id)) throw new Error('background teaching plan is duplicated')
    seenIds.add(entry.id)
    return { id: entry.id, gameTitle: entry.gameTitle }
  })
}

export function parsePreparationTeachingPlans(value: unknown): PreparationTeachingPlanSummary[] {
  if (!Array.isArray(value)) throw new Error('background preparation plans are invalid')
  const seenIds = new Set<string>()
  return value.map((entry) => {
    if (!isRecord(entry)
      || !boundedString(entry.id, 128)
      || !boundedString(entry.documentVersionId, 128)
      || !boundedString(entry.gameTitle, 160)
      || !validTimestamp(entry.createdAt)) {
      throw new Error('background preparation plan is invalid')
    }
    if (seenIds.has(entry.id)) throw new Error('background preparation plan is duplicated')
    seenIds.add(entry.id)
    return {
      id: entry.id,
      documentVersionId: entry.documentVersionId,
      gameTitle: entry.gameTitle,
      createdAt: entry.createdAt,
    }
  })
}

export function parseLatestTeachingRun(value: unknown, subjectId: string, ownerUsername: string) {
  if (!isRecord(value)) throw new Error('background latest run details are invalid')
  const run = parseAssistantRun(value.run)
  if (run.mode !== 'TEACHING' || run.subjectId !== subjectId || run.ownerUsername !== ownerUsername) {
    throw new Error('background latest run identity is invalid')
  }
  return run
}

export function parseExpectedAssistantRun(
  value: unknown,
  expected: Omit<AssistantRunSnapshot, 'state' | 'updatedAt'>,
) {
  if (!isRecord(value)) throw new Error('background run details are invalid')
  const run = parseAssistantRun(value.run)
  if (run.id !== expected.id
    || run.mode !== expected.mode
    || run.subjectId !== expected.subjectId
    || run.ownerUsername !== expected.ownerUsername) {
    throw new Error('background run identity is invalid')
  }
  return run
}

export function parseTeachingRunProgress(
  value: unknown,
  expected: Omit<AssistantRunSnapshot, 'state' | 'updatedAt'>,
): TeachingRunProgress {
  const run = parseExpectedAssistantRun(value, expected)
  if (!isRecord(value)
    || !isRecord(value.run)
    || !validTimestamp(value.run.createdAt)
    || !validTimestamp(value.run.updatedAt)
    || !nullableTimestamp(value.run.completedAt)
    || !nullableBoundedString(value.run.lastErrorCode, 160)
    || !isRecord(value.budget)
    || !nonNegativeInteger(value.budget.usedModelCalls)
    || !Array.isArray(value.activities)) {
    throw new Error('background teaching progress is invalid')
  }
  const activities = value.activities.map((entry) => {
    if (!isRecord(entry)
      || !nonNegativeInteger(entry.sequence)
      || !['TOOL', 'MODEL', 'CRITIC', 'VALIDATION'].includes(String(entry.type))
      || typeof entry.operation !== 'string' || entry.operation.trim().length === 0
      || typeof entry.summary !== 'string'
      || !['RUNNING', 'SUCCEEDED', 'FAILED', 'REJECTED'].includes(String(entry.outcome))
      || !nonNegativeInteger(entry.latencyMs)
      || !validTimestamp(entry.occurredAt)) {
      throw new Error('background teaching activity is invalid')
    }
    return entry as unknown as TeachingRunProgress['activities'][number]
  })
  return {
    run: {
      id: run.id,
      subjectId: run.subjectId,
      state: run.state,
      createdAt: value.run.createdAt,
      updatedAt: value.run.updatedAt,
      completedAt: value.run.completedAt as string | null,
      lastErrorCode: value.run.lastErrorCode as string | null,
    },
    budget: {
      usedModelCalls: value.budget.usedModelCalls,
    },
    activities,
  }
}

export function parseTeachingProgressPlan(value: unknown, expectedPlanId: string): TeachingProgressPlan & {
  id: string
  gameTitle: string
} {
  if (!isRecord(value)
    || value.id !== expectedPlanId
    || typeof value.gameTitle !== 'string' || value.gameTitle.trim().length === 0
    || value.sections !== undefined && !Array.isArray(value.sections)) {
    throw new Error('background teaching plan progress is invalid')
  }
  const sections = (value.sections ?? []).map((entry, index) => {
    if (!isRecord(entry)
      || !Number.isInteger(entry.position) || entry.position !== index + 1
      || typeof entry.title !== 'string' || entry.title.trim().length === 0
      || typeof entry.visualEvidenceRecommended !== 'boolean') {
      throw new Error('background teaching plan section is invalid')
    }
    return {
      position: entry.position,
      title: entry.title,
      visualEvidenceRecommended: entry.visualEvidenceRecommended,
    }
  })
  return { id: expectedPlanId, gameTitle: value.gameTitle, sections }
}

export function parseRulebookImports(value: unknown): RulebookImportJob[] {
  if (!Array.isArray(value)) throw new Error('background imports are invalid')
  const seenIds = new Set<string>()
  return value.map((entry) => {
    if (!isRecord(entry)
      || !boundedString(entry.id, 128)
      || !boundedString(entry.title, 160)
      || !boundedString(entry.sourceDomain, 255)
      || typeof entry.stage !== 'string'
      || !importStages.has(entry.stage)
      || !nonNegativeInteger(entry.downloadedBytes)
      || !nullableNonNegativeInteger(entry.totalBytes)
      || !nullableBoundedString(entry.documentVersionId, 128)
      || !nullableBoundedString(entry.errorCode, 160)
      || typeof entry.teachingHandoffState !== 'string'
      || !importHandoffStates.has(entry.teachingHandoffState)
      || !nullableBoundedString(entry.teachingPreparationRunId, 128)
      || !nullableBoundedString(entry.teachingErrorCode, 160)
      || entry.teachingNextAction !== undefined
        && (typeof entry.teachingNextAction !== 'string'
          || !teachingRecoveryActions.has(entry.teachingNextAction))
      || !nullableTimestamp(entry.downloadCompletedAt)
      || !nullableTimestamp(entry.importCompletedAt)
      || !nullableTimestamp(entry.teachingHandoffUpdatedAt)
      || !validTimestamp(entry.updatedAt)) {
      throw new Error('background import is invalid')
    }
    if (entry.teachingPreparationRunId && !entry.documentVersionId) {
      throw new Error('background import preparation is unbound')
    }
    if (entry.teachingPreparationRunId && entry.teachingHandoffState !== 'LAUNCHED') {
      throw new Error('background import preparation state is invalid')
    }
    if (seenIds.has(entry.id)) throw new Error('background import is duplicated')
    seenIds.add(entry.id)
    return entry as unknown as RulebookImportJob
  })
}

export function parseUploadedHandoffs(value: unknown): UploadedTeachingHandoff[] {
  if (!Array.isArray(value)) throw new Error('background upload handoffs are invalid')
  const seenIds = new Set<string>()
  return value.map((entry) => {
    if (!isRecord(entry)
      || !boundedString(entry.id, 128)
      || !boundedString(entry.documentVersionId, 128)
      || !boundedString(entry.title, 160)
      || !boundedString(entry.rulebookTitle, 255)
      || typeof entry.state !== 'string'
      || !uploadHandoffStates.has(entry.state)
      || !nullableBoundedString(entry.preparationRunId, 128)
      || !nullableBoundedString(entry.errorCode, 160)
      || entry.nextAction !== undefined
        && (typeof entry.nextAction !== 'string'
          || !teachingRecoveryActions.has(entry.nextAction))
      || !validTimestamp(entry.updatedAt)) {
      throw new Error('background upload handoff is invalid')
    }
    if (entry.preparationRunId && entry.state !== 'LAUNCHED') {
      throw new Error('background upload preparation state is invalid')
    }
    if (seenIds.has(entry.id)) throw new Error('background upload handoff is duplicated')
    seenIds.add(entry.id)
    return entry as unknown as UploadedTeachingHandoff
  })
}

export function parseOwnedDocuments(value: unknown, ownerUsername: string): DocumentSummary[] {
  if (!Array.isArray(value)) throw new Error('background documents are invalid')
  const seenDocumentIds = new Set<string>()
  const seenVersionIds = new Set<string>()
  return value.map((entry) => {
    if (!isRecord(entry) || !isRecord(entry.document) || !isRecord(entry.latestVersion)
      || !boundedString(entry.document.id, 128)
      || !boundedString(entry.document.title, 160)
      || entry.document.createdBy !== ownerUsername
      || !boundedString(entry.latestVersion.id, 128)
      || typeof entry.latestVersion.status !== 'string'
      || !documentStatuses.has(entry.latestVersion.status)) {
      throw new Error('background document is invalid')
    }
    if (seenDocumentIds.has(entry.document.id) || seenVersionIds.has(entry.latestVersion.id)) {
      throw new Error('background document is duplicated')
    }
    seenDocumentIds.add(entry.document.id)
    seenVersionIds.add(entry.latestVersion.id)
    return entry as unknown as DocumentSummary
  })
}

export function validateDocumentRelationships(
  importJobs: RulebookImportJob[],
  handoffs: UploadedTeachingHandoff[],
  documentList: DocumentSummary[],
) {
  const knownVersions = new Set(documentList.map(entry => entry.latestVersion.id))
  for (const job of importJobs) {
    if (job.stage !== 'COMPLETED' && job.documentVersionId && !knownVersions.has(job.documentVersionId)) {
      throw new Error('background import document is invalid')
    }
  }
  for (const handoff of handoffs) {
    if (handoff.state !== 'FAILED'
      && handoff.errorCode !== 'DOCUMENT_PROCESSING_FAILED'
      && !knownVersions.has(handoff.documentVersionId)) {
      throw new Error('background upload handoff document is invalid')
    }
  }
}

export function parseDocumentProgress(value: unknown): DocumentProgress {
  if (!isRecord(value)
    || !boundedString(value.stage, 64)
    || !documentProgressStages.has(value.stage)
    || !nonNegativeInteger(value.percentage)
    || value.percentage > 100
    || !nonNegativeInteger(value.processedPages)
    || !nonNegativeInteger(value.totalPages)
    || value.totalPages < value.processedPages
    || typeof value.complete !== 'boolean'
    || (value.complete && (!['READY', 'FAILED'].includes(value.stage) || value.percentage !== 100))
    || (!value.complete && ['READY', 'FAILED'].includes(value.stage))) {
    throw new Error('background document progress is invalid')
  }
  return value as unknown as DocumentProgress
}

function parseAssistantRun(value: unknown): AssistantRunSnapshot {
  if (!isRecord(value)
    || !boundedString(value.id, 128)
    || !boundedString(value.subjectId, 128)
    || !boundedString(value.ownerUsername, 120)
    || typeof value.mode !== 'string'
    || !assistantRunModes.has(value.mode)
    || typeof value.state !== 'string'
    || !assistantRunStates.has(value.state)
    || (value.updatedAt !== undefined && value.updatedAt !== null && !validTimestamp(value.updatedAt))) {
    throw new Error('background run is invalid')
  }
  return {
    id: value.id,
    mode: value.mode as AssistantRunSnapshot['mode'],
    subjectId: value.subjectId,
    ownerUsername: value.ownerUsername,
    state: value.state,
    updatedAt: typeof value.updatedAt === 'string' ? value.updatedAt : undefined,
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function boundedString(value: unknown, _maxLength: number): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function nullableBoundedString(value: unknown, maxLength: number) {
  return value === null || value === undefined || boundedString(value, maxLength)
}

function nonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function nullableNonNegativeInteger(value: unknown) {
  return value === null || nonNegativeInteger(value)
}

function validTimestamp(value: unknown): value is string {
  return boundedString(value, 64) && Number.isFinite(Date.parse(value))
}

function nullableTimestamp(value: unknown): value is string | null | undefined {
  return value === null || value === undefined || validTimestamp(value)
}
