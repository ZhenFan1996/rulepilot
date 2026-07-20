package com.rulepilot.document;

import java.util.Optional;
import java.util.UUID;

public interface DocumentVersionScopeLookup {

    Optional<VersionScope> findVersion(UUID documentVersionId);

    record VersionScope(UUID documentVersionId, UUID editionId, String processingStatus, String createdBy) {}
}
