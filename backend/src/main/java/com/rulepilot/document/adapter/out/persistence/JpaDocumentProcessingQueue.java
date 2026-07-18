package com.rulepilot.document.adapter.out.persistence;

import com.rulepilot.document.application.DocumentProcessingQueue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JpaDocumentProcessingQueue implements DocumentProcessingQueue {

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
