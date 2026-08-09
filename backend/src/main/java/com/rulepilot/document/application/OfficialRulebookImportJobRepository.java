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

    List<OfficialRulebookImportJob> findRecentOwned(String ownerUsername, int limit);

    void updateProgress(UUID jobId, OfficialRulebookImportJob.Stage stage, long downloadedBytes, Long totalBytes, Instant now);

    void complete(UUID jobId, UUID documentVersionId, boolean duplicate, Instant now);

    void fail(UUID jobId, String errorCode, Instant now);

    int failInterrupted(Instant now);
}
