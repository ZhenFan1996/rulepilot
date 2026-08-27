package com.rulepilot.recommendation;

import java.util.List;

/** Provider-neutral generation port for conversational replies and native recommendation actions. */
public interface BoardGameRecommendationModel {

    boolean configured();

    /** Resolves one authenticated owner's private model configuration without thread-local security state. */
    default boolean configured(String ownerUsername) {
        return configured();
    }

    Turn next(Request request);

    default Turn next(Request request, String ownerUsername) {
        return next(request);
    }

    record ToolSpec(String name, String description, String inputSchema) {
        public ToolSpec {
            if (blank(name) || blank(description) || blank(inputSchema)) {
                throw new IllegalArgumentException("recommendation action specification is invalid");
            }
        }
    }

    record ToolCall(String id, String name, String argumentsJson) {
        public ToolCall {
            if (blank(id) || blank(name) || blank(argumentsJson)) {
                throw new IllegalArgumentException("recommendation action call is invalid");
            }
        }
    }

    enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    record Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId, String toolName) {
        public Message {
            if (role == null || content == null || toolCalls == null) {
                throw new IllegalArgumentException("recommendation action message is invalid");
            }
            toolCalls = List.copyOf(toolCalls);
            if (role == Role.TOOL && (blank(toolCallId) || blank(toolName))) {
                throw new IllegalArgumentException("recommendation action response correlation is invalid");
            }
            if (role != Role.ASSISTANT && !toolCalls.isEmpty()) {
                throw new IllegalArgumentException("only assistant messages may contain action calls");
            }
        }

        public static Message system(String content) {
            return new Message(Role.SYSTEM, content, List.of(), null, null);
        }

        public static Message user(String content) {
            return new Message(Role.USER, content, List.of(), null, null);
        }

        public static Message assistant(String content, ToolCall toolCall) {
            return new Message(Role.ASSISTANT, content == null ? "" : content, List.of(toolCall), null, null);
        }

        public static Message tool(ToolCall call, String observation) {
            return new Message(Role.TOOL, observation, List.of(), call.id(), call.name());
        }
    }

    enum ToolChoice {
        REQUIRED
    }

    record Request(
            List<Message> messages,
            List<ToolSpec> tools,
            int maxOutputTokens,
            ToolChoice toolChoice) {
        public Request {
            if (messages == null
                    || messages.isEmpty()
                    || tools == null
                    || tools.isEmpty()
                    || maxOutputTokens < 128
                    || maxOutputTokens > 2_048
                    || toolChoice != ToolChoice.REQUIRED) {
                throw new IllegalArgumentException("recommendation model request is invalid");
            }
            messages = List.copyOf(messages);
            tools = List.copyOf(tools);
        }
    }

    record Turn(String text, List<ToolCall> toolCalls, CompletionStatus completionStatus) {
        public Turn(String text, List<ToolCall> toolCalls) {
            this(text, toolCalls, CompletionStatus.COMPLETE);
        }

        public Turn {
            text = text == null ? "" : text;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            completionStatus = completionStatus == null ? CompletionStatus.UNKNOWN : completionStatus;
        }
    }

    enum CompletionStatus {
        COMPLETE,
        OUTPUT_LIMIT,
        UNKNOWN
    }

    /** Safe provider-protocol diagnosis; never contains raw model output or player text. */
    final class ProtocolFailure extends RuntimeException {
        private final String code;

        public ProtocolFailure(String code, Throwable cause) {
            super(code, cause);
            if (blank(code) || !code.matches("[A-Z0-9_]{3,80}")) {
                throw new IllegalArgumentException("recommendation protocol failure code is invalid");
            }
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
