package com.rulepilot.assistant.adapter.out.persistence;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class JpaAgentExecutionControl implements AgentExecutionControl {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void initialize(UUID runId, BudgetLimits limits, Instant startedAt) {
        entityManager.createNativeQuery("""
                        insert into assistant_run_budget (
                            assistant_run_id, max_steps, max_tool_calls, max_model_calls, max_tokens, deadline_at
                        ) values (:runId, :maxSteps, :maxTools, :maxModels, :maxTokens, :deadline)
                        """)
                .setParameter("runId", runId)
                .setParameter("maxSteps", limits.maxSteps())
                .setParameter("maxTools", limits.maxToolCalls())
                .setParameter("maxModels", limits.maxModelCalls())
                .setParameter("maxTokens", limits.maxTokens())
                .setParameter("deadline", startedAt.plus(limits.timeout()))
                .executeUpdate();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void assertStepAllowed(UUID runId, long nextStep) {
        BudgetRow budget = lockBudget(runId);
        StopReason stopped = stopped(budget, Instant.now());
        if (stopped != null) throw new AgentExecutionStoppedException(stopped);
        if (nextStep > budget.maxSteps) throw new AgentExecutionStoppedException(StopReason.STEP_BUDGET);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InvocationReservation reserve(
            UUID runId, ActivityType type, String operation, int estimatedInputTokens) {
        if (type == ActivityType.VALIDATION) {
            throw new IllegalArgumentException("validation activities do not reserve invocation budget");
        }
        validateInvocation(type, operation, estimatedInputTokens);
        BudgetRow budget = lockBudget(runId);
        StopReason stopped = stopped(budget, Instant.now());
        if (stopped != null) throw new AgentExecutionStoppedException(stopped);
        int tools = budget.usedToolCalls + (type == ActivityType.TOOL ? 1 : 0);
        int models = budget.usedModelCalls + (type == ActivityType.MODEL || type == ActivityType.CRITIC ? 1 : 0);
        if (tools > budget.maxToolCalls) throw new AgentExecutionStoppedException(StopReason.TOOL_BUDGET);
        if (models > budget.maxModelCalls) throw new AgentExecutionStoppedException(StopReason.MODEL_BUDGET);
        if ((long) budget.usedTokens + estimatedInputTokens > budget.maxTokens) {
            throw new AgentExecutionStoppedException(StopReason.TOKEN_BUDGET);
        }
        entityManager.createNativeQuery("""
                        update assistant_run_budget
                        set used_tool_calls = :tools,
                            used_model_calls = :models,
                            used_tokens = used_tokens + :tokens
                        where assistant_run_id = :runId
                        """)
                .setParameter("tools", tools)
                .setParameter("models", models)
                .setParameter("tokens", estimatedInputTokens)
                .setParameter("runId", runId)
                .executeUpdate();
        InvocationReservation reservation =
                new InvocationReservation(UUID.randomUUID(), runId, type, operation.strip(), estimatedInputTokens);
        insertActivity(
                reservation.id(), runId, type, reservation.operation(), ActivityOutcome.RUNNING,
                estimatedInputTokens, 0, 0, "Work started");
        return reservation;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = AgentExecutionStoppedException.class)
    public void complete(
            InvocationReservation reservation,
            ActivityOutcome outcome,
            int estimatedOutputTokens,
            long latencyMs,
            String summary) {
        validateCompletion(reservation, outcome, estimatedOutputTokens, latencyMs, summary);
        BudgetRow budget = lockBudget(reservation.runId());
        StopReason stopReason = stopped(budget, Instant.now());
        if (stopReason == null && (long) budget.usedTokens + estimatedOutputTokens > budget.maxTokens) {
            stopReason = StopReason.TOKEN_BUDGET;
        }
        ActivityOutcome recordedOutcome = stopReason == null ? outcome : ActivityOutcome.REJECTED;
        entityManager.createNativeQuery("""
                        update assistant_run_budget
                        set used_tokens = used_tokens + :tokens
                        where assistant_run_id = :runId
                        """)
                .setParameter("tokens", estimatedOutputTokens)
                .setParameter("runId", reservation.runId())
                .executeUpdate();
        int updated = entityManager.createNativeQuery("""
                        update assistant_run_activity
                        set outcome = :outcome,
                            estimated_output_tokens = :outputTokens,
                            latency_ms = :latency,
                            summary = :summary
                        where id = :id
                          and assistant_run_id = :runId
                          and outcome = 'RUNNING'
                        """)
                .setParameter("outcome", recordedOutcome.name())
                .setParameter("outputTokens", estimatedOutputTokens)
                .setParameter("latency", latencyMs)
                .setParameter("summary", summary.strip())
                .setParameter("id", reservation.id())
                .setParameter("runId", reservation.runId())
                .executeUpdate();
        if (updated != 1) {
            if (stopReason != null) throw new AgentExecutionStoppedException(stopReason);
            throw new IllegalStateException("agent invocation activity is not running");
        }
        if (stopReason != null) throw new AgentExecutionStoppedException(stopReason);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            UUID runId, ActivityType type, String operation, ActivityOutcome outcome, String summary) {
        if (type != ActivityType.VALIDATION || outcome == ActivityOutcome.RUNNING) {
            throw new IllegalArgumentException("diagnostic activity type is invalid");
        }
        validateInvocation(type, operation, 0);
        InvocationReservation diagnostic =
                new InvocationReservation(UUID.randomUUID(), runId, type, operation.strip(), 0);
        validateCompletion(
                diagnostic,
                outcome,
                0,
                0,
                summary);
        lockBudget(runId);
        insertActivity(diagnostic.id(), runId, type, diagnostic.operation(), outcome, 0, 0, 0, summary);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stopRunning(UUID runId, ActivityOutcome outcome, String summary) {
        if (runId == null || outcome == null || outcome == ActivityOutcome.RUNNING
                || outcome == ActivityOutcome.SUCCEEDED || summary == null || summary.isBlank()
                || summary.length() > 240) {
            throw new IllegalArgumentException("running activity stop is invalid");
        }
        entityManager.createNativeQuery("""
                        update assistant_run_activity
                        set outcome = :outcome,
                            latency_ms = greatest(0, extract(epoch from (:now - occurred_at)) * 1000)::bigint,
                            summary = :summary
                        where assistant_run_id = :runId
                          and outcome = 'RUNNING'
                        """)
                .setParameter("outcome", outcome.name())
                .setParameter("now", Instant.now())
                .setParameter("summary", summary.strip())
                .setParameter("runId", runId)
                .executeUpdate();
    }

    private void insertActivity(
            UUID activityId,
            UUID runId,
            ActivityType type,
            String operation,
            ActivityOutcome outcome,
            int inputTokens,
            int outputTokens,
            long latencyMs,
            String summary) {
        Number sequence = (Number) entityManager.createNativeQuery("""
                        select coalesce(max(sequence_number), 0) + 1
                        from assistant_run_activity
                        where assistant_run_id = :runId
                        """)
                .setParameter("runId", runId)
                .getSingleResult();
        entityManager.createNativeQuery("""
                        insert into assistant_run_activity (
                            id, assistant_run_id, sequence_number, activity_type, operation_name, outcome,
                            estimated_input_tokens, estimated_output_tokens, latency_ms, summary, occurred_at
                        ) values (
                            :id, :runId, :sequence, :type, :operation, :outcome,
                            :inputTokens, :outputTokens, :latency, :summary, :occurredAt
                        )
                        """)
                .setParameter("id", activityId)
                .setParameter("runId", runId)
                .setParameter("sequence", sequence.longValue())
                .setParameter("type", type.name())
                .setParameter("operation", operation)
                .setParameter("outcome", outcome.name())
                .setParameter("inputTokens", inputTokens)
                .setParameter("outputTokens", outputTokens)
                .setParameter("latency", latencyMs)
                .setParameter("summary", summary.strip())
                .setParameter("occurredAt", Instant.now())
                .executeUpdate();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requestCancellation(UUID runId, String ownerUsername) {
        int updated = entityManager.createNativeQuery("""
                        update assistant_run_budget budget
                        set cancellation_requested_at = coalesce(cancellation_requested_at, :now)
                        from assistant_run run
                        where budget.assistant_run_id = run.id
                          and run.id = :runId
                          and run.owner_username = :owner
                          and run.completed_at is null
                        """)
                .setParameter("now", Instant.now())
                .setParameter("runId", runId)
                .setParameter("owner", requiredOwner(ownerUsername))
                .executeUpdate();
        if (updated != 1) throw new IllegalArgumentException("active assistant run does not exist");
    }

    @Override
    @Transactional(readOnly = true)
    public BudgetSnapshot budget(UUID runId) {
        Object[] row = (Object[]) entityManager.createNativeQuery("""
                        select max_steps, max_tool_calls, max_model_calls, max_tokens,
                               used_tool_calls, used_model_calls, used_tokens, deadline_at, cancellation_requested_at
                        from assistant_run_budget where assistant_run_id = :runId
                        """)
                .setParameter("runId", runId)
                .getSingleResult();
        return new BudgetSnapshot(
                number(row[0]), number(row[1]), number(row[2]), number(row[3]), number(row[4]), number(row[5]),
                number(row[6]), (Instant) row[7], (Instant) row[8]);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActivitySnapshot> activities(UUID runId) {
        return entityManager.createNativeQuery("""
                        select sequence_number, activity_type, operation_name, outcome,
                               estimated_input_tokens, estimated_output_tokens, latency_ms, summary, occurred_at
                        from assistant_run_activity where assistant_run_id = :runId order by sequence_number
                        """)
                .setParameter("runId", runId)
                .getResultList().stream()
                .map(result -> activity((Object[]) result))
                .toList();
    }

    private BudgetRow lockBudget(UUID runId) {
        Object[] row = (Object[]) entityManager.createNativeQuery("""
                        select max_steps, max_tool_calls, max_model_calls, max_tokens,
                               used_tool_calls, used_model_calls, used_tokens, deadline_at, cancellation_requested_at
                        from assistant_run_budget where assistant_run_id = :runId for update
                        """)
                .setParameter("runId", runId)
                .getSingleResult();
        return new BudgetRow(
                number(row[0]), number(row[1]), number(row[2]), number(row[3]), number(row[4]), number(row[5]),
                number(row[6]), (Instant) row[7], (Instant) row[8]);
    }

    private StopReason stopped(BudgetRow budget, Instant now) {
        if (budget.cancellationRequestedAt != null) return StopReason.CANCELLED;
        if (!now.isBefore(budget.deadlineAt)) return StopReason.TIMEOUT;
        if (budget.usedTokens >= budget.maxTokens) return StopReason.TOKEN_BUDGET;
        return null;
    }

    private ActivitySnapshot activity(Object[] row) {
        return new ActivitySnapshot(
                ((Number) row[0]).longValue(), ActivityType.valueOf((String) row[1]), (String) row[2],
                ActivityOutcome.valueOf((String) row[3]), number(row[4]), number(row[5]),
                ((Number) row[6]).longValue(), (String) row[7], (Instant) row[8]);
    }

    private void validateInvocation(ActivityType type, String operation, int tokens) {
        if (type == null || operation == null || operation.isBlank() || operation.length() > 80 || tokens < 0) {
            throw new IllegalArgumentException("agent invocation reservation is invalid");
        }
    }

    private void validateCompletion(
            InvocationReservation reservation, ActivityOutcome outcome, int tokens, long latency, String summary) {
        if (reservation == null || outcome == null || outcome == ActivityOutcome.RUNNING
                || tokens < 0 || latency < 0 || summary == null
                || summary.isBlank() || summary.length() > 240) {
            throw new IllegalArgumentException("agent invocation completion is invalid");
        }
    }

    private String requiredOwner(String owner) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("assistant run owner is required");
        return owner.strip();
    }

    private int number(Object value) {
        return ((Number) value).intValue();
    }

    private record BudgetRow(
            int maxSteps, int maxToolCalls, int maxModelCalls, int maxTokens,
            int usedToolCalls, int usedModelCalls, int usedTokens,
            Instant deadlineAt, Instant cancellationRequestedAt) {}
}
