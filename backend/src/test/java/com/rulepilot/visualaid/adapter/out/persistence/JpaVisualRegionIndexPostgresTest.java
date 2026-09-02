package com.rulepilot.visualaid.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.visualaid.VisualRegionCatalog.Region;
import jakarta.persistence.EntityManager;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JpaVisualRegionIndexPostgresTest {

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
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .build();
        sessionFactory = new MetadataSources(registry)
                .addAnnotatedClass(VisualAidIndexEntity.class)
                .addAnnotatedClass(VisualAidRegionEntity.class)
                .buildMetadata()
                .buildSessionFactory();
    }

    @AfterAll
    static void stopHibernate() {
        if (sessionFactory != null) sessionFactory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    @Test
    void replacementIsVersionOwnedOrderedAndRemovesStaleRegions() {
        UUID versionId = createDocumentVersion();
        List<Region> original = List.of(
                new Region(1, "PICTURE", 100, 120, 300, 320),
                new Region(2, "TABLE", 200, 220, 400, 420));

        inTransaction(index -> {
            index.replace(versionId, "docling:test", 2, original);
            return null;
        });
        List<Region> restored = inTransaction(index -> index.find(versionId, Set.of(2, 1)));
        assertThat(restored).containsExactlyElementsOf(original);

        Region replacement = new Region(2, "PICTURE", 50, 60, 500, 600);
        inTransaction(index -> {
            index.replace(versionId, "docling:test-v2", 2, List.of(replacement));
            return null;
        });

        List<Region> restoredReplacement = inTransaction(index -> index.find(versionId, Set.of(1, 2)));
        assertThat(restoredReplacement).containsExactly(replacement);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM visual_aid_region WHERE document_version_id = ?",
                        Integer.class,
                        versionId))
                .isEqualTo(1);
    }

    private static UUID createDocumentVersion() {
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO rule_document (id, game_edition_id, title, source_type, created_by, created_at)
                VALUES (?, NULL, 'Visual aid fixture', 'BASE_RULEBOOK', 'visual-aid-test', ?)
                """,
                documentId,
                now);
        jdbc.update(
                """
                INSERT INTO document_version (
                    id, document_id, version_number, original_filename, object_key, checksum,
                    size_bytes, content_type, processing_status, created_at
                ) VALUES (?, ?, 1, 'rules.pdf', ?, ?, 10, 'application/pdf', 'READY', ?)
                """,
                versionId,
                documentId,
                "visual-aid-test/" + versionId,
                "a".repeat(64),
                now);
        return versionId;
    }

    private static <T> T inTransaction(RepositoryWork<T> work) {
        EntityManager entityManager = sessionFactory.createEntityManager();
        JpaVisualRegionIndex repository = new JpaVisualRegionIndex(entityManager);
        var transaction = entityManager.getTransaction();
        try {
            transaction.begin();
            T result = work.run(repository);
            transaction.commit();
            return result;
        } catch (RuntimeException failure) {
            if (transaction.isActive()) transaction.rollback();
            throw failure;
        } finally {
            entityManager.close();
        }
    }

    private static void enableProductionExtensions() {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not enable PostgreSQL test extensions", failure);
        }
    }

    @FunctionalInterface
    private interface RepositoryWork<T> {
        T run(JpaVisualRegionIndex index);
    }
}
