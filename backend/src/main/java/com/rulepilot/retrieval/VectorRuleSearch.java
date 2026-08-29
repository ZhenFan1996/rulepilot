package com.rulepilot.retrieval;

import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.UUID;

public interface VectorRuleSearch {

    List<RuleEvidenceHit> search(UUID documentVersionId, String query, int limit);

    default List<RuleEvidenceHit> search(UUID documentVersionId, String query, int offset, int limit) {
        if (offset != 0) throw new UnsupportedOperationException("vector search paging is unavailable");
        return search(documentVersionId, query, limit);
    }

    /**
     * Prepares one logical query for one or more physical result windows. Implementations may bind request-scoped
     * derived input, such as an embedding, while the default preserves the single-method functional contract.
     */
    default PreparedSearch prepare(UUID documentVersionId, String query) {
        return (offset, limit) -> search(documentVersionId, query, offset, limit);
    }

    @FunctionalInterface
    interface PreparedSearch {
        List<RuleEvidenceHit> search(int offset, int limit);
    }
}
