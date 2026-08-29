package com.rulepilot.retrieval;

import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface RuleEvidenceLookup {

    /**
     * PostgreSQL's 65,535 bind ceiling, reserving repeated version/radius/window binds and two occurrences of the
     * anchor identity collection in the widest adjacent-evidence statement.
     */
    int MAX_IDENTITIES_PER_QUERY = (65_535 - 7) / 2;

    /** Active-document cardinality used to turn model-requested ranges into real corpus ranges. */
    default int canonicalChunkCount(UUID documentVersionId) {
        return -1;
    }

    List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds);

    default List<RuleEvidenceHit> findByChunkIds(
            UUID documentVersionId, Set<UUID> chunkIds, int offset, int limit) {
        if (offset < 0 || limit < 1) throw new IllegalArgumentException("evidence id page is invalid");
        return findByChunkIds(documentVersionId, chunkIds).stream().skip(offset).limit(limit).toList();
    }

    default List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
        return List.of();
    }

    default List<RuleEvidenceHit> findByPageNumbers(
            UUID documentVersionId, Set<Integer> pageNumbers, int offset, int limit) {
        if (offset < 0 || limit < 1) throw new IllegalArgumentException("evidence page window is invalid");
        return findByPageNumbers(documentVersionId, pageNumbers).stream().skip(offset).limit(limit).toList();
    }

    default List<RuleEvidenceHit> findAdjacent(
            UUID documentVersionId, Set<UUID> anchorChunkIds, int radius, Set<String> sectionTypes) {
        return List.of();
    }

    default List<RuleEvidenceHit> findAdjacent(
            UUID documentVersionId,
            Set<UUID> anchorChunkIds,
            int radius,
            Set<String> sectionTypes,
            int offset,
            int limit) {
        if (offset < 0 || limit < 1) throw new IllegalArgumentException("adjacent evidence page is invalid");
        return findAdjacent(documentVersionId, anchorChunkIds, radius, sectionTypes).stream()
                .skip(offset).limit(limit).toList();
    }
}
