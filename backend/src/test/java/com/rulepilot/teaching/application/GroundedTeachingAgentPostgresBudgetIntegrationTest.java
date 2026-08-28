package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivitySnapshot;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionControl.BudgetLimits;
import com.rulepilot.assistant.AgentExecutionControl.BudgetSnapshot;
import com.rulepilot.assistant.AgentExecutionControl.InvocationReservation;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import com.rulepilot.assistant.AgentWorkAlreadyClaimedException;
import com.rulepilot.assistant.AssistantRuns.WorkloadDemand;
import com.rulepilot.assistant.ContentCriticModel.CritiqueDraft;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
        NativeToolScopes scopes = (owner, documentVersionId, assistantRunId) -> Optional.of(
                new ToolScope(owner, documentVersionId, assistantRunId, Instant.now().plusSeconds(30)));
        var refiner = new TeachingSourcePageEvidenceRefiner(
                scopes, tools, new PolicyEvidenceVerifier(), invocations);
        VisualRulebookPageFacts visualFacts = VisualRulebookPageFacts.empty();
        var agent = new GroundedTeachingAgent(
                tools,
                model,
                new PolicyEvidenceVerifier(),
                new ConditionalGeneratedContentCritic(
                        request -> new CritiqueDraft(List.of()), invocations, false),
                invocations,
                visualFacts,
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
        // The durable admission covers the initial image interpretation plus one page-local repair or replay.
        // This fixture's visual catalog is unavailable, so its lower actual usage remains asserted independently.
        assertThat(demand).isEqualTo(new WorkloadDemand(95, 441));
        assertThat(lesson.sections()).hasSize(19).allSatisfy(section ->
                assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED));
        assertThat(model.maximumConcurrentCalls()).isOne();
        List<String> expectedCallOrder = new java.util.ArrayList<>(IntStream.rangeClosed(1, 19)
                .mapToObj(position -> "topic-" + position)
                .toList());
        expectedCallOrder.add(7, "topic-7");
        assertThat(model.callOrder()).containsExactlyElementsOf(expectedCallOrder);
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

    @Test
    void admitsQueuedWorkOnceAndAddsOnlyMeasuredContinuationQueueWait() {
        UUID runId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-29T01:00:00Z");
        Instant admittedAt = Instant.parse("2026-08-29T01:10:00Z");
        BudgetLimits limits = new BudgetLimits(40, 72, 72, 300_000, Duration.ofMinutes(30));
        insertAssistantRun(runId, UUID.randomUUID(), createdAt);
        AgentExecutionControl execution = new TransactionalJpaExecutionControl(sessionFactory);
        execution.initialize(runId, limits, createdAt);
        UUID activationId = UUID.randomUUID();

        execution.activate(runId, activationId, admittedAt);
        assertThat(execution.lockUnactivated(runId)).isFalse();
        execution.excludeQueueWait(runId, Duration.ofSeconds(17));
        execution.excludeQueueWait(runId, Duration.ofSeconds(23));

        BudgetSnapshot active = execution.budget(runId);
        assertThat(active.deadlineAt()).isEqualTo(admittedAt.plus(limits.timeout()).plusSeconds(40));
        assertThat(active.usedToolCalls()).isZero();
        assertThat(active.usedModelCalls()).isZero();
        assertThat(active.usedTokens()).isZero();
        execution.activate(runId, activationId, admittedAt.plusSeconds(1));
        assertThat(execution.budget(runId).deadlineAt())
                .isEqualTo(admittedAt.plus(limits.timeout()).plusSeconds(40));
        assertThatThrownBy(() -> execution.activate(runId, UUID.randomUUID(), admittedAt.plusSeconds(1)))
                .isInstanceOf(AgentWorkAlreadyClaimedException.class);

        execution.requestCancellation(runId, "player");
        Instant cancelledDeadline = execution.budget(runId).deadlineAt();
        assertThatThrownBy(() -> execution.excludeQueueWait(runId, Duration.ofMinutes(2)))
                .isInstanceOf(AgentExecutionStoppedException.class)
                .hasFieldOrPropertyWithValue("reason", StopReason.CANCELLED);
        assertThat(execution.budget(runId).deadlineAt()).isEqualTo(cancelledDeadline);
    }

    @Test
    void admitsExactlyOneWorkerWhenDuplicateDeliveriesRace() throws Exception {
        UUID runId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-29T01:00:00Z");
        BudgetLimits limits = new BudgetLimits(40, 72, 72, 300_000, Duration.ofMinutes(30));
        insertAssistantRun(runId, UUID.randomUUID(), createdAt);
        AgentExecutionControl execution = new TransactionalJpaExecutionControl(sessionFactory);
        execution.initialize(runId, limits, createdAt);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> claim = () -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    execution.activate(runId, UUID.randomUUID(), createdAt.plusSeconds(10));
                    return true;
                } catch (AgentWorkAlreadyClaimedException duplicate) {
                    return false;
                }
            };
            var first = executor.submit(claim);
            var second = executor.submit(claim);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aCommittedFinalizationMakesALateCancellationAnIdempotentNoOp() throws Exception {
        UUID runId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-29T01:00:00Z");
        BudgetLimits limits = new BudgetLimits(40, 72, 72, 300_000, Duration.ofMinutes(30));
        insertAssistantRun(runId, UUID.randomUUID(), createdAt);
        new TransactionalJpaExecutionControl(sessionFactory).initialize(runId, limits, createdAt);
        CountDownLatch finalizationLocked = new CountDownLatch(1);
        CountDownLatch cancellationStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var finalization = executor.submit(() -> {
                EntityManager entityManager = sessionFactory.createEntityManager();
                var transaction = entityManager.getTransaction();
                try {
                    transaction.begin();
                    persistenceControl(entityManager).assertFinalizationAllowed(runId);
                    finalizationLocked.countDown();
                    assertThat(cancellationStarted.await(5, TimeUnit.SECONDS)).isTrue();
                    entityManager.createNativeQuery("""
                                    update assistant_run
                                    set state = 'COMPLETED', revision = 2, completed_at = :completed, updated_at = :completed
                                    where id = :runId
                                    """)
                            .setParameter("completed", createdAt.plusSeconds(20))
                            .setParameter("runId", runId)
                            .executeUpdate();
                    transaction.commit();
                    return true;
                } finally {
                    if (transaction.isActive()) transaction.rollback();
                    entityManager.close();
                }
            });
            var cancellation = executor.submit(() -> {
                assertThat(finalizationLocked.await(5, TimeUnit.SECONDS)).isTrue();
                cancellationStarted.countDown();
                return new TransactionalJpaExecutionControl(sessionFactory)
                        .requestCancellationIfActive(runId, "player");
            });

            assertThat(finalization.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(cancellation.get(10, TimeUnit.SECONDS)).isFalse();
            assertThat(jdbc.queryForObject(
                            "select state from assistant_run where id = ?", String.class, runId))
                    .isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject(
                            "select cancellation_requested_at is null from assistant_run_budget where assistant_run_id = ?",
                            Boolean.class,
                            runId))
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationThatWinsTheBoundaryPreventsLateFinalization() throws Exception {
        UUID runId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-29T01:00:00Z");
        BudgetLimits limits = new BudgetLimits(40, 72, 72, 300_000, Duration.ofMinutes(30));
        insertAssistantRun(runId, UUID.randomUUID(), createdAt);
        new TransactionalJpaExecutionControl(sessionFactory).initialize(runId, limits, createdAt);
        CountDownLatch cancellationLocked = new CountDownLatch(1);
        CountDownLatch finalizationStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var cancellation = executor.submit(() -> {
                EntityManager entityManager = sessionFactory.createEntityManager();
                var transaction = entityManager.getTransaction();
                try {
                    transaction.begin();
                    assertThat(persistenceControl(entityManager)
                                    .requestCancellationIfActive(runId, "player"))
                            .isTrue();
                    cancellationLocked.countDown();
                    assertThat(finalizationStarted.await(5, TimeUnit.SECONDS)).isTrue();
                    entityManager.createNativeQuery("""
                                    update assistant_run
                                    set state = 'FAILED', revision = 2, completed_at = :completed,
                                        updated_at = :completed, last_error_code = 'AGENT_CANCELLED'
                                    where id = :runId
                                    """)
                            .setParameter("completed", createdAt.plusSeconds(20))
                            .setParameter("runId", runId)
                            .executeUpdate();
                    transaction.commit();
                    return true;
                } finally {
                    if (transaction.isActive()) transaction.rollback();
                    entityManager.close();
                }
            });
            var finalization = executor.submit(() -> {
                assertThat(cancellationLocked.await(5, TimeUnit.SECONDS)).isTrue();
                finalizationStarted.countDown();
                try {
                    new TransactionalJpaExecutionControl(sessionFactory).assertFinalizationAllowed(runId);
                    return null;
                } catch (AgentExecutionStoppedException stopped) {
                    return stopped.reason();
                }
            });

            assertThat(cancellation.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(finalization.get(10, TimeUnit.SECONDS)).isEqualTo(StopReason.CANCELLED);
            assertThat(jdbc.queryForObject(
                            "select state from assistant_run where id = ?", String.class, runId))
                    .isEqualTo("FAILED");
            assertThat(jdbc.queryForObject(
                            "select cancellation_requested_at is not null from assistant_run_budget where assistant_run_id = ?",
                            Boolean.class,
                            runId))
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }
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

    private static JpaAgentExecutionControl persistenceControl(EntityManager entityManager) {
        JpaAgentExecutionControl control = new JpaAgentExecutionControl();
        ReflectionTestUtils.setField(control, "entityManager", entityManager);
        return control;
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
        public void activate(UUID runId, Instant startedAt) {
            inTransaction(control -> {
                control.activate(runId, startedAt);
                return null;
            });
        }

        @Override
        public void activate(UUID runId, UUID activationId, Instant startedAt) {
            inTransaction(control -> {
                control.activate(runId, activationId, startedAt);
                return null;
            });
        }

        @Override
        public boolean lockUnactivated(UUID runId) {
            return inTransaction(control -> control.lockUnactivated(runId));
        }

        @Override
        public boolean lockUnactivatedOrOwned(UUID runId, UUID activationId) {
            return inTransaction(control -> control.lockUnactivatedOrOwned(runId, activationId));
        }

        @Override
        public void excludeQueueWait(UUID runId, Duration queueWait) {
            inTransaction(control -> {
                control.excludeQueueWait(runId, queueWait);
                return null;
            });
        }

        @Override
        public void assertFinalizationAllowed(UUID runId) {
            inTransaction(control -> {
                control.assertFinalizationAllowed(runId);
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
            requestCancellationIfActive(runId, ownerUsername);
        }

        @Override
        public boolean requestCancellationIfActive(UUID runId, String ownerUsername) {
            return inTransaction(control -> control.requestCancellationIfActive(runId, ownerUsername));
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
            JpaAgentExecutionControl control = persistenceControl(entityManager);
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
