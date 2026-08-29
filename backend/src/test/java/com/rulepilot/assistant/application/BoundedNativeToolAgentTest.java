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
import com.rulepilot.assistant.NativeAgentTool.ToolMedia;
import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolAgent;
import com.rulepilot.assistant.NativeToolAgent.RunRequest;
import com.rulepilot.assistant.NativeToolAgent.RunStatus;
import com.rulepilot.assistant.NativeToolAgent.TerminalContract;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class BoundedNativeToolAgentTest {

    @Test
    void returnsEachObservationBeforeTheAgentChoosesItsNextAction() {
        QueueModel model = new QueueModel(
                turn(call("call-1", "search_rule_evidence", "{\"query\":\"setup\"}")),
                finalTurn("Cited player guidance"));
        RecordingInvocations audited = new RecordingInvocations();
        BoundedNativeToolAgent agent = agent(model, List.of(tool("search_rule_evidence")), audited);

        var result = agent.run(request(scope(), Set.of("search_rule_evidence"), Set.of(), TerminalContract.none()));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.text()).isEqualTo("Cited player guidance");
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(model.conversations.get(1)).extracting(ConversationMessage::role)
                .containsExactly(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL);
        assertThat(model.conversations.get(1).get(3).toolCallId()).isEqualTo("call-1");
        assertThat(audited.invokedTypes).containsExactly(ActivityType.MODEL, ActivityType.TOOL, ActivityType.MODEL);
        assertThat(audited.invokedOperations).anyMatch(operation ->
                operation.matches("nativeTool\\|search_rule_evidence\\|[a-f0-9]{64}"));
    }

    @Test
    void continuesPastTheFormerFixedCallCeilingUntilTheAgentFinishes() {
        QueueModel model = new QueueModel(
                turn(call("call-1", "search_rule_evidence", "{\"query\":\"one\"}")),
                turn(call("call-2", "search_rule_evidence", "{\"query\":\"two\"}")),
                turn(call("call-3", "search_rule_evidence", "{\"query\":\"three\"}")),
                turn(call("call-4", "search_rule_evidence", "{\"query\":\"four\"}")),
                turn(call("call-5", "search_rule_evidence", "{\"query\":\"five\"}")),
                turn(call("call-6", "search_rule_evidence", "{\"query\":\"six\"}")),
                turn(call("call-7", "search_rule_evidence", "{\"query\":\"seven\"}")),
                finalTurn("Evidence collection is complete."));
        BoundedNativeToolAgent agent = agent(
                model, List.of(tool("search_rule_evidence")), new RecordingInvocations());

        var result = agent.run(request(scope(), Set.of("search_rule_evidence"), Set.of(), TerminalContract.none()));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.toolCalls()).isEqualTo(7);
        assertThat(result.iterations()).isEqualTo(8);
    }

    @Test
    void rejectsParallelActionsAndReturnsTheCompleteProtocolErrorBeforeReplacement() {
        QueueModel model = new QueueModel(
                new ModelTurn("", List.of(
                        call("call-1", "search_rule_evidence", "{\"query\":\"victory\"}"),
                        call("call-2", "read_rule_pages", "{\"pageNumbers\":[2]}")), 10, 5),
                turn(call("call-3", "read_rule_pages", "{\"pageNumbers\":[2]}")),
                finalTurn("Verified."));
        RecordingInvocations audited = new RecordingInvocations();
        BoundedNativeToolAgent agent = agent(
                model, List.of(tool("search_rule_evidence"), tool("read_rule_pages")), audited);

        var result = agent.run(request(
                scope(), Set.of("search_rule_evidence", "read_rule_pages"), Set.of(), TerminalContract.none()));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(model.conversations.get(1)).extracting(ConversationMessage::role)
                .containsExactly(
                        MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT,
                        MessageRole.TOOL, MessageRole.TOOL, MessageRole.USER);
        assertThat(model.conversations.get(1).stream()
                        .map(ConversationMessage::content)
                        .collect(java.util.stream.Collectors.joining("\n")))
                .contains("ONE_ACTION_PER_TURN", "victory", "pageNumbers", "read_rule_pages");
        assertThat(audited.recordedOperations).contains("nativeActionProtocol");
    }

    @Test
    void rejectsCompletionUntilARequiredObservationExistsThenLetsTheAgentDecide() {
        QueueModel model = new QueueModel(
                finalTurn("I can answer from memory."),
                turn(call("call-1", "read_rule_pages", "{\"pageNumbers\":[4]}")),
                finalTurn("Verified from the page."));
        BoundedNativeToolAgent agent = agent(
                model, List.of(tool("read_rule_pages")), new RecordingInvocations());

        var result = agent.run(request(
                scope(), Set.of("read_rule_pages"), Set.of("read_rule_pages"), TerminalContract.none()));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.text()).isEqualTo("Verified from the page.");
        assertThat(model.conversations.get(1)).extracting(ConversationMessage::content)
                .anySatisfy(content -> assertThat(content)
                        .contains("I can answer from memory.", "read_rule_pages", "Validation error"));
    }

    @Test
    void letsAPositivePartialRequiredReadProceedToTheTerminalContract() {
        NativeAgentTool partial = new TestTool("read_rule_pages") {
            @Override
            public ToolObservation execute(String argumentsJson, ToolScope scope) {
                return ToolObservation.partial(
                        "PAGE_EVIDENCE_PAGE_FOUND", Map.of("hasMore", true, "nextCursor", "opaque"), 1);
            }
        };
        QueueModel model = new QueueModel(
                turn(call("call-read", partial.name(), "{}")),
                finalTurn(terminal("EVIDENCE_READY")));
        BoundedNativeToolAgent agent = agent(model, List.of(partial), new RecordingInvocations());

        var result = agent.run(request(
                scope(), Set.of(partial.name()), Set.of(partial.name()), TerminalContract.evidenceReview()));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.terminalStatus()).isEqualTo(NativeToolAgent.TerminalStatus.EVIDENCE_READY);
        assertThat(result.observations()).singleElement().satisfies(observation -> {
            assertThat(observation.observation().status())
                    .isEqualTo(NativeAgentTool.ObservationStatus.PARTIAL);
            assertThat(observation.observation().evidenceCount()).isPositive();
        });
    }

    @Test
    void stopsWhenAnUnchangedCompletionAddsNoInformation() {
        NativeAgentTool partial = new TestTool("read_rule_pages") {
            @Override
            public ToolObservation execute(String argumentsJson, ToolScope scope) {
                return ToolObservation.partial("PAGE_NOT_FOUND", Map.of(), 0);
            }
        };
        QueueModel model = new QueueModel(
                turn(call("call-read", "read_rule_pages", "{}")),
                finalTurn("EVIDENCE_READY"),
                finalTurn("EVIDENCE_READY"));
        BoundedNativeToolAgent agent = agent(model, List.of(partial), new RecordingInvocations());

        var result = agent.run(request(
                scope(), Set.of("read_rule_pages"), Set.of("read_rule_pages"), TerminalContract.none()));

        assertThat(result.status()).isEqualTo(RunStatus.FALLBACK);
        assertThat(result.reason()).isEqualTo("COMPLETION_NO_PROGRESS");
        assertThat(result.toolCalls()).isEqualTo(1);
    }

    @Test
    void returnsEveryDistinctInvalidTerminalCandidateWithErrorSchemaAndAllowedIdentities() {
        QueueModel model = new QueueModel(
                finalTurn("EVIDENCE_READY"),
                finalTurn("{\"status\":\"UNKNOWN\"}"),
                finalTurn("{\"status\":\"EVIDENCE_READY\",\"extra\":true}"),
                finalTurn(terminal("EVIDENCE_READY")));
        BoundedNativeToolAgent agent = agent(
                model, List.of(tool("search_rule_evidence")), new RecordingInvocations());

        var result = agent.run(request(
                scope(), Set.of("search_rule_evidence"), Set.of(), TerminalContract.evidenceReview()));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.terminalStatus()).isEqualTo(NativeToolAgent.TerminalStatus.EVIDENCE_READY);
        assertThat(result.iterations()).isEqualTo(4);
        assertThat(model.conversations.get(1)).extracting(ConversationMessage::content)
                .anySatisfy(content -> assertThat(content)
                        .contains("EVIDENCE_READY", "JSON parsing failed", "Original JSON schema",
                                "EVIDENCE_NOT_FOUND"));
    }

    @Test
    void stopsWhenTheSameInvalidTerminalCandidateIsReturnedAgain() {
        QueueModel model = new QueueModel(finalTurn("not-json"), finalTurn("not-json"));
        BoundedNativeToolAgent agent = agent(
                model, List.of(tool("search_rule_evidence")), new RecordingInvocations());

        var result = agent.run(request(
                scope(), Set.of("search_rule_evidence"), Set.of(), TerminalContract.evidenceReview()));

        assertThat(result.status()).isEqualTo(RunStatus.FALLBACK);
        assertThat(result.reason()).isEqualTo("COMPLETION_NO_PROGRESS");
        assertThat(model.requests).isEqualTo(2);
    }

    @Test
    void returnsTypedToolErrorsAndLetsTheAgentReplaceTheWholeAction() {
        NativeAgentTool correcting = new TestTool("search_rule_evidence") {
            @Override
            public ToolObservation execute(String argumentsJson, ToolScope scope) {
                if (argumentsJson.contains("bad")) throw new IllegalArgumentException("invalid");
                return super.execute(argumentsJson, scope);
            }
        };
        QueueModel model = new QueueModel(
                turn(call("call-1", correcting.name(), "{\"query\":\"bad\"}")),
                turn(call("call-2", correcting.name(), "{\"query\":\"corrected\"}")),
                finalTurn("Verified."));
        BoundedNativeToolAgent agent = agent(model, List.of(correcting), new RecordingInvocations());

        var result = agent.run(request(scope(), Set.of(correcting.name()), Set.of(), TerminalContract.none()));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.observations()).extracting(record -> record.observation().code())
                .containsExactly("INVALID_ARGUMENT", "FOUND");
        assertThat(model.conversations.get(1)).extracting(ConversationMessage::content)
                .anySatisfy(content -> assertThat(content)
                        .contains("INVALID_ARGUMENT", "validationError", "invalid", "inputSchema"));
    }

    @Test
    void stopsOnlyAfterARepeatedActionProducesAnIdenticalObservation() {
        NativeAgentTool invalid = new TestTool("search_rule_evidence") {
            @Override
            public ToolObservation execute(String argumentsJson, ToolScope scope) {
                throw new IllegalArgumentException("invalid");
            }
        };
        ModelToolCall repeated = call("call-1", invalid.name(), "{\"limit\":99}");
        QueueModel model = new QueueModel(
                turn(repeated),
                turn(new ModelToolCall("call-2", repeated.name(), repeated.argumentsJson())));
        RecordingInvocations audited = new RecordingInvocations();
        BoundedNativeToolAgent agent = agent(model, List.of(invalid), audited);

        var result = agent.run(request(scope(), Set.of(invalid.name()), Set.of(), TerminalContract.none()));

        assertThat(result.status()).isEqualTo(RunStatus.FALLBACK);
        assertThat(result.reason()).isEqualTo("OBSERVATION_NO_PROGRESS");
        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(audited.recordedOperations).contains("nativeObservationNoProgress|search_rule_evidence");
    }

    @Test
    void diagnosticPersistenceFailureDoesNotDestroyAValidAgentResult() {
        QueueModel model = new QueueModel(
                turn(call("call-1", "search_rule_evidence", "{}")),
                finalTurn("Verified."));
        RecordingInvocations audited = new RecordingInvocations();
        audited.failDiagnostics = true;
        BoundedNativeToolAgent agent = agent(model, List.of(tool("search_rule_evidence")), audited);

        var result = agent.run(request(scope(), Set.of("search_rule_evidence"), Set.of(), TerminalContract.none()));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.text()).isEqualTo("Verified.");
    }

    @Test
    void keepsDeadlineProviderFailureAndCapabilityFailureExplicit() {
        QueueModel expiredModel = new QueueModel(finalTurn("must not run"));
        BoundedNativeToolAgent expiredAgent = agent(
                expiredModel, List.of(tool("search_rule_evidence")), new RecordingInvocations());
        ToolScope expired = new ToolScope(
                "player", UUID.randomUUID(), UUID.randomUUID(), Instant.now().minusSeconds(1));
        var expiredResult = expiredAgent.run(request(
                expired, Set.of("search_rule_evidence"), Set.of(), TerminalContract.none()));
        assertThat(expiredResult.reason()).isEqualTo("TIMEOUT");
        assertThat(expiredModel.requests).isZero();

        NativeToolModel failing = request -> { throw new IllegalStateException("provider unavailable SECRET"); };
        RecordingInvocations failedAudit = new RecordingInvocations();
        var failedResult = agent(
                        failing, List.of(tool("search_rule_evidence")), failedAudit)
                .run(request(scope(), Set.of("search_rule_evidence"), Set.of(), TerminalContract.none()));
        assertThat(failedResult.reason()).isEqualTo("EXECUTION_FAILED");
        assertThat(failedAudit.recordedOperations).contains("nativeToolFallback|EXECUTION_FAILED");
        assertThat(failedAudit.recordedOperations).noneMatch(operation -> operation.contains("SECRET"));

        QueueModel unsupported = new QueueModel(finalTurn("must not run")) {
            @Override
            public boolean supports(Role role, String ownerUsername) {
                return false;
            }
        };
        var unsupportedResult = agent(
                        unsupported, List.of(tool("search_rule_evidence")), new RecordingInvocations())
                .run(request(scope(), Set.of("search_rule_evidence"), Set.of(), TerminalContract.none()));
        assertThat(unsupportedResult.reason()).isEqualTo("MODEL_CAPABILITY_UNAVAILABLE");
        assertThat(unsupported.requests).isZero();
    }

    @Test
    void forwardsVisualToolMediaOnlyOnTheFollowingModelTurn() {
        InspectingMediaModel model = new InspectingMediaModel();
        NativeAgentTool visual = new TestTool("read_rule_page_image") {
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
        RunRequest request = new RunRequest(
                Role.VISUAL, scope(), "Inspect literal appearance only.", "Locate one cited object.",
                "No verified crop.", Set.of(visual.name()), Set.of(), TerminalContract.none());

        var result = agent.run(request);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(model.sawVisualMedia).isTrue();
    }

    @Test
    void reconcilesProviderReportedPromptUsageThatIncludesMedia() {
        QueueModel model = new QueueModel(new ModelTurn("verified", List.of(), 800, 17));
        RecordingInvocations audited = new RecordingInvocations();
        BoundedNativeToolAgent agent = agent(model, List.of(tool("search_rule_evidence")), audited);

        var result = agent.run(request(scope(), Set.of("search_rule_evidence"), Set.of(), TerminalContract.none()));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(audited.invokedInputTokens.getFirst() + audited.invokedOutputTokens.getFirst())
                .isEqualTo(817);
    }

    @Test
    void stopsBeforeToolExecutionWhenTheCurrentRunHasNoObservationEnvelope() {
        ToolScope original = scope();
        java.util.concurrent.atomic.AtomicInteger observedEnvelope = new java.util.concurrent.atomic.AtomicInteger(-1);
        java.util.concurrent.atomic.AtomicInteger toolExecutions = new java.util.concurrent.atomic.AtomicInteger();
        NativeAgentTool probe = new TestTool("search_rule_evidence") {
            @Override
            public ToolObservation execute(String argumentsJson, ToolScope scope) {
                toolExecutions.incrementAndGet();
                observedEnvelope.set(scope.maxObservationTokens());
                return ToolObservation.partial("OBSERVATION_BUDGET_EXHAUSTED", Map.of("hasMore", true), 0);
            }
        };
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        org.mockito.Mockito.when(execution.budget(original.runId())).thenReturn(new AgentExecutionControl.BudgetSnapshot(
                1_000, 0, 1, 900, original.deadlineAt(), null));
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var agent = new BoundedNativeToolAgent(
                new QueueModel(turn(call("call-1", probe.name(), "{}")), finalTurn("stop")),
                new NativeAgentToolRegistry(List.of(probe), mapper, ignored -> true),
                execution,
                new RecordingInvocations(),
                mapper);

        var result = agent.run(request(original, Set.of(probe.name()), Set.of(), TerminalContract.none()));

        assertThat(result.status()).isEqualTo(RunStatus.FALLBACK);
        assertThat(result.reason()).isEqualTo("OBSERVATION_BUDGET_EXHAUSTED");
        assertThat(result.toolCalls()).isZero();
        assertThat(toolExecutions).hasValue(0);
        assertThat(observedEnvelope).hasValue(-1);
    }

    @Test
    void publishesUnicodeAndEscapeHeavyObservationOnlyWhenItsExactCanonicalJsonFits() {
        String escaped = "quoted=\"value\"\\path\n".repeat(80);
        String chinese = "玩家依次执行行动并核对胜利条件。".repeat(24);
        String emoji = "🎲🧭🀄️".repeat(24);
        ToolObservation observation = ToolObservation.success(
                "ESCAPED_EVIDENCE", Map.of(
                        "evidence", List.of(Map.of("excerpt", escaped + chinese + emoji)),
                        "hasMore", true,
                        "nextCursor", NativeEvidenceObservationBudget.PROVISIONAL_CURSOR), 1);
        NativeAgentTool probe = new TestTool("search_rule_evidence") {
            @Override
            public ToolObservation execute(String argumentsJson, ToolScope scope) {
                return observation;
            }
        };
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var registry = new NativeAgentToolRegistry(List.of(probe), mapper, ignored -> true);
        int exactEnvelope = NativeEvidenceObservationBudget.serializedTokens(
                mapper, observation, registry.specification(Role.ANSWER, probe.name()).schemaHash());
        ToolScope exactScope = scope().withMaxObservationTokens(exactEnvelope);
        QueueModel model = new QueueModel(
                turn(call("call-escaped", probe.name(), "{}")), finalTurn("verified"));
        BoundedNativeToolAgent agent = new BoundedNativeToolAgent(
                model, registry, mock(AgentExecutionControl.class), new RecordingInvocations(), mapper);

        var result = agent.run(request(
                exactScope, Set.of(probe.name()), Set.of(), TerminalContract.none()));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        String publishedJson = model.conversations.get(1).stream()
                .filter(message -> message.role() == MessageRole.TOOL)
                .findFirst()
                .orElseThrow()
                .content();
        assertThat(NativeEvidenceObservationBudget.estimateTokens(publishedJson))
                .isEqualTo(exactEnvelope);
        assertThat(publishedJson).contains(chinese, emoji);

        QueueModel tooSmallModel = new QueueModel(turn(call("call-too-large", probe.name(), "{}")));
        BoundedNativeToolAgent tooSmallAgent = new BoundedNativeToolAgent(
                tooSmallModel, registry, mock(AgentExecutionControl.class), new RecordingInvocations(), mapper);

        var rejected = tooSmallAgent.run(request(
                scope().withMaxObservationTokens(exactEnvelope - 1),
                Set.of(probe.name()), Set.of(), TerminalContract.none()));

        assertThat(rejected.status()).isEqualTo(RunStatus.FALLBACK);
        assertThat(rejected.reason()).isEqualTo("OBSERVATION_BUDGET_EXCEEDED");
    }

    @Test
    void estimatesAsciiCompactlyWithoutUndercountingCjkEmojiOrJsonEscapes() {
        String ascii = "a".repeat(40);
        String chinese = "玩家按照规则结算胜利点";
        String emoji = "🎲🧭🀄";
        String escapedJson = "\\\"quoted\\nvalue\\\\path\\\"";

        assertThat(NativeEvidenceObservationBudget.estimateTokens(ascii)).isEqualTo(10);
        assertThat(NativeEvidenceObservationBudget.estimateTokens(chinese))
                .isGreaterThanOrEqualTo(chinese.codePointCount(0, chinese.length()));
        assertThat(NativeEvidenceObservationBudget.estimateTokens(emoji))
                .isGreaterThanOrEqualTo(emoji.codePointCount(0, emoji.length()) * 2);
        assertThat(NativeEvidenceObservationBudget.estimateTokens(escapedJson))
                .isGreaterThan((escapedJson.length() + 3) / 4);
    }

    @Test
    void stopsOnTheFirstZeroEvidenceBudgetContinuationAndKeepsTheUsefulPrefix() {
        AtomicInteger executions = new AtomicInteger();
        NativeAgentTool paged = new TestTool("search_rule_evidence") {
            @Override
            public ToolObservation execute(String argumentsJson, ToolScope scope) {
                return executions.incrementAndGet() == 1
                        ? ToolObservation.partial(
                                "EVIDENCE_PAGE_FOUND", Map.of("hasMore", true, "nextCursor", "cursor-1"), 1)
                        : ToolObservation.partial(
                                "OBSERVATION_BUDGET_EXHAUSTED",
                                Map.of("hasMore", true, "nextCursor", "cursor-1"), 0);
            }
        };
        QueueModel model = new QueueModel(
                turn(call("call-first", paged.name(), "{\"query\":\"setup\"}")),
                turn(call("call-next", paged.name(), "{\"cursor\":\"cursor-1\"}")),
                finalTurn("must not be requested"));
        RecordingInvocations audited = new RecordingInvocations();
        BoundedNativeToolAgent agent = agent(model, List.of(paged), audited);

        var result = agent.run(request(scope(), Set.of(paged.name()), Set.of(), TerminalContract.none()));

        assertThat(result.status()).isEqualTo(RunStatus.FALLBACK);
        assertThat(result.reason()).isEqualTo("OBSERVATION_BUDGET_EXHAUSTED");
        assertThat(result.observations()).extracting(record -> record.observation().code())
                .containsExactly("EVIDENCE_PAGE_FOUND", "OBSERVATION_BUDGET_EXHAUSTED");
        assertThat(result.observations().getFirst().observation().evidenceCount()).isPositive();
        assertThat(executions).hasValue(2);
        assertThat(model.requests).isEqualTo(2);
        assertThat(audited.recordedOperations)
                .contains("nativeObservationNoProgress|search_rule_evidence",
                        "nativeToolFallback|OBSERVATION_BUDGET_EXHAUSTED");
    }

    @Test
    void preservesCancellationAndNeverReplaysCompletedToolSideEffects() {
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        ToolScope cancelledScope = scope();
        doThrow(new AgentExecutionStoppedException(StopReason.CANCELLED))
                .when(execution).assertStepAllowed(cancelledScope.runId(), 1);
        var mapper = JsonMapper.builder().findAndAddModules().build();
        RecordingInvocations cancelledAudit = new RecordingInvocations();
        BoundedNativeToolAgent cancelledAgent = new BoundedNativeToolAgent(
                new QueueModel(finalTurn("must not run")),
                new NativeAgentToolRegistry(List.of(tool("search_rule_evidence")), mapper, ignored -> true),
                execution, cancelledAudit, mapper);
        var cancelled = cancelledAgent.run(request(
                cancelledScope, Set.of("search_rule_evidence"), Set.of(), TerminalContract.none()));
        assertThat(cancelled.reason()).isEqualTo("CANCELLED");
        assertThat(cancelledAudit.recordedOperations).contains("nativeToolFallback|CANCELLED");

        AtomicInteger executions = new AtomicInteger();
        NativeAgentTool sideEffectProbe = new TestTool("search_rule_evidence") {
            @Override
            public ToolObservation execute(String argumentsJson, ToolScope scope) {
                executions.incrementAndGet();
                return super.execute(argumentsJson, scope);
            }
        };
        AuditedAgentInvocations interrupted = new AuditedAgentInvocations() {
            @Override
            public <T> T invoke(
                    UUID runId, ActivityType type, String operation, int inputTokens, String summary,
                    Supplier<T> invocation, ToIntFunction<T> outputTokenEstimator) {
                T result = invocation.get();
                if (type == ActivityType.TOOL) throw new AgentExecutionStoppedException(StopReason.CANCELLED);
                return result;
            }

            @Override
            public void record(
                    UUID runId, ActivityType type, String operation, ActivityOutcome outcome, String summary) {}
        };
        QueueModel model = new QueueModel(turn(call("call-1", sideEffectProbe.name(), "{}")));
        BoundedNativeToolAgent interruptedAgent = new BoundedNativeToolAgent(
                model,
                new NativeAgentToolRegistry(List.of(sideEffectProbe), mapper, ignored -> true),
                mock(AgentExecutionControl.class), interrupted, mapper);
        var interruptedResult = interruptedAgent.run(request(
                scope(), Set.of(sideEffectProbe.name()), Set.of(), TerminalContract.none()));
        assertThat(interruptedResult.reason()).isEqualTo("CANCELLED");
        assertThat(interruptedResult.observations()).isEmpty();
        assertThat(executions).hasValue(1);
        assertThat(model.requests).isEqualTo(1);
    }

    private BoundedNativeToolAgent agent(
            NativeToolModel model, List<NativeAgentTool> tools, RecordingInvocations audited) {
        var mapper = JsonMapper.builder().findAndAddModules().build();
        return new BoundedNativeToolAgent(
                model, new NativeAgentToolRegistry(tools, mapper, ignored -> true),
                mock(AgentExecutionControl.class), audited, mapper);
    }

    private RunRequest request(
            ToolScope scope, Set<String> allowedTools, Set<String> requiredTools, TerminalContract terminalContract) {
        return new RunRequest(
                Role.ANSWER, scope, "Use read tools only when evidence is needed.",
                "Help the player verify one rule.", "Insufficient verified evidence.",
                allowedTools, requiredTools, terminalContract);
    }

    private ToolScope scope() {
        return new ToolScope(
                "player", UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(30));
    }

    private NativeAgentTool tool(String name) {
        return new TestTool(name);
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

    private String terminal(String status) {
        return "{\"status\":\"" + status + "\"}";
    }

    private static class QueueModel implements NativeToolModel {
        private final Deque<ModelTurn> turns;
        private final List<List<ConversationMessage>> conversations = new ArrayList<>();
        private int requests;

        private QueueModel(ModelTurn... turns) {
            this.turns = new ArrayDeque<>(List.of(turns));
        }

        @Override
        public ModelTurn next(ModelRequest request) {
            requests++;
            conversations.add(request.conversation());
            return turns.removeFirst();
        }
    }

    private static final class InspectingMediaModel implements NativeToolModel {
        private int turn;
        private boolean sawVisualMedia;

        @Override
        public ModelTurn next(ModelRequest request) {
            if (++turn == 1) {
                return new ModelTurn(
                        "", List.of(new ModelToolCall(
                                "visual-call", "read_rule_page_image", "{\"evidenceId\":\"ignored\"}")), 10, 5);
            }
            sawVisualMedia = request.conversation().stream().anyMatch(message -> !message.media().isEmpty());
            return new ModelTurn("verified", List.of(), 10, 5);
        }
    }

    private static class TestTool implements NativeAgentTool {
        private final String name;

        private TestTool(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "Read rule evidence"; }
        @Override public String inputSchema() { return "{\"type\":\"object\"}"; }
        @Override public String schemaVersion() { return "1"; }
        @Override public Set<Role> allowedRoles() { return Set.of(Role.ANSWER); }

        @Override
        public ToolObservation execute(String argumentsJson, ToolScope scope) {
            return ToolObservation.success("FOUND", Map.of("query", argumentsJson), 1);
        }
    }

    private static final class RecordingInvocations implements AuditedAgentInvocations {
        private final List<ActivityType> invokedTypes = new ArrayList<>();
        private final List<String> invokedOperations = new ArrayList<>();
        private final List<Integer> invokedInputTokens = new ArrayList<>();
        private final List<Integer> invokedOutputTokens = new ArrayList<>();
        private final List<String> recordedOperations = new ArrayList<>();
        private boolean failDiagnostics;

        @Override
        public <T> T invoke(
                UUID runId, ActivityType type, String operation, int inputTokens, String summary,
                Supplier<T> invocation, ToIntFunction<T> outputTokenEstimator) {
            invokedTypes.add(type);
            invokedOperations.add(operation);
            invokedInputTokens.add(inputTokens);
            T result = invocation.get();
            invokedOutputTokens.add(outputTokenEstimator.applyAsInt(result));
            return result;
        }

        @Override
        public void record(
                UUID runId, ActivityType type, String operation, ActivityOutcome outcome, String summary) {
            if (failDiagnostics) throw new IllegalStateException("diagnostic store unavailable");
            recordedOperations.add(operation);
        }
    }
}
