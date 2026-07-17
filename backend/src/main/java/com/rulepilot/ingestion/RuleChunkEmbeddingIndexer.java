package com.rulepilot.ingestion;

import java.util.UUID;

public interface RuleChunkEmbeddingIndexer {

    EmbeddingIndexReport index(UUID documentVersionId);

    record EmbeddingIndexReport(String provider, int dimensions, int indexedChunks) {
        public EmbeddingIndexReport {
            if (provider == null || provider.isBlank() || dimensions < 1 || indexedChunks < 0) {
                throw new IllegalArgumentException("embedding index report is invalid");
            }
        }
    }
}
