package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class VisualRulebookCatalogPolicyTest {

    @Test
    void keepsEveryVisualPageIndependent() {
        assertThat(VisualRulebookCatalogPolicy.singlePageBatches(List.of(2, 5, 7)))
                .containsExactly(List.of(2), List.of(5), List.of(7));
    }

    @Test
    void boundsTeachingStartupBatchesWithoutDroppingSourcePages() {
        assertThat(VisualRulebookCatalogPolicy.teachingStartupBatches(List.of(2, 5, 7, 9, 11, 13, 17)))
                .containsExactly(List.of(2, 5, 7), List.of(9, 11, 13), List.of(17));
        assertThat(VisualRulebookCatalogPolicy.teachingStartupBatches(List.of())).isEmpty();
    }

    @Test
    void teachingStartupFactsCanNeverClaimIconOrSpatialCompleteness() {
        PageSummary overreachingModelOutput = new PageSummary(
                4,
                "TURN",
                "A visible turn rule.",
                List.of("turn"),
                List.of(new VisualAnchor("diagram", "turn", "Turn diagram.", 10, 10, 100, 100)),
                List.of(icon("action", "行动")),
                true);

        PageSummary bounded = VisualRulebookCatalogPolicy.teachingStartupFact(overreachingModelOutput);

        assertThat(bounded.factualSummary()).isEqualTo("A visible turn rule.");
        assertThat(bounded.visualAnchors()).isEmpty();
        assertThat(bounded.iconOccurrences()).isEmpty();
        assertThat(bounded.iconInventoryComplete()).isFalse();
    }

    @Test
    void requestsTileAuditFromStructuredDensitySignalsWithoutClassifyingPageVocabulary() {
        PageSummary scoringReference = summary(
                new VisualAnchor("table", "Scoring reference", "A structured reference.", 100, 100, 300, 300));
        PageSummary labeledIconGroup = summary(
                new VisualAnchor("labeled icon group", "Components", "Several labeled shapes.", 100, 100, 300, 300));
        PageSummary cover = summary(
                new VisualAnchor("title block", "RULEBOOK", "Cover title and illustration.", 100, 100, 300, 300));
        PageSummary setupProse =
                summary(new VisualAnchor("setup list", "Steps A-D", "Numbered setup prose.", 100, 100, 300, 300));
        PageSummary denseVisualPageWithNoProposedIcons = new PageSummary(
                1,
                "A; B; C; D; E; F; G; H",
                "Visible page facts.",
                List.of("reference"),
                List.of(new VisualAnchor(
                        "component diagram", "Card anatomy", "A labeled card diagram.", 100, 100, 300, 300)),
                List.of(),
                true);
        PageSummary denseProseWithNoVisualAnchor = new PageSummary(
                1,
                "A; B; C; D; E; F; G; H",
                "Visible page facts.",
                List.of("reference"),
                List.of(),
                List.of(),
                true);
        PageSummary denseCreditsPage = new PageSummary(
                1,
                "CREDITS; Game Design; Production; Editing; A; B; C; D",
                "This page contains credits and copyright information.",
                List.of("credits"),
                List.of(new VisualAnchor(
                        "publisher logo", "Publisher", "A publisher mark.", 100, 100, 300, 300)),
                List.of(),
                true);
        PageSummary densePartialInventory = new PageSummary(
                1,
                "A; B; C; D; E; F; G; H",
                "Visible page facts.",
                List.of("reference"),
                List.of(),
                List.of(icon("leaf", "叶子图标"), icon("point", "计分图标")),
                true);
        PageSummary simpleInventory = new PageSummary(
                1,
                "VISIBLE ICONS",
                "Visible page facts.",
                List.of("reference"),
                List.of(),
                List.of(icon("leaf", "叶子图标"), icon("point", "计分图标")),
                true);

        assertThat(VisualRulebookCatalogPolicy.needsIconTileFallback(scoringReference)).isTrue();
        assertThat(VisualRulebookCatalogPolicy.needsIconTileFallback(labeledIconGroup)).isTrue();
        assertThat(VisualRulebookCatalogPolicy.needsIconTileFallback(denseVisualPageWithNoProposedIcons))
                .isTrue();
        assertThat(VisualRulebookCatalogPolicy.needsIconTileFallback(densePartialInventory)).isTrue();
        assertThat(VisualRulebookCatalogPolicy.needsIconTileFallback(cover)).isTrue();
        assertThat(VisualRulebookCatalogPolicy.needsIconTileFallback(setupProse)).isTrue();
        assertThat(VisualRulebookCatalogPolicy.needsIconTileFallback(denseProseWithNoVisualAnchor))
                .isTrue();
        assertThat(VisualRulebookCatalogPolicy.needsIconTileFallback(denseCreditsPage)).isTrue();
        assertThat(VisualRulebookCatalogPolicy.needsIconTileFallback(simpleInventory)).isFalse();
    }

    @Test
    void mergesTileAuditIconsWithoutDiscardingGroundedFullPageEvidence() {
        IconOccurrence grounded = new IconOccurrence(
                "leaf",
                "叶子",
                "Green leaf silhouette.",
                "代表叶子资源。",
                "LEAF",
                "LEAF",
                IconMeaningStatus.EXPLICIT,
                100,
                100,
                24,
                24);
        PageSummary fullPage = new PageSummary(
                3,
                "LEAF; SUN; WATER",
                "Visible facts.",
                List.of("legend"),
                List.of(),
                List.of(grounded),
                true);
        PageSummary tileAudit = new PageSummary(
                3,
                "SUN; WATER",
                "Tile facts.",
                List.of("icons"),
                List.of(),
                List.of(
                        icon("leaf", "叶片"),
                        icon("sun", "太阳"),
                        icon("water", "水滴")),
                true);

        PageSummary merged = VisualRulebookCatalogPolicy.mergeIconTileAudit(fullPage, tileAudit);

        assertThat(merged.printedTerms()).isEqualTo(fullPage.printedTerms());
        assertThat(merged.factualSummary()).contains("Visible facts.", "Tile facts.");
        assertThat(merged.factualSummary()).startsWith("Tile facts.");
        assertThat(merged.keywords()).containsExactly("legend", "icons");
        assertThat(merged.iconInventoryComplete()).isTrue();
        assertThat(merged.iconOccurrences()).hasSize(3);
        assertThat(merged.iconOccurrences().getFirst()).isEqualTo(grounded);
    }

    @Test
    void persistsADenseValidPageByNarrowingUpstreamKeywordsInsteadOfDroppingThePage() {
        PageSummary dense = new PageSummary(
                3,
                "Visible labels",
                "A complete page-scoped factual ledger.",
                IntStream.rangeClosed(1, 16).mapToObj(index -> "keyword-" + index).toList(),
                List.of(),
                List.of(),
                true);

        var fact = VisualRulebookCatalogPolicy.toPageFact(dense);

        assertThat(fact.pageNumber()).isEqualTo(3);
        assertThat(fact.factualSummary()).isEqualTo(dense.factualSummary());
        assertThat(fact.keywords()).containsExactlyElementsOf(dense.keywords().subList(0, 12));
        assertThat(fact.iconInventoryComplete()).isTrue();
    }

    @Test
    void publishesStructurallyValidCompactRegionsWithoutGuessingTheirSemanticRole() {
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon("leaf", "叶子图标"), 100, 100, 24, 28))
                .isTrue();
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon(
                                "carrot",
                                "胡萝卜图标",
                                "卡片角落白色圆圈中的胡萝卜插图。"),
                        100,
                        100,
                        24,
                        28))
                .isTrue();
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon("point card", "积分卡", "Whole card with a scoring condition."), 100, 100, 88, 170))
                .isFalse();
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon("A", "A"), 100, 100, 24, 24))
                .isFalse();
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon("objective-i", "Objective", "Black uppercase letter I inside a circle."),
                        100,
                        100,
                        24,
                        24))
                .isTrue();
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon("check", "对勾", "Green checkmark next to text labels."), 100, 100, 24, 24))
                .isTrue();
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon("rainbow", "彩虹", "Small arc next to text mentioning a button."), 100, 100, 24, 24))
                .isTrue();
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon("cat illustration", "猫插图"), 100, 100, 64, 64))
                .isTrue();
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon("publisher logo", "出版社徽标", "Red shield with white letters."), 100, 100, 64, 64))
                .isTrue();
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon("adjacency example", "同色相邻示例"), 100, 100, 177, 35))
                .isFalse();
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon("leaf row", "叶子图案"), 100, 100, 860, 50))
                .isFalse();
    }

    private static PageSummary summary(VisualAnchor anchor) {
        return new PageSummary(
                1, "VISIBLE TEXT", "Visible page facts.", List.of("reference"), List.of(anchor), List.of(), true);
    }

    private static IconOccurrence icon(String groupKey, String name) {
        return icon(groupKey, name, "A compact visible mark.");
    }

    private static IconOccurrence icon(String groupKey, String name, String visualDescription) {
        return new IconOccurrence(
                groupKey,
                name,
                visualDescription,
                "",
                "",
                IconMeaningStatus.UNEXPLAINED,
                100,
                100,
                20,
                20);
    }
}
