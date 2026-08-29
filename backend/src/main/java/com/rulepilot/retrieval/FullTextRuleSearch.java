package com.rulepilot.retrieval;

import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.UUID;

public interface FullTextRuleSearch {

    List<RuleEvidenceHit> search(UUID documentVersionId, String query, int limit);

    default List<RuleEvidenceHit> search(UUID documentVersionId, String query, int offset, int limit) {
        if (offset != 0) throw new UnsupportedOperationException("full-text search paging is unavailable");
        return search(documentVersionId, query, limit);
    }
}
