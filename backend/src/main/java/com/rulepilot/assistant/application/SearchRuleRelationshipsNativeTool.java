package com.rulepilot.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.SearchRuleEvidence;
import com.rulepilot.assistant.NativeAgentTool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Retrieves canonical passages for a model-selected relationship topic without classifying their meaning. */
@Component
@Profile("!test")
public class SearchRuleRelationshipsNativeTool implements NativeAgentTool {

    private static final int MAX_TOPIC_LENGTH = 320;
    private static final int MAX_RESULTS = 6;
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "topic": {"type": "string", "description": "One concrete relationship between the current general rule and its possible exception, replacement, precedence, or condition, using active-document terms.", "minLength": 1, "maxLength": 320},
                "limit": {"type": "integer", "description": "Smallest useful candidate count; prefer 3 and never exceed 6.", "minimum": 1, "maximum": 6}
              },
              "required": ["topic", "limit"],
              "additionalProperties": false
            }
            """;

    private final AssistantReadTools readTools;
    private final ObjectMapper objectMapper;

    public SearchRuleRelationshipsNativeTool(AssistantReadTools readTools, ObjectMapper objectMapper) {
        this.readTools = readTools;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "search_rule_relationships";
    }

    @Override
    public String description() {
        return "Use when a plan needs the relationship between a general rule and a possible exception, replacement, "
                + "conflict, precedence, or condition. Search one topic in the active immutable rulebook; do not use "
                + "it for a direct-rule lookup. Passages are authoritative source text, but cue labels do not decide "
                + "which rule wins or applies. Read exact pages and compare scope. Stop when both sides are covered or "
                + "no independent candidate remains.";
    }

    @Override
    public String inputSchema() {
        return INPUT_SCHEMA;
    }

    @Override
    public String schemaVersion() {
        return "2";
    }

    @Override
    public Set<Role> allowedRoles() {
        return Set.of(Role.ANSWER, Role.TEACHING);
    }

    @Override
    public ToolObservation execute(String argumentsJson, ToolScope scope) {
        Arguments arguments = parse(argumentsJson);
        if (arguments.topic() == null || arguments.topic().isBlank()
                || arguments.topic().length() > MAX_TOPIC_LENGTH
                || arguments.topic().chars().anyMatch(Character::isISOControl)
                || arguments.limit() == null || arguments.limit() < 1 || arguments.limit() > MAX_RESULTS) {
            throw new IllegalArgumentException("rule relationship search arguments are invalid");
        }
        List<RuleEvidence> results = readTools.searchRuleEvidence(new SearchRuleEvidence(
                scope.documentVersionId(), arguments.topic().strip(), arguments.limit(), Set.of(), null, true, false));
        LinkedHashMap<UUID, RuleEvidence> canonical = new LinkedHashMap<>();
        for (RuleEvidence evidence : results) {
            if (!scope.documentVersionId().equals(evidence.documentVersionId())) {
                throw new IllegalStateException("relationship search escaped document scope");
            }
            canonical.putIfAbsent(evidence.chunkId(), evidence);
        }
        List<Map<String, Object>> observations = canonical.values().stream()
                .limit(arguments.limit())
                .map(this::observation)
                .toList();
        Map<String, Object> data = Map.of(
                "evidence", observations,
                "relationshipClassificationAuthority", false,
                "nextAction", "READ_EXACT_PAGES_AND_COMPARE_APPLICABILITY");
        return observations.isEmpty()
                ? ToolObservation.partial("NO_RELATIONSHIP_CANDIDATES", data, 0)
                : ToolObservation.success("RELATIONSHIP_CANDIDATES_FOUND", data, observations.size());
    }

    private Map<String, Object> observation(RuleEvidence evidence) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("evidenceId", evidence.chunkId().toString());
        value.put("sectionType", bounded(evidence.sectionType(), 80));
        value.put("heading", bounded(evidence.heading(), 240));
        value.put("excerpt", bounded(evidence.excerpt(), 1600));
        value.put("pageFrom", evidence.pageFrom());
        value.put("pageTo", evidence.pageTo());
        value.put("classificationAuthority", false);
        value.put("evidenceMechanicalAuthority", true);
        return Map.copyOf(value);
    }

    private Arguments parse(String argumentsJson) {
        try {
            return objectMapper.readerFor(Arguments.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(argumentsJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("rule relationship search arguments are invalid", exception);
        }
    }

    private String bounded(String value, int maximum) {
        String normalized = value == null ? "" : value.strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    private record Arguments(String topic, Integer limit) {}
}
