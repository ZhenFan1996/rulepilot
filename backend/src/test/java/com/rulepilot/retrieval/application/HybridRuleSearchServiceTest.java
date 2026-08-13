package com.rulepilot.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.retrieval.HybridRuleSearch.RetrievalOptions;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HybridRuleSearchServiceTest {

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
    void preservesLexicalEvidenceDuringHighRecallRetrievalWhenVectorResultsCrowdItOut() {
        UUID versionId = UUID.randomUUID();
        List<RuleEvidenceHit> lexical = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(page -> hit(versionId, "ACTIONS", page))
                .toList();
        List<RuleEvidenceHit> vectorOnly = java.util.stream.IntStream.rangeClosed(11, 20)
                .mapToObj(page -> hit(versionId, "ACTIONS", page))
                .toList();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        var service = new HybridRuleSearchService(
                (version, query, limit) -> lexical,
                (version, query, limit) -> vectorOnly,
                (version, chunkIds) -> java.util.stream.Stream.concat(lexical.stream(), vectorOnly.stream())
                        .filter(hit -> chunkIds.contains(hit.chunkId()))
                        .map(hit -> complete(hit, "Complete evidence " + hit.pageFrom()))
                        .toList(),
                metrics);

        var results = service.search(versionId, "ending scoring", new RetrievalOptions(20, Set.of(), null));

        assertThat(results).extracting(result -> result.evidence().pageFrom())
                .contains(1, 7, 10)
                .hasSize(20);
        assertThat(metrics.find(HybridRuleSearchService.PHASE_DURATION_METRIC).timers())
                .extracting(timer -> timer.getId().getTag("phase"))
                .containsExactlyInAnyOrder("full-text", "vector", "canonical-hydration");
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
        return new RuleEvidenceHit(
                UUID.randomUUID(), versionId, sectionType, sectionType, "Evidence", page, page, 0.5);
    }
}
