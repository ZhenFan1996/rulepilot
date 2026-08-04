package com.rulepilot.assistant.domain;

/** The explicit rulebook mechanism used to resolve a tie. */
public enum TieResolutionBasis {
    SINGLE_TIEBREAKER,
    ORDERED_TIEBREAKERS,
    RANK_REWARD_SHIFT,
    POSITIONAL_PRIORITY
}
