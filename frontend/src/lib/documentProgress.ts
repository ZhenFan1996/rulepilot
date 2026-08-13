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
