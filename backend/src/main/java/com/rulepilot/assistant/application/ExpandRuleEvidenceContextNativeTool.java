package com.rulepilot.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidenceContext;
import com.rulepilot.assistant.NativeAgentTool;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Expands already located evidence without turning neighboring prose into an automatic ruling. */
@Component
@Profile("!test")
public class ExpandRuleEvidenceContextNativeTool implements NativeAgentTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "evidenceIds": {
                  "type": "array",
                  "description": "Existing active-rulebook evidence handles whose immediately neighboring chunks may contain a governing condition, continuation, or exception.",
                  "items": {"type": "string", "format": "uuid"},
                  "minItems": 1,
                  "maxItems": 3,
                  "uniqueItems": true
                },
                "radius": {"type": "integer", "description": "Canonical chunks on each side; use 1 unless the visible structure clearly spans farther.", "minimum": 1, "maximum": 2}
              },
              "required": ["evidenceIds", "radius"],
              "additionalProperties": false
            }
            """;

    private final AssistantReadTools readTools;
    private final ObjectMapper objectMapper;

    public ExpandRuleEvidenceContextNativeTool(AssistantReadTools readTools, ObjectMapper objectMapper) {
        this.readTools = readTools;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "expand_rule_evidence_context";
    }

    @Override
    public String description() {
        return "Use after search when an excerpt may omit an adjacent condition, list continuation, or exception. "
                + "Expand up to three handles by one or two chunks; never use it first or to roam the active immutable "
                + "rulebook. Neighboring text is candidate context, not an automatic ruling. Read the resulting exact "
                + "pages before deciding, and stop once the missing boundary is visible.";
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
        if (arguments.evidenceIds() == null || arguments.evidenceIds().isEmpty()
                || arguments.evidenceIds().size() > 3
                || arguments.radius() == null || arguments.radius() < 1 || arguments.radius() > 2) {
            throw new IllegalArgumentException("rule evidence context arguments are invalid");
        }
        LinkedHashSet<UUID> evidenceIds = new LinkedHashSet<>();
        for (String value : arguments.evidenceIds()) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("rule evidence context arguments are invalid");
            }
            try {
                evidenceIds.add(UUID.fromString(value));
            } catch (IllegalArgumentException invalidId) {
                throw new IllegalArgumentException("rule evidence context arguments are invalid", invalidId);
            }
        }
        if (evidenceIds.size() != arguments.evidenceIds().size()) {
            throw new IllegalArgumentException("rule evidence context arguments contain duplicates");
        }

        RuleEvidenceContext context = readTools.readRuleEvidenceContext(
                scope.documentVersionId(), Set.copyOf(evidenceIds), arguments.radius());
        List<Map<String, Object>> anchors = context.anchors().stream().map(this::observation).toList();
        List<Map<String, Object>> surrounding = context.surroundingEvidence().stream().map(this::observation).toList();
        Map<String, Object> data = Map.of(
                "requestedAnchorCount", evidenceIds.size(),
                "returnedAnchorCount", anchors.size(),
                "anchors", anchors,
                "surroundingEvidence", surrounding,
                "contextApplicabilityAuthority", false,
                "nextAction", "READ_EXACT_PAGES_AND_CHECK_APPLICABILITY");
        int evidenceCount = anchors.size() + surrounding.size();
        if (anchors.isEmpty()) {
            return ToolObservation.partial("NO_CONTEXT_ANCHOR", data, 0);
        }
        return anchors.size() == evidenceIds.size()
                ? ToolObservation.success("EVIDENCE_CONTEXT_EXPANDED", data, evidenceCount)
                : ToolObservation.partial("EVIDENCE_CONTEXT_PARTIAL", data, evidenceCount);
    }

    private Arguments parse(String argumentsJson) {
        try {
            return objectMapper.readerFor(Arguments.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(argumentsJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("rule evidence context arguments are invalid", exception);
        }
    }

    private Map<String, Object> observation(RuleEvidence evidence) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("evidenceId", evidence.chunkId().toString());
        value.put("sectionType", complete(evidence.sectionType()));
        value.put("heading", complete(evidence.heading()));
        value.put("excerpt", complete(evidence.excerpt()));
        value.put("pageFrom", evidence.pageFrom());
        value.put("pageTo", evidence.pageTo());
        return Map.copyOf(value);
    }

    private String complete(String value) {
        return value == null ? "" : value.strip();
    }

    private record Arguments(List<String> evidenceIds, Integer radius) {}
}
