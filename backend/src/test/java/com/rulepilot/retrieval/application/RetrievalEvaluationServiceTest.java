package com.rulepilot.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.retrieval.RetrievalEvaluationSet;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import com.rulepilot.retrieval.domain.RetrievalEvaluationSample;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetrievalEvaluationServiceTest {

    @Test
    void reportsRecallReciprocalRankAndFailures() {
        UUID versionId = UUID.randomUUID();
        var samples = List.of(
                new RetrievalEvaluationSample("scoring", "How are points counted?", Set.of("SCORING")),
                new RetrievalEvaluationSample("setup", "How is the game prepared?", Set.of("SETUP")));
        RetrievalEvaluationSet evaluationSet = new RetrievalEvaluationSet() {
            public String name() {
                return "test-set";
            }

            public List<RetrievalEvaluationSample> samples() {
                return samples;
            }
        };
        var search = new HybridRuleSearchService(
                (version, query, limit) -> List.of(hit(version, "OBJECTIVE"), hit(version, "SCORING")),
                (version, query, limit) -> List.of());

        var report = new RetrievalEvaluationService(search, evaluationSet).evaluate(versionId);

        assertThat(report.sampleCount()).isEqualTo(2);
        assertThat(report.hitCount()).isEqualTo(1);
        assertThat(report.recallAt5()).isEqualTo(0.5);
        assertThat(report.meanReciprocalRank()).isEqualTo(0.25);
        assertThat(report.errors()).singleElement().satisfies(error -> {
            assertThat(error.sampleId()).isEqualTo("setup");
            assertThat(error.expectedSectionTypes()).containsExactly("SETUP");
            assertThat(error.retrieved()).extracting(candidate -> candidate.sectionType())
                    .containsExactly("OBJECTIVE", "SCORING");
        });
    }

    private RuleEvidenceHit hit(UUID versionId, String sectionType) {
        return new RuleEvidenceHit(
                UUID.randomUUID(), versionId, sectionType, sectionType, "Self-owned evidence", 1, 1, 0.5);
    }
}
