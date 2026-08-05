export interface DocumentProcessingSnapshot {
  stage: string
  percentage: number
  processedPages: number
  totalPages: number
  complete: boolean
}

const stageRanks: Record<string, number> = {
  UPLOADED: 0,
  EXTRACTING: 1,
  RENDERING: 2,
  STRUCTURING: 3,
  READY: 4,
  FAILED: 4,
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
