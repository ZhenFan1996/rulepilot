package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModelInvalidOutputException;
import com.rulepilot.assistant.RuleAnswerModelInvalidOutputException.RejectedOutput;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.assistant.RuleAnswerModelUnavailableException;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.RuleAnswerModel.PlannedSubquestion;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.AnswerConfidence;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class AnswerDraftComposerTest {

    @Test
    void retainsAReadyDraftAndPreservesTypedModelFailureClassification() {
        UUID chunkId = UUID.randomUUID();
        ModelDraft expected = answerableDraft(
                "行动完成后获得1分。",
                "规则说明：完成行动后获得1分。",
                List.of(chunkId),
                List.of(),
                "HIGH");
        RuleAnswerModel model = request -> expected;
        AnswerDraftComposer composer = new AnswerDraftComposer(new AnswerModelGateway(
                model, new PermissiveRateLimiter(), new ImmediateAuditedAgentInvocations()));

        AnswerDraftComposer.Result result = composer.compose(
                UUID.randomUUID(), "player", null, request(chunkId));

        assertThat(result.ready()).isTrue();
        assertThat(result.draft()).isEqualTo(expected);
        assertThat(result.failureStatus()).isNull();
        assertThat(result.modelRepairs()).isZero();

        AnswerDraftComposer.Result unavailable = composerFor(request -> {
                    throw new RuleAnswerModelUnavailableException("provider unavailable");
                })
                .compose(UUID.randomUUID(), "player", null, request(chunkId));
        AnswerDraftComposer.Result timeout = composerFor(request -> {
                    throw new RuleAnswerModelTimeoutException("provider timed out", new java.util.concurrent.TimeoutException());
                })
                .compose(UUID.randomUUID(), "player", null, request(chunkId));
        AnswerDraftComposer.Result invalid = composerFor(request -> {
                    throw new RuleAnswerModelInvalidOutputException("invalid JSON envelope");
                })
                .compose(UUID.randomUUID(), "player", null, request(chunkId));

        assertThat(unavailable.failureStatus()).isEqualTo(AnswerStatus.MODEL_UNAVAILABLE);
        assertThat(unavailable.failureMessage()).contains("模型", "配置", "不可用");
        assertThat(timeout.failureStatus()).isEqualTo(AnswerStatus.MODEL_TIMEOUT);
        assertThat(invalid.failureStatus()).isEqualTo(AnswerStatus.INVALID_MODEL_OUTPUT);
    }

    @Test
    void preservesSupportedScopedRulebookWordingWithoutAKeywordTriggeredRepair() {
        UUID chunkId = UUID.randomUUID();
        AtomicInteger revisions = new AtomicInteger();
        ModelDraft supported = answerableDraft(
                "The bonus does not apply after the final round.",
                "The rulebook does not apply this bonus after the final round; the cited timing clause is explicit.",
                List.of(chunkId),
                List.of(),
                "HIGH");
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return supported;
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                return previousDraft;
            }
        };
        AnswerDraftComposer composer = new AnswerDraftComposer(new AnswerModelGateway(
                model, new PermissiveRateLimiter(), new ImmediateAuditedAgentInvocations()));

        AnswerDraftComposer.Result result = composer.compose(
                UUID.randomUUID(), "player", null, request(chunkId));

        assertThat(result.ready()).isTrue();
        assertThat(result.draft()).isEqualTo(supported);
        assertThat(result.draft().citationIds()).containsExactly(chunkId);
        assertThat(result.warnings()).isEmpty();
        assertThat(result.modelRepairs()).isZero();
        assertThat(revisions).hasValue(0);
    }

    @Test
    void auditsEachCompleteReplacementUntilTheAgentReturnsAValidEnvelope() {
        UUID chunkId = UUID.randomUUID();
        ModelDraft replacement = answerableDraft(
                "修正后的结论",
                "修正后的完整解释直接由引用规则支持。",
                List.of(chunkId),
                List.of(),
                "HIGH");
        AtomicInteger compositions = new AtomicInteger();
        AtomicInteger replacements = new AtomicInteger();
        AtomicReference<RejectedOutput> feedback = new AtomicReference<>();
        RejectedOutput firstRejection = new RejectedOutput(
                "{\"answerable\":true}",
                "Missing required creator property 'citationIds'",
                "{\"type\":\"object\",\"required\":[\"citationIds\"]}",
                Set.of(chunkId));
        RejectedOutput secondRejection = new RejectedOutput(
                "{\"answerable\":true,\"citationIds\":[\"unknown\"]}",
                "citationIds[0] is not a UUID",
                firstRejection.schema(),
                Set.of(chunkId));
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                compositions.incrementAndGet();
                throw new RuleAnswerModelInvalidOutputException(
                        "invalid JSON envelope", null, firstRejection);
            }

            @Override
            public ModelDraft replaceInvalidOutput(ModelRequest request, RejectedOutput rejectedOutput) {
                int replacementNumber = replacements.incrementAndGet();
                feedback.set(rejectedOutput);
                if (replacementNumber == 1) {
                    throw new RuleAnswerModelInvalidOutputException(
                            "invalid replacement JSON envelope", null, secondRejection);
                }
                return replacement;
            }
        };
        RecordingInvocations invocations = new RecordingInvocations();
        AnswerDraftComposer composer = new AnswerDraftComposer(
                new AnswerModelGateway(model, new PermissiveRateLimiter(), invocations));

        AnswerDraftComposer.Result result = composer.compose(
                UUID.randomUUID(), "player", null, request(chunkId));

        assertThat(result.ready()).isTrue();
        assertThat(result.draft()).isEqualTo(replacement);
        assertThat(result.modelRepairs()).isEqualTo(2);
        assertThat(compositions).hasValue(1);
        assertThat(replacements).hasValue(2);
        assertThat(feedback).hasValue(secondRejection);
        assertThat(invocations.operations)
                .containsExactly(
                        "composeRuleAnswer",
                        "replaceInvalidRuleAnswerOutput",
                        "replaceInvalidRuleAnswerOutput");
    }

    @Test
    void doesNotParseQuotedProseOrAddAModelRepairCall() {
        UUID overviewId = UUID.randomUUID();
        UUID victoryId = UUID.randomUUID();
        String clause = "A player wins immediately after reaching thirty points.";
        String explanation = "The rule states: \u201c" + clause + "\u201d";
        AtomicInteger revisions = new AtomicInteger();
        ModelRequest request = new ModelRequest(
                "How does a player win?",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(
                        new EvidenceInput(overviewId, "RULE", "Overview", "Turns proceed clockwise.", 1, 1),
                        new EvidenceInput(victoryId, "RULE", "Victory", clause, 2, 2)));
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest ignored) {
                return answerableDraft(
                        "Reach thirty points.",
                        explanation,
                        List.of(overviewId),
                        List.of(),
                        "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest ignored, ModelDraft previous, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).singleElement().asString()
                        .contains("CITATION_OWNERSHIP", victoryId.toString());
                return answerableDraft(
                        "Do not replace this locked verdict.",
                        "Do not replace this locked explanation.",
                        List.of(overviewId, victoryId),
                        List.of("Do not add an exception."),
                        "LOW");
            }
        };
        AnswerDraftComposer composer = new AnswerDraftComposer(new AnswerModelGateway(
                model, new PermissiveRateLimiter(), new ImmediateAuditedAgentInvocations()));

        AnswerDraftComposer.Result result = composer.compose(
                UUID.randomUUID(), "player", null, request);

        assertThat(result.ready()).isTrue();
        assertThat(result.draft().shortVerdict()).isEqualTo("Reach thirty points.");
        assertThat(result.draft().explanation()).isEqualTo(explanation);
        assertThat(result.draft().exceptions()).isEmpty();
        assertThat(result.draft().citationIds()).containsExactly(overviewId);
        assertThat(result.draft().confidence()).isEqualTo(AnswerConfidence.HIGH);
        assertThat(result.modelRepairs()).isZero();
        assertThat(revisions).hasValue(0);
    }

    @Test
    void finalValidatorFailureProducesOneCompleteModelReplacementWithoutFieldSplicing() {
        UUID chunkId = UUID.randomUUID();
        ModelDraft rejected = answerableDraft(
                "旧结论",
                "旧解释",
                List.of(chunkId),
                List.of("旧例外"),
                "LOW");
        ModelDraft replacement = answerableDraft(
                "修正后的完整结论",
                "修正后的完整解释直接由同一条证据支持。",
                List.of(chunkId),
                List.of(),
                "HIGH");
        AtomicReference<List<String>> feedbackReceived = new AtomicReference<>();
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return rejected;
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                assertThat(previousDraft).isEqualTo(rejected);
                revisions.incrementAndGet();
                feedbackReceived.set(List.copyOf(feedback));
                return replacement;
            }
        };
        AnswerDraftComposer composer = new AnswerDraftComposer(new AnswerModelGateway(
                model, new PermissiveRateLimiter(), new ImmediateAuditedAgentInvocations()));

        AnswerDraftComposer.Result result = composer.continueAfterValidationRejection(
                UUID.randomUUID(),
                "player",
                null,
                request(chunkId),
                rejected,
                new IllegalArgumentException("CITATION_OWNERSHIP: citation belongs to another source"));

        assertThat(result.ready()).isTrue();
        assertThat(result.draft()).isEqualTo(replacement);
        assertThat(result.modelRepairs()).isEqualTo(1);
        assertThat(revisions).hasValue(1);
        assertThat(String.join(" ", feedbackReceived.get()))
                .contains("CITATION_OWNERSHIP", "complete replacement", "patch");
    }

    private ModelRequest request(UUID chunkId) {
        return new ModelRequest(
                "完成行动后会发生什么？",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.ZH_CN),
                List.of(new EvidenceInput(
                        chunkId,
                        "ACTIONS",
                        "完成行动",
                        "完成行动后获得1分。",
                        5,
                        5)));
    }

    private static AnswerDraftComposer composerFor(RuleAnswerModel model) {
        return new AnswerDraftComposer(new AnswerModelGateway(
                model, new PermissiveRateLimiter(), new ImmediateAuditedAgentInvocations()));
    }

    private static ModelDraft answerableDraft(
            String shortVerdict,
            String explanation,
            List<UUID> citationIds,
            List<String> exceptions,
            String confidence) {
        return new ModelDraft(
                true,
                null,
                shortVerdict,
                explanation,
                citationIds,
                exceptions,
                confidence,
                "DIRECT_RULE");
    }

    private ModelRequest adviceRequest(UUID chunkId) {
        return new ModelRequest(
                "怎么赢？有没有保证获胜的开局？",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.ZH_CN),
                List.of(new EvidenceInput(chunkId, "RULE", "获胜", "达到30分即可获胜。", 5, 5)),
                Set.of(EvidenceNeed.DIRECT_RULE, EvidenceNeed.ADVICE),
                AnswerAid.NONE,
                List.of(
                        new PlannedSubquestion("怎么赢？", Set.of(EvidenceNeed.DIRECT_RULE)),
                        new PlannedSubquestion("有没有保证获胜的开局？", Set.of(EvidenceNeed.ADVICE))));
    }

    private static final class PermissiveRateLimiter implements RuleAnswerRateLimiter {

        @Override
        public void checkUser(String username) {}

        @Override
        public Permit acquireModel(String username, UUID gameSessionId, String providerId) {
            return () -> {};
        }
    }

    private static final class RecordingInvocations implements AuditedAgentInvocations {
        private final List<String> operations = new ArrayList<>();

        @Override
        public <T> T invoke(
                UUID runId,
                ActivityType type,
                String operation,
                int estimatedInputTokens,
                String successSummary,
                Supplier<T> invocation,
                ToIntFunction<T> outputTokenEstimator) {
            operations.add(operation);
            return invocation.get();
        }
    }
}
