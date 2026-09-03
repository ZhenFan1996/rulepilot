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
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
@Transactional(readOnly = true)
class RuleEvidenceLookupService implements RuleEvidenceLookup {

    private final RuleEvidenceLookupRepository evidence;

    RuleEvidenceLookupService(RuleEvidenceLookupRepository evidence) {
        this.evidence = evidence;
    }

    @Override
    public int canonicalChunkCount(UUID documentVersionId) {
        if (documentVersionId == null) {
            throw new IllegalArgumentException("document version is required");
        }
        int count = evidence.canonicalChunkCount(documentVersionId);
        if (count < 0) throw new IllegalStateException("canonical evidence count is invalid");
        return count;
    }

    @Override
    public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
        if (documentVersionId == null || chunkIds == null || chunkIds.isEmpty()) {
            throw new IllegalArgumentException("document version and evidence chunk ids are required");
        }
        return evidence.findByChunkIds(documentVersionId, Set.copyOf(chunkIds));
    }

    @Override
    public List<RuleEvidenceHit> findByChunkIds(
            UUID documentVersionId, Set<UUID> chunkIds, int offset, int limit) {
        if (documentVersionId == null || chunkIds == null || chunkIds.isEmpty()
                || chunkIds.size() > RuleEvidenceLookup.MAX_IDENTITIES_PER_QUERY
                || offset < 0 || limit < 1) {
            throw new IllegalArgumentException("evidence id page is invalid");
        }
        return evidence.findByChunkIds(documentVersionId, Set.copyOf(chunkIds), offset, limit);
    }

    @Override
    public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
        if (documentVersionId == null || pageNumbers == null || pageNumbers.isEmpty()
                || pageNumbers.stream().anyMatch(page -> page == null || page < 1)) {
            throw new IllegalArgumentException("document version and evidence page numbers are required");
        }
        return evidence.findByPageNumbers(documentVersionId, Set.copyOf(pageNumbers));
    }

    @Override
    public List<RuleEvidenceHit> findByPageNumbers(
            UUID documentVersionId, Set<Integer> pageNumbers, int offset, int limit) {
        if (documentVersionId == null || pageNumbers == null || pageNumbers.isEmpty()
                || pageNumbers.size() > RuleEvidenceLookup.MAX_IDENTITIES_PER_QUERY
                || pageNumbers.stream().anyMatch(page -> page == null || page < 1)
                || offset < 0 || limit < 1) {
            throw new IllegalArgumentException("evidence page window is invalid");
        }
        return evidence.findByPageNumbers(documentVersionId, Set.copyOf(pageNumbers), offset, limit);
    }

    @Override
    public List<RuleEvidenceHit> findAdjacent(
            UUID documentVersionId, Set<UUID> anchorChunkIds, int radius, Set<String> sectionTypes) {
        if (documentVersionId == null || anchorChunkIds == null || anchorChunkIds.isEmpty()
                || anchorChunkIds.stream().anyMatch(java.util.Objects::isNull) || radius < 1
                || sectionTypes == null
                || sectionTypes.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("adjacent evidence lookup is invalid");
        }
        return evidence.findAdjacent(
                documentVersionId, Set.copyOf(anchorChunkIds), radius,
                sectionTypes.stream().map(String::strip).map(value -> value.toUpperCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet()));
    }

    @Override
    public List<RuleEvidenceHit> findAdjacent(
            UUID documentVersionId,
            Set<UUID> anchorChunkIds,
            int radius,
            Set<String> sectionTypes,
            int offset,
            int limit) {
        if (documentVersionId == null || anchorChunkIds == null || anchorChunkIds.isEmpty()
                || anchorChunkIds.size() > RuleEvidenceLookup.MAX_IDENTITIES_PER_QUERY
                || anchorChunkIds.stream().anyMatch(java.util.Objects::isNull) || radius < 1
                || sectionTypes == null
                || sectionTypes.stream().anyMatch(value -> value == null || value.isBlank())
                || offset < 0 || limit < 1) {
            throw new IllegalArgumentException("adjacent evidence page is invalid");
        }
        return evidence.findAdjacent(
                documentVersionId, Set.copyOf(anchorChunkIds), radius,
                sectionTypes.stream().map(String::strip).map(value -> value.toUpperCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet()),
                offset, limit);
    }
}
