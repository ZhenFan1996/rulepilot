package com.rulepilot.catalog.adapter.out.persistence;

import static com.rulepilot.catalog.application.BggPopularMetadataPrewarmProgress.MAX_COHORT_SIZE;
import static com.rulepilot.catalog.application.BggPopularMetadataPrewarmProgress.MAX_TARGET_COUNT;

import com.rulepilot.catalog.application.BggPopularMetadataPrewarmProgress;
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
public class PostgresBggPopularMetadataPrewarmProgress implements BggPopularMetadataPrewarmProgress {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final NamedParameterJdbcTemplate jdbc;

    public PostgresBggPopularMetadataPrewarmProgress(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Optional<Cohort> claim(
            String snapshotSha256,
            int targetCount,
            int metadataCohortSize,
            int translationCohortSize,
            Instant claimedAt,
            Duration leaseDuration) {
        checkedClaim(
                snapshotSha256,
                targetCount,
                metadataCohortSize,
                translationCohortSize,
                claimedAt,
                leaseDuration);
        MapSqlParameterSource state = new MapSqlParameterSource()
                .addValue("snapshotSha256", snapshotSha256)
                .addValue("claimedAt", Timestamp.from(claimedAt));
        jdbc.update(
                """
                INSERT INTO bgg_metadata_prewarm_state (
                    singleton, snapshot_sha256, metadata_next_offset, translation_next_offset, updated_at)
                VALUES (TRUE, :snapshotSha256, 0, 0, :claimedAt)
                ON CONFLICT (singleton) DO UPDATE SET
                    snapshot_sha256 = EXCLUDED.snapshot_sha256,
                    metadata_next_offset = 0,
                    translation_next_offset = 0,
                    lease_id = NULL,
                    lease_until = NULL,
                    updated_at = EXCLUDED.updated_at
                WHERE bgg_metadata_prewarm_state.snapshot_sha256 <> EXCLUDED.snapshot_sha256
                """,
                state);

        UUID leaseId = UUID.randomUUID();
        MapSqlParameterSource claim = new MapSqlParameterSource()
                .addValue("snapshotSha256", snapshotSha256)
                .addValue("leaseId", leaseId)
                .addValue("claimedAt", Timestamp.from(claimedAt))
                .addValue("leaseUntil", Timestamp.from(claimedAt.plus(leaseDuration)));
        return jdbc.query(
                        """
                        UPDATE bgg_metadata_prewarm_state
                        SET lease_id = :leaseId, lease_until = :leaseUntil, updated_at = :claimedAt
                        WHERE singleton
                          AND snapshot_sha256 = :snapshotSha256
                          AND (lease_until IS NULL OR lease_until <= :claimedAt)
                        RETURNING metadata_next_offset, translation_next_offset
                        """,
                        claim,
                        (result, row) -> {
                            int metadataStart = result.getInt("metadata_next_offset");
                            int translationStart = result.getInt("translation_next_offset");
                            return new Cohort(
                                    leaseId,
                                    snapshotSha256,
                                    metadataStart,
                                    Math.max(metadataStart, Math.min(targetCount, metadataStart + metadataCohortSize)),
                                    translationStart,
                                    Math.max(translationStart, Math.min(targetCount, translationStart + translationCohortSize)));
                        })
                .stream()
                .findFirst();
    }

    @Override
    public void complete(Cohort cohort, int metadataNextOffset, int translationNextOffset, Instant completedAt) {
        if (cohort == null
                || completedAt == null
                || metadataNextOffset < cohort.metadataStart()
                || metadataNextOffset > cohort.metadataEnd()
                || translationNextOffset < cohort.translationStart()
                || translationNextOffset > cohort.translationEnd()) {
            throw new IllegalArgumentException("BGG prewarm completion is outside its claimed cohort");
        }
        jdbc.update(
                """
                UPDATE bgg_metadata_prewarm_state
                SET metadata_next_offset = GREATEST(metadata_next_offset, :metadataNextOffset),
                    translation_next_offset = GREATEST(translation_next_offset, :translationNextOffset),
                    lease_id = NULL,
                    lease_until = NULL,
                    updated_at = :completedAt
                WHERE singleton AND snapshot_sha256 = :snapshotSha256 AND lease_id = :leaseId
                """,
                new MapSqlParameterSource()
                        .addValue("snapshotSha256", cohort.snapshotSha256())
                        .addValue("leaseId", cohort.leaseId())
                        .addValue("metadataNextOffset", metadataNextOffset)
                        .addValue("translationNextOffset", translationNextOffset)
                        .addValue("completedAt", Timestamp.from(completedAt)));
    }

    private void checkedClaim(
            String snapshotSha256,
            int targetCount,
            int metadataCohortSize,
            int translationCohortSize,
            Instant claimedAt,
            Duration leaseDuration) {
        if (snapshotSha256 == null || !SHA256.matcher(snapshotSha256).matches()) {
            throw new IllegalArgumentException("BGG prewarm requires a lowercase snapshot SHA-256 digest");
        }
        if (targetCount < 1
                || targetCount > MAX_TARGET_COUNT
                || metadataCohortSize < 1
                || metadataCohortSize > MAX_COHORT_SIZE
                || translationCohortSize < 1
                || translationCohortSize > MAX_COHORT_SIZE) {
            throw new IllegalArgumentException("BGG prewarm target and cohort sizes are invalid");
        }
        if (claimedAt == null
                || leaseDuration == null
                || leaseDuration.compareTo(Duration.ofMinutes(5)) < 0
                || leaseDuration.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException("BGG prewarm lease must be between five minutes and two hours");
        }
    }
}
