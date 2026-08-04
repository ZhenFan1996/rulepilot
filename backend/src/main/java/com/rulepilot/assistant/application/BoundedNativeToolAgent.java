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

    public BoundedNativeToolAgent(
            NativeToolModel model,
            NativeAgentToolRegistry tools,
            AgentExecutionControl execution,
            AuditedAgentInvocations audited,
            ObjectMapper objectMapper) {
        this.model = model;
        this.tools = tools;
        this.execution = execution;
        this.audited = audited;
        this.objectMapper = objectMapper;
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

        for (int iteration = 1; iteration <= request.maxIterations(); iteration++) {
            if (Instant.now().isAfter(request.scope().deadlineAt())) {
                return fallback(request, "TIMEOUT", iteration - 1, toolCalls, observations);
            }
            try {
                execution.assertStepAllowed(request.scope().runId(), iteration);
            } catch (RuntimeException exception) {
                return fallback(request, stopReason(exception), iteration - 1, toolCalls, observations);
            }

            ModelTurn turn;
            List<com.rulepilot.assistant.NativeToolModel.ToolSpec> advertisedTools =
                    tools.specifications(request.role(), request.allowedTools());
            if (!request.allowedTools().isEmpty()
                    && advertisedTools.size() != request.allowedTools().size()) {
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
                        () -> model.next(new ModelRequest(
                                request.role(),
                                request.scope(),
                                messages,
                                advertisedTools,
                                request.maxOutputTokens())),
                        ModelTurn::completionTokens);
            } catch (NativeAgentConversation.ContextLimitException exception) {
                return fallback(request, "CONTEXT_LIMIT", iteration - 1, toolCalls, observations);
            } catch (RuntimeException exception) {
                return fallback(request, stopReason(exception), iteration, toolCalls, observations);
            }

            if (turn.toolCalls().isEmpty()) {
                List<String> missingCompletionTools = request.requiredToolsBeforeCompletion().stream()
                        .filter(required -> observations.stream().noneMatch(observation ->
                                required.equals(observation.toolName())
                                        && observation.observation().status() != ObservationStatus.ERROR))
                        .sorted()
                        .toList();
                if (!missingCompletionTools.isEmpty()) {
                    boolean recorded = recordDiagnostic(
                            request.scope().runId(),
                            ActivityType.VALIDATION,
                            "nativeCompletionRequirement",
                            ActivityOutcome.REJECTED,
                            "native completion missing required observation");
                    if (!recorded) return fallback(request, "AUDIT_FAILED", iteration, toolCalls, observations);
                    if (iteration == request.maxIterations()) {
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
                if (turn.text().isBlank()) return fallback(request, "EMPTY_MODEL_RESULT", iteration, toolCalls, observations);
                return new RunResult(
                        RunStatus.COMPLETED,
                        turn.text().strip(),
                        "MODEL_COMPLETED",
                        iteration,
                        toolCalls,
                        observations);
            }

            conversation.appendAssistant(turn.text(), turn.toolCalls(), advertisedTools);
            for (ModelToolCall call : turn.toolCalls()) {
                if (toolCalls >= request.maxToolCalls()) {
                    if (completionRequirementsSatisfied(request, observations)) {
                        return completedAtToolLimit(iteration, toolCalls, observations);
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
                            () -> tools.execute(request.role(), call.name(), call.argumentsJson(), request.scope()),
                            result -> estimateTokens(observationJson(result)));
                } catch (NativeAgentConversation.StaleSchemaException exception) {
                    return fallback(request, "TOOL_SCHEMA_STALE", iteration, toolCalls, observations);
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
            if (!request.requiredToolsBeforeCompletion().isEmpty()
                    && completionRequirementsSatisfied(request, observations)) {
                return completedAfterRequiredEvidence(iteration, toolCalls, observations);
            }
        }
        return fallback(request, "ITERATION_LIMIT", request.maxIterations(), toolCalls, observations);
    }

    private boolean completionRequirementsSatisfied(
            RunRequest request, List<ObservationRecord> observations) {
        return request.requiredToolsBeforeCompletion().stream().allMatch(required -> observations.stream()
                .anyMatch(observation -> required.equals(observation.toolName())
                        && observation.observation().status() != ObservationStatus.ERROR));
    }

    private RunResult completedAtToolLimit(
            int iteration, int toolCalls, List<ObservationRecord> observations) {
        return new RunResult(
                RunStatus.COMPLETED,
                "EVIDENCE_READY",
                "REQUIRED_EVIDENCE_COLLECTED_AT_TOOL_LIMIT",
                iteration,
                toolCalls,
                observations);
    }

    private RunResult completedAfterRequiredEvidence(
            int iteration, int toolCalls, List<ObservationRecord> observations) {
        return new RunResult(
                RunStatus.COMPLETED,
                "EVIDENCE_READY",
                "REQUIRED_EVIDENCE_COLLECTED",
                iteration,
                toolCalls,
                observations);
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
