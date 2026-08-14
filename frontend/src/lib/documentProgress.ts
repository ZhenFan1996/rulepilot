export interface DocumentProcessingSnapshot {
  stage: string
  percentage: number
  processedPages: number
  totalPages: number
  complete: boolean
}

const stageRanks: Record<string, number> = {
  UPLOADED: 0,
  VALIDATING: 1,
  EXTRACTING: 2,
  RENDERING: 3,
  STRUCTURING: 4,
  CHUNKING: 5,
  EMBEDDING: 6,
  INDEXING: 7,
  READY: 8,
  FAILED: 8,
}

export function parseDocumentProgressSnapshot(value: unknown): DocumentProcessingSnapshot | null {
  if (!value || typeof value !== 'object') return null
  const candidate = value as Partial<DocumentProcessingSnapshot>
  const totalPages = candidate.totalPages === undefined ? candidate.processedPages : candidate.totalPages
  if (!(typeof candidate.stage === 'string'
    && candidate.stage in stageRanks
    && Number.isInteger(candidate.percentage)
    && candidate.percentage! >= 0
    && candidate.percentage! <= 100
    && Number.isInteger(candidate.processedPages)
    && candidate.processedPages! >= 0
    && Number.isInteger(totalPages)
    && totalPages! >= candidate.processedPages!
    && typeof candidate.complete === 'boolean'
    && (!candidate.complete || ['READY', 'FAILED'].includes(candidate.stage) && candidate.percentage === 100)
    && (candidate.complete || !['READY', 'FAILED'].includes(candidate.stage)))) return null
  return { ...candidate, totalPages } as DocumentProcessingSnapshot
}

export function mergeDocumentProgress(
  previous: DocumentProcessingSnapshot | undefined,
  incoming: DocumentProcessingSnapshot,
): DocumentProcessingSnapshot {
  if (!previous) return incoming
  if (previous.complete) return previous
  const previousRank = stageRanks[previous.stage] ?? 0
  const incomingRank = stageRanks[incoming.stage] ?? previousRank
  if (incomingRank < previousRank) return previous
  return {
    ...incoming,
    percentage: Math.max(previous.percentage, incoming.percentage),
    processedPages: Math.max(previous.processedPages, incoming.processedPages),
    totalPages: Math.max(previous.totalPages, incoming.totalPages),
  }
}
