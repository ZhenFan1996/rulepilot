package com.rulepilot.document;

import java.util.List;
import java.util.UUID;

/** Persistent teaching intents created together with a player-uploaded rulebook. */
public interface UploadedRulebookTeachingHandoffs {

    List<ReadyHandoff> claimReady(int limit);

    List<ReadyHandoff> claimReadyForDocument(UUID documentVersionId, int limit);

    int failUnusableDocuments();

    void markLaunched(UUID handoffId, UUID preparationRunId);

    void markFailed(UUID handoffId, String errorCode);

    int failInterruptedLaunches();

    record ReadyHandoff(
            UUID handoffId,
            UUID documentVersionId,
            String ownerUsername,
            String learningGoal) {

        public ReadyHandoff {
            if (handoffId == null
                    || documentVersionId == null
                    || ownerUsername == null
                    || ownerUsername.isBlank()) {
                throw new IllegalArgumentException("uploaded rulebook teaching handoff is invalid");
            }
            ownerUsername = ownerUsername.strip();
            if (learningGoal != null) {
                learningGoal = learningGoal.strip();
                if (learningGoal.isBlank()) {
                    throw new IllegalArgumentException("uploaded rulebook teaching goal is invalid");
                }
            }
        }
    }
}
