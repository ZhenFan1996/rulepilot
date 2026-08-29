package com.rulepilot.retrieval.application;

import com.rulepilot.retrieval.FullTextRuleSearch;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class FullTextRuleSearchService implements FullTextRuleSearch {

    private final FullTextRuleSearchRepository repository;

    public FullTextRuleSearchService(FullTextRuleSearchRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuleEvidenceHit> search(UUID documentVersionId, String query, int limit) {
        return search(documentVersionId, query, 0, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuleEvidenceHit> search(UUID documentVersionId, String query, int offset, int limit) {
        if (documentVersionId == null || query == null || query.isBlank() || offset < 0 || limit < 1) {
            throw new IllegalArgumentException("document version and search query are required");
        }
        return repository.search(documentVersionId, query.strip(), offset, limit);
    }
}
