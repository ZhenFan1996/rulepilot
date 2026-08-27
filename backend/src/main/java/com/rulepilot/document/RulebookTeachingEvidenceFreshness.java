package com.rulepilot.document;

import java.util.UUID;

/**
 * Checks whether a completed import needs a new teaching preparation before it can be safely reused.
 *
 * <p>The document module owns the reuse decision, while the teaching module owns the derived-evidence schema.
 * Keeping this as a document-owned SPI preserves that dependency direction.</p>
 */
public interface RulebookTeachingEvidenceFreshness {

    ReuseAssessment assess(UUID documentVersionId, UUID preparationRunId, String ownerUsername);

    enum ReuseAssessment {
        /** The referenced preparation run is still doing real work and must not be duplicated. */
        IN_PROGRESS,
        /** A plan and at least one source-cited player-readable section are still persisted. */
        REUSABLE,
        /** The last preparation failed because of a transient provider, timeout, or worker condition. */
        RETRYABLE_FAILURE,
        /** The last preparation deterministically failed its source-bound plan contract. */
        TERMINAL_FAILURE,
        /** Durable Teaching evidence could not be read or written; automatic model retries cannot repair storage. */
        EXTERNAL_REPAIR_REQUIRED,
        /** The player explicitly cancelled the referenced preparation; recovery must preserve that intent. */
        CANCELLED,
        /** The referenced run ended without a reusable Teaching result, or its derived evidence is stale. */
        REFRESH_REQUIRED
    }

    static RulebookTeachingEvidenceFreshness alwaysCurrent() {
        return (documentVersionId, preparationRunId, ownerUsername) -> ReuseAssessment.REUSABLE;
    }
}
