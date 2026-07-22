package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.VisualRegionLocator.Claim;
import java.util.List;
import java.util.UUID;
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
    void parses_two_distinct_visual_walkthrough_anchors_from_one_model_response() {
        var guide = SpringAiVisualRegionLocator.parseModelGuide("""
                {"regions":[
                  {"pageNumber":2,"label":"行动图标","visibleDescription":"骰子图标旁有向右箭头","x":100,"y":100,"width":60,"height":60,"supportedClaimRefs":["C1"]},
                  {"pageNumber":2,"label":"示例状态","visibleDescription":"棋子位于行动轨道的第三格","x":300,"y":500,"width":240,"height":160,"supportedClaimRefs":["C2"]}
                ]}
                """);

        assertThat(guide).isPresent();
        assertThat(guide.orElseThrow().regions()).extracting(SpringAiVisualRegionLocator.ModelRegion::label)
                .containsExactly("行动图标", "示例状态");
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
        var options = SpringAiVisualRegionLocator.qwenJsonOptions("qwen3-vl-plus").build();

        assertThat(options.getModel()).isEqualTo("qwen3-vl-plus");
        assertThat(options.getExtraBody()).containsEntry("enable_thinking", false);
        assertThat(options.getResponseFormat().getType()).isEqualTo(Type.JSON_OBJECT);
    }

    @Test
    void treats_an_explicit_null_as_a_terminal_no_region_response() {
        assertThat(SpringAiVisualRegionLocator.isExplicitNoRegion(" null ")).isTrue();
        assertThat(SpringAiVisualRegionLocator.isExplicitNoRegion(" {} ")).isTrue();
        assertThat(SpringAiVisualRegionLocator.isExplicitNoRegion("not valid JSON")).isFalse();
    }

    @Test
    void exposes_the_reason_when_a_visual_response_is_not_parseable() {
        assertThat(SpringAiVisualRegionLocator.diagnosticFor(
                        SpringAiVisualRegionLocator.Rejection.MALFORMED_JSON))
                .isEqualTo(com.rulepilot.teaching.VisualRegionLocator.Diagnostic.MALFORMED_RESPONSE);
        assertThat(SpringAiVisualRegionLocator.diagnosticFor(
                        SpringAiVisualRegionLocator.Rejection.EXPLICIT_NO_REGION))
                .isEqualTo(com.rulepilot.teaching.VisualRegionLocator.Diagnostic.EXPLICIT_NO_REGION);
        assertThat(SpringAiVisualRegionLocator.diagnosticFor(
                        SpringAiVisualRegionLocator.Rejection.NON_CHINESE_OBSERVATION))
                .isEqualTo(com.rulepilot.teaching.VisualRegionLocator.Diagnostic.NON_CHINESE_OBSERVATION);
    }

    @Test
    void gives_the_single_retry_a_correction_that_matches_the_rejected_contract() {
        assertThat(SpringAiVisualRegionLocator.retryInstruction(SpringAiVisualRegionLocator.Rejection.UNSUPPORTED_SCOPE))
                .contains("page", "claim");
        assertThat(SpringAiVisualRegionLocator.retryInstruction(SpringAiVisualRegionLocator.Rejection.INVALID_GEOMETRY))
                .contains("x + width", "y + height");
        assertThat(SpringAiVisualRegionLocator.retryInstruction(SpringAiVisualRegionLocator.Rejection.MALFORMED_JSON))
                .contains("JSON");
        assertThat(SpringAiVisualRegionLocator.retryInstruction(
                        SpringAiVisualRegionLocator.Rejection.NON_CHINESE_OBSERVATION))
                .contains("Simplified Chinese", "crop itself visibly contains");
    }

    @Test
    void recognizesWhetherAVisionObservationUsesChinese() {
        assertThat(SpringAiVisualRegionLocator.containsChinese("骰子行动区")).isTrue();
        assertThat(SpringAiVisualRegionLocator.containsChinese("Dice Actions")).isFalse();
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

    @Test
    void requests_a_tighter_retry_for_a_tall_icon_legend_but_not_for_a_worked_diagram_or_vertical_card() {
        var tallLegend = new com.rulepilot.teaching.VisualRegionLocator.LocatedRegion(
                2, "资源图标图例", "五种资源名称下方分别是彩色立方体图标", 45, 510, 300, 483, List.of(UUID.randomUUID()));
        var workedDiagram = new com.rulepilot.teaching.VisualRegionLocator.LocatedRegion(
                4, "建造示例", "建筑卡、资源方块和箭头展示建造前后状态", 350, 420, 650, 580, List.of(UUID.randomUUID()));
        var verticalCard = new com.rulepilot.teaching.VisualRegionLocator.LocatedRegion(
                3, "纪念碑卡牌解剖图", "一张竖版卡牌标出标题、插画、资源图案和能力区域", 650, 308, 350, 692, List.of(UUID.randomUUID()));

        assertThat(SpringAiVisualRegionLocator.requiresTighterIconViewport(tallLegend)).isTrue();
        assertThat(SpringAiVisualRegionLocator.requiresTighterIconViewport(workedDiagram)).isFalse();
        assertThat(SpringAiVisualRegionLocator.requiresTighterIconViewport(verticalCard)).isFalse();
        assertThat(SpringAiVisualRegionLocator.withoutOversizedIconLegends(List.of(tallLegend, workedDiagram)))
                .containsExactly(workedDiagram);
        assertThat(SpringAiVisualRegionLocator.tightIconViewportInstruction())
                .contains("tighter rectangle", "numbered prose");
    }

    @Test
    void requests_a_tighter_retry_when_a_crop_is_nearly_a_full_page_even_if_it_is_not_a_tall_icon_legend() {
        var broadComponentOverview = new com.rulepilot.teaching.VisualRegionLocator.LocatedRegion(
                3, "组件图示", "桌面上的多个游戏组件与对应图标", 50, 60, 900, 850, List.of(UUID.randomUUID()));
        var compactWorkedState = new com.rulepilot.teaching.VisualRegionLocator.LocatedRegion(
                3, "放置示例", "一块栖息地板块与相邻的野生动物标记", 100, 420, 400, 300, List.of(UUID.randomUUID()));

        assertThat(SpringAiVisualRegionLocator.requiresTighterReaderViewport(broadComponentOverview)).isTrue();
        assertThat(SpringAiVisualRegionLocator.requiresTighterReaderViewport(compactWorkedState)).isFalse();
        assertThat(SpringAiVisualRegionLocator.withoutOversizedReaderViewports(
                        List.of(broadComponentOverview, compactWorkedState)))
                .containsExactly(compactWorkedState);
        assertThat(SpringAiVisualRegionLocator.tightReaderViewportInstruction())
                .contains("too much", "compact rectangle");
    }

    @Test
    void requests_a_tighter_retry_for_a_tall_score_example_column_but_keeps_a_compact_score_row_and_portrait_component_card() {
        var stackedScoreExamples = new com.rulepilot.teaching.VisualRegionLocator.LocatedRegion(
                11,
                "鲑鱼计分卡示例",
                "四个鲑鱼计分卡示例，旁边标有数字和得分说明",
                45,
                460,
                260,
                300,
                List.of(UUID.randomUUID()));
        var portraitCard = new com.rulepilot.teaching.VisualRegionLocator.LocatedRegion(
                3,
                "纪念碑卡牌解剖图",
                "一张竖版卡牌标出标题、插画、资源图案和能力区域",
                650,
                308,
                350,
                692,
                List.of(UUID.randomUUID()));
        var compactScoreRow = new com.rulepilot.teaching.VisualRegionLocator.LocatedRegion(
                11,
                "鲑鱼计分卡示例",
                "四张鲑鱼计分卡的粉色图标与相邻得分说明",
                25,
                510,
                300,
                180,
                List.of(UUID.randomUUID()));

        assertThat(SpringAiVisualRegionLocator.requiresTighterScoreExampleViewport(stackedScoreExamples)).isTrue();
        assertThat(SpringAiVisualRegionLocator.requiresTighterScoreExampleViewport(portraitCard)).isFalse();
        assertThat(SpringAiVisualRegionLocator.requiresTighterScoreExampleViewport(compactScoreRow)).isFalse();
        assertThat(SpringAiVisualRegionLocator.withoutOversizedReaderViewports(
                        List.of(stackedScoreExamples, compactScoreRow, portraitCard)))
                .containsExactly(compactScoreRow, portraitCard);
        assertThat(SpringAiVisualRegionLocator.tightReaderViewportInstruction())
                .contains("neighbouring score examples", "another animal's examples");
    }

    @Test
    void rebinds_a_visual_crop_to_the_matching_source_page_when_the_model_numbers_a_neighbouring_claim() {
        Claim overview = new Claim(UUID.randomUUID(), "游戏目标", List.of(2));
        Claim cardAnatomy = new Claim(UUID.randomUUID(), "文物卡的构成", List.of(3));

        List<Claim> rebound = SpringAiVisualRegionLocator.pageScopedClaims(
                3, List.of(overview), List.of(overview, cardAnatomy));

        assertThat(rebound).containsExactly(cardAnatomy);
    }

    @Test
    void rejects_an_ambiguous_page_fallback_instead_of_binding_a_crop_to_the_first_step() {
        Claim overview = new Claim(UUID.randomUUID(), "游戏目标", List.of(2), 1);
        Claim playerBoard = new Claim(UUID.randomUUID(), "发给每位玩家玩家板", List.of(3), 2);
        Claim supply = new Claim(UUID.randomUUID(), "创建公共供应区", List.of(3), 3);

        List<Claim> rebound = SpringAiVisualRegionLocator.pageScopedClaims(
                3, List.of(overview), List.of(overview, playerBoard, supply));

        assertThat(rebound).isEmpty();
    }
}
