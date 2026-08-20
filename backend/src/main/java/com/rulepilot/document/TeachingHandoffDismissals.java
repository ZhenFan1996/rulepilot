package com.rulepilot.document;

import java.util.UUID;

/** Persists a player's decision to remove generated teaching while keeping the source rulebook. */
public interface TeachingHandoffDismissals {

    void dismissOwnedForDocumentVersion(UUID documentVersionId, String ownerUsername);
}
