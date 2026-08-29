package com.rulepilot.document.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UploadedRulebookTeachingHandoffStore {

    Snapshot request(UUID handoffId, UUID documentVersionId, String ownerUsername, String learningGoal, Instant now);

    Optional<Snapshot> findOwned(UUID handoffId, String ownerUsername);

    Snapshot retry(UUID handoffId, UUID expectedPreparationRunId, String ownerUsername, Instant now);

    boolean dismissOwned(
            UUID handoffId,
            String ownerUsername,
            State expectedState,
            UUID expectedPreparationRunId);

    int dismissOwnedForDocumentVersion(UUID documentVersionId, String ownerUsername);

    List<Snapshot> findRecentOwned(String ownerUsername, int limit);

    List<Snapshot> claimReady(int limit, Instant now);

    List<Snapshot> claimReadyForDocument(UUID documentVersionId, int limit, Instant now);

    int failUnusableDocuments(Instant now);

    void completeLaunch(UUID handoffId, UUID preparationRunId, Instant now);

    void failLaunch(UUID handoffId, String errorCode, Instant now);

    int failInterruptedLaunches(Instant now);

    List<RecoveryCandidate> findUnreconciledLaunched(int limit);

    boolean failTerminal(UUID handoffId, UUID expectedPreparationRunId, String errorCode, Instant now);

    boolean markReconciled(UUID handoffId, UUID expectedPreparationRunId, Instant now);

    boolean dismissCancelled(UUID handoffId, UUID expectedPreparationRunId);

    enum State {
        WAITING_FOR_DOCUMENT,
        LAUNCHING,
        LAUNCHED,
        FAILED
    }

    record Snapshot(
            UUID id,
            UUID documentVersionId,
            String ownerUsername,
            String learningGoal,
            State state,
            UUID preparationRunId,
            String errorCode,
            int automaticRecoveryCount,
            Instant createdAt,
            Instant updatedAt) {
        public Snapshot {
            if (automaticRecoveryCount < 0) {
                throw new IllegalArgumentException("uploaded teaching recovery count is invalid");
            }
        }
    }

    record RecoveryCandidate(
            UUID id,
            UUID documentVersionId,
            String ownerUsername,
            UUID preparationRunId,
            int automaticRecoveryCount) {}
}
