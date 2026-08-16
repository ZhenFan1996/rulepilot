package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.document.FailedTeachingHandoffRemovals;
import com.rulepilot.document.FailedTeachingHandoffRemovals.Candidate;
import com.rulepilot.document.FailedTeachingHandoffRemovals.Origin;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Removes a failed preparation entry while deliberately retaining its source rulebook. */
@Service
@Profile("!test")
public class FailedTeachingPreparationRemovalService {

    private final FailedTeachingHandoffRemovals handoffs;
    private final AssistantRuns runs;

    public FailedTeachingPreparationRemovalService(
            FailedTeachingHandoffRemovals handoffs,
            AssistantRuns runs) {
        this.handoffs = handoffs;
        this.runs = runs;
    }

    public void removeOfficialImport(UUID importJobId, String ownerUsername) {
        remove(Origin.OFFICIAL_IMPORT, importJobId, ownerUsername);
    }

    public void removeUpload(UUID handoffId, String ownerUsername) {
        remove(Origin.UPLOAD, handoffId, ownerUsername);
    }

    private void remove(Origin origin, UUID sourceId, String ownerUsername) {
        Candidate candidate = handoffs.findOwned(origin, sourceId, ownerUsername)
                .orElseThrow(() -> new IllegalArgumentException("failed teaching preparation does not exist"));
        if (candidate.preparationRunId() != null) validateFailedRun(candidate, ownerUsername);
        if (!handoffs.dismissOwned(candidate, ownerUsername)) {
            throw new IllegalStateException("failed teaching preparation changed before deletion");
        }
    }

    private void validateFailedRun(Candidate candidate, String ownerUsername) {
        var run = runs.findOwned(candidate.preparationRunId(), ownerUsername)
                .map(AssistantRuns.RunDetails::run)
                .orElseThrow(() -> new IllegalStateException("teaching preparation run is unavailable"));
        boolean samePreparation = run.mode() == AssistantRunMode.TEACHING_PREPARATION
                && run.subjectId().equals(candidate.documentVersionId());
        AssistantRunState state = run.state();
        if (!samePreparation || !state.terminal() || state == AssistantRunState.COMPLETED) {
            throw new IllegalStateException("teaching preparation is not a removable failure");
        }
    }
}
