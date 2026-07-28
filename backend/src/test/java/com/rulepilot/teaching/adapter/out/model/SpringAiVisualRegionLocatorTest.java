package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.DocumentPageImages.PageImage;
import com.rulepilot.teaching.VisualRegionLocator.Claim;
import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.VisualRegionLocator.VisualLocationRequest;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import com.rulepilot.teaching.application.VisualRegionCandidateSelector.Candidate;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type;
import org.junit.jupiter.api.Test;

class SpringAiVisualRegionLocatorTest {

    @Test
    void accepts_json_wrapped_in_a_model_code_fence() {
        var parsed = VisualLocatorResponsePolicy.parseModelRegion("""
                ```json
                {"pageNumber":1,"label":"QR code","x":780,"y":472,"width":126,"height":89,"supportedClaimRefs":["C1"]}
                ```
                """);

        assertThat(parsed).isPresent();
        assertThat(parsed.orElseThrow())
                .extracting(
                        VisualLocatorResponsePolicy.ModelRegion::pageNumber,
                        VisualLocatorResponsePolicy.ModelRegion::label,
                        VisualLocatorResponsePolicy.ModelRegion::x,
                        VisualLocatorResponsePolicy.ModelRegion::y)
                .containsExactly(1, "QR code", 780, 472);
    }

    @Test
    void rejects_prose_or_a_null_response() {
        assertThat(VisualLocatorResponsePolicy.parseModelRegion("I cannot find a useful image.")).isEmpty();
        assertThat(VisualLocatorResponsePolicy.parseModelRegion("null")).isEmpty();
    }

    @Test
    void accepts_plain_json_without_a_code_fence() {
        assertThat(VisualLocatorResponsePolicy.parseModelRegion(
                        "{\"pageNumber\":1,\"label\":\"board\",\"visibleDescription\":\"中央有一块地图和相邻的标记区。\",\"x\":100,\"y\":100,\"width\":200,\"height\":200,\"supportedClaimRefs\":[\"C1\"]}"))
                .isPresent();
    }

    @Test
    void retains_a_literal_visual_observation_with_the_crop() {
        var parsed = VisualLocatorResponsePolicy.parseModelRegion(
                "{\"pageNumber\":2,\"label\":\"matching icons\",\"visibleDescription\":\"两张卡牌之间以箭头连接，花色图标相同。\",\"x\":100,\"y\":100,\"width\":200,\"height\":200,\"supportedClaimRefs\":[\"C1\"]}");

        assertThat(parsed).isPresent();
        assertThat(parsed.orElseThrow().visibleDescription()).isEqualTo("两张卡牌之间以箭头连接，花色图标相同。");
    }

    @Test
    void parses_two_distinct_visual_walkthrough_anchors_from_one_model_response() {
        var guide = VisualLocatorResponsePolicy.parseModelGuide("""
                {"regions":[
                  {"pageNumber":2,"label":"行动图标","visibleDescription":"骰子图标旁有向右箭头","x":100,"y":100,"width":60,"height":60,"supportedClaimRefs":["C1"]},
                  {"pageNumber":2,"label":"示例状态","visibleDescription":"棋子位于行动轨道的第三格","x":300,"y":500,"width":240,"height":160,"supportedClaimRefs":["C2"]}
                ]}
                """);

        assertThat(guide).isPresent();
        assertThat(guide.orElseThrow().regions()).extracting(VisualLocatorResponsePolicy.ModelRegion::label)
                .containsExactly("行动图标", "示例状态");
    }

    @Test
    void accepts_a_single_json_object_after_brief_model_prose() {
        assertThat(VisualLocatorResponsePolicy.parseModelRegion("""
                I found a matching rule reference.
                {"pageNumber":1,"label":"board","x":100,"y":100,"width":200,"height":200,"supportedClaimRefs":["C1"]}
                """))
                .isPresent();
    }

    @Test
    void requests_qwen_json_mode_without_enabling_thinking() {
        var options = SpringAiVisualRegionLocator.qwenJsonOptions("qwen3-vl-plus").build();

        assertThat(options.getModel()).isEqualTo("qwen3-vl-plus");
        assertThat(options.getMaxTokens()).isEqualTo(800);
        assertThat(options.getExtraBody()).containsEntry("enable_thinking", false);
        assertThat(options.getResponseFormat().getType()).isEqualTo(Type.JSON_OBJECT);
    }

    @Test
    void gives_qwen_a_compact_exact_step_contract_and_strips_extracted_prose_from_candidates() {
        var candidates = VisualLocatorResponsePolicy.candidatePromptPayload(
                List.of(new Candidate(
                        4,
                        new Rectangle(600, 500, 300, 200),
                        "Cited page 4 visual context: a long extracted paragraph that must not distract vision.")),
                true);

        assertThat(SpringAiVisualRegionLocator.QWEN_SYSTEM)
                .contains("one exact", "width * height", "not merely nearby prose", "supportedClaimRefs");
        assertThat(SpringAiVisualRegionLocator.QWEN_CROP_VERIFIER_SYSTEM)
                .contains("recognise that claim", "need not independently show every procedural word", "different");
        assertThat(candidates).containsExactly(Map.of(
                "pageNumber", 4,
                "hint", "page visual context"));
    }

    @Test
    void treats_an_explicit_null_as_a_terminal_no_region_response() {
        assertThat(VisualLocatorResponsePolicy.isExplicitNoRegion(" null ")).isTrue();
        assertThat(VisualLocatorResponsePolicy.isExplicitNoRegion(" {} ")).isTrue();
        assertThat(VisualLocatorResponsePolicy.isExplicitNoRegion("not valid JSON")).isFalse();
    }

    @Test
    void exposes_the_reason_when_a_visual_response_is_not_parseable() {
        assertThat(VisualLocatorResponsePolicy.diagnosticFor(
                        VisualLocatorResponsePolicy.Rejection.MALFORMED_JSON))
                .isEqualTo(com.rulepilot.teaching.VisualRegionLocator.Diagnostic.MALFORMED_RESPONSE);
        assertThat(VisualLocatorResponsePolicy.diagnosticFor(
                        VisualLocatorResponsePolicy.Rejection.EXPLICIT_NO_REGION))
                .isEqualTo(com.rulepilot.teaching.VisualRegionLocator.Diagnostic.EXPLICIT_NO_REGION);
        assertThat(VisualLocatorResponsePolicy.diagnosticFor(
                        VisualLocatorResponsePolicy.Rejection.NON_CHINESE_OBSERVATION))
                .isEqualTo(com.rulepilot.teaching.VisualRegionLocator.Diagnostic.NON_CHINESE_OBSERVATION);
    }

    @Test
    void gives_the_single_retry_a_correction_that_matches_the_rejected_contract() {
        assertThat(VisualLocatorResponsePolicy.retryInstruction(VisualLocatorResponsePolicy.Rejection.UNSUPPORTED_SCOPE))
                .contains("page", "claim");
        assertThat(VisualLocatorResponsePolicy.retryInstruction(VisualLocatorResponsePolicy.Rejection.INVALID_GEOMETRY))
                .contains("x + width", "y + height");
        assertThat(VisualLocatorResponsePolicy.retryInstruction(VisualLocatorResponsePolicy.Rejection.MALFORMED_JSON))
                .contains("JSON");
        assertThat(VisualLocatorResponsePolicy.retryInstruction(
                        VisualLocatorResponsePolicy.Rejection.NON_CHINESE_OBSERVATION))
                .contains("Simplified Chinese", "crop itself visibly contains");
    }

    @Test
    void recognizesWhetherAVisionObservationUsesChinese() {
        assertThat(VisualLocatorResponsePolicy.containsChinese("骰子行动区")).isTrue();
        assertThat(VisualLocatorResponsePolicy.containsChinese("Dice Actions")).isFalse();
    }

    @Test
    void clampsARegionThatSlightlyOverrunsTheModelsNormalizedPageBoundary() {
        var normalized = VisualCropAcceptancePolicy.normalizedGeometry(
                new VisualLocatorResponsePolicy.ModelRegion(
                        1, "card detail", "可见卡牌图标", 960, 970, 100, 100, java.util.List.of("C1")));

        assertThat(normalized)
                .extracting(
                        VisualLocatorResponsePolicy.ModelRegion::x,
                        VisualLocatorResponsePolicy.ModelRegion::y,
                        VisualLocatorResponsePolicy.ModelRegion::width,
                        VisualLocatorResponsePolicy.ModelRegion::height)
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

        assertThat(VisualCropAcceptancePolicy.requiresTighterIconViewport(tallLegend)).isTrue();
        assertThat(VisualCropAcceptancePolicy.requiresTighterIconViewport(workedDiagram)).isFalse();
        assertThat(VisualCropAcceptancePolicy.requiresTighterIconViewport(verticalCard)).isFalse();
        assertThat(VisualCropAcceptancePolicy.withoutOversizedIconLegends(List.of(tallLegend, workedDiagram)))
                .containsExactly(workedDiagram);
        assertThat(VisualCropAcceptancePolicy.tightIconViewportInstruction())
                .contains("tighter rectangle", "numbered prose");
    }

    @Test
    void requests_a_tighter_retry_when_a_crop_is_nearly_a_full_page_even_if_it_is_not_a_tall_icon_legend() {
        var broadComponentOverview = new com.rulepilot.teaching.VisualRegionLocator.LocatedRegion(
                3, "组件图示", "桌面上的多个游戏组件与对应图标", 50, 60, 900, 850, List.of(UUID.randomUUID()));
        var compactWorkedState = new com.rulepilot.teaching.VisualRegionLocator.LocatedRegion(
                3, "放置示例", "一块栖息地板块与相邻的野生动物标记", 100, 420, 400, 300, List.of(UUID.randomUUID()));

        assertThat(VisualCropAcceptancePolicy.requiresTighterReaderViewport(broadComponentOverview)).isTrue();
        assertThat(VisualCropAcceptancePolicy.requiresTighterReaderViewport(compactWorkedState)).isFalse();
        assertThat(VisualCropAcceptancePolicy.withoutOversizedReaderViewports(
                        List.of(broadComponentOverview, compactWorkedState)))
                .containsExactly(compactWorkedState);
        assertThat(VisualCropAcceptancePolicy.tightReaderViewportInstruction())
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

        assertThat(VisualCropAcceptancePolicy.requiresTighterScoreExampleViewport(stackedScoreExamples)).isTrue();
        assertThat(VisualCropAcceptancePolicy.requiresTighterScoreExampleViewport(portraitCard)).isFalse();
        assertThat(VisualCropAcceptancePolicy.requiresTighterScoreExampleViewport(compactScoreRow)).isFalse();
        assertThat(VisualCropAcceptancePolicy.withoutOversizedReaderViewports(
                        List.of(stackedScoreExamples, compactScoreRow, portraitCard)))
                .containsExactly(compactScoreRow, portraitCard);
        assertThat(VisualCropAcceptancePolicy.tightReaderViewportInstruction())
                .contains("neighbouring score examples", "another animal's examples");
    }

    @Test
    void rebinds_a_visual_crop_to_the_matching_source_page_when_the_model_numbers_a_neighbouring_claim() {
        Claim overview = new Claim(UUID.randomUUID(), "游戏目标", List.of(2));
        Claim cardAnatomy = new Claim(UUID.randomUUID(), "文物卡的构成", List.of(3));

        List<Claim> rebound = VisualCropAcceptancePolicy.pageScopedClaims(
                3, List.of(overview), List.of(overview, cardAnatomy));

        assertThat(rebound).containsExactly(cardAnatomy);
    }

    @Test
    void rejects_an_ambiguous_page_fallback_instead_of_binding_a_crop_to_the_first_step() {
        Claim overview = new Claim(UUID.randomUUID(), "游戏目标", List.of(2), 1);
        Claim playerBoard = new Claim(UUID.randomUUID(), "发给每位玩家玩家板", List.of(3), 2);
        Claim supply = new Claim(UUID.randomUUID(), "创建公共供应区", List.of(3), 3);

        List<Claim> rebound = VisualCropAcceptancePolicy.pageScopedClaims(
                3, List.of(overview), List.of(overview, playerBoard, supply));

        assertThat(rebound).isEmpty();
    }

    @Test
    void binds_a_crop_without_a_claim_reference_when_one_step_unambiguously_cites_its_page() {
        Claim setup = new Claim(UUID.randomUUID(), "发给每位玩家玩家板", List.of(3), 2);

        List<Claim> rebound = VisualCropAcceptancePolicy.pageScopedClaims(3, List.of(), List.of(setup));

        assertThat(rebound).containsExactly(setup);
    }

    @Test
    void derives_a_page_scoped_exact_step_region_from_a_cataloged_visual_anchor() {
        Claim setup = new Claim(UUID.randomUUID(), "步骤 2（摆放资源）：把资源放到公共供应区。", List.of(3), 2);
        VisualAnchor anchor = new VisualAnchor(
                "component group", "公共供应区资源", "四种彩色资源标记排在公共供应区旁。", 120, 330, 280, 180);
        var request = new VisualLocationRequest(
                "开局设置",
                List.of(setup),
                List.of(new Candidate(
                        3,
                        new Rectangle(120, 330, 280, 180),
                        "Cataloged visual anchor (verify against the attached image): component group — 公共供应区资源。",
                        anchor)),
                List.of(new com.rulepilot.teaching.VisualRegionLocator.PageImage(3, "image/png", cardGroupImage())));

        assertThat(VisualCropAcceptancePolicy.catalogedAnchorRegion(request, request.candidates().getFirst()))
                .contains(new LocatedRegion(
                        3,
                        "公共供应区资源",
                        "四种彩色资源标记排在公共供应区旁。",
                        120,
                        330,
                        280,
                        180,
                        List.of(setup.evidenceId()),
                        List.of(2)));
    }

    @Test
    void never_derives_a_cataloged_anchor_for_a_page_the_exact_step_does_not_cite() {
        Claim setup = new Claim(UUID.randomUUID(), "步骤 2（摆放资源）：把资源放到公共供应区。", List.of(3), 2);
        VisualAnchor anchor = new VisualAnchor(
                "component group", "公共供应区资源", "四种彩色资源标记排在公共供应区旁。", 120, 330, 280, 180);
        var request = new VisualLocationRequest(
                "开局设置",
                List.of(setup),
                List.of(new Candidate(
                        4,
                        new Rectangle(120, 330, 280, 180),
                        "Cataloged visual anchor (verify against the attached image): component group — 公共供应区资源。",
                        anchor)),
                List.of(new com.rulepilot.teaching.VisualRegionLocator.PageImage(4, "image/png", cardGroupImage())));

        assertThat(VisualCropAcceptancePolicy.catalogedAnchorRegion(request, request.candidates().getFirst())).isEmpty();
    }

    @Test
    void parses_only_offered_exact_crop_references_from_the_second_visual_check() {
        assertThat(VisualLocatorResponsePolicy.acceptedCropReferences(
                        "{\"acceptedCropRefs\":[\"R1\"]}", Set.of("R1", "R2")))
                .contains(Set.of("R1"));
        assertThat(VisualLocatorResponsePolicy.acceptedCropReferences(
                        "{\"acceptedCropRefs\":[\"R3\"]}", Set.of("R1", "R2")))
                .isEmpty();
    }

    @Test
    void offers_the_crop_verifier_only_the_exact_step_that_the_crop_claims_to_support() {
        Claim namedCard = new Claim(UUID.randomUUID(), "步骤 1（打出统治卡）：打出统治卡。", List.of(21), 1);
        Claim alliance = new Claim(UUID.randomUUID(), "步骤 2（结盟）：与其他玩家结盟。", List.of(21), 2);
        var request = new VisualLocationRequest(
                "结束条件", List.of(namedCard, alliance),
                List.of(new Candidate(21, new Rectangle(0, 0, 1_000, 1_000), "Cited page 21 visual context")),
                List.of(new com.rulepilot.teaching.VisualRegionLocator.PageImage(21, "image/png", cardGroupImage())));
        var region = new LocatedRegion(
                21, "流浪者结盟步骤", "狐狸与松鼠角色站在一起", 100, 100, 300, 300,
                List.of(namedCard.evidenceId()), List.of(1));

        assertThat(VisualCropAcceptancePolicy.claimsForExactCrop(request, region))
                .containsExactly(namedCard);
    }

    @Test
    void reserves_qwen_crop_review_for_rules_where_a_neighbouring_visual_would_change_the_meaning() {
        Claim supply = new Claim(
                UUID.randomUUID(),
                "步骤 4（建立物品供应）：摆放物品标记。请注意，完成设置后通过得分决定胜负。",
                List.of(4),
                4);
        Claim dominance = new Claim(UUID.randomUUID(), "步骤 1（打出统治卡）：打出统治卡。", List.of(21), 1);
        Claim scoring = new Claim(UUID.randomUUID(), "步骤 2（计分）：比较分数。", List.of(11), 2);

        assertThat(VisualExactCropReviewPolicy.qwenNeedsExactCropReview(List.of(supply))).isFalse();
        assertThat(VisualExactCropReviewPolicy.qwenNeedsExactCropReview(List.of(dominance))).isTrue();
        assertThat(VisualExactCropReviewPolicy.qwenNeedsExactCropReview(List.of(scoring))).isTrue();
    }

    @Test
    void fallback_signal_rejects_a_label_only_crop_but_keeps_a_card_group_when_the_second_visual_check_is_unavailable() {
        LocatedRegion crop = new LocatedRegion(
                1, "抽牌区", "三组卡牌", 0, 0, 1_000, 1_000, List.of(UUID.randomUUID()));

        assertThat(VisualCropAcceptancePolicy.hasEnoughRenderedVisualSignal(request(labelOnlyImage()), crop)).isFalse();
        assertThat(VisualCropAcceptancePolicy.hasEnoughRenderedVisualSignal(request(cardGroupImage()), crop)).isTrue();
    }

    private VisualLocationRequest request(byte[] page) {
        UUID evidence = UUID.randomUUID();
        return new VisualLocationRequest(
                "选择行动",
                List.of(new Claim(evidence, "选择一组卡牌", List.of(1), 1)),
                List.of(new Candidate(1, new Rectangle(0, 0, 1_000, 1_000), "Cataloged visual anchor: labeled legend")),
                List.of(new com.rulepilot.teaching.VisualRegionLocator.PageImage(1, "image/png", page)));
    }

    private byte[] labelOnlyImage() {
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(238, 250, 220));
        graphics.fillRect(0, 0, 400, 400);
        graphics.setColor(new Color(80, 160, 50));
        graphics.fillRoundRect(50, 30, 220, 80, 18, 18);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(70, 55, 160, 16);
        graphics.dispose();
        return png(image);
    }

    private byte[] cardGroupImage() {
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(238, 250, 220));
        graphics.fillRect(0, 0, 400, 400);
        for (int index = 0; index < 3; index++) {
            graphics.setColor(List.of(new Color(65, 150, 75), new Color(220, 115, 55), new Color(200, 75, 70)).get(index));
            graphics.fillRoundRect(35 + index * 120, 65, 95, 250, 12, 12);
            graphics.setColor(Color.WHITE);
            graphics.fillOval(60 + index * 120, 145, 45, 45);
        }
        graphics.dispose();
        return png(image);
    }

    private byte[] png(BufferedImage image) {
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
