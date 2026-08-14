package com.rulepilot.document.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JpaRuleDocumentPageImageConcurrencyPostgresTest {

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
                .addAnnotatedClass(RuleDocumentEntity.class)
                .addAnnotatedClass(DocumentVersionEntity.class)
                .addAnnotatedClass(DocumentPageEntity.class)
                .buildMetadata()
                .buildSessionFactory();
    }

    @AfterAll
    static void stopHibernate() {
        if (sessionFactory != null) sessionFactory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    @BeforeEach
    void clearFixture() {
        jdbc.update("DELETE FROM document_page WHERE text_content LIKE 'concurrent-page-%'");
        jdbc.update("DELETE FROM document_version WHERE object_key LIKE 'test-concurrent-pages/%'");
        jdbc.update("DELETE FROM rule_document WHERE created_by = 'page-image-concurrency-test'");
    }

    @Test
    void commitsDifferentPageImagesForTheSameDocumentVersionWithoutLostUpdates() throws Exception {
        UUID versionId = insertTwoPageVersion();
        var start = new CountDownLatch(1);
        try (var writers = Executors.newFixedThreadPool(2)) {
            var first = writers.submit(() -> updateAfterGate(
                    start, versionId, 1, "documents/test/pages/0001.jpg", 1601, 2201));
            var second = writers.submit(() -> updateAfterGate(
                    start, versionId, 2, "documents/test/pages/0002.jpg", 1602, 2202));

            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        List<Map<String, Object>> pages = jdbc.queryForList(
                """
                SELECT page_number, image_object_key, image_width, image_height
                FROM document_page
                WHERE document_version_id = ?
                ORDER BY page_number
                """,
                versionId);
        assertThat(pages).containsExactly(
                Map.of(
                        "page_number", 1,
                        "image_object_key", "documents/test/pages/0001.jpg",
                        "image_width", 1601,
                        "image_height", 2201),
                Map.of(
                        "page_number", 2,
                        "image_object_key", "documents/test/pages/0002.jpg",
                        "image_width", 1602,
                        "image_height", 2202));
    }

    private static void updateAfterGate(
            CountDownLatch start,
            UUID versionId,
            int pageNumber,
            String objectKey,
            int width,
            int height) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent page update gate timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrent page update was interrupted", interrupted);
        }
        inTransaction(repository -> repository.updatePageImage(versionId, pageNumber, objectKey, width, height));
    }

    private static UUID insertTwoPageVersion() {
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO rule_document (id, game_edition_id, title, source_type, created_by, created_at)
                VALUES (?, NULL, 'Concurrent page fixture', 'BASE_RULEBOOK', 'page-image-concurrency-test', ?)
                """,
                documentId,
                now);
        jdbc.update(
                """
                INSERT INTO document_version (
                    id, document_id, version_number, original_filename, object_key,
                    checksum, size_bytes, content_type, processing_status, created_at
                ) VALUES (?, ?, 1, 'fixture.pdf', ?, ?, 1024, 'application/pdf', 'EXTRACTING', ?)
                """,
                versionId,
                documentId,
                "test-concurrent-pages/" + versionId + ".pdf",
                "b".repeat(64),
                now);
        for (int pageNumber = 1; pageNumber <= 2; pageNumber++) {
            jdbc.update(
                    """
                    INSERT INTO document_page (
                        id, document_version_id, page_number, text_content, character_count, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    versionId,
                    pageNumber,
                    "concurrent-page-" + pageNumber,
                    17,
                    now);
        }
        return versionId;
    }

    private static void inTransaction(RepositoryWork work) {
        EntityManager entityManager = sessionFactory.createEntityManager();
        var transaction = entityManager.getTransaction();
        try {
            transaction.begin();
            work.run(new JpaRuleDocumentRepository(entityManager));
            transaction.commit();
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
        void run(JpaRuleDocumentRepository repository);
    }
}
