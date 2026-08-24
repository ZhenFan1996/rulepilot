package com.rulepilot.catalog.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Coordinates cover-format warming without rewinding metadata or translation progress. */
public interface BggCatalogCoverPrewarmProgress {

    Optional<CoverCohort> claim(
            String snapshotSha256,
            String formatVersion,
            int targetCount,
            int cohortSize,
            Instant claimedAt,
            Duration leaseDuration);

    void complete(CoverCohort cohort, int nextOffset, Instant completedAt);

    record CoverCohort(
            UUID leaseId,
            String snapshotSha256,
            String formatVersion,
            int start,
            int end) {
        public CoverCohort {
            if (leaseId == null
                    || snapshotSha256 == null
                    || snapshotSha256.isBlank()
                    || formatVersion == null
                    || formatVersion.isBlank()
                    || start < 0
                    || end < start) {
                throw new IllegalArgumentException("BGG cover prewarm cohort is invalid");
            }
        }
    }
}
