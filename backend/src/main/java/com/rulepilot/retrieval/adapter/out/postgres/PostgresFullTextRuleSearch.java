package com.rulepilot.retrieval.adapter.out.postgres;

import com.rulepilot.retrieval.application.FullTextRuleSearchRepository;
import com.rulepilot.retrieval.domain.RuleEvidenceHit;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class PostgresFullTextRuleSearch implements FullTextRuleSearchRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public List<RuleEvidenceHit> search(UUID documentVersionId, String query, int limit) {
        String sql = """
                WITH search_query AS (
                    SELECT websearch_to_tsquery('simple', :query) AS value
                )
                SELECT c.id, c.document_version_id, c.section_type, c.heading,
                       ts_headline('simple', c.content, q.value,
                           'MaxWords=35, MinWords=12, StartSel=[, StopSel=]') AS excerpt,
                       c.page_from, c.page_to,
                       ts_rank_cd(c.content_tsv, q.value, 32) AS score
                FROM rule_chunk c
                CROSS JOIN search_query q
                WHERE c.document_version_id = :versionId
                  AND c.content_tsv @@ q.value
                ORDER BY score DESC, c.chunk_index ASC
                LIMIT :limit
                """;
        List<Object[]> rows = entityManager.createNativeQuery(sql)
                .setParameter("query", query)
                .setParameter("versionId", documentVersionId)
                .setParameter("limit", limit)
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
                ((Number) row[7]).doubleValue());
    }
}
