package com.rulepilot.assistant;

import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import java.util.List;
import java.util.Map;
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

    enum TerminalStatus {
        EVIDENCE_READY,
        EVIDENCE_NOT_FOUND
    }

    record TerminalContract(Set<TerminalStatus> allowedStatuses) {
        public TerminalContract {
            if (allowedStatuses == null
                    || allowedStatuses.size() > 8
                    || allowedStatuses.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("native tool Agent terminal contract is invalid");
            }
            allowedStatuses = Set.copyOf(allowedStatuses);
        }

        public static TerminalContract none() {
            return new TerminalContract(Set.of());
        }

        public static TerminalContract exact(TerminalStatus status) {
            return new TerminalContract(Set.of(status));
        }

        public static TerminalContract evidenceReview() {
            return new TerminalContract(Set.of(TerminalStatus.EVIDENCE_READY, TerminalStatus.EVIDENCE_NOT_FOUND));
        }

        public boolean required() {
            return !allowedStatuses.isEmpty();
        }
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
            int maxToolCalls,
            TerminalContract terminalContract,
            Map<String, Integer> finalResponseAfterToolSuccesses,
            boolean completeAfterRequiredTools) {
        public RunRequest {
            if (role == null || scope == null || blank(systemPrompt) || blank(playerRequest) || blank(fallbackText)
                    || maxIterations < 1 || maxIterations > 12 || maxOutputTokens < 1 || maxOutputTokens > 8192
                    || allowedTools == null
                    || allowedTools.isEmpty()
                    || requiredToolsBeforeCompletion == null
                    || maxToolCalls < 1 || maxToolCalls > 24
                    || terminalContract == null
                    || finalResponseAfterToolSuccesses == null
                    || allowedTools.stream().anyMatch(value -> value == null || value.isBlank())
                    || requiredToolsBeforeCompletion.stream().anyMatch(value -> value == null || value.isBlank())
                    || finalResponseAfterToolSuccesses.entrySet().stream().anyMatch(entry ->
                            entry.getKey() == null
                                    || entry.getKey().isBlank()
                                    || entry.getValue() == null
                                    || entry.getValue() < 1
                                    || entry.getValue() > maxToolCalls)) {
                throw new IllegalArgumentException("native tool Agent request is invalid");
            }
            allowedTools = Set.copyOf(allowedTools);
            requiredToolsBeforeCompletion = Set.copyOf(requiredToolsBeforeCompletion);
            finalResponseAfterToolSuccesses = Map.copyOf(finalResponseAfterToolSuccesses);
            if (!allowedTools.containsAll(requiredToolsBeforeCompletion)) {
                throw new IllegalArgumentException("required native tools must be included in the allow-list");
            }
            if (!allowedTools.containsAll(finalResponseAfterToolSuccesses.keySet())) {
                throw new IllegalArgumentException("final-response native tools must be included in the allow-list");
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
            List<ObservationRecord> observations,
            TerminalStatus terminalStatus) {
        public RunResult(
                RunStatus status,
                String text,
                String reason,
                int iterations,
                int toolCalls,
                List<ObservationRecord> observations) {
            this(status, text, reason, iterations, toolCalls, observations, null);
        }

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
