package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.HybridRuleSearch.RetrievalOptions;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
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

    public ValidatedAssistantReadTools(HybridRuleSearch retrieval) {
        this.retrieval = retrieval;
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
        return hits.stream().map(hit -> {
            var evidence = hit.evidence();
            if (!request.documentVersionId().equals(evidence.documentVersionId())) {
                throw new IllegalStateException("retrieval tool returned evidence outside document scope");
            }
            return new RuleEvidence(
                    evidence.chunkId(),
                    evidence.documentVersionId(),
                    evidence.sectionType(),
                    evidence.heading(),
                    evidence.excerpt(),
                    evidence.pageFrom(),
                    evidence.pageTo());
        }).toList();
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
