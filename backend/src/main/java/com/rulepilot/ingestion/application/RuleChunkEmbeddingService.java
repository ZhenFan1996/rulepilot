package com.rulepilot.ingestion.application;

import com.rulepilot.ingestion.EmbeddingProvider;
import com.rulepilot.ingestion.EmbeddingIndexCoverage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class RuleChunkEmbeddingService {

    static final String PHASE_DURATION_METRIC = "rulepilot.document.processing.embedding.phase.duration";
    static final String INDEX_OUTCOME_METRIC = "rulepilot.document.processing.embedding.index";
    private static final Logger LOGGER = LoggerFactory.getLogger(RuleChunkEmbeddingService.class);

    private final EmbeddingProvider provider;
    private final RuleChunkEmbeddingRepository chunks;
    private final MeterRegistry metrics;

    public RuleChunkEmbeddingService(
            EmbeddingProvider provider,
            RuleChunkEmbeddingRepository chunks,
            MeterRegistry metrics) {
        this.provider = provider;
        this.chunks = chunks;
        this.metrics = metrics;
    }

    public EmbeddingIndexCoverage index(UUID documentVersionId) {
        if (documentVersionId == null) throw new IllegalArgumentException("document version is required");
        String providerId = provider.id();
        int batchSize = provider.batchSize();
        if (batchSize < 1) throw new IllegalStateException("embedding provider batch size is invalid");
        long pendingLoadNanos = 0;
        long providerNanos = 0;
        long persistenceNanos = 0;
        int indexed = 0;
        while (true) {
            long pendingLoadStartedAt = System.nanoTime();
            var pending = chunks.findPending(documentVersionId, providerId, batchSize);
            pendingLoadNanos += recordPhase("pending-load", pendingLoadStartedAt);
            if (pending.isEmpty()) break;

            long providerStartedAt = System.nanoTime();
            var embeddings = provider.embed(pending.stream()
                    .map(RuleChunkEmbeddingRepository.EmbeddableChunk::embeddingText)
                    .toList());
            providerNanos += recordPhase("provider", providerStartedAt);
            if (embeddings.size() != pending.size()
                    || embeddings.stream().anyMatch(embedding -> embedding.values().size() != provider.dimensions())) {
                recordOutcome("failed");
                throw new IllegalStateException("embedding provider returned an invalid batch");
            }
            var indexedBatch = java.util.stream.IntStream.range(0, pending.size())
                    .mapToObj(index -> new RuleChunkEmbeddingRepository.IndexedChunk(
                            pending.get(index).id(), embeddings.get(index)))
                    .toList();
            long persistenceStartedAt = System.nanoTime();
            chunks.saveBatch(indexedBatch, providerId, Instant.now());
            persistenceNanos += recordPhase("persistence", persistenceStartedAt);
            indexed += indexedBatch.size();
        }
        EmbeddingIndexCoverage coverage = chunks.coverage(documentVersionId, providerId);
        if (!coverage.complete()) {
            recordOutcome("incomplete");
            throw new IllegalStateException("embedding index is incomplete after indexing");
        }
        recordOutcome("complete");
        LOGGER.info(
                "Document embedding completed: indexedChunks={}, totalChunks={}, pendingLoadMs={}, providerMs={}, persistenceMs={}",
                indexed,
                coverage.totalChunks(),
                milliseconds(pendingLoadNanos),
                milliseconds(providerNanos),
                milliseconds(persistenceNanos));
        return coverage;
    }

    private void recordOutcome(String outcome) {
        Counter.builder(INDEX_OUTCOME_METRIC)
                .description("Document embedding index completion outcome")
                .tag("outcome", outcome)
                .register(metrics)
                .increment();
    }

    private long recordPhase(String phase, long startedAt) {
        long duration = System.nanoTime() - startedAt;
        Timer.builder(PHASE_DURATION_METRIC)
                .description("Document embedding phase duration")
                .tag("phase", phase)
                .register(metrics)
                .record(duration, TimeUnit.NANOSECONDS);
        return duration;
    }

    private long milliseconds(long duration) {
        return TimeUnit.NANOSECONDS.toMillis(duration);
    }
}
