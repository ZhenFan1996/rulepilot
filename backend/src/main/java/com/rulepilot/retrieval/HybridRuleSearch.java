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

    /**
     * Returns one logical page. Implementations that own physical channel paging should override this method so
     * {@code hasMore} is based on a real lookahead or source exhaustion. The default keeps this interface usable as a
     * single-method test port while conservatively treating a full page as continuable.
     */
    default SearchPage searchPage(UUID documentVersionId, String query, RetrievalOptions options) {
        List<HybridEvidenceHit> hits = search(documentVersionId, query, options);
        return new SearchPage(hits, hits.size() == options.limit());
    }

    enum SourceAvailability {
        COMPLETE,
        PARTIAL
    }

    record SearchPage(
            List<HybridEvidenceHit> hits,
            boolean hasMore,
            SourceAvailability sourceAvailability) {
        public SearchPage(List<HybridEvidenceHit> hits, boolean hasMore) {
            this(hits, hasMore, SourceAvailability.COMPLETE);
        }

        public SearchPage {
            if (hits == null || sourceAvailability == null) {
                throw new IllegalArgumentException("hybrid search page hits and availability are required");
            }
            hits = List.copyOf(hits);
        }
    }

    /**
     * {@code offset} is a logical offset after filters and exclusions, not a physical offset shared by the two
     * retrieval channels. Continuations should prefer {@code excludedEvidenceIds} with offset zero so newly fused
     * ranks cannot re-publish or skip an identity that was already shown.
     */
    record RetrievalOptions(
            int limit,
            Set<String> sectionTypes,
            String currentSectionType,
            Set<Integer> allowedEvidencePages,
            int offset,
            Set<UUID> excludedEvidenceIds) {
        public RetrievalOptions(int limit, Set<String> sectionTypes, String currentSectionType) {
            this(limit, sectionTypes, currentSectionType, null, 0, Set.of());
        }

        public RetrievalOptions(
                int limit, Set<String> sectionTypes, String currentSectionType, Set<Integer> allowedEvidencePages) {
            this(limit, sectionTypes, currentSectionType, allowedEvidencePages, 0, Set.of());
        }

        public RetrievalOptions(
                int limit,
                Set<String> sectionTypes,
                String currentSectionType,
                Set<Integer> allowedEvidencePages,
                int offset) {
            this(limit, sectionTypes, currentSectionType, allowedEvidencePages, offset, Set.of());
        }

        public RetrievalOptions {
            sectionTypes = sectionTypes == null
                    ? Set.of()
                    : sectionTypes.stream()
                            .map(String::strip)
                            .map(value -> value.toUpperCase(java.util.Locale.ROOT))
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
            currentSectionType = currentSectionType == null || currentSectionType.isBlank()
                    ? null
                    : currentSectionType.strip().toUpperCase(java.util.Locale.ROOT);
            if (allowedEvidencePages != null) {
                if (allowedEvidencePages.isEmpty()
                        || allowedEvidencePages.stream().anyMatch(page -> page == null || page < 1)) {
                    throw new IllegalArgumentException("hybrid retrieval page scope is invalid");
                }
                allowedEvidencePages = Set.copyOf(allowedEvidencePages);
            }
            if (offset < 0) throw new IllegalArgumentException("hybrid retrieval offset is invalid");
            if (excludedEvidenceIds == null) excludedEvidenceIds = Set.of();
            else if (excludedEvidenceIds.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("hybrid retrieval exclusions are invalid");
            } else excludedEvidenceIds = Set.copyOf(excludedEvidenceIds);
        }
    }
}
