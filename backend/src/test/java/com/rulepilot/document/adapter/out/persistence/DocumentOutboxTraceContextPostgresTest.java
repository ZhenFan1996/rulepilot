package com.rulepilot.document.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class DocumentOutboxTraceContextPostgresTest {

    private static final String TRACE_PARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

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

    @BeforeEach
    void clearOutbox() {
        jdbc.update("DELETE FROM outbox_event");
    }

    @Test
    void storesAValidW3cContextAndStillAcceptsLegacyEventsWithoutOne() {
        UUID traced = insert(TRACE_PARENT, "vendor=value");
        UUID legacy = insert(null, null);

        assertThat(jdbc.queryForMap(
                        "SELECT trace_parent, trace_state FROM outbox_event WHERE id = ?", traced))
                .containsEntry("trace_parent", TRACE_PARENT)
                .containsEntry("trace_state", "vendor=value");
        assertThat(jdbc.queryForMap(
                        "SELECT trace_parent, trace_state FROM outbox_event WHERE id = ?", legacy))
                .containsEntry("trace_parent", null)
                .containsEntry("trace_state", null);
    }

    @Test
    void rejectsAnUnrestorableParentOrAnOrphanTraceStateAtTheDatabaseBoundary() {
        assertThatThrownBy(() -> insert(
                        "00-" + "0".repeat(32) + "-00f067aa0ba902b7-01", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(TRACE_PARENT.toUpperCase(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(null, "vendor=value"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(TRACE_PARENT, "vendor=value\nprivate=leak"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(TRACE_PARENT, "vendor=值"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insert(String traceParent, String traceState) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO outbox_event (
                    id, aggregate_type, aggregate_id, event_type, payload, schema_version,
                    occurred_at, publish_attempts, next_attempt_at, trace_parent, trace_state)
                VALUES (?, 'DOCUMENT_VERSION', ?, 'DocumentProcessingRequested', CAST('{}' AS jsonb), 1,
                    ?, 0, ?, ?, ?)
                """,
                id,
                UUID.randomUUID(),
                Timestamp.from(Instant.parse("2026-08-25T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-08-25T00:00:00Z")),
                traceParent,
                traceState);
        return id;
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
