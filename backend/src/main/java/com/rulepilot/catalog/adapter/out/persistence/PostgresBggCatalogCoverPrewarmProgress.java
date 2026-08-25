package com.rulepilot.catalog.adapter.out.persistence;

import static com.rulepilot.catalog.application.BggPopularMetadataPrewarmProgress.MAX_COHORT_SIZE;
import static com.rulepilot.catalog.application.BggPopularMetadataPrewarmProgress.MAX_TARGET_COUNT;

import com.rulepilot.catalog.application.BggCatalogCoverPrewarmProgress;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("!test")
public class PostgresBggCatalogCoverPrewarmProgress implements BggCatalogCoverPrewarmProgress {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern FORMAT_VERSION = Pattern.compile("[a-z0-9][a-z0-9._-]{0,79}");

    private final NamedParameterJdbcTemplate jdbc;

    public PostgresBggCatalogCoverPrewarmProgress(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Optional<CoverCohort> claim(
            String snapshotSha256,
            String formatVersion,
            int targetCount,
            int cohortSize,
            Instant claimedAt,
            Duration leaseDuration) {
        checkedClaim(snapshotSha256, formatVersion, targetCount, cohortSize, claimedAt, leaseDuration);
        MapSqlParameterSource state = new MapSqlParameterSource()
                .addValue("snapshotSha256", snapshotSha256)
                .addValue("formatVersion", formatVersion)
                .addValue("claimedAt", Timestamp.from(claimedAt));
        jdbc.update(
                """
                INSERT INTO bgg_catalog_cover_prewarm_state (
                    singleton, snapshot_sha256, format_version, next_offset, updated_at)
                VALUES (TRUE, :snapshotSha256, :formatVersion, 0, :claimedAt)
                ON CONFLICT (singleton) DO UPDATE SET
                    snapshot_sha256 = EXCLUDED.snapshot_sha256,
                    format_version = EXCLUDED.format_version,
                    next_offset = 0,
                    lease_id = NULL,
                    lease_until = NULL,
                    updated_at = EXCLUDED.updated_at
                WHERE bgg_catalog_cover_prewarm_state.snapshot_sha256 <> EXCLUDED.snapshot_sha256
                   OR bgg_catalog_cover_prewarm_state.format_version <> EXCLUDED.format_version
                """,
                state);

        UUID leaseId = UUID.randomUUID();
        MapSqlParameterSource claim = new MapSqlParameterSource()
                .addValue("snapshotSha256", snapshotSha256)
                .addValue("formatVersion", formatVersion)
                .addValue("targetCount", targetCount)
                .addValue("cohortSize", cohortSize)
                .addValue("leaseId", leaseId)
                .addValue("claimedAt", Timestamp.from(claimedAt))
                .addValue("leaseUntil", Timestamp.from(claimedAt.plus(leaseDuration)));
        return jdbc.query(
                        """
                        UPDATE bgg_catalog_cover_prewarm_state
                        SET lease_id = :leaseId, lease_until = :leaseUntil, updated_at = :claimedAt
                        WHERE singleton
                          AND snapshot_sha256 = :snapshotSha256
                          AND format_version = :formatVersion
                          AND next_offset < :targetCount
                          AND (lease_until IS NULL OR lease_until <= :claimedAt)
                        RETURNING next_offset
                        """,
                        claim,
                        (result, row) -> {
                            int start = result.getInt("next_offset");
                            return new CoverCohort(
                                    leaseId,
                                    snapshotSha256,
                                    formatVersion,
                                    start,
                                    Math.min(targetCount, start + cohortSize));
                        })
                .stream()
                .findFirst();
    }

    @Override
    public void complete(CoverCohort cohort, int nextOffset, Instant completedAt) {
        if (cohort == null
                || completedAt == null
                || nextOffset < cohort.start()
                || nextOffset > cohort.end()) {
            throw new IllegalArgumentException("BGG cover prewarm completion is outside its claimed cohort");
        }
        jdbc.update(
                """
                UPDATE bgg_catalog_cover_prewarm_state
                SET next_offset = GREATEST(next_offset, :nextOffset),
                    lease_id = NULL,
                    lease_until = NULL,
                    updated_at = :completedAt
                WHERE singleton
                  AND snapshot_sha256 = :snapshotSha256
                  AND format_version = :formatVersion
                  AND lease_id = :leaseId
                """,
                new MapSqlParameterSource()
                        .addValue("snapshotSha256", cohort.snapshotSha256())
                        .addValue("formatVersion", cohort.formatVersion())
                        .addValue("leaseId", cohort.leaseId())
                        .addValue("nextOffset", nextOffset)
                        .addValue("completedAt", Timestamp.from(completedAt)));
    }

    private void checkedClaim(
            String snapshotSha256,
            String formatVersion,
            int targetCount,
            int cohortSize,
            Instant claimedAt,
            Duration leaseDuration) {
        if (snapshotSha256 == null || !SHA256.matcher(snapshotSha256).matches()) {
            throw new IllegalArgumentException("BGG cover prewarm requires a lowercase snapshot SHA-256 digest");
        }
        if (formatVersion == null || !FORMAT_VERSION.matcher(formatVersion).matches()) {
            throw new IllegalArgumentException("BGG cover prewarm format version is invalid");
        }
        if (targetCount < 1
                || targetCount > MAX_TARGET_COUNT
                || cohortSize < 1
                || cohortSize > MAX_COHORT_SIZE) {
            throw new IllegalArgumentException("BGG cover prewarm target and cohort size are invalid");
        }
        if (claimedAt == null
                || leaseDuration == null
                || leaseDuration.compareTo(Duration.ofMinutes(5)) < 0
                || leaseDuration.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException("BGG cover prewarm lease must be between five minutes and two hours");
        }
    }
}
