package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import java.util.ArrayList;
import java.util.List;

/** Maps the one native Agent run to its public lifecycle without inventing retired model stages. */
final class AnswerRunProgressPolicy {

    private AnswerRunProgressPolicy() {}

    static List<ProgressUpdate> updatesFor(StructuredRuleAnswer answer, ExecutionPhase lastReachedPhase) {
        if (answer == null || lastReachedPhase == null) {
            throw new IllegalArgumentException("answer progress outcome is required");
        }
        List<ProgressUpdate> updates = new ArrayList<>();
        if (lastReachedPhase == ExecutionPhase.AGENT_RUNNING) {
            updates.add(new ProgressUpdate(
                    AssistantRunState.ANSWER_COMPOSITION,
                    "The answer Agent completed its model decisions and read-only tool actions"));
        }
        if (answer.status() == AnswerStatus.CLARIFICATION_REQUIRED) {
            updates.add(new ProgressUpdate(AssistantRunState.NEED_CLARIFICATION, "Question requires additional context"));
            return List.copyOf(updates);
        }
        if (answer.status() == AnswerStatus.INSUFFICIENT_EVIDENCE || answer.status() == AnswerStatus.VERSION_CONFLICT) {
            updates.add(new ProgressUpdate(AssistantRunState.INSUFFICIENT_EVIDENCE, "Answer evidence is insufficient"));
            return List.copyOf(updates);
        }
        if (!answer.status().publishesConclusion()) {
            updates.add(new ProgressUpdate(
                    AssistantRunState.DEGRADED,
                    "The answer Agent stopped at its typed execution or publication boundary"));
            return List.copyOf(updates);
        }
        updates.add(new ProgressUpdate(
                answer.status() == AnswerStatus.ANSWERED_WITH_WARNING
                        ? AssistantRunState.DEGRADED
                        : AssistantRunState.COMPLETED,
                answer.status() == AnswerStatus.ANSWERED_WITH_WARNING
                        ? "The answer Agent completed with player-visible warnings"
                        : "The answer Agent published its validated terminal response"));
        return List.copyOf(updates);
    }

    enum ExecutionPhase {
        RECEIVED,
        AGENT_RUNNING
    }

    static final class Tracker {
        private ExecutionPhase phase = ExecutionPhase.RECEIVED;

        void reached(ExecutionPhase reached) {
            if (reached == null) throw new IllegalArgumentException("answer execution phase is required");
            phase = reached;
        }

        ExecutionPhase phase() {
            return phase;
        }
    }

    record ProgressUpdate(AssistantRunState state, String summary) {}
}
