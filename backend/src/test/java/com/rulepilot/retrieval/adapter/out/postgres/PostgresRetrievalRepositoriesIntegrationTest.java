package com.rulepilot.retrieval.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.ingestion.EmbeddingProvider.EmbeddingVector;
import com.rulepilot.ingestion.adapter.out.persistence.PostgresRuleChunkEmbeddingRepository;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresRetrievalRepositoriesIntegrationTest {

    private static final String CURRENT_PROVIDER = "integration:2";

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
        sessionFactory = new MetadataSources(registry).buildMetadata().buildSessionFactory();
    }

    @AfterAll
    static void stopHibernate() {
        if (sessionFactory != null) sessionFactory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    @Test
    void embeddingCheckpointAndVectorSearchUseTheCurrentProviderAcrossRealPgvectorRows() {
        UUID versionId = createVersion("Vector fixture");
        UUID first = insertChunk(versionId, 0, 1, "Setup", "Place the lantern board", "[1,0]", CURRENT_PROVIDER);
        UUID second = insertChunk(versionId, 1, 2, "Turns", "Move one lantern", "[0,1]", "old:2");
        UUID third = insertChunk(versionId, 2, 3, "Scoring", "Score the final beacon", null, null);

        inTransaction(entityManager -> {
            var embeddings = embeddingRepository(entityManager);
            assertThat(embeddings.coverage(versionId, CURRENT_PROVIDER).totalChunks()).isEqualTo(3);
            assertThat(embeddings.coverage(versionId, CURRENT_PROVIDER).indexedChunks()).isEqualTo(1);
            assertThat(embeddings.findPending(versionId, CURRENT_PROVIDER, 1))
                    .extracting(pending -> pending.id())
                    .containsExactly(second);
            embeddings.saveBatch(
                    List.of(
                            new com.rulepilot.ingestion.application.RuleChunkEmbeddingRepository.IndexedChunk(
                                    second, new EmbeddingVector(List.of(0.0f, 1.0f))),
                            new com.rulepilot.ingestion.application.RuleChunkEmbeddingRepository.IndexedChunk(
                                    third, new EmbeddingVector(List.of(-1.0f, 0.0f)))),
                    CURRENT_PROVIDER,
                    Instant.parse("2026-09-03T00:00:00Z"));
            return null;
        });

        inTransaction(entityManager -> {
            var embeddings = embeddingRepository(entityManager);
            assertThat(embeddings.coverage(versionId, CURRENT_PROVIDER).complete()).isTrue();
            var vector = vectorRepository(entityManager);
            assertThat(vector.search(
                            versionId,
                            new EmbeddingVector(List.of(1.0f, 0.0f)),
                            CURRENT_PROVIDER,
                            1,
                            1))
                    .extracting(hit -> hit.chunkId())
                    .containsExactly(second);
            assertThat(vector.search(
                            versionId,
                            new EmbeddingVector(List.of(1.0f, 0.0f)),
                            "missing:2",
                            5))
                    .isEmpty();
            return null;
        });

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void fullTextAndCanonicalHydrationRemainBoundToTheRequestedVersion() {
        UUID requestedVersion = createVersion("Requested fixture");
        UUID foreignVersion = createVersion("Foreign fixture");
        UUID requestedChunk = insertChunk(
                requestedVersion, 0, 4, "Components", "The prism marker starts beside the board", null, null);
        UUID foreignChunk = insertChunk(
                foreignVersion, 0, 4, "Components", "The prism marker belongs to another rulebook", null, null);

        inTransaction(entityManager -> {
            var fullText = fullTextRepository(entityManager);
            assertThat(fullText.search(requestedVersion, "prism marker", 10))
                    .extracting(hit -> hit.chunkId())
                    .containsExactly(requestedChunk);
            assertThat(fullText.search(requestedVersion, "unmatched phrase", 10)).isEmpty();

            var evidence = evidenceRepository(entityManager);
            assertThat(evidence.findByChunkIds(requestedVersion, Set.of(requestedChunk, foreignChunk)))
                    .extracting(hit -> hit.chunkId())
                    .containsExactly(requestedChunk);
            assertThat(evidence.findByPageNumbers(requestedVersion, Set.of(4)))
                    .extracting(hit -> hit.chunkId())
                    .containsExactly(requestedChunk);
            return null;
        });
    }

    private static UUID createVersion(String title) {
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO rule_document (id, game_edition_id, title, source_type, created_by, created_at)
                VALUES (?, NULL, ?, 'BASE_RULEBOOK', 'retrieval-test', ?)
                """,
                documentId,
                title,
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
                "retrieval-test/" + versionId,
                "a".repeat(64),
                now);
        return versionId;
    }

    private static UUID insertChunk(
            UUID versionId,
            int chunkIndex,
            int page,
            String heading,
            String content,
            String embedding,
            String provider) {
        UUID chunkId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO rule_chunk (
                    id, document_version_id, section_type, heading, content, page_from, page_to,
                    chunk_index, embedding, embedding_provider, embedded_at, created_at
                ) VALUES (?, ?, 'GENERAL', ?, ?, ?, ?, ?, cast(? AS vector), ?, ?, ?)
                """,
                chunkId,
                versionId,
                heading,
                content,
                page,
                page,
                chunkIndex,
                embedding,
                provider,
                embedding == null ? null : now,
                now);
        return chunkId;
    }

    private static PostgresRuleChunkEmbeddingRepository embeddingRepository(EntityManager entityManager) {
        var repository = new PostgresRuleChunkEmbeddingRepository();
        ReflectionTestUtils.setField(repository, "entityManager", entityManager);
        return repository;
    }

    private static PostgresVectorRuleSearch vectorRepository(EntityManager entityManager) {
        var repository = new PostgresVectorRuleSearch();
        ReflectionTestUtils.setField(repository, "entityManager", entityManager);
        return repository;
    }

    private static PostgresFullTextRuleSearch fullTextRepository(EntityManager entityManager) {
        var repository = new PostgresFullTextRuleSearch();
        ReflectionTestUtils.setField(repository, "entityManager", entityManager);
        return repository;
    }

    private static PostgresRuleEvidenceLookup evidenceRepository(EntityManager entityManager) {
        var repository = new PostgresRuleEvidenceLookup();
        ReflectionTestUtils.setField(repository, "entityManager", entityManager);
        return repository;
    }

    private static <T> T inTransaction(RepositoryWork<T> work) {
        EntityManager entityManager = sessionFactory.createEntityManager();
        var transaction = entityManager.getTransaction();
        try {
            transaction.begin();
            T result = work.run(entityManager);
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
        T run(EntityManager entityManager);
    }
}
