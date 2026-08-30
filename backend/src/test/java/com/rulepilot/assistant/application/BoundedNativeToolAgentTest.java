package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.NativeAgentTool;
import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolAgent;
import com.rulepilot.assistant.NativeToolAgent.RunRequest;
import com.rulepilot.assistant.NativeToolAgent.RunStatus;
import com.rulepilot.assistant.NativeToolAgent.TerminalContract;
import com.rulepilot.assistant.NativeToolAgent.TerminalValidation;
import com.rulepilot.assistant.NativeToolModel;
import com.rulepilot.assistant.NativeToolModel.ConversationMessage;
import com.rulepilot.assistant.NativeToolModel.MessageRole;
import com.rulepilot.assistant.NativeToolModel.ModelRequest;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class BoundedNativeToolAgentTest {

    @Test
    void overlapsIndependentReadsKeepsTheSuccessfulSiblingAndMakesOneNextDecision() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        NativeAgentTool first = overlapping("search_rule_evidence", bothStarted, active, peak);
        NativeAgentTool second = overlapping("read_rule_pages", bothStarted, active, peak);
        QueueModel model = new QueueModel(
                turn(
                        call("search", first.name(), "{\"query\":\"victory\"}"),
                        call("page", second.name(), "{\"pageNumbers\":[7]}")),
                finalTurn("done"));
        RecordingInvocations audited = new RecordingInvocations("read_rule_pages");

        var result = agent(model, List.of(first, second), audited).run(request(
                Set.of(first.name(), second.name()), TerminalContract.none()));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(peak).hasValue(2);
        assertThat(model.requests).hasValue(2);
        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(result.observations()).extracting(NativeToolAgent.ObservationRecord::toolName)
                .containsExactly(first.name(), second.name());
        assertThat(result.observations()).extracting(value -> value.observation().code())
                .containsExactly("FOUND", "TOOL_EXECUTION_FAILED");
        assertThat(model.conversations.get(1)).extracting(ConversationMessage::role)
                .containsExactly(
                        MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT,
                        MessageRole.TOOL, MessageRole.TOOL);
    }

    @Test
    void rejectsDependentSiblingBatchAsOneTypedActionWithoutExecutingIt() {
        AtomicInteger executions = new AtomicInteger();
        NativeAgentTool search = counting("search_rule_evidence", executions);
        NativeAgentTool pages = counting("read_rule_pages", executions);
        QueueModel model = new QueueModel(
                turn(
                        call("search", search.name(), "{\"query\":\"winner\"}"),
                        call("page", pages.name(), "{\"sourceCallId\":\"search\"}")),
                finalTurn("done"));

        var result = agent(model, List.of(search, pages), new RecordingInvocations(null)).run(request(
                Set.of(search.name(), pages.name()), TerminalContract.none()));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(executions).hasValue(0);
        assertThat(model.conversations.get(1)).extracting(ConversationMessage::content)
                .anySatisfy(content -> assertThat(content)
                        .contains("BATCH_ACTION_INCOMPATIBLE", "depends on that sibling observation"));
    }

    @Test
    void returnsTheWholeRejectedTerminalAndTypedBoundaryToTheSameAgent() {
        String rejected = "{\"kind\":\"RULE_ANSWER\",\"citationIds\":[\"outside\"],\"extra\":true}";
        String accepted = "{\"kind\":\"CHAT\",\"shortVerdict\":\"你好\",\"explanation\":\"\",\"extra\":true}";
        QueueModel model = new QueueModel(finalTurn(rejected), finalTurn(accepted));
        String schema = """
                {"type":"object","required":["kind"],"properties":{"kind":{"type":"string"}},
                 "additionalProperties":true}
                """;
        TerminalContract contract = TerminalContract.json(schema, (candidate, observations) ->
                candidate.equals(rejected)
                        ? TerminalValidation.rejected(
                                "CITATION_NOT_OBSERVED", "/citationIds/0", "identity is outside this run",
                                Set.of("evidence-1"))
                        : TerminalValidation.accepted());

        var result = agent(model, List.of(tool("search_rule_evidence")), new RecordingInvocations(null))
                .run(request(Set.of("search_rule_evidence"), contract));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.text()).isEqualTo(accepted);
        assertThat(model.requests).hasValue(2);
        assertThat(model.conversations.get(1))
                .anySatisfy(message -> assertThat(message)
                        .returns(MessageRole.ASSISTANT, ConversationMessage::role)
                        .returns(rejected, ConversationMessage::content));
        assertThat(model.conversations.get(1))
                .filteredOn(message -> message.role() == MessageRole.USER
                        && message.content().contains("CITATION_NOT_OBSERVED"))
                .singleElement()
                .satisfies(message -> assertThat(message.content())
                        .contains(
                                "/citationIds/0",
                                "identity is outside this run",
                                "currentSchema",
                                "evidence-1")
                        .doesNotContain(rejected, "<rejected-candidate>"));
    }

    @Test
    void returnsCompleteInvalidArgumentsAndAcceptsAnAdditiveFieldOnTheReplacement() throws Exception {
        NativeAgentTool checked = new TestTool("search_rule_evidence") {
            @Override
            public ToolObservation execute(String argumentsJson, ToolScope scope) {
                if (!argumentsJson.contains("\"limit\":1")) {
                    throw new IllegalArgumentException("limit must be positive");
                }
                return super.execute(argumentsJson, scope);
            }
        };
        String invalid = "{\"limit\":0,\"additive\":\"preserve-me\"}";
        QueueModel model = new QueueModel(
                turn(call("bad", checked.name(), invalid)),
                turn(call("good", checked.name(), "{\"limit\":1,\"additive\":true}")),
                finalTurn("done"));

        var result = agent(model, List.of(checked), new RecordingInvocations(null))
                .run(request(Set.of(checked.name()), TerminalContract.none()));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.observations()).extracting(value -> value.observation().code())
                .containsExactly("INVALID_ARGUMENT", "FOUND");
        String rejectionJson = model.conversations.get(1).stream()
                .filter(message -> message.role() == MessageRole.TOOL)
                .map(ConversationMessage::content)
                .findFirst()
                .orElseThrow();
        var rejection = JsonMapper.builder().build().readTree(rejectionJson);
        assertThat(rejection.path("code").asText()).isEqualTo("INVALID_ARGUMENT");
        assertThat(rejection.path("data").path("rejectedArgumentsJson").asText()).isEqualTo(invalid);
        assertThat(rejection.path("data").path("currentSchema").asText())
                .isEqualTo(checked.inputSchema());
        assertThat(rejection.path("data").path("allowedToolNames").isArray()).isTrue();
        assertThat(rejection.path("data").path("allowedToolNames").size()).isOne();
        assertThat(rejection.path("data").path("allowedToolNames").get(0).asText())
                .isEqualTo(checked.name());
        assertThat(rejection.path("data").path("path").asText()).isEqualTo("/");
        assertThat(rejection.path("data").path("reason").asText()).isEqualTo("limit must be positive");
        assertThat(checked.inputSchema()).contains("\"additionalProperties\":true");
    }

    private BoundedNativeToolAgent agent(
            NativeToolModel model, List<NativeAgentTool> tools, AuditedAgentInvocations audited) {
        var mapper = JsonMapper.builder().findAndAddModules().build();
        return new BoundedNativeToolAgent(
                model,
                new NativeAgentToolRegistry(tools, mapper, ignored -> true),
                mock(AgentExecutionControl.class),
                audited,
                mapper);
    }

    private RunRequest request(Set<String> allowedTools, TerminalContract contract) {
        return new RunRequest(
                Role.ANSWER,
                new ToolScope(
                        "player", UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(30)),
                "Choose reads or return one terminal response.",
                "Help the player.",
                "No publishable response.",
                allowedTools,
                Set.of(),
                contract);
    }

    private NativeAgentTool overlapping(
            String name,
            CountDownLatch bothStarted,
            AtomicInteger active,
            AtomicInteger peak) {
        return new TestTool(name) {
            @Override
            public ToolObservation execute(String argumentsJson, ToolScope scope) {
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                bothStarted.countDown();
                try {
                    if (!bothStarted.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("sibling did not overlap");
                    }
                    return super.execute(argumentsJson, scope);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                } finally {
                    active.decrementAndGet();
                }
            }
        };
    }

    private NativeAgentTool counting(String name, AtomicInteger executions) {
        return new TestTool(name) {
            @Override
            public ToolObservation execute(String argumentsJson, ToolScope scope) {
                executions.incrementAndGet();
                return super.execute(argumentsJson, scope);
            }
        };
    }

    private NativeAgentTool tool(String name) {
        return new TestTool(name);
    }

    private ModelToolCall call(String id, String name, String arguments) {
        return new ModelToolCall(id, name, arguments);
    }

    private ModelTurn turn(ModelToolCall... calls) {
        return new ModelTurn("", List.of(calls), 10, 5);
    }

    private ModelTurn finalTurn(String text) {
        return new ModelTurn(text, List.of(), 10, 5);
    }

    private static class TestTool implements NativeAgentTool {
        private final String name;

        private TestTool(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }

        @Override public String description() { return "Read one bounded test observation."; }

        @Override
        public String inputSchema() {
            return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":true}";
        }

        @Override public String schemaVersion() { return "1"; }

        @Override public Set<Role> allowedRoles() { return Set.of(Role.ANSWER); }

        @Override
        public ToolObservation execute(String argumentsJson, ToolScope scope) {
            return ToolObservation.success("FOUND", Map.of("arguments", argumentsJson), 1);
        }
    }

    private static final class QueueModel implements NativeToolModel {
        private final Deque<ModelTurn> turns = new ArrayDeque<>();
        private final List<List<ConversationMessage>> conversations = new ArrayList<>();
        private final AtomicInteger requests = new AtomicInteger();

        private QueueModel(ModelTurn... turns) {
            this.turns.addAll(List.of(turns));
        }

        @Override
        public synchronized ModelTurn next(ModelRequest request) {
            requests.incrementAndGet();
            conversations.add(request.conversation());
            return turns.removeFirst();
        }
    }

    private static final class RecordingInvocations implements AuditedAgentInvocations {
        private final String failAfterTool;
        private final List<String> operations = new CopyOnWriteArrayList<>();

        private RecordingInvocations(String failAfterTool) {
            this.failAfterTool = failAfterTool;
        }

        @Override
        public <T> T invoke(
                UUID runId,
                ActivityType type,
                String operation,
                int inputTokens,
                String summary,
                Supplier<T> invocation,
                ToIntFunction<T> outputTokenEstimator) {
            T result = invocation.get();
            operations.add(operation);
            if (type == ActivityType.TOOL && failAfterTool != null && operation.contains(failAfterTool)) {
                throw new IllegalStateException("isolated sibling audit failure");
            }
            return result;
        }

        @Override
        public void record(
                UUID runId, ActivityType type, String operation, ActivityOutcome outcome, String summary) {
            operations.add(operation);
        }
    }
}
