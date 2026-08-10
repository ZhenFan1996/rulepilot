package com.rulepilot.document.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JpaOfficialRulebookImportJobRepositoryPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.2-pg17")
            .withDatabaseName("rulepilot")
            .withUsername("rulepilot")
            .withPassword("rulepilot-test");

    private static JdbcTemplate jdbc;
    private static StandardServiceRegistry registry;
    private static SessionFactory sessionFactory;

    @BeforeAll
    static void migrateAndStartHibernate() {
        enableProductionExtensions();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        registry = new StandardServiceRegistryBuilder()
                .applySetting("jakarta.persistence.jdbc.driver", "org.postgresql.Driver")
                .applySetting("jakarta.persistence.jdbc.url", POSTGRES.getJdbcUrl())
                .applySetting("jakarta.persistence.jdbc.user", POSTGRES.getUsername())
                .applySetting("jakarta.persistence.jdbc.password", POSTGRES.getPassword())
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .build();
        sessionFactory = new MetadataSources(registry)
                .addAnnotatedClass(OfficialRulebookImportJobEntity.class)
                .buildMetadata()
                .buildSessionFactory();
    }

    @AfterAll
    static void stopHibernate() {
        if (sessionFactory != null) sessionFactory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    @BeforeEach
    void clearImportJobs() {
        jdbc.update("DELETE FROM official_rulebook_import_job");
    }

    @Test
    void failsAnImportWithoutLosingTheTypedTeachingHandoffTimestampBranches() {
        Instant createdAt = Instant.parse("2026-08-10T00:00:00Z");
        Instant failedAt = Instant.parse("2026-08-10T00:05:00Z");
        UUID ordinaryJob = insertJob("NOT_REQUESTED", null, createdAt);
        UUID teachingJob = insertJob("WAITING_FOR_DOCUMENT", createdAt, createdAt);

        inTransaction(repository -> {
            repository.fail(ordinaryJob, "DOWNLOAD_FAILED", failedAt);
            repository.fail(teachingJob, "DOWNLOAD_FAILED", failedAt);
        });

        assertFailedImport(
                ordinaryJob, "DOWNLOAD_FAILED", "NOT_REQUESTED", null, null, failedAt);
        assertFailedImport(
                teachingJob,
                "DOWNLOAD_FAILED",
                "FAILED",
                "IMPORT_FAILED",
                failedAt,
                failedAt);
    }

    @Test
    void recoversInterruptedImportsAcrossBothTeachingHandoffTimestampBranches() {
        Instant createdAt = Instant.parse("2026-08-10T01:00:00Z");
        Instant recoveredAt = Instant.parse("2026-08-10T01:05:00Z");
        UUID ordinaryJob = insertJob("NOT_REQUESTED", null, createdAt);
        UUID teachingJob = insertJob("WAITING_FOR_DOCUMENT", createdAt, createdAt);

        int updated = inTransactionReturning(repository -> repository.failInterrupted(recoveredAt));

        assertThat(updated).isEqualTo(2);
        assertFailedImport(
                ordinaryJob, "APPLICATION_RESTARTED", "NOT_REQUESTED", null, null, recoveredAt);
        assertFailedImport(
                teachingJob,
                "APPLICATION_RESTARTED",
                "FAILED",
                "APPLICATION_RESTARTED",
                recoveredAt,
                recoveredAt);
    }

    private static UUID insertJob(String teachingState, Instant teachingUpdatedAt, Instant createdAt) {
        UUID jobId = UUID.randomUUID();
        OffsetDateTime created = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO official_rulebook_import_job (
                    id, owner_username, title, source_type, source_url, stage,
                    downloaded_bytes, teaching_handoff_state, teaching_handoff_updated_at,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                jobId,
                "postgres-regression-player",
                "Typed timestamp branches",
                "BASE_RULEBOOK",
                "https://publisher.example/" + jobId + ".pdf",
                "DOWNLOADING",
                512L,
                teachingState,
                teachingUpdatedAt == null ? null : OffsetDateTime.ofInstant(teachingUpdatedAt, ZoneOffset.UTC),
                created,
                created);
        return jobId;
    }

    private static void assertFailedImport(
            UUID jobId,
            String importError,
            String teachingState,
            String teachingError,
            Instant teachingUpdatedAt,
            Instant failedAt) {
        FailedImportRow row = jdbc.queryForObject(
                """
                SELECT stage, error_code, teaching_handoff_state, teaching_error_code,
                       teaching_handoff_updated_at, updated_at, completed_at
                FROM official_rulebook_import_job
                WHERE id = ?
                """,
                (resultSet, rowNumber) -> new FailedImportRow(
                        resultSet.getString("stage"),
                        resultSet.getString("error_code"),
                        resultSet.getString("teaching_handoff_state"),
                        resultSet.getString("teaching_error_code"),
                        instant(resultSet.getTimestamp("teaching_handoff_updated_at")),
                        instant(resultSet.getTimestamp("updated_at")),
                        instant(resultSet.getTimestamp("completed_at"))),
                jobId);
        assertThat(row).isEqualTo(new FailedImportRow(
                "FAILED", importError, teachingState, teachingError, teachingUpdatedAt, failedAt, failedAt));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void inTransaction(RepositoryWork work) {
        inTransactionReturning(repository -> {
            work.run(repository);
            return null;
        });
    }

    private static <T> T inTransactionReturning(RepositoryResult<T> work) {
        EntityManager entityManager = sessionFactory.createEntityManager();
        JpaOfficialRulebookImportJobRepository repository = new JpaOfficialRulebookImportJobRepository();
        ReflectionTestUtils.setField(repository, "entityManager", entityManager);
        var transaction = entityManager.getTransaction();
        try {
            transaction.begin();
            T result = work.run(repository);
            transaction.commit();
            return result;
        } catch (RuntimeException exception) {
            if (transaction.isActive()) transaction.rollback();
            throw exception;
        } finally {
            entityManager.close();
        }
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

    @FunctionalInterface
    private interface RepositoryWork {
        void run(JpaOfficialRulebookImportJobRepository repository);
    }

    @FunctionalInterface
    private interface RepositoryResult<T> {
        T run(JpaOfficialRulebookImportJobRepository repository);
    }

    private record FailedImportRow(
            String stage,
            String importError,
            String teachingState,
            String teachingError,
            Instant teachingUpdatedAt,
            Instant updatedAt,
            Instant completedAt) {}
}
