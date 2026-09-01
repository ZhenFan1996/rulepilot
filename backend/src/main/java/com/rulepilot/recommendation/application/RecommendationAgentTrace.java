package com.rulepilot.recommendation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rulepilot.agenttrace.AgentTraceEvent;
import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.JourneyStage;
import com.rulepilot.agenttrace.AgentTraceEvent.LifecycleSignal;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelCallStarted;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelToolCall;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelTurn;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceType;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolArgumentValidation;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolCall;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolObservation;
import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ModelDescriptor;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolSpec;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Fail-open projection of one recommendation turn into the explicitly enabled private trace. */
final class RecommendationAgentTrace {

    private static final String MODEL_SCHEMA_VERSION = "recommendation-native-actions-v1";
    private final CaptureHandle capture;
    private final ObjectMapper json;
    private final UUID turnOperationId;
    private final ResourceRef turnResource;
    private UUID modelOperationId;
    private UUID toolOperationId;

    private RecommendationAgentTrace(
            CaptureHandle capture,
            ObjectMapper json,
            UUID turnOperationId) {
        this.capture = capture == null ? CaptureHandle.noop() : capture;
        this.json = json;
        this.turnOperationId = turnOperationId == null ? UUID.randomUUID() : turnOperationId;
        turnResource = new ResourceRef(ResourceType.RECOMMENDATION_TURN, this.turnOperationId);
    }

    static RecommendationAgentTrace begin(
            CaptureHandle capture,
            ObjectMapper json,
            UUID turnOperationId) {
        return new RecommendationAgentTrace(capture, json, turnOperationId);
    }

    void modelCallStarted(
            int attempt,
            Request request,
            List<ToolSpec> actions,
            ModelDescriptor descriptor) {
        modelOperationId = UUID.randomUUID();
        toolOperationId = null;
        safely(() -> capture.modelCallStarted(new ModelCallStarted(
                context(modelOperationId, turnOperationId),
                descriptor.providerId(),
                descriptor.modelId(),
                attempt,
                BoardGameRecommendationAgent.PROMPT_VERSION,
                MODEL_SCHEMA_VERSION,
                schemaHash(actions),
                inputTokenEstimate(request),
                request.maxOutputTokens())));
    }

    void modelTurn(
            int attempt,
            BoardGameRecommendationModel.Turn turn,
            ModelDescriptor descriptor) {
        UUID operation = modelOperationId == null ? UUID.randomUUID() : modelOperationId;
        safely(() -> capture.modelTurn(new ModelTurn(
                context(operation, turnOperationId),
                descriptor.providerId(),
                descriptor.modelId(),
                attempt,
                turn.text(),
                turn.toolCalls().stream()
                        .map(call -> new ModelToolCall(call.id(), call.name(), call.argumentsJson()))
                        .toList(),
                turn.completionStatus().name(),
                0,
                0,
                false)));
    }

    void beginTool() {
        toolOperationId = UUID.randomUUID();
    }

    void toolCall(
            BoardGameRecommendationModel.ToolCall call,
            ToolSpec spec,
            boolean accepted) {
        UUID operation = toolOperationId == null ? UUID.randomUUID() : toolOperationId;
        safely(() -> capture.toolCall(new ToolCall(
                context(operation, modelOperationId),
                call.id(),
                call.name(),
                call.argumentsJson(),
                canonicalArguments(call.argumentsJson()),
                BoardGameRecommendationAgent.PROMPT_VERSION,
                sha256(spec == null ? call.name() : spec.inputSchema()),
                accepted ? ToolArgumentValidation.ACCEPTED : ToolArgumentValidation.REJECTED)));
    }

    void toolObservation(
            BoardGameRecommendationModel.ToolCall call,
            String modelVisibleObservation,
            boolean rejected,
            boolean reused) {
        UUID operation = toolOperationId == null ? UUID.randomUUID() : toolOperationId;
        safely(() -> capture.toolObservation(new ToolObservation(
                context(operation, modelOperationId),
                call.id(),
                call.name(),
                modelVisibleObservation,
                rejected ? "REJECTED" : "OBSERVED",
                0,
                reused,
                List.of())));
    }

    void terminalToolDisposition(
            BoardGameRecommendationModel.ToolCall call,
            ConversationResponse response,
            boolean reused) {
        UUID operation = toolOperationId == null ? UUID.randomUUID() : toolOperationId;
        safely(() -> capture.toolObservation(new ToolObservation(
                context(operation, modelOperationId),
                call.id(),
                call.name(),
                json.writeValueAsString(response),
                "TERMINAL_RESPONSE",
                0,
                reused,
                List.of())));
    }

    void failure(String code) {
        UUID operation = modelOperationId == null ? turnOperationId : modelOperationId;
        safely(() -> capture.bindingOrFailure(new BindingOrFailure(
                context(operation, operation.equals(turnOperationId) ? null : turnOperationId),
                LifecycleSignal.FAILURE,
                code,
                turnResource,
                null)));
    }

    private String canonicalArguments(String rawArguments) {
        try {
            return json.writeValueAsString(canonical(json.readTree(rawArguments)));
        } catch (JsonProcessingException exception) {
            return "";
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode object = json.createObjectNode();
            List<String> fields = new ArrayList<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.stream().sorted().forEach(field -> object.set(field, canonical(value.path(field))));
            return object;
        }
        if (value.isArray()) {
            ArrayNode array = json.createArrayNode();
            value.forEach(element -> array.add(canonical(element)));
            return array;
        }
        return value.deepCopy();
    }

    private int inputTokenEstimate(Request request) {
        long characters = request.messages().stream().mapToLong(message -> message.content().length()).sum()
                + request.tools().stream()
                        .mapToLong(tool -> (long) tool.name().length()
                                + tool.description().length()
                                + tool.inputSchema().length())
                        .sum();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, (characters + 3L) / 4L));
    }

    private String schemaHash(List<ToolSpec> actions) {
        StringBuilder value = new StringBuilder();
        actions.forEach(action -> value.append(action.name())
                .append('\u0000')
                .append(action.inputSchema())
                .append('\u0001'));
        return sha256(value.toString());
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private TraceEventContext context(UUID operationId, UUID parentOperationId) {
        return TraceEventContext.create(
                Instant.now(),
                JourneyStage.RECOMMENDATION,
                operationId,
                parentOperationId,
                turnResource);
    }

    private void safely(ThrowingAction action) {
        if (!capture.enabled() || json == null) return;
        try {
            action.run();
        } catch (RuntimeException | JsonProcessingException ignored) {
            // Private diagnostics are strictly fail-open and cannot replace the validated player result.
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws JsonProcessingException;
    }
}
