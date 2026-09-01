package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelCallStarted;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolCall;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolArgumentValidation;
import com.rulepilot.agenttrace.AgentTraceEvent.UserTurn;
import com.rulepilot.agenttrace.AgentTraceEvent.Publication;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.BudgetSnapshot;
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
import com.rulepilot.assistant.NativeToolAgent;
import com.rulepilot.assistant.NativeToolAgent.RunRequest;
import com.rulepilot.assistant.NativeToolAgent.RunStatus;
import com.rulepilot.assistant.NativeToolAgent.TerminalContract;
import com.rulepilot.assistant.NativeToolModel;
import com.rulepilot.assistant.NativeToolModel.ModelToolCall;
import com.rulepilot.assistant.NativeToolModel.ModelTurn;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class BoundedNativeToolAgentTest {

    @Test
    void capturesProviderNeutralRawTurnsAndTypedToolExchangeWithoutChangingTheResult() {
        QueueModel model = new QueueModel(
                turn(call("call-1", "search_rule_evidence", "{\"query\":\"setup\"}")),
                finalTurn("Cited player guidance"));
        BoundedNativeToolAgent agent = agent(
                model, List.of(successTool("search_rule_evidence")), new RecordingInvocations());
        RecordingCapture capture = new RecordingCapture(false);

        var result = agent.run(request(scope(), 4), capture);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.text()).isEqualTo("Cited player guidance");
        assertThat(capture.modelStarts).hasSize(2);
        assertThat(capture.modelTurns).hasSize(2);
        assertThat(capture.modelTurns.get(0).toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("search_rule_evidence");
            assertThat(call.argumentsJson()).isEqualTo("{\"query\":\"setup\"}");
        });
        assertThat(capture.modelTurns.get(1).assistantText()).isEqualTo("Cited player guidance");
        assertThat(capture.toolCalls)
                .extracting(ToolCall::validation)
                .containsExactly(ToolArgumentValidation.ACCEPTED);
        assertThat(capture.toolCalls.getLast()).satisfies(call -> {
            assertThat(call.toolName()).isEqualTo("search_rule_evidence");
            assertThat(call.rawArgumentsJson()).isEqualTo("{\"query\":\"setup\"}");
            assertThat(call.canonicalArgumentsJson()).isEqualTo("{\"query\":\"setup\"}");
        });
        assertThat(capture.toolObservations).singleElement().satisfies(observation -> {
            assertThat(observation.toolName()).isEqualTo("search_rule_evidence");
            assertThat(observation.modelVisibleObservationJson()).contains("FOUND");
            assertThat(observation.evidenceCount()).isEqualTo(1);
        });
    }

    @Test
    void captureFailuresRemainFailOpen() {
        QueueModel model = new QueueModel(
                turn(call("call-1", "search_rule_evidence", "{}")),
                finalTurn("still published"));
        BoundedNativeToolAgent agent = agent(
                model, List.of(successTool("search_rule_evidence")), new RecordingInvocations());

        var result = agent.run(request(scope(), 4), new RecordingCapture(true));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.text()).isEqualTo("still published");
    }

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
    void advertisesOnlyTheRequestToolPortfolio() {
        java.util.concurrent.atomic.AtomicReference<List<String>> advertised = new java.util.concurrent.atomic.AtomicReference<>();
        NativeToolModel model = request -> {
            advertised.set(request.tools().stream().map(NativeToolModel.ToolSpec::name).toList());
            return finalTurn("READY");
        };
        BoundedNativeToolAgent agent = agent(
                model,
                List.of(successTool("search_rule_evidence"), successTool("read_rule_pages")),
                new RecordingInvocations());
        RunRequest request = new RunRequest(
                Role.ANSWER,
                scope(),
                "Use the smallest useful tool portfolio.",
                "Find one missing rule.",
                "Insufficient verified evidence.",
                3,
                256,
                Set.of("search_rule_evidence"),
                Set.of(),
                2);

        var result = agent.run(request);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(advertised.get()).containsExactly("search_rule_evidence");
    }

    @Test
    void stopsTheLoopAtTheRequestToolCallCap() {
        QueueModel model = new QueueModel(
                turn(call("call-1", "search_rule_evidence", "{\"query\":\"ending\"}")),
                turn(call("call-2", "read_rule_pages", "{\"pageNumbers\":[9]}")));
        BoundedNativeToolAgent agent = agent(
                model,
                List.of(successTool("search_rule_evidence"), successTool("read_rule_pages")),
                new RecordingInvocations());
        RunRequest request = new RunRequest(
                Role.ANSWER,
                scope(),
                "Use bounded read tools.",
                "Find one missing rule.",
                "Insufficient verified evidence.",
                4,
                256,
                Set.of("search_rule_evidence", "read_rule_pages"),
                Set.of("search_rule_evidence", "read_rule_pages"),
                1);

        var result = agent.run(request);

        assertThat(result.status()).isEqualTo(RunStatus.FALLBACK);
        assertThat(result.reason()).isEqualTo("TOOL_CALL_LIMIT");
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.observations()).extracting(observation -> observation.toolName())
                .containsExactly("search_rule_evidence");
    }

    @Test
    void completesAtTheToolCapWhenEveryRequiredObservationWasCollected() {
        QueueModel model = new QueueModel(
                turn(call("call-1", "search_rule_evidence", "{\"query\":\"ending\"}")),
                turn(call("call-2", "read_rule_pages", "{\"pageNumbers\":[9]}")));
        BoundedNativeToolAgent agent = agent(
                model,
                List.of(successTool("search_rule_evidence"), successTool("read_rule_pages")),
                new RecordingInvocations());
        RunRequest request = new RunRequest(
                Role.ANSWER,
                scope(),
                "Use bounded read tools.",
                "Find and confirm one missing rule.",
                "Insufficient verified evidence.",
                4,
                256,
                Set.of("search_rule_evidence", "read_rule_pages"),
                Set.of("search_rule_evidence", "read_rule_pages"),
                2);

        var result = agent.run(request);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.reason()).isEqualTo("REQUIRED_EVIDENCE_COLLECTED");
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(result.observations()).extracting(observation -> observation.toolName())
                .containsExactly("search_rule_evidence", "read_rule_pages");
    }

    @Test
    void reservesTheLastToolCallForARequiredConfirmationInsteadOfAdvertisingMoreSearches() {
        AtomicInteger turn = new AtomicInteger();
        List<List<String>> advertised = new ArrayList<>();
        NativeToolModel model = request -> {
            advertised.add(request.tools().stream().map(NativeToolModel.ToolSpec::name).toList());
            return switch (turn.incrementAndGet()) {
                case 1 -> new ModelTurn("", List.of(
                        call("call-search-1", "search_rule_evidence", "{\"query\":\"victory\"}"),
                        call("call-search-2", "search_rule_evidence", "{\"query\":\"winning\"}")), 10, 5);
                default -> turn(call("call-read", "read_rule_pages", "{\"pageNumbers\":[2]}"));
            };
        };
        BoundedNativeToolAgent agent = agent(
                model,
                List.of(successTool("search_rule_evidence"), successTool("read_rule_pages")),
                new RecordingInvocations());
        RunRequest request = new RunRequest(
                Role.ANSWER,
                scope(),
                "Search, then confirm the canonical page.",
                "Find the complete source-backed answer.",
                "Insufficient verified evidence.",
                4,
                256,
                Set.of("search_rule_evidence", "read_rule_pages"),
                Set.of("read_rule_pages"),
                3);

        var result = agent.run(request);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.toolCalls()).isEqualTo(3);
        assertThat(advertised).hasSize(2);
        assertThat(advertised.get(0)).containsExactlyInAnyOrder("search_rule_evidence", "read_rule_pages");
        assertThat(advertised.get(1)).containsExactly("read_rule_pages");
    }

    @Test
    void preservesARequiredReadWhenOneParallelSearchTurnWouldOtherwiseConsumeTheBudget() {
        AtomicInteger turn = new AtomicInteger();
        NativeToolModel model = request -> switch (turn.incrementAndGet()) {
            case 1 -> new ModelTurn("", List.of(
                    call("call-search-1", "search_rule_evidence", "{\"query\":\"advice\"}"),
                    call("call-search-2", "search_rule_evidence", "{\"query\":\"warning\"}")), 10, 5);
            case 2 -> new ModelTurn("", List.of(
                    call("call-search-3", "search_rule_evidence", "{\"query\":\"strategy\"}"),
                    call("call-search-4", "search_rule_evidence", "{\"query\":\"tip\"}")), 10, 5);
            case 3 -> turn(call("call-read", "read_rule_pages", "{\"pageNumbers\":[22]}"));
            default -> finalTurn(terminal("EVIDENCE_READY"));
        };
        RecordingInvocations audited = new RecordingInvocations();
        BoundedNativeToolAgent agent = agent(
                model,
                List.of(successTool("search_rule_evidence"), successTool("read_rule_pages")),
                audited);
        RunRequest request = new RunRequest(
                Role.ANSWER,
                scope(),
                "Search, read, then assess source-authored advice.",
                "Find source-authored advice.",
                "EVIDENCE_NOT_FOUND",
                4,
                256,
                Set.of("search_rule_evidence", "read_rule_pages"),
                Set.of("read_rule_pages"),
                4,
                TerminalContract.evidenceReview(),
                Map.of("read_rule_pages", 1),
                true);

        var result = agent.run(request);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.terminalStatus()).isEqualTo(NativeToolAgent.TerminalStatus.EVIDENCE_READY);
        assertThat(result.toolCalls()).isEqualTo(4);
        assertThat(result.observations()).extracting(observation -> observation.toolName())
                .containsExactly(
                        "search_rule_evidence",
                        "search_rule_evidence",
                        "search_rule_evidence",
                        "read_rule_pages");
        assertThat(audited.recordedOperations).contains("nativeRequiredToolBudgetReservation");
    }

    @Test
    void givesOneBoundedCorrectionWhenRequiredOnlyModeReceivesAnotherSearchRequest() {
        AtomicInteger turn = new AtomicInteger();
        NativeToolModel model = request -> switch (turn.incrementAndGet()) {
            case 1 -> new ModelTurn("", List.of(
                    call("call-search-1", "search_rule_evidence", "{\"query\":\"victory\"}"),
                    call("call-search-2", "search_rule_evidence", "{\"query\":\"winning\"}")), 10, 5);
            case 2 -> turn(call("call-search-rejected", "search_rule_evidence", "{\"query\":\"score\"}"));
            default -> turn(call("call-read", "read_rule_pages", "{\"pageNumbers\":[2]}"));
        };
        RecordingInvocations audited = new RecordingInvocations();
        BoundedNativeToolAgent agent = agent(
                model,
                List.of(successTool("search_rule_evidence"), successTool("read_rule_pages")),
                audited);
        RunRequest request = new RunRequest(
                Role.ANSWER,
                scope(),
                "Search, then confirm the canonical page.",
                "Find the complete source-backed answer.",
                "Insufficient verified evidence.",
                4,
                256,
                Set.of("search_rule_evidence", "read_rule_pages"),
                Set.of("read_rule_pages"),
                3);

        var result = agent.run(request);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.toolCalls()).isEqualTo(3);
        assertThat(result.observations()).extracting(observation -> observation.toolName())
                .containsExactly("search_rule_evidence", "search_rule_evidence", "read_rule_pages");
        assertThat(audited.recordedOperations).contains("nativeRequiredToolContractRepair");
    }

    @Test
    void partialObservationDoesNotSatisfyARequiredConfirmation() {
        QueueModel model = new QueueModel(
                turn(call("call-read", "read_rule_pages", "{}")),
                finalTurn("EVIDENCE_READY"));
        NativeAgentTool partial = new TestTool("read_rule_pages", false, false) {
            @Override
            public ToolObservation execute(String argumentsJson, ToolScope scope) {
                return ToolObservation.partial("PAGE_NOT_FOUND", Map.of(), 0);
            }
        };
        BoundedNativeToolAgent agent = agent(model, List.of(partial), new RecordingInvocations());
        RunRequest request = new RunRequest(
                Role.ANSWER,
                scope(),
                "Confirm one canonical page.",
                "Read the exact source page.",
                "Insufficient verified evidence.",
                2,
                256,
                Set.of("read_rule_pages"),
                Set.of("read_rule_pages"),
                2);

        var result = agent.run(request);

        assertThat(result.status()).isEqualTo(RunStatus.FALLBACK);
        assertThat(result.reason()).isEqualTo("COMPLETION_REQUIREMENT_UNMET");
    }

    @Test
    void canRequireAConfirmationReadAndThenASeparateBoundedAssessment() {
        QueueModel model = new QueueModel(
                turn(call("call-read", "read_rule_pages", "{}")),
                finalTurn(terminal("EVIDENCE_NOT_FOUND")));
        BoundedNativeToolAgent agent = agent(
                model, List.of(successTool("read_rule_pages")), new RecordingInvocations());
        RunRequest request = new RunRequest(
                Role.ANSWER,
                scope(),
                "Inspect evidence and return one terminal assessment.",
                "Determine whether the required source kind exists.",
                "EVIDENCE_NOT_FOUND",
                2,
                256,
                Set.of("read_rule_pages"),
                Set.of("read_rule_pages"),
                1,
                TerminalContract.evidenceReview(),
                Map.of("read_rule_pages", 1),
                true);

        var result = agent.run(request);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.terminalStatus()).isEqualTo(NativeToolAgent.TerminalStatus.EVIDENCE_NOT_FOUND);
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.toolCalls()).isEqualTo(1);
    }

    @Test
    void allowsOneToolFreeAssessmentWhenTheRequiredReadOccursOnTheLastAcquisitionTurn() {
        QueueModel model = new QueueModel(
                turn(call("call-search-1", "search_rule_evidence", "{\"query\":\"advice\"}")),
                turn(call("call-search-2", "search_rule_evidence", "{\"query\":\"caution\"}")),
                turn(call("call-search-3", "search_rule_evidence", "{\"query\":\"tip\"}")),
                turn(call("call-read", "read_rule_pages", "{\"pageNumbers\":[22]}")),
                finalTurn(terminal("EVIDENCE_READY")));
        BoundedNativeToolAgent agent = agent(
                model,
                List.of(successTool("search_rule_evidence"), successTool("read_rule_pages")),
                new RecordingInvocations());
        RunRequest request = new RunRequest(
                Role.ANSWER,
                scope(),
                "Read and then assess source-authored advice.",
                "Find source-authored guidance.",
                "EVIDENCE_NOT_FOUND",
                4,
                256,
                Set.of("search_rule_evidence", "read_rule_pages"),
                Set.of("read_rule_pages"),
                4,
                TerminalContract.evidenceReview(),
                Map.of("read_rule_pages", 1),
                true);

        var result = agent.run(request);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.terminalStatus()).isEqualTo(NativeToolAgent.TerminalStatus.EVIDENCE_READY);
        assertThat(result.iterations()).isEqualTo(5);
        assertThat(result.toolCalls()).isEqualTo(4);
    }

    @Test
    void canContinueSearchingAfterARequiredReadWhenCoverageNeedsExplicitCertification() {
        QueueModel model = new QueueModel(
                turn(call("call-search-1", "search_rule_evidence", "{\"query\":\"alternative victory\"}")),
                turn(call("call-read-1", "read_rule_pages", "{\"pageNumbers\":[21]}")),
                turn(call("call-search-2", "search_rule_evidence", "{\"query\":\"standard victory\"}")),
                turn(call("call-read-2", "read_rule_pages", "{\"pageNumbers\":[2]}")),
                finalTurn(terminal("EVIDENCE_READY")));
        BoundedNativeToolAgent agent = agent(
                model,
                List.of(successTool("search_rule_evidence"), successTool("read_rule_pages")),
                new RecordingInvocations());
        RunRequest request = new RunRequest(
                Role.ANSWER,
                scope(),
                "Certify a complete source list.",
                "Find every source-defined route.",
                "EVIDENCE_NOT_FOUND",
                5,
                256,
                Set.of("search_rule_evidence", "read_rule_pages"),
                Set.of("read_rule_pages"),
                4,
                TerminalContract.evidenceReview(),
                Map.of(),
                false);

        var result = agent.run(request);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.terminalStatus()).isEqualTo(NativeToolAgent.TerminalStatus.EVIDENCE_READY);
        assertThat(result.iterations()).isEqualTo(5);
        assertThat(result.toolCalls()).isEqualTo(4);
        assertThat(result.observations()).extracting(observation -> observation.toolName())
                .containsExactly(
                        "search_rule_evidence",
                        "read_rule_pages",
                        "search_rule_evidence",
                        "read_rule_pages");
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
        assertThat(result.iterations()).isEqualTo(3);
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
    void rejectsOneEmptyEarlyStopAndLetsTheAgentContinueWithinItsExistingBudget() {
        QueueModel model = new QueueModel(
                new ModelTurn("", List.of(), 10, 0),
                finalTurn("EVIDENCE_READY"));
        RecordingInvocations audited = new RecordingInvocations();
        BoundedNativeToolAgent agent = agent(
                model,
                List.of(successTool("search_rule_evidence")),
                audited);

        var result = agent.run(request(scope(), 2));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.text()).isEqualTo("EVIDENCE_READY");
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(model.requests).isEqualTo(2);
        assertThat(audited.recordedOperations).contains("nativeEmptyCompletion");
    }

    @Test
    void rejectsProseAndAcceptsOnlyTheStrictJsonTerminalContract() {
        QueueModel model = new QueueModel(
                finalTurn("I think the evidence is ready."),
                finalTurn(terminal("EVIDENCE_READY")));
        RecordingInvocations audited = new RecordingInvocations();
        BoundedNativeToolAgent agent = agent(
                model,
                List.of(successTool("search_rule_evidence")),
                audited);
        RunRequest request = new RunRequest(
                Role.ANSWER,
                scope(),
                "Use the exact terminal protocol.",
                "Verify the compound request.",
                "Insufficient verified evidence.",
                2,
                256,
                Set.of("search_rule_evidence"),
                Set.of(),
                2,
                TerminalContract.exact(NativeToolAgent.TerminalStatus.EVIDENCE_READY),
                Map.of(),
                true);

        var result = agent.run(request);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.text()).isEqualTo(terminal("EVIDENCE_READY"));
        assertThat(result.terminalStatus()).isEqualTo(NativeToolAgent.TerminalStatus.EVIDENCE_READY);
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(audited.recordedOperations).contains("nativeCompletionProtocol");
    }

    @Test
    void performsAtMostOneTerminalSchemaRepair() {
        QueueModel model = new QueueModel(
                finalTurn("EVIDENCE_READY"),
                finalTurn("{\"status\":\"EVIDENCE_READY\",\"explanation\":\"extra\"}"),
                finalTurn(terminal("EVIDENCE_READY")));
        BoundedNativeToolAgent agent = agent(
                model,
                List.of(successTool("search_rule_evidence")),
                new RecordingInvocations());
        RunRequest request = new RunRequest(
                Role.ANSWER,
                scope(),
                "Use the structured terminal contract.",
                "Verify the request.",
                "Insufficient verified evidence.",
                4,
                256,
                Set.of("search_rule_evidence"),
                Set.of(),
                2,
                TerminalContract.evidenceReview(),
                Map.of(),
                true);

        var result = agent.run(request);

        assertThat(result.status()).isEqualTo(RunStatus.FALLBACK);
        assertThat(result.reason()).isEqualTo("COMPLETION_PROTOCOL_REJECTED");
        assertThat(model.requests).isEqualTo(2);
    }

    @Test
    void feedsOneInvalidArgumentObservationBackWithoutPublishingItAsEvidence() {
        NativeAgentTool invalid = failingTool("search_rule_evidence", true);
        QueueModel model = new QueueModel(
                turn(call("call-1", "search_rule_evidence", "{\"limit\":99}")),
                finalTurn("I cannot verify that request."));
        BoundedNativeToolAgent agent = agent(model, List.of(invalid), new RecordingInvocations());

        RecordingCapture capture = new RecordingCapture(false);
        var result = agent.run(request(scope(), 3), capture);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.observations()).singleElement().satisfies(observation -> {
            assertThat(observation.observation().status()).isEqualTo(NativeAgentTool.ObservationStatus.ERROR);
            assertThat(observation.observation().code()).isEqualTo("INVALID_ARGUMENT");
            assertThat(observation.observation().evidenceCount()).isZero();
        });
        assertThat(capture.toolCalls)
                .extracting(ToolCall::validation)
                .containsExactly(ToolArgumentValidation.REJECTED);
        assertThat(capture.toolCalls.getLast().canonicalArgumentsJson()).isEqualTo("{\"limit\":99}");
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
        RecordingCapture capture = new RecordingCapture(false);

        var result = agent.run(request(scope(), 3), capture);

        assertThat(result.status()).isEqualTo(RunStatus.FALLBACK);
        assertThat(result.reason()).isEqualTo("EXECUTION_FAILED");
        assertThat(result.text()).isEqualTo("Insufficient verified evidence.");
        assertThat(capture.modelStarts).singleElement().satisfies(start ->
                assertThat(capture.failures).anySatisfy(failure -> {
                    assertThat(failure.signal()).isEqualTo(com.rulepilot.agenttrace.AgentTraceEvent.LifecycleSignal.FAILURE);
                    assertThat(failure.context().operationId()).isEqualTo(start.context().operationId());
                    assertThat(failure.code()).isEqualTo("EXECUTION_FAILED");
                }));
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
    void hidesToolsAfterTheConfiguredSuccessfulObservationAndLetsTheAgentComposeItsFinalResponse() {
        AtomicInteger turn = new AtomicInteger();
        List<List<String>> advertised = new ArrayList<>();
        NativeToolModel model = request -> {
            advertised.add(request.tools().stream().map(NativeToolModel.ToolSpec::name).toList());
            return switch (turn.incrementAndGet()) {
                case 1 -> turn(call("call-page", "search_rule_evidence", "{}"));
                case 2 -> turn(call("call-exact", "read_rule_pages", "{}"));
                default -> finalTurn("{\"regions\":[{\"label\":\"已核对区域\"}]}");
            };
        };
        BoundedNativeToolAgent agent = agent(
                model,
                List.of(successTool("search_rule_evidence"), successTool("read_rule_pages")),
                new RecordingInvocations());
        RunRequest request = new RunRequest(
                Role.ANSWER,
                scope(),
                "Use observed evidence before composing the final structure.",
                "Inspect one bounded source and then finish.",
                "No verified result.",
                4,
                256,
                Set.of("search_rule_evidence", "read_rule_pages"),
                Set.of(),
                4,
                "",
                Map.of("read_rule_pages", 1));

        var result = agent.run(request);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.text()).contains("已核对区域");
        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(advertised).hasSize(3);
        assertThat(advertised.get(2)).isEmpty();
    }

    @Test
    void keepsToolsAvailableWhenTheConfiguredObservationIsOnlyPartial() {
        List<List<String>> advertised = new ArrayList<>();
        AtomicInteger turn = new AtomicInteger();
        NativeToolModel model = request -> {
            advertised.add(request.tools().stream().map(NativeToolModel.ToolSpec::name).toList());
            return turn.incrementAndGet() == 1
                    ? turn(call("call-partial", "read_rule_pages", "{}"))
                    : finalTurn("No complete source was found.");
        };
        NativeAgentTool partial = new TestTool("read_rule_pages", false, false) {
            @Override
            public ToolObservation execute(String argumentsJson, ToolScope scope) {
                return ToolObservation.partial("PAGE_NOT_FOUND", Map.of(), 0);
            }
        };
        BoundedNativeToolAgent agent = agent(model, List.of(partial), new RecordingInvocations());
        RunRequest request = new RunRequest(
                Role.ANSWER,
                scope(),
                "Use only successful observations.",
                "Inspect one bounded source.",
                "No verified result.",
                3,
                256,
                Set.of("read_rule_pages"),
                Set.of(),
                3,
                "",
                Map.of("read_rule_pages", 1));

        var result = agent.run(request);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(advertised).hasSize(2);
        assertThat(advertised.get(1)).containsExactly("read_rule_pages");
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
    void interruptsABlockingReadToolAtTheRunDeadlineWithoutPublishingItsObservation() throws Exception {
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        UUID runId = UUID.randomUUID();
        Instant deadlineAt = Instant.now().plusMillis(250);
        ToolScope scope = new ToolScope("player", UUID.randomUUID(), runId, deadlineAt);
        when(execution.budget(runId)).thenReturn(new BudgetSnapshot(
                40, 24, 16, 24_000, 0, 0, 0, deadlineAt, null));
        CountDownLatch interrupted = new CountDownLatch(1);
        NativeAgentTool blockingTool = new TestTool("search_rule_evidence", false, false) {
            @Override
            public ToolObservation execute(String argumentsJson, ToolScope ignoredScope) {
                try {
                    Thread.sleep(Duration.ofMinutes(5));
                } catch (InterruptedException stopped) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return super.execute(argumentsJson, ignoredScope);
            }
        };
        var mapper = JsonMapper.builder().findAndAddModules().build();

        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentInvocationDeadline guard = new AgentInvocationDeadline(
                    execution, calls, Duration.ofMillis(20));
            BoundedNativeToolAgent agent = new BoundedNativeToolAgent(
                    new QueueModel(turn(call("call-1", blockingTool.name(), "{}"))),
                    new NativeAgentToolRegistry(List.of(blockingTool), mapper, ignored -> true),
                    execution,
                    new RecordingInvocations(),
                    mapper,
                    guard);

            var result = agent.run(request(scope, 3));

            assertThat(result.status()).isEqualTo(RunStatus.FALLBACK);
            assertThat(result.reason()).isEqualTo("TIMEOUT");
            assertThat(result.toolCalls()).isZero();
            assertThat(result.observations()).isEmpty();
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        }
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

    private String terminal(String status) {
        return "{\"status\":\"" + status + "\"}";
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

    private static final class RecordingCapture implements CaptureHandle {
        private final boolean fail;
        private final List<ModelCallStarted> modelStarts = new ArrayList<>();
        private final List<com.rulepilot.agenttrace.AgentTraceEvent.ModelTurn> modelTurns = new ArrayList<>();
        private final List<ToolCall> toolCalls = new ArrayList<>();
        private final List<com.rulepilot.agenttrace.AgentTraceEvent.ToolObservation> toolObservations =
                new ArrayList<>();
        private final List<BindingOrFailure> failures = new ArrayList<>();

        private RecordingCapture(boolean fail) {
            this.fail = fail;
        }

        @Override public boolean enabled() { return true; }
        @Override public Optional<UUID> traceId() { return Optional.of(UUID.randomUUID()); }
        @Override public void userTurn(UserTurn event) { maybeFail(); }
        @Override public void modelCallStarted(ModelCallStarted event) { maybeFail(); modelStarts.add(event); }
        @Override public void modelTurn(com.rulepilot.agenttrace.AgentTraceEvent.ModelTurn event) {
            maybeFail();
            modelTurns.add(event);
        }
        @Override public void toolCall(ToolCall event) { maybeFail(); toolCalls.add(event); }
        @Override public void toolObservation(
                com.rulepilot.agenttrace.AgentTraceEvent.ToolObservation event) {
            maybeFail();
            toolObservations.add(event);
        }
        @Override public void publication(Publication event) { maybeFail(); }
        @Override public void bindingOrFailure(BindingOrFailure event) { maybeFail(); failures.add(event); }
        @Override public boolean bind(com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef resource) {
            maybeFail();
            return true;
        }

        private void maybeFail() {
            if (fail) throw new IllegalStateException("private trace unavailable");
        }
    }
}
