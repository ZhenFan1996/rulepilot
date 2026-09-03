package com.rulepilot.retrieval.application;

import com.rulepilot.ingestion.EmbeddingIndexCoverage;
import java.util.UUID;

public final class IncompleteEmbeddingIndexException extends IllegalStateException {

    private final UUID documentVersionId;
    private final EmbeddingIndexCoverage coverage;

    public IncompleteEmbeddingIndexException(UUID documentVersionId, EmbeddingIndexCoverage coverage) {
        super("current embedding index is incomplete: %d/%d chunks"
                .formatted(coverage.indexedChunks(), coverage.totalChunks()));
        this.documentVersionId = documentVersionId;
        this.coverage = coverage;
    }

    public UUID documentVersionId() {
        return documentVersionId;
    }

    public EmbeddingIndexCoverage coverage() {
        return coverage;
    }
}
