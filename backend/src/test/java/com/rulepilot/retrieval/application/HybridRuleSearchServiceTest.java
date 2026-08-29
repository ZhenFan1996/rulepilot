package com.rulepilot.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.retrieval.HybridRuleSearch.RetrievalOptions;
import com.rulepilot.retrieval.FullTextRuleSearch;
import com.rulepilot.retrieval.VectorRuleSearch;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HybridRuleSearchServiceTest {

    @Test
    void logicalContinuationDoesNotLosePendingCandidatesFromEitherChannel() {
        UUID versionId = UUID.randomUUID();
        List<RuleEvidenceHit> lexical = List.of(
                hit(new UUID(0, 1), versionId, "ACTIONS", 1),
                hit(new UUID(0, 4), versionId, "ACTIONS", 2));
        List<RuleEvidenceHit> semantic = List.of(
                hit(new UUID(0, 2), versionId, "ACTIONS", 3),
                hit(new UUID(0, 3), versionId, "ACTIONS", 4));
        List<RuleEvidenceHit> all = java.util.stream.Stream.concat(lexical.stream(), semantic.stream()).toList();
        var service = new HybridRuleSearchService(
                fullTextPages(lexical, new ArrayList<>()),
                vectorPages(semantic, new ArrayList<>()),
                (version, ids) -> all.stream()
                        .filter(candidate -> ids.contains(candidate.chunkId()))
                        .map(candidate -> complete(candidate, "Complete page " + candidate.pageFrom()))
                        .toList(),
                new SimpleMeterRegistry());

        var first = service.searchPage(
                versionId, "movement", new RetrievalOptions(2, Set.of(), null));
        var second = service.searchPage(
                versionId, "movement", new RetrievalOptions(2, Set.of(), null, null, 2));

        assertThat(first.hits()).hasSize(2);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.hits()).hasSize(2);
        assertThat(second.hasMore()).isFalse();
        assertThat(first.hits()).extracting(result -> result.evidence().chunkId())
                .containsExactly(new UUID(0, 1), new UUID(0, 2));
        assertThat(first.hits()).allSatisfy(result ->
                assertThat(java.util.stream.Stream.of(result.fullTextRank(), result.vectorRank())
                                .filter(java.util.Objects::nonNull)
                                .toList())
                        .containsExactly(1));
        assertThat(second.hits()).extracting(result -> result.evidence().chunkId())
                .containsExactly(new UUID(0, 3), new UUID(0, 4));
        assertThat(second.hits()).allSatisfy(result ->
                assertThat(java.util.stream.Stream.of(result.fullTextRank(), result.vectorRank())
                                .filter(java.util.Objects::nonNull)
                                .toList())
                        .containsExactly(2));
        assertThat(java.util.stream.Stream.concat(first.hits().stream(), second.hits().stream())
                        .map(result -> result.evidence().chunkId())
                        .toList())
                .containsExactlyInAnyOrderElementsOf(all.stream().map(RuleEvidenceHit::chunkId).toList());
    }

    @Test
    void stopsScanningAfterTheEligibleLookaheadInsteadOfMaterializingTheCorpus() {
        UUID versionId = UUID.randomUUID();
        List<RuleEvidenceHit> lexical = java.util.stream.IntStream.rangeClosed(1, 100)
                .mapToObj(page -> hit(versionId, "ACTIONS", page))
                .toList();
        List<RuleEvidenceHit> semantic = java.util.stream.IntStream.rangeClosed(101, 200)
                .mapToObj(page -> hit(versionId, "ACTIONS", page))
                .toList();
        List<Integer> lexicalOffsets = new ArrayList<>();
        List<Integer> semanticOffsets = new ArrayList<>();
        List<RuleEvidenceHit> all = java.util.stream.Stream.concat(lexical.stream(), semantic.stream()).toList();
        var service = new HybridRuleSearchService(
                fullTextPages(lexical, lexicalOffsets),
                vectorPages(semantic, semanticOffsets),
                (version, ids) -> all.stream()
                        .filter(candidate -> ids.contains(candidate.chunkId()))
                        .map(candidate -> complete(candidate, "Complete page " + candidate.pageFrom()))
                        .toList(),
                new SimpleMeterRegistry());

        var page = service.searchPage(
                versionId, "movement", new RetrievalOptions(2, Set.of(), null));

        assertThat(page.hits()).hasSize(2);
        assertThat(page.hasMore()).isTrue();
        assertThat(lexicalOffsets).containsExactly(0);
        assertThat(semanticOffsets).containsExactly(0);
    }

    @Test
    void scansPastFilteredSourceWindowsAndUsesARealEligibleLookahead() {
        UUID versionId = UUID.randomUUID();
        List<RuleEvidenceHit> lexical = List.of(
                hit(versionId, "SETUP", 1),
                hit(versionId, "SETUP", 2),
                hit(versionId, "SCORING", 3),
                hit(versionId, "SCORING", 4));
        List<Integer> offsets = new ArrayList<>();
        var service = new HybridRuleSearchService(
                fullTextPages(lexical, offsets),
                vectorPages(List.of(), new ArrayList<>()),
                (version, ids) -> lexical.stream()
                        .filter(candidate -> ids.contains(candidate.chunkId()))
                        .map(candidate -> complete(candidate, "Complete page " + candidate.pageFrom()))
                        .toList(),
                new SimpleMeterRegistry());

        var first = service.searchPage(
                versionId, "points", new RetrievalOptions(1, Set.of("SCORING"), null));
        UUID published = first.hits().getFirst().evidence().chunkId();
        var second = service.searchPage(
                versionId,
                "points",
                new RetrievalOptions(1, Set.of("SCORING"), null, null, 0, Set.of(published)));

        assertThat(first.hits()).singleElement().satisfies(hit ->
                assertThat(hit.evidence().sectionType()).isEqualTo("SCORING"));
        assertThat(first.hasMore()).isTrue();
        assertThat(second.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.evidence().sectionType()).isEqualTo("SCORING");
            assertThat(hit.evidence().chunkId()).isNotEqualTo(published);
        });
        assertThat(second.hasMore()).isFalse();
        assertThat(offsets).containsSubsequence(0, 1, 2, 3);
    }

    @Test
    void fusesRanksFiltersMetadataAndBoostsCurrentLessonSection() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit setup = hit(versionId, "SETUP", 1);
        RuleEvidenceHit scoring = hit(versionId, "SCORING", 2);
        var service = new HybridRuleSearchService(
                (version, query, limit) -> List.of(setup, scoring),
                (version, query, limit) -> List.of(scoring, setup),
                (version, chunkIds) -> List.of(
                        complete(setup, "Complete setup evidence"),
                        complete(scoring, "Complete scoring evidence")).stream()
                        .filter(candidate -> chunkIds.contains(candidate.chunkId()))
                        .toList(),
                new SimpleMeterRegistry());

        var results = service.search(
                versionId,
                "points after the game",
                new RetrievalOptions(5, Set.of("scoring"), "SCORING"));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.evidence().sectionType()).isEqualTo("SCORING");
            assertThat(result.fullTextRank()).isEqualTo(2);
            assertThat(result.vectorRank()).isEqualTo(1);
            assertThat(result.currentSectionBoosted()).isTrue();
            assertThat(result.score()).isGreaterThan(0.03);
            assertThat(result.evidence().excerpt()).isEqualTo("Complete scoring evidence");
        });
    }

    @Test
    void preservesTheRequestedCandidateDepthAcrossBothRetrievalSources() {
        UUID versionId = UUID.randomUUID();
        List<RuleEvidenceHit> lexical = java.util.stream.IntStream.rangeClosed(1, 15)
                .mapToObj(page -> hit(versionId, "ACTIONS", page))
                .toList();
        List<RuleEvidenceHit> vectorOnly = java.util.stream.IntStream.rangeClosed(16, 30)
                .mapToObj(page -> hit(versionId, "ACTIONS", page))
                .toList();
        java.util.concurrent.atomic.AtomicInteger fullTextLimit = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger vectorLimit = new java.util.concurrent.atomic.AtomicInteger();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        var service = new HybridRuleSearchService(
                (version, query, limit) -> {
                    fullTextLimit.set(limit);
                    return lexical;
                },
                (version, query, limit) -> {
                    vectorLimit.set(limit);
                    return vectorOnly;
                },
                (version, chunkIds) -> java.util.stream.Stream.concat(lexical.stream(), vectorOnly.stream())
                        .filter(hit -> chunkIds.contains(hit.chunkId()))
                        .map(hit -> complete(hit, "Complete evidence " + hit.pageFrom()))
                        .toList(),
                metrics);

        var results = service.search(versionId, "ending scoring", new RetrievalOptions(30, Set.of(), null));

        assertThat(results).extracting(result -> result.evidence().pageFrom())
                .containsExactlyInAnyOrderElementsOf(java.util.stream.IntStream.rangeClosed(1, 30).boxed().toList());
        assertThat(fullTextLimit).hasValue(30);
        assertThat(vectorLimit).hasValue(30);
        assertThat(metrics.find(HybridRuleSearchService.PHASE_DURATION_METRIC).timers())
                .extracting(timer -> timer.getId().getTag("phase"))
                .containsExactlyInAnyOrder("full-text", "vector", "canonical-hydration");
    }

    @Test
    void excludesEveryEvidenceRangeThatEscapesThePublishedPageScope() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit disallowedHighRank = hit(versionId, "ACTIONS", 1);
        RuleEvidenceHit allowed = hit(versionId, "ACTIONS", 2);
        RuleEvidenceHit crossingBoundary = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "ACTIONS", "Evidence", 2, 3, 0.5);
        List<RuleEvidenceHit> indexed = List.of(disallowedHighRank, allowed, crossingBoundary);
        var service = new HybridRuleSearchService(
                (version, query, limit) -> indexed,
                (version, query, limit) -> indexed,
                (version, chunkIds) -> indexed.stream()
                        .filter(candidate -> chunkIds.contains(candidate.chunkId()))
                        .map(candidate -> complete(candidate, "Complete evidence"))
                        .toList(),
                new SimpleMeterRegistry());

        var results = service.search(
                versionId,
                "legal action",
                new RetrievalOptions(5, Set.of(), null, Set.of(2)));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.evidence().chunkId()).isEqualTo(allowed.chunkId());
            assertThat(result.evidence().pageFrom()).isEqualTo(2);
            assertThat(result.evidence().pageTo()).isEqualTo(2);
        });
    }

    @Test
    void scansAnUnavailableChannelOnceAndBasesContinuationOnTheHealthyLookahead() {
        UUID versionId = UUID.randomUUID();
        List<RuleEvidenceHit> lexical = List.of(
                hit(versionId, "ACTIONS", 1),
                hit(versionId, "ACTIONS", 2),
                hit(versionId, "ACTIONS", 3));
        java.util.concurrent.atomic.AtomicInteger vectorCalls = new java.util.concurrent.atomic.AtomicInteger();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        var service = new HybridRuleSearchService(
                fullTextPages(lexical, new ArrayList<>()),
                new VectorRuleSearch() {
                    @Override
                    public List<RuleEvidenceHit> search(UUID documentVersionId, String query, int limit) {
                        vectorCalls.incrementAndGet();
                        throw new IllegalStateException("embedding provider unavailable");
                    }
                },
                (version, ids) -> lexical.stream()
                        .filter(candidate -> ids.contains(candidate.chunkId()))
                        .map(candidate -> complete(candidate, "Complete page " + candidate.pageFrom()))
                        .toList(),
                metrics);

        var first = service.searchPage(
                versionId, "legal actions", new RetrievalOptions(2, Set.of(), null));
        assertThat(vectorCalls).hasValue(1);
        Set<UUID> published = first.hits().stream()
                .map(result -> result.evidence().chunkId())
                .collect(java.util.stream.Collectors.toSet());
        var second = service.searchPage(
                versionId,
                "legal actions",
                new RetrievalOptions(2, Set.of(), null, null, 0, published));

        assertThat(first.hits()).hasSize(2).allSatisfy(result -> {
            assertThat(result.evidence().excerpt()).startsWith("Complete page ");
            assertThat(result.fullTextRank()).isNotNull();
            assertThat(result.vectorRank()).isNull();
        });
        assertThat(first.hits())
                .extracting(result -> result.evidence().pageFrom())
                .containsExactly(1, 2);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.hits()).singleElement().satisfies(result -> {
            assertThat(result.evidence().excerpt()).isEqualTo("Complete page 3");
            assertThat(result.fullTextRank()).isEqualTo(3);
            assertThat(result.vectorRank()).isNull();
        });
        assertThat(second.hasMore()).isFalse();
        assertThat(vectorCalls).hasValue(2);
        assertThat(metrics.get(HybridRuleSearchService.CHANNEL_OUTCOME_METRIC)
                        .tag("channel", "vector")
                        .tag("outcome", "unavailable")
                        .counter()
                        .count())
                .isEqualTo(2.0);
        assertThat(metrics.get(HybridRuleSearchService.AVAILABILITY_METRIC)
                        .tag("outcome", "partial")
                        .counter()
                        .count())
                .isEqualTo(2.0);
    }

    @Test
    void preservesVectorEvidenceWhenTheFullTextChannelIsUnavailable() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit semantic = hit(versionId, "SCORING", 7);
        RuntimeException fullTextFailure = new IllegalStateException("full-text query unavailable");
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        var service = new HybridRuleSearchService(
                (version, query, limit) -> {
                    throw fullTextFailure;
                },
                (version, query, limit) -> List.of(semantic),
                (version, chunkIds) -> List.of(complete(semantic, "Complete semantic evidence")),
                metrics);

        var results = service.search(
                versionId,
                "end game scoring",
                new RetrievalOptions(5, Set.of(), null));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.evidence().excerpt()).isEqualTo("Complete semantic evidence");
            assertThat(result.fullTextRank()).isNull();
            assertThat(result.vectorRank()).isEqualTo(1);
        });
        assertThat(metrics.get(HybridRuleSearchService.CHANNEL_OUTCOME_METRIC)
                        .tag("channel", "full-text")
                        .tag("outcome", "unavailable")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(metrics.get(HybridRuleSearchService.AVAILABILITY_METRIC)
                        .tag("outcome", "partial")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void failsOnlyWhenBothRetrievalChannelsAreUnavailable() {
        UUID versionId = UUID.randomUUID();
        RuntimeException fullTextFailure = new IllegalStateException("full-text unavailable");
        RuntimeException vectorFailure = new IllegalStateException("vector unavailable");
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        var service = new HybridRuleSearchService(
                (version, query, limit) -> {
                    throw fullTextFailure;
                },
                (version, query, limit) -> {
                    throw vectorFailure;
                },
                (version, chunkIds) -> {
                    throw new AssertionError("canonical hydration must not run without a retrieval channel");
                },
                metrics);

        assertThatThrownBy(() -> service.search(
                        versionId,
                        "setup",
                        new RetrievalOptions(5, Set.of(), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("all hybrid retrieval channels are unavailable")
                .hasCause(fullTextFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(vectorFailure));
        assertThat(metrics.get(HybridRuleSearchService.AVAILABILITY_METRIC)
                        .tag("outcome", "failed")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void rejectsRankedHitsThatCannotBeCanonicallyHydrated() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit indexed = hit(versionId, "SETUP", 1);
        var service = new HybridRuleSearchService(
                (version, query, limit) -> List.of(indexed),
                (version, query, limit) -> List.of(),
                (version, chunkIds) -> List.of(),
                new SimpleMeterRegistry());

        assertThatThrownBy(() -> service.search(
                        versionId, "setup", new RetrievalOptions(5, Set.of(), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ranked evidence could not be canonically hydrated");
    }

    private RuleEvidenceHit complete(RuleEvidenceHit hit, String evidence) {
        return new RuleEvidenceHit(
                hit.chunkId(), hit.documentVersionId(), hit.sectionType(), hit.heading(), evidence,
                hit.pageFrom(), hit.pageTo(), 1.0);
    }

    private RuleEvidenceHit hit(UUID versionId, String sectionType, int page) {
        return hit(UUID.randomUUID(), versionId, sectionType, page);
    }

    private RuleEvidenceHit hit(UUID chunkId, UUID versionId, String sectionType, int page) {
        return new RuleEvidenceHit(
                chunkId, versionId, sectionType, sectionType, "Evidence", page, page, 0.5);
    }

    private FullTextRuleSearch fullTextPages(List<RuleEvidenceHit> hits, List<Integer> offsets) {
        return new FullTextRuleSearch() {
            @Override
            public List<RuleEvidenceHit> search(UUID documentVersionId, String query, int limit) {
                return search(documentVersionId, query, 0, limit);
            }

            @Override
            public List<RuleEvidenceHit> search(UUID documentVersionId, String query, int offset, int limit) {
                offsets.add(offset);
                return window(hits, offset, limit);
            }
        };
    }

    private VectorRuleSearch vectorPages(List<RuleEvidenceHit> hits, List<Integer> offsets) {
        return new VectorRuleSearch() {
            @Override
            public List<RuleEvidenceHit> search(UUID documentVersionId, String query, int limit) {
                return search(documentVersionId, query, 0, limit);
            }

            @Override
            public List<RuleEvidenceHit> search(UUID documentVersionId, String query, int offset, int limit) {
                offsets.add(offset);
                return window(hits, offset, limit);
            }
        };
    }

    private List<RuleEvidenceHit> window(List<RuleEvidenceHit> hits, int offset, int limit) {
        if (offset >= hits.size()) return List.of();
        return hits.subList(offset, Math.min(hits.size(), offset + limit));
    }
}
