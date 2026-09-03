package com.rulepilot.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.ingestion.EmbeddingProvider;
import com.rulepilot.ingestion.EmbeddingProvider.EmbeddingVector;
import com.rulepilot.ingestion.EmbeddingIndexCoverage;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.HybridRuleSearch.RetrievalOptions;
import com.rulepilot.retrieval.VectorRuleSearch;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HybridSearchSourceAvailabilityPreparedSessionTest {

    @Test
    void reportsPartialWithoutInventingContinuationWhenOnlyOneChannelIsAvailable() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit lexical = hit(versionId, "ACTIONS", 1);
        var service = new HybridRuleSearchService(
                (version, query, limit) -> List.of(lexical),
                (version, query, limit) -> {
                    throw new IllegalStateException("embedding unavailable");
                },
                (version, ids) -> List.of(complete(lexical)),
                new SimpleMeterRegistry());

        HybridRuleSearch.SearchPage page = service.searchPage(
                versionId, "legal action", new RetrievalOptions(2, Set.of(), null));

        assertThat(page.hits()).hasSize(1);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.sourceAvailability()).isEqualTo(HybridRuleSearch.SourceAvailability.PARTIAL);
    }

    @Test
    void reportsCompleteForHealthyChannelsAndCompatibilityPages() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit indexed = hit(versionId, "SETUP", 1);
        var service = new HybridRuleSearchService(
                (version, query, limit) -> List.of(indexed),
                (version, query, limit) -> List.of(indexed),
                (version, ids) -> List.of(complete(indexed)),
                new SimpleMeterRegistry());

        HybridRuleSearch.SearchPage page = service.searchPage(
                versionId, "setup", new RetrievalOptions(2, Set.of(), null));
        HybridRuleSearch.SearchPage compatibilityPage = new HybridRuleSearch.SearchPage(List.of(), false);

        assertThat(page.sourceAvailability()).isEqualTo(HybridRuleSearch.SourceAvailability.COMPLETE);
        assertThat(compatibilityPage.sourceAvailability()).isEqualTo(HybridRuleSearch.SourceAvailability.COMPLETE);
    }

    @Test
    void embedsOnceAcrossPhysicalWindowsButAgainForTheNextLogicalSearch() {
        UUID versionId = UUID.randomUUID();
        AtomicInteger embeddingCalls = new AtomicInteger();
        List<Integer> repositoryOffsets = new ArrayList<>();
        EmbeddingVector queryVector = new EmbeddingVector(List.of(1.0f, 0.0f));
        List<RuleEvidenceHit> indexed = List.of(
                hit(versionId, "SETUP", 1),
                hit(versionId, "SETUP", 2),
                hit(versionId, "SCORING", 3),
                hit(versionId, "SCORING", 4));
        EmbeddingProvider embeddings = new EmbeddingProvider() {
            @Override
            public String id() {
                return "prepared-test:2";
            }

            @Override
            public int dimensions() {
                return 2;
            }

            @Override
            public List<EmbeddingVector> embed(List<String> texts) {
                embeddingCalls.incrementAndGet();
                assertThat(texts).containsExactly("end scoring");
                return List.of(queryVector);
            }
        };
        VectorRuleSearchRepository repository = new VectorRuleSearchRepository() {
            @Override
            public EmbeddingIndexCoverage coverage(UUID version, String provider) {
                return new EmbeddingIndexCoverage(indexed.size(), indexed.size());
            }

            @Override
            public List<RuleEvidenceHit> search(
                    UUID version, EmbeddingVector vector, String provider, int limit) {
                return search(version, vector, provider, 0, limit);
            }

            @Override
            public List<RuleEvidenceHit> search(
                    UUID version, EmbeddingVector vector, String provider, int offset, int limit) {
                assertThat(version).isEqualTo(versionId);
                assertThat(vector).isEqualTo(queryVector);
                assertThat(provider).isEqualTo("prepared-test:2");
                repositoryOffsets.add(offset);
                if (offset >= indexed.size()) return List.of();
                return indexed.subList(offset, Math.min(indexed.size(), offset + limit));
            }
        };
        VectorRuleSearch vector = new VectorRuleSearchService(
                embeddings, repository, new SimpleMeterRegistry());
        var service = new HybridRuleSearchService(
                (version, query, limit) -> List.of(),
                vector,
                (version, ids) -> indexed.stream()
                        .filter(candidate -> ids.contains(candidate.chunkId()))
                        .map(this::complete)
                        .toList(),
                new SimpleMeterRegistry());

        HybridRuleSearch.SearchPage first = service.searchPage(
                versionId, "end scoring", new RetrievalOptions(1, Set.of("SCORING"), null));
        HybridRuleSearch.SearchPage second = service.searchPage(
                versionId, "end scoring", new RetrievalOptions(1, Set.of("SCORING"), null));

        assertThat(first.hits()).hasSize(1);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.hits()).hasSize(1);
        assertThat(embeddingCalls).hasValue(2);
        assertThat(repositoryOffsets).containsExactly(0, 0);
    }

    private RuleEvidenceHit hit(UUID versionId, String sectionType, int page) {
        return new RuleEvidenceHit(
                UUID.randomUUID(), versionId, sectionType, sectionType, "Indexed evidence", page, page, 0.8);
    }

    private RuleEvidenceHit complete(RuleEvidenceHit indexed) {
        return new RuleEvidenceHit(
                indexed.chunkId(), indexed.documentVersionId(), indexed.sectionType(), indexed.heading(),
                "Canonical evidence " + indexed.pageFrom(), indexed.pageFrom(), indexed.pageTo(), 1.0);
    }
}
