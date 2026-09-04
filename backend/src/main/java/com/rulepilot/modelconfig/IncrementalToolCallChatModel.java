package com.rulepilot.modelconfig;

import java.util.List;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** Provider-neutral access to tool-call deltas before a chat framework aggregates them. */
public interface IncrementalToolCallChatModel {

    default boolean supportsIncrementalToolCallChunks() {
        return true;
    }

    Flux<Chunk> streamToolCallChunks(Prompt prompt);

    record Chunk(
            String text,
            List<ToolCallDelta> toolCalls,
            String finishReason,
            long promptTokens,
            long completionTokens) {
        public Chunk {
            text = text == null ? "" : text;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            finishReason = finishReason == null ? "" : finishReason;
        }
    }

    record ToolCallDelta(int index, String id, String name, String arguments) {
        public ToolCallDelta(String id, String name, String arguments) {
            this(0, id, name, arguments);
        }

        public ToolCallDelta {
            if (index < 0) throw new IllegalArgumentException("tool-call delta index must be non-negative");
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            arguments = arguments == null ? "" : arguments;
        }
    }
}
