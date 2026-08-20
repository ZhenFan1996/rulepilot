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

    default boolean requiresRefresh(UUID documentVersionId, UUID preparationRunId, String ownerUsername) {
        return assess(documentVersionId, preparationRunId, ownerUsername) == ReuseAssessment.REFRESH_REQUIRED;
    }

    enum ReuseAssessment {
        /** The referenced preparation run is still doing real work and must not be duplicated. */
        IN_PROGRESS,
        /** A plan and at least one source-cited player-readable section are still persisted. */
        REUSABLE,
        /** The referenced run ended without a reusable Teaching result, or its derived evidence is stale. */
        REFRESH_REQUIRED
    }

    static RulebookTeachingEvidenceFreshness alwaysCurrent() {
        return (documentVersionId, preparationRunId, ownerUsername) -> ReuseAssessment.REUSABLE;
    }
}
