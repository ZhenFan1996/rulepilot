package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import java.util.ArrayList;
import java.util.List;

/** Sequences only phases the answer workflow recorded as reached; persistence remains lifecycle-owned. */
final class AnswerRunProgressPolicy {

    private AnswerRunProgressPolicy() {}

    static List<ProgressUpdate> updatesFor(StructuredRuleAnswer answer, ExecutionPhase lastReachedPhase) {
        if (answer == null || lastReachedPhase == null) {
            throw new IllegalArgumentException("answer progress outcome is required");
        }
        List<ProgressUpdate> updates = new ArrayList<>();
        addReachedPhases(updates, lastReachedPhase);
        if (answer.status() == AnswerStatus.CLARIFICATION_REQUIRED) {
            updates.add(new ProgressUpdate(AssistantRunState.NEED_CLARIFICATION, "Question requires additional context"));
            return List.copyOf(updates);
        }
        if (answer.status() == AnswerStatus.INSUFFICIENT_EVIDENCE || answer.status() == AnswerStatus.VERSION_CONFLICT) {
            updates.add(new ProgressUpdate(AssistantRunState.INSUFFICIENT_EVIDENCE, "Answer evidence is insufficient"));
            return List.copyOf(updates);
        }
        if (!answer.status().publishesConclusion()) {
            updates.add(new ProgressUpdate(AssistantRunState.DEGRADED, "Answer generation degraded safely"));
            return List.copyOf(updates);
        }
        updates.add(new ProgressUpdate(
                answer.status() == AnswerStatus.ANSWERED_WITH_WARNING
                        ? AssistantRunState.DEGRADED
                        : AssistantRunState.COMPLETED,
                answer.status() == AnswerStatus.ANSWERED_WITH_WARNING
                        ? "Evidence-scoped answer completed with player-visible warnings"
                        : "Question workflow completed"));
        return List.copyOf(updates);
    }

    private static void addReachedPhases(List<ProgressUpdate> updates, ExecutionPhase lastReachedPhase) {
        if (lastReachedPhase.reached(ExecutionPhase.QUESTION_UNDERSTANDING)) {
            updates.add(new ProgressUpdate(
                    AssistantRunState.QUESTION_UNDERSTANDING, "Question context is normalized"));
        }
        if (lastReachedPhase.reached(ExecutionPhase.RETRIEVAL_PLANNING)) {
            updates.add(new ProgressUpdate(
                    AssistantRunState.RETRIEVAL_PLANNING, "Answer evidence scope is planned"));
        }
        if (lastReachedPhase.reached(ExecutionPhase.RETRIEVING)) {
            updates.add(new ProgressUpdate(
                    AssistantRunState.RETRIEVING, "Allow-listed answer source lookup completed"));
        }
        if (lastReachedPhase.reached(ExecutionPhase.VERIFYING_EVIDENCE)) {
            updates.add(new ProgressUpdate(
                    AssistantRunState.VERIFYING_EVIDENCE, "Answer source scope is policy checked"));
        }
        if (lastReachedPhase.reached(ExecutionPhase.ANSWER_COMPOSITION)) {
            updates.add(new ProgressUpdate(
                    AssistantRunState.ANSWER_COMPOSITION, "Structured cited answer composition was attempted"));
        }
        if (lastReachedPhase.reached(ExecutionPhase.CRITIQUING)) {
            updates.add(new ProgressUpdate(
                    AssistantRunState.CRITIQUING, "Optional answer review was attempted"));
        }
    }

    enum ExecutionPhase {
        RECEIVED,
        QUESTION_UNDERSTANDING,
        RETRIEVAL_PLANNING,
        RETRIEVING,
        VERIFYING_EVIDENCE,
        ANSWER_COMPOSITION,
        CRITIQUING;

        boolean reached(ExecutionPhase phase) {
            return ordinal() >= phase.ordinal();
        }
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
