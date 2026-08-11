package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class AnswerPostPublicationReviewerTest {

    @Test
    void retriesOneEvidenceBackedCorrectionWhenTheFirstRevisionIncorrectlyDeclaresInsufficiency() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(),
                versionId,
                "END_GAME",
                "Ending the game",
                "If two rows are complete and contain no disabled locations, you may end the game. Finish the round.",
                13,
                13,
                0.9);
        HybridEvidenceHit evidence = new HybridEvidenceHit(source, 0.1, 1, null, false);
        StructuredRuleAnswer answer = answer(versionId, source);
        AtomicInteger revisions = new AtomicInteger();
        AtomicInteger reviews = new AtomicInteger();
        AnswerPostPublicationReviewer reviewer = new AnswerPostPublicationReviewer(
                (reviewRequest, risk) -> reviews.getAndIncrement() == 0
                        ? new GeneratedContentCritic.Review(
                                true,
                                List.of(new GeneratedContentCritic.Issue(
                                        GeneratedContentCritic.IssueType.OVERREACH,
                                        1,
                                        List.of(source.chunkId()),
                                        "The answer must keep the no-disabled-location condition.")))
                        : new GeneratedContentCritic.Review(true, List.of()),
                new AnswerModelGateway(
                        new RuleAnswerModel() {
                            @Override
                            public ModelDraft compose(ModelRequest request) {
                                throw new AssertionError("reviewer must use the bounded revision path");
                            }

                            @Override
                            public ModelDraft revise(
                                    ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                                if (revisions.getAndIncrement() == 0) {
                                    return new ModelDraft(false, "incorrectly declined", null, null, List.of(), List.of(), null);
                                }
                                return new ModelDraft(
                                        "You may end only with two complete rows and no disabled locations.",
                                        "After the trigger, finish the current round so every player has the same number of turns.",
                                        List.of(source.chunkId()),
                                        List.of(),
                                        "HIGH");
                            }
                        },
                        unlimitedRateLimiter(),
                        immediateInvocations()),
                new AnswerPublicationValidator(verifiedEvidence()));

        AnswerPostPublicationReviewer.Result result = reviewer.review(
                UUID.randomUUID(),
                new UnderstoodQuestion(
                        versionId,
                        "If a completed row has a disabled location, may I end the game?",
                        "If a completed row has a disabled location, may I end the game?",
                        QuestionType.SITUATION_QUERY,
                        List.of("disabled location"),
                        Set.of()),
                new QuestionContext(versionId),
                "player",
                null,
                request(source),
                new ModelDraft(
                        answer.shortVerdict(),
                        answer.explanation(),
                        List.of(source.chunkId()),
                        List.of(),
                        "HIGH"),
                answer,
                List.of(evidence));

        assertThat(result.accepted()).isTrue();
        assertThat(result.answer().shortVerdict()).contains("no disabled locations");
        assertThat(revisions).hasValue(2);
        assertThat(reviews).hasValue(2);
    }

    @Test
    void attemptsOneBoundedCorrectionBeforeRejectingAMaterialCriticFailure() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "Action timing", "Take the main action once.", 4, 4, 0.9);
        HybridEvidenceHit evidence = new HybridEvidenceHit(source, 0.1, 1, null, false);
        AtomicInteger modelCalls = new AtomicInteger();
        AnswerPostPublicationReviewer reviewer = new AnswerPostPublicationReviewer(
                (request, risk) -> new GeneratedContentCritic.Review(
                        true,
                        List.of(new GeneratedContentCritic.Issue(
                                GeneratedContentCritic.IssueType.OVERREACH,
                                1,
                                List.of(source.chunkId()),
                                "The conclusion exceeds the evidence."))),
                new AnswerModelGateway(
                        request -> {
                            modelCalls.incrementAndGet();
                            throw new IllegalStateException("correction unavailable");
                        },
                        unlimitedRateLimiter(),
                        immediateInvocations()),
                new AnswerPublicationValidator(verifiedEvidence()));
        StructuredRuleAnswer answer = answer(versionId, source);
        ModelRequest request = request(source);

        AnswerPostPublicationReviewer.Result result = reviewer.review(
                UUID.randomUUID(),
                understood(versionId),
                new QuestionContext(versionId),
                "player",
                null,
                request,
                new ModelDraft(
                        answer.shortVerdict(),
                        answer.explanation(),
                        List.of(source.chunkId()),
                        List.of(),
                        "HIGH"),
                answer,
                List.of(evidence));

        assertThat(result.accepted()).isFalse();
        assertThat(result.failureStatus()).isEqualTo(AnswerStatus.INVALID_MODEL_OUTPUT);
        assertThat(result.failureMessage()).contains("一致性审查");
        assertThat(modelCalls).hasValue(1);
    }

    @Test
    void returnsAQualifiedAnswerForANonMaterialReviewConcern() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "Action timing", "Take the main action once.", 4, 4, 0.9);
        HybridEvidenceHit evidence = new HybridEvidenceHit(source, 0.1, 1, null, false);
        StructuredRuleAnswer answer = answer(versionId, source);
        AtomicInteger modelCalls = new AtomicInteger();
        AnswerPostPublicationReviewer reviewer = new AnswerPostPublicationReviewer(
                (request, risk) -> new GeneratedContentCritic.Review(
                        true,
                        List.of(new GeneratedContentCritic.Issue(
                                GeneratedContentCritic.IssueType.MISSING_EXCEPTION,
                                1,
                                List.of(source.chunkId()),
                                "A minor exception may be worth mentioning."))),
                new AnswerModelGateway(
                        request -> {
                            modelCalls.incrementAndGet();
                            throw new IllegalStateException("correction unavailable");
                        },
                        unlimitedRateLimiter(),
                        immediateInvocations()),
                new AnswerPublicationValidator(verifiedEvidence()));

        AnswerPostPublicationReviewer.Result result = reviewer.review(
                UUID.randomUUID(),
                understood(versionId),
                new QuestionContext(versionId),
                "player",
                null,
                request(source),
                new ModelDraft(
                        answer.shortVerdict(),
                        answer.explanation(),
                        List.of(source.chunkId()),
                        List.of(),
                        "HIGH"),
                answer,
                List.of(evidence));

        assertThat(result.accepted()).isTrue();
        assertThat(result.answer().status()).isEqualTo(AnswerStatus.ANSWERED_WITH_WARNING);
        assertThat(result.answer().warnings())
                .extracting(warning -> warning.type())
                .containsExactly(com.rulepilot.assistant.domain.AnswerWarning.Type.REVIEW_UNRESOLVED);
        assertThat(modelCalls).hasValue(1);
    }

    @Test
    void returnsAQualifiedAnswerWhenTheCriticIsUnavailable() {
        UUID versionId = UUID.randomUUID();
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "Action timing", "Take the main action once.", 4, 4, 0.9);
        HybridEvidenceHit evidence = new HybridEvidenceHit(source, 0.1, 1, null, false);
        StructuredRuleAnswer answer = answer(versionId, source);
        AnswerPostPublicationReviewer reviewer = new AnswerPostPublicationReviewer(
                (request, risk) -> {
                    throw new IllegalStateException("critic unavailable");
                },
                new AnswerModelGateway(request -> {
                    throw new AssertionError("critic failure must not start a correction");
                }, unlimitedRateLimiter(), immediateInvocations()),
                new AnswerPublicationValidator(verifiedEvidence()));

        AnswerPostPublicationReviewer.Result result = reviewer.review(
                UUID.randomUUID(),
                understood(versionId),
                new QuestionContext(versionId),
                "player",
                null,
                request(source),
                new ModelDraft(
                        answer.shortVerdict(),
                        answer.explanation(),
                        List.of(source.chunkId()),
                        List.of(),
                        "HIGH"),
                answer,
                List.of(evidence));

        assertThat(result.accepted()).isTrue();
        assertThat(result.answer().warnings())
                .extracting(warning -> warning.type())
                .containsExactly(com.rulepilot.assistant.domain.AnswerWarning.Type.REVIEW_UNAVAILABLE);
    }

    private static StructuredRuleAnswer answer(UUID versionId, RuleEvidenceHit source) {
        return new StructuredRuleAnswer(
                versionId,
                AnswerStatus.ANSWERED,
                "You may take the main action once.",
                "The cited action rule states one main action.",
                List.of(new RuleCitation(
                        source.chunkId(),
                        source.documentVersionId(),
                        source.sectionType(),
                        source.heading(),
                        source.excerpt(),
                        source.pageFrom(),
                        source.pageTo())),
                List.of(),
                AnswerConfidence.HIGH,
                AnswerBasis.DIRECT_RULE,
                false,
                null,
                null,
                null);
    }

    private static UnderstoodQuestion understood(UUID versionId) {
        return new UnderstoodQuestion(
                versionId,
                "How often can I take the main action?",
                "How often can I take the main action?",
                QuestionType.RULE_QUERY,
                List.of("main action"),
                Set.of());
    }

    private static ModelRequest request(RuleEvidenceHit source) {
        return new ModelRequest(
                "How often can I take the main action?",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.ZH_CN),
                List.of(new EvidenceInput(
                        source.chunkId(),
                        source.sectionType(),
                        source.heading(),
                        source.excerpt(),
                        source.pageFrom(),
                        source.pageTo())));
    }

    private static EvidenceVerifier verifiedEvidence() {
        return request -> new EvidenceVerifier.Verification(
                EvidenceVerifier.VerificationStatus.VERIFIED,
                List.of());
    }

    private static RuleAnswerRateLimiter unlimitedRateLimiter() {
        return new RuleAnswerRateLimiter() {
            @Override
            public void checkUser(String username) {}

            @Override
            public Permit acquireModel(String username, UUID gameSessionId, String providerId) {
                return () -> {};
            }
        };
    }

    private static AuditedAgentInvocations immediateInvocations() {
        return new AuditedAgentInvocations() {
            @Override
            public <T> T invoke(
                    UUID runId,
                    com.rulepilot.assistant.AgentExecutionControl.ActivityType type,
                    String operation,
                    int estimatedInputTokens,
                    String successSummary,
                    Supplier<T> invocation,
                    ToIntFunction<T> outputTokenEstimator) {
                return invocation.get();
            }
        };
    }
}
