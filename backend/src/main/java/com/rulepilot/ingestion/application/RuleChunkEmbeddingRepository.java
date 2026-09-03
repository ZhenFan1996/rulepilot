package com.rulepilot.ingestion.application;

import com.rulepilot.ingestion.EmbeddingProvider.EmbeddingVector;
import com.rulepilot.ingestion.EmbeddingIndexCoverage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RuleChunkEmbeddingRepository {

    List<EmbeddableChunk> findPending(UUID documentVersionId, String provider, int limit);

    void saveBatch(List<IndexedChunk> indexedChunks, String provider, Instant embeddedAt);

    EmbeddingIndexCoverage coverage(UUID documentVersionId, String provider);

    record EmbeddableChunk(UUID id, String heading, String content) {
        public EmbeddableChunk {
            if (id == null || heading == null || heading.isBlank() || content == null || content.isBlank()) {
                throw new IllegalArgumentException("embeddable rule chunk is invalid");
            }
        }

        public String embeddingText() {
            return heading + "\n" + content;
        }
    }

    record IndexedChunk(UUID id, EmbeddingVector embedding) {
        public IndexedChunk {
            if (id == null || embedding == null) {
                throw new IllegalArgumentException("indexed rule chunk is invalid");
            }
        }
    }
}
