package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivitySnapshot;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionControl.BudgetLimits;
import com.rulepilot.assistant.AgentExecutionControl.BudgetSnapshot;
import com.rulepilot.assistant.AgentExecutionControl.InvocationReservation;
import com.rulepilot.assistant.AssistantRuns.WorkloadDemand;
import com.rulepilot.assistant.ContentCriticModel.CritiqueDraft;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolScopes;
import com.rulepilot.assistant.adapter.out.persistence.JpaAgentExecutionControl;
import com.rulepilot.assistant.application.BudgetedAgentInvocations;
import com.rulepilot.assistant.application.ConditionalGeneratedContentCritic;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.TeachingPlan;
import jakarta.persistence.EntityManager;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
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
class GroundedTeachingAgentPostgresBudgetIntegrationTest {

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
                .applySetting("hibernate.connection.pool_size", 8)
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .build();
        sessionFactory = new MetadataSources(registry)
                .buildMetadata()
                .buildSessionFactory();
    }

    @AfterAll
    static void stopHibernate() {
        if (sessionFactory != null) sessionFactory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    @Test
    void enforcesTheNineteenSectionWorkloadThroughTheProductionPostgresBudget() {
        UUID versionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        TeachingPlan plan = GroundedTeachingAgentWorkloadTest.plan(versionId);
        var tools = new GroundedTeachingAgentWorkloadTest.WorkflowTools(
                versionId, evidenceIds(), evidenceIds());
        var model = new GroundedTeachingAgentWorkloadTest.WorkflowModel();
        AgentExecutionControl execution = new TransactionalJpaExecutionControl(sessionFactory);
        var invocations = new BudgetedAgentInvocations(execution);
        GeneratedContentCritic critic = new ConditionalGeneratedContentCritic(
                request -> new CritiqueDraft(List.of()), invocations, false);
        NativeToolScopes scopes = (owner, documentVersionId, assistantRunId) -> Optional.of(
                new ToolScope(owner, documentVersionId, assistantRunId, Instant.now().plusSeconds(30)));
        var refiner = new TeachingSourcePageEvidenceRefiner(
                scopes, tools, new PolicyEvidenceVerifier(), invocations);
        VisualRulebookPageFacts visualFacts = VisualRulebookPageFacts.empty();
        var agent = new GroundedTeachingAgent(
                tools,
                model,
                new PolicyEvidenceVerifier(),
                critic,
                invocations,
                visualFacts,
                3,
                3,
                refiner,
                VisualRulebookCatalogerTestFixture.unavailable(tools, invocations, visualFacts));
        WorkloadDemand demand = agent.workload(plan);
        Instant startedAt = Instant.now();
        insertAssistantRun(runId, plan.id(), startedAt);
        execution.initialize(
                runId,
                new BudgetLimits(
                        40,
                        demand.requiredToolCalls(),
                        demand.requiredModelCalls(),
                        5_000_000,
                        Duration.ofMinutes(5)),
                startedAt);

        var lesson = agent.createBase(plan, runId, null, ignored -> {});

        BudgetSnapshot budget = execution.budget(runId);
        List<ActivitySnapshot> activities = execution.activities(runId);
        // The durable admission must cover the longest mutually exclusive page-catalog branch for every bound page:
        // initial image semantics, OCR after a typed-contract failure, then semantics with the changed transcript.
        // This fixture's visual catalog is unavailable, so its lower actual usage remains asserted independently.
        assertThat(demand).isEqualTo(new WorkloadDemand(95, 134));
        assertThat(lesson.sections()).hasSize(19).allSatisfy(section ->
                assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED));
        assertThat(model.maximumConcurrentCalls()).isGreaterThanOrEqualTo(3);
        assertThat(model.attempts("topic-7")).isEqualTo(2);
        assertThat(tools.searches()).isEqualTo(57);
        assertThat(tools.visualPageReads()).isEqualTo(19);
        assertThat(tools.canonicalFallbackReads()).isOne();
        assertThat(budget.maxToolCalls()).isEqualTo(demand.requiredToolCalls());
        assertThat(budget.maxModelCalls()).isEqualTo(demand.requiredModelCalls());
        assertThat(budget.usedToolCalls()).isEqualTo(77).isLessThanOrEqualTo(budget.maxToolCalls());
        assertThat(budget.usedModelCalls()).isEqualTo(21).isLessThanOrEqualTo(budget.maxModelCalls());
        assertThat(activities).filteredOn(activity -> activity.type() == ActivityType.TOOL).hasSize(77);
        assertThat(activities).filteredOn(activity -> activity.type() == ActivityType.MODEL).hasSize(20);
        assertThat(activities).filteredOn(activity -> activity.type() == ActivityType.CRITIC)
                .singleElement()
                .satisfies(activity -> {
                    assertThat(activity.operation()).isEqualTo("reviewPublishedTeachingLesson");
                    assertThat(activity.outcome()).isEqualTo(ActivityOutcome.SUCCEEDED);
                });
    }

    private static Map<Integer, UUID> evidenceIds() {
        return IntStream.rangeClosed(1, 19)
                .boxed()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        position -> position, ignored -> UUID.randomUUID()));
    }

    private static void insertAssistantRun(UUID runId, UUID subjectId, Instant startedAt) {
        jdbc.update(
                """
                INSERT INTO assistant_run (
                    id, mode, subject_id, owner_username, state, revision, created_at, updated_at
                ) VALUES (?, 'TEACHING', ?, 'player', 'RECEIVED', 1, ?, ?)
                """,
                runId,
                subjectId,
                Timestamp.from(startedAt),
                Timestamp.from(startedAt));
    }

    private static void enableProductionExtensions() {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize PostgreSQL extensions", exception);
        }
    }

    /** Runs every production persistence method in its own transaction, matching each REQUIRES_NEW boundary. */
    private static final class TransactionalJpaExecutionControl implements AgentExecutionControl {
        private final SessionFactory sessionFactory;

        private TransactionalJpaExecutionControl(SessionFactory sessionFactory) {
            this.sessionFactory = sessionFactory;
        }

        @Override
        public void initialize(UUID runId, BudgetLimits limits, Instant startedAt) {
            inTransaction(control -> {
                control.initialize(runId, limits, startedAt);
                return null;
            });
        }

        @Override
        public void assertStepAllowed(UUID runId, long nextStep) {
            inTransaction(control -> {
                control.assertStepAllowed(runId, nextStep);
                return null;
            });
        }

        @Override
        public InvocationReservation reserve(
                UUID runId, ActivityType type, String operation, int estimatedInputTokens) {
            return inTransaction(control -> control.reserve(runId, type, operation, estimatedInputTokens));
        }

        @Override
        public void complete(
                InvocationReservation reservation,
                ActivityOutcome outcome,
                int estimatedOutputTokens,
                long latencyMs,
                String summary) {
            inTransaction(control -> {
                control.complete(reservation, outcome, estimatedOutputTokens, latencyMs, summary);
                return null;
            });
        }

        @Override
        public void record(
                UUID runId, ActivityType type, String operation, ActivityOutcome outcome, String summary) {
            inTransaction(control -> {
                control.record(runId, type, operation, outcome, summary);
                return null;
            });
        }

        @Override
        public void stopRunning(UUID runId, ActivityOutcome outcome, String summary) {
            inTransaction(control -> {
                control.stopRunning(runId, outcome, summary);
                return null;
            });
        }

        @Override
        public void stopRunning(
                UUID runId, String operation, ActivityOutcome outcome, String summary) {
            inTransaction(control -> {
                control.stopRunning(runId, operation, outcome, summary);
                return null;
            });
        }

        @Override
        public void requestCancellation(UUID runId, String ownerUsername) {
            inTransaction(control -> {
                control.requestCancellation(runId, ownerUsername);
                return null;
            });
        }

        @Override
        public BudgetSnapshot budget(UUID runId) {
            return inTransaction(control -> control.budget(runId));
        }

        @Override
        public List<ActivitySnapshot> activities(UUID runId) {
            return inTransaction(control -> control.activities(runId));
        }

        @Override
        public List<ActivitySnapshot> activitiesAfter(UUID runId, long afterSequence) {
            return inTransaction(control -> control.activitiesAfter(runId, afterSequence));
        }

        private <T> T inTransaction(Function<JpaAgentExecutionControl, T> work) {
            EntityManager entityManager = sessionFactory.createEntityManager();
            JpaAgentExecutionControl control = new JpaAgentExecutionControl();
            ReflectionTestUtils.setField(control, "entityManager", entityManager);
            var transaction = entityManager.getTransaction();
            try {
                transaction.begin();
                T result = work.apply(control);
                transaction.commit();
                return result;
            } catch (RuntimeException | Error failure) {
                if (transaction.isActive()) transaction.rollback();
                throw failure;
            } finally {
                entityManager.close();
            }
        }
    }
}
