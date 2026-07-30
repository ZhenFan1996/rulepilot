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
import com.rulepilot.assistant.RuleAnswerModel.RetrievalQueryRequest;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
        AnswerModelGateway gateway = new AnswerModelGateway(model(expected, expected, List.of()), limiter, invocations);

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
    void rewritesRetrievalPhrasesWithoutBorrowingALiveGameSessionPermit() {
        RecordingRateLimiter limiter = new RecordingRateLimiter();
        RecordingInvocations invocations = new RecordingInvocations();
        AnswerModelGateway gateway = new AnswerModelGateway(
                model(draft("unused"), draft("unused"), List.of("setup actions", "end scoring")), limiter, invocations);

        List<String> phrases = gateway.rewriteRetrievalQueries(
                runId, "alice", new RetrievalQueryRequest("如何设置？", null, "设置"));

        assertThat(phrases).containsExactly("setup actions", "end scoring");
        assertThat(limiter.requests).containsExactly(new PermitRequest("alice", null, "test-provider"));
        assertThat(limiter.releases).isEqualTo(1);
        assertThat(invocations.calls).containsExactly(new Invocation(
                runId,
                ActivityType.MODEL,
                "rewriteAnswerRetrievalQueries",
                "Cross-language retrieval phrases prepared",
                true));
    }

    private RuleAnswerModel model(ModelDraft composition, ModelDraft revision, List<String> rewrittenQueries) {
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

            @Override
            public List<String> rewriteRetrievalQueries(RetrievalQueryRequest request) {
                return rewrittenQueries;
            }
        };
    }

    private ModelRequest request() {
        return new ModelRequest(
                "如何执行行动？",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, null, PlayerLocale.ZH_CN),
                List.of(new EvidenceInput(UUID.randomUUID(), "RULES", "行动", "执行该行动。", 2, 2)));
    }

    private ModelDraft draft(String verdict) {
        return new ModelDraft(verdict, "依据规则执行。", List.of(UUID.randomUUID()), List.of(), "HIGH");
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
