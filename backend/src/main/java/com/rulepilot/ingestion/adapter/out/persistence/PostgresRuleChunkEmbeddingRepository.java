package com.rulepilot.ingestion.adapter.out.persistence;

import com.rulepilot.ingestion.EmbeddingProvider.EmbeddingVector;
import com.rulepilot.ingestion.EmbeddingIndexCoverage;
import com.rulepilot.ingestion.application.RuleChunkEmbeddingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("!test")
public class PostgresRuleChunkEmbeddingRepository implements RuleChunkEmbeddingRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<EmbeddableChunk> findPending(UUID documentVersionId, String provider, int limit) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT id, heading, content
                        FROM rule_chunk
                        WHERE document_version_id = :versionId
                          AND (embedding IS NULL OR embedding_provider <> :provider)
                        ORDER BY chunk_index
                        LIMIT :limit
                        """)
                .setParameter("versionId", documentVersionId)
                .setParameter("provider", provider)
                .setParameter("limit", limit)
                .getResultList();
        return rows.stream().map(row -> new EmbeddableChunk((UUID) row[0], (String) row[1], (String) row[2])).toList();
    }

    @Override
    @Transactional
    public void saveBatch(List<IndexedChunk> indexedChunks, String provider, Instant embeddedAt) {
        for (IndexedChunk indexedChunk : indexedChunks) {
            entityManager.createNativeQuery("""
                            UPDATE rule_chunk
                            SET embedding = cast(:embedding AS vector),
                                embedding_provider = :provider,
                                embedded_at = :embeddedAt
                            WHERE id = :chunkId
                            """)
                    .setParameter("embedding", vectorLiteral(indexedChunk.embedding()))
                    .setParameter("provider", provider)
                    .setParameter("embeddedAt", embeddedAt)
                    .setParameter("chunkId", indexedChunk.id())
                    .executeUpdate();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public EmbeddingIndexCoverage coverage(UUID documentVersionId, String provider) {
        Object[] row = (Object[]) entityManager.createNativeQuery("""
                        SELECT count(*), count(*) FILTER (
                            WHERE embedding IS NOT NULL AND embedding_provider = :provider)
                        FROM rule_chunk
                        WHERE document_version_id = :versionId
                        """)
                .setParameter("versionId", documentVersionId)
                .setParameter("provider", provider)
                .getSingleResult();
        return new EmbeddingIndexCoverage(
                ((Number) row[0]).longValue(), ((Number) row[1]).longValue());
    }

    static String vectorLiteral(EmbeddingVector embedding) {
        return embedding.values().stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
