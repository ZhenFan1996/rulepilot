package com.rulepilot.ingestion.application;

import com.rulepilot.ingestion.EmbeddingProvider;
import com.rulepilot.ingestion.RuleChunkEmbeddingIndexer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class RuleChunkEmbeddingService implements RuleChunkEmbeddingIndexer {

    static final String PHASE_DURATION_METRIC = "rulepilot.document.processing.embedding.phase.duration";
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

    @Override
    @Transactional
    public EmbeddingIndexReport index(UUID documentVersionId) {
        if (documentVersionId == null) throw new IllegalArgumentException("document version is required");
        long pendingLoadStartedAt = System.nanoTime();
        var pending = chunks.findPending(documentVersionId, provider.id());
        long pendingLoadNanos = recordPhase("pending-load", pendingLoadStartedAt);
        if (pending.isEmpty()) {
            LOGGER.info(
                    "Document embedding completed: chunks=0, pendingLoadMs={}, providerMs=0, persistenceMs=0",
                    milliseconds(pendingLoadNanos));
            return new EmbeddingIndexReport(provider.id(), provider.dimensions(), 0);
        }
        long providerStartedAt = System.nanoTime();
        var embeddings = provider.embed(pending.stream()
                .map(RuleChunkEmbeddingRepository.EmbeddableChunk::embeddingText)
                .toList());
        long providerNanos = recordPhase("provider", providerStartedAt);
        if (embeddings.size() != pending.size()
                || embeddings.stream().anyMatch(embedding -> embedding.values().size() != provider.dimensions())) {
            throw new IllegalStateException("embedding provider returned an invalid batch");
        }
        Instant now = Instant.now();
        long persistenceStartedAt = System.nanoTime();
        for (int index = 0; index < pending.size(); index++) {
            chunks.save(pending.get(index).id(), embeddings.get(index), provider.id(), now);
        }
        long persistenceNanos = recordPhase("persistence", persistenceStartedAt);
        LOGGER.info(
                "Document embedding completed: chunks={}, pendingLoadMs={}, providerMs={}, persistenceMs={}",
                pending.size(),
                milliseconds(pendingLoadNanos),
                milliseconds(providerNanos),
                milliseconds(persistenceNanos));
        return new EmbeddingIndexReport(provider.id(), provider.dimensions(), pending.size());
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
