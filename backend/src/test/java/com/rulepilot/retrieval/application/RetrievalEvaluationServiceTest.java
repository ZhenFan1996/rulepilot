package com.rulepilot.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.document.DocumentVersionScopeLookup.VersionScope;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.HybridRuleSearch.RetrievalOptions;
import com.rulepilot.retrieval.HybridRuleSearch.SearchPage;
import com.rulepilot.retrieval.HybridRuleSearch.SourceAvailability;
import com.rulepilot.retrieval.RetrievalEvaluationSet;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import com.rulepilot.retrieval.domain.RetrievalEvaluationSample;
import com.rulepilot.retrieval.domain.RetrievalEvaluationSample.RelevantEvidence;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetrievalEvaluationServiceTest {

    private static final String SOURCE_SHA = "a".repeat(64);

    @Test
    void reportsRecallReciprocalRankAndFailures() {
        UUID versionId = UUID.randomUUID();
        var samples = List.of(
                new RetrievalEvaluationSample(
                        "scoring", "How are points counted?",
                        List.of(new RelevantEvidence(2, List.of("three points")))),
                new RetrievalEvaluationSample(
                        "setup", "How is the game prepared?",
                        List.of(new RelevantEvidence(3, List.of("place the board")))));
        RetrievalEvaluationSet evaluationSet = new RetrievalEvaluationSet() {
            public String name() {
                return "test-set";
            }

            public String sourceSha256() {
                return SOURCE_SHA;
            }

            public List<RetrievalEvaluationSample> samples() {
                return samples;
            }
        };
        List<RuleEvidenceHit> indexed = List.of(
                hit(versionId, "OBJECTIVE", "Win the game", 1),
                hit(versionId, "SCORING", "Score three points", 2));
        var search = new HybridRuleSearchService(
                (version, query, limit) -> indexed,
                (version, query, limit) -> List.of(),
                (version, chunkIds) -> indexed.stream()
                        .filter(candidate -> chunkIds.contains(candidate.chunkId()))
                        .toList(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        var report = new RetrievalEvaluationService(
                        search,
                        evaluationSet,
                        requested -> java.util.Optional.of(
                                new VersionScope(requested, null, "READY", "owner", "Rules", SOURCE_SHA)))
                .evaluate(versionId);

        assertThat(report.sourceSha256()).isEqualTo(SOURCE_SHA);
        assertThat(report.sampleCount()).isEqualTo(2);
        assertThat(report.hitCount()).isEqualTo(1);
        assertThat(report.recallAt5()).isEqualTo(0.5);
        assertThat(report.meanReciprocalRank()).isEqualTo(0.25);
        assertThat(report.maximumLatencyMillis()).isGreaterThanOrEqualTo(report.p95LatencyMillis());
        assertThat(report.sampleResults()).hasSize(2);
        assertThat(report.sampleResults().getFirst().relevantRank()).isEqualTo(2);
        assertThat(report.sampleResults()).extracting(result -> result.sourceAvailability())
                .containsOnly(SourceAvailability.COMPLETE);
        assertThat(report.errors()).singleElement().satisfies(error -> {
            assertThat(error.sampleId()).isEqualTo("setup");
            assertThat(error.relevantEvidence()).containsExactly(new RelevantEvidence(3, List.of("place the board")));
            assertThat(error.retrieved()).extracting(candidate -> candidate.sectionType())
                    .containsExactly("OBJECTIVE", "SCORING");
        });
    }

    @Test
    void reportsPartialSourceAvailabilityEvenWhenFullTextFallbackFindsTheAnswer() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit evidence = hit(versionId, "SCORING", "Score three points", 2);
        RetrievalEvaluationSet evaluationSet = evaluationSet(List.of(new RetrievalEvaluationSample(
                "scoring", "How are points counted?",
                List.of(new RelevantEvidence(2, List.of("three points"))))));
        HybridRuleSearch partiallyAvailableSearch = new HybridRuleSearch() {
            @Override
            public List<HybridEvidenceHit> search(UUID version, String query, RetrievalOptions options) {
                return List.of(new HybridEvidenceHit(evidence, 1.0, 1, null, false));
            }

            @Override
            public SearchPage searchPage(UUID version, String query, RetrievalOptions options) {
                return new SearchPage(search(version, query, options), false, SourceAvailability.PARTIAL);
            }
        };

        var report = new RetrievalEvaluationService(
                        partiallyAvailableSearch,
                        evaluationSet,
                        requested -> java.util.Optional.of(
                                new VersionScope(requested, null, "READY", "owner", "Rules", SOURCE_SHA)))
                .evaluate(versionId);

        assertThat(report.hitCount()).isEqualTo(1);
        assertThat(report.sampleResults()).singleElement().satisfies(result -> {
            assertThat(result.relevantRank()).isEqualTo(1);
            assertThat(result.sourceAvailability()).isEqualTo(SourceAvailability.PARTIAL);
        });
    }

    @Test
    void rejectsAnEvaluationFixtureThatDoesNotOwnTheUploadedSource() {
        UUID versionId = UUID.randomUUID();
        RetrievalEvaluationSet evaluationSet = new RetrievalEvaluationSet() {
            public String name() {
                return "test-set";
            }

            public String sourceSha256() {
                return SOURCE_SHA;
            }

            public List<RetrievalEvaluationSample> samples() {
                return List.of(new RetrievalEvaluationSample(
                        "setup", "How is the game prepared?",
                        List.of(new RelevantEvidence(1, List.of("place the board")))));
            }
        };
        var service = new RetrievalEvaluationService(
                (version, query, options) -> List.of(),
                evaluationSet,
                requested -> java.util.Optional.of(
                        new VersionScope(requested, null, "READY", "owner", "Other Rules", "b".repeat(64))));

        assertThatThrownBy(() -> service.evaluate(versionId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("retrieval evaluation fixture does not match the document source");
    }

    private RuleEvidenceHit hit(UUID versionId, String sectionType, String content, int page) {
        return new RuleEvidenceHit(
                UUID.randomUUID(), versionId, sectionType, sectionType, content, page, page, 0.5);
    }

    private RetrievalEvaluationSet evaluationSet(List<RetrievalEvaluationSample> samples) {
        return new RetrievalEvaluationSet() {
            public String name() {
                return "test-set";
            }

            public String sourceSha256() {
                return SOURCE_SHA;
            }

            public List<RetrievalEvaluationSample> samples() {
                return samples;
            }
        };
    }
}
