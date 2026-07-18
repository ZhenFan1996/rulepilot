package com.rulepilot.retrieval.application;

import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.UUID;

public interface FullTextRuleSearchRepository {

    List<RuleEvidenceHit> search(UUID documentVersionId, String query, int limit);
}
