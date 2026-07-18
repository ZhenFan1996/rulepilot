package com.rulepilot.retrieval;

import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.UUID;

public interface FullTextRuleSearch {

    List<RuleEvidenceHit> search(UUID documentVersionId, String query, int limit);
}
