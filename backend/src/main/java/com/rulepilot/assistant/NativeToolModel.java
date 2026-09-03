package com.rulepilot.assistant;

import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeAgentTool.ToolMedia;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import java.util.List;
import java.util.Objects;

/** Provider-neutral native tool-call model port. */
public interface NativeToolModel {

    ModelTurn next(ModelRequest request);

    default String providerId(Role role, String ownerUsername) {
        return "native-tool-model";
    }

    default boolean supports(Role role, String ownerUsername) {
        return true;
    }

    enum ModelRequestFailureKind {
        TIMEOUT,
        TEMPORARILY_UNAVAILABLE
    }

    /** Provider-neutral failure from one model request; retry policy remains with the caller. */
    final class ModelRequestFailure extends RuntimeException {
        private final ModelRequestFailureKind kind;

        public ModelRequestFailure(ModelRequestFailureKind kind, Throwable cause) {
            super(
                    "native model request failed: " + Objects.requireNonNull(kind).name(),
                    Objects.requireNonNull(cause));
            this.kind = kind;
        }

        public ModelRequestFailureKind kind() {
            return kind;
        }
    }

    record ToolSpec(
            String name,
            String description,
            String inputSchema,
            String schemaVersion,
            String schemaHash) {
        public ToolSpec {
            if (blank(name) || blank(description) || blank(inputSchema) || blank(schemaVersion) || blank(schemaHash)) {
                throw new IllegalArgumentException("native tool specification is invalid");
            }
        }
    }

    record ModelToolCall(String id, String name, String argumentsJson) {
        public ModelToolCall {
            if (blank(id) || blank(name) || blank(argumentsJson)) {
                throw new IllegalArgumentException("native model tool call is invalid");
            }
        }
    }

    enum MessageRole {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    record ConversationMessage(
            MessageRole role,
            String content,
            List<ModelToolCall> toolCalls,
            String toolCallId,
            String toolName,
            List<ToolMedia> media) {
        public ConversationMessage(
                MessageRole role,
                String content,
                List<ModelToolCall> toolCalls,
                String toolCallId,
                String toolName) {
            this(role, content, toolCalls, toolCallId, toolName, List.of());
        }

        public ConversationMessage {
            if (role == null || content == null || toolCalls == null || media == null) {
                throw new IllegalArgumentException("native tool conversation message is invalid");
            }
            toolCalls = List.copyOf(toolCalls);
            media = List.copyOf(media);
            if (role == MessageRole.TOOL && (blank(toolCallId) || blank(toolName))) {
                throw new IllegalArgumentException("native tool response correlation is invalid");
            }
            if (role != MessageRole.ASSISTANT && !toolCalls.isEmpty()) {
                throw new IllegalArgumentException("only assistant messages may carry tool calls");
            }
            if (role != MessageRole.USER && !media.isEmpty()) {
                throw new IllegalArgumentException("only user messages may carry native tool media");
            }
        }

        public static ConversationMessage system(String content) {
            return new ConversationMessage(MessageRole.SYSTEM, content, List.of(), null, null);
        }

        public static ConversationMessage user(String content) {
            return new ConversationMessage(MessageRole.USER, content, List.of(), null, null);
        }

        public static ConversationMessage assistant(String content, List<ModelToolCall> calls) {
            return new ConversationMessage(MessageRole.ASSISTANT, content == null ? "" : content, calls, null, null);
        }

        public static ConversationMessage tool(String callId, String name, String observationJson) {
            return new ConversationMessage(MessageRole.TOOL, observationJson, List.of(), callId, name);
        }

        public static ConversationMessage visualObservation(String content, List<ToolMedia> media) {
            return new ConversationMessage(MessageRole.USER, content, List.of(), null, null, media);
        }
    }

    record ModelRequest(
            Role role,
            ToolScope scope,
            List<ConversationMessage> conversation,
            List<ToolSpec> tools,
            boolean jsonOutputRequired) {
        public ModelRequest(
                Role role,
                ToolScope scope,
                List<ConversationMessage> conversation,
                List<ToolSpec> tools) {
            this(role, scope, conversation, tools, false);
        }

        public ModelRequest {
            if (role == null || scope == null || conversation == null || conversation.isEmpty()
                    || tools == null) {
                throw new IllegalArgumentException("native tool model request is invalid");
            }
            conversation = List.copyOf(conversation);
            tools = List.copyOf(tools);
        }
    }

    record ModelTurn(String text, List<ModelToolCall> toolCalls, int promptTokens, int completionTokens) {
        public ModelTurn {
            text = text == null ? "" : text;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            if (promptTokens < 0 || completionTokens < 0) {
                throw new IllegalArgumentException("native model token usage is invalid");
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
