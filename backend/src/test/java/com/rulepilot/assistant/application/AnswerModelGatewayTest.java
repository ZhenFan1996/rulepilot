package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.PlayerFacingField;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class AnswerModelGatewayTest {

    private final UUID runId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @Test
    void composesUnderOneAuditedProviderPermit() {
        ModelDraft expected = draft("完成回答。");
        RecordingRateLimiter limiter = new RecordingRateLimiter();
        RecordingInvocations invocations = new RecordingInvocations();
        AnswerModelGateway gateway = new AnswerModelGateway(model(expected, expected), limiter, invocations);

        ModelDraft result = gateway.compose(runId, "alice", sessionId, request());

        assertThat(result).isEqualTo(expected);
        assertThat(limiter.requests).containsExactly(new PermitRequest("alice", sessionId, "test-provider"));
        assertThat(limiter.releases).isEqualTo(1);
        assertThat(invocations.calls).containsExactly(new Invocation(
                runId,
                ActivityType.MODEL,
                "composeRuleAnswer",
                "Rule answer model output received",
                true));
    }

    @Test
    void releasesThePermitWhenRevisionFails() {
        RecordingRateLimiter limiter = new RecordingRateLimiter();
        RuleAnswerModel failingModel = new RuleAnswerModel() {
            @Override
            public String providerId() {
                return "test-provider";
            }

            @Override
            public ModelDraft compose(ModelRequest request) {
                return draft("unused");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                throw new IllegalStateException("provider unavailable");
            }
        };
        AnswerModelGateway gateway = new AnswerModelGateway(failingModel, limiter, new RecordingInvocations());

        assertThatThrownBy(() -> gateway.revise(
                        runId,
                        "alice",
                        sessionId,
                        request(),
                        draft("旧回答。"),
                        List.of("修订。"),
                        "repairPlayerFacingRuleAnswer",
                        "Answer repaired"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider unavailable");
        assertThat(limiter.releases).isEqualTo(1);
    }

    @Test
    void resolvesTheProviderAndAnswerCallFromTheExplicitOwnerOutsideRequestThreadState() {
        AtomicReference<String> providerOwner = new AtomicReference<>();
        AtomicReference<String> compositionOwner = new AtomicReference<>();
        ModelDraft expected = draft("仅使用 Alice 的模型配置。");
        RuleAnswerModel ownerScoped = new RuleAnswerModel() {
            @Override
            public String providerId() {
                throw new AssertionError("thread-local startup provider must not be consulted");
            }

            @Override
            public String providerId(String ownerUsername) {
                providerOwner.set(ownerUsername);
                return "alice-provider";
            }

            @Override
            public ModelDraft compose(ModelRequest request) {
                throw new AssertionError("thread-local startup model must not be invoked");
            }

            @Override
            public ModelDraft compose(ModelRequest request, String ownerUsername) {
                compositionOwner.set(ownerUsername);
                return expected;
            }
        };
        RecordingRateLimiter limiter = new RecordingRateLimiter();
        AnswerModelGateway gateway = new AnswerModelGateway(
                ownerScoped, limiter, new RecordingInvocations());

        assertThat(gateway.compose(runId, "alice", sessionId, request())).isEqualTo(expected);
        assertThat(providerOwner).hasValue("alice");
        assertThat(compositionOwner).hasValue("alice");
        assertThat(limiter.requests).containsExactly(new PermitRequest("alice", sessionId, "alice-provider"));
    }

    @Test
    void locksEveryFieldThatWasNotExplicitlyRejectedForPlayerFacingRepair() {
        UUID retainedCitation = UUID.randomUUID();
        ModelDraft previous = draft(
                "原裁定。", "原解释。", List.of(retainedCitation), List.of("原例外。"), "HIGH");
        ModelDraft providerRepair = draft(
                "不应改动的裁定。",
                "修复后的解释。",
                List.of(UUID.randomUUID()),
                List.of("不应改动的例外。"),
                "LOW");
        AnswerModelGateway gateway = new AnswerModelGateway(
                model(draft("unused"), providerRepair),
                new RecordingRateLimiter(),
                new RecordingInvocations());

        ModelDraft repaired = gateway.revisePlayerFacing(
                runId,
                "alice",
                sessionId,
                request(),
                previous,
                List.of("Only repair the explanation."),
                Set.of(PlayerFacingField.EXPLANATION),
                "repairPlayerFacingRuleAnswer",
                "Answer repaired");

        assertThat(repaired.shortVerdict()).isEqualTo(previous.shortVerdict());
        assertThat(repaired.explanation()).isEqualTo(providerRepair.explanation());
        assertThat(repaired.exceptions()).isEqualTo(previous.exceptions());
        assertThat(repaired.citationIds()).isEqualTo(previous.citationIds());
        assertThat(repaired.confidence()).isEqualTo(previous.confidence());
    }

    private RuleAnswerModel model(ModelDraft composition, ModelDraft revision) {
        return new RuleAnswerModel() {
            @Override
            public String providerId() {
                return "test-provider";
            }

            @Override
            public ModelDraft compose(ModelRequest request) {
                return composition;
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                return revision;
            }
        };
    }

    private ModelRequest request() {
        return new ModelRequest(
                "如何执行行动？",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.ZH_CN),
                List.of(new EvidenceInput(UUID.randomUUID(), "RULES", "行动", "执行该行动。", 2, 2)));
    }

    private ModelDraft draft(String verdict) {
        return draft(verdict, "依据规则执行。", List.of(UUID.randomUUID()), List.of(), "HIGH");
    }

    private ModelDraft draft(
            String verdict,
            String explanation,
            List<UUID> citationIds,
            List<String> exceptions,
            String confidence) {
        return new ModelDraft(
                true,
                null,
                verdict,
                explanation,
                citationIds,
                exceptions,
                confidence,
                "DIRECT_RULE",
                List.of());
    }

    private static final class RecordingRateLimiter implements RuleAnswerRateLimiter {
        private final List<PermitRequest> requests = new ArrayList<>();
        private int releases;

        @Override
        public void checkUser(String username) {}

        @Override
        public Permit acquireModel(String username, UUID gameSessionId, String providerId) {
            requests.add(new PermitRequest(username, gameSessionId, providerId));
            return () -> releases++;
        }
    }

    private static final class RecordingInvocations implements AuditedAgentInvocations {
        private final List<Invocation> calls = new ArrayList<>();

        @Override
        public <T> T invoke(
                UUID runId,
                ActivityType type,
                String operation,
                int estimatedInputTokens,
                String successSummary,
                Supplier<T> invocation,
                ToIntFunction<T> outputTokenEstimator) {
            T result = invocation.get();
            calls.add(new Invocation(runId, type, operation, successSummary, outputTokenEstimator.applyAsInt(result) > 0));
            return result;
        }
    }

    private record PermitRequest(String username, UUID gameSessionId, String providerId) {}

    private record Invocation(UUID runId, ActivityType type, String operation, String summary, boolean hasOutputEstimate) {}
}
