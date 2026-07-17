package com.rulepilot.ingestion.application;

import java.util.Optional;
import java.util.UUID;

public interface ProcessingProgressStore {

    void save(UUID versionId, ProcessingProgressTracker.ProgressSnapshot progress);

    Optional<ProcessingProgressTracker.ProgressSnapshot> find(UUID versionId);
}
