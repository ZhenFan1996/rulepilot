package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.NativeAgentTool;
import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import com.rulepilot.assistant.NativeAgentTool.ToolMedia;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolAgent.RunRequest;
import com.rulepilot.assistant.NativeToolAgent.RunStatus;
import com.rulepilot.assistant.NativeToolModel;
import com.rulepilot.assistant.NativeToolModel.ModelToolCall;
import com.rulepilot.assistant.NativeToolModel.ModelTurn;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class BoundedNativeToolAgentTest {

    @Test
    void completesAfterOneRequiredReadToolObservation() {
        QueueModel model = new QueueModel(
                turn(call("call-1", "search_rule_evidence", "{\"query\":\"setup\"}")),
                finalTurn("Cited player guidance"));
        RecordingInvocations audited = new RecordingInvocations();
        BoundedNativeToolAgent agent = agent(model, List.of(successTool("search_rule_evidence")), audited);

        var result = agent.run(request(scope(), 4));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.text()).isEqualTo("Cited player guidance");
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.observations()).singleElement().satisfies(observation -> {
            assertThat(observation.toolName()).isEqualTo("search_rule_evidence");
            assertThat(observation.schemaHash()).matches("[a-f0-9]{64}");
            assertThat(observation.observation().evidenceCount()).isEqualTo(1);
        });
        assertThat(audited.invokedTypes).containsExactly(ActivityType.MODEL, ActivityType.TOOL, ActivityType.MODEL);
        assertThat(audited.invokedOperations).anyMatch(operation ->
                operation.matches("nativeTool\\|search_rule_evidence\\|[a-f0-9]{12}"));
        assertThat(audited.recordedOperations).anyMatch(operation ->
                operation.matches("nativeObs\\|search_rule_evidence\\|[a-f0-9]{12}\\|[a-f0-9]{8}"));
    }

    @Test
    void permitsARelevantSecondObservationBeforeCompletion() {
        QueueModel model = new QueueModel(
                turn(call("call-1", "search_rule_evidence", "{\"query\":\"end trigger\"}")),
                turn(call("call-2", "read_rule_pages", "{\"pageNumbers\":[4]}")),
                finalTurn("The player can now verify the exception."));
        BoundedNativeToolAgent agent = agent(
                model,
                List.of(successTool("search_rule_evidence"), successTool("read_rule_pages")),
                new RecordingInvocations());

        var result = agent.run(request(scope(), 4));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.iterations()).isEqualTo(3);
        assertThat(result.observations()).extracting(observation -> observation.toolName())
                .containsExactly("search_rule_evidence", "read_rule_pages");
    }

    @Test
    void rejectsPrematureCompletionUntilTheRequiredConfirmationToolIsObserved() {
        QueueModel model = new QueueModel(
                turn(call("call-1", "search_rule_evidence", "{\"query\":\"end condition\"}")),
                finalTurn("EVIDENCE_READY"),
                turn(call("call-2", "read_rule_pages", "{\"pageNumbers\":[4]}")),
                finalTurn("EVIDENCE_READY"));
        RecordingInvocations audited = new RecordingInvocations();
        BoundedNativeToolAgent agent = agent(
                model,
                List.of(successTool("search_rule_evidence"), successTool("read_rule_pages")),
                audited);
        RunRequest request = new RunRequest(
                Role.ANSWER,
                scope(),
                "Confirm evidence before completion.",
                "Verify every part of a compound question.",
                "Insufficient verified evidence.",
                4,
                512,
                Set.of("read_rule_pages"));

        var result = agent.run(request);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.iterations()).isEqualTo(4);
        assertThat(result.observations()).extracting(observation -> observation.toolName())
                .containsExactly("search_rule_evidence", "read_rule_pages");
        assertThat(audited.recordedOperations).contains("nativeCompletionRequirement");
    }

    @Test
    void returnsDirectlyWhenThePlayerRequestNeedsNoTool() {
        RecordingInvocations audited = new RecordingInvocations();
        BoundedNativeToolAgent agent = agent(
                new QueueModel(finalTurn("READY")),
                List.of(successTool("search_rule_evidence")),
                audited);

        var result = agent.run(request(scope(), 3));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.toolCalls()).isZero();
        assertThat(result.observations()).isEmpty();
        assertThat(audited.invokedTypes).containsExactly(ActivityType.MODEL);
    }

    @Test
    void feedsOneInvalidArgumentObservationBackWithoutPublishingItAsEvidence() {
        NativeAgentTool invalid = failingTool("search_rule_evidence", true);
        QueueModel model = new QueueModel(
                turn(call("call-1", "search_rule_evidence", "{\"limit\":99}")),
                finalTurn("I cannot verify that request."));
        BoundedNativeToolAgent agent = agent(model, List.of(invalid), new RecordingInvocations());

        var result = agent.run(request(scope(), 3));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.observations()).singleElement().satisfies(observation -> {
            assertThat(observation.observation().status()).isEqualTo(NativeAgentTool.ObservationStatus.ERROR);
            assertThat(observation.observation().code()).isEqualTo("INVALID_ARGUMENT");
            assertThat(observation.observation().evidenceCount()).isZero();
        });
    }

    @Test
    void opensCircuitOnAnIdenticalRepeatedFailedCall() {
        NativeAgentTool invalid = failingTool("search_rule_evidence", true);
        ModelToolCall repeated = call("call-1", "search_rule_evidence", "{\"limit\":99}");
        QueueModel model = new QueueModel(turn(repeated), turn(new ModelToolCall("call-2", repeated.name(), repeated.argumentsJson())));
        RecordingInvocations audited = new RecordingInvocations();
        BoundedNativeToolAgent agent = agent(model, List.of(invalid), audited);

        var result = agent.run(request(scope(), 4));

        assertThat(result.status()).isEqualTo(RunStatus.FALLBACK);
        assertThat(result.reason()).isEqualTo("TOOL_CIRCUIT_OPEN");
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(audited.recordedOperations).contains("nativeCircuit|search_rule_evidence");
    }

    @Test
    void terminatesBeforeCallingTheModelWhenDeadlineExpired() {
        QueueModel model = new QueueModel(finalTurn("must not run"));
        BoundedNativeToolAgent agent = agent(model, List.of(successTool("search_rule_evidence")), new RecordingInvocations());
        ToolScope expired = new ToolScope("player", UUID.randomUUID(), UUID.randomUUID(), Instant.now().minusSeconds(1));

        var result = agent.run(request(expired, 3));

        assertThat(result.status()).isEqualTo(RunStatus.FALLBACK);
        assertThat(result.reason()).isEqualTo("TIMEOUT");
        assertThat(model.requests).isZero();
    }

    @Test
    void isolatesProviderFailureBehindDeterministicFallback() {
        NativeToolModel failing = request -> { throw new IllegalStateException("provider unavailable"); };
        BoundedNativeToolAgent agent = agent(failing, List.of(successTool("search_rule_evidence")), new RecordingInvocations());

        var result = agent.run(request(scope(), 3));

        assertThat(result.status()).isEqualTo(RunStatus.FALLBACK);
        assertThat(result.reason()).isEqualTo("EXECUTION_FAILED");
        assertThat(result.text()).isEqualTo("Insufficient verified evidence.");
    }

    @Test
    void forwardsVisualToolMediaOnlyOnTheFollowingModelTurn() {
        InspectingMediaModel model = new InspectingMediaModel();
        NativeAgentTool visual = new TestTool("read_rule_page_image", false, false) {
            @Override
            public Set<Role> allowedRoles() {
                return Set.of(Role.VISUAL);
            }

            @Override
            public ToolObservation execute(String argumentsJson, ToolScope scope) {
                return new ToolObservation(
                        NativeAgentTool.ObservationStatus.SUCCESS,
                        "PAGE_IMAGE_FOUND",
                        Map.of("mechanicalRuleAuthority", false),
                        1,
                        List.of(new ToolMedia("image/png", new byte[] {1, 2}, "page", 10, 20)));
            }
        };
        BoundedNativeToolAgent agent = agent(model, List.of(visual), new RecordingInvocations());

        RunRequest visualRequest = new RunRequest(
                Role.VISUAL,
                scope(),
                "Inspect literal appearance only.",
                "Locate one cited object.",
                "No verified crop.",
                3,
                256);
        var result = agent.run(visualRequest);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(model.sawVisualMedia).isTrue();
    }

    @Test
    void stopsBeforeModelOrToolCallsWhenTheRoleCapabilityIsUnavailable() {
        QueueModel model = new QueueModel(finalTurn("must not run")) {
            @Override
            public boolean supports(Role role, String ownerUsername) {
                return false;
            }
        };
        BoundedNativeToolAgent agent = agent(
                model, List.of(successTool("search_rule_evidence")), new RecordingInvocations());

        var result = agent.run(request(scope(), 3));

        assertThat(result.status()).isEqualTo(RunStatus.FALLBACK);
        assertThat(result.reason()).isEqualTo("MODEL_CAPABILITY_UNAVAILABLE");
        assertThat(model.requests).isZero();
    }

    @Test
    void preservesCancellationAsAnExplicitTerminalReason() {
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        ToolScope scope = scope();
        doThrow(new AgentExecutionStoppedException(StopReason.CANCELLED))
                .when(execution).assertStepAllowed(scope.runId(), 1);
        var mapper = JsonMapper.builder().findAndAddModules().build();
        BoundedNativeToolAgent agent = new BoundedNativeToolAgent(
                new QueueModel(finalTurn("must not run")),
                new NativeAgentToolRegistry(
                        List.of(successTool("search_rule_evidence")), mapper, ignored -> true),
                execution,
                new RecordingInvocations(),
                mapper);

        var result = agent.run(request(scope, 3));

        assertThat(result.status()).isEqualTo(RunStatus.FALLBACK);
        assertThat(result.reason()).isEqualTo("CANCELLED");
        assertThat(result.toolCalls()).isZero();
    }

    @Test
    void doesNotContinueOrReplayWhenExecutionStopsAfterAToolSideCompletes() {
        AtomicInteger toolExecutions = new AtomicInteger();
        NativeAgentTool tool = new TestTool("search_rule_evidence", false, false) {
            @Override
            public ToolObservation execute(String argumentsJson, ToolScope scope) {
                toolExecutions.incrementAndGet();
                return super.execute(argumentsJson, scope);
            }
        };
        QueueModel model = new QueueModel(
                turn(call("call-1", tool.name(), "{}")),
                finalTurn("must not continue"));
        AuditedAgentInvocations interrupted = new AuditedAgentInvocations() {
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
                if (type == ActivityType.TOOL) {
                    throw new AgentExecutionStoppedException(StopReason.CANCELLED);
                }
                return result;
            }

            @Override
            public void record(
                    UUID runId, ActivityType type, String operation, ActivityOutcome outcome, String summary) {}
        };
        var mapper = JsonMapper.builder().findAndAddModules().build();
        BoundedNativeToolAgent agent = new BoundedNativeToolAgent(
                model,
                new NativeAgentToolRegistry(List.of(tool), mapper, ignored -> true),
                mock(AgentExecutionControl.class),
                interrupted,
                mapper);

        var result = agent.run(request(scope(), 3));

        assertThat(result.status()).isEqualTo(RunStatus.FALLBACK);
        assertThat(result.reason()).isEqualTo("CANCELLED");
        assertThat(result.toolCalls()).isZero();
        assertThat(result.observations()).isEmpty();
        assertThat(toolExecutions).hasValue(1);
        assertThat(model.requests).isEqualTo(1);
    }

    private BoundedNativeToolAgent agent(
            NativeToolModel model, List<NativeAgentTool> tools, RecordingInvocations audited) {
        var mapper = JsonMapper.builder().findAndAddModules().build();
        return new BoundedNativeToolAgent(
                model,
                new NativeAgentToolRegistry(tools, mapper, ignored -> true),
                mock(AgentExecutionControl.class),
                audited,
                mapper);
    }

    private RunRequest request(ToolScope scope, int iterations) {
        return new RunRequest(
                Role.ANSWER,
                scope,
                "Use read tools only when evidence is needed.",
                "Help the player verify one rule.",
                "Insufficient verified evidence.",
                iterations,
                512);
    }

    private ToolScope scope() {
        return new ToolScope("player", UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(30));
    }

    private NativeAgentTool successTool(String name) {
        return new TestTool(name, false, false);
    }

    private NativeAgentTool failingTool(String name, boolean invalid) {
        return new TestTool(name, invalid, !invalid);
    }

    private ModelToolCall call(String id, String name, String arguments) {
        return new ModelToolCall(id, name, arguments);
    }

    private ModelTurn turn(ModelToolCall call) {
        return new ModelTurn("", List.of(call), 10, 5);
    }

    private ModelTurn finalTurn(String text) {
        return new ModelTurn(text, List.of(), 10, 5);
    }

    private static class QueueModel implements NativeToolModel {
        private final Deque<ModelTurn> turns;
        private int requests;

        private QueueModel(ModelTurn... turns) {
            this.turns = new ArrayDeque<>(List.of(turns));
        }

        @Override
        public ModelTurn next(ModelRequest request) {
            requests++;
            return turns.removeFirst();
        }
    }

    private static final class InspectingMediaModel implements NativeToolModel {
        private int turn;
        private boolean sawVisualMedia;

        @Override
        public ModelTurn next(ModelRequest request) {
            turn++;
            if (turn == 1) {
                return new ModelTurn(
                        "",
                        List.of(new ModelToolCall(
                                "visual-call", "read_rule_page_image", "{\"evidenceId\":\"ignored\"}")),
                        10,
                        5);
            }
            sawVisualMedia = request.conversation().stream().anyMatch(message -> !message.media().isEmpty());
            return new ModelTurn("verified", List.of(), 10, 5);
        }
    }

    private static class TestTool implements NativeAgentTool {
        private final String name;
        private final boolean invalid;
        private final boolean failure;

        private TestTool(String name, boolean invalid, boolean failure) {
            this.name = name;
            this.invalid = invalid;
            this.failure = failure;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "Read bounded rule evidence"; }
        @Override public String inputSchema() { return "{\"type\":\"object\"}"; }
        @Override public String schemaVersion() { return "1"; }
        @Override public Set<Role> allowedRoles() { return Set.of(Role.ANSWER); }

        @Override
        public ToolObservation execute(String argumentsJson, ToolScope scope) {
            if (invalid) throw new IllegalArgumentException("invalid");
            if (failure) throw new IllegalStateException("failed");
            return ToolObservation.success("FOUND", Map.of("evidence", List.of("bounded")), 1);
        }
    }

    private static final class RecordingInvocations implements AuditedAgentInvocations {
        private final List<ActivityType> invokedTypes = new ArrayList<>();
        private final List<String> invokedOperations = new ArrayList<>();
        private final List<String> recordedOperations = new ArrayList<>();

        @Override
        public <T> T invoke(
                UUID runId,
                ActivityType type,
                String operation,
                int estimatedInputTokens,
                String successSummary,
                Supplier<T> invocation,
                ToIntFunction<T> outputTokenEstimator) {
            invokedTypes.add(type);
            invokedOperations.add(operation);
            return invocation.get();
        }

        @Override
        public void record(UUID runId, ActivityType type, String operation, ActivityOutcome outcome, String summary) {
            recordedOperations.add(operation);
        }
    }
}
