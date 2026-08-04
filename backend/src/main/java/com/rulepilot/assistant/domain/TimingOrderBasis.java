package com.rulepilot.assistant.domain;

/** The explicit rulebook relationship that determines how simultaneous effects are ordered. */
public enum TimingOrderBasis {
    CURRENT_PLAYER_CHOOSES,
    PRINTED_TOP_TO_BOTTOM,
    NORMAL_TURN_ORDER
}
