package com.rulepilot.document.adapter.out.persistence;

import com.rulepilot.document.application.OfficialRulebookImportJobRepository;
import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.OfficialRulebookImportJob;
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
        entityManager
                .createQuery(
                        """
                        update OfficialRulebookImportJobEntity job
                        set job.stage = 'FAILED', job.errorCode = :errorCode,
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
        entity.createdAt = job.createdAt();
        entity.updatedAt = job.updatedAt();
        entity.completedAt = job.completedAt();
        return entity;
    }

    OfficialRulebookImportJob toDomain() {
        return new OfficialRulebookImportJob(
                id, ownerUsername, editionId, title, DocumentSourceType.valueOf(sourceType), sourceUrl,
                OfficialRulebookImportJob.Stage.valueOf(stage), downloadedBytes, totalBytes,
                documentVersionId, duplicate, errorCode, createdAt, updatedAt, completedAt);
    }
}
