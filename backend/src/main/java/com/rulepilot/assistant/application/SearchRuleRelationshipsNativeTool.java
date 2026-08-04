package com.rulepilot.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.SearchRuleEvidence;
import com.rulepilot.assistant.NativeAgentTool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Finds candidate exception and precedence passages without deciding which rule wins.
 *
 * <p>The generic cue classification is only a retrieval aid. The returned canonical excerpts remain the sole source
 * of mechanical authority and must be read in context before publication.</p>
 */
@Component
@Profile("!test")
public class SearchRuleRelationshipsNativeTool implements NativeAgentTool {

    private static final int MAX_TOPIC_LENGTH = 320;
    private static final int MAX_RESULTS = 6;
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "topic": {"type": "string", "minLength": 1, "maxLength": 320},
                "limit": {"type": "integer", "minimum": 1, "maximum": 6}
              },
              "required": ["topic", "limit"],
              "additionalProperties": false
            }
            """;
    private static final Map<String, Pattern> RELATION_CUES = Map.of(
            "EXCEPTION",
            Pattern.compile("(?iu)\\b(?:except(?:ion)?|unless|does\\s+not\\s+apply)\\b|除非|例外|不适用"),
            "REPLACEMENT",
            Pattern.compile("(?iu)\\b(?:instead|replaces?|rather\\s+than)\\b|改为|取代|替代"),
            "PRIORITY",
            Pattern.compile("(?iu)\\b(?:takes\\s+precedence|overrides?|supersedes?)\\b|优先于|覆盖"),
            "CONDITION",
            Pattern.compile("(?iu)\\b(?:only\\s+if|only\\s+when|provided\\s+that)\\b|仅当|只有.{0,30}才"));

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
        return "Search the active rulebook for candidate exception, replacement, priority, and conditional passages "
                + "about one topic. Cue labels are retrieval hints only; read exact pages before deciding a ruling.";
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
        Arguments arguments = parse(argumentsJson);
        if (arguments.topic() == null || arguments.topic().isBlank()
                || arguments.topic().length() > MAX_TOPIC_LENGTH
                || arguments.topic().chars().anyMatch(Character::isISOControl)
                || arguments.limit() == null || arguments.limit() < 1 || arguments.limit() > MAX_RESULTS) {
            throw new IllegalArgumentException("rule relationship search arguments are invalid");
        }
        String topic = arguments.topic().strip();
        LinkedHashMap<UUID, RuleEvidence> merged = new LinkedHashMap<>();
        for (String query : queries(topic)) {
            List<RuleEvidence> results = readTools.searchRuleEvidence(new SearchRuleEvidence(
                    scope.documentVersionId(), query, arguments.limit(), Set.of(), null, true, false));
            for (RuleEvidence evidence : results) {
                if (!scope.documentVersionId().equals(evidence.documentVersionId())) {
                    throw new IllegalStateException("relationship search escaped document scope");
                }
                merged.putIfAbsent(evidence.chunkId(), evidence);
            }
        }
        List<Map<String, Object>> observations = merged.values().stream()
                .sorted((left, right) -> Boolean.compare(hasRelationshipCue(right), hasRelationshipCue(left)))
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

    private List<String> queries(String topic) {
        return List.of(
                bounded(topic + " exception unless instead override only if", 500),
                bounded(topic + " 例外 除非 改为 优先于 仅当", 500),
                topic);
    }

    private Map<String, Object> observation(RuleEvidence evidence) {
        String searchable = evidence.heading() + "\n" + evidence.excerpt();
        List<String> cues = new ArrayList<>();
        RELATION_CUES.forEach((type, pattern) -> {
            if (pattern.matcher(searchable).find()) cues.add(type);
        });
        cues.sort(String::compareTo);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("evidenceId", evidence.chunkId().toString());
        value.put("sectionType", bounded(evidence.sectionType(), 80));
        value.put("heading", bounded(evidence.heading(), 240));
        value.put("excerpt", bounded(evidence.excerpt(), 1600));
        value.put("pageFrom", evidence.pageFrom());
        value.put("pageTo", evidence.pageTo());
        value.put("candidateRelationTypes", List.copyOf(cues));
        value.put("classificationAuthority", false);
        value.put("evidenceMechanicalAuthority", true);
        return Map.copyOf(value);
    }

    private boolean hasRelationshipCue(RuleEvidence evidence) {
        String searchable = evidence.heading() + "\n" + evidence.excerpt();
        return RELATION_CUES.values().stream().anyMatch(pattern -> pattern.matcher(searchable).find());
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
