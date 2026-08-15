package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.DocumentProcessing.PageView;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
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
        assertThat(VisualRulebookCatalogPolicy.teachingStartupBatches(List.of(2, 5, 7, 9, 11, 13, 17, 19, 23, 29)))
                .containsExactly(List.of(2, 5, 7, 9, 11, 13, 17, 19), List.of(23, 29));
        assertThat(VisualRulebookCatalogPolicy.teachingStartupBatches(List.of())).isEmpty();
    }

    @Test
    void reusesOnlyCurrentCompleteLedgersAndRejectsUnboundFactsAtConstruction() {
        PageFact valid = pageFact(PageFact.CURRENT_SCHEMA_VERSION, "TURN: Take one action.", true);
        PageFact incomplete = pageFact(PageFact.CURRENT_SCHEMA_VERSION, "TURN: Take one action.", false);
        PageFact stale = pageFact(PageFact.CURRENT_SCHEMA_VERSION - 1, "TURN: Take one action.", true);

        assertThat(VisualRulebookCatalogPolicy.hasReusableCompleteRuleLedger(valid)).isTrue();
        assertThat(VisualRulebookCatalogPolicy.hasReusableCompleteRuleLedger(incomplete)).isFalse();
        assertThat(VisualRulebookCatalogPolicy.hasReusableCompleteRuleLedger(stale)).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> pageFact(
                        PageFact.CURRENT_SCHEMA_VERSION,
                        "A turn rule was observed without an exact ledger key.",
                        true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete rule-group inventory");
    }

    @Test
    void teachingStartupFactsCanNeverClaimIconOrSpatialCompleteness() {
        PageSummary overreachingModelOutput = new PageSummary(
                4,
                "TURN",
                "TURN: A visible turn rule.",
                List.of("turn"),
                List.of(new VisualAnchor("diagram", "turn", "Turn diagram.", 10, 10, 100, 100)),
                List.of(icon("action", "行动")),
                true,
                List.of(),
                List.of("TURN"),
                true);

        PageSummary bounded = VisualRulebookCatalogPolicy.teachingStartupFact(overreachingModelOutput);

        assertThat(bounded.factualSummary()).isEqualTo("TURN: A visible turn rule.");
        assertThat(bounded.visualAnchors()).isEmpty();
        assertThat(bounded.iconOccurrences()).isEmpty();
        assertThat(bounded.iconInventoryComplete()).isFalse();
        assertThat(bounded.ruleGroupIdentifiers()).containsExactly("TURN");
        assertThat(bounded.ruleGroupInventoryComplete()).isTrue();
    }

    @Test
    void externalSourceDependenciesSurviveTheDurableFactAndOutlineInputBoundary() {
        var dependency = new SourceDependency("First Session Booklet", List.of("setup"));
        PageSummary summary = new PageSummary(
                4,
                "PLAY A CARD",
                "PLAY A CARD: 当前页要求另查开局资料。",
                List.of("PLAY A CARD"),
                List.of(),
                List.of(),
                false,
                List.of(dependency),
                List.of("PLAY A CARD"),
                true);

        PageFact fact = VisualRulebookCatalogPolicy.toPageFact(summary);
        var inputs = VisualRulebookCatalogPolicy.pageInputs(
                List.of(new PageView(4, "", 0)), List.of(fact));

        assertThat(fact.sourceDependencies()).containsExactly(dependency);
        assertThat(inputs).singleElement().satisfies(input -> {
            assertThat(input.sourceDependencies()).containsExactly(dependency);
            assertThat(input.sourceRuleGroupIdentifiers()).containsExactly("PLAY A CARD");
            assertThat(input.sourceRuleGroupInventoryComplete()).isTrue();
        });
    }

    @Test
    void rejectsATeachingStartupFactWhoseRuleGroupInventoryIsNotComplete() {
        PageSummary incomplete = new PageSummary(
                4,
                "MOVE",
                "Only one visible relation was returned.",
                List.of("MOVE"),
                List.of(),
                List.of(),
                false,
                List.of(),
                List.of("MOVE"),
                false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        VisualRulebookCatalogPolicy.teachingStartupFact(incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("every readable gameplay rule group");
    }

    @Test
    void rejectsATeachingStartupCompleteMarkerWithoutEveryBoundRuleFact() {
        PageSummary unbound = new PageSummary(
                4,
                "MOVE; BUILD",
                "MOVE: Move one pawn.",
                List.of("MOVE", "BUILD"),
                List.of(),
                List.of(),
                false,
                List.of(),
                List.of("MOVE", "BUILD"),
                true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        VisualRulebookCatalogPolicy.teachingStartupFact(unbound))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lost a rule-group fact")
                .hasMessageContaining("BUILD");
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
                "MOVE: Move one pawn.\nBUILD: Place one building.",
                List.of("legend"),
                List.of(),
                List.of(grounded),
                true,
                List.of(),
                List.of("MOVE", "BUILD"),
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
                true,
                List.of(),
                List.of(),
                false);

        PageSummary merged = VisualRulebookCatalogPolicy.mergeIconTileAudit(fullPage, tileAudit);

        assertThat(merged.printedTerms()).isEqualTo(fullPage.printedTerms());
        assertThat(merged.factualSummary()).contains("MOVE: Move one pawn.", "BUILD: Place one building.", "Tile facts.");
        assertThat(merged.factualSummary()).startsWith("MOVE: Move one pawn.");
        assertThat(merged.keywords()).containsExactly("legend", "icons");
        assertThat(merged.iconInventoryComplete()).isTrue();
        assertThat(merged.iconOccurrences()).hasSize(3);
        assertThat(merged.iconOccurrences().getFirst()).isEqualTo(grounded);
        assertThat(merged.ruleGroupIdentifiers()).containsExactly("MOVE", "BUILD");
        assertThat(merged.ruleGroupInventoryComplete()).isTrue();
    }

    @Test
    void completeFullPageRuleFactsCannotBeEvictedByDenseOptionalTileFacts() {
        PageSummary fullPage = new PageSummary(
                3,
                "MOVE; BUILD",
                "MOVE: Move one pawn.\nBUILD: Place one building.",
                List.of("turn"),
                List.of(),
                List.of(),
                false,
                List.of(),
                List.of("MOVE", "BUILD"),
                true);
        PageSummary denseTileAudit = new PageSummary(
                3,
                "VISIBLE ICONS",
                "T".repeat(3_999),
                List.of("icons"),
                List.of(),
                List.of(icon("pawn", "棋子")),
                true,
                List.of(),
                List.of(),
                false);

        PageSummary merged = VisualRulebookCatalogPolicy.mergeIconTileAudit(fullPage, denseTileAudit);

        assertThat(merged.ruleGroupInventoryComplete()).isTrue();
        assertThat(merged.ruleGroupIdentifiers()).containsExactly("MOVE", "BUILD");
        assertThat(merged.factualSummary()).contains("MOVE: Move one pawn.", "BUILD: Place one building.");
    }

    @Test
    void rejectsACompleteMarkerWhenAnInheritedRuleGroupHasNoBoundFact() {
        PageSummary invalidFullPage = new PageSummary(
                3,
                "MOVE; BUILD",
                "MOVE: Move one pawn.",
                List.of("turn"),
                List.of(),
                List.of(),
                false,
                List.of(),
                List.of("MOVE", "BUILD"),
                true);
        PageSummary tileAudit = new PageSummary(
                3,
                "VISIBLE ICONS",
                "Tile facts.",
                List.of("icons"),
                List.of(),
                List.of(icon("pawn", "棋子")),
                true,
                List.of(),
                List.of(),
                false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        VisualRulebookCatalogPolicy.mergeIconTileAudit(invalidFullPage, tileAudit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lost a rule-group fact")
                .hasMessageContaining("BUILD");
    }

    @Test
    void iconTileAuditCanNeverUpgradeAnIncompleteRuleGroupInventory() {
        PageSummary incompleteFullPage = new PageSummary(
                3,
                "Visible labels",
                "A partial full-page observation.",
                List.of("reference"),
                List.of(),
                List.of(),
                false,
                List.of(),
                List.of(),
                false);
        PageSummary overreachingIconTile = new PageSummary(
                3,
                "MOVE",
                "MOVE: Move one pawn.",
                List.of("move"),
                List.of(),
                List.of(icon("pawn", "棋子")),
                true,
                List.of(),
                List.of("MOVE"),
                true);

        PageSummary merged = VisualRulebookCatalogPolicy.mergeIconTileAudit(
                incompleteFullPage, overreachingIconTile);

        assertThat(merged.ruleGroupIdentifiers()).isEmpty();
        assertThat(merged.ruleGroupInventoryComplete()).isFalse();
    }

    @Test
    void completePersistedObservationReplacesTheRuleLedgerAndKeepsIndependentVisualEvidence() {
        VisualAnchor priorAnchor = new VisualAnchor(
                "diagram", "Board map", "A previously localized board map.", 40, 50, 300, 220);
        IconOccurrence priorIcon = icon("resource", "Resource");
        PageSummary incompleteExisting = new PageSummary(
                3,
                "OLD PARTIAL",
                "An earlier observation admitted that it was partial.",
                List.of("old"),
                List.of(priorAnchor),
                List.of(priorIcon),
                true,
                List.of(new SourceDependency("Obsolete leaflet", List.of("setup"))),
                List.of("OLD PARTIAL"),
                false);
        PageSummary completeObservation = new PageSummary(
                3,
                "MOVE; BUILD",
                "MOVE: Move one pawn.\nBUILD: Place one building.",
                List.of("move", "build"),
                List.of(),
                List.of(),
                false,
                List.of(new SourceDependency("First Session Guide", List.of("setup"))),
                List.of("MOVE", "BUILD"),
                true);

        PageSummary merged = VisualRulebookCatalogPolicy.mergePersistedPageObservation(
                incompleteExisting, completeObservation);

        assertThat(merged.printedTerms()).isEqualTo("MOVE; BUILD");
        assertThat(merged.factualSummary())
                .contains("MOVE: Move one pawn.", "BUILD: Place one building.")
                .doesNotContain("earlier observation");
        assertThat(merged.keywords()).containsExactly("move", "build");
        assertThat(merged.sourceDependencies())
                .containsExactly(new SourceDependency("First Session Guide", List.of("setup")));
        assertThat(merged.ruleGroupIdentifiers()).containsExactly("MOVE", "BUILD");
        assertThat(merged.ruleGroupInventoryComplete()).isTrue();
        assertThat(merged.visualAnchors()).containsExactly(priorAnchor);
        assertThat(merged.iconOccurrences()).containsExactly(priorIcon);
        assertThat(merged.iconInventoryComplete()).isTrue();
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

    private static PageFact pageFact(int schemaVersion, String factualSummary, boolean complete) {
        return new PageFact(
                1,
                "TURN",
                factualSummary,
                List.of("turn"),
                List.of(),
                List.of(),
                false,
                schemaVersion,
                List.of(),
                List.of("TURN"),
                complete);
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
