package com.rulepilot.assistant.domain;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record AssistantRun(
        UUID id,
        AssistantRunMode mode,
        UUID subjectId,
        String ownerUsername,
        AssistantRunState state,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        String lastErrorCode) {

    private static final Map<AssistantRunState, Set<AssistantRunState>> TEACHING_TRANSITIONS = Map.ofEntries(
            Map.entry(AssistantRunState.RECEIVED, Set.of(AssistantRunState.DOCUMENT_READINESS)),
            Map.entry(AssistantRunState.DOCUMENT_READINESS, Set.of(AssistantRunState.LESSON_PLANNING)),
            Map.entry(AssistantRunState.LESSON_PLANNING, Set.of(AssistantRunState.RETRIEVAL_PLANNING)),
            Map.entry(AssistantRunState.RETRIEVAL_PLANNING, Set.of(AssistantRunState.RETRIEVING)),
            Map.entry(AssistantRunState.RETRIEVING, Set.of(AssistantRunState.VERIFYING_EVIDENCE)),
            Map.entry(
                    AssistantRunState.VERIFYING_EVIDENCE,
                    Set.of(
                            AssistantRunState.RETRIEVAL_PLANNING,
                            AssistantRunState.INSUFFICIENT_EVIDENCE,
                            AssistantRunState.LESSON_COMPOSITION)),
            Map.entry(
                    AssistantRunState.LESSON_COMPOSITION,
                    Set.of(
                            AssistantRunState.MEDIA_PACKAGING,
                            AssistantRunState.CRITIQUING,
                            AssistantRunState.COMPLETED,
                            AssistantRunState.DEGRADED)),
            Map.entry(
                    AssistantRunState.MEDIA_PACKAGING,
                    Set.of(AssistantRunState.CRITIQUING, AssistantRunState.COMPLETED, AssistantRunState.DEGRADED)),
            Map.entry(
                    AssistantRunState.CRITIQUING,
                    Set.of(AssistantRunState.COMPLETED, AssistantRunState.DEGRADED)));

    private static final Map<AssistantRunState, Set<AssistantRunState>> TEACHING_PREPARATION_TRANSITIONS = Map.of(
            AssistantRunState.RECEIVED, Set.of(AssistantRunState.DOCUMENT_READINESS),
            AssistantRunState.DOCUMENT_READINESS, Set.of(AssistantRunState.LESSON_PLANNING),
            AssistantRunState.LESSON_PLANNING, Set.of(AssistantRunState.COMPLETED));

    private static final Map<AssistantRunState, Set<AssistantRunState>> QUESTION_TRANSITIONS = Map.ofEntries(
            Map.entry(AssistantRunState.RECEIVED, Set.of(AssistantRunState.QUESTION_UNDERSTANDING)),
            Map.entry(
                    AssistantRunState.QUESTION_UNDERSTANDING,
                    Set.of(AssistantRunState.NEED_CLARIFICATION, AssistantRunState.RETRIEVAL_PLANNING)),
            Map.entry(AssistantRunState.NEED_CLARIFICATION, Set.of(AssistantRunState.QUESTION_UNDERSTANDING)),
            Map.entry(AssistantRunState.RETRIEVAL_PLANNING, Set.of(AssistantRunState.RETRIEVING)),
            Map.entry(AssistantRunState.RETRIEVING, Set.of(AssistantRunState.VERIFYING_EVIDENCE)),
            Map.entry(
                    AssistantRunState.VERIFYING_EVIDENCE,
                    Set.of(
                            AssistantRunState.RETRIEVAL_PLANNING,
                            AssistantRunState.INSUFFICIENT_EVIDENCE,
                            AssistantRunState.ANSWER_COMPOSITION)),
            Map.entry(
                    AssistantRunState.ANSWER_COMPOSITION,
                    Set.of(AssistantRunState.CRITIQUING, AssistantRunState.COMPLETED, AssistantRunState.DEGRADED)),
            Map.entry(
                    AssistantRunState.CRITIQUING,
                    Set.of(AssistantRunState.COMPLETED, AssistantRunState.DEGRADED)));

    public AssistantRun {
        if (id == null || mode == null || subjectId == null || state == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("assistant run identity, mode, subject, state, and timestamps are required");
        }
        if (ownerUsername == null || ownerUsername.isBlank() || ownerUsername.length() > 120 || revision < 1) {
            throw new IllegalArgumentException("assistant run owner and revision are invalid");
        }
        if (state.terminal() != (completedAt != null)) {
            throw new IllegalArgumentException("assistant run completion timestamp does not match its state");
        }
        if ((state == AssistantRunState.FAILED) != (lastErrorCode != null)) {
            throw new IllegalArgumentException("assistant run failure code does not match its state");
        }
    }

    public static AssistantRun start(AssistantRunMode mode, UUID subjectId, String ownerUsername, Instant now) {
        if (ownerUsername == null) {
            throw new IllegalArgumentException("assistant run owner is required");
        }
        return new AssistantRun(
                UUID.randomUUID(), mode, subjectId, ownerUsername.strip(), AssistantRunState.RECEIVED,
                1, now, now, null, null);
    }

    public AssistantRun advance(AssistantRunState nextState, Instant now) {
        if (state.terminal()) {
            throw new IllegalStateException("a terminal assistant run cannot advance");
        }
        if (nextState == AssistantRunState.FAILED) {
            throw new IllegalArgumentException("use fail to enter the failed state");
        }
        Set<AssistantRunState> allowed = transitions().getOrDefault(state, Set.of());
        if (!allowed.contains(nextState)) {
            throw new IllegalStateException("cannot transition " + mode + " run from " + state + " to " + nextState);
        }
        return changed(nextState, now, null);
    }

    public AssistantRun fail(String errorCode, Instant now) {
        if (state.terminal()) {
            throw new IllegalStateException("a terminal assistant run cannot fail");
        }
        if (errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("assistant run error code is invalid");
        }
        return changed(AssistantRunState.FAILED, now, errorCode);
    }

    private AssistantRun changed(AssistantRunState nextState, Instant now, String errorCode) {
        if (now == null || now.isBefore(updatedAt)) {
            throw new IllegalArgumentException("assistant run transition timestamp is invalid");
        }
        return new AssistantRun(
                id, mode, subjectId, ownerUsername, nextState, revision + 1, createdAt, now,
                nextState.terminal() ? now : null, errorCode);
    }

    private Map<AssistantRunState, Set<AssistantRunState>> transitions() {
        return switch (mode) {
            case TEACHING_PREPARATION -> TEACHING_PREPARATION_TRANSITIONS;
            case TEACHING -> TEACHING_TRANSITIONS;
            case QUESTION_ANSWER -> QUESTION_TRANSITIONS;
        };
    }
}
