package com.rulepilot.teaching.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class TeachingPlanSchemaMigrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.2-pg17")
            .withDatabaseName("rulepilot")
            .withUsername("rulepilot")
            .withPassword("rulepilot-test");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        enableProductionExtensions();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @Test
    void removesLegacyAudienceAndDurationColumnsFromTeachingPlans() {
        List<String> columns = jdbc.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'teaching_plan'
                """,
                String.class);

        assertThat(columns)
                .contains(
                        "id",
                        "document_version_id",
                        "learning_goal",
                        "game_title",
                        "premise",
                        "created_by",
                        "created_at")
                .doesNotContain("player_count", "beginner_count", "duration_minutes");
    }

    @Test
    void persistsARecommendedRulebookTeachingHandoffAcrossTheDownloadPipeline() {
        List<String> columns = jdbc.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'official_rulebook_import_job'
                """,
                String.class);
        assertThat(columns).contains(
                "teaching_handoff_state",
                "teaching_learning_goal",
                "teaching_preparation_run_id",
                "teaching_error_code",
                "teaching_handoff_updated_at");

        UUID jobId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-08-10T00:00:00Z");
        jdbc.update(
                """
                INSERT INTO official_rulebook_import_job (
                    id, owner_username, title, source_type, source_url, stage,
                    downloaded_bytes, teaching_handoff_state, teaching_learning_goal,
                    teaching_handoff_updated_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                jobId,
                "migration-player",
                "Persistent handoff rules",
                "BASE_RULEBOOK",
                "https://publisher.example/persistent-handoff.pdf",
                "COMPRESSING",
                52_428_800L,
                "WAITING_FOR_DOCUMENT",
                "Explain setup first",
                now,
                now,
                now);

        assertThat(jdbc.queryForObject(
                        "SELECT teaching_handoff_state FROM official_rulebook_import_job WHERE id = ?",
                        String.class,
                        jobId))
                .isEqualTo("WAITING_FOR_DOCUMENT");
    }

    @Test
    void addsANonNullStructuredExternalSourceLedgerToVisualPageFacts() {
        var column = jdbc.queryForMap(
                """
                SELECT data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'visual_rulebook_page_fact'
                  AND column_name = 'source_dependencies'
                """);

        assertThat(column)
                .containsEntry("data_type", "text")
                .containsEntry("is_nullable", "NO");
        assertThat(String.valueOf(column.get("column_default"))).contains("[]");
    }

    @Test
    void addsANonNullCompleteRuleGroupInventoryToVisualPageFacts() {
        var columns = jdbc.queryForList(
                """
                SELECT column_name, data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'visual_rulebook_page_fact'
                  AND column_name IN ('rule_group_identifiers', 'rule_group_inventory_complete')
                ORDER BY column_name
                """);

        assertThat(columns).hasSize(2);
        assertThat(columns.getFirst())
                .containsEntry("column_name", "rule_group_identifiers")
                .containsEntry("data_type", "text")
                .containsEntry("is_nullable", "NO");
        assertThat(String.valueOf(columns.getFirst().get("column_default"))).contains("[]");
        assertThat(columns.getLast())
                .containsEntry("column_name", "rule_group_inventory_complete")
                .containsEntry("data_type", "boolean")
                .containsEntry("is_nullable", "NO");
        assertThat(String.valueOf(columns.getLast().get("column_default"))).contains("false");
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
