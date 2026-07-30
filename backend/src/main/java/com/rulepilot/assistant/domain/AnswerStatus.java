package com.rulepilot.assistant.domain;

public enum AnswerStatus {
    ANSWERED,
    ANSWERED_WITH_WARNING,
    CLARIFICATION_REQUIRED,
    INSUFFICIENT_EVIDENCE,
    MODEL_TIMEOUT,
    INVALID_MODEL_OUTPUT,
    VERSION_CONFLICT;

    public boolean publishesConclusion() {
        return this == ANSWERED || this == ANSWERED_WITH_WARNING;
    }
}
