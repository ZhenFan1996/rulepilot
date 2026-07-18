package com.rulepilot.retrieval.adapter.out.postgres;

import com.rulepilot.retrieval.application.RuleEvidenceLookupRepository;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
class PostgresRuleEvidenceLookup implements RuleEvidenceLookupRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
        String sql = """
                SELECT id, document_version_id, section_type, heading, content, page_from, page_to
                FROM rule_chunk
                WHERE document_version_id = :versionId
                  AND id IN (:chunkIds)
                """;
        List<Object[]> rows = entityManager.createNativeQuery(sql)
                .setParameter("versionId", documentVersionId)
                .setParameter("chunkIds", chunkIds)
                .getResultList();
        return rows.stream()
                .map(row -> new RuleEvidenceHit(
                        (UUID) row[0], (UUID) row[1], (String) row[2], (String) row[3], (String) row[4],
                        ((Number) row[5]).intValue(), ((Number) row[6]).intValue(), 1.0))
                .toList();
    }
}
