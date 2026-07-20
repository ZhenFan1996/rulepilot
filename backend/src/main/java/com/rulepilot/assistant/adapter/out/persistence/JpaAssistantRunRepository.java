package com.rulepilot.assistant.adapter.out.persistence;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns.StepSnapshot;
import com.rulepilot.assistant.application.AssistantRunRepository;
import com.rulepilot.assistant.domain.AssistantRun;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JpaAssistantRunRepository implements AssistantRunRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void insert(AssistantRun run, String summary) {
        entityManager
                .createNativeQuery(
                        """
                        insert into assistant_run (
                            id, mode, subject_id, owner_username, state, revision,
                            created_at, updated_at, completed_at, last_error_code
                        ) values (
                            :id, :mode, :subjectId, :owner, :state, :revision,
                            :createdAt, :updatedAt, :completedAt, :errorCode
                        )
                        """)
                .setParameter("id", run.id())
                .setParameter("mode", run.mode().name())
                .setParameter("subjectId", run.subjectId())
                .setParameter("owner", run.ownerUsername())
                .setParameter("state", run.state().name())
                .setParameter("revision", run.revision())
                .setParameter("createdAt", run.createdAt())
                .setParameter("updatedAt", run.updatedAt())
                .setParameter("completedAt", run.completedAt())
                .setParameter("errorCode", run.lastErrorCode())
                .executeUpdate();
        insertStep(run.id(), run.revision(), null, run.state(), summary, run.updatedAt());
    }

    @Override
    public boolean update(AssistantRun previous, AssistantRun changed, String summary) {
        int updated = entityManager
                .createNativeQuery(
                        """
                        update assistant_run
                        set state = :state,
                            revision = :nextRevision,
                            updated_at = :updatedAt,
                            completed_at = :completedAt,
                            last_error_code = :errorCode
                        where id = :id and revision = :expectedRevision
                        """)
                .setParameter("state", changed.state().name())
                .setParameter("nextRevision", changed.revision())
                .setParameter("updatedAt", changed.updatedAt())
                .setParameter("completedAt", changed.completedAt())
                .setParameter("errorCode", changed.lastErrorCode())
                .setParameter("id", changed.id())
                .setParameter("expectedRevision", previous.revision())
                .executeUpdate();
        if (updated == 1) {
            insertStep(
                    changed.id(), changed.revision(), previous.state(), changed.state(), summary, changed.updatedAt());
        }
        return updated == 1;
    }

    @Override
    public Optional<AssistantRun> find(UUID runId) {
        List<?> rows = entityManager
                .createNativeQuery(
                        """
                        select id, mode, subject_id, owner_username, state, revision,
                               created_at, updated_at, completed_at, last_error_code
                        from assistant_run
                        where id = :runId
                        """)
                .setParameter("runId", runId)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(run((Object[]) rows.getFirst()));
    }

    @Override
    public Optional<AssistantRun> findLatest(
            AssistantRunMode mode, UUID subjectId, String ownerUsername) {
        List<?> rows = entityManager
                .createNativeQuery(
                        """
                        select id, mode, subject_id, owner_username, state, revision,
                               created_at, updated_at, completed_at, last_error_code
                        from assistant_run
                        where mode = :mode and subject_id = :subjectId and owner_username = :owner
                        order by created_at desc
                        fetch first 1 row only
                        """)
                .setParameter("mode", mode.name())
                .setParameter("subjectId", subjectId)
                .setParameter("owner", ownerUsername)
                .getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(run((Object[]) rows.getFirst()));
    }

    @Override
    public List<AssistantRun> findNonTerminal(AssistantRunMode mode) {
        return entityManager
                .createNativeQuery(
                        """
                        select id, mode, subject_id, owner_username, state, revision,
                               created_at, updated_at, completed_at, last_error_code
                        from assistant_run
                        where mode = :mode
                          and state not in ('COMPLETED', 'INSUFFICIENT_EVIDENCE', 'FAILED', 'DEGRADED')
                        order by created_at
                        """)
                .setParameter("mode", mode.name())
                .getResultList()
                .stream()
                .map(result -> run((Object[]) result))
                .toList();
    }

    @Override
    public List<AssistantRun> findNonTerminalOwned(AssistantRunMode mode, String ownerUsername) {
        return entityManager
                .createNativeQuery(
                        """
                        select id, mode, subject_id, owner_username, state, revision,
                               created_at, updated_at, completed_at, last_error_code
                        from assistant_run
                        where mode = :mode
                          and owner_username = :owner
                          and state not in ('COMPLETED', 'INSUFFICIENT_EVIDENCE', 'FAILED', 'DEGRADED')
                        order by created_at
                        """)
                .setParameter("mode", mode.name())
                .setParameter("owner", ownerUsername)
                .getResultList()
                .stream()
                .map(result -> run((Object[]) result))
                .toList();
    }

    @Override
    public List<StepSnapshot> steps(UUID runId) {
        return entityManager
                .createNativeQuery(
                        """
                        select sequence_number, from_state, to_state, step_summary, occurred_at
                        from assistant_run_step
                        where assistant_run_id = :runId
                        order by sequence_number
                        """)
                .setParameter("runId", runId)
                .getResultList()
                .stream()
                .map(result -> step((Object[]) result))
                .toList();
    }

    private void insertStep(
            UUID runId,
            long sequence,
            AssistantRunState fromState,
            AssistantRunState toState,
            String summary,
            Instant occurredAt) {
        entityManager
                .createNativeQuery(
                        """
                        insert into assistant_run_step (
                            id, assistant_run_id, sequence_number, from_state, to_state, step_summary, occurred_at
                        ) values (
                            :id, :runId, :sequence, :fromState, :toState, :summary, :occurredAt
                        )
                        """)
                .setParameter("id", UUID.randomUUID())
                .setParameter("runId", runId)
                .setParameter("sequence", sequence)
                .setParameter("fromState", fromState == null ? null : fromState.name())
                .setParameter("toState", toState.name())
                .setParameter("summary", summary)
                .setParameter("occurredAt", occurredAt)
                .executeUpdate();
    }

    private StepSnapshot step(Object[] row) {
        return new StepSnapshot(
                ((Number) row[0]).longValue(),
                row[1] == null ? null : AssistantRunState.valueOf((String) row[1]),
                AssistantRunState.valueOf((String) row[2]),
                (String) row[3],
                (Instant) row[4]);
    }

    private AssistantRun run(Object[] row) {
        return new AssistantRun(
                (UUID) row[0],
                AssistantRunMode.valueOf((String) row[1]),
                (UUID) row[2],
                (String) row[3],
                AssistantRunState.valueOf((String) row[4]),
                ((Number) row[5]).longValue(),
                (Instant) row[6],
                (Instant) row[7],
                (Instant) row[8],
                (String) row[9]);
    }
}
