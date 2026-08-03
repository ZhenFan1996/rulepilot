package com.rulepilot.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.SearchRuleEvidence;
import com.rulepilot.assistant.NativeAgentTool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class SearchRuleEvidenceNativeTool implements NativeAgentTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": {"type": "string", "minLength": 1, "maxLength": 500},
                "limit": {"type": "integer", "minimum": 1, "maximum": 6},
                "sectionTypes": {
                  "type": "array",
                  "items": {"type": "string", "pattern": "^[A-Za-z][A-Za-z0-9_]{1,49}$"},
                  "maxItems": 6,
                  "uniqueItems": true
                },
                "currentSectionType": {
                  "type": ["string", "null"],
                  "pattern": "^[A-Za-z][A-Za-z0-9_]{1,49}$"
                },
                "includeAdjacentContext": {"type": "boolean"}
              },
              "required": ["query", "limit", "sectionTypes", "includeAdjacentContext"],
              "additionalProperties": false
            }
            """;

    private final AssistantReadTools readTools;
    private final ObjectMapper objectMapper;

    public SearchRuleEvidenceNativeTool(AssistantReadTools readTools, ObjectMapper objectMapper) {
        this.readTools = readTools;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "search_rule_evidence";
    }

    @Override
    public String description() {
        return "Search the active rulebook version for cited rule evidence relevant to one bounded player need.";
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
        return Set.of(Role.ANSWER, Role.TEACHING);
    }

    @Override
    public ToolObservation execute(String argumentsJson, ToolScope scope) {
        SearchArguments arguments = parse(argumentsJson);
        if (arguments.query() == null || arguments.query().isBlank() || arguments.query().length() > 500
                || arguments.limit() == null || arguments.limit() < 1 || arguments.limit() > 6
                || arguments.sectionTypes() == null || arguments.sectionTypes().size() > 6
                || arguments.includeAdjacentContext() == null) {
            throw new IllegalArgumentException("search arguments are invalid");
        }
        List<RuleEvidence> evidence = readTools.searchRuleEvidence(new SearchRuleEvidence(
                scope.documentVersionId(),
                arguments.query(),
                arguments.limit(),
                Set.copyOf(arguments.sectionTypes()),
                arguments.currentSectionType(),
                arguments.includeAdjacentContext(),
                false));
        List<Map<String, Object>> observations = evidence.stream().map(this::observation).toList();
        Map<String, Object> data = Map.of("evidence", observations);
        return evidence.isEmpty()
                ? ToolObservation.partial("NO_EVIDENCE", data, 0)
                : ToolObservation.success("EVIDENCE_FOUND", data, evidence.size());
    }

    private SearchArguments parse(String argumentsJson) {
        try {
            return objectMapper.readerFor(SearchArguments.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(argumentsJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("search arguments are invalid", exception);
        }
    }

    private Map<String, Object> observation(RuleEvidence evidence) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("evidenceId", evidence.chunkId().toString());
        value.put("sectionType", bounded(evidence.sectionType(), 80));
        value.put("heading", bounded(evidence.heading(), 240));
        value.put("excerpt", bounded(evidence.excerpt(), 1600));
        value.put("pageFrom", evidence.pageFrom());
        value.put("pageTo", evidence.pageTo());
        return Map.copyOf(value);
    }

    private String bounded(String value, int maximum) {
        if (value == null) return "";
        String stripped = value.strip();
        return stripped.length() <= maximum ? stripped : stripped.substring(0, maximum);
    }

    private record SearchArguments(
            String query,
            Integer limit,
            List<String> sectionTypes,
            String currentSectionType,
            Boolean includeAdjacentContext) {}
}
