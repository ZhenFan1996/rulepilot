package com.rulepilot.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.NativeAgentTool;
import com.rulepilot.assistant.NativeVisualEvidence;
import com.rulepilot.assistant.NativeVisualEvidence.VisualPageFact;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class ReadVisualPageFactsNativeTool implements NativeAgentTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "evidenceId": {"type": "string", "description": "An existing active-rulebook evidence handle that binds this visual lookup to the same document and page.", "format": "uuid"},
                "pageNumber": {"type": "integer", "description": "The exact page carried by that evidence handle, never a guessed locator.", "minimum": 1}
              },
              "required": ["evidenceId", "pageNumber"],
              "additionalProperties": false
            }
            """;

    private final NativeVisualEvidence visualEvidence;
    private final ObjectMapper objectMapper;

    public ReadVisualPageFactsNativeTool(NativeVisualEvidence visualEvidence, ObjectMapper objectMapper) {
        this.visualEvidence = visualEvidence;
        this.objectMapper = objectMapper;
    }

    @Override public String name() { return "read_visual_page_facts"; }

    @Override
    public String description() {
        return "Use for a visible icon, label, table, diagram, arrow, or layout after an evidence handle identifies "
                + "the exact page in the active immutable rulebook. Page-local observations can locate printed "
                + "objects but have no independent mechanical-rule authority; confirm a ruling with canonical text. "
                + "Do not use it for non-visual questions or guessed identifiers. Stop when the visible reference is "
                + "located or reported absent.";
    }

    @Override public String inputSchema() { return INPUT_SCHEMA; }
    @Override public String schemaVersion() { return "1"; }
    @Override public Set<Role> allowedRoles() { return Set.of(Role.ANSWER, Role.VISUAL); }

    @Override
    public ToolObservation execute(String argumentsJson, ToolScope scope) {
        Arguments arguments = parse(argumentsJson);
        if (arguments.evidenceId() == null || arguments.pageNumber() < 1) {
            throw new IllegalArgumentException("visual fact arguments are invalid");
        }
        List<VisualPageFact> facts = visualEvidence.readPageFacts(
                scope.documentVersionId(), arguments.evidenceId(), arguments.pageNumber());
        Map<String, Object> data = Map.of(
                "evidenceId", arguments.evidenceId().toString(),
                "pageNumber", arguments.pageNumber(),
                "mechanicalRuleAuthority", false,
                "facts", facts.stream().map(this::fact).toList());
        return facts.isEmpty()
                ? ToolObservation.partial("VISUAL_FACTS_NOT_FOUND", data, 0)
                : ToolObservation.success("VISUAL_FACTS_FOUND", data, facts.size());
    }

    private Map<String, Object> fact(VisualPageFact fact) {
        return Map.of(
                "printedTerms", fact.printedTerms(),
                "literalSummary", fact.literalSummary(),
                "anchors", fact.anchors(),
                "icons", fact.icons());
    }

    private Arguments parse(String json) {
        try {
            return objectMapper.readerFor(Arguments.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("visual fact arguments are invalid", exception);
        }
    }

    private record Arguments(UUID evidenceId, int pageNumber) {}
}
