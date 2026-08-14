package com.rulepilot.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.ingestion.EmbeddingProvider;
import com.rulepilot.ingestion.EmbeddingProvider.EmbeddingVector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VectorRuleSearchServiceTest {

    @Test
    void attributesQueryEmbeddingAndRepositoryPhasesWithoutRecordingTheQuery() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        EmbeddingVector vector = new EmbeddingVector(List.of(1.0f, 0.0f));
        EmbeddingProvider provider = provider(List.of(vector));
        VectorRuleSearchRepository repository = (version, requestedVector, providerId, limit) -> {
            assertThat(requestedVector).isEqualTo(vector);
            assertThat(providerId).isEqualTo("test:2");
            assertThat(limit).isEqualTo(20);
            return List.of();
        };
        var service = new VectorRuleSearchService(provider, repository, metrics);

        assertThat(service.search(UUID.randomUUID(), "secret rulebook query", 50)).isEmpty();

        assertThat(metrics.find(VectorRuleSearchService.PHASE_DURATION_METRIC).timers())
                .extracting(timer -> timer.getId().getTag("phase"))
                .containsExactlyInAnyOrder("query-embedding", "vector-repository");
        assertThat(metrics.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .noneMatch(tag -> tag.getValue().contains("secret rulebook query")));
    }

    @Test
    void rejectsAnInvalidProviderShapeBeforeTheRepositoryRuns() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        java.util.concurrent.atomic.AtomicInteger repositoryCalls = new java.util.concurrent.atomic.AtomicInteger();
        var service = new VectorRuleSearchService(
                provider(List.of()),
                (version, vector, providerId, limit) -> {
                    repositoryCalls.incrementAndGet();
                    return List.of();
                },
                metrics);

        assertThatThrownBy(() -> service.search(UUID.randomUUID(), "setup", 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("embedding provider returned an invalid query vector");

        assertThat(repositoryCalls).hasValue(0);
        assertThat(metrics.find(VectorRuleSearchService.PHASE_DURATION_METRIC)
                        .tag("phase", "query-embedding").timer().count())
                .isEqualTo(1);
    }

    private EmbeddingProvider provider(List<EmbeddingVector> vectors) {
        return new EmbeddingProvider() {
            @Override
            public String id() {
                return "test:2";
            }

            @Override
            public int dimensions() {
                return 2;
            }

            @Override
            public List<EmbeddingVector> embed(List<String> texts) {
                assertThat(texts).hasSize(1);
                return vectors;
            }
        };
    }
}
