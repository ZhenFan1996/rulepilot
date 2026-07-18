package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class StructuredRuleAnswerServiceTest {

    private final UUID versionId = UUID.randomUUID();
    private final DeterministicQuestionUnderstanding understanding = new DeterministicQuestionUnderstanding();

    @Test
    void returnsOnlyValidatedCitationsFromCurrentVersion() {
        RuleEvidenceHit source = evidence("SCORING");
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, 1, false)),
                request -> new ModelDraft(
                        "Coins score one point.", "Each coin contributes one point.",
                        List.of(source.chunkId()), List.of("Only count remaining coins."), "HIGH"));

        var answer = service.answer(
                "How are coins scored?", new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.chunkId()).isEqualTo(source.chunkId());
            assertThat(citation.documentVersionId()).isEqualTo(versionId);
            assertThat(citation.pageFrom()).isEqualTo(8);
        });
        assertThat(answer.official()).isFalse();
    }

    @Test
    void rejectsCitationThatWasNotRetrieved() {
        RuleEvidenceHit source = evidence("SCORING");
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> new ModelDraft("Unsupported", "Unsupported", List.of(UUID.randomUUID()), List.of(), "HIGH"));

        var answer = service.answer(
                "How is scoring resolved?", new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INVALID_MODEL_OUTPUT);
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void missingContextStopsBeforeRetrievalAndModel() {
        AtomicBoolean called = new AtomicBoolean();
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> {
                    called.set(true);
                    return List.of();
                },
                request -> {
                    called.set(true);
                    return null;
                });

        var answer = service.answer(
                "Can I play this card from my hand?",
                new QuestionContext(versionId, null, null, 3, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.CLARIFICATION_REQUIRED);
        assertThat(answer.clarification()).contains("GAME_PHASE", "SITUATION_DETAILS");
        assertThat(called).isFalse();
    }

    @Test
    void refusesWhenNoEvidenceWasRetrieved() {
        AtomicBoolean modelCalled = new AtomicBoolean();
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> List.of(),
                request -> {
                    modelCalled.set(true);
                    return null;
                });

        var answer = service.answer(
                "What does this unknown symbol do?",
                new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(answer.citations()).isEmpty();
        assertThat(modelCalled).isFalse();
    }

    @Test
    void reportsModelTimeoutWithoutLeakingAnswerContent() {
        RuleEvidenceHit source = evidence("ACTIONS");
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> {
                    throw new RuleAnswerModelTimeoutException("provider details", new RuntimeException("secret"));
                });

        var answer = service.answer(
                "Which actions are available during a turn?",
                new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.MODEL_TIMEOUT);
        assertThat(answer.shortVerdict()).doesNotContain("provider details", "secret");
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void rejectsVersionConflictBeforeCallingModel() {
        UUID otherVersion = UUID.randomUUID();
        RuleEvidenceHit wrongVersion = new RuleEvidenceHit(
                UUID.randomUUID(), otherVersion, "SCORING", "Scoring", "One point.", 2, 2, 0.7);
        AtomicBoolean modelCalled = new AtomicBoolean();
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> List.of(new HybridEvidenceHit(wrongVersion, 0.03, 1, null, false)),
                request -> {
                    modelCalled.set(true);
                    return null;
                });

        var answer = service.answer(
                "How does scoring work?",
                new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.VERSION_CONFLICT);
        assertThat(answer.citations()).isEmpty();
        assertThat(modelCalled).isFalse();
    }

    private RuleEvidenceHit evidence(String sectionType) {
        return new RuleEvidenceHit(
                UUID.randomUUID(), versionId, sectionType, "Scoring", "Each coin is worth one point.", 8, 8, 0.8);
    }
}
