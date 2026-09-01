package com.rulepilot.catalog.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class BggMetadataTranslationMigrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.2-pg17")
            .withDatabaseName("rulepilot")
            .withUsername("rulepilot")
            .withPassword("rulepilot-test");

    @Test
    void preservesTheDeployedV105ChecksumAndLegacyWriteCompatibility() {
        enableProductionExtensions();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("105"))
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        assertThat(jdbc.queryForObject(
                        "SELECT checksum FROM flyway_schema_history WHERE version = '105'", Integer.class))
                .isEqualTo(1069875422);
        String payload = "{\"chineseName\":\"回填游戏\"}";
        Instant translatedAt = Instant.parse("2026-08-24T08:00:00Z");
        jdbc.update(
                """
                INSERT INTO bgg_metadata_translation (
                    bgg_id, source_sha256, locale, payload, payload_bytes, translated_at)
                VALUES (?, ?, 'zh-CN', CAST(? AS jsonb), ?, ?)
                """,
                104,
                "a".repeat(64),
                payload,
                payload.getBytes(StandardCharsets.UTF_8).length,
                Timestamp.from(translatedAt));

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(legacyColumns(jdbc))
                .containsExactly("bgg_id", "source_sha256", "locale", "payload", "payload_bytes", "translated_at");
        assertThat(primaryKeyColumns(jdbc)).containsExactly("bgg_id", "source_sha256");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT contract_version
                        FROM bgg_metadata_translation_versioned
                        WHERE bgg_id = ? AND locale = 'zh-CN' AND source_sha256 = ?
                        """,
                        Integer.class,
                        104,
                        "a".repeat(64)))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT payload::text
                        FROM bgg_metadata_translation_versioned
                        WHERE bgg_id = ? AND locale = 'zh-CN' AND contract_version = 4 AND source_sha256 = ?
                        """,
                        String.class,
                        104,
                        "a".repeat(64)))
                .isEqualTo("{\"chineseName\": \"回填游戏\"}");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT translated_at
                        FROM bgg_metadata_translation_versioned
                        WHERE bgg_id = ? AND locale = 'zh-CN' AND contract_version = 4 AND source_sha256 = ?
                        """,
                        Timestamp.class,
                        104,
                        "a".repeat(64)))
                .isEqualTo(Timestamp.from(translatedAt));
    }

    private static List<String> legacyColumns(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'bgg_metadata_translation'
                ORDER BY ordinal_position
                """,
                String.class);
    }

    private static List<String> primaryKeyColumns(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                """
                SELECT key_column_usage.column_name
                FROM information_schema.table_constraints
                JOIN information_schema.key_column_usage
                  ON key_column_usage.constraint_catalog = table_constraints.constraint_catalog
                 AND key_column_usage.constraint_schema = table_constraints.constraint_schema
                 AND key_column_usage.constraint_name = table_constraints.constraint_name
                WHERE table_constraints.table_schema = 'public'
                  AND table_constraints.table_name = 'bgg_metadata_translation'
                  AND table_constraints.constraint_type = 'PRIMARY KEY'
                ORDER BY key_column_usage.ordinal_position
                """,
                String.class);
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
