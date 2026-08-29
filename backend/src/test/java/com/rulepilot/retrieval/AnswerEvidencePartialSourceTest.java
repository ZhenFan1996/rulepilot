package com.rulepilot.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.retrieval.HybridRuleSearch.RetrievalOptions;
import com.rulepilot.retrieval.HybridRuleSearch.SearchPage;
import com.rulepilot.retrieval.HybridRuleSearch.SourceAvailability;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class AnswerEvidencePartialSourceTest {

    @Test
    void preservesVerifiedHitsAndReturnsAPartialState() {
        UUID versionId = UUID.randomUUID();
        HybridEvidenceHit evidence = evidence(versionId);
        AnswerEvidenceRetriever retriever = retriever(new HybridRuleSearch() {
            @Override
            public List<HybridEvidenceHit> search(
                    UUID documentVersionId, String query, RetrievalOptions options) {
                throw new AssertionError("answer retrieval must inspect typed search-page availability");
            }

            @Override
            public SearchPage searchPage(UUID documentVersionId, String query, RetrievalOptions options) {
                return new SearchPage(List.of(evidence), false, SourceAvailability.PARTIAL);
            }
        });

        AnswerEvidenceRetriever.Result result = retriever.retrieve(
                UUID.randomUUID(), question(), new AnswerRetrievalContext(versionId), "alice");

        assertThat(result.state()).isEqualTo(AnswerEvidenceRetriever.State.PARTIAL);
        assertThat(result.evidence()).containsExactly(evidence);
    }

    @Test
    void doesNotCertifyNoEvidenceWhenTheAvailableChannelReturnsNoHits() {
        UUID versionId = UUID.randomUUID();
        AnswerEvidenceRetriever retriever = retriever(new HybridRuleSearch() {
            @Override
            public List<HybridEvidenceHit> search(
                    UUID documentVersionId, String query, RetrievalOptions options) {
                throw new AssertionError("answer retrieval must inspect typed search-page availability");
            }

            @Override
            public SearchPage searchPage(UUID documentVersionId, String query, RetrievalOptions options) {
                return new SearchPage(List.of(), false, SourceAvailability.PARTIAL);
            }
        });

        AnswerEvidenceRetriever.Result result = retriever.retrieve(
                UUID.randomUUID(), question(), new AnswerRetrievalContext(versionId), "alice");

        assertThat(result.state()).isEqualTo(AnswerEvidenceRetriever.State.PARTIAL);
        assertThat(result.evidence()).isEmpty();
    }

    private AnswerEvidenceRetriever retriever(HybridRuleSearch search) {
        return new AnswerEvidenceRetriever(
                search,
                VisualRulebookPageFactSearch.empty(),
                (documentVersionId, chunkIds) -> List.of(),
                new AnswerRetrievalInvocations() {
                    @Override
                    public <T> T invoke(
                            UUID runId,
                            String operation,
                            int estimatedInputTokens,
                            String successSummary,
                            Supplier<T> invocation,
                            ToIntFunction<T> outputTokenEstimator) {
                        return invocation.get();
                    }

                    @Override
                    public boolean executionStopped(RuntimeException failure) {
                        return false;
                    }
                });
    }

    private AnswerRetrievalQuestion question() {
        return new AnswerRetrievalQuestion(
                "How does this action work?",
                AnswerRetrievalQuestion.QuestionType.RULE_QUERY,
                List.of("action"));
    }

    private HybridEvidenceHit evidence(UUID versionId) {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "Action", "Canonical action rule.", 2, 2, 0.9);
        return new HybridEvidenceHit(source, 0.9, 1, null, false);
    }
}
