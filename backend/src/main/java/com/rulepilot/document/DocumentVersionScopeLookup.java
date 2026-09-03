package com.rulepilot.document;

import java.util.Optional;
import java.util.UUID;

public interface DocumentVersionScopeLookup {

    Optional<VersionScope> findVersion(UUID documentVersionId);

    record VersionScope(
            UUID documentVersionId,
            UUID editionId,
            String processingStatus,
            String createdBy,
            String documentTitle,
            String sourceSha256) {

        public VersionScope(UUID documentVersionId, UUID editionId, String processingStatus, String createdBy) {
            this(documentVersionId, editionId, processingStatus, createdBy, null, null);
        }

        public VersionScope(
                UUID documentVersionId,
                UUID editionId,
                String processingStatus,
                String createdBy,
                String documentTitle) {
            this(documentVersionId, editionId, processingStatus, createdBy, documentTitle, null);
        }
    }
}
