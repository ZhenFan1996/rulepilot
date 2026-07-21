package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type;
import org.junit.jupiter.api.Test;

class SpringAiVisualRegionLocatorTest {

    @Test
    void accepts_json_wrapped_in_a_model_code_fence() {
        var parsed = SpringAiVisualRegionLocator.parseModelRegion("""
                ```json
                {"pageNumber":1,"label":"QR code","x":780,"y":472,"width":126,"height":89,"supportedClaimRefs":["C1"]}
                ```
                """);

        assertThat(parsed).isPresent();
        assertThat(parsed.orElseThrow())
                .extracting(
                        SpringAiVisualRegionLocator.ModelRegion::pageNumber,
                        SpringAiVisualRegionLocator.ModelRegion::label,
                        SpringAiVisualRegionLocator.ModelRegion::x,
                        SpringAiVisualRegionLocator.ModelRegion::y)
                .containsExactly(1, "QR code", 780, 472);
    }

    @Test
    void rejects_prose_or_a_null_response() {
        assertThat(SpringAiVisualRegionLocator.parseModelRegion("I cannot find a useful image.")).isEmpty();
        assertThat(SpringAiVisualRegionLocator.parseModelRegion("null")).isEmpty();
    }

    @Test
    void accepts_plain_json_without_a_code_fence() {
        assertThat(SpringAiVisualRegionLocator.parseModelRegion(
                        "{\"pageNumber\":1,\"label\":\"board\",\"x\":100,\"y\":100,\"width\":200,\"height\":200,\"supportedClaimRefs\":[\"C1\"]}"))
                .isPresent();
    }

    @Test
    void accepts_a_single_json_object_after_brief_model_prose() {
        assertThat(SpringAiVisualRegionLocator.parseModelRegion("""
                I found a matching rule reference.
                {"pageNumber":1,"label":"board","x":100,"y":100,"width":200,"height":200,"supportedClaimRefs":["C1"]}
                """))
                .isPresent();
    }

    @Test
    void requests_qwen_json_mode_without_enabling_thinking() {
        var options = SpringAiVisualRegionLocator.qwenJsonOptions().build();

        assertThat(options.getExtraBody()).containsEntry("enable_thinking", false);
        assertThat(options.getResponseFormat().getType()).isEqualTo(Type.JSON_OBJECT);
    }
}
