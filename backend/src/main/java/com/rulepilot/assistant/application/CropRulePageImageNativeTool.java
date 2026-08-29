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
                "x": {"type": "integer", "minimum": 0, "maximum": 980},
                "y": {"type": "integer", "minimum": 0, "maximum": 980},
                "width": {"type": "integer", "minimum": 20, "maximum": 1000},
                "height": {"type": "integer", "minimum": 20, "maximum": 1000}
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
    @Override public String schemaVersion() { return "2"; }
    @Override public Set<Role> allowedRoles() { return Set.of(Role.VISUAL); }

    @Override
    public ToolObservation execute(String argumentsJson, ToolScope scope) {
        Arguments arguments = parse(argumentsJson);
        validate(arguments);
        Rectangle rectangle = new Rectangle(
                arguments.x(), arguments.y(), arguments.width(), arguments.height());
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
            throw new IllegalArgumentException("visual crop arguments JSON could not be decoded", exception);
        }
    }

    private void validate(Arguments value) {
        if (value.evidenceId() == null) {
            throw new IllegalArgumentException("evidenceId must be an active-rulebook UUID");
        }
        if (value.pageNumber() < 1) {
            throw new IllegalArgumentException("pageNumber must be a positive exact page");
        }
        if (value.x() < 0 || value.x() > 980 || value.y() < 0 || value.y() > 980) {
            throw new IllegalArgumentException("x and y must use the advertised normalized page coordinates");
        }
        if (value.width() < 20 || value.width() > 1_000
                || value.height() < 20 || value.height() > 1_000) {
            throw new IllegalArgumentException("width and height must use the advertised normalized page size");
        }
        if ((long) value.x() + value.width() > 1_000
                || (long) value.y() + value.height() > 1_000) {
            throw new IllegalArgumentException("the crop rectangle must remain inside the normalized page");
        }
    }

    private record Arguments(UUID evidenceId, int pageNumber, int x, int y, int width, int height) {}

    private record Rectangle(int x, int y, int width, int height) {}
}
