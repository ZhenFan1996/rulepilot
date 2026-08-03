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
public class CropRulePageImageNativeTool implements NativeAgentTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "evidenceId": {"type": "string", "format": "uuid"},
                "pageNumber": {"type": "integer", "minimum": 1},
                "x": {"type": "integer", "minimum": 0, "maximum": 988},
                "y": {"type": "integer", "minimum": 0, "maximum": 988},
                "width": {"type": "integer", "minimum": 12, "maximum": 1000},
                "height": {"type": "integer", "minimum": 12, "maximum": 1000}
              },
              "required": ["evidenceId", "pageNumber", "x", "y", "width", "height"],
              "additionalProperties": false
            }
            """;

    private final NativeVisualEvidence visualEvidence;
    private final ObjectMapper objectMapper;

    public CropRulePageImageNativeTool(NativeVisualEvidence visualEvidence, ObjectMapper objectMapper) {
        this.visualEvidence = visualEvidence;
        this.objectMapper = objectMapper;
    }

    @Override public String name() { return "crop_rule_page_image"; }

    @Override
    public String description() {
        return "Crop one compact region from a rendered page tied to a cited evidence handle. Use it to verify visible objects or layout only; cited text controls rule effects.";
    }

    @Override public String inputSchema() { return INPUT_SCHEMA; }
    @Override public String schemaVersion() { return "1"; }
    @Override public Set<Role> allowedRoles() { return Set.of(Role.VISUAL); }

    @Override
    public ToolObservation execute(String argumentsJson, ToolScope scope) {
        Arguments arguments = parse(argumentsJson);
        validate(arguments);
        Rectangle rectangle = normalize(arguments);
        return visualEvidence.cropPage(
                        scope.documentVersionId(), arguments.evidenceId(), arguments.pageNumber(),
                        rectangle.x(), rectangle.y(), rectangle.width(), rectangle.height())
                .map(crop -> new ToolObservation(
                        ObservationStatus.SUCCESS,
                        "PAGE_CROP_FOUND",
                        Map.of(
                                "evidenceId", crop.evidenceId().toString(),
                                "pageNumber", crop.pageNumber(),
                                "rectangle", Map.of(
                                        "x", crop.x(), "y", crop.y(),
                                        "width", crop.width(), "height", crop.height()),
                                "nextAction", "RETURN_FINAL_JSON_WITH_THIS_EXACT_RECTANGLE",
                                "mechanicalRuleAuthority", false),
                        1,
                        List.of(new ToolMedia(
                                crop.mediaType(), crop.content(), "Rulebook page crop " + crop.pageNumber(),
                                crop.pixelWidth(), crop.pixelHeight()))))
                .orElseGet(() -> ToolObservation.partial(
                        "PAGE_CROP_NOT_FOUND",
                        Map.of("evidenceId", arguments.evidenceId().toString(), "pageNumber", arguments.pageNumber()),
                        0));
    }

    private Arguments parse(String json) {
        try {
            return objectMapper.readerFor(Arguments.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("visual crop arguments are invalid", exception);
        }
    }

    private void validate(Arguments value) {
        if (value.evidenceId() == null || value.pageNumber() < 1
                || value.width() < 12 || value.height() < 12) {
            throw new IllegalArgumentException("visual crop arguments are invalid");
        }
    }

    private Rectangle normalize(Arguments value) {
        int x = Math.max(0, Math.min(988, value.x()));
        int y = Math.max(0, Math.min(988, value.y()));
        int width = Math.max(12, Math.min(value.width(), 1_000 - x));
        int height = Math.max(12, Math.min(value.height(), 1_000 - y));
        return new Rectangle(x, y, width, height);
    }

    private record Arguments(UUID evidenceId, int pageNumber, int x, int y, int width, int height) {}

    private record Rectangle(int x, int y, int width, int height) {}
}
