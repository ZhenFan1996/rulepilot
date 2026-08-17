package com.rulepilot.document.adapter.out.persistence;

import com.rulepilot.document.application.UploadedRulebookTeachingHandoffStore;
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
class JpaUploadedRulebookTeachingHandoffStore implements UploadedRulebookTeachingHandoffStore {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Snapshot request(
            UUID handoffId,
            UUID documentVersionId,
            String ownerUsername,
            String learningGoal,
            Instant now) {
        int changed = entityManager
                .createNativeQuery(
                        """
                        INSERT INTO uploaded_rulebook_teaching_handoff (
                            id, document_version_id, owner_username, learning_goal, state,
                            preparation_run_id, error_code, created_at, updated_at
                        )
                        SELECT :handoffId, version.id, document.created_by, :learningGoal,
                               'WAITING_FOR_DOCUMENT', NULL, NULL, :now, :now
                        FROM document_version version
                        JOIN rule_document document ON document.id = version.document_id
                        WHERE version.id = :versionId AND document.created_by = :owner
                        ON CONFLICT (document_version_id) DO UPDATE
                        SET learning_goal = EXCLUDED.learning_goal,
                            state = 'WAITING_FOR_DOCUMENT',
                            preparation_run_id = NULL,
                            error_code = NULL,
                            updated_at = EXCLUDED.updated_at
                        WHERE uploaded_rulebook_teaching_handoff.state = 'FAILED'
                        """)
                .setParameter("handoffId", handoffId)
                .setParameter("versionId", documentVersionId)
                .setParameter("owner", ownerUsername)
                .setParameter("learningGoal", learningGoal)
                .setParameter("now", now)
                .executeUpdate();
        entityManager.flush();
        var existing = findOwnedByVersion(documentVersionId, ownerUsername);
        if (existing == null) {
            throw new IllegalArgumentException("uploaded rulebook document does not exist");
        }
        if (changed == 0 && existing.state() == State.FAILED) {
            throw new IllegalStateException("uploaded rulebook teaching handoff could not be retried");
        }
        return existing;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Snapshot> findOwned(UUID handoffId, String ownerUsername) {
        return entityManager
                .createQuery(
                        """
                        select handoff from UploadedRulebookTeachingHandoffEntity handoff
                        where handoff.id = :handoffId and handoff.ownerUsername = :owner
                        """,
                        UploadedRulebookTeachingHandoffEntity.class)
                .setParameter("handoffId", handoffId)
                .setParameter("owner", ownerUsername)
                .getResultStream()
                .findFirst()
                .map(UploadedRulebookTeachingHandoffEntity::toSnapshot);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Snapshot retry(
            UUID handoffId, UUID expectedPreparationRunId, String ownerUsername, Instant now) {
        String eligibleState = expectedPreparationRunId == null
                ? "handoff.state = 'FAILED'"
                : "(handoff.state = 'FAILED' or (handoff.state = 'LAUNCHED' and handoff.preparationRunId = :expectedRunId))";
        var update = entityManager.createQuery(
                """
                update UploadedRulebookTeachingHandoffEntity handoff
                set handoff.state = 'WAITING_FOR_DOCUMENT',
                    handoff.preparationRunId = null,
                    handoff.errorCode = null,
                    handoff.updatedAt = :now
                where handoff.id = :handoffId
                  and handoff.ownerUsername = :owner
                  and (handoff.state <> 'FAILED'
                       or coalesce(handoff.errorCode, '') <> 'DOCUMENT_PROCESSING_FAILED')
                  and """ + eligibleState);
        update.setParameter("handoffId", handoffId)
                .setParameter("owner", ownerUsername)
                .setParameter("now", now);
        if (expectedPreparationRunId != null) update.setParameter("expectedRunId", expectedPreparationRunId);
        int changed = update.executeUpdate();
        entityManager.flush();
        Snapshot current = findOwned(handoffId, ownerUsername)
                .orElseThrow(() -> new IllegalArgumentException("uploaded teaching handoff does not exist"));
        if (changed == 1
                || current.state() == State.WAITING_FOR_DOCUMENT
                || current.state() == State.LAUNCHING
                || current.state() == State.LAUNCHED
                        && !java.util.Objects.equals(current.preparationRunId(), expectedPreparationRunId)) {
            return current;
        }
        throw new IllegalStateException("uploaded rulebook teaching handoff could not be retried");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean dismissOwned(
            UUID handoffId,
            String ownerUsername,
            State expectedState,
            UUID expectedPreparationRunId) {
        String runCondition = expectedPreparationRunId == null
                ? "and handoff.preparationRunId is null"
                : "and handoff.preparationRunId = :expectedRunId";
        var delete = entityManager.createQuery(
                """
                delete from UploadedRulebookTeachingHandoffEntity handoff
                where handoff.id = :handoffId
                  and handoff.ownerUsername = :owner
                  and handoff.state = :expectedState
                """ + runCondition);
        delete.setParameter("handoffId", handoffId)
                .setParameter("owner", ownerUsername)
                .setParameter("expectedState", expectedState.name());
        if (expectedPreparationRunId != null) delete.setParameter("expectedRunId", expectedPreparationRunId);
        return delete.executeUpdate() == 1;
    }

    @Override
    public List<Snapshot> findRecentOwned(String ownerUsername, int limit) {
        return entityManager
                .createQuery(
                        """
                        select handoff from UploadedRulebookTeachingHandoffEntity handoff
                        where handoff.ownerUsername = :owner
                        order by handoff.createdAt desc
                        """,
                        UploadedRulebookTeachingHandoffEntity.class)
                .setParameter("owner", ownerUsername)
                .setMaxResults(limit)
                .getResultList()
                .stream()
                .map(UploadedRulebookTeachingHandoffEntity::toSnapshot)
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Snapshot> claimReady(int limit, Instant now) {
        return claimReady(null, limit, now);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Snapshot> claimReadyForDocument(UUID documentVersionId, int limit, Instant now) {
        if (documentVersionId == null) throw new IllegalArgumentException("ready document version is required");
        return claimReady(documentVersionId, limit, now);
    }

    private List<Snapshot> claimReady(UUID documentVersionId, int limit, Instant now) {
        List<UploadedRulebookTeachingHandoffEntity> claimed = entityManager
                .createQuery(
                        """
                        select handoff
                        from UploadedRulebookTeachingHandoffEntity handoff, DocumentVersionEntity version
                        where handoff.documentVersionId = version.id
                          and (:documentVersionId is null or version.id = :documentVersionId)
                          and handoff.state = 'WAITING_FOR_DOCUMENT'
                          and version.processingStatus = 'READY'
                        order by handoff.createdAt
                        """,
                        UploadedRulebookTeachingHandoffEntity.class)
                .setParameter("documentVersionId", documentVersionId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setMaxResults(limit)
                .getResultList();
        for (UploadedRulebookTeachingHandoffEntity handoff : claimed) {
            handoff.state = State.LAUNCHING.name();
            handoff.updatedAt = now;
        }
        entityManager.flush();
        return claimed.stream().map(UploadedRulebookTeachingHandoffEntity::toSnapshot).toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int failUnusableDocuments(Instant now) {
        return entityManager
                .createNativeQuery(
                        """
                        UPDATE uploaded_rulebook_teaching_handoff AS handoff
                        SET state = 'FAILED',
                            preparation_run_id = NULL,
                            error_code = 'DOCUMENT_PROCESSING_FAILED',
                            updated_at = :now
                        FROM document_version AS version
                        WHERE handoff.document_version_id = version.id
                          AND handoff.state = 'WAITING_FOR_DOCUMENT'
                          AND version.processing_status = 'FAILED'
                        """)
                .setParameter("now", now)
                .executeUpdate();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeLaunch(UUID handoffId, UUID preparationRunId, Instant now) {
        int updated = entityManager
                .createQuery(
                        """
                        update UploadedRulebookTeachingHandoffEntity handoff
                        set handoff.state = 'LAUNCHED',
                            handoff.preparationRunId = :runId,
                            handoff.errorCode = null,
                            handoff.updatedAt = :now
                        where handoff.id = :handoffId and handoff.state = 'LAUNCHING'
                        """)
                .setParameter("runId", preparationRunId)
                .setParameter("now", now)
                .setParameter("handoffId", handoffId)
                .executeUpdate();
        if (updated != 1) throw new IllegalStateException("uploaded teaching handoff is not launching");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failLaunch(UUID handoffId, String errorCode, Instant now) {
        int updated = entityManager
                .createQuery(
                        """
                        update UploadedRulebookTeachingHandoffEntity handoff
                        set handoff.state = 'FAILED',
                            handoff.preparationRunId = null,
                            handoff.errorCode = :errorCode,
                            handoff.updatedAt = :now
                        where handoff.id = :handoffId and handoff.state = 'LAUNCHING'
                        """)
                .setParameter("errorCode", errorCode)
                .setParameter("now", now)
                .setParameter("handoffId", handoffId)
                .executeUpdate();
        if (updated != 1) throw new IllegalStateException("uploaded teaching handoff is not launching");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int failInterruptedLaunches(Instant now) {
        return entityManager
                .createQuery(
                        """
                        update UploadedRulebookTeachingHandoffEntity handoff
                        set handoff.state = 'FAILED',
                            handoff.preparationRunId = null,
                            handoff.errorCode = 'APPLICATION_RESTARTED',
                            handoff.updatedAt = :now
                        where handoff.state = 'LAUNCHING'
                        """)
                .setParameter("now", now)
                .executeUpdate();
    }

    private Snapshot findOwnedByVersion(UUID documentVersionId, String ownerUsername) {
        return entityManager
                .createQuery(
                        """
                        select handoff from UploadedRulebookTeachingHandoffEntity handoff
                        where handoff.documentVersionId = :versionId and handoff.ownerUsername = :owner
                        """,
                        UploadedRulebookTeachingHandoffEntity.class)
                .setParameter("versionId", documentVersionId)
                .setParameter("owner", ownerUsername)
                .getResultStream()
                .findFirst()
                .map(UploadedRulebookTeachingHandoffEntity::toSnapshot)
                .orElse(null);
    }
}

@Entity(name = "UploadedRulebookTeachingHandoffEntity")
@Table(name = "uploaded_rulebook_teaching_handoff")
class UploadedRulebookTeachingHandoffEntity {

    @Id UUID id;
    @Column(name = "document_version_id", nullable = false) UUID documentVersionId;
    @Column(name = "owner_username", nullable = false) String ownerUsername;
    @Column(name = "learning_goal", columnDefinition = "text") String learningGoal;
    @Column(nullable = false) String state;
    @Column(name = "preparation_run_id") UUID preparationRunId;
    @Column(name = "error_code") String errorCode;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;

    protected UploadedRulebookTeachingHandoffEntity() {}

    UploadedRulebookTeachingHandoffStore.Snapshot toSnapshot() {
        return new UploadedRulebookTeachingHandoffStore.Snapshot(
                id,
                documentVersionId,
                ownerUsername,
                learningGoal,
                UploadedRulebookTeachingHandoffStore.State.valueOf(state),
                preparationRunId,
                errorCode,
                createdAt,
                updatedAt);
    }
}
