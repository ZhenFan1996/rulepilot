package com.rulepilot.document;

import java.util.Optional;
import java.util.UUID;

/** Exposes only publisher-link metadata needed by the anonymous lesson reader. */
public interface PublicRulebookReferenceLookup {

    Optional<Reference> findReference(UUID documentVersionId);

    record Reference(UUID documentVersionId, String title, String officialSourceUrl) {
        public Reference {
            if (documentVersionId == null || title == null || title.isBlank()) {
                throw new IllegalArgumentException("public rulebook reference is invalid");
            }
            title = title.strip();
        }
    }
}
