export interface PendingGuidePlan {
  documentVersionId: string
}

export interface PendingGuideImport {
  id: string
  title: string
  rulebookTitle?: string
  stage: string
  downloadedBytes: number
  totalBytes: number | null
  documentVersionId: string | null
  errorCode: string | null
  teachingHandoffState: 'NOT_REQUESTED' | 'WAITING_FOR_DOCUMENT' | 'LAUNCHING' | 'LAUNCHED' | 'FAILED'
  teachingPreparationRunId: string | null
  teachingErrorCode?: string | null
  updatedAt: string
}

export interface PendingGuidePreparationRun {
  id: string
  subjectId: string
  state: string
  updatedAt: string
  lastErrorCode?: string | null
}

export interface PendingGuideUploadHandoff {
  id: string
  documentVersionId: string
  editionId: string | null
  rulebookTitle: string
  state: 'WAITING_FOR_DOCUMENT' | 'LAUNCHING' | 'LAUNCHED' | 'FAILED'
  preparationRunId: string | null
  errorCode: string | null
  updatedAt: string
}

export interface PendingGuideDocument {
  document: { gameEditionId: string | null; title: string }
  latestVersion: { id: string; status?: string }
}

export interface PendingGuideCatalogGame {
  game: { name: string }
  editions: Array<{ id: string }>
}

export interface PendingGuideJourney {
  id: string
  title: string
  rulebookTitle: string | null
  documentVersionId: string | null
  importJobId: string | null
  phase: 'DOWNLOADING' | 'READING_RULEBOOK' | 'PREPARING_GUIDE' | 'FAILED'
  state: 'active' | 'failed'
  progress: number | null
  canReadRulebook: boolean
  updatedAt: string
}

export function buildPendingGuideJourneys(
  plans: PendingGuidePlan[],
  imports: PendingGuideImport[],
  preparationRuns: PendingGuidePreparationRun[],
  documents: PendingGuideDocument[],
  catalog: PendingGuideCatalogGame[],
  uploadHandoffs: PendingGuideUploadHandoff[] = [],
): PendingGuideJourney[] {
  const plannedVersions = new Set(plans.map(plan => plan.documentVersionId))
  const documentByVersion = new Map(documents.map(document => [document.latestVersion.id, document]))
  const gameByEdition = new Map(catalog.flatMap(entry =>
    entry.editions.map(edition => [edition.id, entry.game.name] as const)))
  const preparationByVersion = new Map(preparationRuns.map(run => [run.subjectId, run]))
  const representedVersions = new Set<string>()

  const fromImports = imports.flatMap((job): PendingGuideJourney[] => {
    if (job.teachingHandoffState === 'NOT_REQUESTED') return []
    if (job.documentVersionId && plannedVersions.has(job.documentVersionId)) return []
    if (job.documentVersionId) representedVersions.add(job.documentVersionId)
    const preparation = job.documentVersionId ? preparationByVersion.get(job.documentVersionId) : undefined
    const document = job.documentVersionId ? documentByVersion.get(job.documentVersionId) : undefined
    const preparationFailed = preparation != null
      && ['FAILED', 'DEGRADED', 'INSUFFICIENT_EVIDENCE'].includes(preparation.state)
    const failed = job.stage === 'FAILED'
      || job.teachingHandoffState === 'FAILED'
      || document?.latestVersion.status === 'FAILED'
      || preparationFailed
    return [{
      id: `import:${job.id}`,
      title: job.title,
      rulebookTitle: job.rulebookTitle && job.rulebookTitle !== job.title ? job.rulebookTitle : null,
      documentVersionId: job.documentVersionId,
      importJobId: job.id,
      phase: failed
        ? 'FAILED'
        : preparation || job.teachingHandoffState === 'LAUNCHING' || job.teachingHandoffState === 'LAUNCHED'
          ? 'PREPARING_GUIDE'
          : job.stage === 'COMPLETED' ? 'READING_RULEBOOK' : 'DOWNLOADING',
      state: failed ? 'failed' : 'active',
      progress: importProgress(job),
      canReadRulebook: document?.latestVersion.status === 'READY'
        || preparation != null
        || job.teachingHandoffState === 'LAUNCHING'
        || job.teachingHandoffState === 'LAUNCHED',
      updatedAt: preparation?.updatedAt ?? job.updatedAt,
    }]
  })

  const fromUploads = uploadHandoffs.flatMap((handoff): PendingGuideJourney[] => {
    if (plannedVersions.has(handoff.documentVersionId) || representedVersions.has(handoff.documentVersionId)) return []
    representedVersions.add(handoff.documentVersionId)
    const document = documentByVersion.get(handoff.documentVersionId)
    const editionId = handoff.editionId ?? document?.document.gameEditionId ?? null
    const gameTitle = editionId ? gameByEdition.get(editionId) : null
    const preparation = preparationByVersion.get(handoff.documentVersionId)
    const preparationFailed = preparation != null
      && ['FAILED', 'DEGRADED', 'INSUFFICIENT_EVIDENCE'].includes(preparation.state)
    const failed = handoff.state === 'FAILED'
      || document?.latestVersion.status === 'FAILED'
      || preparationFailed
    return [{
      id: `upload:${handoff.id}`,
      title: gameTitle ?? handoff.rulebookTitle,
      rulebookTitle: gameTitle && gameTitle !== handoff.rulebookTitle ? handoff.rulebookTitle : null,
      documentVersionId: handoff.documentVersionId,
      importJobId: null,
      phase: failed
        ? 'FAILED'
        : preparation || handoff.state === 'LAUNCHING' || handoff.state === 'LAUNCHED'
          ? 'PREPARING_GUIDE'
          : 'READING_RULEBOOK',
      state: failed ? 'failed' : 'active',
      progress: null,
      canReadRulebook: document?.latestVersion.status === 'READY'
        || preparation != null
        || handoff.state === 'LAUNCHING'
        || handoff.state === 'LAUNCHED',
      updatedAt: preparation?.updatedAt ?? handoff.updatedAt,
    }]
  })

  const fromPreparation = preparationRuns.flatMap((run): PendingGuideJourney[] => {
    if (plannedVersions.has(run.subjectId) || representedVersions.has(run.subjectId)) return []
    const document = documentByVersion.get(run.subjectId)
    if (!document) return []
    const gameTitle = document.document.gameEditionId
      ? gameByEdition.get(document.document.gameEditionId)
      : null
    const failed = ['FAILED', 'DEGRADED', 'INSUFFICIENT_EVIDENCE'].includes(run.state)
    return [{
      id: `preparation:${run.id}`,
      title: gameTitle ?? document.document.title,
      rulebookTitle: gameTitle && gameTitle !== document.document.title ? document.document.title : null,
      documentVersionId: run.subjectId,
      importJobId: null,
      phase: failed ? 'FAILED' : 'PREPARING_GUIDE',
      state: failed ? 'failed' : 'active',
      progress: null,
      canReadRulebook: true,
      updatedAt: run.updatedAt,
    }]
  })

  return [...fromImports, ...fromUploads, ...fromPreparation]
    .sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt))
}

function importProgress(job: PendingGuideImport) {
  if (job.stage === 'COMPLETED') return 100
  if (job.stage !== 'DOWNLOADING' || !job.totalBytes || job.totalBytes <= 0) return null
  return Math.min(100, Math.max(0, Math.round(job.downloadedBytes / job.totalBytes * 100)))
}
