package com.rulepilot.document;

import java.util.UUID;

/**
 * Checks whether a completed import needs a new teaching preparation before it can be safely reused.
 *
 * <p>The document module owns the reuse decision, while the teaching module owns the derived-evidence schema.
 * Keeping this as a document-owned SPI preserves that dependency direction.</p>
 */
public interface RulebookTeachingEvidenceFreshness {

    boolean requiresRefresh(UUID documentVersionId, UUID preparationRunId, String ownerUsername);

    static RulebookTeachingEvidenceFreshness alwaysCurrent() {
        return (documentVersionId, preparationRunId, ownerUsername) -> false;
    }
}
