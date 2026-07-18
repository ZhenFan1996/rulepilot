package com.rulepilot.assistant.domain;

public enum AnswerStatus {
    ANSWERED,
    CLARIFICATION_REQUIRED,
    INSUFFICIENT_EVIDENCE,
    MODEL_TIMEOUT,
    INVALID_MODEL_OUTPUT,
    VERSION_CONFLICT
}
