package com.rulepilot.document.adapter.out.persistence;

import com.rulepilot.document.DocumentProcessingCommand;
import com.rulepilot.document.DocumentProcessingStage;
import com.rulepilot.document.application.DocumentProcessingDeduplicationStore;
import com.rulepilot.document.application.DocumentOutboxStore;
import com.rulepilot.document.application.DocumentProcessingJobStore;
import com.rulepilot.document.application.DocumentProcessingQueue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JpaDocumentProcessingQueue
        implements DocumentProcessingQueue,
                DocumentOutboxStore,
                DocumentProcessingJobStore,
                DocumentProcessingDeduplicationStore {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void enqueue(UUID documentVersionId, Instant occurredAt) {
        UUID jobId = UUID.randomUUID();
        entityManager.persist(new DocumentProcessingJobEntity(jobId, documentVersionId, occurredAt));
        entityManager.persist(new OutboxEventEntity(
                UUID.randomUUID(),
                documentVersionId,
                "DocumentProcessingRequested",
                payload(documentVersionId, jobId),
                occurredAt));
    }

    @Override
    public List<PendingEvent> findReady(Instant now, int limit) {
        return entityManager
                .createQuery(
                        """
                        select event
                        from OutboxEventEntity event
                        where event.publishedAt is null
                          and event.nextAttemptAt <= :now
                        order by event.occurredAt, event.id
                        """,
                        OutboxEventEntity.class)
                .setParameter("now", now)
                .setMaxResults(limit)
                .getResultList()
                .stream()
                .map(event -> new PendingEvent(event.id, event.eventType, event.payload))
                .toList();
    }

    @Override
    public void markPublished(UUID eventId, Instant publishedAt) {
        entityManager
                .createQuery(
                        """
                        update OutboxEventEntity event
                        set event.publishedAt = :publishedAt,
                            event.publishAttempts = event.publishAttempts + 1
                        where event.id = :eventId
                          and event.publishedAt is null
                        """)
                .setParameter("publishedAt", publishedAt)
                .setParameter("eventId", eventId)
                .executeUpdate();
    }

    @Override
    public void update(UUID jobId, DocumentProcessingStage stage, String status, Instant updatedAt) {
        int updated = entityManager
                .createQuery(
                        """
                        update DocumentProcessingJobEntity job
                        set job.stage = :stage,
                            job.status = :status,
                            job.updatedAt = :updatedAt
                        where job.id = :jobId
                        """)
                .setParameter("stage", stage.name())
                .setParameter("status", status)
                .setParameter("updatedAt", updatedAt)
                .setParameter("jobId", jobId)
                .executeUpdate();
        if (updated != 1) {
            throw new IllegalArgumentException("document processing job does not exist");
        }
    }

    @Override
    public boolean begin(DocumentProcessingCommand command, UUID eventId, int attempt, Instant startedAt) {
        int inserted = entityManager
                .createNativeQuery(
                        """
                        insert into processing_stage_execution (
                            id, document_version_id, processing_job_id, stage, pipeline_version,
                            first_event_id, status, attempt_count, started_at, updated_at
                        ) select
                            :id, :documentVersionId, :processingJobId, :stage, :pipelineVersion,
                            :eventId, 'RUNNING', :attempt, :startedAt, :startedAt
                        from document_version
                        where id = :documentVersionId
                        on conflict (document_version_id, stage, pipeline_version) do update
                        set processing_job_id = excluded.processing_job_id,
                            first_event_id = excluded.first_event_id,
                            status = 'RUNNING',
                            attempt_count = :attempt,
                            started_at = excluded.started_at,
                            completed_at = null,
                            updated_at = excluded.updated_at
                        where (processing_stage_execution.status = 'FAILED'
                               and (:attempt > 1 or processing_stage_execution.first_event_id = :eventId))
                           or (processing_stage_execution.status = 'RUNNING'
                               and processing_stage_execution.first_event_id = :eventId)
                        """)
                .setParameter("id", UUID.randomUUID())
                .setParameter("documentVersionId", command.documentVersionId())
                .setParameter("processingJobId", command.processingJobId())
                .setParameter("stage", command.stage().name())
                .setParameter("pipelineVersion", command.pipelineVersion())
                .setParameter("eventId", eventId)
                .setParameter("attempt", attempt)
                .setParameter("startedAt", startedAt)
                .executeUpdate();
        return inserted == 1;
    }

    @Override
    public void update(DocumentProcessingCommand command, String status, String errorCode, Instant completedAt) {
        int updated = entityManager
                .createNativeQuery(
                        """
                        update processing_stage_execution
                        set status = :status,
                            last_error_code = :errorCode,
                            completed_at = :completedAt,
                            updated_at = :completedAt
                        where document_version_id = :documentVersionId
                          and stage = :stage
                          and pipeline_version = :pipelineVersion
                          and status = 'RUNNING'
                        """)
                .setParameter("status", status)
                .setParameter("errorCode", errorCode)
                .setParameter("completedAt", completedAt)
                .setParameter("documentVersionId", command.documentVersionId())
                .setParameter("stage", command.stage().name())
                .setParameter("pipelineVersion", command.pipelineVersion())
                .executeUpdate();
        if (updated != 1) {
            throw new IllegalStateException("document processing stage is not running");
        }
    }

    @Override
    public Optional<DocumentProcessingCommand> findFailed(UUID jobId) {
        List<?> results = entityManager
                .createNativeQuery(
                        """
                        select document_version_id, processing_job_id, pipeline_version, stage
                        from processing_stage_execution
                        where processing_job_id = :jobId and status = 'FAILED'
                        order by updated_at desc
                        limit 1
                        """)
                .setParameter("jobId", jobId)
                .getResultList();
        if (results.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) results.getFirst();
        return Optional.of(new DocumentProcessingCommand(
                1,
                (UUID) row[0],
                (UUID) row[1],
                (String) row[2],
                DocumentProcessingStage.valueOf((String) row[3])));
    }

    @Override
    public void resetJob(UUID jobId, String stage, Instant updatedAt) {
        int updated = entityManager
                .createNativeQuery(
                        """
                        update processing_job
                        set stage = :stage, status = 'PENDING', updated_at = :updatedAt
                        where id = :jobId and status in ('FAILED', 'PENDING')
                        """)
                .setParameter("stage", stage)
                .setParameter("updatedAt", updatedAt)
                .setParameter("jobId", jobId)
                .executeUpdate();
        if (updated != 1) {
            throw new IllegalStateException("failed processing job does not exist");
        }
    }

    private String payload(UUID documentVersionId, UUID jobId) {
        return "{\"schemaVersion\":1,\"documentVersionId\":\"" + documentVersionId
                + "\",\"processingJobId\":\"" + jobId + "\",\"pipelineVersion\":\"v1\"}";
    }
}

@Entity(name = "DocumentProcessingJobEntity")
@Table(name = "processing_job")
class DocumentProcessingJobEntity {

    @Id UUID id;
    @Column(name = "document_version_id", nullable = false) UUID documentVersionId;
    @Column(name = "pipeline_version", nullable = false) String pipelineVersion;
    @Column(nullable = false) String stage;
    @Column(nullable = false) String status;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;

    protected DocumentProcessingJobEntity() {}

    DocumentProcessingJobEntity(UUID id, UUID documentVersionId, Instant occurredAt) {
        this.id = id;
        this.documentVersionId = documentVersionId;
        this.pipelineVersion = "v1";
        this.stage = "UPLOADED";
        this.status = "PENDING";
        this.createdAt = occurredAt;
        this.updatedAt = occurredAt;
    }
}

@Entity(name = "OutboxEventEntity")
@Table(name = "outbox_event")
class OutboxEventEntity {

    @Id UUID id;
    @Column(name = "aggregate_type", nullable = false) String aggregateType;
    @Column(name = "aggregate_id", nullable = false) UUID aggregateId;
    @Column(name = "event_type", nullable = false) String eventType;
    @ColumnTransformer(write = "?::jsonb")
    @Column(nullable = false, columnDefinition = "jsonb") String payload;
    @Column(name = "schema_version", nullable = false) int schemaVersion;
    @Column(name = "occurred_at", nullable = false) Instant occurredAt;
    @Column(name = "published_at") Instant publishedAt;
    @Column(name = "publish_attempts", nullable = false) int publishAttempts;
    @Column(name = "next_attempt_at", nullable = false) Instant nextAttemptAt;

    protected OutboxEventEntity() {}

    OutboxEventEntity(UUID id, UUID aggregateId, String eventType, String payload, Instant occurredAt) {
        this.id = id;
        this.aggregateType = "DOCUMENT_VERSION";
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.schemaVersion = 1;
        this.occurredAt = occurredAt;
        this.publishAttempts = 0;
        this.nextAttemptAt = occurredAt;
    }
}
