package com.rulepilot.retrieval.application;

import com.rulepilot.ingestion.EmbeddingProvider.EmbeddingVector;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.UUID;

public interface VectorRuleSearchRepository {

    List<RuleEvidenceHit> search(UUID documentVersionId, EmbeddingVector query, String provider, int limit);
}
