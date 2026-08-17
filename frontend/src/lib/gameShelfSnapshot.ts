import {
  hasInvalidShelfRelationships,
  type ShelfCatalogEntry,
  type ShelfDocument,
  type ShelfImportJob,
  type ShelfPlan,
  type ShelfPreparationRun,
  type ShelfUploadHandoff,
} from '@/lib/gameShelf'
import { parseExpectedAssistantRun } from '@/lib/backgroundWorkSnapshot'

export interface PersonalShelfBaseSnapshot {
  catalog: ShelfCatalogEntry[]
  documents: ShelfDocument[]
  imports: ShelfImportJob[]
}

export interface RichBggDetails {
  bggId: number
  name: string
  description: string
  imageUrl: string
  thumbnailUrl: string
  averageRating: number | null
  averageWeight: number | null
  categories: string[]
  mechanics: string[]
  designers: string[]
  publishers: string[]
  bggUrl: string
}

const documentStatuses = new Set([
  'UPLOADED', 'VALIDATING', 'EXTRACTING', 'STRUCTURING', 'CHUNKING', 'EMBEDDING', 'INDEXING', 'READY', 'FAILED',
])
const importStages = new Set([
  'QUEUED', 'CONNECTING', 'DOWNLOADING', 'COMPRESSING', 'VERIFYING_FILE', 'SAVING', 'COMPLETED', 'FAILED',
])
const teachingHandoffStates = new Set(['NOT_REQUESTED', 'WAITING_FOR_DOCUMENT', 'LAUNCHING', 'LAUNCHED', 'FAILED'])
const uploadHandoffStates = new Set(['WAITING_FOR_DOCUMENT', 'LAUNCHING', 'LAUNCHED', 'FAILED'])
const preparationStates = new Set(['RECEIVED', 'DOCUMENT_READINESS', 'LESSON_PLANNING', 'COMPLETED', 'FAILED'])

export function parsePersonalShelfBase(
  catalogPayload: unknown,
  documentPayload: unknown,
  importPayload: unknown,
  ownerUsername: string,
): PersonalShelfBaseSnapshot {
  const catalog = parseShelfCatalog(catalogPayload)
  const documents = parseShelfDocuments(documentPayload, ownerUsername)
  const imports = parseShelfImports(importPayload)
  validatePersonalShelfRelationships(catalog, documents, imports)
  return { catalog, documents, imports }
}

export function validatePersonalShelfRelationships(
  catalog: ShelfCatalogEntry[],
  documents: ShelfDocument[],
  imports: ShelfImportJob[],
) {
  if (hasInvalidShelfRelationships(catalog, documents, imports)) throw new Error('personal shelf relationship is invalid')
  const documentByVersion = new Map(documents.map(document => [document.latestVersion.id, document]))
  for (const job of imports) {
    if (!job.documentVersionId || !job.editionId) continue
    const document = documentByVersion.get(job.documentVersionId)
    if (document && document.document.gameEditionId !== job.editionId) {
      throw new Error('personal shelf import binding is invalid')
    }
  }
}

export function parseShelfCatalog(value: unknown): ShelfCatalogEntry[] {
  if (!Array.isArray(value)) throw new Error('personal shelf catalog is invalid')
  const gameIds = new Set<string>()
  const editionIds = new Set<string>()
  const expansionIds = new Set<string>()
  const bggIds = new Set<number>()
  return value.map((rawEntry) => {
    if (!isRecord(rawEntry)
      || !isRecord(rawEntry.game)
      || !boundedString(rawEntry.game.id, 128)
      || !boundedString(rawEntry.game.name, 255)
      || !Array.isArray(rawEntry.editions)
      || !Array.isArray(rawEntry.expansions)) {
      throw new Error('personal shelf catalog entry is invalid')
    }
    if (gameIds.has(rawEntry.game.id)) throw new Error('personal shelf game is duplicated')
    gameIds.add(rawEntry.game.id)
    const gameId = rawEntry.game.id
    const editions = rawEntry.editions.map((rawEdition) => {
      if (!isRecord(rawEdition)
        || !boundedString(rawEdition.id, 128)
        || rawEdition.gameId !== gameId
        || !boundedString(rawEdition.name, 255)
        || !boundedString(rawEdition.language, 32)
        || !nullableInteger(rawEdition.publicationYear)) {
        throw new Error('personal shelf edition is invalid')
      }
      if (editionIds.has(rawEdition.id)) throw new Error('personal shelf edition is duplicated')
      editionIds.add(rawEdition.id)
      return {
        id: rawEdition.id,
        gameId,
        name: rawEdition.name,
        language: rawEdition.language,
        publicationYear: rawEdition.publicationYear,
      }
    })
    const expansions = rawEntry.expansions.map((rawExpansion) => {
      if (!isRecord(rawExpansion)
        || !boundedString(rawExpansion.id, 128)
        || rawExpansion.gameId !== gameId
        || !boundedString(rawExpansion.name, 255)) {
        throw new Error('personal shelf expansion is invalid')
      }
      if (expansionIds.has(rawExpansion.id)) throw new Error('personal shelf expansion is duplicated')
      expansionIds.add(rawExpansion.id)
      return { id: rawExpansion.id, gameId, name: rawExpansion.name }
    })
    const bggMetadata = rawEntry.bggMetadata === null
      ? null
      : parseBggMetadata(rawEntry.bggMetadata, bggIds)
    return {
      game: { id: gameId, name: rawEntry.game.name },
      editions,
      expansions,
      bggMetadata,
    }
  })
}

export function parseShelfDocuments(value: unknown, ownerUsername: string): ShelfDocument[] {
  if (!Array.isArray(value)) throw new Error('personal shelf documents are invalid')
  const documentIds = new Set<string>()
  const versionIds = new Set<string>()
  return value.map((rawEntry) => {
    if (!isRecord(rawEntry)
      || !isRecord(rawEntry.document)
      || !isRecord(rawEntry.latestVersion)
      || !boundedString(rawEntry.document.id, 128)
      || !nullableBoundedString(rawEntry.document.gameEditionId, 128)
      || !boundedString(rawEntry.document.title, 255)
      || !nullableString(rawEntry.document.officialSourceUrl, 2_000)
      || !nullableString(rawEntry.document.officialCoverUrl, 2_000)
      || rawEntry.document.createdBy !== ownerUsername
      || !boundedString(rawEntry.latestVersion.id, 128)
      || typeof rawEntry.latestVersion.status !== 'string'
      || !documentStatuses.has(rawEntry.latestVersion.status)) {
      throw new Error('personal shelf document is invalid')
    }
    if (documentIds.has(rawEntry.document.id) || versionIds.has(rawEntry.latestVersion.id)) {
      throw new Error('personal shelf document is duplicated')
    }
    documentIds.add(rawEntry.document.id)
    versionIds.add(rawEntry.latestVersion.id)
    return {
      document: {
        id: rawEntry.document.id,
        gameEditionId: rawEntry.document.gameEditionId,
        title: rawEntry.document.title,
        officialSourceUrl: rawEntry.document.officialSourceUrl,
        officialCoverUrl: rawEntry.document.officialCoverUrl,
        createdBy: ownerUsername,
      },
      latestVersion: {
        id: rawEntry.latestVersion.id,
        status: rawEntry.latestVersion.status,
      },
    }
  })
}

export function parseShelfPlans(value: unknown, ownerUsername: string): ShelfPlan[] {
  if (!Array.isArray(value)) throw new Error('personal shelf plans are invalid')
  const planIds = new Set<string>()
  return value.map((rawPlan) => {
    if (!isRecord(rawPlan)
      || !boundedString(rawPlan.id, 128)
      || !boundedString(rawPlan.documentVersionId, 128)
      || !boundedString(rawPlan.gameTitle, 255)
      || rawPlan.createdBy !== ownerUsername
      || !validTimestamp(rawPlan.createdAt)) {
      throw new Error('personal shelf plan is invalid')
    }
    if (planIds.has(rawPlan.id)) throw new Error('personal shelf plan is duplicated')
    planIds.add(rawPlan.id)
    return {
      id: rawPlan.id,
      documentVersionId: rawPlan.documentVersionId,
      gameTitle: rawPlan.gameTitle,
      createdBy: ownerUsername,
      createdAt: rawPlan.createdAt,
    }
  })
}

export function parseShelfImports(value: unknown): ShelfImportJob[] {
  if (!Array.isArray(value)) throw new Error('personal shelf imports are invalid')
  const jobIds = new Set<string>()
  return value.map((rawJob) => {
    if (!isRecord(rawJob)
      || !boundedString(rawJob.id, 128)
      || !boundedString(rawJob.title, 255)
      || !boundedString(rawJob.rulebookTitle, 255)
      || !nullableBoundedString(rawJob.editionId, 128)
      || !nullableString(rawJob.editionName, 255)
      || !boundedString(rawJob.sourceDomain, 255)
      || typeof rawJob.stage !== 'string'
      || !importStages.has(rawJob.stage)
      || !nonNegativeInteger(rawJob.downloadedBytes)
      || !nullableNonNegativeInteger(rawJob.totalBytes)
      || !nullableBoundedString(rawJob.documentVersionId, 128)
      || !nullableString(rawJob.errorCode, 160)
      || typeof rawJob.teachingHandoffState !== 'string'
      || !teachingHandoffStates.has(rawJob.teachingHandoffState)
      || !nullableBoundedString(rawJob.teachingPreparationRunId, 128)
      || !nullableString(rawJob.teachingErrorCode, 160)
      || !nullableTimestamp(rawJob.downloadCompletedAt)
      || !nullableTimestamp(rawJob.importCompletedAt)
      || !nullableTimestamp(rawJob.teachingHandoffUpdatedAt)
      || !validTimestamp(rawJob.updatedAt)) {
      throw new Error('personal shelf import is invalid')
    }
    if (rawJob.totalBytes !== null && (rawJob.totalBytes === 0 || rawJob.downloadedBytes > rawJob.totalBytes)) {
      throw new Error('personal shelf import progress is invalid')
    }
    const importFailed = rawJob.stage === 'FAILED'
    const importCompleted = rawJob.stage === 'COMPLETED'
    const teachingFailed = rawJob.teachingHandoffState === 'FAILED'
    const teachingLaunched = rawJob.teachingHandoffState === 'LAUNCHED'
    if (importCompleted !== (rawJob.documentVersionId !== null)
      || importFailed !== (rawJob.errorCode !== null)
      || teachingLaunched !== (rawJob.teachingPreparationRunId !== null)
      || teachingFailed !== (rawJob.teachingErrorCode !== null)
      || rawJob.teachingPreparationRunId !== null && rawJob.documentVersionId === null) {
      throw new Error('personal shelf import terminal relationship is invalid')
    }
    if (jobIds.has(rawJob.id)) throw new Error('personal shelf import is duplicated')
    jobIds.add(rawJob.id)
    return rawJob as unknown as ShelfImportJob
  })
}

export function parseShelfUploadHandoffs(value: unknown): ShelfUploadHandoff[] {
  if (!Array.isArray(value)) throw new Error('personal shelf upload handoffs are invalid')
  const handoffIds = new Set<string>()
  const versionIds = new Set<string>()
  return value.map((rawHandoff) => {
    if (!isRecord(rawHandoff)
      || !boundedString(rawHandoff.id, 128)
      || !boundedString(rawHandoff.documentVersionId, 128)
      || !nullableBoundedString(rawHandoff.editionId, 128)
      || !boundedString(rawHandoff.rulebookTitle, 255)
      || typeof rawHandoff.state !== 'string'
      || !uploadHandoffStates.has(rawHandoff.state)
      || !nullableBoundedString(rawHandoff.preparationRunId, 128)
      || !nullableString(rawHandoff.errorCode, 160)
      || !validTimestamp(rawHandoff.updatedAt)) {
      throw new Error('personal shelf upload handoff is invalid')
    }
    if ((rawHandoff.state === 'LAUNCHED') !== (rawHandoff.preparationRunId !== null)
      || (rawHandoff.state === 'FAILED') !== (rawHandoff.errorCode !== null)) {
      throw new Error('personal shelf upload preparation is invalid')
    }
    if (handoffIds.has(rawHandoff.id) || versionIds.has(rawHandoff.documentVersionId)) {
      throw new Error('personal shelf upload handoff is duplicated')
    }
    handoffIds.add(rawHandoff.id)
    versionIds.add(rawHandoff.documentVersionId)
    return rawHandoff as unknown as ShelfUploadHandoff
  })
}

export function shelfPreparationSubjects(
  imports: ShelfImportJob[],
  handoffs: ShelfUploadHandoff[],
) {
  const subjects = new Map<string, string>()
  for (const [runId, documentVersionId] of [
    ...imports.map(job => [job.teachingPreparationRunId, job.documentVersionId] as const),
    ...handoffs.map(handoff => [handoff.preparationRunId, handoff.documentVersionId] as const),
  ]) {
    if (!runId || !documentVersionId) continue
    const existing = subjects.get(runId)
    if (existing && existing !== documentVersionId) {
      throw new Error('personal shelf preparation relationship is invalid')
    }
    subjects.set(runId, documentVersionId)
  }
  return subjects
}

export function parseShelfPreparationRun(
  value: unknown,
  runId: string,
  documentVersionId: string,
  ownerUsername: string,
): ShelfPreparationRun {
  const run = parseExpectedAssistantRun(value, {
    id: runId,
    mode: 'TEACHING_PREPARATION',
    subjectId: documentVersionId,
    ownerUsername,
  })
  if (!preparationStates.has(run.state)) throw new Error('personal shelf preparation state is invalid')
  return {
    id: run.id,
    documentVersionId: run.subjectId,
    state: run.state === 'COMPLETED' ? 'COMPLETED' : run.state === 'FAILED' ? 'FAILED' : 'ACTIVE',
  }
}

export function validateShelfUploadHandoffs(
  handoffs: ShelfUploadHandoff[],
  documents: ShelfDocument[],
) {
  const documentByVersion = new Map(documents.map(document => [document.latestVersion.id, document]))
  for (const handoff of handoffs) {
    const document = documentByVersion.get(handoff.documentVersionId)
    if (document && document.document.gameEditionId !== handoff.editionId) {
      throw new Error('personal shelf upload handoff binding is invalid')
    }
  }
}

export function parseRichBggDetails(value: unknown, expectedBggId: number): RichBggDetails {
  if (!isRecord(value)
    || value.bggId !== expectedBggId
    || !boundedString(value.name, 255)
    || !stringWithin(value.description, 100_000)
    || !stringWithin(value.imageUrl, 2_000)
    || !stringWithin(value.thumbnailUrl, 2_000)
    || !nullableNumberInRange(value.averageRating, 0, 10)
    || !nullableNumberInRange(value.averageWeight, 0, 5)
    || !boundedStringArray(value.categories, 200, 255)
    || !boundedStringArray(value.mechanics, 200, 255)
    || !boundedStringArray(value.designers, 200, 255)
    || !boundedStringArray(value.publishers, 200, 255)
    || !boundedString(value.bggUrl, 2_000)) {
    throw new Error('game presentation details are invalid')
  }
  return {
    bggId: expectedBggId,
    name: value.name,
    description: value.description,
    imageUrl: value.imageUrl,
    thumbnailUrl: value.thumbnailUrl,
    averageRating: value.averageRating,
    averageWeight: value.averageWeight,
    categories: value.categories,
    mechanics: value.mechanics,
    designers: value.designers,
    publishers: value.publishers,
    bggUrl: value.bggUrl,
  }
}

function parseBggMetadata(value: unknown, seenIds: Set<number>): ShelfCatalogEntry['bggMetadata'] {
  if (!isRecord(value)
    || !positiveInteger(value.bggId)
    || !stringWithin(value.thumbnailUrl, 2_000)
    || !boundedString(value.bggUrl, 2_000)
    || !nullableNonNegativeInteger(value.minPlayers)
    || !nullableNonNegativeInteger(value.maxPlayers)
    || !nullableNonNegativeInteger(value.playingTimeMinutes)
    || !nullableNonNegativeInteger(value.minimumAge)) {
    throw new Error('personal shelf BGG metadata is invalid')
  }
  if (seenIds.has(value.bggId)) throw new Error('personal shelf BGG metadata is duplicated')
  seenIds.add(value.bggId)
  return {
    bggId: value.bggId,
    thumbnailUrl: value.thumbnailUrl,
    bggUrl: value.bggUrl,
    minPlayers: value.minPlayers,
    maxPlayers: value.maxPlayers,
    playingTimeMinutes: value.playingTimeMinutes,
    minimumAge: value.minimumAge,
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function boundedString(value: unknown, _maxLength: number): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function stringWithin(value: unknown, _maxLength: number): value is string {
  return typeof value === 'string'
}

function nullableString(value: unknown, maxLength: number): value is string | null {
  return value === null || stringWithin(value, maxLength)
}

function nullableBoundedString(value: unknown, maxLength: number): value is string | null {
  return value === null || boundedString(value, maxLength)
}

function nonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function positiveInteger(value: unknown): value is number {
  return nonNegativeInteger(value) && value > 0
}

function nullableNonNegativeInteger(value: unknown): value is number | null {
  return value === null || nonNegativeInteger(value)
}

function nullableInteger(value: unknown): value is number | null {
  return value === null
    || typeof value === 'number' && Number.isSafeInteger(value) && value >= 1000 && value <= 9999
}

function validTimestamp(value: unknown): value is string {
  return boundedString(value, 64) && Number.isFinite(Date.parse(value))
}

function nullableTimestamp(value: unknown): value is string | null | undefined {
  return value === null || value === undefined || validTimestamp(value)
}

function nullableNumberInRange(value: unknown, minimum: number, maximum: number): value is number | null {
  return value === null || typeof value === 'number' && Number.isFinite(value) && value >= minimum && value <= maximum
}

function boundedStringArray(value: unknown, _maxItems: number, maxLength: number): value is string[] {
  return Array.isArray(value)
    && value.every(item => boundedString(item, maxLength))
}
