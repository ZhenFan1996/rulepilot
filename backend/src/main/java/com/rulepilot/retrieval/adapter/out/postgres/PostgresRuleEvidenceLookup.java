package com.rulepilot.retrieval.adapter.out.postgres;

import com.rulepilot.retrieval.application.RuleEvidenceLookupRepository;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit.ContentKind;
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
    public int canonicalChunkCount(UUID documentVersionId) {
        Number count = (Number) entityManager.createNativeQuery("""
                        SELECT count(*)
                        FROM rule_chunk
                        WHERE document_version_id = :versionId
                        """)
                .setParameter("versionId", documentVersionId)
                .getSingleResult();
        return Math.toIntExact(count.longValue());
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
        return findByChunkIds(documentVersionId, chunkIds, 0, chunkIds.size());
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<RuleEvidenceHit> findByChunkIds(
            UUID documentVersionId, Set<UUID> chunkIds, int offset, int limit) {
        String sql = """
                SELECT id, document_version_id, section_type, heading, content, page_from, page_to, content_kind
                FROM rule_chunk
                WHERE document_version_id = :versionId
                  AND id IN (:chunkIds)
                ORDER BY chunk_index
                LIMIT :limit OFFSET :offset
                """;
        List<Object[]> rows = entityManager.createNativeQuery(sql)
                .setParameter("versionId", documentVersionId)
                .setParameter("chunkIds", chunkIds)
                .setParameter("limit", limit)
                .setParameter("offset", offset)
                .getResultList();
        return rows.stream()
                .map(row -> new RuleEvidenceHit(
                        (UUID) row[0], (UUID) row[1], (String) row[2], (String) row[3], (String) row[4],
                        ((Number) row[5]).intValue(), ((Number) row[6]).intValue(), 1.0,
                        ContentKind.valueOf((String) row[7]), (String) row[4]))
                .toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
        int corpusRows = canonicalChunkCount(documentVersionId);
        return corpusRows == 0 ? List.of() : findByPageNumbers(documentVersionId, pageNumbers, 0, corpusRows);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<RuleEvidenceHit> findByPageNumbers(
            UUID documentVersionId, Set<Integer> pageNumbers, int offset, int limit) {
        String sql = """
                SELECT id, document_version_id, section_type, heading, content, page_from, page_to, content_kind
                FROM rule_chunk
                WHERE document_version_id = :versionId
                  AND page_from = page_to
                  AND page_from IN (:pageNumbers)
                ORDER BY page_from, chunk_index
                LIMIT :limit OFFSET :offset
                """;
        List<Object[]> rows = entityManager.createNativeQuery(sql)
                .setParameter("versionId", documentVersionId)
                .setParameter("pageNumbers", pageNumbers)
                .setParameter("limit", limit)
                .setParameter("offset", offset)
                .getResultList();
        return rows.stream()
                .map(row -> new RuleEvidenceHit(
                        (UUID) row[0], (UUID) row[1], (String) row[2], (String) row[3], (String) row[4],
                        ((Number) row[5]).intValue(), ((Number) row[6]).intValue(), 1.0,
                        ContentKind.valueOf((String) row[7]), (String) row[4]))
                .toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<RuleEvidenceHit> findAdjacent(
            UUID documentVersionId, Set<UUID> anchorChunkIds, int radius, Set<String> sectionTypes) {
        int corpusRows = canonicalChunkCount(documentVersionId);
        return corpusRows == 0
                ? List.of()
                : findAdjacent(documentVersionId, anchorChunkIds, radius, sectionTypes, 0, corpusRows);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<RuleEvidenceHit> findAdjacent(
            UUID documentVersionId,
            Set<UUID> anchorChunkIds,
            int radius,
            Set<String> sectionTypes,
            int offset,
            int limit) {
        String sectionPredicate = sectionTypes.isEmpty() ? "" : "AND c.section_type IN (:sectionTypes)";
        String sql = """
                WITH anchors AS (
                    SELECT chunk_index
                    FROM rule_chunk
                    WHERE document_version_id = :versionId
                      AND id IN (:anchorIds)
                )
                SELECT DISTINCT c.id, c.document_version_id, c.section_type, c.heading,
                       c.content, c.page_from, c.page_to, c.chunk_index, c.content_kind
                FROM rule_chunk c
                JOIN anchors a ON c.chunk_index::bigint
                    BETWEEN a.chunk_index::bigint - CAST(:radius AS bigint)
                        AND a.chunk_index::bigint + CAST(:radius AS bigint)
                WHERE c.document_version_id = :versionId
                  AND c.id NOT IN (:anchorIds)
                  %s
                ORDER BY c.chunk_index
                LIMIT :limit OFFSET :offset
                """.formatted(sectionPredicate);
        var query = entityManager.createNativeQuery(sql)
                .setParameter("versionId", documentVersionId)
                .setParameter("anchorIds", anchorChunkIds)
                .setParameter("radius", radius)
                .setParameter("limit", limit)
                .setParameter("offset", offset);
        if (!sectionTypes.isEmpty()) query.setParameter("sectionTypes", sectionTypes);
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(row -> new RuleEvidenceHit(
                        (UUID) row[0], (UUID) row[1], (String) row[2], (String) row[3], (String) row[4],
                        ((Number) row[5]).intValue(), ((Number) row[6]).intValue(), 1.0,
                        ContentKind.valueOf((String) row[8]), (String) row[4]))
                .toList();
    }
}
