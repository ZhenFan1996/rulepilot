import { playerFacingTitle } from '@/lib/lessonPresentation'

export interface ShelfCatalogEntry {
  game: { id: string; name: string }
  editions: Array<{ id: string; gameId: string; name: string; language: string; publicationYear: number | null }>
  expansions: Array<{ id: string; gameId: string; name: string }>
  bggMetadata: {
    bggId: number
    thumbnailUrl: string
    bggUrl: string
    minPlayers: number | null
    maxPlayers: number | null
    playingTimeMinutes: number | null
    minimumAge: number | null
  } | null
}

export interface ShelfDocument {
  document: {
    id: string
    gameEditionId: string | null
    title: string
    officialSourceUrl: string | null
    officialCoverUrl: string | null
    createdBy: string
  }
  latestVersion: { id: string; status: string }
}

export interface ShelfPlan {
  id: string
  documentVersionId: string
  gameTitle: string
  createdBy: string
  createdAt: string
}

export interface ShelfImportJob {
  id: string
  title: string
  rulebookTitle: string
  editionId: string | null
  editionName: string | null
  sourceDomain: string
  stage: 'QUEUED' | 'CONNECTING' | 'DOWNLOADING' | 'COMPRESSING' | 'VERIFYING_FILE' | 'SAVING' | 'COMPLETED' | 'FAILED'
  downloadedBytes: number
  totalBytes: number | null
  documentVersionId: string | null
  errorCode: string | null
  teachingHandoffState: 'NOT_REQUESTED' | 'WAITING_FOR_DOCUMENT' | 'LAUNCHING' | 'LAUNCHED' | 'FAILED'
  teachingPreparationRunId: string | null
  teachingErrorCode: string | null
  downloadCompletedAt?: string | null
  importCompletedAt?: string | null
  teachingHandoffUpdatedAt?: string | null
  updatedAt: string
}

export interface ShelfUploadHandoff {
  id: string
  documentVersionId: string
  editionId: string | null
  rulebookTitle: string
  state: 'WAITING_FOR_DOCUMENT' | 'LAUNCHING' | 'LAUNCHED' | 'FAILED'
  preparationRunId: string | null
  errorCode: string | null
  updatedAt: string
}

export interface ShelfPreparationRun {
  id: string
  documentVersionId: string
  state: 'ACTIVE' | 'COMPLETED' | 'FAILED'
}

export type ShelfPlansAvailability = 'LOADING' | 'READY' | 'UNAVAILABLE'

export interface ShelfItem {
  id: string
  gameId: string | null
  editionId: string | null
  title: string
  coverUrl: string | null
  coverAttributionUrl: string | null
  editionLabel: string | null
  players: { min: number; max: number } | null
  playtimeMinutes: number | null
  minimumAge: number | null
  documentCount: number
  pendingImportCount: number
  lessonCount: number
  latestPlanId: string | null
  documentStatus: 'IMPORTING' | 'READY' | 'READING' | 'NEEDS_ATTENTION'
  guideStatus: 'LOADING' | 'PREPARING' | 'READY' | 'NONE' | 'FAILED' | 'UNAVAILABLE'
  expansionCount: number
}

interface BuildShelfOptions {
  imports?: ShelfImportJob[]
  uploadHandoffs?: ShelfUploadHandoff[]
  preparationRuns?: ShelfPreparationRun[]
  plansAvailability?: ShelfPlansAvailability
  preparationsAvailability?: ShelfPlansAvailability
}

const ready = new Set(['READY'])
const reading = new Set(['UPLOADED', 'VALIDATING', 'EXTRACTING', 'STRUCTURING', 'CHUNKING', 'EMBEDDING', 'INDEXING'])

export function buildPersonalShelf(
  catalog: ShelfCatalogEntry[],
  documents: ShelfDocument[],
  plans: ShelfPlan[],
  options: BuildShelfOptions = {},
): ShelfItem[] {
  const imports = options.imports ?? []
  const uploadHandoffs = options.uploadHandoffs ?? []
  const preparationById = new Map((options.preparationRuns ?? []).map(run => [run.id, run]))
  const plansAvailability = options.plansAvailability ?? 'READY'
  const preparationsAvailability = options.preparationsAvailability ?? 'READY'
  const editionLookup = new Map<string, { entry: ShelfCatalogEntry; edition: ShelfCatalogEntry['editions'][number] }>()
  for (const entry of catalog) {
    for (const edition of entry.editions) editionLookup.set(edition.id, { entry, edition })
  }

  const documentByVersion = new Map(documents.map(document => [document.latestVersion.id, document]))
  const items = new Map<string, ShelfItem>()
  const seenPlanIds = new Set<string>()
  for (const document of documents) {
    const requestedEditionId = document.document.gameEditionId
    const assignment = requestedEditionId ? editionLookup.get(requestedEditionId) : undefined
    if (requestedEditionId && !assignment) continue
    const key = assignment ? `game:${assignment.entry.game.id}` : `document:${document.document.id}`
    const planMatches = plans.filter(plan => plan.documentVersionId === document.latestVersion.id)
    planMatches.forEach(plan => seenPlanIds.add(plan.id))
    const existing = items.get(key)
    const title = playerFacingTitle(assignment?.entry.game.name ?? planMatches[0]?.gameTitle ?? document.document.title)
    const metadata = assignment?.entry.bggMetadata ?? null
    const next: ShelfItem = existing ?? {
      id: key,
      gameId: assignment?.entry.game.id ?? null,
      editionId: assignment?.edition.id ?? null,
      title,
      coverUrl: metadata?.thumbnailUrl || document.document.officialCoverUrl || null,
      coverAttributionUrl: metadata?.bggUrl || null,
      editionLabel: assignment ? editionLabel(assignment.edition) : null,
      players: playerLabel(metadata),
      playtimeMinutes: metadata?.playingTimeMinutes ?? null,
      minimumAge: metadata?.minimumAge ?? null,
      documentCount: 0,
      pendingImportCount: 0,
      lessonCount: 0,
      latestPlanId: null,
      documentStatus: statusFor(document.latestVersion.status),
      guideStatus: guideStatusWithoutPlan(plansAvailability),
      expansionCount: assignment?.entry.expansions.length ?? 0,
    }
    next.documentCount += 1
    next.lessonCount += planMatches.length
    next.documentStatus = strongestDocumentStatus(next.documentStatus, statusFor(document.latestVersion.status))
    const latest = latestPlan([...planMatches, ...plans.filter(plan => plan.id === next.latestPlanId)])
    next.latestPlanId = latest?.id ?? null
    if (latest) next.guideStatus = 'READY'
    const uploadHandoff = uploadHandoffs.find(handoff => handoff.documentVersionId === document.latestVersion.id)
    if (!latest && uploadHandoff) {
      next.guideStatus = strongestGuideStatus(next.guideStatus, uploadHandoffGuideStatus(
        uploadHandoff,
        uploadHandoff.preparationRunId ? preparationById.get(uploadHandoff.preparationRunId) : undefined,
        preparationsAvailability,
      ))
    }
    items.set(key, next)
  }

  for (const job of imports) {
    const representedDocument = (job.documentVersionId ? documentByVersion.get(job.documentVersionId) : undefined)
      ?? (job.stage === 'COMPLETED' && job.editionId
        ? documents.find(document => document.document.gameEditionId === job.editionId)
        : undefined)
    const representedEditionId = representedDocument?.document.gameEditionId
    const requestedEditionId = job.editionId ?? representedEditionId ?? null
    const assignment = requestedEditionId ? editionLookup.get(requestedEditionId) : undefined
    if (requestedEditionId && !assignment) continue
    const representedKey = representedDocument
      ? representedEditionId && assignment
        ? `game:${assignment.entry.game.id}`
        : `document:${representedDocument.document.id}`
      : null
    const key = representedKey ?? (assignment ? `game:${assignment.entry.game.id}` : `import:${job.id}`)
    const existing = items.get(key)
    const metadata = assignment?.entry.bggMetadata ?? null
    const next: ShelfItem = existing ?? {
      id: key,
      gameId: assignment?.entry.game.id ?? null,
      editionId: assignment?.edition.id ?? null,
      title: playerFacingTitle(assignment?.entry.game.name ?? job.title ?? job.rulebookTitle),
      coverUrl: metadata?.thumbnailUrl || null,
      coverAttributionUrl: metadata?.bggUrl || null,
      editionLabel: assignment ? editionLabel(assignment.edition) : job.editionName,
      players: playerLabel(metadata),
      playtimeMinutes: metadata?.playingTimeMinutes ?? null,
      minimumAge: metadata?.minimumAge ?? null,
      documentCount: 0,
      pendingImportCount: 0,
      lessonCount: 0,
      latestPlanId: null,
      documentStatus: importDocumentStatus(job),
      guideStatus: guideStatusWithoutPlan(plansAvailability),
      expansionCount: assignment?.entry.expansions.length ?? 0,
    }
    if (!representedDocument) {
      next.pendingImportCount += 1
      next.documentStatus = strongestDocumentStatus(next.documentStatus, importDocumentStatus(job))
    }
    if (!next.latestPlanId) {
      next.guideStatus = strongestGuideStatus(next.guideStatus, importGuideStatus(
        job,
        job.teachingPreparationRunId ? preparationById.get(job.teachingPreparationRunId) : undefined,
        preparationsAvailability,
      ))
    }
    items.set(key, next)
  }

  for (const plan of plans.filter(candidate => !seenPlanIds.has(candidate.id))) {
    const key = `plan:${plan.id}`
    items.set(key, {
      id: key,
      gameId: null,
      editionId: null,
      title: playerFacingTitle(plan.gameTitle),
      coverUrl: null,
      coverAttributionUrl: null,
      editionLabel: null,
      players: null,
      playtimeMinutes: null,
      minimumAge: null,
      documentCount: 0,
      pendingImportCount: 0,
      lessonCount: 1,
      latestPlanId: plan.id,
      documentStatus: 'READY',
      guideStatus: 'READY',
      expansionCount: 0,
    })
  }

  return [...items.values()].sort((left, right) => {
    if (Boolean(left.latestPlanId) !== Boolean(right.latestPlanId)) return left.latestPlanId ? -1 : 1
    if (Boolean(left.pendingImportCount) !== Boolean(right.pendingImportCount)) return left.pendingImportCount ? -1 : 1
    return left.title.localeCompare(right.title, 'zh-CN')
  })
}

export function hasInvalidShelfRelationships(
  catalog: ShelfCatalogEntry[],
  documents: ShelfDocument[],
  imports: ShelfImportJob[],
) {
  const editionIds = new Set(catalog.flatMap(entry => entry.editions.map(edition => edition.id)))
  return documents.some(document => document.document.gameEditionId !== null
      && !editionIds.has(document.document.gameEditionId))
    || imports.some(job => job.editionId !== null && !editionIds.has(job.editionId))
}

export function unrepresentedImportsForEdition(
  editionId: string,
  imports: ShelfImportJob[],
  documents: ShelfDocument[],
) {
  const documentVersionIds = new Set(documents.map(document => document.latestVersion.id))
  const editionHasDocument = documents.some(document => document.document.gameEditionId === editionId)
  return imports
    .filter(job => job.editionId === editionId
      && (!job.documentVersionId || !documentVersionIds.has(job.documentVersionId))
      && !(job.stage === 'COMPLETED' && editionHasDocument))
    .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))
}

export function hasPendingShelfWork(
  documents: ShelfDocument[],
  imports: ShelfImportJob[],
  uploadHandoffs: ShelfUploadHandoff[],
  plans: ShelfPlan[],
  editionIds?: Set<string>,
  preparationRuns: ShelfPreparationRun[] = [],
) {
  const matchesEdition = (editionId: string | null) => !editionIds
    || editionId !== null && editionIds.has(editionId)
  const selectedDocuments = documents.filter(document => matchesEdition(document.document.gameEditionId))
  const selectedVersionIds = new Set(selectedDocuments.map(document => document.latestVersion.id))
  const selectedEditionIds = new Set(selectedDocuments
    .map(document => document.document.gameEditionId)
    .filter((editionId): editionId is string => editionId !== null))
  const planVersionIds = new Set(plans.map(plan => plan.documentVersionId))
  const preparationById = new Map(preparationRuns.map(run => [run.id, run]))
  if (selectedDocuments.some(document => reading.has(document.latestVersion.status))) return true
  if (imports.some((job) => {
    if (!matchesEdition(job.editionId) || job.stage === 'FAILED') return false
    if (job.stage !== 'COMPLETED') return true
    const documentRepresented = job.documentVersionId !== null && selectedVersionIds.has(job.documentVersionId)
      || job.editionId !== null && selectedEditionIds.has(job.editionId)
    if (!documentRepresented) return true
    if (job.documentVersionId !== null && planVersionIds.has(job.documentVersionId)) return false
    if (['WAITING_FOR_DOCUMENT', 'LAUNCHING'].includes(job.teachingHandoffState)) return true
    if (job.teachingHandoffState !== 'LAUNCHED' || !job.teachingPreparationRunId) return false
    return preparationById.get(job.teachingPreparationRunId)?.state !== 'FAILED'
  })) return true
  return uploadHandoffs.some((handoff) => {
    if ((!matchesEdition(handoff.editionId) && !selectedVersionIds.has(handoff.documentVersionId))
      || handoff.state === 'FAILED'
      || planVersionIds.has(handoff.documentVersionId)) return false
    if (handoff.state !== 'LAUNCHED' || !handoff.preparationRunId) return true
    return preparationById.get(handoff.preparationRunId)?.state !== 'FAILED'
  })
}

function editionLabel(edition: ShelfCatalogEntry['editions'][number]) {
  const year = edition.publicationYear ? ` · ${edition.publicationYear}` : ''
  return `${edition.name} · ${edition.language}${year}`
}

function playerLabel(metadata: ShelfCatalogEntry['bggMetadata']) {
  if (metadata?.minPlayers === null || metadata?.maxPlayers === null || !metadata) return null
  return { min: metadata.minPlayers, max: metadata.maxPlayers }
}

function statusFor(status: string): ShelfItem['documentStatus'] {
  if (ready.has(status)) return 'READY'
  if (reading.has(status)) return 'READING'
  return 'NEEDS_ATTENTION'
}

function importDocumentStatus(job: ShelfImportJob): ShelfItem['documentStatus'] {
  if (job.stage === 'FAILED') return 'NEEDS_ATTENTION'
  if (job.stage === 'COMPLETED') return 'READING'
  return 'IMPORTING'
}

function guideStatusWithoutPlan(availability: ShelfPlansAvailability): ShelfItem['guideStatus'] {
  if (availability === 'LOADING') return 'LOADING'
  if (availability === 'UNAVAILABLE') return 'UNAVAILABLE'
  return 'NONE'
}

function importGuideStatus(
  job: ShelfImportJob,
  preparation: ShelfPreparationRun | undefined,
  availability: ShelfPlansAvailability,
): ShelfItem['guideStatus'] {
  if (job.teachingHandoffState === 'FAILED') return 'FAILED'
  if (['WAITING_FOR_DOCUMENT', 'LAUNCHING'].includes(job.teachingHandoffState)) return 'PREPARING'
  if (job.teachingHandoffState === 'LAUNCHED') return preparationGuideStatus(preparation, availability)
  return 'NONE'
}

function uploadHandoffGuideStatus(
  handoff: ShelfUploadHandoff,
  preparation: ShelfPreparationRun | undefined,
  availability: ShelfPlansAvailability,
): ShelfItem['guideStatus'] {
  if (handoff.state === 'FAILED') return 'FAILED'
  if (handoff.state === 'LAUNCHED') return preparationGuideStatus(preparation, availability)
  return 'PREPARING'
}

function preparationGuideStatus(
  preparation: ShelfPreparationRun | undefined,
  availability: ShelfPlansAvailability,
): ShelfItem['guideStatus'] {
  if (preparation?.state === 'FAILED') return 'FAILED'
  if (preparation?.state === 'ACTIVE') return 'PREPARING'
  if (preparation?.state === 'COMPLETED') return 'LOADING'
  if (availability === 'LOADING') return 'LOADING'
  if (availability === 'UNAVAILABLE') return 'UNAVAILABLE'
  return 'UNAVAILABLE'
}

function strongestDocumentStatus(left: ShelfItem['documentStatus'], right: ShelfItem['documentStatus']) {
  const rank = { READY: 4, READING: 3, IMPORTING: 2, NEEDS_ATTENTION: 1 }
  return rank[left] >= rank[right] ? left : right
}

function strongestGuideStatus(left: ShelfItem['guideStatus'], right: ShelfItem['guideStatus']) {
  const rank = { READY: 6, PREPARING: 5, FAILED: 4, UNAVAILABLE: 3, LOADING: 2, NONE: 1 }
  return rank[left] >= rank[right] ? left : right
}

function latestPlan(plans: ShelfPlan[]) {
  return [...plans].sort((left, right) => right.createdAt.localeCompare(left.createdAt))[0]
}
