package com.rulepilot.catalog.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class BggOfficialChineseAliasMigrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.2-pg17")
            .withDatabaseName("rulepilot")
            .withUsername("rulepilot")
            .withPassword("rulepilot-test");

    @Test
    void backfillsOfficialChineseNamesFromMetadataCachedBeforeTheAliasIndexExisted() {
        enableProductionExtensions();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("89"))
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        Instant cachedAt = Instant.parse("2026-08-07T08:00:00Z");
        insertCache(jdbc, "DISCOVERY", 10, "{\"chineseName\":\" 百变策略 \"}", cachedAt);
        insertCache(
                jdbc,
                "GAME",
                20,
                "{\"officialChineseNames\":[\"群岛竞逐\",\"群岛竞逐\",\"\"]}",
                cachedAt);
        insertCache(jdbc, "GAME", 30, "{\"officialChineseNames\":{\"legacy\":true}}", cachedAt);

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(jdbc.queryForList(
                        "SELECT bgg_id || ':' || alias FROM bgg_game_name_alias ORDER BY bgg_id", String.class))
                .containsExactly("10:百变策略", "20:群岛竞逐");
    }

    private static void insertCache(JdbcTemplate jdbc, String kind, int bggId, String payload, Instant cachedAt) {
        jdbc.update(
                """
                INSERT INTO bgg_metadata_cache (
                    cache_kind, bgg_id, payload, payload_bytes, cached_at,
                    fresh_until, stale_until, last_accessed_at)
                VALUES (?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?)
                """,
                kind,
                bggId,
                payload,
                payload.getBytes(StandardCharsets.UTF_8).length,
                Timestamp.from(cachedAt),
                Timestamp.from(cachedAt.plusSeconds(3_600)),
                Timestamp.from(cachedAt.plusSeconds(86_400)),
                Timestamp.from(cachedAt));
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
