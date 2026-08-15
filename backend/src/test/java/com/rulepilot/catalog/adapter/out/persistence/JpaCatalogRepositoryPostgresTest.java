package com.rulepilot.catalog.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.catalog.domain.Expansion;
import com.rulepilot.catalog.domain.Game;
import com.rulepilot.catalog.domain.GameEdition;
import jakarta.persistence.EntityManager;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JpaCatalogRepositoryPostgresTest {

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
                .addAnnotatedClass(GameEntity.class)
                .addAnnotatedClass(GameEditionEntity.class)
                .addAnnotatedClass(ExpansionEntity.class)
                .addAnnotatedClass(EditionExpansionEntity.class)
                .buildMetadata()
                .buildSessionFactory();
    }

    @AfterAll
    static void stopHibernate() {
        if (sessionFactory != null) sessionFactory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    @Test
    void materializesExpansionRowsBeforeLoadingTheirCompatibleEditions() {
        Instant now = Instant.parse("2026-08-11T00:00:00Z");
        Game game = Game.create("Catalog result-set regression", now);
        GameEdition edition = GameEdition.create(game.id(), "Base edition", "en", 2026, now);
        Expansion first = Expansion.create(game.id(), "First expansion", Set.of(edition.id()), now);
        Expansion second = Expansion.create(game.id(), "Second expansion", Set.of(edition.id()), now);
        inTransaction(repository -> {
            repository.save(game);
            repository.save(edition);
            repository.save(first);
            repository.save(second);
            return null;
        });

        List<Expansion> result = inTransaction(repository -> repository.findExpansions(game.id()));

        assertThat(result).containsExactly(first, second);
    }

    @Test
    void atomicallyConfirmsAnUnknownEditionLanguageOnlyOnceUnderConcurrentRequests() throws Exception {
        Instant now = Instant.parse("2026-08-15T00:00:00Z");
        Game game = Game.create("Language confirmation regression", now);
        GameEdition edition = GameEdition.create(game.id(), "Unknown-language edition", "und", 2026, now);
        inTransaction(repository -> {
            repository.save(game);
            repository.save(edition);
            return null;
        });

        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var english = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return inTransaction(repository ->
                        repository.confirmEditionLanguageIfUnknown(edition.id(), "en"));
            });
            var chinese = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return inTransaction(repository ->
                        repository.confirmEditionLanguageIfUnknown(edition.id(), "zh-CN"));
            });
            start.countDown();

            assertThat(List.of(english.get(15, TimeUnit.SECONDS), chinese.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        GameEdition persisted = inTransaction(repository -> repository.findEdition(edition.id()).orElseThrow());

        assertThat(persisted.language()).isIn("en", "zh-CN");
    }

    private static <T> T inTransaction(RepositoryWork<T> work) {
        EntityManager entityManager = sessionFactory.createEntityManager();
        JpaCatalogRepository repository = new JpaCatalogRepository();
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
        T run(JpaCatalogRepository repository);
    }
}
