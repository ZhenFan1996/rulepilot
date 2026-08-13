import { describe, expect, it } from 'vitest'

import {
  parseActiveTeachingRuns,
  parseDocumentProgress,
  parseExpectedAssistantRun,
  parseOwnedDocuments,
  parseRulebookImports,
  parseTeachingPlans,
  parseUploadedHandoffs,
  validateDocumentRelationships,
} from './backgroundWorkSnapshot'

describe('background work snapshot boundary', () => {
  const run = {
    id: 'run-1', mode: 'TEACHING' as const, subjectId: 'plan-1', ownerUsername: 'player',
    state: 'LESSON_COMPOSITION', updatedAt: '2026-08-13T00:00:00Z',
  }
  const documentList = parseOwnedDocuments([{
    document: { id: 'document-1', title: 'rules.pdf', createdBy: 'player' },
    latestVersion: { id: 'version-1', status: 'EXTRACTING' },
  }], 'player')

  it('accepts the authoritative active-run identity and rejects cross-account or duplicate subjects', () => {
    expect(parseActiveTeachingRuns([run], 'player')).toEqual([run])
    expect(() => parseActiveTeachingRuns([{ ...run, ownerUsername: 'other' }], 'player')).toThrow()
    expect(() => parseActiveTeachingRuns([run, { ...run, id: 'run-2' }], 'player')).toThrow()
    expect(() => parseExpectedAssistantRun({ run: { ...run, subjectId: 'wrong-plan' } }, {
      id: 'run-1', mode: 'TEACHING', subjectId: 'plan-1', ownerUsername: 'player',
    })).toThrow()
  })

  it('bounds teaching plans and owner-scoped documents', () => {
    expect(parseTeachingPlans([{ id: 'plan-1', gameTitle: '可信标题' }])).toEqual([
      { id: 'plan-1', gameTitle: '可信标题' },
    ])
    expect(documentList[0]?.latestVersion.id).toBe('version-1')
    expect(() => parseOwnedDocuments([{
      document: { id: 'document-1', title: 'rules.pdf', createdBy: 'other' },
      latestVersion: { id: 'version-1', status: 'READY' },
    }], 'player')).toThrow()
  })

  it('requires imports and upload handoffs to bind to coherent document versions', () => {
    const imports = parseRulebookImports([{
      id: 'import-1', title: 'Rules', sourceDomain: 'publisher.example', stage: 'DOWNLOADING',
      downloadedBytes: 20, totalBytes: 100, documentVersionId: null, errorCode: null,
      teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null,
      teachingErrorCode: null, updatedAt: '2026-08-13T00:00:00Z',
    }])
    const handoffs = parseUploadedHandoffs([{
      id: 'handoff-1', documentVersionId: 'version-1', title: 'Game', rulebookTitle: 'rules.pdf',
      state: 'WAITING_FOR_DOCUMENT', preparationRunId: null, errorCode: null,
      updatedAt: '2026-08-13T00:00:00Z',
    }])
    expect(() => validateDocumentRelationships(imports, handoffs, documentList)).not.toThrow()
    expect(() => validateDocumentRelationships(imports, [{ ...handoffs[0]!, documentVersionId: 'wrong' }], documentList))
      .toThrow()
    expect(() => parseRulebookImports([{ ...imports[0]!, downloadedBytes: -1 }])).toThrow()
    expect(() => parseUploadedHandoffs([{ ...handoffs[0]!, preparationRunId: 'run-1' }])).toThrow()
  })

  it('accepts only bounded, internally coherent progress snapshots', () => {
    expect(parseDocumentProgress({
      stage: 'INDEXING', percentage: 95, processedPages: 10, totalPages: 10, complete: false,
    }).stage).toBe('INDEXING')
    expect(() => parseDocumentProgress({
      stage: 'READY', percentage: 95, processedPages: 10, totalPages: 10, complete: true,
    })).toThrow()
    expect(() => parseDocumentProgress({
      stage: 'UNTRUSTED', percentage: 50, processedPages: 2, totalPages: 10, complete: false,
    })).toThrow()
  })
})
