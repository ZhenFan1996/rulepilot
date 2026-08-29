package com.rulepilot.retrieval.application;

import com.rulepilot.ingestion.EmbeddingProvider;
import com.rulepilot.retrieval.VectorRuleSearch;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class VectorRuleSearchService implements VectorRuleSearch {

    static final String PHASE_DURATION_METRIC = "rulepilot.retrieval.vector.phase.duration";
    private final EmbeddingProvider embeddings;
    private final VectorRuleSearchRepository repository;
    private final MeterRegistry metrics;

    public VectorRuleSearchService(
            EmbeddingProvider embeddings,
            VectorRuleSearchRepository repository,
            MeterRegistry metrics) {
        this.embeddings = embeddings;
        this.repository = repository;
        this.metrics = metrics;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuleEvidenceHit> search(UUID documentVersionId, String query, int limit) {
        validateWindow(0, limit);
        return prepare(documentVersionId, query).search(0, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuleEvidenceHit> search(UUID documentVersionId, String query, int offset, int limit) {
        validateWindow(offset, limit);
        return prepare(documentVersionId, query).search(offset, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public PreparedSearch prepare(UUID documentVersionId, String query) {
        if (documentVersionId == null || query == null || query.isBlank()) {
            throw new IllegalArgumentException("document version and vector query are required");
        }
        var vectors = recordPhase("query-embedding", () -> embeddings.embed(List.of(query.strip())));
        if (vectors.size() != 1 || vectors.getFirst().values().size() != embeddings.dimensions()) {
            throw new IllegalStateException("embedding provider returned an invalid query vector");
        }
        var queryVector = vectors.getFirst();
        String providerId = embeddings.id();
        return (offset, limit) -> {
            validateWindow(offset, limit);
            return recordPhase(
                    "vector-repository",
                    () -> repository.search(documentVersionId, queryVector, providerId, offset, limit));
        };
    }

    private void validateWindow(int offset, int limit) {
        if (offset < 0 || limit < 1) {
            throw new IllegalArgumentException("vector search window is invalid");
        }
    }

    private <T> T recordPhase(String phase, Supplier<T> work) {
        long startedAt = System.nanoTime();
        try {
            return work.get();
        } finally {
            Timer.builder(PHASE_DURATION_METRIC)
                    .description("Vector retrieval phase duration")
                    .tag("phase", phase)
                    .register(metrics)
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }
}
