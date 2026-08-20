package com.rulepilot.catalog.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Coordinates bounded catalog hydration across API and worker processes. */
public interface BggPopularMetadataPrewarmProgress {

    Optional<Cohort> claim(
            String snapshotSha256,
            int targetCount,
            int metadataCohortSize,
            int translationCohortSize,
            Instant claimedAt,
            Duration leaseDuration);

    void complete(Cohort cohort, int metadataNextOffset, int translationNextOffset, Instant completedAt);

    record Cohort(
            UUID leaseId,
            String snapshotSha256,
            int metadataStart,
            int metadataEnd,
            int translationStart,
            int translationEnd) {
        public Cohort {
            if (leaseId == null
                    || snapshotSha256 == null
                    || snapshotSha256.isBlank()
                    || metadataStart < 0
                    || metadataEnd < metadataStart
                    || translationStart < 0
                    || translationEnd < translationStart) {
                throw new IllegalArgumentException("BGG prewarm cohort is invalid");
            }
        }
    }
}
