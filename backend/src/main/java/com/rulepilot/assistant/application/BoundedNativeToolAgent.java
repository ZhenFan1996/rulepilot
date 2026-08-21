package com.rulepilot.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.NativeAgentTool.ObservationStatus;
import com.rulepilot.assistant.NativeToolAgent;
import com.rulepilot.assistant.NativeToolModel;
import com.rulepilot.assistant.NativeToolModel.ConversationMessage;
import com.rulepilot.assistant.NativeToolModel.ModelRequest;
import com.rulepilot.assistant.NativeToolModel.ModelTurn;
import com.rulepilot.assistant.NativeToolModel.ModelToolCall;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        if (!model.supports(request.role(), request.scope().ownerUsername())) {
            return fallback(request, "MODEL_CAPABILITY_UNAVAILABLE", 0, 0, List.of());
        }
        NativeAgentConversation conversation = new NativeAgentConversation(
                request.systemPrompt(), request.playerRequest(), MAX_CONTEXT_CHARACTERS);
        List<ObservationRecord> observations = new java.util.ArrayList<>();
        Map<String, Integer> failedCallCounts = new HashMap<>();
        int toolCalls = 0;
        boolean finalResponseOnly = false;
        boolean requiredOnlyInstructionAdded = false;

        for (int iteration = 1; iteration <= request.maxIterations() + 1; iteration++) {
            // A tool read on the last acquisition turn still needs one tool-free protocol decision. This extra turn
            // cannot expand the evidence search because the registry advertises no tools in final-response mode.
            if (iteration > request.maxIterations() && !finalResponseOnly) break;
            if (Instant.now().isAfter(request.scope().deadlineAt())) {
                return fallback(request, "TIMEOUT", iteration - 1, toolCalls, observations);
            }
            try {
                execution.assertStepAllowed(request.scope().runId(), iteration);
            } catch (RuntimeException exception) {
                return fallback(request, stopReason(exception), iteration - 1, toolCalls, observations);
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
                return fallback(request, "TOOL_ALLOWLIST_UNAVAILABLE", iteration - 1, toolCalls, observations);
            }
            try {
                List<ConversationMessage> messages = conversation.messages();
                int estimatedInputTokens = estimateTokens(messages.stream()
                        .map(ConversationMessage::content)
                        .reduce("", (left, right) -> left + right));
                int currentIteration = iteration;
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
            } catch (NativeAgentConversation.ContextLimitException exception) {
                return fallback(request, "CONTEXT_LIMIT", iteration - 1, toolCalls, observations);
            } catch (RuntimeException exception) {
                return fallback(request, stopReason(exception), iteration, toolCalls, observations);
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
                    if (!recorded) return fallback(request, "AUDIT_FAILED", iteration, toolCalls, observations);
                    if (iteration >= request.maxIterations()) {
                        return fallback(
                                request, "COMPLETION_REQUIREMENT_UNMET", iteration, toolCalls, observations);
                    }
                    conversation.appendAssistant(turn.text(), List.of(), advertisedTools);
                    conversation.appendApplicationInstruction(
                            "The application rejected completion because no successful observation exists yet for: "
                                    + String.join(", ", missingCompletionTools)
                                    + ". Continue with the advertised read-only tools. Do not answer from memory.");
                    continue;
                }
                boolean emptyCompletion = turn.text().isBlank();
                boolean terminalProtocolRejected = !request.requiredTerminalText().isBlank()
                        && !request.requiredTerminalText().equals(turn.text().strip());
                if (emptyCompletion || terminalProtocolRejected) {
                    boolean recorded = recordDiagnostic(
                            request.scope().runId(),
                            ActivityType.VALIDATION,
                            emptyCompletion ? "nativeEmptyCompletion" : "nativeCompletionProtocol",
                            ActivityOutcome.REJECTED,
                            emptyCompletion
                                    ? "native model returned neither a tool call nor a terminal status"
                                    : "native model returned a nonconforming terminal status");
                    if (!recorded) return fallback(request, "AUDIT_FAILED", iteration, toolCalls, observations);
                    if (iteration >= request.maxIterations()) {
                        return fallback(
                                request,
                                emptyCompletion ? "EMPTY_MODEL_RESULT" : "COMPLETION_PROTOCOL_REJECTED",
                                iteration,
                                toolCalls,
                                observations);
                    }
                    conversation.appendAssistant(turn.text(), List.of(), advertisedTools);
                    conversation.appendApplicationInstruction(
                            "The application rejected this terminal turn. Review the unresolved request and prior observations, then choose one advertised read-only tool or return exactly: "
                                    + (request.requiredTerminalText().isBlank()
                                            ? "the terminal status required by the system instructions"
                                            : request.requiredTerminalText())
                                    + ". Do not answer from memory.");
                    continue;
                }
                return new RunResult(
                        RunStatus.COMPLETED,
                        turn.text().strip(),
                        "MODEL_COMPLETED",
                        iteration,
                        toolCalls,
                        observations);
            }

            if (finalResponseOnly) {
                boolean recorded = recordDiagnostic(
                        request.scope().runId(),
                        ActivityType.VALIDATION,
                        "nativeToolAfterFinalization",
                        ActivityOutcome.REJECTED,
                        "native model requested a tool after the observation budget entered final response mode");
                return fallback(
                        request,
                        recorded ? "TOOL_REQUESTED_AFTER_FINALIZATION" : "AUDIT_FAILED",
                        iteration,
                        toolCalls,
                        observations);
            }

            conversation.appendAssistant(turn.text(), turn.toolCalls(), advertisedTools);
            boolean requiredToolContractRepair = false;
            for (ModelToolCall call : turn.toolCalls()) {
                List<String> stillMissingCompletionTools = missingCompletionTools(request, observations);
                boolean mustReserveThisSlot = toolsAllowedThisTurn.contains(call.name())
                        && !stillMissingCompletionTools.isEmpty()
                        && !stillMissingCompletionTools.contains(call.name())
                        && request.maxToolCalls() - toolCalls <= stillMissingCompletionTools.size();
                if (mustReserveThisSlot) {
                    boolean recorded = recordDiagnostic(
                            request.scope().runId(),
                            ActivityType.VALIDATION,
                            "nativeRequiredToolBudgetReservation",
                            ActivityOutcome.REJECTED,
                            "optional native tool call rejected to preserve required confirmation budget");
                    if (!recorded) return fallback(request, "AUDIT_FAILED", iteration, toolCalls, observations);
                    conversation.appendTool(call, "{\"status\":\"ERROR\",\"code\":\"TOOL_BUDGET_RESERVED\","
                            + "\"data\":{},\"evidenceCount\":0}");
                    requiredToolContractRepair = true;
                    continue;
                }
                if (toolCalls >= request.maxToolCalls()) {
                    if (completionRequirementsSatisfied(request, observations)) {
                        return completedAtToolLimit(request, iteration, toolCalls, observations);
                    }
                    return fallback(request, "TOOL_CALL_LIMIT", iteration, toolCalls, observations);
                }
                String failureKey = call.name() + "\n" + call.argumentsJson();
                if (failedCallCounts.getOrDefault(failureKey, 0) > 0) {
                    boolean recorded = recordDiagnostic(
                            request.scope().runId(), ActivityType.VALIDATION,
                            "nativeCircuit|" + boundedOperationName(call.name()),
                            ActivityOutcome.REJECTED, "repeated failed native tool call rejected");
                    return fallback(
                            request,
                            recorded ? "TOOL_CIRCUIT_OPEN" : "AUDIT_FAILED",
                            iteration,
                            toolCalls,
                            observations);
                }

                NativeAgentToolRegistry.ToolExecution toolExecution;
                try {
                    var toolSpec = tools.specification(request.role(), call.name());
                    conversation.assertAdvertisedSchema(call, toolSpec);
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
                    if (!reserveRemainingCallsForRequiredTools || iteration == request.maxIterations()) {
                        return fallback(request, "TOOL_SCHEMA_STALE", iteration, toolCalls, observations);
                    }
                    boolean recorded = recordDiagnostic(
                            request.scope().runId(),
                            ActivityType.VALIDATION,
                            "nativeRequiredToolContractRepair",
                            ActivityOutcome.REJECTED,
                            "native model selected a tool outside the required-only portfolio");
                    if (!recorded) return fallback(request, "AUDIT_FAILED", iteration, toolCalls, observations);
                    conversation.appendTool(call, "{\"status\":\"ERROR\",\"code\":\"TOOL_NOT_ADVERTISED\","
                            + "\"data\":{},\"evidenceCount\":0}");
                    requiredToolContractRepair = true;
                    continue;
                } catch (RuntimeException exception) {
                    return fallback(request, stopReason(exception), iteration, toolCalls, observations);
                }
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
                    return fallback(request, "AUDIT_FAILED", iteration, toolCalls, observations);
                }
                if (toolExecution.observation().status() == ObservationStatus.ERROR) {
                    failedCallCounts.merge(failureKey, 1, Integer::sum);
                }
                conversation.appendTool(call, observationJson(toolExecution));
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
        return fallback(request, "ITERATION_LIMIT", request.maxIterations(), toolCalls, observations);
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
                terminalText(request),
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
                terminalText(request),
                "REQUIRED_EVIDENCE_COLLECTED",
                iteration,
                toolCalls,
                observations);
    }

    private String terminalText(RunRequest request) {
        return request.requiredTerminalText().isBlank() ? "EVIDENCE_READY" : request.requiredTerminalText();
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
            RunRequest request,
            String reason,
            int iterations,
            int toolCalls,
            List<ObservationRecord> observations) {
        recordDiagnostic(
                request.scope().runId(), ActivityType.VALIDATION, "nativeToolFallback",
                ActivityOutcome.REJECTED, "native tool loop returned deterministic fallback");
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
