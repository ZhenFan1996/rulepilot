package com.rulepilot.document;

import java.util.UUID;

public interface DocumentTeachingPreparation {

    DocumentVersionScopeLookup.VersionScope prepare(
            UUID documentVersionId, String ownerUsername, String sourceConfirmedGameName);
}
