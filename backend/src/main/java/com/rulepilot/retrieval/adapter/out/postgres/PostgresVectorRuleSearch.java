package com.rulepilot.retrieval.adapter.out.postgres;

import com.rulepilot.ingestion.EmbeddingProvider.EmbeddingVector;
import com.rulepilot.retrieval.application.VectorRuleSearchRepository;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit.ContentKind;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class PostgresVectorRuleSearch implements VectorRuleSearchRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public List<RuleEvidenceHit> search(UUID documentVersionId, EmbeddingVector query, String provider, int limit) {
        return search(documentVersionId, query, provider, 0, limit);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<RuleEvidenceHit> search(
            UUID documentVersionId, EmbeddingVector query, String provider, int offset, int limit) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT id, document_version_id, section_type, heading, content,
                               page_from, page_to,
                               (2 - (embedding <=> cast(:embedding AS vector))) / 2 AS score,
                               content_kind
                        FROM rule_chunk
                        WHERE document_version_id = :versionId
                          AND embedding IS NOT NULL
                          AND embedding_provider = :provider
                        ORDER BY embedding <=> cast(:embedding AS vector), chunk_index
                        LIMIT :limit OFFSET :offset
                        """)
                .setParameter("embedding", vectorLiteral(query))
                .setParameter("versionId", documentVersionId)
                .setParameter("provider", provider)
                .setParameter("limit", limit)
                .setParameter("offset", offset)
                .getResultList();
        return rows.stream().map(this::toHit).toList();
    }

    private RuleEvidenceHit toHit(Object[] row) {
        return new RuleEvidenceHit(
                (UUID) row[0],
                (UUID) row[1],
                (String) row[2],
                (String) row[3],
                (String) row[4],
                ((Number) row[5]).intValue(),
                ((Number) row[6]).intValue(),
                Math.max(0, ((Number) row[7]).doubleValue()),
                ContentKind.valueOf((String) row[8]),
                (String) row[4]);
    }

    private String vectorLiteral(EmbeddingVector embedding) {
        return embedding.values().stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
