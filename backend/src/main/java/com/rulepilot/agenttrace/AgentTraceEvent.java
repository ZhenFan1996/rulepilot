package com.rulepilot.agenttrace;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AgentTraceEvent.UserTurn.class, name = "USER_TURN"),
    @JsonSubTypes.Type(value = AgentTraceEvent.ModelCallStarted.class, name = "MODEL_CALL_STARTED"),
    @JsonSubTypes.Type(value = AgentTraceEvent.ModelTurn.class, name = "MODEL_TURN"),
    @JsonSubTypes.Type(value = AgentTraceEvent.ToolCall.class, name = "TOOL_CALL"),
    @JsonSubTypes.Type(value = AgentTraceEvent.ToolObservation.class, name = "TOOL_OBSERVATION"),
    @JsonSubTypes.Type(value = AgentTraceEvent.Publication.class, name = "PUBLICATION"),
    @JsonSubTypes.Type(value = AgentTraceEvent.BindingOrFailure.class, name = "BINDING_OR_FAILURE")
})
public sealed interface AgentTraceEvent
        permits AgentTraceEvent.UserTurn,
                AgentTraceEvent.ModelCallStarted,
                AgentTraceEvent.ModelTurn,
                AgentTraceEvent.ToolCall,
                AgentTraceEvent.ToolObservation,
                AgentTraceEvent.Publication,
                AgentTraceEvent.BindingOrFailure {

    TraceEventContext context();

    @JsonIgnore
    EventKind kind();

    enum EventKind {
        USER_TURN,
        MODEL_CALL_STARTED,
        MODEL_TURN,
        TOOL_CALL,
        TOOL_OBSERVATION,
        PUBLICATION,
        BINDING_OR_FAILURE
    }

    enum JourneyStage {
        RECOMMENDATION,
        IMPORT,
        TEACHING,
        ANSWER
    }

    enum ResourceType {
        RECOMMENDATION_CONVERSATION,
        RECOMMENDATION_TURN,
        IMPORT_JOB,
        DOCUMENT_VERSION,
        ASSISTANT_RUN,
        TEACHING_PLAN,
        TEACHING_RUN,
        VISUAL_RUN,
        LOCALIZATION_RUN,
        GAME_SESSION,
        CONVERSATION_TURN
    }

    enum ToolArgumentValidation {
        UNCHECKED,
        ACCEPTED,
        REJECTED
    }

    enum PublicationChannel {
        RECOMMENDATION,
        IMPORT_CANDIDATES,
        TEACHING_PLAN,
        TEACHING_SECTION,
        TEACHING_LESSON,
        ANSWER,
        FALLBACK
    }

    enum LifecycleSignal {
        BINDING,
        FAILURE,
        REPLAY,
        GAP,
        FALLBACK
    }

    record ResourceRef(ResourceType type, UUID id) {
        public ResourceRef {
            if (type == null || id == null) throw new IllegalArgumentException("agent trace resource is invalid");
        }
    }

    record TraceEventContext(
            UUID eventId,
            Instant occurredAt,
            JourneyStage stage,
            UUID operationId,
            UUID parentOperationId,
            ResourceRef resource) {
        public TraceEventContext {
            if (eventId == null || occurredAt == null || stage == null || operationId == null) {
                throw new IllegalArgumentException("agent trace event context is invalid");
            }
        }

        public static TraceEventContext create(
                Instant occurredAt,
                JourneyStage stage,
                UUID operationId,
                UUID parentOperationId,
                ResourceRef resource) {
            return new TraceEventContext(UUID.randomUUID(), occurredAt, stage, operationId, parentOperationId, resource);
        }
    }

    record ModelToolCall(String callId, String name, String argumentsJson) {
        public ModelToolCall {
            callId = required(callId, "model tool call id", 240);
            name = required(name, "model tool name", 120);
            argumentsJson = required(argumentsJson, "model tool arguments", 256_000);
        }
    }

    record MediaDescriptor(String mediaType, String label, int width, int height, long byteCount, String sha256) {
        public MediaDescriptor {
            mediaType = required(mediaType, "media type", 120);
            label = required(label, "media label", 240);
            sha256 = required(sha256, "media digest", 128);
            if (width < 1 || height < 1 || byteCount < 1) {
                throw new IllegalArgumentException("agent trace media descriptor is invalid");
            }
        }
    }

    record UserTurn(TraceEventContext context, String userText, String typedRequestJson, String locale)
            implements AgentTraceEvent {
        public UserTurn {
            context = requiredContext(context);
            userText = required(userText, "user turn text", 16_000);
            typedRequestJson = required(typedRequestJson, "typed user request", 128_000);
            locale = required(locale, "user turn locale", 40);
        }

        @Override
        public EventKind kind() {
            return EventKind.USER_TURN;
        }
    }

    record ModelCallStarted(
            TraceEventContext context,
            String providerId,
            String modelId,
            int attempt,
            String templateVersion,
            String outputSchemaVersion,
            String outputSchemaHash,
            int inputTokenEstimate,
            int maximumOutputTokens)
            implements AgentTraceEvent {
        public ModelCallStarted {
            context = requiredContext(context);
            providerId = required(providerId, "model provider", 120);
            modelId = required(modelId, "model id", 160);
            templateVersion = required(templateVersion, "model template version", 120);
            outputSchemaVersion = optional(outputSchemaVersion, 120);
            outputSchemaHash = optional(outputSchemaHash, 128);
            if (attempt < 1 || inputTokenEstimate < 0 || maximumOutputTokens < 1) {
                throw new IllegalArgumentException("agent trace model call metadata is invalid");
            }
        }

        @Override
        public EventKind kind() {
            return EventKind.MODEL_CALL_STARTED;
        }
    }

    record ModelTurn(
            TraceEventContext context,
            String providerId,
            String modelId,
            int attempt,
            String assistantText,
            List<ModelToolCall> toolCalls,
            String finishStatus,
            int inputTokens,
            int outputTokens,
            boolean partialFailed)
            implements AgentTraceEvent {
        public ModelTurn {
            context = requiredContext(context);
            providerId = required(providerId, "model provider", 120);
            modelId = required(modelId, "model id", 160);
            assistantText = assistantText == null ? "" : bounded(assistantText, "assistant text", 500_000);
            toolCalls = immutable(toolCalls, "model tool calls", 24);
            finishStatus = required(finishStatus, "model finish status", 80);
            if (attempt < 1 || inputTokens < 0 || outputTokens < 0
                    || assistantText.isBlank() && toolCalls.isEmpty() && !partialFailed) {
                throw new IllegalArgumentException("agent trace model turn is invalid");
            }
        }

        @Override
        public EventKind kind() {
            return EventKind.MODEL_TURN;
        }
    }

    record ToolCall(
            TraceEventContext context,
            String callId,
            String toolName,
            String rawArgumentsJson,
            String canonicalArgumentsJson,
            String schemaVersion,
            String schemaHash,
            ToolArgumentValidation validation)
            implements AgentTraceEvent {
        public ToolCall {
            context = requiredContext(context);
            callId = required(callId, "tool call id", 240);
            toolName = required(toolName, "tool name", 120);
            rawArgumentsJson = required(rawArgumentsJson, "raw tool arguments", 256_000);
            canonicalArgumentsJson = optional(canonicalArgumentsJson, 256_000);
            schemaVersion = required(schemaVersion, "tool schema version", 120);
            schemaHash = required(schemaHash, "tool schema hash", 128);
            if (validation == null) throw new IllegalArgumentException("tool argument validation is required");
        }

        @Override
        public EventKind kind() {
            return EventKind.TOOL_CALL;
        }
    }

    record ToolObservation(
            TraceEventContext context,
            String callId,
            String toolName,
            String modelVisibleObservationJson,
            String statusCode,
            int evidenceCount,
            boolean reused,
            List<MediaDescriptor> media)
            implements AgentTraceEvent {
        public ToolObservation {
            context = requiredContext(context);
            callId = required(callId, "tool observation call id", 240);
            toolName = required(toolName, "tool observation name", 120);
            modelVisibleObservationJson = required(
                    modelVisibleObservationJson, "model-visible tool observation", 500_000);
            statusCode = required(statusCode, "tool observation status", 120);
            media = immutable(media, "tool observation media", 4);
            if (evidenceCount < 0) throw new IllegalArgumentException("tool evidence count cannot be negative");
        }

        @Override
        public EventKind kind() {
            return EventKind.TOOL_OBSERVATION;
        }
    }

    record Publication(
            TraceEventContext context,
            PublicationChannel channel,
            String playerFacingJson,
            String statusCode,
            List<UUID> citationIds)
            implements AgentTraceEvent {
        public Publication {
            context = requiredContext(context);
            if (channel == null) throw new IllegalArgumentException("publication channel is required");
            playerFacingJson = required(playerFacingJson, "player-facing publication", 500_000);
            statusCode = required(statusCode, "publication status", 120);
            citationIds = immutable(citationIds, "publication citations", 200);
        }

        @Override
        public EventKind kind() {
            return EventKind.PUBLICATION;
        }
    }

    record BindingOrFailure(
            TraceEventContext context,
            LifecycleSignal signal,
            String code,
            ResourceRef parentResource,
            ResourceRef childResource)
            implements AgentTraceEvent {
        public BindingOrFailure {
            context = requiredContext(context);
            if (signal == null) throw new IllegalArgumentException("agent trace lifecycle signal is required");
            code = required(code, "agent trace lifecycle code", 120);
            if (signal == LifecycleSignal.BINDING && childResource == null) {
                throw new IllegalArgumentException("agent trace binding requires a child resource");
            }
        }

        @Override
        public EventKind kind() {
            return EventKind.BINDING_OR_FAILURE;
        }
    }

    private static TraceEventContext requiredContext(TraceEventContext context) {
        if (context == null) throw new IllegalArgumentException("agent trace event context is required");
        return context;
    }

    private static String required(String value, String label, int maximumLength) {
        String checked = bounded(value, label, maximumLength).strip();
        if (checked.isBlank()) throw new IllegalArgumentException(label + " is required");
        return checked;
    }

    private static String optional(String value, int maximumLength) {
        if (value == null || value.isBlank()) return "";
        return bounded(value, "optional agent trace text", maximumLength).strip();
    }

    private static String bounded(String value, String label, int maximumLength) {
        if (value == null || value.length() > maximumLength) {
            throw new IllegalArgumentException(label + " exceeds its private trace boundary");
        }
        return value;
    }

    private static <T> List<T> immutable(List<T> values, String label, int maximumSize) {
        List<T> checked = values == null ? List.of() : List.copyOf(values);
        if (checked.size() > maximumSize || checked.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(label + " exceeds its private trace boundary");
        }
        return checked;
    }
}
