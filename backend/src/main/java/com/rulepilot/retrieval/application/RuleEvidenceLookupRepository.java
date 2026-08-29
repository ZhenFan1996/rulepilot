package com.rulepilot.retrieval.application;

import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface RuleEvidenceLookupRepository {

    int canonicalChunkCount(UUID documentVersionId);

    List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds);

    default List<RuleEvidenceHit> findByChunkIds(
            UUID documentVersionId, Set<UUID> chunkIds, int offset, int limit) {
        return findByChunkIds(documentVersionId, chunkIds).stream().skip(offset).limit(limit).toList();
    }

    default List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
        return List.of();
    }

    default List<RuleEvidenceHit> findByPageNumbers(
            UUID documentVersionId, Set<Integer> pageNumbers, int offset, int limit) {
        return findByPageNumbers(documentVersionId, pageNumbers).stream().skip(offset).limit(limit).toList();
    }

    List<RuleEvidenceHit> findAdjacent(
            UUID documentVersionId, Set<UUID> anchorChunkIds, int radius, Set<String> sectionTypes);

    default List<RuleEvidenceHit> findAdjacent(
            UUID documentVersionId,
            Set<UUID> anchorChunkIds,
            int radius,
            Set<String> sectionTypes,
            int offset,
            int limit) {
        return findAdjacent(documentVersionId, anchorChunkIds, radius, sectionTypes).stream()
                .skip(offset).limit(limit).toList();
    }
}
