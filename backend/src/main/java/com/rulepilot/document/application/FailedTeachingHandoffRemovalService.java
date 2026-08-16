package com.rulepilot.document.application;

import com.rulepilot.document.FailedTeachingHandoffRemovals;
import com.rulepilot.document.FailedTeachingHandoffRemovals.Candidate;
import com.rulepilot.document.FailedTeachingHandoffRemovals.HandoffState;
import com.rulepilot.document.FailedTeachingHandoffRemovals.Origin;
import com.rulepilot.document.domain.OfficialRulebookImportJob;
import com.rulepilot.document.domain.ProcessingStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class FailedTeachingHandoffRemovalService implements FailedTeachingHandoffRemovals {

    private final OfficialRulebookImportJobRepository officialImports;
    private final UploadedRulebookTeachingHandoffStore uploads;
    private final RuleDocumentRepository documents;
    private final Clock clock;

    @Autowired
    public FailedTeachingHandoffRemovalService(
            OfficialRulebookImportJobRepository officialImports,
            UploadedRulebookTeachingHandoffStore uploads,
            RuleDocumentRepository documents) {
        this(officialImports, uploads, documents, Clock.systemUTC());
    }

    FailedTeachingHandoffRemovalService(
            OfficialRulebookImportJobRepository officialImports,
            UploadedRulebookTeachingHandoffStore uploads,
            RuleDocumentRepository documents,
            Clock clock) {
        this.officialImports = officialImports;
        this.uploads = uploads;
        this.documents = documents;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Candidate> findOwned(Origin origin, UUID sourceId, String ownerUsername) {
        if (origin == null || sourceId == null) {
            throw new IllegalArgumentException("failed teaching handoff identity is required");
        }
        String owner = checkedOwner(ownerUsername);
        return switch (origin) {
            case OFFICIAL_IMPORT -> officialImports.findOwned(sourceId, owner).flatMap(this::officialCandidate);
            case UPLOAD -> uploads.findOwned(sourceId, owner).flatMap(this::uploadCandidate);
        };
    }

    @Override
    @Transactional
    public boolean dismissOwned(Candidate candidate, String ownerUsername) {
        if (candidate == null) throw new IllegalArgumentException("failed teaching handoff candidate is required");
        String owner = checkedOwner(ownerUsername);
        Instant now = Instant.now(clock);
        return switch (candidate.origin()) {
            case OFFICIAL_IMPORT -> officialImports.dismissTeaching(
                    candidate.sourceId(),
                    owner,
                    OfficialRulebookImportJob.TeachingHandoffState.valueOf(candidate.handoffState().name()),
                    candidate.preparationRunId(),
                    now);
            case UPLOAD -> uploads.dismissOwned(
                    candidate.sourceId(),
                    owner,
                    UploadedRulebookTeachingHandoffStore.State.valueOf(candidate.handoffState().name()),
                    candidate.preparationRunId());
        };
    }

    private Optional<Candidate> officialCandidate(OfficialRulebookImportJob job) {
        var handoff = job.teachingHandoff();
        if (handoff.state() == OfficialRulebookImportJob.TeachingHandoffState.NOT_REQUESTED) {
            return Optional.empty();
        }
        boolean failureRecorded = job.stage() == OfficialRulebookImportJob.Stage.FAILED
                || handoff.state() == OfficialRulebookImportJob.TeachingHandoffState.FAILED
                || documentFailed(job.documentVersionId());
        if (!failureRecorded && handoff.preparationRunId() == null) return Optional.empty();
        return Optional.of(new Candidate(
                Origin.OFFICIAL_IMPORT,
                job.id(),
                job.documentVersionId(),
                handoff.preparationRunId(),
                HandoffState.valueOf(handoff.state().name()),
                failureRecorded));
    }

    private Optional<Candidate> uploadCandidate(UploadedRulebookTeachingHandoffStore.Snapshot handoff) {
        boolean failureRecorded = handoff.state() == UploadedRulebookTeachingHandoffStore.State.FAILED
                || documentFailed(handoff.documentVersionId());
        if (!failureRecorded && handoff.preparationRunId() == null) return Optional.empty();
        return Optional.of(new Candidate(
                Origin.UPLOAD,
                handoff.id(),
                handoff.documentVersionId(),
                handoff.preparationRunId(),
                HandoffState.valueOf(handoff.state().name()),
                failureRecorded));
    }

    private boolean documentFailed(UUID documentVersionId) {
        return documentVersionId != null
                && documents.findVersion(documentVersionId)
                        .map(version -> version.status() == ProcessingStatus.FAILED)
                        .orElse(false);
    }

    private String checkedOwner(String ownerUsername) {
        if (ownerUsername == null || ownerUsername.isBlank()) {
            throw new IllegalArgumentException("failed teaching handoff owner is required");
        }
        return ownerUsername.strip();
    }
}
