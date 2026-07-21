package com.rulepilot.ingestion;

import java.util.UUID;

/** Recreates version-scoped layout evidence from the immutable uploaded PDF. */
public interface RulebookUnderstandingRebuilder {

    void rebuild(UUID documentVersionId);
}
