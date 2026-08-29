package com.rulepilot.assistant;

import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import java.util.List;
import java.util.Set;

/** Executes one observe-decide-act loop without publishing its output. */
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
            Set<String> allowedTools,
            Set<String> requiredToolsBeforeCompletion,
            TerminalContract terminalContract) {
        public RunRequest {
            if (role == null || scope == null || blank(systemPrompt) || blank(playerRequest) || blank(fallbackText)
                    || allowedTools == null
                    || allowedTools.isEmpty()
                    || requiredToolsBeforeCompletion == null
                    || terminalContract == null
                    || allowedTools.stream().anyMatch(value -> value == null || value.isBlank())
                    || requiredToolsBeforeCompletion.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("native tool Agent request is invalid");
            }
            allowedTools = Set.copyOf(allowedTools);
            requiredToolsBeforeCompletion = Set.copyOf(requiredToolsBeforeCompletion);
            if (!allowedTools.containsAll(requiredToolsBeforeCompletion)) {
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
