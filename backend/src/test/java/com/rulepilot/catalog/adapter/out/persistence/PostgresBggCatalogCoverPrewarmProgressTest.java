package com.rulepilot.catalog.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresBggCatalogCoverPrewarmProgressTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.2-pg17")
            .withDatabaseName("rulepilot")
            .withUsername("rulepilot")
            .withPassword("rulepilot-test");

    private static PostgresBggCatalogCoverPrewarmProgress progress;
    private static NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        enableProductionExtensions();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        progress = new PostgresBggCatalogCoverPrewarmProgress(jdbc);
    }

    @BeforeEach
    void clearProgress() {
        jdbc.getJdbcTemplate().update("DELETE FROM bgg_catalog_cover_prewarm_state");
        jdbc.getJdbcTemplate().update("DELETE FROM bgg_metadata_prewarm_state");
    }

    @Test
    void advancesAnIndependentCoverLeaseBySnapshotAndFormatVersion() {
        Instant now = Instant.parse("2026-08-25T08:00:00Z");
        String snapshot = "a".repeat(64);
        var first = progress.claim(
                        snapshot, "catalog-cover-v3", 2_000, 500, now, Duration.ofMinutes(30))
                .orElseThrow();

        assertThat(first.start()).isZero();
        assertThat(first.end()).isEqualTo(500);
        assertThat(progress.claim(
                        snapshot, "catalog-cover-v3", 2_000, 500, now, Duration.ofMinutes(30)))
                .isEmpty();

        progress.complete(first, 417, now.plusSeconds(120));
        var second = progress.claim(
                        snapshot, "catalog-cover-v3", 2_000, 500, now.plusSeconds(121), Duration.ofMinutes(30))
                .orElseThrow();
        assertThat(second.start()).isEqualTo(417);
        assertThat(second.end()).isEqualTo(917);
    }

    @Test
    void aCoverFormatChangeResetsOnlyCoverProgressAndNeverMetadataOrTranslation() {
        Instant now = Instant.parse("2026-08-25T08:00:00Z");
        jdbc.getJdbcTemplate().update(
                """
                INSERT INTO bgg_metadata_prewarm_state (
                    singleton, snapshot_sha256, metadata_next_offset, translation_next_offset, updated_at)
                VALUES (TRUE, ?, 800, 120, ?)
                """,
                "b".repeat(64),
                java.sql.Timestamp.from(now));
        var first = progress.claim(
                        "b".repeat(64), "catalog-cover-v3", 2_000, 500, now, Duration.ofMinutes(30))
                .orElseThrow();
        progress.complete(first, 500, now.plusSeconds(1));
        var oldFormatLease = progress.claim(
                        "b".repeat(64),
                        "catalog-cover-v3",
                        2_000,
                        500,
                        now.plusSeconds(2),
                        Duration.ofMinutes(30))
                .orElseThrow();

        var reformatted = progress.claim(
                        "b".repeat(64),
                        "catalog-cover-v4",
                        2_000,
                        500,
                        now.plusSeconds(3),
                        Duration.ofMinutes(30))
                .orElseThrow();
        progress.complete(oldFormatLease, 900, now.plusSeconds(4));

        assertThat(reformatted.start()).isZero();
        assertThat(jdbc.getJdbcTemplate().queryForMap(
                        "SELECT format_version, next_offset, lease_id FROM bgg_catalog_cover_prewarm_state"))
                .containsEntry("format_version", "catalog-cover-v4")
                .containsEntry("next_offset", 0)
                .containsEntry("lease_id", reformatted.leaseId());
        assertThat(jdbc.getJdbcTemplate().queryForMap(
                        "SELECT metadata_next_offset, translation_next_offset FROM bgg_metadata_prewarm_state"))
                .containsEntry("metadata_next_offset", 800)
                .containsEntry("translation_next_offset", 120);
    }

    private static void enableProductionExtensions() {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize the production PostgreSQL extensions", exception);
        }
    }
}
