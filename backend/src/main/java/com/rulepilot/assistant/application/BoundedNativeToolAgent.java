package com.rulepilot.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rulepilot.agenttrace.AgentTraceEvent;
import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.JourneyStage;
import com.rulepilot.agenttrace.AgentTraceEvent.LifecycleSignal;
import com.rulepilot.agenttrace.AgentTraceEvent.MediaDescriptor;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelCallStarted;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceType;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolArgumentValidation;
import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.NativeAgentTool.ObservationStatus;
import com.rulepilot.assistant.NativeToolAgent;
import com.rulepilot.assistant.NativeToolModel;
import com.rulepilot.assistant.PrivateAgentTraceCapture;
import com.rulepilot.assistant.NativeToolModel.ConversationMessage;
import com.rulepilot.assistant.NativeToolModel.ModelRequest;
import com.rulepilot.assistant.NativeToolModel.ModelTurn;
import com.rulepilot.assistant.NativeToolModel.ModelToolCall;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class BoundedNativeToolAgent implements NativeToolAgent {

    private static final int MAX_CONTEXT_CHARACTERS = 32_000;

    private final NativeToolModel model;
    private final NativeAgentToolRegistry tools;
    private final AgentExecutionControl execution;
    private final AuditedAgentInvocations audited;
    private final ObjectMapper objectMapper;
    private final AgentInvocationDeadline deadline;

    @Autowired
    public BoundedNativeToolAgent(
            NativeToolModel model,
            NativeAgentToolRegistry tools,
            AgentExecutionControl execution,
            AuditedAgentInvocations audited,
            ObjectMapper objectMapper,
            AgentInvocationDeadline deadline) {
        this.model = model;
        this.tools = tools;
        this.execution = execution;
        this.audited = audited;
        this.objectMapper = objectMapper;
        this.deadline = deadline;
    }

    public BoundedNativeToolAgent(
            NativeToolModel model,
            NativeAgentToolRegistry tools,
            AgentExecutionControl execution,
            AuditedAgentInvocations audited,
            ObjectMapper objectMapper) {
        this(model, tools, execution, audited, objectMapper, AgentInvocationDeadline.unbounded());
    }

    @Override
    public RunResult run(RunRequest request) {
        return run(request, CaptureHandle.noop());
    }

    @Override
    public RunResult run(RunRequest request, CaptureHandle capture) {
        CaptureHandle trace = PrivateAgentTraceCapture.failOpen(capture);
        if (!model.supports(request.role(), request.scope().ownerUsername())) {
            return fallback(trace, request, "MODEL_CAPABILITY_UNAVAILABLE", 0, 0, List.of());
        }
        NativeAgentConversation conversation = new NativeAgentConversation(
                request.systemPrompt(), request.playerRequest(), MAX_CONTEXT_CHARACTERS);
        List<ObservationRecord> observations = new java.util.ArrayList<>();
        Map<String, Integer> failedCallCounts = new HashMap<>();
        int toolCalls = 0;
        boolean finalResponseOnly = false;
        boolean requiredOnlyInstructionAdded = false;
        boolean terminalProtocolRepairAttempted = false;

        for (int iteration = 1; iteration <= request.maxIterations() + 1; iteration++) {
            // A tool read on the last acquisition turn still needs one tool-free protocol decision. This extra turn
            // cannot expand the evidence search because the registry advertises no tools in final-response mode.
            if (iteration > request.maxIterations() && !finalResponseOnly) break;
            if (Instant.now().isAfter(request.scope().deadlineAt())) {
                return fallback(trace, request, "TIMEOUT", iteration - 1, toolCalls, observations);
            }
            try {
                execution.assertStepAllowed(request.scope().runId(), iteration);
            } catch (RuntimeException exception) {
                return fallback(trace, request, stopReason(exception), iteration - 1, toolCalls, observations);
            }

            List<String> missingCompletionTools = missingCompletionTools(request, observations);
            boolean reserveRemainingCallsForRequiredTools = !finalResponseOnly
                    && !missingCompletionTools.isEmpty()
                    && (iteration == request.maxIterations()
                            || request.maxToolCalls() - toolCalls <= missingCompletionTools.size());
            Set<String> toolsAllowedThisTurn = reserveRemainingCallsForRequiredTools
                    ? Set.copyOf(missingCompletionTools)
                    : request.allowedTools();
            if (reserveRemainingCallsForRequiredTools && !requiredOnlyInstructionAdded && iteration > 1) {
                conversation.appendApplicationInstruction(
                        "The remaining observation budget is reserved for the required confirmation tool(s): "
                                + String.join(", ", missingCompletionTools)
                                + ". Use only those tools with locators already present in prior observations. "
                                + "If no prior candidate can satisfy the requirement, stop without inventing one.");
                requiredOnlyInstructionAdded = true;
            }

            ModelTurn turn;
            List<com.rulepilot.assistant.NativeToolModel.ToolSpec> advertisedTools =
                    finalResponseOnly ? List.of() : tools.specifications(request.role(), toolsAllowedThisTurn);
            if (!finalResponseOnly
                    && !toolsAllowedThisTurn.isEmpty()
                    && advertisedTools.size() != toolsAllowedThisTurn.size()) {
                return fallback(trace, request, "TOOL_ALLOWLIST_UNAVAILABLE", iteration - 1, toolCalls, observations);
            }
            UUID modelOperationId = UUID.randomUUID();
            TraceEventContext modelContext = context(request, modelOperationId, request.scope().runId());
            try {
                List<ConversationMessage> messages = conversation.messages();
                int currentIteration = iteration;
                int estimatedInputTokens = estimateTokens(messages.stream()
                        .map(ConversationMessage::content)
                        .reduce("", (left, right) -> left + right));
                capture(trace, () -> trace.modelCallStarted(new ModelCallStarted(
                                modelContext,
                                model.providerId(request.role(), request.scope().ownerUsername()),
                                model.modelId(request.role(), request.scope().ownerUsername()),
                                currentIteration,
                                "native-tool-loop-v1",
                                "native-model-turn-v1",
                                "",
                                estimatedInputTokens,
                                request.maxOutputTokens())));
                turn = audited.invoke(
                        request.scope().runId(),
                        ActivityType.MODEL,
                        "nativeModelTurn|" + currentIteration,
                        estimatedInputTokens,
                        "native model turn completed",
                        () -> deadline.invoke(
                                request.scope().runId(),
                                request.scope().deadlineAt(),
                                () -> model.next(new ModelRequest(
                                        request.role(),
                                        request.scope(),
                                        messages,
                                        advertisedTools,
                                        request.maxOutputTokens()))),
                        ModelTurn::completionTokens);
                ModelTurn capturedTurn = turn;
                capture(trace, () -> trace.modelTurn(new AgentTraceEvent.ModelTurn(
                                nextEvent(modelContext),
                                model.providerId(request.role(), request.scope().ownerUsername()),
                                model.modelId(request.role(), request.scope().ownerUsername()),
                                currentIteration,
                                capturedTurn.text(),
                                capturedTurn.toolCalls().stream()
                                        .map(call -> new AgentTraceEvent.ModelToolCall(
                                                call.id(), call.name(), call.argumentsJson()))
                                        .toList(),
                                capturedTurn.toolCalls().isEmpty() ? "STOP" : "TOOL_CALLS",
                                capturedTurn.promptTokens(),
                                capturedTurn.completionTokens(),
                                capturedTurn.text().isBlank() && capturedTurn.toolCalls().isEmpty())));
            } catch (NativeAgentConversation.ContextLimitException exception) {
                captureModelFailure(trace, modelContext, "CONTEXT_LIMIT");
                return fallback(trace, request, "CONTEXT_LIMIT", iteration - 1, toolCalls, observations);
            } catch (RuntimeException exception) {
                String reason = stopReason(exception);
                captureModelFailure(trace, modelContext, reason);
                return fallback(trace, request, reason, iteration, toolCalls, observations);
            }

            if (turn.toolCalls().isEmpty()) {
                missingCompletionTools = missingCompletionTools(request, observations);
                if (!missingCompletionTools.isEmpty()) {
                    boolean recorded = recordDiagnostic(
                            request.scope().runId(),
                            ActivityType.VALIDATION,
                            "nativeCompletionRequirement",
                            ActivityOutcome.REJECTED,
                            "native completion missing required observation");
                    if (!recorded) return fallback(trace, request, "AUDIT_FAILED", iteration, toolCalls, observations);
                    if (iteration >= request.maxIterations()) {
                        return fallback(
                                trace, request, "COMPLETION_REQUIREMENT_UNMET", iteration, toolCalls, observations);
                    }
                    conversation.appendAssistant(turn.text(), List.of(), advertisedTools);
                    conversation.appendApplicationInstruction(
                            "The application rejected completion because no successful observation exists yet for: "
                                    + String.join(", ", missingCompletionTools)
                                    + ". Continue with the advertised read-only tools. Do not answer from memory.");
                    continue;
                }
                boolean emptyCompletion = turn.text().isBlank();
                java.util.Optional<NativeToolAgent.TerminalStatus> terminalStatus = terminalStatus(request, turn.text());
                boolean terminalProtocolRejected = request.terminalContract().required()
                        && terminalStatus.isEmpty();
                if (emptyCompletion || terminalProtocolRejected) {
                    boolean recorded = recordDiagnostic(
                            request.scope().runId(),
                            ActivityType.VALIDATION,
                            emptyCompletion ? "nativeEmptyCompletion" : "nativeCompletionProtocol",
                            ActivityOutcome.REJECTED,
                            emptyCompletion
                                    ? "native model returned neither a tool call nor a terminal status"
                                    : "native model returned a nonconforming terminal status");
                    if (!recorded) return fallback(trace, request, "AUDIT_FAILED", iteration, toolCalls, observations);
                    if (terminalProtocolRepairAttempted || iteration >= request.maxIterations()) {
                        return fallback(
                                trace,
                                request,
                                emptyCompletion ? "EMPTY_MODEL_RESULT" : "COMPLETION_PROTOCOL_REJECTED",
                                iteration,
                                toolCalls,
                                observations);
                    }
                    terminalProtocolRepairAttempted = true;
                    conversation.appendAssistant(turn.text(), List.of(), advertisedTools);
                    conversation.appendApplicationInstruction(terminalRepairInstruction(request));
                    continue;
                }
                return new RunResult(
                        RunStatus.COMPLETED,
                        turn.text().strip(),
                        "MODEL_COMPLETED",
                        iteration,
                        toolCalls,
                        observations,
                        terminalStatus.orElse(null));
            }

            if (finalResponseOnly) {
                for (ModelToolCall call : turn.toolCalls()) {
                    TraceEventContext rejectedContext = context(request, UUID.randomUUID(), modelOperationId);
                    captureToolCall(
                            trace,
                            rejectedContext,
                            call,
                            advertisedTools,
                            ToolArgumentValidation.REJECTED,
                            canonicalArguments(call.argumentsJson()));
                    captureToolFailure(trace, rejectedContext, "TOOL_REQUESTED_AFTER_FINALIZATION");
                }
                boolean recorded = recordDiagnostic(
                        request.scope().runId(),
                        ActivityType.VALIDATION,
                        "nativeToolAfterFinalization",
                        ActivityOutcome.REJECTED,
                        "native model requested a tool after the observation budget entered final response mode");
                return fallback(
                        trace,
                        request,
                        recorded ? "TOOL_REQUESTED_AFTER_FINALIZATION" : "AUDIT_FAILED",
                        iteration,
                        toolCalls,
                        observations);
            }

            conversation.appendAssistant(turn.text(), turn.toolCalls(), advertisedTools);
            boolean requiredToolContractRepair = false;
            for (ModelToolCall call : turn.toolCalls()) {
                UUID toolOperationId = UUID.randomUUID();
                TraceEventContext toolContext = context(request, toolOperationId, modelOperationId);
                List<String> stillMissingCompletionTools = missingCompletionTools(request, observations);
                boolean mustReserveThisSlot = toolsAllowedThisTurn.contains(call.name())
                        && !stillMissingCompletionTools.isEmpty()
                        && !stillMissingCompletionTools.contains(call.name())
                        && request.maxToolCalls() - toolCalls <= stillMissingCompletionTools.size();
                if (mustReserveThisSlot) {
                    captureToolCall(
                            trace,
                            toolContext,
                            call,
                            advertisedTools,
                            ToolArgumentValidation.REJECTED,
                            canonicalArguments(call.argumentsJson()));
                    boolean recorded = recordDiagnostic(
                            request.scope().runId(),
                            ActivityType.VALIDATION,
                            "nativeRequiredToolBudgetReservation",
                            ActivityOutcome.REJECTED,
                            "optional native tool call rejected to preserve required confirmation budget");
                    if (!recorded) return fallback(trace, request, "AUDIT_FAILED", iteration, toolCalls, observations);
                    String observation = "{\"status\":\"ERROR\",\"code\":\"TOOL_BUDGET_RESERVED\","
                            + "\"data\":{},\"evidenceCount\":0}";
                    captureObservation(trace, toolContext, call, observation, "TOOL_BUDGET_RESERVED", 0, List.of());
                    conversation.appendTool(call, observation);
                    requiredToolContractRepair = true;
                    continue;
                }
                if (toolCalls >= request.maxToolCalls()) {
                    captureToolCall(
                            trace,
                            toolContext,
                            call,
                            advertisedTools,
                            ToolArgumentValidation.REJECTED,
                            canonicalArguments(call.argumentsJson()));
                    captureToolFailure(trace, toolContext, "TOOL_CALL_LIMIT");
                    if (!request.terminalContract().required()
                            && completionRequirementsSatisfied(request, observations)) {
                        return completedAtToolLimit(request, iteration, toolCalls, observations);
                    }
                    return fallback(trace, request, "TOOL_CALL_LIMIT", iteration, toolCalls, observations);
                }
                String failureKey = call.name() + "\n" + call.argumentsJson();
                if (failedCallCounts.getOrDefault(failureKey, 0) > 0) {
                    captureToolCall(
                            trace,
                            toolContext,
                            call,
                            advertisedTools,
                            ToolArgumentValidation.REJECTED,
                            canonicalArguments(call.argumentsJson()));
                    captureToolFailure(trace, toolContext, "TOOL_CIRCUIT_OPEN");
                    boolean recorded = recordDiagnostic(
                            request.scope().runId(), ActivityType.VALIDATION,
                            "nativeCircuit|" + boundedOperationName(call.name()),
                            ActivityOutcome.REJECTED, "repeated failed native tool call rejected");
                    return fallback(
                            trace,
                            request,
                            recorded ? "TOOL_CIRCUIT_OPEN" : "AUDIT_FAILED",
                            iteration,
                            toolCalls,
                            observations);
                }

                NativeAgentToolRegistry.ToolExecution toolExecution;
                boolean advertisedSchemaAccepted = false;
                String canonicalArguments = canonicalArguments(call.argumentsJson());
                try {
                    var toolSpec = tools.specification(request.role(), call.name());
                    conversation.assertAdvertisedSchema(call, toolSpec);
                    advertisedSchemaAccepted = true;
                    toolExecution = audited.invoke(
                            request.scope().runId(),
                            ActivityType.TOOL,
                            "nativeTool|" + boundedOperationName(call.name()) + "|" + shortHash(toolSpec.schemaHash()),
                            estimateTokens(call.argumentsJson()),
                            "native read tool observation recorded",
                            () -> deadline.invoke(
                                    request.scope().runId(),
                                    request.scope().deadlineAt(),
                                    () -> tools.execute(
                                            request.role(), call.name(), call.argumentsJson(), request.scope())),
                            result -> estimateTokens(observationJson(result)));
                } catch (NativeAgentConversation.StaleSchemaException exception) {
                    captureToolCall(
                            trace,
                            toolContext,
                            call,
                            advertisedTools,
                            ToolArgumentValidation.REJECTED,
                            canonicalArguments);
                    if (!reserveRemainingCallsForRequiredTools || iteration == request.maxIterations()) {
                        captureToolFailure(trace, toolContext, "TOOL_SCHEMA_STALE");
                        return fallback(trace, request, "TOOL_SCHEMA_STALE", iteration, toolCalls, observations);
                    }
                    boolean recorded = recordDiagnostic(
                            request.scope().runId(),
                            ActivityType.VALIDATION,
                            "nativeRequiredToolContractRepair",
                            ActivityOutcome.REJECTED,
                            "native model selected a tool outside the required-only portfolio");
                    if (!recorded) return fallback(trace, request, "AUDIT_FAILED", iteration, toolCalls, observations);
                    String observation = "{\"status\":\"ERROR\",\"code\":\"TOOL_NOT_ADVERTISED\","
                            + "\"data\":{},\"evidenceCount\":0}";
                    captureObservation(trace, toolContext, call, observation, "TOOL_NOT_ADVERTISED", 0, List.of());
                    conversation.appendTool(call, observation);
                    requiredToolContractRepair = true;
                    continue;
                } catch (RuntimeException exception) {
                    captureToolCall(
                            trace,
                            toolContext,
                            call,
                            advertisedTools,
                            advertisedSchemaAccepted && !canonicalArguments.isBlank()
                                    ? ToolArgumentValidation.ACCEPTED
                                    : ToolArgumentValidation.REJECTED,
                            canonicalArguments);
                    captureToolFailure(trace, toolContext, stopReason(exception));
                    return fallback(trace, request, stopReason(exception), iteration, toolCalls, observations);
                }
                captureToolCall(
                        trace,
                        toolContext,
                        call,
                        advertisedTools,
                        argumentsAccepted(toolExecution, canonicalArguments)
                                ? ToolArgumentValidation.ACCEPTED
                                : ToolArgumentValidation.REJECTED,
                        canonicalArguments);
                toolCalls++;
                observations.add(new ObservationRecord(
                        iteration,
                        call.name(),
                        toolExecution.specification().schemaHash(),
                        toolExecution.observation()));
                boolean observationRecorded = recordDiagnostic(
                        request.scope().runId(),
                        ActivityType.VALIDATION,
                        "nativeObs|" + boundedOperationName(call.name()) + "|"
                                + shortHash(toolExecution.specification().schemaHash()) + "|" + callCorrelation(call.id()),
                        toolExecution.observation().status() == ObservationStatus.ERROR
                                ? ActivityOutcome.REJECTED
                                : ActivityOutcome.SUCCEEDED,
                        "code=" + toolExecution.observation().code()
                                + " evidenceCount=" + toolExecution.observation().evidenceCount());
                if (!observationRecorded) {
                    return fallback(trace, request, "AUDIT_FAILED", iteration, toolCalls, observations);
                }
                if (toolExecution.observation().status() == ObservationStatus.ERROR) {
                    failedCallCounts.merge(failureKey, 1, Integer::sum);
                }
                String modelVisibleObservation = observationJson(toolExecution);
                captureObservation(
                        trace,
                        toolContext,
                        call,
                        modelVisibleObservation,
                        toolExecution.observation().code(),
                        toolExecution.observation().evidenceCount(),
                        mediaDescriptors(toolExecution.observation().media()));
                conversation.appendTool(call, modelVisibleObservation);
                if (!toolExecution.observation().media().isEmpty()) {
                    conversation.appendVisual(
                            "Visual observations returned by " + call.name()
                                    + ". Treat them as literal page appearance only; cited text controls rule effects.",
                            toolExecution.observation().media());
                }
            }
            if (requiredToolContractRepair) {
                conversation.appendApplicationInstruction(
                        "The application rejected a tool outside the required-only portfolio. Retry once using only: "
                                + String.join(", ", missingCompletionTools(request, observations))
                                + ". Use locators already present in prior observations; do not search again.");
                continue;
            }
            if (finalResponseTriggered(request, observations)) {
                finalResponseOnly = true;
                conversation.appendApplicationInstruction(
                        "The configured evidence-acquisition budget is complete. No more tools are available. Return "
                                + "the final response required by the system instructions now, using only the prior "
                                + "successful observations. Do not request another tool.");
            } else if (request.completeAfterRequiredTools()
                    && !request.requiredToolsBeforeCompletion().isEmpty()
                    && completionRequirementsSatisfied(request, observations)) {
                return completedAfterRequiredEvidence(request, iteration, toolCalls, observations);
            }
        }
        return fallback(trace, request, "ITERATION_LIMIT", request.maxIterations(), toolCalls, observations);
    }

    private boolean finalResponseTriggered(
            RunRequest request, List<ObservationRecord> observations) {
        if (request.finalResponseAfterToolSuccesses().isEmpty()) return false;
        return request.finalResponseAfterToolSuccesses().entrySet().stream().allMatch(target ->
                observations.stream()
                                .filter(observation -> target.getKey().equals(observation.toolName()))
                                .filter(observation -> observation.observation().status() == ObservationStatus.SUCCESS)
                                .count()
                        >= target.getValue());
    }

    private boolean completionRequirementsSatisfied(
            RunRequest request, List<ObservationRecord> observations) {
        return request.requiredToolsBeforeCompletion().stream().allMatch(required -> observations.stream()
                .anyMatch(observation -> required.equals(observation.toolName())
                        && observation.observation().status() == ObservationStatus.SUCCESS));
    }

    private List<String> missingCompletionTools(
            RunRequest request, List<ObservationRecord> observations) {
        return request.requiredToolsBeforeCompletion().stream()
                .filter(required -> observations.stream().noneMatch(observation ->
                        required.equals(observation.toolName())
                                && observation.observation().status() == ObservationStatus.SUCCESS))
                .sorted()
                .toList();
    }

    private RunResult completedAtToolLimit(
            RunRequest request,
            int iteration,
            int toolCalls,
            List<ObservationRecord> observations) {
        return new RunResult(
                RunStatus.COMPLETED,
                "REQUIRED_EVIDENCE_COLLECTED",
                "REQUIRED_EVIDENCE_COLLECTED_AT_TOOL_LIMIT",
                iteration,
                toolCalls,
                observations);
    }

    private RunResult completedAfterRequiredEvidence(
            RunRequest request,
            int iteration,
            int toolCalls,
            List<ObservationRecord> observations) {
        return new RunResult(
                RunStatus.COMPLETED,
                "REQUIRED_EVIDENCE_COLLECTED",
                "REQUIRED_EVIDENCE_COLLECTED",
                iteration,
                toolCalls,
                observations);
    }

    private java.util.Optional<NativeToolAgent.TerminalStatus> terminalStatus(RunRequest request, String text) {
        if (!request.terminalContract().required()) return java.util.Optional.empty();
        try {
            JsonNode root = objectMapper.readTree(text);
            if (root == null
                    || !root.isObject()
                    || root.size() != 1
                    || !root.has("status")
                    || !root.get("status").isTextual()) {
                return java.util.Optional.empty();
            }
            NativeToolAgent.TerminalStatus status = NativeToolAgent.TerminalStatus.valueOf(
                    root.get("status").textValue());
            return request.terminalContract().allowedStatuses().contains(status)
                    ? java.util.Optional.of(status)
                    : java.util.Optional.empty();
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }

    private String terminalRepairInstruction(RunRequest request) {
        if (!request.terminalContract().required()) {
            return "The application rejected an empty terminal turn. Choose one advertised read-only tool or return the non-empty response required by the system instructions. Do not answer from memory.";
        }
        return "The terminal response failed its JSON schema. Return one JSON object with exactly one string field named status and no prose or markdown. Allowed status values: "
                + request.terminalContract().allowedStatuses().stream()
                        .map(Enum::name)
                        .sorted()
                        .collect(java.util.stream.Collectors.joining(", "))
                + ". You may instead choose one advertised read-only tool if evidence is still missing. Do not answer from memory.";
    }

    @Override
    public String providerId(com.rulepilot.assistant.NativeAgentTool.Role role, String ownerUsername) {
        return model.providerId(role, ownerUsername);
    }

    @Override
    public boolean supports(com.rulepilot.assistant.NativeAgentTool.Role role, String ownerUsername) {
        return model.supports(role, ownerUsername);
    }

    private RunResult fallback(
            CaptureHandle trace,
            RunRequest request,
            String reason,
            int iterations,
            int toolCalls,
            List<ObservationRecord> observations) {
        recordDiagnostic(
                request.scope().runId(), ActivityType.VALIDATION, "nativeToolFallback",
                ActivityOutcome.REJECTED, "native tool loop returned deterministic fallback");
        capture(trace, () -> {
            TraceEventContext failureContext = context(request, UUID.randomUUID(), request.scope().runId());
            trace.bindingOrFailure(new BindingOrFailure(
                    failureContext,
                    LifecycleSignal.FALLBACK,
                    reason,
                    resource(request),
                    null));
        });
        return new RunResult(
                RunStatus.FALLBACK,
                request.fallbackText().strip(),
                reason,
                Math.max(0, iterations),
                toolCalls,
                observations);
    }

    private String observationJson(NativeAgentToolRegistry.ToolExecution execution) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", execution.observation().status().name(),
                    "code", execution.observation().code(),
                    "data", execution.observation().data(),
                    "evidenceCount", execution.observation().evidenceCount(),
                    "schemaHash", execution.specification().schemaHash()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("native tool observation serialization failed", exception);
        }
    }

    private void captureObservation(
            CaptureHandle trace,
            TraceEventContext context,
            ModelToolCall call,
            String modelVisibleObservation,
            String statusCode,
            int evidenceCount,
            List<MediaDescriptor> media) {
        capture(trace, () -> trace.toolObservation(new AgentTraceEvent.ToolObservation(
                        nextEvent(context),
                        call.id(),
                        call.name(),
                        modelVisibleObservation,
                        statusCode,
                        evidenceCount,
                        false,
                        media)));
    }

    private void captureToolCall(
            CaptureHandle trace,
            TraceEventContext context,
            ModelToolCall call,
            List<NativeToolModel.ToolSpec> advertisedTools,
            ToolArgumentValidation validation,
            String canonicalArguments) {
        capture(trace, () -> {
            NativeToolModel.ToolSpec spec = advertisedTools.stream()
                    .filter(candidate -> candidate.name().equals(call.name()))
                    .findFirst()
                    .orElse(null);
            trace.toolCall(new AgentTraceEvent.ToolCall(
                    nextEvent(context),
                    call.id(),
                    call.name(),
                    call.argumentsJson(),
                    canonicalArguments,
                    spec == null ? "unadvertised-v1" : spec.schemaVersion(),
                    spec == null ? sha256("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)) : spec.schemaHash(),
                    validation));
        });
    }

    private void captureToolFailure(CaptureHandle trace, TraceEventContext context, String code) {
        capture(trace, () -> trace.bindingOrFailure(new BindingOrFailure(
                        nextEvent(context),
                        LifecycleSignal.FAILURE,
                        code,
                        context.resource(),
                        null)));
    }

    private void captureModelFailure(CaptureHandle trace, TraceEventContext context, String code) {
        capture(trace, () -> trace.bindingOrFailure(new BindingOrFailure(
                        nextEvent(context),
                        LifecycleSignal.FAILURE,
                        code,
                        context.resource(),
                        null)));
    }

    private boolean argumentsAccepted(
            NativeAgentToolRegistry.ToolExecution execution, String canonicalArguments) {
        return !canonicalArguments.isBlank()
                && !Set.of("INVALID_ARGUMENT", "SCOPE_REJECTED", "TOOL_NOT_ALLOWED")
                        .contains(execution.observation().code());
    }

    private String canonicalArguments(String rawArguments) {
        try {
            JsonNode parsed = objectMapper.readTree(rawArguments);
            return parsed == null ? "" : objectMapper.writeValueAsString(canonical(parsed));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return "";
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode object = objectMapper.createObjectNode();
            List<String> fields = new java.util.ArrayList<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.stream().sorted().forEach(field -> object.set(field, canonical(value.path(field))));
            return object;
        }
        if (value.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            value.forEach(element -> array.add(canonical(element)));
            return array;
        }
        return value.deepCopy();
    }

    private void capture(CaptureHandle trace, Runnable emission) {
        if (trace == null || !trace.enabled()) return;
        try {
            emission.run();
        } catch (RuntimeException ignored) {
            // Private diagnostics never alter the native-agent result.
        }
    }

    private List<MediaDescriptor> mediaDescriptors(
            List<com.rulepilot.assistant.NativeAgentTool.ToolMedia> media) {
        return media.stream()
                .limit(4)
                .map(value -> new MediaDescriptor(
                        value.mediaType(),
                        value.label(),
                        value.width(),
                        value.height(),
                        value.content().length,
                        sha256(value.content())))
                .toList();
    }

    private TraceEventContext context(RunRequest request, UUID operationId, UUID parentOperationId) {
        return TraceEventContext.create(
                Instant.now(), stage(request), operationId, parentOperationId, resource(request));
    }

    private TraceEventContext nextEvent(TraceEventContext context) {
        return TraceEventContext.create(
                Instant.now(),
                context.stage(),
                context.operationId(),
                context.parentOperationId(),
                context.resource());
    }

    private JourneyStage stage(RunRequest request) {
        return request.role() == com.rulepilot.assistant.NativeAgentTool.Role.ANSWER
                ? JourneyStage.ANSWER
                : JourneyStage.TEACHING;
    }

    private ResourceRef resource(RunRequest request) {
        return new ResourceRef(ResourceType.ASSISTANT_RUN, request.scope().runId());
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private int estimateTokens(String value) {
        return value == null || value.isEmpty() ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    private String shortHash(String value) {
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private String boundedOperationName(String value) {
        return value.length() <= 32 ? value : value.substring(0, 32);
    }

    private String callCorrelation(String callId) {
        return java.util.UUID.nameUUIDFromBytes(callId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString()
                .substring(0, 8);
    }

    private boolean recordDiagnostic(
            java.util.UUID runId,
            ActivityType type,
            String operation,
            ActivityOutcome outcome,
            String summary) {
        try {
            audited.record(runId, type, operation, outcome, summary);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String stopReason(RuntimeException exception) {
        if (exception instanceof com.rulepilot.assistant.AgentExecutionStoppedException stopped) {
            return stopped.reason().name();
        }
        return "EXECUTION_FAILED";
    }
}
