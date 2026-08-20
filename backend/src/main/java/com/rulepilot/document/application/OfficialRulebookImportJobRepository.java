package com.rulepilot.document.application;

import com.rulepilot.document.domain.OfficialRulebookImportJob;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfficialRulebookImportJobRepository {

    void insert(OfficialRulebookImportJob job);

    Optional<OfficialRulebookImportJob> findOwned(UUID jobId, String ownerUsername);

    Optional<OfficialRulebookImportJob> findActiveOwnedBySource(String ownerUsername, String sourceUrl);

    Optional<OfficialRulebookImportJob> findLatestOwnedBySource(String ownerUsername, String sourceUrl);

    Optional<OfficialRulebookImportJob> findCompletedOwnedBySourceAndEdition(
            String ownerUsername, String sourceUrl, UUID editionId);

    List<OfficialRulebookImportJob> findRecentOwned(String ownerUsername, int limit);

    List<OfficialRulebookImportJob> findUnreconciledLaunchedTeaching(int limit);

    void recordReuse(UUID jobId, Instant now);

    void requestTeaching(UUID jobId, String learningGoal, Instant now);

    boolean retryTeaching(UUID jobId, UUID expectedPreparationRunId, Instant now);

    boolean retryTeachingAutomatically(UUID jobId, UUID expectedPreparationRunId, Instant now);

    boolean failTeachingRecoveryExhausted(UUID jobId, UUID expectedPreparationRunId, Instant now);

    boolean failTeachingTerminal(
            UUID jobId, UUID expectedPreparationRunId, String errorCode, Instant now);

    boolean markTeachingReconciled(UUID jobId, UUID expectedPreparationRunId, Instant now);

    boolean dismissTeaching(
            UUID jobId,
            String ownerUsername,
            OfficialRulebookImportJob.TeachingHandoffState expectedState,
            UUID expectedPreparationRunId,
            Instant now);

    int dismissTeachingForDocumentVersion(UUID documentVersionId, String ownerUsername, Instant now);

    List<OfficialRulebookImportJob> claimReadyTeaching(int limit, Instant now);

    List<OfficialRulebookImportJob> claimReadyTeachingForDocument(
            UUID documentVersionId, int limit, Instant now);

    int failTeachingForUnusableDocuments(Instant now);

    void completeTeachingLaunch(UUID jobId, UUID preparationRunId, Instant now);

    void failTeachingLaunch(UUID jobId, String errorCode, Instant now);

    int failInterruptedTeachingLaunches(Instant now);

    void markDownloadCompleted(UUID jobId, Instant now);

    void updateProgress(UUID jobId, OfficialRulebookImportJob.Stage stage, long downloadedBytes, Long totalBytes, Instant now);

    void complete(UUID jobId, UUID documentVersionId, boolean duplicate, Instant now);

    void fail(UUID jobId, String errorCode, Instant now);

    int failInterrupted(Instant now);
}
