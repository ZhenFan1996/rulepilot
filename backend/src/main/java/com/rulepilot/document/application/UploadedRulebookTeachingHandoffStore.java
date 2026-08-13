package com.rulepilot.document.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UploadedRulebookTeachingHandoffStore {

    Snapshot request(UUID handoffId, UUID documentVersionId, String ownerUsername, String learningGoal, Instant now);

    List<Snapshot> findRecentOwned(String ownerUsername, int limit);

    List<Snapshot> claimReady(int limit, Instant now);

    List<Snapshot> claimReadyForDocument(UUID documentVersionId, int limit, Instant now);

    int failUnusableDocuments(Instant now);

    void completeLaunch(UUID handoffId, UUID preparationRunId, Instant now);

    void failLaunch(UUID handoffId, String errorCode, Instant now);

    int failInterruptedLaunches(Instant now);

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
            Instant createdAt,
            Instant updatedAt) {}
}
