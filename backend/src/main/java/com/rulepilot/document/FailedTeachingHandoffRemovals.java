package com.rulepilot.document;

import java.util.Optional;
import java.util.UUID;

/**
 * Player-owned failed teaching intents that can be removed without deleting their rulebook.
 *
 * <p>The document module owns the durable handoff record. A caller that also owns assistant-run
 * lifecycle knowledge must verify any referenced preparation run before dismissing the record.</p>
 */
public interface FailedTeachingHandoffRemovals {

    Optional<Candidate> findOwned(Origin origin, UUID sourceId, String ownerUsername);

    boolean dismissOwned(Candidate candidate, String ownerUsername);

    enum Origin {
        OFFICIAL_IMPORT,
        UPLOAD
    }

    enum HandoffState {
        WAITING_FOR_DOCUMENT,
        LAUNCHING,
        LAUNCHED,
        FAILED
    }

    record Candidate(
            Origin origin,
            UUID sourceId,
            UUID documentVersionId,
            UUID preparationRunId,
            HandoffState handoffState,
            boolean failureRecordedWithoutRun) {

        public Candidate {
            if (origin == null || sourceId == null || handoffState == null) {
                throw new IllegalArgumentException("failed teaching handoff candidate is invalid");
            }
            if (preparationRunId != null && documentVersionId == null) {
                throw new IllegalArgumentException("teaching preparation run requires a document version");
            }
            if (!failureRecordedWithoutRun && preparationRunId == null) {
                throw new IllegalArgumentException("teaching handoff candidate is not failed");
            }
        }
    }
}
