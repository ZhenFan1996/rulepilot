package com.rulepilot.document.application;

import com.rulepilot.document.PublicRulebookReferenceLookup.Reference;
import com.rulepilot.document.UploadedRulebookTeachingHandoffs;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class UploadedRulebookTeachingHandoffService implements UploadedRulebookTeachingHandoffs {

    private final UploadedRulebookTeachingHandoffStore handoffs;
    private final RuleDocumentRepository documents;
    private final Clock clock;

    @Autowired
    public UploadedRulebookTeachingHandoffService(
            UploadedRulebookTeachingHandoffStore handoffs,
            RuleDocumentRepository documents) {
        this(handoffs, documents, Clock.systemUTC());
    }

    UploadedRulebookTeachingHandoffService(
            UploadedRulebookTeachingHandoffStore handoffs,
            RuleDocumentRepository documents,
            Clock clock) {
        this.handoffs = handoffs;
        this.documents = documents;
        this.clock = clock;
    }

    @Transactional
    public HandoffView request(UUID documentVersionId, String learningGoal, String ownerUsername) {
        String owner = checkedOwner(ownerUsername);
        String goal = normalizedGoal(learningGoal);
        var snapshot = handoffs.request(
                UUID.randomUUID(), documentVersionId, owner, goal, Instant.now(clock));
        return view(snapshot);
    }

    @Transactional
    public HandoffView retry(UUID handoffId, UUID expectedPreparationRunId, String ownerUsername) {
        String owner = checkedOwner(ownerUsername);
        var existing = handoffs.findOwned(handoffId, owner)
                .orElseThrow(() -> new IllegalArgumentException("uploaded teaching handoff does not exist"));
        if (existing.state() == UploadedRulebookTeachingHandoffStore.State.FAILED
                && "DOCUMENT_PROCESSING_FAILED".equals(existing.errorCode())) {
            throw new IllegalStateException("uploaded rulebook processing failed");
        }
        return view(handoffs.retry(handoffId, expectedPreparationRunId, owner, Instant.now(clock)));
    }

    @Transactional(readOnly = true)
    public List<HandoffView> recentOwned(String ownerUsername) {
        String owner = checkedOwner(ownerUsername);
        List<UploadedRulebookTeachingHandoffStore.Snapshot> recent = handoffs.findRecentOwned(owner, 20);
        var references = documents.findReferences(recent.stream()
                .map(UploadedRulebookTeachingHandoffStore.Snapshot::documentVersionId)
                .toList());
        return recent.stream()
                .map(snapshot -> view(snapshot, references.get(snapshot.documentVersionId())))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Override
    public List<ReadyHandoff> claimReady(int limit) {
        return readyHandoffs(handoffs.claimReady(checkedClaimLimit(limit), Instant.now(clock)));
    }

    @Override
    public List<ReadyHandoff> claimReadyForDocument(UUID documentVersionId, int limit) {
        if (documentVersionId == null) throw new IllegalArgumentException("ready document version is required");
        return readyHandoffs(handoffs.claimReadyForDocument(
                documentVersionId, checkedClaimLimit(limit), Instant.now(clock)));
    }

    private int checkedClaimLimit(int limit) {
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("uploaded teaching handoff claim limit is invalid");
        }
        return limit;
    }

    private List<ReadyHandoff> readyHandoffs(List<UploadedRulebookTeachingHandoffStore.Snapshot> claimed) {
        return claimed.stream()
                .map(snapshot -> new ReadyHandoff(
                        snapshot.id(),
                        snapshot.documentVersionId(),
                        snapshot.ownerUsername(),
                        snapshot.learningGoal()))
                .toList();
    }

    @Override
    public int failUnusableDocuments() {
        return handoffs.failUnusableDocuments(Instant.now(clock));
    }

    @Override
    public void markLaunched(UUID handoffId, UUID preparationRunId) {
        if (handoffId == null || preparationRunId == null) {
            throw new IllegalArgumentException("uploaded teaching launch identity is required");
        }
        handoffs.completeLaunch(handoffId, preparationRunId, Instant.now(clock));
    }

    @Override
    public void markFailed(UUID handoffId, String errorCode) {
        if (handoffId == null || errorCode == null || errorCode.isBlank() || errorCode.length() > 64) {
            throw new IllegalArgumentException("uploaded teaching launch failure is invalid");
        }
        handoffs.failLaunch(handoffId, errorCode, Instant.now(clock));
    }

    @Override
    public int failInterruptedLaunches() {
        return handoffs.failInterruptedLaunches(Instant.now(clock));
    }

    private HandoffView view(UploadedRulebookTeachingHandoffStore.Snapshot snapshot) {
        Reference reference = documents.findReferences(List.of(snapshot.documentVersionId()))
                .get(snapshot.documentVersionId());
        HandoffView view = view(snapshot, reference);
        if (view == null) throw new IllegalArgumentException("uploaded rulebook document does not exist");
        return view;
    }

    private HandoffView view(UploadedRulebookTeachingHandoffStore.Snapshot snapshot, Reference reference) {
        if (reference == null) return null;
        return new HandoffView(
                snapshot.id(),
                snapshot.documentVersionId(),
                reference.gameEditionId(),
                reference.title(),
                snapshot.state(),
                snapshot.preparationRunId(),
                snapshot.errorCode(),
                snapshot.createdAt(),
                snapshot.updatedAt());
    }

    private String checkedOwner(String ownerUsername) {
        if (ownerUsername == null || ownerUsername.isBlank()) {
            throw new IllegalArgumentException("uploaded teaching handoff owner is required");
        }
        return ownerUsername.strip();
    }

    private String normalizedGoal(String learningGoal) {
        if (learningGoal == null || learningGoal.isBlank()) return null;
        return learningGoal.strip();
    }

    public record HandoffView(
            UUID id,
            UUID documentVersionId,
            UUID editionId,
            String rulebookTitle,
            UploadedRulebookTeachingHandoffStore.State state,
            UUID preparationRunId,
            String errorCode,
            Instant createdAt,
            Instant updatedAt) {}
}
