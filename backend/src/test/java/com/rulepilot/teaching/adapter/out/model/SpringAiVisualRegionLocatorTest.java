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
                        "{\"pageNumber\":1,\"label\":\"board\",\"visibleDescription\":\"中央有一块地图和相邻的标记区。\",\"x\":100,\"y\":100,\"width\":200,\"height\":200,\"supportedClaimRefs\":[\"C1\"]}"))
                .isPresent();
    }

    @Test
    void retains_a_literal_visual_observation_with_the_crop() {
        var parsed = SpringAiVisualRegionLocator.parseModelRegion(
                "{\"pageNumber\":2,\"label\":\"matching icons\",\"visibleDescription\":\"两张卡牌之间以箭头连接，花色图标相同。\",\"x\":100,\"y\":100,\"width\":200,\"height\":200,\"supportedClaimRefs\":[\"C1\"]}");

        assertThat(parsed).isPresent();
        assertThat(parsed.orElseThrow().visibleDescription()).isEqualTo("两张卡牌之间以箭头连接，花色图标相同。");
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

    @Test
    void treats_an_explicit_null_as_a_terminal_no_region_response() {
        assertThat(SpringAiVisualRegionLocator.isExplicitNoRegion(" null ")).isTrue();
        assertThat(SpringAiVisualRegionLocator.isExplicitNoRegion("not valid JSON")).isFalse();
    }

    @Test
    void gives_the_single_retry_a_correction_that_matches_the_rejected_contract() {
        assertThat(SpringAiVisualRegionLocator.retryInstruction(SpringAiVisualRegionLocator.Rejection.UNSUPPORTED_SCOPE))
                .contains("page", "claim");
        assertThat(SpringAiVisualRegionLocator.retryInstruction(SpringAiVisualRegionLocator.Rejection.INVALID_GEOMETRY))
                .contains("x + width", "y + height");
        assertThat(SpringAiVisualRegionLocator.retryInstruction(SpringAiVisualRegionLocator.Rejection.MALFORMED_JSON))
                .contains("JSON");
    }

    @Test
    void clampsARegionThatSlightlyOverrunsTheModelsNormalizedPageBoundary() {
        var normalized = SpringAiVisualRegionLocator.normalizedGeometry(
                new SpringAiVisualRegionLocator.ModelRegion(
                        1, "card detail", "可见卡牌图标", 960, 970, 100, 100, java.util.List.of("C1")));

        assertThat(normalized)
                .extracting(
                        SpringAiVisualRegionLocator.ModelRegion::x,
                        SpringAiVisualRegionLocator.ModelRegion::y,
                        SpringAiVisualRegionLocator.ModelRegion::width,
                        SpringAiVisualRegionLocator.ModelRegion::height)
                .containsExactly(960, 970, 40, 30);
    }
}
