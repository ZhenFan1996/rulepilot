package com.rulepilot.retrieval.application;

import com.rulepilot.ingestion.EmbeddingProvider;
import com.rulepilot.ingestion.EmbeddingIndexCoverage;
import com.rulepilot.retrieval.VectorRuleSearch;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class VectorRuleSearchService implements VectorRuleSearch {

    static final String PHASE_DURATION_METRIC = "rulepilot.retrieval.vector.phase.duration";
    static final String INDEX_AVAILABILITY_METRIC = "rulepilot.retrieval.vector.index.availability";
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
    public List<RuleEvidenceHit> search(UUID documentVersionId, String query, int limit) {
        validateWindow(0, limit);
        return prepare(documentVersionId, query).search(0, limit);
    }

    @Override
    public List<RuleEvidenceHit> search(UUID documentVersionId, String query, int offset, int limit) {
        validateWindow(offset, limit);
        return prepare(documentVersionId, query).search(offset, limit);
    }

    @Override
    public PreparedSearch prepare(UUID documentVersionId, String query) {
        if (documentVersionId == null || query == null || query.isBlank()) {
            throw new IllegalArgumentException("document version and vector query are required");
        }
        String providerId = embeddings.id();
        EmbeddingIndexCoverage coverage = recordPhase(
                "index-coverage", () -> repository.coverage(documentVersionId, providerId));
        if (!coverage.complete()) {
            recordIndexAvailability("incomplete");
            throw new IncompleteEmbeddingIndexException(documentVersionId, coverage);
        }
        recordIndexAvailability("complete");
        var vectors = recordPhase("query-embedding", () -> embeddings.embed(List.of(query.strip())));
        if (vectors.size() != 1 || vectors.getFirst().values().size() != embeddings.dimensions()) {
            throw new IllegalStateException("embedding provider returned an invalid query vector");
        }
        var queryVector = vectors.getFirst();
        return (offset, limit) -> {
            validateWindow(offset, limit);
            return recordPhase(
                    "vector-repository",
                    () -> repository.search(documentVersionId, queryVector, providerId, offset, limit));
        };
    }

    private void recordIndexAvailability(String outcome) {
        Counter.builder(INDEX_AVAILABILITY_METRIC)
                .description("Availability of the current-provider embedding index")
                .tag("outcome", outcome)
                .register(metrics)
                .increment();
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
