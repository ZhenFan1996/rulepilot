package com.rulepilot.teaching.application;

import java.util.List;

/**
 * Owns one bounded structured-output repair only.
 *
 * <p>Rule meaning belongs to the Teaching model and the independent semantic Critic. This policy deliberately does
 * not inspect, append, delete, or rewrite player-facing rule prose.</p>
 */
final class TeachingDraftRecoveryPolicy {

    private static final int MAX_SCHEMA_REPAIR_ATTEMPTS = 1;

    int maxRepairAttempts() {
        return MAX_SCHEMA_REPAIR_ATTEMPTS;
    }

    List<String> repairFeedback(String diagnostic) {
        return List.of(diagnostic);
    }

}
