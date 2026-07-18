package com.rulepilot.retrieval.application;

import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
class RuleEvidenceLookupService implements RuleEvidenceLookup {

    private final RuleEvidenceLookupRepository evidence;

    RuleEvidenceLookupService(RuleEvidenceLookupRepository evidence) {
        this.evidence = evidence;
    }

    @Override
    public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
        if (documentVersionId == null || chunkIds == null || chunkIds.isEmpty()) {
            throw new IllegalArgumentException("document version and evidence chunk ids are required");
        }
        return evidence.findByChunkIds(documentVersionId, Set.copyOf(chunkIds));
    }
}
