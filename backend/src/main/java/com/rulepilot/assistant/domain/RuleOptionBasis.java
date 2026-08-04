package com.rulepilot.assistant.domain;

/** The rulebook relationship that makes several cited choices a complete option set. */
public enum RuleOptionBasis {
    SOURCE_SELECTION,
    TIMING_CATALOG,
    ALTERNATIVE_ACTION,
    EXCLUSIVE_CHOICE
}
