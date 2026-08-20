package com.rulepilot.document;

import java.util.List;
import java.util.UUID;

/**
 * Persistent handoffs whose imported document is ready for background teaching.
 *
 * <p>The document module owns the durable player intent and atomically claims ready work. The teaching module owns
 * creation of Assistant Runs and reports only the resulting run identity or a bounded failure code.</p>
 */
public interface RulebookTeachingHandoffs {

    List<ReadyHandoff> claimReady(int limit);

    List<ReadyHandoff> claimReadyForDocument(UUID documentVersionId, int limit);

    int failUnusableDocuments();

    void markLaunched(UUID importJobId, UUID preparationRunId);

    void markFailed(UUID importJobId, String errorCode);

    int failInterruptedLaunches();

    Reconciliation reconcileLaunched(int limit);

    record Reconciliation(int restarted, int settled, int exhausted) {
        public Reconciliation {
            if (restarted < 0 || settled < 0 || exhausted < 0) {
                throw new IllegalArgumentException("teaching handoff reconciliation counts are invalid");
            }
        }
    }

    record ReadyHandoff(
            UUID importJobId,
            UUID documentVersionId,
            String ownerUsername,
            String learningGoal) {

        public ReadyHandoff {
            if (importJobId == null || documentVersionId == null || ownerUsername == null || ownerUsername.isBlank()) {
                throw new IllegalArgumentException("ready rulebook teaching handoff is invalid");
            }
            ownerUsername = ownerUsername.strip();
            if (learningGoal != null) {
                learningGoal = learningGoal.strip();
                if (learningGoal.isBlank()) {
                    throw new IllegalArgumentException("ready rulebook teaching goal is invalid");
                }
            }
        }
    }
}
