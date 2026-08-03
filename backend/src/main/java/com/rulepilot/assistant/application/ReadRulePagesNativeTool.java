package com.rulepilot.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.NativeAgentTool;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class ReadRulePagesNativeTool implements NativeAgentTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "pageNumbers": {
                  "type": "array",
                  "items": {"type": "integer", "minimum": 1},
                  "minItems": 1,
                  "maxItems": 5,
                  "uniqueItems": true
                }
              },
              "required": ["pageNumbers"],
              "additionalProperties": false
            }
            """;

    private final AssistantReadTools readTools;
    private final ObjectMapper objectMapper;

    public ReadRulePagesNativeTool(AssistantReadTools readTools, ObjectMapper objectMapper) {
        this.readTools = readTools;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "read_rule_pages";
    }

    @Override
    public String description() {
        return "Read cited text evidence from up to five exact pages of the active rulebook version.";
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
        ReadArguments arguments = parse(argumentsJson);
        if (arguments.pageNumbers() == null || arguments.pageNumbers().isEmpty() || arguments.pageNumbers().size() > 5
                || arguments.pageNumbers().stream().anyMatch(page -> page == null || page < 1)) {
            throw new IllegalArgumentException("page read arguments are invalid");
        }
        Set<Integer> pages = new LinkedHashSet<>(arguments.pageNumbers());
        if (pages.size() != arguments.pageNumbers().size()) {
            throw new IllegalArgumentException("page read arguments contain duplicates");
        }
        List<RuleEvidence> evidence = readTools.readRuleEvidencePages(scope.documentVersionId(), pages, false);
        List<Map<String, Object>> observations = evidence.stream().map(this::observation).toList();
        Map<String, Object> data = Map.of("requestedPages", List.copyOf(pages), "evidence", observations);
        return evidence.isEmpty()
                ? ToolObservation.partial("NO_PAGE_EVIDENCE", data, 0)
                : ToolObservation.success("PAGE_EVIDENCE_FOUND", data, evidence.size());
    }

    private ReadArguments parse(String argumentsJson) {
        try {
            return objectMapper.readerFor(ReadArguments.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(argumentsJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("page read arguments are invalid", exception);
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

    private record ReadArguments(List<Integer> pageNumbers) {}
}
