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
class PostgresBggPopularMetadataPrewarmProgressTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.2-pg17")
            .withDatabaseName("rulepilot")
            .withUsername("rulepilot")
            .withPassword("rulepilot-test");

    private static PostgresBggPopularMetadataPrewarmProgress progress;
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
        progress = new PostgresBggPopularMetadataPrewarmProgress(jdbc);
    }

    @BeforeEach
    void clearProgress() {
        jdbc.getJdbcTemplate().update("DELETE FROM bgg_metadata_prewarm_state");
    }

    @Test
    void claimsOneCrossProcessLeaseAndContinuesFromEachCompletedOffset() {
        Instant now = Instant.parse("2026-08-20T08:00:00Z");
        String snapshot = "a".repeat(64);
        var first = progress.claim(snapshot, 2_000, 500, 60, now, Duration.ofMinutes(30)).orElseThrow();

        assertThat(first.metadataStart()).isZero();
        assertThat(first.metadataEnd()).isEqualTo(500);
        assertThat(first.translationStart()).isZero();
        assertThat(first.translationEnd()).isEqualTo(60);
        assertThat(progress.claim(snapshot, 2_000, 500, 60, now, Duration.ofMinutes(30))).isEmpty();

        progress.complete(first, 500, 41, now.plusSeconds(120));
        var second = progress.claim(
                        snapshot, 2_000, 500, 60, now.plusSeconds(121), Duration.ofMinutes(30))
                .orElseThrow();
        assertThat(second.metadataStart()).isEqualTo(500);
        assertThat(second.metadataEnd()).isEqualTo(1_000);
        assertThat(second.translationStart()).isEqualTo(41);
        assertThat(second.translationEnd()).isEqualTo(101);
    }

    @Test
    void resetsBothOffsetsWhenTheRankedSnapshotChanges() {
        Instant now = Instant.parse("2026-08-20T08:00:00Z");
        var first = progress.claim("a".repeat(64), 2_000, 500, 60, now, Duration.ofMinutes(30)).orElseThrow();
        progress.complete(first, 500, 60, now.plusSeconds(1));

        var changed = progress.claim(
                        "b".repeat(64), 2_000, 500, 60, now.plusSeconds(2), Duration.ofMinutes(30))
                .orElseThrow();

        assertThat(changed.metadataStart()).isZero();
        assertThat(changed.translationStart()).isZero();
    }

    @Test
    void keepsAnExclusiveLeaseForHotGamesAfterRankedWorkFinishesOrTargetShrinks() {
        Instant now = Instant.parse("2026-08-20T08:00:00Z");
        String snapshot = "c".repeat(64);
        var first = progress.claim(snapshot, 20, 20, 20, now, Duration.ofMinutes(30)).orElseThrow();
        progress.complete(first, 20, 20, now.plusSeconds(1));

        var hot = progress.claim(snapshot, 10, 20, 20, now.plusSeconds(2), Duration.ofMinutes(30))
                .orElseThrow();
        assertThat(hot.metadataStart()).isEqualTo(20);
        assertThat(hot.metadataEnd()).isEqualTo(20);
        assertThat(hot.translationStart()).isEqualTo(20);
        assertThat(hot.translationEnd()).isEqualTo(20);
        assertThat(progress.claim(snapshot, 10, 20, 20, now.plusSeconds(3), Duration.ofMinutes(30)))
                .isEmpty();
        progress.complete(hot, 20, 20, now.plusSeconds(4));
        assertThat(progress.claim(snapshot, 10, 20, 20, now.plusSeconds(5), Duration.ofMinutes(30)))
                .isPresent();
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
