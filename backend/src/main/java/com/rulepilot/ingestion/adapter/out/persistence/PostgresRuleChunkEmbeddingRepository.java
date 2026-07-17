package com.rulepilot.ingestion.adapter.out.persistence;

import com.rulepilot.ingestion.EmbeddingProvider.EmbeddingVector;
import com.rulepilot.ingestion.application.RuleChunkEmbeddingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class PostgresRuleChunkEmbeddingRepository implements RuleChunkEmbeddingRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public List<EmbeddableChunk> findPending(UUID documentVersionId, String provider) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT id, heading, content
                        FROM rule_chunk
                        WHERE document_version_id = :versionId
                          AND (embedding IS NULL OR embedding_provider <> :provider)
                        ORDER BY chunk_index
                        """)
                .setParameter("versionId", documentVersionId)
                .setParameter("provider", provider)
                .getResultList();
        return rows.stream().map(row -> new EmbeddableChunk((UUID) row[0], (String) row[1], (String) row[2])).toList();
    }

    @Override
    public void save(UUID chunkId, EmbeddingVector embedding, String provider, Instant embeddedAt) {
        entityManager.createNativeQuery("""
                        UPDATE rule_chunk
                        SET embedding = cast(:embedding AS vector),
                            embedding_provider = :provider,
                            embedded_at = :embeddedAt
                        WHERE id = :chunkId
                        """)
                .setParameter("embedding", vectorLiteral(embedding))
                .setParameter("provider", provider)
                .setParameter("embeddedAt", embeddedAt)
                .setParameter("chunkId", chunkId)
                .executeUpdate();
    }

    static String vectorLiteral(EmbeddingVector embedding) {
        return embedding.values().stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
