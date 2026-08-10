package com.rulepilot.document.adapter.out.persistence;

import com.rulepilot.document.application.OfficialRulebookImportJobRepository;
import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.OfficialRulebookImportJob;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("!test")
class JpaOfficialRulebookImportJobRepository implements OfficialRulebookImportJobRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(OfficialRulebookImportJob job) {
        entityManager.persist(OfficialRulebookImportJobEntity.from(job));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OfficialRulebookImportJob> findOwned(UUID jobId, String ownerUsername) {
        return entityManager
                .createQuery(
                        "select job from OfficialRulebookImportJobEntity job where job.id = :jobId and job.ownerUsername = :owner",
                        OfficialRulebookImportJobEntity.class)
                .setParameter("jobId", jobId)
                .setParameter("owner", ownerUsername)
                .getResultStream()
                .findFirst()
                .map(OfficialRulebookImportJobEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OfficialRulebookImportJob> findActiveOwnedBySource(String ownerUsername, String sourceUrl) {
        return entityManager
                .createQuery(
                        """
                        select job from OfficialRulebookImportJobEntity job
                        where job.ownerUsername = :owner and job.sourceUrl = :sourceUrl
                          and job.stage not in ('COMPLETED', 'FAILED')
                        order by job.createdAt desc
                        """,
                        OfficialRulebookImportJobEntity.class)
                .setParameter("owner", ownerUsername)
                .setParameter("sourceUrl", sourceUrl)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(OfficialRulebookImportJobEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OfficialRulebookImportJob> findCompletedOwnedBySourceAndEdition(
            String ownerUsername, String sourceUrl, UUID editionId) {
        return entityManager
                .createQuery(
                        """
                        select job from OfficialRulebookImportJobEntity job
                        where job.ownerUsername = :owner and job.sourceUrl = :sourceUrl
                          and job.stage = 'COMPLETED' and job.documentVersionId is not null
                          and ((:editionId is null and job.editionId is null) or job.editionId = :editionId)
                        order by job.createdAt desc
                        """,
                        OfficialRulebookImportJobEntity.class)
                .setParameter("owner", ownerUsername)
                .setParameter("sourceUrl", sourceUrl)
                .setParameter("editionId", editionId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(OfficialRulebookImportJobEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OfficialRulebookImportJob> findRecentOwned(String ownerUsername, int limit) {
        return entityManager
                .createQuery(
                        "select job from OfficialRulebookImportJobEntity job where job.ownerUsername = :owner order by job.createdAt desc",
                        OfficialRulebookImportJobEntity.class)
                .setParameter("owner", ownerUsername)
                .setMaxResults(limit)
                .getResultList()
                .stream()
                .map(OfficialRulebookImportJobEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requestTeaching(UUID jobId, String learningGoal, Instant now) {
        int updated = entityManager
                .createQuery(
                        """
                        update OfficialRulebookImportJobEntity job
                        set job.teachingHandoffState = 'WAITING_FOR_DOCUMENT',
                            job.teachingLearningGoal = :learningGoal,
                            job.teachingPreparationRunId = null,
                            job.teachingErrorCode = null,
                            job.teachingHandoffUpdatedAt = :now,
                            job.updatedAt = :now
                        where job.id = :jobId
                          and job.stage <> 'FAILED'
                          and job.teachingHandoffState in ('NOT_REQUESTED', 'FAILED')
                        """)
                .setParameter("learningGoal", learningGoal)
                .setParameter("now", now)
                .setParameter("jobId", jobId)
                .executeUpdate();
        if (updated != 1) throw new IllegalStateException("official rulebook teaching handoff cannot be requested");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OfficialRulebookImportJob> claimReadyTeaching(int limit, Instant now) {
        List<OfficialRulebookImportJobEntity> claimed = entityManager
                .createQuery(
                        """
                        select job from OfficialRulebookImportJobEntity job, DocumentVersionEntity version
                        where job.documentVersionId = version.id
                          and job.stage = 'COMPLETED'
                          and job.teachingHandoffState = 'WAITING_FOR_DOCUMENT'
                          and version.processingStatus = 'READY'
                        order by job.createdAt
                        """,
                        OfficialRulebookImportJobEntity.class)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setMaxResults(limit)
                .getResultList();
        for (OfficialRulebookImportJobEntity job : claimed) {
            job.teachingHandoffState = "LAUNCHING";
            job.teachingHandoffUpdatedAt = now;
            job.updatedAt = now;
        }
        entityManager.flush();
        return claimed.stream().map(OfficialRulebookImportJobEntity::toDomain).toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int failTeachingForUnusableDocuments(Instant now) {
        return entityManager
                .createNativeQuery(
                        """
                        UPDATE official_rulebook_import_job AS job
                        SET teaching_handoff_state = 'FAILED',
                            teaching_preparation_run_id = NULL,
                            teaching_error_code = 'DOCUMENT_PROCESSING_FAILED',
                            teaching_handoff_updated_at = :now,
                            updated_at = :now
                        FROM document_version AS version
                        WHERE job.document_version_id = version.id
                          AND job.stage = 'COMPLETED'
                          AND job.teaching_handoff_state = 'WAITING_FOR_DOCUMENT'
                          AND version.processing_status = 'FAILED'
                        """)
                .setParameter("now", now)
                .executeUpdate();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeTeachingLaunch(UUID jobId, UUID preparationRunId, Instant now) {
        int updated = entityManager
                .createQuery(
                        """
                        update OfficialRulebookImportJobEntity job
                        set job.teachingHandoffState = 'LAUNCHED',
                            job.teachingPreparationRunId = :runId,
                            job.teachingErrorCode = null,
                            job.teachingHandoffUpdatedAt = :now,
                            job.updatedAt = :now
                        where job.id = :jobId and job.teachingHandoffState = 'LAUNCHING'
                        """)
                .setParameter("runId", preparationRunId)
                .setParameter("now", now)
                .setParameter("jobId", jobId)
                .executeUpdate();
        if (updated != 1) throw new IllegalStateException("official rulebook teaching handoff is not launching");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failTeachingLaunch(UUID jobId, String errorCode, Instant now) {
        entityManager
                .createQuery(
                        """
                        update OfficialRulebookImportJobEntity job
                        set job.teachingHandoffState = 'FAILED',
                            job.teachingPreparationRunId = null,
                            job.teachingErrorCode = :errorCode,
                            job.teachingHandoffUpdatedAt = :now,
                            job.updatedAt = :now
                        where job.id = :jobId and job.teachingHandoffState = 'LAUNCHING'
                        """)
                .setParameter("errorCode", errorCode)
                .setParameter("now", now)
                .setParameter("jobId", jobId)
                .executeUpdate();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int failInterruptedTeachingLaunches(Instant now) {
        return entityManager
                .createQuery(
                        """
                        update OfficialRulebookImportJobEntity job
                        set job.teachingHandoffState = 'FAILED',
                            job.teachingPreparationRunId = null,
                            job.teachingErrorCode = 'APPLICATION_RESTARTED',
                            job.teachingHandoffUpdatedAt = :now,
                            job.updatedAt = :now
                        where job.teachingHandoffState = 'LAUNCHING'
                        """)
                .setParameter("now", now)
                .executeUpdate();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgress(
            UUID jobId,
            OfficialRulebookImportJob.Stage stage,
            long downloadedBytes,
            Long totalBytes,
            Instant now) {
        int updated = entityManager
                .createQuery(
                        """
                        update OfficialRulebookImportJobEntity job
                        set job.stage = :stage,
                            job.downloadedBytes = :downloadedBytes,
                            job.totalBytes = :totalBytes,
                            job.updatedAt = :now
                        where job.id = :jobId and job.stage not in ('COMPLETED', 'FAILED')
                        """)
                .setParameter("stage", stage.name())
                .setParameter("downloadedBytes", downloadedBytes)
                .setParameter("totalBytes", totalBytes)
                .setParameter("now", now)
                .setParameter("jobId", jobId)
                .executeUpdate();
        if (updated != 1) throw new IllegalStateException("official rulebook import job is no longer active");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID jobId, UUID documentVersionId, boolean duplicate, Instant now) {
        int updated = entityManager
                .createQuery(
                        """
                        update OfficialRulebookImportJobEntity job
                        set job.stage = 'COMPLETED', job.documentVersionId = :versionId,
                            job.duplicate = :duplicate, job.errorCode = null,
                            job.updatedAt = :now, job.completedAt = :now
                        where job.id = :jobId and job.stage not in ('COMPLETED', 'FAILED')
                        """)
                .setParameter("versionId", documentVersionId)
                .setParameter("duplicate", duplicate)
                .setParameter("now", now)
                .setParameter("jobId", jobId)
                .executeUpdate();
        if (updated != 1) throw new IllegalStateException("official rulebook import job is no longer active");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID jobId, String errorCode, Instant now) {
        // The V73 constraint keeps this column null for NOT_REQUESTED. Referencing that typed column in both updates
        // prevents PostgreSQL from resolving Hibernate's otherwise-untyped null/Instant CASE expression as text.
        entityManager
                .createQuery(
                        """
                        update OfficialRulebookImportJobEntity job
                        set job.stage = 'FAILED', job.errorCode = :errorCode,
                            job.teachingHandoffState = case
                                when job.teachingHandoffState = 'NOT_REQUESTED' then 'NOT_REQUESTED'
                                else 'FAILED'
                            end,
                            job.teachingPreparationRunId = null,
                            job.teachingErrorCode = case
                                when job.teachingHandoffState = 'NOT_REQUESTED' then null
                                else 'IMPORT_FAILED'
                            end,
                            job.teachingHandoffUpdatedAt = case
                                when job.teachingHandoffState = 'NOT_REQUESTED' then job.teachingHandoffUpdatedAt
                                else :now
                            end,
                            job.updatedAt = :now, job.completedAt = :now
                        where job.id = :jobId and job.stage not in ('COMPLETED', 'FAILED')
                        """)
                .setParameter("errorCode", errorCode)
                .setParameter("now", now)
                .setParameter("jobId", jobId)
                .executeUpdate();
    }

    @Override
    @Transactional
    public int failInterrupted(Instant now) {
        return entityManager
                .createQuery(
                        """
                        update OfficialRulebookImportJobEntity job
                        set job.stage = 'FAILED', job.errorCode = 'APPLICATION_RESTARTED',
                            job.teachingHandoffState = case
                                when job.teachingHandoffState = 'NOT_REQUESTED' then 'NOT_REQUESTED'
                                else 'FAILED'
                            end,
                            job.teachingPreparationRunId = null,
                            job.teachingErrorCode = case
                                when job.teachingHandoffState = 'NOT_REQUESTED' then null
                                else 'APPLICATION_RESTARTED'
                            end,
                            job.teachingHandoffUpdatedAt = case
                                when job.teachingHandoffState = 'NOT_REQUESTED' then job.teachingHandoffUpdatedAt
                                else :now
                            end,
                            job.updatedAt = :now, job.completedAt = :now
                        where job.stage not in ('COMPLETED', 'FAILED')
                        """)
                .setParameter("now", now)
                .executeUpdate();
    }
}

@Entity(name = "OfficialRulebookImportJobEntity")
@Table(name = "official_rulebook_import_job")
class OfficialRulebookImportJobEntity {

    @Id UUID id;
    @Column(name = "owner_username", nullable = false) String ownerUsername;
    @Column(name = "edition_id") UUID editionId;
    @Column(nullable = false) String title;
    @Column(name = "source_type", nullable = false) String sourceType;
    @Column(name = "source_url", nullable = false, length = 2000) String sourceUrl;
    @Column(nullable = false) String stage;
    @Column(name = "downloaded_bytes", nullable = false) long downloadedBytes;
    @Column(name = "total_bytes") Long totalBytes;
    @Column(name = "document_version_id") UUID documentVersionId;
    @Column(nullable = false) boolean duplicate;
    @Column(name = "error_code") String errorCode;
    @Column(name = "teaching_handoff_state", nullable = false) String teachingHandoffState;
    @Column(name = "teaching_learning_goal", length = 500) String teachingLearningGoal;
    @Column(name = "teaching_preparation_run_id") UUID teachingPreparationRunId;
    @Column(name = "teaching_error_code") String teachingErrorCode;
    @Column(name = "teaching_handoff_updated_at") Instant teachingHandoffUpdatedAt;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Column(name = "completed_at") Instant completedAt;

    protected OfficialRulebookImportJobEntity() {}

    static OfficialRulebookImportJobEntity from(OfficialRulebookImportJob job) {
        var entity = new OfficialRulebookImportJobEntity();
        entity.id = job.id();
        entity.ownerUsername = job.ownerUsername();
        entity.editionId = job.editionId();
        entity.title = job.title();
        entity.sourceType = job.sourceType().name();
        entity.sourceUrl = job.sourceUrl();
        entity.stage = job.stage().name();
        entity.downloadedBytes = job.downloadedBytes();
        entity.totalBytes = job.totalBytes();
        entity.documentVersionId = job.documentVersionId();
        entity.duplicate = job.duplicate();
        entity.errorCode = job.errorCode();
        entity.teachingHandoffState = job.teachingHandoff().state().name();
        entity.teachingLearningGoal = job.teachingHandoff().learningGoal();
        entity.teachingPreparationRunId = job.teachingHandoff().preparationRunId();
        entity.teachingErrorCode = job.teachingHandoff().errorCode();
        entity.teachingHandoffUpdatedAt = job.teachingHandoff().updatedAt();
        entity.createdAt = job.createdAt();
        entity.updatedAt = job.updatedAt();
        entity.completedAt = job.completedAt();
        return entity;
    }

    OfficialRulebookImportJob toDomain() {
        return new OfficialRulebookImportJob(
                id, ownerUsername, editionId, title, DocumentSourceType.valueOf(sourceType), sourceUrl,
                OfficialRulebookImportJob.Stage.valueOf(stage), downloadedBytes, totalBytes,
                documentVersionId, duplicate, errorCode,
                new OfficialRulebookImportJob.TeachingHandoff(
                        OfficialRulebookImportJob.TeachingHandoffState.valueOf(teachingHandoffState),
                        teachingLearningGoal,
                        teachingPreparationRunId,
                        teachingErrorCode,
                        teachingHandoffUpdatedAt),
                createdAt, updatedAt, completedAt);
    }
}
