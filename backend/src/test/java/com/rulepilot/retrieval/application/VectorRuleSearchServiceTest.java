package com.rulepilot.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.ingestion.EmbeddingProvider;
import com.rulepilot.ingestion.EmbeddingProvider.EmbeddingVector;
import com.rulepilot.ingestion.EmbeddingIndexCoverage;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
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
        VectorRuleSearchRepository repository = new VectorRuleSearchRepository() {
            @Override
            public EmbeddingIndexCoverage coverage(UUID version, String providerId) {
                return new EmbeddingIndexCoverage(3, 3);
            }

            @Override
            public List<RuleEvidenceHit> search(
                    UUID version, EmbeddingVector requestedVector, String providerId, int limit) {
                assertThat(requestedVector).isEqualTo(vector);
                assertThat(providerId).isEqualTo("test:2");
                assertThat(limit).isEqualTo(50);
                return List.of();
            }
        };
        var service = new VectorRuleSearchService(provider, repository, metrics);

        assertThat(service.search(UUID.randomUUID(), "secret rulebook query", 50)).isEmpty();

        assertThat(metrics.find(VectorRuleSearchService.PHASE_DURATION_METRIC).timers())
                .extracting(timer -> timer.getId().getTag("phase"))
                .containsExactlyInAnyOrder("index-coverage", "query-embedding", "vector-repository");
        assertThat(metrics.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .noneMatch(tag -> tag.getValue().contains("secret rulebook query")));
    }

    @Test
    void rejectsAnInvalidProviderShapeBeforeTheRepositoryRuns() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        java.util.concurrent.atomic.AtomicInteger repositoryCalls = new java.util.concurrent.atomic.AtomicInteger();
        var service = new VectorRuleSearchService(provider(List.of()), new VectorRuleSearchRepository() {
            @Override
            public EmbeddingIndexCoverage coverage(UUID version, String providerId) {
                return new EmbeddingIndexCoverage(2, 2);
            }

            @Override
            public List<RuleEvidenceHit> search(
                    UUID version, EmbeddingVector vector, String providerId, int limit) {
                repositoryCalls.incrementAndGet();
                return List.of();
            }
        }, metrics);

        assertThatThrownBy(() -> service.search(UUID.randomUUID(), "setup", 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("embedding provider returned an invalid query vector");

        assertThat(repositoryCalls).hasValue(0);
        assertThat(metrics.find(VectorRuleSearchService.PHASE_DURATION_METRIC)
                        .tag("phase", "query-embedding").timer().count())
                .isEqualTo(1);
    }

    @Test
    void passesTheStableOffsetToTheVectorRepository() {
        EmbeddingVector vector = new EmbeddingVector(List.of(1.0f, 0.0f));
        java.util.concurrent.atomic.AtomicInteger offset = new java.util.concurrent.atomic.AtomicInteger();
        VectorRuleSearchRepository repository = new VectorRuleSearchRepository() {
            @Override
            public EmbeddingIndexCoverage coverage(UUID version, String providerId) {
                return new EmbeddingIndexCoverage(4, 4);
            }

            @Override
            public List<com.rulepilot.retrieval.evidence.RuleEvidenceHit> search(
                    UUID version, EmbeddingVector query, String providerId, int limit) {
                throw new AssertionError("paged vector search must use the offset-aware repository contract");
            }

            @Override
            public List<com.rulepilot.retrieval.evidence.RuleEvidenceHit> search(
                    UUID version, EmbeddingVector query, String providerId, int resultOffset, int limit) {
                offset.set(resultOffset);
                assertThat(limit).isEqualTo(4);
                return List.of();
            }
        };
        var service = new VectorRuleSearchService(
                provider(List.of(vector)), repository, new SimpleMeterRegistry());

        assertThat(service.search(UUID.randomUUID(), "turn order", 90, 4)).isEmpty();

        assertThat(offset).hasValue(90);
    }

    @Test
    void rejectsAnIncompleteCurrentProviderIndexBeforeCallingTheProvider() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        java.util.concurrent.atomic.AtomicInteger providerCalls = new java.util.concurrent.atomic.AtomicInteger();
        EmbeddingProvider provider = new EmbeddingProvider() {
            @Override
            public String id() {
                return "current:2";
            }

            @Override
            public int dimensions() {
                return 2;
            }

            @Override
            public List<EmbeddingVector> embed(List<String> texts) {
                providerCalls.incrementAndGet();
                return List.of(new EmbeddingVector(List.of(1.0f, 0.0f)));
            }
        };
        VectorRuleSearchRepository repository = new VectorRuleSearchRepository() {
            @Override
            public EmbeddingIndexCoverage coverage(UUID version, String providerId) {
                assertThat(providerId).isEqualTo("current:2");
                return new EmbeddingIndexCoverage(7, 5);
            }

            @Override
            public List<RuleEvidenceHit> search(
                    UUID version, EmbeddingVector vector, String providerId, int limit) {
                throw new AssertionError("incomplete indexes must not be searched");
            }
        };
        var service = new VectorRuleSearchService(provider, repository, metrics);

        assertThatThrownBy(() -> service.prepare(UUID.randomUUID(), "setup"))
                .isInstanceOf(IncompleteEmbeddingIndexException.class)
                .hasMessage("current embedding index is incomplete: 5/7 chunks");

        assertThat(providerCalls).hasValue(0);
        assertThat(metrics.find(VectorRuleSearchService.PHASE_DURATION_METRIC)
                        .tag("phase", "query-embedding")
                        .timer())
                .isNull();
        assertThat(metrics.counter(
                                VectorRuleSearchService.INDEX_AVAILABILITY_METRIC,
                                "outcome",
                                "incomplete")
                        .count())
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
