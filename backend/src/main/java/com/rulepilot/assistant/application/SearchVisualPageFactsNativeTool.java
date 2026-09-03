package com.rulepilot.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.NativeAgentTool;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit.ContentKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class SearchVisualPageFactsNativeTool implements NativeAgentTool {

    private static final int MAX_RESULTS = 5;
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": {"type": "string", "description": "A visible printed label, icon name, table heading, diagram term, or component identifier from the player's question.", "minLength": 1, "maxLength": 600},
                "limit": {"type": "integer", "description": "The number of page candidates needed, at most five.", "minimum": 1, "maximum": 5}
              },
              "required": ["query", "limit"],
              "additionalProperties": true
            }
            """;

    private final VisualRulebookPageFactSearch facts;
    private final RuleEvidenceLookup evidence;
    private final ObjectMapper objectMapper;

    public SearchVisualPageFactsNativeTool(
            VisualRulebookPageFactSearch facts,
            RuleEvidenceLookup evidence,
            ObjectMapper objectMapper) {
        this.facts = facts;
        this.evidence = evidence;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "search_visual_page_facts";
    }

    @Override
    public String description() {
        return "Use only when a visible icon, printed identifier, table heading, diagram label, or board location "
                + "cannot be located through canonical text search. It finds page-scoped visual observations in the "
                + "active immutable rulebook and returns a page-bound evidence handle for a dependent page or image "
                + "read. Visual observations locate content but never establish a mechanical ruling; confirm rule "
                + "claims with canonical text and do not retry with superficial synonyms.";
    }

    @Override
    public String inputSchema() {
        return INPUT_SCHEMA;
    }

    @Override
    public String schemaVersion() {
        return "1";
    }

    @Override
    public Set<Role> allowedRoles() {
        return Set.of(Role.ANSWER, Role.VISUAL);
    }

    @Override
    public ToolObservation execute(String argumentsJson, ToolScope scope) {
        Arguments arguments = parse(argumentsJson);
        if (arguments.query() == null || arguments.query().isBlank()
                || arguments.query().length() > 600
                || arguments.limit() == null || arguments.limit() < 1 || arguments.limit() > MAX_RESULTS) {
            throw new IllegalArgumentException("visual page fact search arguments are invalid");
        }
        var matches = facts.search(scope.documentVersionId(), arguments.query().strip(), arguments.limit());
        Set<Integer> pages = matches.stream()
                .map(VisualRulebookPageFactSearch.PageFactMatch::pageNumber)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<Integer, RuleEvidenceHit> handles = new LinkedHashMap<>();
        if (!pages.isEmpty()) {
            evidence.findByPageNumbers(scope.documentVersionId(), pages).stream()
                    .filter(hit -> hit.pageFrom() == hit.pageTo())
                    .sorted(java.util.Comparator.comparing(
                            (RuleEvidenceHit hit) -> hit.contentKind() != ContentKind.VISUAL_PLACEHOLDER))
                    .forEach(hit -> handles.putIfAbsent(hit.pageFrom(), hit));
        }
        List<Map<String, Object>> results = matches.stream()
                .filter(match -> handles.containsKey(match.pageNumber()))
                .map(match -> result(match, handles.get(match.pageNumber())))
                .toList();
        Map<String, Object> data = Map.of(
                "mechanicalRuleAuthority", false,
                "results", results);
        return results.isEmpty()
                ? ToolObservation.partial("VISUAL_PAGE_FACTS_NOT_FOUND", data, 0)
                : ToolObservation.success("VISUAL_PAGE_FACTS_FOUND", data, results.size());
    }

    private Map<String, Object> result(
            VisualRulebookPageFactSearch.PageFactMatch match,
            RuleEvidenceHit handle) {
        return Map.of(
                "evidenceId", handle.chunkId().toString(),
                "pageNumber", match.pageNumber(),
                "printedTerms", match.printedTerms(),
                "literalSummary", match.factualSummary(),
                "keywords", match.keywords(),
                "factStatus", match.ruleFactStatus().name(),
                "mechanicalRuleAuthority", false);
    }

    private Arguments parse(String json) {
        try {
            return objectMapper.readerFor(Arguments.class)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("visual page fact search arguments JSON could not be decoded", exception);
        }
    }

    private record Arguments(String query, Integer limit) {}
}
