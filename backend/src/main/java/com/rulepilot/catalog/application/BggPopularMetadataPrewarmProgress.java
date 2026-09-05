package com.rulepilot.catalog.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Coordinates worker leases, including hot-game refreshes after ranked hydration completes. */
public interface BggPopularMetadataPrewarmProgress {

    int MAX_TARGET_COUNT = 10_000;
    int MAX_COHORT_SIZE = 500;

    Optional<Cohort> claim(
            String snapshotSha256,
            int targetCount,
            int metadataCohortSize,
            Instant claimedAt,
            Duration leaseDuration);

    void complete(Cohort cohort, int metadataNextOffset, Instant completedAt);

    record Cohort(
            UUID leaseId,
            String snapshotSha256,
            int metadataStart,
            int metadataEnd) {
        public Cohort {
            if (leaseId == null
                    || snapshotSha256 == null
                    || snapshotSha256.isBlank()
                    || metadataStart < 0
                    || metadataEnd < metadataStart) {
                throw new IllegalArgumentException("BGG prewarm cohort is invalid");
            }
        }
    }
}
