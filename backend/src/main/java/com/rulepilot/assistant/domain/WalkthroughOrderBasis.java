package com.rulepilot.assistant.domain;

/** Distinguishes rule-mandated timing from a presentation-only teaching sequence. */
public enum WalkthroughOrderBasis {
    RULE_ORDER,
    EXPLANATION_ORDER
}
