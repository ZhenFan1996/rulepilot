package com.rulepilot.retrieval;

import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface HybridRuleSearch {

    List<HybridEvidenceHit> search(UUID documentVersionId, String query, RetrievalOptions options);

    record RetrievalOptions(int limit, Set<String> sectionTypes, String currentSectionType) {
        public RetrievalOptions {
            sectionTypes = sectionTypes == null
                    ? Set.of()
                    : sectionTypes.stream().map(String::strip).map(String::toUpperCase)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
            currentSectionType = currentSectionType == null || currentSectionType.isBlank()
                    ? null
                    : currentSectionType.strip().toUpperCase();
        }
    }
}
