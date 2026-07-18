package com.rulepilot.assistant;

public enum AssistantRunState {
    RECEIVED,
    DOCUMENT_READINESS,
    LESSON_PLANNING,
    QUESTION_UNDERSTANDING,
    NEED_CLARIFICATION,
    RETRIEVAL_PLANNING,
    RETRIEVING,
    VERIFYING_EVIDENCE,
    INSUFFICIENT_EVIDENCE,
    LESSON_COMPOSITION,
    ANSWER_COMPOSITION,
    MEDIA_PACKAGING,
    CRITIQUING,
    COMPLETED,
    FAILED,
    DEGRADED;

    public boolean terminal() {
        return this == INSUFFICIENT_EVIDENCE || this == COMPLETED || this == FAILED || this == DEGRADED;
    }
}
