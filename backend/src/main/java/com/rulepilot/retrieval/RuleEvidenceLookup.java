package com.rulepilot.retrieval;

import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface RuleEvidenceLookup {

    List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds);

    default List<RuleEvidenceHit> findAdjacent(
            UUID documentVersionId, Set<UUID> anchorChunkIds, int radius, Set<String> sectionTypes) {
        return List.of();
    }
}
