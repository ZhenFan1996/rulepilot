package com.rulepilot.retrieval.application;

import com.rulepilot.retrieval.FullTextRuleSearch;
import com.rulepilot.retrieval.domain.RuleEvidenceHit;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class FullTextRuleSearchService implements FullTextRuleSearch {

    private static final int MAX_RESULTS = 20;
    private final FullTextRuleSearchRepository repository;

    public FullTextRuleSearchService(FullTextRuleSearchRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuleEvidenceHit> search(UUID documentVersionId, String query, int limit) {
        if (documentVersionId == null || query == null || query.isBlank()) {
            throw new IllegalArgumentException("document version and search query are required");
        }
        int boundedLimit = Math.max(1, Math.min(limit, MAX_RESULTS));
        return repository.search(documentVersionId, query.strip(), boundedLimit);
    }
}
