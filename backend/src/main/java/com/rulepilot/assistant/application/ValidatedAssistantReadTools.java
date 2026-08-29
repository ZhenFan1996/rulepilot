package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RulePageImage;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidenceContext;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidenceContextPage;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidencePage;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.HybridRuleSearch.RetrievalOptions;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class ValidatedAssistantReadTools implements AssistantReadTools {

    private static final Logger LOGGER = LoggerFactory.getLogger(ValidatedAssistantReadTools.class);
    // rule_chunk.section_type is VARCHAR(40). This validates the stored identity shape rather than
    // inventing an application candidate-count limit.
    private static final Pattern SECTION_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    private final HybridRuleSearch retrieval;
    private final RuleEvidenceLookup evidenceLookup;
    private final DocumentPageImages pageImages;

    @Autowired
    public ValidatedAssistantReadTools(
            HybridRuleSearch retrieval,
            RuleEvidenceLookup evidenceLookup,
            DocumentPageImages pageImages) {
        this.retrieval = retrieval;
        this.evidenceLookup = evidenceLookup;
        this.pageImages = pageImages;
    }

    public ValidatedAssistantReadTools(HybridRuleSearch retrieval, RuleEvidenceLookup evidenceLookup) {
        this(retrieval, evidenceLookup, (documentVersionId, pageNumbers) -> List.of());
    }

    public ValidatedAssistantReadTools(HybridRuleSearch retrieval) {
        this(retrieval, (documentVersionId, chunkIds) -> List.of(), (documentVersionId, pageNumbers) -> List.of());
    }

    @Override
    public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
        validate(request);
        int corpusSize = evidenceLookup.canonicalChunkCount(request.documentVersionId());
        if (corpusSize == 0) return List.of();
        int effectiveLimit = corpusSize < 0 ? request.limit() : Math.min(request.limit(), corpusSize);
        SearchRuleEvidence effective = new SearchRuleEvidence(
                request.documentVersionId(), request.query(), effectiveLimit, request.sectionTypes(),
                request.currentSectionType(), request.includeAdjacentContext(), request.includePageImages());
        long expandedPageSize = (long) effectiveLimit * 3;
        int pageSize = request.includeAdjacentContext() && expandedPageSize <= Integer.MAX_VALUE
                ? (int) expandedPageSize
                : effectiveLimit;
        return searchRuleEvidencePage(effective, 0, pageSize).evidence();
    }

    @Override
    public RuleEvidencePage searchRuleEvidencePage(SearchRuleEvidence request, int offset, int pageSize) {
        return searchRuleEvidencePage(request, offset, pageSize, Set.of());
    }

    @Override
    public RuleEvidencePage searchRuleEvidencePage(
            SearchRuleEvidence request, int offset, int pageSize, Set<UUID> excludedEvidenceIds) {
        validate(request);
        if (offset < 0 || pageSize < 1 || offset >= request.limit()) {
            throw new IllegalArgumentException("rule search page window is invalid");
        }
        int remainingRequested = request.limit() - offset;
        int anchorLimit = request.includeAdjacentContext()
                ? Math.min(remainingRequested, Math.max(1, pageSize / 3))
                : Math.min(remainingRequested, pageSize);
        Set<String> sectionTypes = request.sectionTypes().stream()
                .map(String::strip)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        String currentSection = request.currentSectionType() == null
                ? null
                : request.currentSectionType().strip().toUpperCase(Locale.ROOT);
        if (excludedEvidenceIds == null || excludedEvidenceIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("excluded rule evidence identities are invalid");
        }
        int retrievalOffset = excludedEvidenceIds.isEmpty() ? offset : 0;
        var retrievalPage = retrieval.searchPage(
                request.documentVersionId(),
                request.query().strip(),
                new RetrievalOptions(
                        anchorLimit,
                        sectionTypes,
                        currentSection,
                        null,
                        retrievalOffset,
                        Set.copyOf(excludedEvidenceIds)));
        var hits = retrievalPage.hits();
        if (hits.size() > anchorLimit) {
            throw new IllegalStateException("retrieval tool returned more evidence than requested");
        }
        List<RuleEvidenceHit> evidence = hits.stream().map(hit -> hit.evidence())
                .filter(hit -> !excludedEvidenceIds.contains(hit.chunkId()))
                .toList();
        if (request.includeAdjacentContext()) {
            evidence = expandAdjacent(request, sectionTypes, evidence, pageSize);
        }
        evidence = evidence.stream()
                .filter(hit -> !excludedEvidenceIds.contains(hit.chunkId()))
                .toList();
        Map<Integer, RulePageImage> visuals = pageVisuals(request, evidence);
        List<RuleEvidence> result = evidence.stream().map(source -> {
            if (!request.documentVersionId().equals(source.documentVersionId())) {
                throw new IllegalStateException("retrieval tool returned evidence outside document scope");
            }
            return new RuleEvidence(
                    source.chunkId(),
                    source.documentVersionId(),
                    source.sectionType(),
                    source.heading(),
                    source.excerpt(),
                    source.pageFrom(),
                    source.pageTo(),
                    visuals.values().stream()
                            .filter(image -> image.pageNumber() >= source.pageFrom()
                                    && image.pageNumber() <= source.pageTo())
                            .toList(),
                    contentKind(source));
        }).toList();
        boolean hasMore = retrievalPage.hasMore() && offset + hits.size() < request.limit();
        AssistantReadTools.SourceAvailability sourceAvailability = switch (retrievalPage.sourceAvailability()) {
            case COMPLETE -> AssistantReadTools.SourceAvailability.COMPLETE;
            case PARTIAL -> AssistantReadTools.SourceAvailability.PARTIAL;
        };
        return new RuleEvidencePage(result, hasMore, hits.size(), sourceAvailability);
    }

    @Override
    public List<RuleEvidence> readRuleEvidencePages(
            UUID documentVersionId, Set<Integer> pageNumbers, boolean includePageImages) {
        if (documentVersionId == null || pageNumbers == null || pageNumbers.isEmpty()
                || pageNumbers.stream().anyMatch(page -> page == null || page < 1)) {
            throw new IllegalArgumentException("rule evidence page read is invalid");
        }
        Set<Integer> requestedPages = pageNumbers.stream()
                .sorted()
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<RuleEvidenceHit> evidence = new java.util.ArrayList<>();
        List<Integer> orderedPages = List.copyOf(requestedPages);
        for (int start = 0; start < orderedPages.size(); start += RuleEvidenceLookup.MAX_IDENTITIES_PER_QUERY) {
            Set<Integer> batch = new LinkedHashSet<>(orderedPages.subList(
                    start, Math.min(start + RuleEvidenceLookup.MAX_IDENTITIES_PER_QUERY, orderedPages.size())));
            evidence.addAll(evidenceLookup.findByPageNumbers(documentVersionId, batch));
        }
        Map<Integer, RulePageImage> visuals = includePageImages
                ? exactPageVisuals(documentVersionId, requestedPages)
                : Map.of();
        return evidence.stream()
                .peek(source -> {
                    if (!documentVersionId.equals(source.documentVersionId())) {
                        throw new IllegalStateException("page evidence escaped document scope");
                    }
                    boolean intersectsRequestedPage = java.util.stream.IntStream
                            .rangeClosed(source.pageFrom(), source.pageTo())
                            .anyMatch(requestedPages::contains);
                    if (!intersectsRequestedPage) {
                        throw new IllegalStateException("page evidence escaped the requested page scope");
                    }
                })
                .map(source -> new RuleEvidence(
                        source.chunkId(),
                        source.documentVersionId(),
                        source.sectionType(),
                        source.heading(),
                        source.excerpt(),
                        source.pageFrom(),
                        source.pageTo(),
                        visuals.values().stream()
                                .filter(image -> image.pageNumber() >= source.pageFrom()
                                        && image.pageNumber() <= source.pageTo())
                                .toList(),
                        contentKind(source)))
                .toList();
    }

    @Override
    public RuleEvidencePage readRuleEvidencePagesPage(
            UUID documentVersionId,
            Set<Integer> pageNumbers,
            boolean includePageImages,
            int offset,
            int pageSize) {
        if (documentVersionId == null || pageNumbers == null || pageNumbers.isEmpty()
                || pageNumbers.size() > RuleEvidenceLookup.MAX_IDENTITIES_PER_QUERY
                || pageNumbers.stream().anyMatch(page -> page == null || page < 1)
                || offset < 0 || pageSize < 1) {
            throw new IllegalArgumentException("rule evidence page window is invalid");
        }
        Set<Integer> requestedPages = pageNumbers.stream().sorted()
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<RuleEvidenceHit> sources = evidenceLookup.findByPageNumbers(
                documentVersionId, requestedPages, offset, pageSize);
        Map<Integer, RulePageImage> visuals = includePageImages
                ? exactPageVisuals(documentVersionId, requestedPages)
                : Map.of();
        List<RuleEvidence> result = sources.stream()
                .peek(source -> validatePageEvidence(documentVersionId, requestedPages, source))
                .map(source -> new RuleEvidence(
                        source.chunkId(), source.documentVersionId(), source.sectionType(), source.heading(),
                        source.excerpt(), source.pageFrom(), source.pageTo(),
                        visuals.values().stream().filter(image -> image.pageNumber() >= source.pageFrom()
                                && image.pageNumber() <= source.pageTo()).toList(),
                        contentKind(source)))
                .toList();
        return new RuleEvidencePage(result, result.size() == pageSize, result.size());
    }

    private Map<Integer, RulePageImage> exactPageVisuals(
            UUID documentVersionId, Set<Integer> requestedPages) {
        List<Integer> ordered = List.copyOf(requestedPages);
        Map<Integer, RulePageImage> visuals = new LinkedHashMap<>();
        for (int start = 0; start < ordered.size(); start += DocumentPageImages.MAX_PAGES_PER_READ) {
            Set<Integer> batch = new LinkedHashSet<>(ordered.subList(
                    start,
                    Math.min(start + DocumentPageImages.MAX_PAGES_PER_READ, ordered.size())));
            List<DocumentPageImages.PageImage> batchImages;
            try {
                batchImages = pageImages.read(documentVersionId, batch);
            } catch (RuntimeException unavailableBatch) {
                LOGGER.warn(
                        "Optional rule page image batch is unavailable; preserving text evidence: "
                                + "documentVersionId={}, pages={}, failureType={}",
                        documentVersionId,
                        batch,
                        unavailableBatch.getClass().getSimpleName());
                continue;
            }
            for (DocumentPageImages.PageImage image : batchImages) {
                if (!batch.contains(image.pageNumber())) {
                    throw new IllegalStateException("page image escaped the requested page scope");
                }
                RulePageImage previous = visuals.putIfAbsent(
                        image.pageNumber(),
                        new RulePageImage(
                                image.pageNumber(), image.mediaType(), image.content(), image.width(), image.height()));
                if (previous != null) throw new IllegalStateException("page image read returned a duplicate page");
            }
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(visuals));
    }

    @Override
    public List<RuleEvidence> readRuleEvidenceIds(UUID documentVersionId, Set<UUID> evidenceIds) {
        if (documentVersionId == null || evidenceIds == null || evidenceIds.isEmpty()
                || evidenceIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("rule evidence id read is invalid");
        }
        Set<UUID> requestedIds = Set.copyOf(evidenceIds);
        List<RuleEvidenceHit> sources = new java.util.ArrayList<>();
        List<UUID> ordered = requestedIds.stream().sorted().toList();
        for (int start = 0; start < ordered.size(); start += RuleEvidenceLookup.MAX_IDENTITIES_PER_QUERY) {
            Set<UUID> batch = new LinkedHashSet<>(ordered.subList(
                    start, Math.min(start + RuleEvidenceLookup.MAX_IDENTITIES_PER_QUERY, ordered.size())));
            sources.addAll(evidenceLookup.findByChunkIds(documentVersionId, batch));
        }
        return sources.stream()
                .peek(source -> {
                    if (!documentVersionId.equals(source.documentVersionId())
                            || !requestedIds.contains(source.chunkId())) {
                        throw new IllegalStateException("evidence id read escaped the requested scope");
                    }
                })
                .map(source -> new RuleEvidence(
                        source.chunkId(),
                        source.documentVersionId(),
                        source.sectionType(),
                        source.heading(),
                        source.excerpt(),
                        source.pageFrom(),
                        source.pageTo(),
                        List.of(),
                        contentKind(source)))
                .toList();
    }

    @Override
    public RuleEvidencePage readRuleEvidenceIdsPage(
            UUID documentVersionId, Set<UUID> evidenceIds, int offset, int pageSize) {
        if (documentVersionId == null || evidenceIds == null || evidenceIds.isEmpty()
                || evidenceIds.size() > RuleEvidenceLookup.MAX_IDENTITIES_PER_QUERY
                || evidenceIds.stream().anyMatch(java.util.Objects::isNull)
                || offset < 0 || pageSize < 1) {
            throw new IllegalArgumentException("rule evidence id window is invalid");
        }
        Set<UUID> requestedIds = Set.copyOf(evidenceIds);
        List<RuleEvidence> result = evidenceLookup.findByChunkIds(
                        documentVersionId, requestedIds, offset, pageSize).stream()
                .peek(source -> validateIdEvidence(documentVersionId, requestedIds, source))
                .map(this::ruleEvidence)
                .toList();
        return new RuleEvidencePage(result, result.size() == pageSize, result.size());
    }

    @Override
    public RuleEvidenceContext readRuleEvidenceContext(
            UUID documentVersionId, Set<UUID> anchorEvidenceIds, int radius) {
        if (documentVersionId == null || anchorEvidenceIds == null || anchorEvidenceIds.isEmpty()
                || anchorEvidenceIds.stream().anyMatch(java.util.Objects::isNull) || radius < 1) {
            throw new IllegalArgumentException("rule evidence context read is invalid");
        }
        Set<UUID> anchors = Set.copyOf(anchorEvidenceIds);
        List<RuleEvidence> canonicalAnchors = evidenceLookup.findByChunkIds(documentVersionId, anchors).stream()
                .peek(source -> {
                    validateContextEvidence(documentVersionId, source);
                    if (!anchors.contains(source.chunkId())) {
                        throw new IllegalStateException("context anchor escaped the requested scope");
                    }
                })
                .map(this::ruleEvidence)
                .toList();
        Set<UUID> resolvedAnchorIds = canonicalAnchors.stream()
                .map(RuleEvidence::chunkId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (resolvedAnchorIds.isEmpty()) {
            return new RuleEvidenceContext(List.of(), List.of());
        }
        int canonicalChunkCount = evidenceLookup.canonicalChunkCount(documentVersionId);
        int effectiveRadius = canonicalChunkCount < 0
                ? radius
                : Math.min(radius, Math.max(1, canonicalChunkCount - 1));
        List<RuleEvidence> surrounding = evidenceLookup.findAdjacent(
                        documentVersionId, resolvedAnchorIds, effectiveRadius, Set.of()).stream()
                .peek(source -> validateContextEvidence(documentVersionId, source))
                .filter(source -> !resolvedAnchorIds.contains(source.chunkId()))
                .map(this::ruleEvidence)
                .toList();
        return new RuleEvidenceContext(canonicalAnchors, surrounding);
    }

    @Override
    public RuleEvidenceContextPage readRuleEvidenceContextPage(
            UUID documentVersionId,
            Set<UUID> anchorEvidenceIds,
            int radius,
            int offset,
            int pageSize) {
        if (documentVersionId == null || anchorEvidenceIds == null || anchorEvidenceIds.isEmpty()
                || anchorEvidenceIds.size() > RuleEvidenceLookup.MAX_IDENTITIES_PER_QUERY
                || anchorEvidenceIds.stream().anyMatch(java.util.Objects::isNull)
                || radius < 1 || offset < 0 || pageSize < 1) {
            throw new IllegalArgumentException("rule evidence context window is invalid");
        }
        Set<UUID> requested = Set.copyOf(anchorEvidenceIds);
        List<RuleEvidence> anchors = evidenceLookup.findByChunkIds(documentVersionId, requested).stream()
                .peek(source -> {
                    validateContextEvidence(documentVersionId, source);
                    if (!requested.contains(source.chunkId())) {
                        throw new IllegalStateException("context anchor escaped the requested scope");
                    }
                })
                .map(this::ruleEvidence)
                .toList();
        Set<UUID> resolved = anchors.stream().map(RuleEvidence::chunkId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<RuleEvidence> returnedAnchors = List.of();
        int remaining = pageSize;
        int adjacentOffset = Math.max(0, offset - anchors.size());
        if (offset < anchors.size()) {
            returnedAnchors = anchors.subList(offset, Math.min(anchors.size(), offset + pageSize));
            remaining -= returnedAnchors.size();
        }
        List<RuleEvidence> surrounding = remaining > 0 && !resolved.isEmpty()
                ? evidenceLookup.findAdjacent(
                                documentVersionId, resolved, radius, Set.of(), adjacentOffset, remaining).stream()
                        .peek(source -> validateContextEvidence(documentVersionId, source))
                        .filter(source -> !resolved.contains(source.chunkId()))
                        .map(this::ruleEvidence)
                        .toList()
                : List.of();
        int returned = returnedAnchors.size() + surrounding.size();
        boolean moreAnchors = offset + returnedAnchors.size() < anchors.size();
        boolean hasMore = moreAnchors || returned == pageSize;
        return new RuleEvidenceContextPage(returnedAnchors, surrounding, hasMore);
    }

    private void validateContextEvidence(UUID documentVersionId, RuleEvidenceHit source) {
        if (!documentVersionId.equals(source.documentVersionId())) {
            throw new IllegalStateException("context evidence escaped document scope");
        }
    }

    private RuleEvidence ruleEvidence(RuleEvidenceHit source) {
        return new RuleEvidence(
                source.chunkId(),
                source.documentVersionId(),
                source.sectionType(),
                source.heading(),
                source.excerpt(),
                source.pageFrom(),
                source.pageTo(),
                List.of(),
                contentKind(source));
    }

    private RuleEvidence.ContentKind contentKind(RuleEvidenceHit source) {
        return switch (source.contentKind()) {
            case CANONICAL_TEXT -> RuleEvidence.ContentKind.CANONICAL_TEXT;
            case VISUAL_PLACEHOLDER -> RuleEvidence.ContentKind.VISUAL_PLACEHOLDER;
            case CANONICAL_TEXT_WITH_VISUAL_FACTS -> RuleEvidence.ContentKind.CANONICAL_TEXT_WITH_VISUAL_FACTS;
            case VISUAL_TRANSCRIPTION -> RuleEvidence.ContentKind.VISUAL_TRANSCRIPTION;
        };
    }

    private Map<Integer, RulePageImage> pageVisuals(
            SearchRuleEvidence request, List<RuleEvidenceHit> evidence) {
        if (!request.includePageImages()) {
            return Map.of();
        }
        Set<Integer> requestedPages = evidence.stream()
                .flatMapToInt(source -> java.util.stream.IntStream.rangeClosed(
                        source.pageFrom(), source.pageTo()))
                .boxed()
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (requestedPages.isEmpty()) {
            return Map.of();
        }
        return exactPageVisuals(request.documentVersionId(), requestedPages);
    }

    private List<RuleEvidenceHit> expandAdjacent(
            SearchRuleEvidence request,
            Set<String> sectionTypes,
            List<RuleEvidenceHit> ranked,
            int pageSize) {
        List<RuleEvidenceHit> anchors = ranked;
        if (anchors.isEmpty()) {
            return ranked;
        }
        Set<UUID> anchorIds = anchors.stream()
                .map(RuleEvidenceHit::chunkId)
                .collect(java.util.stream.Collectors.toSet());
        int remaining = Math.max(0, pageSize - anchors.size());
        List<RuleEvidenceHit> adjacent = remaining == 0
                ? List.of()
                : evidenceLookup.findAdjacent(
                        request.documentVersionId(), anchorIds, 1, sectionTypes, 0, remaining);
        Map<UUID, RuleEvidenceHit> merged = new LinkedHashMap<>();
        anchors.forEach(source -> {
            validateExpandedEvidence(request, sectionTypes, source);
            merged.put(source.chunkId(), source);
        });
        for (RuleEvidenceHit source : adjacent) {
            validateExpandedEvidence(request, sectionTypes, source);
            merged.putIfAbsent(source.chunkId(), source);
        }
        ranked.forEach(source -> merged.putIfAbsent(source.chunkId(), source));
        return List.copyOf(merged.values());
    }

    private void validatePageEvidence(
            UUID documentVersionId, Set<Integer> requestedPages, RuleEvidenceHit source) {
        if (!documentVersionId.equals(source.documentVersionId())) {
            throw new IllegalStateException("page evidence escaped document scope");
        }
        boolean intersectsRequestedPage = java.util.stream.IntStream.rangeClosed(source.pageFrom(), source.pageTo())
                .anyMatch(requestedPages::contains);
        if (!intersectsRequestedPage) {
            throw new IllegalStateException("page evidence escaped the requested page scope");
        }
    }

    private void validateIdEvidence(UUID documentVersionId, Set<UUID> requestedIds, RuleEvidenceHit source) {
        if (!documentVersionId.equals(source.documentVersionId()) || !requestedIds.contains(source.chunkId())) {
            throw new IllegalStateException("evidence id read escaped the requested scope");
        }
    }

    private void validateExpandedEvidence(
            SearchRuleEvidence request, Set<String> sectionTypes, RuleEvidenceHit source) {
        if (!request.documentVersionId().equals(source.documentVersionId())
                || (!sectionTypes.isEmpty()
                        && !sectionTypes.contains(source.sectionType().toUpperCase(Locale.ROOT)))) {
            throw new IllegalStateException("expanded evidence escaped the requested scope");
        }
    }

    private void validate(SearchRuleEvidence request) {
        if (request == null || request.documentVersionId() == null) {
            throw new IllegalArgumentException("document version is required for rule search");
        }
        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("rule search query must be non-blank");
        }
        if (request.query().chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("rule search query must not contain control characters");
        }
        if (request.limit() < 1) {
            throw new IllegalArgumentException("rule search result limit must be positive");
        }
        if (request.sectionTypes() == null) {
            throw new IllegalArgumentException("rule search sectionTypes must be present; use an empty set for no filter");
        }
        if (request.sectionTypes().stream().anyMatch(this::invalidSectionType)) {
            throw new IllegalArgumentException("each rule search sectionTypes value must match a stored section identity");
        }
        if (request.currentSectionType() != null && invalidSectionType(request.currentSectionType())) {
            throw new IllegalArgumentException("currentSectionType must match a stored section identity");
        }
    }

    private boolean invalidSectionType(String value) {
        return value == null || !SECTION_TYPE.matcher(value.strip().toUpperCase(Locale.ROOT)).matches();
    }
}
