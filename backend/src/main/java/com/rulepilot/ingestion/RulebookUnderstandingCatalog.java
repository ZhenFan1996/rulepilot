package com.rulepilot.ingestion;

import com.rulepilot.ingestion.domain.RulebookUnderstanding;
import java.util.UUID;

/** Version-scoped document evidence for planning and auditing a lesson. */
public interface RulebookUnderstandingCatalog {

    RulebookUnderstanding understanding(UUID documentVersionId);
}
