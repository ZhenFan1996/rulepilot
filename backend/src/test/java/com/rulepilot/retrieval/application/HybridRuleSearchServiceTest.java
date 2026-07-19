package com.rulepilot.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.retrieval.HybridRuleSearch.RetrievalOptions;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
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
                        complete(scoring, "Complete scoring evidence")));

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
