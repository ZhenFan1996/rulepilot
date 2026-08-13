import { describe, expect, it } from 'vitest'

import { mergeDocumentProgress } from './documentProgress'

describe('document progress continuity', () => {
  it('ignores replayed events from an earlier processing stage', () => {
    const current = { stage: 'STRUCTURING', percentage: 82, processedPages: 20, totalPages: 20, complete: false }
    const replayed = { stage: 'RENDERING', percentage: 55, processedPages: 11, totalPages: 20, complete: false }
    expect(mergeDocumentProgress(current, replayed)).toEqual(current)
  })

  it('never lowers counters and keeps a terminal result terminal', () => {
    const current = { stage: 'RENDERING', percentage: 60, processedPages: 12, totalPages: 20, complete: false }
    const next = { stage: 'RENDERING', percentage: 58, processedPages: 11, totalPages: 20, complete: false }
    expect(mergeDocumentProgress(current, next)).toEqual({ ...next, percentage: 60, processedPages: 12 })
    const ready = { stage: 'READY', percentage: 100, processedPages: 20, totalPages: 20, complete: true }
    expect(mergeDocumentProgress(ready, next)).toEqual(ready)
  })

  it('keeps every ingestion phase monotonic through indexing', () => {
    const indexed = { stage: 'INDEXING', percentage: 95, processedPages: 20, totalPages: 20, complete: false }
    const delayedEmbedding = { stage: 'EMBEDDING', percentage: 90, processedPages: 20, totalPages: 20, complete: false }
    const validating = { stage: 'VALIDATING', percentage: 15, processedPages: 0, totalPages: 0, complete: false }
    const chunking = { stage: 'CHUNKING', percentage: 85, processedPages: 20, totalPages: 20, complete: false }

    expect(mergeDocumentProgress(indexed, delayedEmbedding)).toEqual(indexed)
    expect(mergeDocumentProgress(validating, chunking)).toEqual(chunking)
  })
})
