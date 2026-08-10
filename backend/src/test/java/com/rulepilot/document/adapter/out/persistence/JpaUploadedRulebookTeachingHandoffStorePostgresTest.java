package com.rulepilot.document.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.document.application.UploadedRulebookTeachingHandoffStore;
import jakarta.persistence.EntityManager;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
class JpaUploadedRulebookTeachingHandoffStorePostgresTest {

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
                .addAnnotatedClass(UploadedRulebookTeachingHandoffEntity.class)
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
    void clearPlayerUploads() {
        jdbc.update("DELETE FROM uploaded_rulebook_teaching_handoff");
        jdbc.update("DELETE FROM document_version WHERE object_key LIKE 'test-upload/%'");
        jdbc.update("DELETE FROM rule_document WHERE created_by = 'upload-handoff-player'");
    }

    @Test
    void waitsForReadyThenClaimsAndPersistsARecoverableFailure() {
        Instant requestedAt = Instant.parse("2026-08-10T10:00:00Z");
        UUID versionId = insertDocument("UPLOADED");
        UUID handoffId = UUID.randomUUID();

        var requested = inTransactionReturning(store -> store.request(
                handoffId,
                versionId,
                "upload-handoff-player",
                "先讲清开局。",
                requestedAt));

        assertThat(requested.state())
                .isEqualTo(UploadedRulebookTeachingHandoffStore.State.WAITING_FOR_DOCUMENT);
        List<UploadedRulebookTeachingHandoffStore.Snapshot> notReady =
                inTransactionReturning(store -> store.claimReady(4, requestedAt.plusSeconds(1)));
        assertThat(notReady).isEmpty();

        jdbc.update("UPDATE document_version SET processing_status = 'READY' WHERE id = ?", versionId);
        List<UploadedRulebookTeachingHandoffStore.Snapshot> claimed =
                inTransactionReturning(store -> store.claimReady(4, requestedAt.plusSeconds(2)));

        assertThat(claimed).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(handoffId);
            assertThat(item.documentVersionId()).isEqualTo(versionId);
            assertThat(item.state()).isEqualTo(UploadedRulebookTeachingHandoffStore.State.LAUNCHING);
        });

        inTransaction(store -> store.failLaunch(
                handoffId, "TEACHING_HANDOFF_LAUNCH_FAILED", requestedAt.plusSeconds(3)));
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM uploaded_rulebook_teaching_handoff WHERE id = ?",
                        String.class,
                        handoffId))
                .isEqualTo("FAILED");

        var retried = inTransactionReturning(store -> store.request(
                UUID.randomUUID(),
                versionId,
                "upload-handoff-player",
                null,
                requestedAt.plusSeconds(4)));
        assertThat(retried.id()).isEqualTo(handoffId);
        assertThat(retried.state())
                .isEqualTo(UploadedRulebookTeachingHandoffStore.State.WAITING_FOR_DOCUMENT);
    }

    @Test
    void neverCreatesAPlayerIntentForAnotherOwnersDocument() {
        UUID versionId = insertDocument("UPLOADED");

        assertThatThrownBy(() -> inTransactionReturning(store -> store.request(
                        UUID.randomUUID(),
                        versionId,
                        "mallory",
                        null,
                        Instant.parse("2026-08-10T10:00:00Z"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("uploaded rulebook document does not exist");
    }

    @Test
    void terminalizesWaitingTeachingWhenDocumentProcessingFailed() {
        Instant requestedAt = Instant.parse("2026-08-10T10:00:00Z");
        UUID versionId = insertDocument("UPLOADED");
        UUID handoffId = UUID.randomUUID();
        inTransactionReturning(store -> store.request(
                handoffId,
                versionId,
                "upload-handoff-player",
                null,
                requestedAt));
        jdbc.update("UPDATE document_version SET processing_status = 'FAILED' WHERE id = ?", versionId);

        int failed = inTransactionReturning(store ->
                store.failUnusableDocuments(requestedAt.plusSeconds(1)));

        assertThat(failed).isEqualTo(1);
        assertThat(jdbc.queryForMap(
                        "SELECT state, error_code FROM uploaded_rulebook_teaching_handoff WHERE id = ?",
                        handoffId))
                .containsEntry("state", "FAILED")
                .containsEntry("error_code", "DOCUMENT_PROCESSING_FAILED");
    }

    private static UUID insertDocument(String status) {
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(
                Instant.parse("2026-08-10T09:59:00Z"), ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO rule_document (id, game_edition_id, title, source_type, created_by, created_at)
                VALUES (?, NULL, ?, 'BASE_RULEBOOK', 'upload-handoff-player', ?)
                """,
                documentId,
                "Local upload rules",
                now);
        jdbc.update(
                """
                INSERT INTO document_version (
                    id, document_id, version_number, original_filename, object_key,
                    checksum, size_bytes, content_type, processing_status, created_at
                ) VALUES (?, ?, 1, ?, ?, ?, 1024, 'application/pdf', ?, ?)
                """,
                versionId,
                documentId,
                "local-rules.pdf",
                "test-upload/" + versionId + ".pdf",
                "a".repeat(64),
                status,
                now);
        return versionId;
    }

    private static void inTransaction(RepositoryWork work) {
        inTransactionReturning(store -> {
            work.run(store);
            return null;
        });
    }

    private static <T> T inTransactionReturning(RepositoryResult<T> work) {
        EntityManager entityManager = sessionFactory.createEntityManager();
        JpaUploadedRulebookTeachingHandoffStore store = new JpaUploadedRulebookTeachingHandoffStore();
        ReflectionTestUtils.setField(store, "entityManager", entityManager);
        var transaction = entityManager.getTransaction();
        try {
            transaction.begin();
            T result = work.run(store);
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
        void run(JpaUploadedRulebookTeachingHandoffStore store);
    }

    @FunctionalInterface
    private interface RepositoryResult<T> {
        T run(JpaUploadedRulebookTeachingHandoffStore store);
    }
}
