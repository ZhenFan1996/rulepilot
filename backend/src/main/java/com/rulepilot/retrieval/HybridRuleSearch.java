package com.rulepilot.retrieval;

import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface HybridRuleSearch {

    /**
     * Returns ranked hits whose evidence payload is the canonical stored chunk, not a search-index projection.
     * Downstream context expansion may therefore trust the returned anchors without hydrating them a second time.
     */
    List<HybridEvidenceHit> search(UUID documentVersionId, String query, RetrievalOptions options);

    record RetrievalOptions(
            int limit,
            Set<String> sectionTypes,
            String currentSectionType,
            Set<Integer> allowedEvidencePages) {
        public RetrievalOptions(int limit, Set<String> sectionTypes, String currentSectionType) {
            this(limit, sectionTypes, currentSectionType, null);
        }

        public RetrievalOptions {
            sectionTypes = sectionTypes == null
                    ? Set.of()
                    : sectionTypes.stream().map(String::strip).map(String::toUpperCase)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
            currentSectionType = currentSectionType == null || currentSectionType.isBlank()
                    ? null
                    : currentSectionType.strip().toUpperCase();
            if (allowedEvidencePages != null) {
                if (allowedEvidencePages.isEmpty()
                        || allowedEvidencePages.stream().anyMatch(page -> page == null || page < 1)) {
                    throw new IllegalArgumentException("hybrid retrieval page scope is invalid");
                }
                allowedEvidencePages = Set.copyOf(allowedEvidencePages);
            }
        }
    }
}
