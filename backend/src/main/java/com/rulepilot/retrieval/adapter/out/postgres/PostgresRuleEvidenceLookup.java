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

    @Override
    @SuppressWarnings("unchecked")
    public List<RuleEvidenceHit> findAdjacent(
            UUID documentVersionId, Set<UUID> anchorChunkIds, int radius, Set<String> sectionTypes) {
        String sql = """
                WITH anchors AS (
                    SELECT chunk_index
                    FROM rule_chunk
                    WHERE document_version_id = :versionId
                      AND id IN (:anchorIds)
                )
                SELECT DISTINCT c.id, c.document_version_id, c.section_type, c.heading,
                       c.content, c.page_from, c.page_to, c.chunk_index
                FROM rule_chunk c
                JOIN anchors a ON c.chunk_index BETWEEN a.chunk_index - :radius AND a.chunk_index + :radius
                WHERE c.document_version_id = :versionId
                  AND c.id NOT IN (:anchorIds)
                  AND c.section_type IN (:sectionTypes)
                ORDER BY c.chunk_index
                """;
        List<Object[]> rows = entityManager.createNativeQuery(sql)
                .setParameter("versionId", documentVersionId)
                .setParameter("anchorIds", anchorChunkIds)
                .setParameter("radius", radius)
                .setParameter("sectionTypes", sectionTypes)
                .getResultList();
        return rows.stream()
                .map(row -> new RuleEvidenceHit(
                        (UUID) row[0], (UUID) row[1], (String) row[2], (String) row[3], (String) row[4],
                        ((Number) row[5]).intValue(), ((Number) row[6]).intValue(), 1.0))
                .toList();
    }
}
