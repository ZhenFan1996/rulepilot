package com.rulepilot.teaching.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch.RuleFactStatus;
import jakarta.persistence.EntityManager;
import java.sql.DriverManager;
import java.sql.SQLException;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JpaVisualRulebookPageFactsPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.2-pg17")
            .withDatabaseName("rulepilot")
            .withUsername("rulepilot")
            .withPassword("rulepilot-test");

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
        registry = new StandardServiceRegistryBuilder()
                .applySetting("jakarta.persistence.jdbc.driver", "org.postgresql.Driver")
                .applySetting("jakarta.persistence.jdbc.url", POSTGRES.getJdbcUrl())
                .applySetting("jakarta.persistence.jdbc.user", POSTGRES.getUsername())
                .applySetting("jakarta.persistence.jdbc.password", POSTGRES.getPassword())
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .build();
        sessionFactory = new MetadataSources(registry)
                .addAnnotatedClass(VisualRulebookPageFactEntity.class)
                .buildMetadata()
                .buildSessionFactory();
    }

    @AfterAll
    static void stopHibernate() {
        if (sessionFactory != null) sessionFactory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    @Test
    void roundTripsExternalSourceDependenciesThroughTheProductionPostgresMapping() {
        UUID documentVersionId = UUID.randomUUID();
        var dependency = new SourceDependency("First Session Booklet", List.of("setup"));
        var fact = new PageFact(
                4,
                "PLAY A CARD; First Session Booklet",
                "PLAY A CARD: 当前页明确指向另一份开局资料。",
                List.of("PLAY A CARD"),
                List.of(),
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of(dependency),
                List.of("PLAY A CARD"),
                true,
                List.of(new RuleGroupFact(
                        "PLAY A CARD", "PLAY A CARD", "当前页明确指向另一份开局资料。")));

        inTransaction(repository -> {
            repository.replace(documentVersionId, List.of(fact));
            return null;
        });
        List<PageFact> restored = inTransaction(repository -> repository.find(documentVersionId, Set.of(4)));

        assertThat(restored).singleElement().satisfies(page -> {
            assertThat(page.schemaVersion()).isEqualTo(PageFact.CURRENT_SCHEMA_VERSION);
            assertThat(page.sourceDependencies()).containsExactly(dependency);
            assertThat(page.ruleGroupIdentifiers()).containsExactly("PLAY A CARD");
            assertThat(page.ruleGroupInventoryComplete()).isTrue();
        });
    }

    @Test
    void searchesOnlyCurrentSchemaFactsForAnswerEvidence() {
        UUID documentVersionId = UUID.randomUUID();
        var stale = new PageFact(
                4,
                "TURN",
                "TURN: This obsolete schema observation must not enter a new answer.",
                List.of("turn", "obsolete"),
                List.of(),
                PageFact.CURRENT_SCHEMA_VERSION - 1);
        var current = new PageFact(
                5,
                "TURN",
                "TURN: This current observation may be used to locate source evidence.",
                List.of("turn", "current"),
                List.of(),
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of("TURN"),
                true,
                List.of(new RuleGroupFact(
                        "TURN", "TURN", "This current observation may be used to locate source evidence.")));

        inTransaction(repository -> {
            repository.replace(documentVersionId, List.of(stale, current));
            return null;
        });
        var matches = inTransaction(repository -> repository.search(documentVersionId, "TURN", 5));

        assertThat(matches).singleElement().satisfies(match -> {
            assertThat(match.pageNumber()).isEqualTo(5);
            assertThat(match.factualSummary()).doesNotContain("obsolete");
            assertThat(match.ruleFactStatus()).isEqualTo(RuleFactStatus.CURRENT_RULE_FACTS);
        });
    }

    private static <T> T inTransaction(RepositoryWork<T> work) {
        EntityManager entityManager = sessionFactory.createEntityManager();
        JpaVisualRulebookPageFacts repository = new JpaVisualRulebookPageFacts();
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
    private interface RepositoryWork<T> {
        T run(JpaVisualRulebookPageFacts repository);
    }
}
