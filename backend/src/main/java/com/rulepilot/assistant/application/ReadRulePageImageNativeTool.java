package com.rulepilot.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.NativeAgentTool;
import com.rulepilot.assistant.NativeVisualEvidence;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class ReadRulePageImageNativeTool implements NativeAgentTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "evidenceId": {"type": "string", "format": "uuid"},
                "pageNumber": {"type": "integer", "minimum": 1}
              },
              "required": ["evidenceId", "pageNumber"],
              "additionalProperties": true
            }
            """;

    private final NativeVisualEvidence visualEvidence;
    private final ObjectMapper objectMapper;

    public ReadRulePageImageNativeTool(NativeVisualEvidence visualEvidence, ObjectMapper objectMapper) {
        this.visualEvidence = visualEvidence;
        this.objectMapper = objectMapper;
    }

    @Override public String name() { return "read_rule_page_image"; }

    @Override
    public String description() {
        return "Read one rendered page only when its page number belongs to a cited evidence handle from the active rulebook. The image proves literal appearance, not a rule effect.";
    }

    @Override public String inputSchema() { return INPUT_SCHEMA; }
    @Override public String schemaVersion() { return "2"; }
    @Override public Set<Role> allowedRoles() { return Set.of(Role.ANSWER, Role.VISUAL); }

    @Override
    public ToolObservation execute(String argumentsJson, ToolScope scope) {
        Arguments arguments = parse(argumentsJson);
        if (arguments.evidenceId() == null) {
            throw new IllegalArgumentException("evidenceId must be an active-rulebook UUID");
        }
        if (arguments.pageNumber() < 1) {
            throw new IllegalArgumentException("pageNumber must be a positive exact page");
        }
        return visualEvidence.readPage(
                        scope.documentVersionId(), arguments.evidenceId(), arguments.pageNumber())
                .map(page -> new ToolObservation(
                        ObservationStatus.SUCCESS,
                        "PAGE_IMAGE_FOUND",
                        Map.of(
                                "evidenceId", page.evidenceId().toString(),
                                "pageNumber", page.pageNumber(),
                                "width", page.width(),
                                "height", page.height(),
                                "mechanicalRuleAuthority", false),
                        1,
                        List.of(new ToolMedia(
                                page.mediaType(), page.content(), "Rulebook page " + page.pageNumber(),
                                page.width(), page.height()))))
                .orElseGet(() -> ToolObservation.partial(
                        "PAGE_IMAGE_NOT_FOUND",
                        Map.of("evidenceId", arguments.evidenceId().toString(), "pageNumber", arguments.pageNumber()),
                        0));
    }

    private Arguments parse(String json) {
        try {
            return objectMapper.readerFor(Arguments.class)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("visual page arguments JSON could not be decoded", exception);
        }
    }

    private record Arguments(UUID evidenceId, int pageNumber) {}
}
