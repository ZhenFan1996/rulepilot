package com.rulepilot.retrieval.application;

import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface RuleEvidenceLookupRepository {

    List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds);
}
