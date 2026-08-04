package com.rulepilot.assistant.domain;

/** Whether the current player question explicitly establishes one evidence-backed rule requirement. */
public enum SituationCheckStatus {
    CONFIRMED,
    CONTRADICTED,
    NOT_PROVIDED
}
