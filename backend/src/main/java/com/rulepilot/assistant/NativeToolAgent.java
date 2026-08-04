package com.rulepilot.assistant;

import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import java.util.List;
import java.util.Set;

/** Executes one bounded observe-decide-act loop without publishing its output. */
public interface NativeToolAgent {

    RunResult run(RunRequest request);

    default String providerId(Role role, String ownerUsername) {
        return "native-tool-agent";
    }

    default boolean supports(Role role, String ownerUsername) {
        return true;
    }

    enum RunStatus {
        COMPLETED,
        FALLBACK
    }

    record RunRequest(
            Role role,
            ToolScope scope,
            String systemPrompt,
            String playerRequest,
            String fallbackText,
            int maxIterations,
            int maxOutputTokens,
            Set<String> allowedTools,
            Set<String> requiredToolsBeforeCompletion,
            int maxToolCalls) {
        public RunRequest(
                Role role,
                ToolScope scope,
                String systemPrompt,
                String playerRequest,
                String fallbackText,
                int maxIterations,
                int maxOutputTokens) {
            this(
                    role,
                    scope,
                    systemPrompt,
                    playerRequest,
                    fallbackText,
                    maxIterations,
                    maxOutputTokens,
                    Set.of(),
                    Set.of(),
                    Math.min(24, maxIterations * 4));
        }

        /** Compatibility constructor: an empty allow-list means every tool registered for the role. */
        public RunRequest(
                Role role,
                ToolScope scope,
                String systemPrompt,
                String playerRequest,
                String fallbackText,
                int maxIterations,
                int maxOutputTokens,
                Set<String> requiredToolsBeforeCompletion) {
            this(
                    role,
                    scope,
                    systemPrompt,
                    playerRequest,
                    fallbackText,
                    maxIterations,
                    maxOutputTokens,
                    Set.of(),
                    requiredToolsBeforeCompletion,
                    Math.min(24, maxIterations * 4));
        }

        public RunRequest {
            if (role == null || scope == null || blank(systemPrompt) || blank(playerRequest) || blank(fallbackText)
                    || maxIterations < 1 || maxIterations > 12 || maxOutputTokens < 1 || maxOutputTokens > 8192
                    || allowedTools == null
                    || requiredToolsBeforeCompletion == null
                    || maxToolCalls < 1 || maxToolCalls > 24
                    || allowedTools.stream().anyMatch(value -> value == null || value.isBlank())
                    || requiredToolsBeforeCompletion.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("native tool Agent request is invalid");
            }
            allowedTools = Set.copyOf(allowedTools);
            requiredToolsBeforeCompletion = Set.copyOf(requiredToolsBeforeCompletion);
            if (!allowedTools.isEmpty() && !allowedTools.containsAll(requiredToolsBeforeCompletion)) {
                throw new IllegalArgumentException("required native tools must be included in the allow-list");
            }
        }
    }

    record ObservationRecord(
            int iteration,
            String toolName,
            String schemaHash,
            ToolObservation observation) {
        public ObservationRecord {
            if (iteration < 1 || blank(toolName) || blank(schemaHash) || observation == null) {
                throw new IllegalArgumentException("native tool observation record is invalid");
            }
        }
    }

    record RunResult(
            RunStatus status,
            String text,
            String reason,
            int iterations,
            int toolCalls,
            List<ObservationRecord> observations) {
        public RunResult {
            if (status == null || blank(text) || blank(reason) || iterations < 0 || toolCalls < 0
                    || observations == null) {
                throw new IllegalArgumentException("native tool Agent result is invalid");
            }
            observations = List.copyOf(observations);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
