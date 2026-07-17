package com.rulepilot.ingestion.application;

import com.rulepilot.ingestion.EmbeddingProvider;
import com.rulepilot.ingestion.RuleChunkEmbeddingIndexer;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class RuleChunkEmbeddingService implements RuleChunkEmbeddingIndexer {

    private final EmbeddingProvider provider;
    private final RuleChunkEmbeddingRepository chunks;

    public RuleChunkEmbeddingService(EmbeddingProvider provider, RuleChunkEmbeddingRepository chunks) {
        this.provider = provider;
        this.chunks = chunks;
    }

    @Override
    @Transactional
    public EmbeddingIndexReport index(UUID documentVersionId) {
        if (documentVersionId == null) throw new IllegalArgumentException("document version is required");
        var pending = chunks.findPending(documentVersionId, provider.id());
        if (pending.isEmpty()) return new EmbeddingIndexReport(provider.id(), provider.dimensions(), 0);
        var embeddings = provider.embed(pending.stream().map(RuleChunkEmbeddingRepository.EmbeddableChunk::embeddingText).toList());
        if (embeddings.size() != pending.size()
                || embeddings.stream().anyMatch(embedding -> embedding.values().size() != provider.dimensions())) {
            throw new IllegalStateException("embedding provider returned an invalid batch");
        }
        Instant now = Instant.now();
        for (int index = 0; index < pending.size(); index++) {
            chunks.save(pending.get(index).id(), embeddings.get(index), provider.id(), now);
        }
        return new EmbeddingIndexReport(provider.id(), provider.dimensions(), pending.size());
    }
}
