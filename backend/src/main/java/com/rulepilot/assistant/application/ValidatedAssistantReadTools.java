package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.HybridRuleSearch.RetrievalOptions;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.LinkedHashMap;
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

    @Autowired
    public ValidatedAssistantReadTools(HybridRuleSearch retrieval, RuleEvidenceLookup evidenceLookup) {
        this.retrieval = retrieval;
        this.evidenceLookup = evidenceLookup;
    }

    public ValidatedAssistantReadTools(HybridRuleSearch retrieval) {
        this(retrieval, (documentVersionId, chunkIds) -> List.of());
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
                    source.pageTo());
        }).toList();
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
        List<RuleEvidenceHit> adjacent = evidenceLookup.findAdjacent(
                request.documentVersionId(), anchorIds, 1, sectionTypes);
        Map<UUID, RuleEvidenceHit> merged = new LinkedHashMap<>();
        anchors.forEach(source -> merged.put(source.chunkId(), source));
        for (RuleEvidenceHit source : adjacent) {
            if (!request.documentVersionId().equals(source.documentVersionId())
                    || !sectionTypes.contains(source.sectionType().toUpperCase(Locale.ROOT))) {
                throw new IllegalStateException("adjacent evidence escaped the requested scope");
            }
            merged.putIfAbsent(source.chunkId(), source);
        }
        ranked.forEach(source -> merged.putIfAbsent(source.chunkId(), source));
        return merged.values().stream().limit(request.limit()).toList();
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
                || (request.currentSectionType() != null && invalidSectionType(request.currentSectionType()))
                || (request.includeAdjacentContext() && request.sectionTypes().isEmpty())) {
            throw new IllegalArgumentException("rule search section filter is invalid");
        }
    }

    private boolean invalidSectionType(String value) {
        return value == null || !SECTION_TYPE.matcher(value.strip().toUpperCase(Locale.ROOT)).matches();
    }
}
