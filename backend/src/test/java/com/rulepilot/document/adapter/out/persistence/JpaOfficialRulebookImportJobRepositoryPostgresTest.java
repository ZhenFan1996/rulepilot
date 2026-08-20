package com.rulepilot.document.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.domain.OfficialRulebookImportJob;
import com.rulepilot.document.domain.RuleDocument;
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
                .addAnnotatedClass(RuleDocumentEntity.class)
                .addAnnotatedClass(DocumentVersionEntity.class)
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
        jdbc.update("DELETE FROM assistant_run WHERE owner_username = 'official-handoff-player'");
        jdbc.update("DELETE FROM document_version WHERE object_key LIKE 'official-handoff-test/%'");
        jdbc.update("DELETE FROM rule_document WHERE created_by IN ('official-handoff-player', 'other-identity-player')");
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

    @Test
    void resolvesTheLatestPersistedBindingForAnExactOwnerAndSource() {
        String source = "https://publisher.example/shared-identity.pdf";
        Instant firstAt = Instant.parse("2026-08-10T01:15:00Z");
        Instant latestAt = firstAt.plusSeconds(30);
        insertSourceIdentityJob(source, "FAILED", firstAt);
        UUID latestId = insertSourceIdentityJob(source, "QUEUED", latestAt);
        insertSourceIdentityJob("https://publisher.example/other.pdf", "QUEUED", latestAt.plusSeconds(5));

        var found = inTransactionReturning(repository ->
                repository.findLatestOwnedBySource("postgres-regression-player", source));

        assertThat(found).get().extracting(OfficialRulebookImportJob::id).isEqualTo(latestId);
    }

    @Test
    void resolvesTheLatestPersistedDocumentForAnExactOwnerAndOfficialSource() {
        String source = "https://publisher.example/document-identity.pdf";
        Instant firstAt = Instant.parse("2026-08-10T01:20:00Z");
        Instant latestAt = firstAt.plusSeconds(30);
        insertSourceIdentityDocument("official-handoff-player", source, firstAt);
        UUID latestId = insertSourceIdentityDocument("official-handoff-player", source, latestAt);
        insertSourceIdentityDocument(
                "official-handoff-player",
                "https://publisher.example/other-document.pdf",
                latestAt.plusSeconds(5));
        insertSourceIdentityDocument("other-identity-player", source, latestAt.plusSeconds(10));

        var found = inDocumentTransactionReturning(repository ->
                repository.findLatestOwnedByOfficialSource("official-handoff-player", source));

        assertThat(found).get().extracting(RuleDocument::id).isEqualTo(latestId);
    }

    @Test
    void persistsTheFirstExactDownloadCompletionMilestoneIdempotently() {
        Instant createdAt = Instant.parse("2026-08-10T01:30:00Z");
        Instant downloadedAt = createdAt.plusSeconds(7);
        UUID jobId = insertJob("NOT_REQUESTED", null, createdAt);

        inTransaction(repository -> repository.markDownloadCompleted(jobId, downloadedAt));
        inTransaction(repository -> repository.markDownloadCompleted(jobId, downloadedAt.plusSeconds(3)));

        var persisted = inTransactionReturning(repository -> repository.findOwned(jobId, "postgres-regression-player"))
                .orElseThrow();
        assertThat(persisted.downloadCompletedAt()).isEqualTo(downloadedAt);
        assertThat(persisted.updatedAt()).isEqualTo(downloadedAt.plusSeconds(3));
    }

    @Test
    void reusedWorkBecomesTheMostRecentOwnedStatusWithoutChangingItsCreationTime() {
        Instant oldCreatedAt = Instant.parse("2026-08-10T01:35:00Z");
        Instant newerCreatedAt = oldCreatedAt.plusSeconds(30);
        Instant reusedAt = newerCreatedAt.plusSeconds(30);
        UUID reusedJobId = insertJob("NOT_REQUESTED", null, oldCreatedAt);
        UUID newerJobId = insertJob("NOT_REQUESTED", null, newerCreatedAt);

        var beforeReuse = inTransactionReturning(repository ->
                repository.findRecentOwned("postgres-regression-player", 1));
        assertThat(beforeReuse)
                .extracting(OfficialRulebookImportJob::id)
                .containsExactly(newerJobId);

        inTransaction(repository -> repository.recordReuse(reusedJobId, reusedAt));

        var afterReuse = inTransactionReturning(repository ->
                repository.findRecentOwned("postgres-regression-player", 1));
        assertThat(afterReuse)
                .singleElement()
                .satisfies(job -> {
                    assertThat(job.id()).isEqualTo(reusedJobId);
                    assertThat(job.createdAt()).isEqualTo(oldCreatedAt);
                    assertThat(job.updatedAt()).isEqualTo(reusedAt);
                });
    }

    @Test
    void promptClaimScopesTheOfficialHandoffToTheReadyDocumentVersion() {
        Instant now = Instant.parse("2026-08-10T01:45:00Z");
        UUID matchingVersionId = insertDocument("matching", "READY", now);
        UUID otherVersionId = insertDocument("other", "READY", now);
        UUID matchingJobId = insertCompletedTeachingJob(matchingVersionId, now);
        UUID otherJobId = insertCompletedTeachingJob(otherVersionId, now);

        var claimed = inTransactionReturning(repository ->
                repository.claimReadyTeachingForDocument(matchingVersionId, 4, now.plusSeconds(1)));

        assertThat(claimed).extracting(OfficialRulebookImportJob::id).containsExactly(matchingJobId);
        assertThat(jdbc.queryForObject(
                        "SELECT teaching_handoff_state FROM official_rulebook_import_job WHERE id = ?",
                        String.class,
                        otherJobId))
                .isEqualTo("WAITING_FOR_DOCUMENT");
    }

    @Test
    void terminalizesTeachingWhenTheImportedDocumentCouldNotBeProcessed() {
        Instant now = Instant.parse("2026-08-10T02:00:00Z");
        UUID versionId = insertFailedDocument(now);
        UUID jobId = UUID.randomUUID();
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO official_rulebook_import_job (
                    id, owner_username, title, source_type, source_url, stage,
                    downloaded_bytes, total_bytes, document_version_id, duplicate,
                    teaching_handoff_state, teaching_handoff_updated_at,
                    created_at, updated_at, completed_at
                ) VALUES (?, ?, ?, 'BASE_RULEBOOK', ?, 'COMPLETED', 1024, 1024, ?, FALSE,
                          'WAITING_FOR_DOCUMENT', ?, ?, ?, ?)
                """,
                jobId,
                "official-handoff-player",
                "Unusable imported rules",
                "https://publisher.example/" + jobId + ".pdf",
                versionId,
                timestamp,
                timestamp,
                timestamp,
                timestamp);

        int failed = inTransactionReturning(repository ->
                repository.failTeachingForUnusableDocuments(now.plusSeconds(1)));

        assertThat(failed).isEqualTo(1);
        assertThat(jdbc.queryForMap(
                        """
                        SELECT teaching_handoff_state, teaching_error_code
                        FROM official_rulebook_import_job
                        WHERE id = ?
                        """,
                        jobId))
                .containsEntry("teaching_handoff_state", "FAILED")
                .containsEntry("teaching_error_code", "DOCUMENT_PROCESSING_FAILED");
    }

    @Test
    void retryResetsTheObservedPreparationButCannotReplaceANewerRun() {
        Instant now = Instant.parse("2026-08-10T02:30:00Z");
        UUID versionId = insertDocument("retry", "READY", now);
        UUID jobId = insertCompletedTeachingJob(versionId, now);
        UUID failedRunId = UUID.randomUUID();
        UUID newerRunId = UUID.randomUUID();
        insertPreparationRun(failedRunId, versionId, "FAILED", now);
        insertPreparationRun(newerRunId, versionId, "RECEIVED", now.plusSeconds(4));
        inTransaction(repository -> repository.claimReadyTeachingForDocument(versionId, 1, now.plusSeconds(1)));
        inTransaction(repository -> repository.completeTeachingLaunch(jobId, failedRunId, now.plusSeconds(2)));

        boolean retried = inTransactionReturning(repository ->
                repository.retryTeaching(jobId, failedRunId, now.plusSeconds(3)));
        inTransaction(repository -> repository.claimReadyTeachingForDocument(versionId, 1, now.plusSeconds(4)));
        inTransaction(repository -> repository.completeTeachingLaunch(jobId, newerRunId, now.plusSeconds(5)));
        boolean staleRetry = inTransactionReturning(repository ->
                repository.retryTeaching(jobId, failedRunId, now.plusSeconds(6)));

        assertThat(retried).isTrue();
        assertThat(staleRetry).isFalse();
        assertThat(jdbc.queryForMap(
                        """
                        SELECT teaching_handoff_state, teaching_preparation_run_id
                        FROM official_rulebook_import_job WHERE id = ?
                        """,
                        jobId))
                .containsEntry("teaching_handoff_state", "LAUNCHED")
                .containsEntry("teaching_preparation_run_id", newerRunId);
    }

    @Test
    void dismissesOnlyTheExactOwnedFailedPreparationAndKeepsTheOfficialImport() {
        Instant now = Instant.parse("2026-08-10T03:00:00Z");
        UUID versionId = insertDocument("dismiss", "READY", now);
        UUID jobId = insertCompletedTeachingJob(versionId, now);
        UUID failedRunId = UUID.randomUUID();
        insertPreparationRun(failedRunId, versionId, "FAILED", now);
        inTransaction(repository -> repository.claimReadyTeachingForDocument(
                versionId, 1, now.plusSeconds(1)));
        inTransaction(repository -> repository.completeTeachingLaunch(
                jobId, failedRunId, now.plusSeconds(2)));

        boolean wrongOwner = inTransactionReturning(repository -> repository.dismissTeaching(
                jobId,
                "mallory",
                OfficialRulebookImportJob.TeachingHandoffState.LAUNCHED,
                failedRunId,
                now.plusSeconds(3)));
        boolean staleRun = inTransactionReturning(repository -> repository.dismissTeaching(
                jobId,
                "official-handoff-player",
                OfficialRulebookImportJob.TeachingHandoffState.LAUNCHED,
                UUID.randomUUID(),
                now.plusSeconds(4)));
        boolean dismissed = inTransactionReturning(repository -> repository.dismissTeaching(
                jobId,
                "official-handoff-player",
                OfficialRulebookImportJob.TeachingHandoffState.LAUNCHED,
                failedRunId,
                now.plusSeconds(5)));

        assertThat(wrongOwner).isFalse();
        assertThat(staleRun).isFalse();
        assertThat(dismissed).isTrue();
        assertThat(jdbc.queryForMap(
                        """
                        SELECT teaching_handoff_state, teaching_learning_goal,
                               teaching_preparation_run_id, teaching_error_code,
                               teaching_handoff_updated_at, document_version_id
                        FROM official_rulebook_import_job
                        WHERE id = ?
                        """,
                        jobId))
                .containsEntry("teaching_handoff_state", "NOT_REQUESTED")
                .containsEntry("teaching_learning_goal", null)
                .containsEntry("teaching_preparation_run_id", null)
                .containsEntry("teaching_error_code", null)
                .containsEntry("teaching_handoff_updated_at", null)
                .containsEntry("document_version_id", versionId);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM document_version WHERE id = ?",
                        Integer.class,
                        versionId))
                .isOne();
    }

    private static UUID insertFailedDocument(Instant now) {
        return insertDocument("unusable", "FAILED", now);
    }

    private static UUID insertSourceIdentityJob(String source, String stage, Instant createdAt) {
        UUID jobId = UUID.randomUUID();
        boolean terminal = "FAILED".equals(stage);
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO official_rulebook_import_job (
                    id, owner_username, title, source_type, source_url, stage,
                    downloaded_bytes, error_code, teaching_handoff_state,
                    created_at, updated_at, completed_at
                ) VALUES (?, 'postgres-regression-player', 'Identity review rules', 'BASE_RULEBOOK', ?, ?,
                          0, ?, 'NOT_REQUESTED', ?, ?, ?)
                """,
                jobId,
                source,
                stage,
                terminal ? "SOURCE_UNAVAILABLE" : null,
                timestamp,
                timestamp,
                terminal ? timestamp : null);
        return jobId;
    }

    private static UUID insertSourceIdentityDocument(
            String owner, String officialSourceUrl, Instant createdAt) {
        UUID documentId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO rule_document (
                    id, game_edition_id, title, source_type, official_source_url,
                    created_by, created_at
                ) VALUES (?, NULL, ?, 'BASE_RULEBOOK', ?, ?, ?)
                """,
                documentId,
                "Identity review rules " + documentId,
                officialSourceUrl,
                owner,
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
        return documentId;
    }

    private static UUID insertDocument(String label, String status, Instant now) {
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO rule_document (id, game_edition_id, title, source_type, created_by, created_at)
                VALUES (?, NULL, ?, 'BASE_RULEBOOK', 'official-handoff-player', ?)
                """,
                documentId,
                label + " imported rules",
                timestamp);
        jdbc.update(
                """
                INSERT INTO document_version (
                    id, document_id, version_number, original_filename, object_key,
                    checksum, size_bytes, content_type, processing_status, created_at
                ) VALUES (?, ?, 1, ?, ?, ?, 1024, 'application/pdf', ?, ?)
                """,
                versionId,
                documentId,
                label + "-rules.pdf",
                "official-handoff-test/" + versionId + ".pdf",
                "b".repeat(64),
                status,
                timestamp);
        return versionId;
    }

    private static UUID insertCompletedTeachingJob(UUID versionId, Instant now) {
        UUID jobId = UUID.randomUUID();
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO official_rulebook_import_job (
                    id, owner_username, title, source_type, source_url, stage,
                    downloaded_bytes, total_bytes, document_version_id, duplicate,
                    teaching_handoff_state, teaching_handoff_updated_at,
                    created_at, updated_at, completed_at
                ) VALUES (?, ?, ?, 'BASE_RULEBOOK', ?, 'COMPLETED', 1024, 1024, ?, FALSE,
                          'WAITING_FOR_DOCUMENT', ?, ?, ?, ?)
                """,
                jobId,
                "official-handoff-player",
                "Ready imported rules",
                "https://publisher.example/" + jobId + ".pdf",
                versionId,
                timestamp,
                timestamp,
                timestamp,
                timestamp);
        return jobId;
    }

    private static void insertPreparationRun(UUID runId, UUID versionId, String state, Instant now) {
        boolean failed = "FAILED".equals(state);
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO assistant_run (
                    id, mode, subject_id, owner_username, state, revision,
                    created_at, updated_at, completed_at, last_error_code
                ) VALUES (?, 'TEACHING_PREPARATION', ?, 'official-handoff-player', ?, 1, ?, ?, ?, ?)
                """,
                runId,
                versionId,
                state,
                timestamp,
                timestamp,
                failed ? timestamp : null,
                failed ? "TEACHING_PREPARATION_FAILED" : null);
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

    private static <T> T inDocumentTransactionReturning(DocumentRepositoryResult<T> work) {
        EntityManager entityManager = sessionFactory.createEntityManager();
        JpaRuleDocumentRepository repository = new JpaRuleDocumentRepository(entityManager);
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

    @FunctionalInterface
    private interface DocumentRepositoryResult<T> {
        T run(JpaRuleDocumentRepository repository);
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
