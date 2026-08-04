package com.rulepilot.assistant.domain;

/** The explicit, game-independent relationship that justifies a rule-priority conclusion. */
public enum RulePriorityBasis {
    EXPLICIT_OVERRIDE,
    IMPOSSIBILITY_PRIORITY,
    CONFLICT_ONLY_OVERRIDE
}
