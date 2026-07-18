package com.rulepilot.retrieval.application;

import com.rulepilot.ingestion.EmbeddingProvider;
import com.rulepilot.retrieval.VectorRuleSearch;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class VectorRuleSearchService implements VectorRuleSearch {

    private static final int MAX_RESULTS = 20;
    private final EmbeddingProvider embeddings;
    private final VectorRuleSearchRepository repository;

    public VectorRuleSearchService(EmbeddingProvider embeddings, VectorRuleSearchRepository repository) {
        this.embeddings = embeddings;
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuleEvidenceHit> search(UUID documentVersionId, String query, int limit) {
        if (documentVersionId == null || query == null || query.isBlank()) {
            throw new IllegalArgumentException("document version and vector query are required");
        }
        int boundedLimit = Math.max(1, Math.min(limit, MAX_RESULTS));
        var vector = embeddings.embed(List.of(query.strip())).getFirst();
        return repository.search(documentVersionId, vector, embeddings.id(), boundedLimit);
    }
}
