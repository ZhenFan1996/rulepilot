package com.rulepilot.teaching.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.domain.TeachingPlan;
import jakarta.persistence.EntityManager;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
class JpaTeachingPlanRepositoryPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.2-pg17")
            .withDatabaseName("rulepilot")
            .withUsername("rulepilot")
            .withPassword("rulepilot-test");

    private static StandardServiceRegistry registry;
    private static SessionFactory sessionFactory;
    private static JdbcTemplate jdbc;

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
                .addAnnotatedClass(TeachingPlanEntity.class)
                .addAnnotatedClass(TeachingPlanSectionEntity.class)
                .buildMetadata()
                .buildSessionFactory();
    }

    @AfterAll
    static void stopHibernate() {
        if (sessionFactory != null) sessionFactory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    @Test
    void saveAndReloadPreservesTheSourceBoundWholeGameContext() {
        UUID versionId = createDocumentVersion();
        var context = new TeachingPlan.WholeGameContext(
                List.of(new TeachingPlan.TopicDependency(
                        "observe-state", "apply-change", "先观察，后改变。")),
                List.of("条件变化例外尚未解决。"));
        TeachingPlan original = new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "先掌握整体关系",
                "Opaque system",
                "两章相互依赖。",
                context,
                List.of(
                        new TeachingPlan.PlannedSection(
                                1, "observe-state", "观察状态", "识别状态。", true, false,
                                List.of("R-alpha"), List.of("whole_game_context_v1"), List.of(2)),
                        new TeachingPlan.PlannedSection(
                                2, "apply-change", "应用变化", "应用条件。", true, true,
                                List.of("R-beta"), List.of("whole_game_context_v1"), List.of(3), List.of(4))),
                "persistence-player",
                Instant.parse("2026-08-16T09:00:00Z"));

        inTransaction(repository -> {
            repository.save(original);
            return null;
        });
        TeachingPlan restored = inTransaction(repository -> repository.findById(original.id()).orElseThrow());

        assertThat(restored.wholeGameContext()).isEqualTo(context);
        assertThat(restored.sections()).isEqualTo(original.sections());
    }

    @Test
    void ownedListUsesTheSmallProjectionNeededByTheWorkStatusScreen() {
        UUID versionId = createDocumentVersion();
        TeachingPlan original = new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "只验证列表字段",
                        "Large-context game",
                        "列表只需要一句前提。",
                new TeachingPlan.WholeGameContext(List.of(), List.of("列表应显示这个缺口。")),
                List.of(new TeachingPlan.PlannedSection(
                        1, "setup", "设置", "摆好组件。", true, true,
                        List.of("不应返回给列表的检索词"), List.of("internal-tag"), List.of(9))),
                "persistence-player",
                Instant.parse("2026-08-16T10:00:00Z"));

        inTransaction(repository -> {
            repository.save(original);
            return null;
        });
        var summaries = inTransaction(repository -> repository.findSummariesByCreatedBy("persistence-player"));

        var summary = summaries.stream().filter(item -> item.id().equals(original.id())).findFirst().orElseThrow();
        assertThat(summary.gameTitle()).isEqualTo("Large-context game");
        assertThat(summary.sections()).containsExactly(
                new com.rulepilot.teaching.application.TeachingPlanSummary.SectionSummary(
                        1, "setup", "设置", true, true));
    }

    private static UUID createDocumentVersion() {
        UUID gameId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-08-16T08:00:00Z"));
        jdbc.update("INSERT INTO game (id, name, created_at) VALUES (?, ?, ?)", gameId, "Opaque " + gameId, now);
        jdbc.update(
                "INSERT INTO game_edition (id, game_id, name, language, created_at) VALUES (?, ?, ?, ?, ?)",
                editionId, gameId, "First", "en", now);
        jdbc.update(
                "INSERT INTO rule_document (id, game_edition_id, title, source_type, created_by, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                documentId, editionId, "Rules", "BASE_RULEBOOK", "persistence-player", now);
        jdbc.update(
                "INSERT INTO document_version (id, document_id, version_number, original_filename, object_key, "
                        + "checksum, size_bytes, content_type, processing_status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                versionId, documentId, 1, "rules.pdf", "teaching/" + versionId,
                "a".repeat(64), 10L, "application/pdf", "READY", now);
        return versionId;
    }

    private static <T> T inTransaction(RepositoryWork<T> work) {
        EntityManager entityManager = sessionFactory.createEntityManager();
        JpaTeachingPlanRepository repository = new JpaTeachingPlanRepository();
        ReflectionTestUtils.setField(repository, "entityManager", entityManager);
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
            throw new IllegalStateException("Could not initialize PostgreSQL extensions", failure);
        }
    }

    @FunctionalInterface
    private interface RepositoryWork<T> {
        T run(JpaTeachingPlanRepository repository);
    }
}
