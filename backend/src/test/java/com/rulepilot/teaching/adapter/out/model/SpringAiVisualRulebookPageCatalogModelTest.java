package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.CatalogDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.CatalogRequest;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconCropDecision;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconLocation;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class SpringAiVisualRulebookPageCatalogModelTest {

    @Test
    void cropPublicationGateKeepsCompleteCentralSymbolsWithoutTrustingNeighborFragments() throws IOException {
        String prompt = new ClassPathResource("prompts/visual-icon-crop-review-v3-system.txt")
                .getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        assertThat(prompt)
                .contains(
                        "single compact graphic nearest the crop center",
                        "separated from all four crop edges by visible background",
                        "tiny clipped edge of a different neighboring symbol may be ignored",
                        "isolated stylized resource cube is a valid pictogram",
                        "grid or board with multiple cells");
    }

    @Test
    void disablesSamplingForReplayableQwenVisualCatalogDecisions() {
        var options = SpringAiVisualRulebookPageCatalogModel.qwenJsonOptions("qwen3-vl-235b-a22b-instruct", 1_000)
                .build();

        assertThat(options.getModel()).isEqualTo("qwen3-vl-235b-a22b-instruct");
        assertThat(options.getTemperature()).isEqualTo(0.0);
        assertThat(options.getMaxTokens()).isEqualTo(1_000);
        assertThat(options.getExtraBody()).containsEntry("enable_thinking", false);
    }

    @Test
    void parsesQwenJsonContentWithoutDependingOnNativeStructuredOutput() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseCatalog("""
                ```json
                {"pages":[{"pageNumber":14,"printedTerms":"KODORA",
                "factualSummary":"红色图标与图例中的胜利点相同。","keywords":["KODORA","victory point"]}]}
                ```
                """);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.pageNumber()).isEqualTo(14);
            assertThat(page.factualSummary()).contains("胜利点");
        });
    }

    @Test
    void acceptsQwenTermAndSummaryArraysWithoutDiscardingTheVisualFacts() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseCatalog("""
                {"pages":[{"pageNumber":7,
                "printedTerms":["power tokens","victory point token"],
                "factualSummary":["黄色图标表示能量标记。","红色图标表示胜利点。"],
                "keywords":["power tokens","victory point token"],
                "visualAnchors":[{"kind":"icon legend","label":"power tokens",
                "visibleDescription":"黄色圆形图标与 power tokens 标签相邻。",
                "x":120,"y":280,"width":240,"height":140}]}]}
                """);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.printedTerms()).isEqualTo("power tokens; victory point token");
            assertThat(page.factualSummary()).contains("能量标记", "胜利点");
            assertThat(page.visualAnchors()).singleElement().satisfies(anchor -> {
                assertThat(anchor.label()).isEqualTo("power tokens");
                assertThat(anchor.x()).isEqualTo(120);
            });
        });
    }

    @Test
    void parses_a_complete_icon_inventory_without_treating_unexplained_icons_as_rules() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseCatalog("""
                {"pages":[{"pageNumber":4,"printedTerms":["Resource icons"],"factualSummary":[],
                "keywords":["resource"],
                "iconOccurrences":[
                  {"groupKey":"wood","name":"Wood","visualDescription":"棕色木块轮廓。",
                   "meaningStatus":"EXPLICIT","explanation":"表示一份木材。","evidenceText":"Wood resource",
                   "x":100,"y":240,"width":42,"height":42},
                  {"groupKey":"blue circle wave","name":"蓝色波纹圆标","visualDescription":"蓝色圆形内有波纹。",
                   "meaningStatus":"UNEXPLAINED","explanation":"","evidenceText":"",
                   "x":300,"y":240,"width":42,"height":42}
                ],"iconInventoryComplete":true}]}
                """);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.iconInventoryComplete()).isTrue();
            assertThat(page.iconOccurrences()).hasSize(2);
            assertThat(page.iconOccurrences().getFirst().explanation()).isEqualTo("表示一份木材。");
            assertThat(page.iconOccurrences().getLast().explanation()).isEmpty();
        });
    }

    @Test
    void drops_only_a_malformed_icon_and_keeps_the_page_inventory_incomplete() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseCatalog("""
                {"pages":[{"pageNumber":8,"printedTerms":"icons","factualSummary":"图标页。",
                "keywords":["icons"],"iconOccurrences":[
                  {"groupKey":"bad","name":"Bad","visualDescription":"越界图标。",
                   "meaningStatus":"UNEXPLAINED","explanation":"","evidenceText":"",
                   "x":990,"y":990,"width":80,"height":80}
                ],"iconInventoryComplete":false}]}
                """);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.iconOccurrences()).isEmpty();
            assertThat(page.iconInventoryComplete()).isFalse();
        });
    }

    @Test
    void keeps_the_page_catalog_when_an_optional_anchor_has_invalid_geometry() {
        var draft = SpringAiVisualRulebookPageCatalogModel.parseCatalog("""
                {"pages":[{"pageNumber":7,"printedTerms":"power tokens",
                "factualSummary":"黄色图标与标签相邻。","keywords":["power tokens"],
                "visualAnchors":[{"kind":"legend","label":"power tokens","visibleDescription":"黄色图标。",
                "x":900,"y":900,"width":300,"height":300}]}]}
                """);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.printedTerms()).isEqualTo("power tokens");
            assertThat(page.visualAnchors()).isEmpty();
        });
    }

    @Test
    void bounds_overlong_optional_text_instead_of_discarding_the_icon_page() {
        String longSummary = "visible rule ".repeat(300);
        String json = "{\"pages\":[{\"pageNumber\":6,\"printedTerms\":\"ICONS\","
                + "\"factualSummary\":" + jsonString(longSummary) + ","
                + "\"keywords\":[\""
                + "x".repeat(180) + "\"],\"iconOccurrences\":[],\"iconInventoryComplete\":true}]}";

        var draft = SpringAiVisualRulebookPageCatalogModel.parseCatalog(json);

        assertThat(draft.pages()).singleElement().satisfies(page -> {
            assertThat(page.factualSummary()).hasSize(1_600);
            assertThat(page.keywords().getFirst()).hasSize(120);
        });
    }

    @Test
    void parsesDedicatedIconLocationsFromQwenArrayOrObjectShape() {
        var array = SpringAiVisualRulebookPageCatalogModel.parseIconLocalization("""
                [
                  {"candidateIndex":0,"present":true,"x":120,"y":300,"width":24,"height":20,"observedLabel":"WOOD"},
                  {"candidateIndex":1,"present":false}
                ]
                """, 2);
        var object = SpringAiVisualRulebookPageCatalogModel.parseIconLocalization("""
                {"items":[
                  {"candidateIndex":0,"present":true,"x":120,"y":300,"width":24,"height":20,"observedLabel":"WOOD"},
                  {"candidateIndex":1,"present":false}
                ]}
                """, 2);

        assertThat(array).isEqualTo(object);
        assertThat(array.locations()).hasSize(2);
        assertThat(array.locations().getFirst().present()).isTrue();
        assertThat(array.locations().getFirst().observedLabel()).isEqualTo("WOOD");
        assertThat(array.locations().getLast().present()).isFalse();
    }

    @Test
    void rejectsOnlyTheMalformedRectangleInsteadOfDiscardingVerifiedCandidates() {
        var result = SpringAiVisualRulebookPageCatalogModel.parseIconLocalization("""
                {"items":[
                  {"candidateIndex":0,"present":true,"x":120,"y":300,"width":24,"height":20},
                  {"candidateIndex":1,"present":true,"x":995,"y":995,"width":30,"height":30}
                ]}
                """, 2);

        assertThat(result.locations().getFirst().present()).isTrue();
        assertThat(result.locations().getLast()).isEqualTo(IconLocation.absent(1));
    }

    @Test
    void parsesCloseUpCropReviewForTheExactCandidateIndexes() {
        var result = SpringAiVisualRulebookPageCatalogModel.parseIconCropReview("""
                {"items":[
                  {"candidateIndex":3,"accepted":true,"rejectionCode":"ACCEPTED"},
                  {"candidateIndex":7,"accepted":false,"rejectionCode":"NO_MATCHING_PICTOGRAM"}
                ]}
                """, List.of(3, 7));

        assertThat(result.decisions()).hasSize(2);
        assertThat(result.decisions().getFirst().matchesAppearance()).isTrue();
        assertThat(result.decisions().getLast().matchesAppearance()).isFalse();
    }

    @Test
    void rejectsClippedAndMultiSymbolCropsFromThePublicationGate() {
        var result = SpringAiVisualRulebookPageCatalogModel.parseIconCropReview("""
                {"items":[
                  {"candidateIndex":2,"accepted":false,"rejectionCode":"CLIPPED_OR_PARTIAL"},
                  {"candidateIndex":5,"accepted":false,"rejectionCode":"MULTIPLE_SYMBOLS"}
                ]}
                """, List.of(2, 5));

        assertThat(result.decisions()).containsExactly(
                IconCropDecision.rejected(2),
                IconCropDecision.rejected(5));
    }

    @Test
    void rejectsOnlyTheCandidateWhosePublicationVerdictIsMissing() {
        var result = SpringAiVisualRulebookPageCatalogModel.parseIconCropReview("""
                {"items":[
                  {"candidateIndex":3,"accepted":true,"rejectionCode":"ACCEPTED"},
                  {"candidateIndex":7,"rejectionCode":"ACCEPTED"}
                ]}
                """, List.of(3, 7));

        assertThat(result.decisions().getFirst().matchesAppearance()).isTrue();
        assertThat(result.decisions().getLast()).isEqualTo(IconCropDecision.rejected(7));
    }

    @Test
    void localizesFromAppearanceWithoutSemanticNamesThatCanMatchNearbyProse() {
        String candidates = SpringAiVisualRulebookPageCatalogModel.iconLocalizationCandidates(List.of(
                new com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence(
                        "collect-no-buttons",
                        "收集零个纽扣",
                        "红色叉号叠加在白色纽扣图标上。",
                        "",
                        "",
                        com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus.UNEXPLAINED,
                        100,
                        100,
                        20,
                        20)));

        assertThat(candidates).contains("visible appearance=红色叉号叠加在白色纽扣图标上。");
        assertThat(candidates).doesNotContain("collect-no-buttons", "收集零个纽扣");
    }

    @Test
    void redactsFirstPassLabelsBeforeIndependentImageLabelVerification() {
        String candidates = SpringAiVisualRulebookPageCatalogModel.iconLocalizationCandidates(List.of(
                new com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence(
                        "CARROT",
                        "胡萝卜",
                        "Orange card with CARROT printed above a carrot silhouette.",
                        "代表胡萝卜类型。",
                        "CARROT",
                        com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus.EXPLICIT,
                        100,
                        100,
                        20,
                        20)));

        assertThat(candidates).contains("redacted-label");
        assertThat(candidates).doesNotContain("CARROT", "胡萝卜");
    }

    @Test
    void repairsOneConcatenatedPageNumberWithoutChangingTheKnownPageBinding() {
        CatalogRequest request = new CatalogRequest(
                List.of(
                        new PageImageInput(7, "image/jpeg", new byte[] {1}),
                        new PageImageInput(14, "image/jpeg", new byte[] {2})),
                "owner");
        CatalogDraft draft = new CatalogDraft(List.of(
                new PageSummary(714, "power tokens", "红色图标是胜利点。", List.of("victory point")),
                new PageSummary(14, "KODORA", "下注两个胜利点。", List.of("KODORA"))));

        CatalogDraft normalized = SpringAiVisualRulebookPageCatalogModel.normalizePageBindings(request, draft);

        assertThat(normalized.pages()).extracting(PageSummary::pageNumber).containsExactly(7, 14);
        assertThat(normalized.pages().getLast().printedTerms()).isEqualTo("KODORA");
    }

    private static String jsonString(String value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }
}
