package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RulePageImage;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class ValidatedAssistantReadTools implements AssistantReadTools {

    private static final int MAX_QUERY_LENGTH = 500;
    private static final int MAX_RESULT_LIMIT = 10;
    private static final int MAX_SECTION_FILTERS = 6;
    private static final Pattern SECTION_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{1,49}");

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
        Set<String> sectionTypes = request.sectionTypes().stream()
                .map(String::strip)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        String currentSection = request.currentSectionType() == null
                ? null
                : request.currentSectionType().strip().toUpperCase(Locale.ROOT);
        var hits = retrieval.search(
                request.documentVersionId(),
                request.query().strip(),
                new RetrievalOptions(request.limit(), sectionTypes, currentSection));
        if (hits.size() > request.limit()) {
            throw new IllegalStateException("retrieval tool returned more evidence than requested");
        }
        List<RuleEvidenceHit> evidence = hits.stream().map(hit -> hit.evidence()).toList();
        if (request.includeAdjacentContext()) {
            evidence = expandAdjacent(request, sectionTypes, evidence);
        }
        Map<Integer, RulePageImage> visuals = pageVisuals(request, evidence);
        return evidence.stream().map(source -> {
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
                            .toList());
        }).toList();
    }

    @Override
    public List<RuleEvidence> readRuleEvidencePages(
            UUID documentVersionId, Set<Integer> pageNumbers, boolean includePageImages) {
        if (documentVersionId == null || pageNumbers == null || pageNumbers.isEmpty() || pageNumbers.size() > 5
                || pageNumbers.stream().anyMatch(page -> page == null || page < 1)) {
            throw new IllegalArgumentException("bounded rule evidence page read is invalid");
        }
        List<RuleEvidenceHit> evidence = evidenceLookup.findByPageNumbers(documentVersionId, Set.copyOf(pageNumbers));
        Map<Integer, RulePageImage> visuals = includePageImages
                ? pageImages.read(documentVersionId, new LinkedHashSet<>(pageNumbers)).stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                DocumentPageImages.PageImage::pageNumber,
                                image -> new RulePageImage(
                                        image.pageNumber(), image.mediaType(), image.content(), image.width(), image.height())))
                : Map.of();
        return evidence.stream().map(source -> new RuleEvidence(
                source.chunkId(),
                source.documentVersionId(),
                source.sectionType(),
                source.heading(),
                source.excerpt(),
                source.pageFrom(),
                source.pageTo(),
                visuals.values().stream()
                        .filter(image -> image.pageNumber() >= source.pageFrom() && image.pageNumber() <= source.pageTo())
                        .toList())).toList();
    }

    @Override
    public List<RuleEvidence> readRuleEvidenceIds(UUID documentVersionId, Set<UUID> evidenceIds) {
        if (documentVersionId == null || evidenceIds == null || evidenceIds.isEmpty() || evidenceIds.size() > 24
                || evidenceIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("bounded rule evidence id read is invalid");
        }
        return evidenceLookup.findByChunkIds(documentVersionId, Set.copyOf(evidenceIds)).stream()
                .filter(source -> documentVersionId.equals(source.documentVersionId()))
                .map(source -> new RuleEvidence(
                        source.chunkId(),
                        source.documentVersionId(),
                        source.sectionType(),
                        source.heading(),
                        source.excerpt(),
                        source.pageFrom(),
                        source.pageTo()))
                .toList();
    }

    private Map<Integer, RulePageImage> pageVisuals(
            SearchRuleEvidence request, List<RuleEvidenceHit> evidence) {
        if (!request.includePageImages()) {
            return Map.of();
        }
        Set<Integer> requestedPages = evidence.stream()
                .map(RuleEvidenceHit::pageFrom)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        requestedPages = requestedPages.stream().limit(2)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (requestedPages.isEmpty()) {
            return Map.of();
        }
        return pageImages.read(request.documentVersionId(), requestedPages).stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        DocumentPageImages.PageImage::pageNumber,
                        image -> new RulePageImage(
                                image.pageNumber(), image.mediaType(), image.content(), image.width(), image.height())));
    }

    private List<RuleEvidenceHit> expandAdjacent(
            SearchRuleEvidence request, Set<String> sectionTypes, List<RuleEvidenceHit> ranked) {
        List<RuleEvidenceHit> anchors = ranked.stream().limit(Math.min(2, request.limit())).toList();
        if (anchors.isEmpty()) {
            return ranked;
        }
        Set<UUID> anchorIds = anchors.stream()
                .map(RuleEvidenceHit::chunkId)
                .collect(java.util.stream.Collectors.toSet());
        Map<UUID, RuleEvidenceHit> hydratedAnchors = evidenceLookup
                .findByChunkIds(request.documentVersionId(), anchorIds)
                .stream()
                .collect(java.util.stream.Collectors.toMap(RuleEvidenceHit::chunkId, source -> source));
        List<RuleEvidenceHit> adjacent = evidenceLookup.findAdjacent(
                request.documentVersionId(), anchorIds, 1, sectionTypes);
        Map<UUID, RuleEvidenceHit> merged = new LinkedHashMap<>();
        anchors.forEach(source -> {
            RuleEvidenceHit hydrated = hydratedAnchors.getOrDefault(source.chunkId(), source);
            validateExpandedEvidence(request, sectionTypes, hydrated);
            merged.put(source.chunkId(), hydrated);
        });
        for (RuleEvidenceHit source : adjacent) {
            validateExpandedEvidence(request, sectionTypes, source);
            merged.putIfAbsent(source.chunkId(), source);
        }
        ranked.forEach(source -> merged.putIfAbsent(source.chunkId(), source));
        return merged.values().stream().limit(request.limit()).toList();
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
        if (request.query() == null || request.query().isBlank() || request.query().length() > MAX_QUERY_LENGTH
                || request.query().chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("rule search query is invalid");
        }
        if (request.limit() < 1 || request.limit() > MAX_RESULT_LIMIT) {
            throw new IllegalArgumentException("rule search result limit is invalid");
        }
        if (request.sectionTypes() == null || request.sectionTypes().size() > MAX_SECTION_FILTERS
                || request.sectionTypes().stream().anyMatch(this::invalidSectionType)
                || (request.currentSectionType() != null && invalidSectionType(request.currentSectionType()))) {
            throw new IllegalArgumentException("rule search section filter is invalid");
        }
    }

    private boolean invalidSectionType(String value) {
        return value == null || !SECTION_TYPE.matcher(value.strip().toUpperCase(Locale.ROOT)).matches();
    }
}
