package com.rulepilot.ingestion.application;

import com.rulepilot.ingestion.EmbeddingProvider.EmbeddingVector;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RuleChunkEmbeddingRepository {

    List<EmbeddableChunk> findPending(UUID documentVersionId, String provider);

    void save(UUID chunkId, EmbeddingVector embedding, String provider, Instant embeddedAt);

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
}
