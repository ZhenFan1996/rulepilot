package com.rulepilot.retrieval.application;

import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

    @Override
    public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
        if (documentVersionId == null || pageNumbers == null || pageNumbers.isEmpty() || pageNumbers.size() > 4
                || pageNumbers.stream().anyMatch(page -> page == null || page < 1)) {
            throw new IllegalArgumentException("document version and bounded evidence page numbers are required");
        }
        return evidence.findByPageNumbers(documentVersionId, Set.copyOf(pageNumbers));
    }

    @Override
    public List<RuleEvidenceHit> findAdjacent(
            UUID documentVersionId, Set<UUID> anchorChunkIds, int radius, Set<String> sectionTypes) {
        if (documentVersionId == null || anchorChunkIds == null || anchorChunkIds.isEmpty()
                || anchorChunkIds.size() > 4 || radius < 1 || radius > 2
                || sectionTypes == null || sectionTypes.size() > 6
                || sectionTypes.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("adjacent evidence lookup is invalid");
        }
        return evidence.findAdjacent(
                documentVersionId, Set.copyOf(anchorChunkIds), radius,
                sectionTypes.stream().map(String::strip).map(value -> value.toUpperCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet()));
    }
}
