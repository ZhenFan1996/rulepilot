package com.rulepilot.assistant.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivitySnapshot;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionControl.BudgetLimits;
import com.rulepilot.assistant.AgentExecutionControl.InvocationReservation;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import jakarta.persistence.EntityManager;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
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
class JpaAgentExecutionControlPostgresTest {

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
        sessionFactory = new MetadataSources(registry).buildMetadata().buildSessionFactory();
    }

    @AfterAll
    static void stopHibernate() {
        if (sessionFactory != null) sessionFactory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    @Test
    void teachingRecordsUsageBeyondItsThresholdAndContinues() {
        UUID runId = createRun("TEACHING");
        inTransaction(control -> {
            control.initialize(
                    runId,
                    BudgetLimits.observationalTokens(10, Duration.ofMinutes(5)),
                    Instant.now());
            return null;
        });

        InvocationReservation first = inTransaction(control ->
                control.reserve(runId, ActivityType.MODEL, "composeTeachingSection|1", 11));
        inTransaction(control -> {
            control.complete(first, ActivityOutcome.SUCCEEDED, 7, 1, "First chapter composed");
            return null;
        });
        InvocationReservation second = inTransaction(control ->
                control.reserve(runId, ActivityType.MODEL, "composeTeachingSection|2", 5));
        inTransaction(control -> {
            control.complete(second, ActivityOutcome.SUCCEEDED, 3, 1, "Second chapter composed");
            control.assertStepAllowed(runId, 2);
            return null;
        });

        var budget = inTransaction(control -> control.budget(runId));
        assertThat(budget.usedTokens()).isEqualTo(26);
        assertThat(budget.maxTokens()).isEqualTo(10);
        assertThat(budget.tokenLimitEnforced()).isFalse();
        java.util.List<ActivitySnapshot> activities = inTransaction(control -> control.activities(runId));
        assertThat(activities)
                .extracting(activity -> activity.outcome())
                .containsExactly(ActivityOutcome.SUCCEEDED, ActivityOutcome.SUCCEEDED);
    }

    @Test
    void questionAnswerStillUsesItsHardTokenBoundary() {
        UUID runId = createRun("QUESTION_ANSWER");
        inTransaction(control -> {
            control.initialize(runId, new BudgetLimits(10, Duration.ofMinutes(5)), Instant.now());
            return null;
        });

        assertThatThrownBy(() -> inTransaction(control ->
                        control.reserve(runId, ActivityType.MODEL, "composeRuleAnswer", 11)))
                .isInstanceOfSatisfying(
                        AgentExecutionStoppedException.class,
                        stopped -> assertThat(stopped.reason()).isEqualTo(StopReason.TOKEN_BUDGET));
    }

    private static UUID createRun(String mode) {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO assistant_run (id, mode, subject_id, owner_username, state, revision, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'RECEIVED', 1, ?, ?)",
                runId,
                mode,
                UUID.randomUUID(),
                "budget-player",
                now,
                now);
        return runId;
    }

    private static <T> T inTransaction(ControlWork<T> work) {
        EntityManager entityManager = sessionFactory.createEntityManager();
        JpaAgentExecutionControl control = new JpaAgentExecutionControl();
        ReflectionTestUtils.setField(control, "entityManager", entityManager);
        var transaction = entityManager.getTransaction();
        try {
            transaction.begin();
            T result = work.run(control);
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
    private interface ControlWork<T> {
        T run(JpaAgentExecutionControl control);
    }
}
