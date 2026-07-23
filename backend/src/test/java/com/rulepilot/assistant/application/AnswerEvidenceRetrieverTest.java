package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AnswerEvidenceRetrieverTest {

    private final UUID versionId = UUID.randomUUID();
    private final UUID chunkId = UUID.randomUUID();

    @Test
    void selectsTextEvidenceAndEnrichesItWithTheMatchingVisualPageFact() {
        HybridEvidenceHit source = evidence("执行该行动后获得一分。", 0.7);
        VisualRulebookPageFactSearch facts = (documentVersionId, query, limit) -> List.of(
                new VisualRulebookPageFactSearch.PageFactMatch(
                        4, "Score marker", "The marker advances one space after this action.", List.of("marker"), 0.9));
        RuleEvidenceLookup lookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                assertThat(pageNumbers).contains(4);
                return List.of(source.evidence());
            }
        };
        AnswerEvidenceRetriever retriever = retriever((documentVersionId, query, options) -> List.of(source), facts, lookup);

        AnswerEvidenceRetriever.Result result = retriever.retrieve(
                UUID.randomUUID(), question("How do I score after this action?"), context(), "alice");

        assertThat(result.state()).isEqualTo(AnswerEvidenceRetriever.State.READY);
        assertThat(result.evidence()).singleElement().satisfies(hit ->
                assertThat(hit.evidence().excerpt()).contains("执行该行动后获得一分", "Visible facts", "marker"));
    }

    @Test
    void reportsUnavailableWhenEveryCoreRetrievalCallFails() {
        AnswerEvidenceRetriever retriever = retriever(
                (documentVersionId, query, options) -> {
                    throw new IllegalStateException("search unavailable");
                },
                VisualRulebookPageFactSearch.empty(),
                (documentVersionId, chunkIds) -> List.of());

        AnswerEvidenceRetriever.Result result = retriever.retrieve(
                UUID.randomUUID(), question("How do I score after this action?"), context(), "alice");

        assertThat(result.state()).isEqualTo(AnswerEvidenceRetriever.State.UNAVAILABLE);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void rejectsConflictingSnapshotsForTheSameEvidenceIdentity() {
        AtomicInteger calls = new AtomicInteger();
        AnswerEvidenceRetriever retriever = retriever(
                (documentVersionId, query, options) -> List.of(evidence(
                        calls.getAndIncrement() == 0 ? "执行该行动后获得一分。" : "执行该行动后失去一分。",
                        0.7)),
                VisualRulebookPageFactSearch.empty(),
                (documentVersionId, chunkIds) -> List.of());

        AnswerEvidenceRetriever.Result result = retriever.retrieve(
                UUID.randomUUID(), question("How do I score after this action?"), context(), "alice");

        assertThat(result.state()).isEqualTo(AnswerEvidenceRetriever.State.CONFLICTING);
        assertThat(result.evidence()).isEmpty();
    }

    private AnswerEvidenceRetriever retriever(
            HybridRuleSearch retrieval,
            VisualRulebookPageFactSearch visualFacts,
            RuleEvidenceLookup evidenceLookup) {
        ImmediateAuditedAgentInvocations invocations = new ImmediateAuditedAgentInvocations();
        return new AnswerEvidenceRetriever(
                retrieval,
                visualFacts,
                evidenceLookup,
                invocations,
                new AnswerModelGateway(new RuleAnswerModel() {
                    @Override
                    public ModelDraft compose(ModelRequest request) {
                        return null;
                    }
                }, new RuleAnswerRateLimiter() {
                    @Override
                    public void checkUser(String username) {}

                    @Override
                    public Permit acquireModel(String username, UUID gameSessionId, String providerId) {
                        return () -> {};
                    }
                }, invocations));
    }

    private UnderstoodQuestion question(String text) {
        return new UnderstoodQuestion(
                versionId, text, text.toLowerCase(), QuestionType.RULE_QUERY, List.of("score"), Set.of(), "ACTIONS");
    }

    private QuestionContext context() {
        return new QuestionContext(versionId, "ACTIONS", null, 4, Set.of());
    }

    private HybridEvidenceHit evidence(String excerpt, double score) {
        return new HybridEvidenceHit(
                new RuleEvidenceHit(chunkId, versionId, "ACTIONS", "Scoring", excerpt, 4, 4, score),
                score,
                1,
                null,
                false);
    }
}
