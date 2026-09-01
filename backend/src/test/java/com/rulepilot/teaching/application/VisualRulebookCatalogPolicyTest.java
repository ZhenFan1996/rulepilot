package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.DocumentProcessing.PageView;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.PageLedgerState;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import com.rulepilot.teaching.VisualQuantityObservation;
import com.rulepilot.teaching.VisualQuantityObservation.QuantityResolution;
import com.rulepilot.teaching.VisualQuantityObservation.QuantifierScope;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class VisualRulebookCatalogPolicyTest {

    @Test
    void pageFactEvidenceRetainsValidatedQuantityScopeAndTheOriginalVisibleSpan() {
        PageSummary summary = new PageSummary(
                7,
                "R-Δ; glyph families; prisms",
                "R-Δ: Place one prism for each of the four visibly listed glyph families.",
                List.of("R-Δ", "glyph families", "prisms"),
                List.of(),
                List.of(),
                false,
                List.of(),
                List.of("R-Δ"),
                true,
                List.of(new VisualQuantityObservation(
                        7,
                        "R-Δ",
                        QuantifierScope.PER_VARIANT,
                        "glyph families",
                        4,
                        1,
                        4,
                        "4 glyph families × 1 prism each",
                        QuantityResolution.EXACT)),
                List.of(new RuleGroupFact(
                        "R-Δ", "R-Δ", "Place one prism for each of the four visibly listed glyph families.")));

        PageFact fact = VisualRulebookCatalogPolicy.toPageFact(summary);

        assertThat(fact.factualSummary()).contains(
                "R-Δ: Place one prism for each of the four visibly listed glyph families.",
                "page=7",
                "ruleGroup=R-Δ",
                "scope=PER_VARIANT",
                "variantAxis=glyph families",
                "variantCount=4",
                "perVariantQuantity=1",
                "derivedTotal=4",
                "resolution=EXACT",
                "originalSpan=4 glyph families × 1 prism each");
    }

    @Test
    void keepsEveryVisualPageIndependent() {
        assertThat(VisualRulebookCatalogPolicy.singlePageBatches(List.of(2, 5, 7)))
                .containsExactly(List.of(2), List.of(5), List.of(7));
    }

    @Test
    void keepsTheCompleteTeachingLedgerWithinOneDensePagePerModelOutput() {
        assertThat(VisualRulebookCatalogPolicy.teachingStartupBatches(List.of(2, 5, 7, 9, 11, 13, 17, 19, 23, 29)))
                .containsExactly(
                        List.of(2),
                        List.of(5),
                        List.of(7),
                        List.of(9),
                        List.of(11),
                        List.of(13),
                        List.of(17),
                        List.of(19),
                        List.of(23),
                        List.of(29));
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
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PageFact(
                        1,
                        "TURN",
                        "Natural prose is not parsed into a rule-group binding.",
                        List.of("turn"),
                        List.of(),
                        List.of(),
                        false,
                        PageFact.CURRENT_SCHEMA_VERSION,
                        List.of(),
                        List.of("TURN"),
                        true,
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete rule-group inventory");
    }

    @Test
    void readsButNeverReusesAHistoricalCompleteFlagThatPredatesTypedRuleGroupFacts() {
        PageFact historical = new PageFact(
                1,
                "TURN",
                "TURN: Historical prose ledger.",
                List.of("turn"),
                List.of(),
                List.of(),
                false,
                PageFact.CURRENT_SCHEMA_VERSION - 1,
                List.of(),
                List.of("TURN"),
                true,
                List.of());

        assertThat(historical.ruleGroupInventoryComplete()).isTrue();
        assertThat(historical.ruleGroupFacts()).isEmpty();
        assertThat(VisualRulebookCatalogPolicy.hasReusableCompleteRuleLedger(historical)).isFalse();
        assertThat(VisualRulebookCatalogPolicy.missingPages(java.util.Set.of(1), List.of(historical)))
                .containsExactly(1);
    }

    @Test
    void rejectsACurrentCompletePageSummaryWithoutTypedFactBindings() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PageSummary(
                        1,
                        "TURN",
                        "Natural prose is not a typed rule-group binding.",
                        List.of("turn"),
                        List.of(),
                        List.of(),
                        false,
                        List.of(),
                        List.of("TURN"),
                        true,
                        List.of(),
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly match");
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
                true,
                List.of(),
                List.of(new RuleGroupFact("TURN", "TURN", "A visible turn rule.")));

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
                true,
                List.of(),
                List.of(new RuleGroupFact(
                        "PLAY A CARD", "PLAY A CARD", "当前页要求另查开局资料。")));

        PageFact fact = VisualRulebookCatalogPolicy.toPageFact(summary);
        var inputs = VisualRulebookCatalogPolicy.pageInputs(
                List.of(new PageView(4, "", 0)), List.of(fact));

        assertThat(fact.sourceDependencies()).containsExactly(dependency);
        assertThat(inputs).singleElement().satisfies(input -> {
            assertThat(input.sourceDependencies()).containsExactly(dependency);
            assertThat(input.sourceRuleGroupIdentifiers()).containsExactly("PLAY A CARD");
            assertThat(input.sourceRuleGroupInventoryComplete()).isTrue();
            assertThat(input.pageLedgerState()).isEqualTo(PageLedgerState.VISUAL_EXACT_COMPLETE);
        });
    }

    @Test
    void keepsAVisualCatalogMissAsTypedUnavailableEvidenceRatherThanPromptProse() {
        var inputs = VisualRulebookCatalogPolicy.pageInputs(
                List.of(new PageView(4, "", 0), new PageView(5, "", 0), new PageView(6, "", 0)),
                List.of(
                        new PageFact(
                                4,
                                "MOVE",
                                "MOVE: Move one piece.",
                                List.of("move"),
                                List.of(),
                                List.of(),
                                false,
                                PageFact.CURRENT_SCHEMA_VERSION,
                                List.of(),
                                List.of("MOVE"),
                                true,
                                List.of(new RuleGroupFact("MOVE", "MOVE", "Move one piece."))),
                        new PageFact(
                                6,
                                "SCORE",
                                "Only part of the scoring table was interpreted.",
                                List.of("score"),
                                List.of(),
                                List.of(),
                                false,
                                PageFact.CURRENT_SCHEMA_VERSION,
                                List.of(),
                                List.of("SCORE"),
                                false,
                                List.of(new RuleGroupFact("SCORE", "SCORE", "Partial observation.")))));

        assertThat(inputs).extracting(PageInput::pageLedgerState)
                .containsExactly(
                        PageLedgerState.VISUAL_EXACT_COMPLETE,
                        PageLedgerState.VISUAL_EXPLICITLY_UNAVAILABLE,
                        PageLedgerState.VISUAL_PARTIAL);
        assertThat(inputs.get(1).sourceRuleGroupIdentifiers()).isEmpty();
        assertThat(inputs.get(1).sourceRuleGroupInventoryComplete()).isFalse();
    }

    @Test
    void visualEnhancementDoesNotPromoteAnOrdinaryTextRulebookIntoTheVisualCanonicalProtocol() {
        PageInput legacyText = new PageInput(4, "MOVE: Move one piece.");
        PageFact exactVisualSupplement = new PageFact(
                4,
                "MOVE",
                "MOVE: Move one piece.",
                List.of("move"),
                List.of(),
                List.of(),
                false,
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of("MOVE"),
                true,
                List.of(new RuleGroupFact("MOVE", "MOVE", "Move one piece.")));

        var enhanced = VisualRulebookCatalogPolicy.appendFactsToPageInputs(
                List.of(legacyText), List.of(exactVisualSupplement));

        assertThat(enhanced).singleElement().satisfies(page -> {
            assertThat(page.pageLedgerState()).isEqualTo(PageLedgerState.LEGACY_TEXT);
            assertThat(page.sourceRuleGroupInventoryComplete()).isTrue();
        });
    }

    @Test
    void preservesAnIncompleteStartupFactWithoutPromotingItsCompletionMarker() {
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

        PageSummary retained = VisualRulebookCatalogPolicy.teachingStartupFact(incomplete);

        assertThat(retained.ruleGroupIdentifiers()).containsExactly("MOVE");
        assertThat(retained.ruleGroupInventoryComplete()).isFalse();
        assertThat(retained.factualSummary()).isEqualTo("Only one visible relation was returned.");
    }

    @Test
    void teachingStartupUsesTypedRuleGroupsInsteadOfParsingTheNaturalSummary() {
        PageSummary structured = new PageSummary(
                4,
                "MOVE; BUILD",
                "MOVE: Move one pawn.",
                List.of("MOVE", "BUILD"),
                List.of(),
                List.of(),
                false,
                List.of(),
                List.of("MOVE", "BUILD"),
                true,
                List.of(),
                ruleFacts());

        PageSummary retained = VisualRulebookCatalogPolicy.teachingStartupFact(structured);

        assertThat(retained.ruleGroupFacts()).containsExactlyElementsOf(ruleFacts());
        assertThat(retained.ruleGroupInventoryComplete()).isTrue();
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
        assertThat(VisualRulebookCatalogPolicy.needsIconTileFallback(densePartialInventory)).isFalse();
        assertThat(VisualRulebookCatalogPolicy.needsIconTileFallback(cover)).isTrue();
        assertThat(VisualRulebookCatalogPolicy.needsIconTileFallback(setupProse)).isTrue();
        assertThat(VisualRulebookCatalogPolicy.needsIconTileFallback(denseProseWithNoVisualAnchor))
                .isFalse();
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
                true,
                List.of(),
                ruleFacts());
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
                true,
                List.of(),
                ruleFacts());
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
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PageSummary(
                        3,
                        "MOVE; BUILD",
                        "Natural prose is irrelevant to structured ownership.",
                        List.of("turn"),
                        List.of(),
                        List.of(),
                        false,
                        List.of(),
                        List.of("MOVE", "BUILD"),
                        true,
                        List.of(),
                        List.of(new RuleGroupFact("MOVE", "MOVE", "Move one pawn."))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly match");
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
                true,
                List.of(),
                List.of(new RuleGroupFact("MOVE", "MOVE", "Move one pawn.")));

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
                true,
                List.of(),
                ruleFacts());

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
    void persistsEveryKeywordFromADenseValidPage() {
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
        assertThat(fact.keywords()).containsExactlyElementsOf(dense.keywords());
        assertThat(fact.iconInventoryComplete()).isTrue();
    }

    @Test
    void normalizesEmptyKeywordMetadataBeforeDurableProjectionWithoutWeakeningRuleEvidence() {
        PageSummary sparseMetadata = new PageSummary(
                9,
                "R-1",
                "R-1: Execute the visible procedure.",
                List.of(),
                List.of(),
                List.of(),
                false,
                List.of(),
                List.of("R-1"),
                true,
                List.of(),
                List.of(new RuleGroupFact("R-1", "R-1", "Execute the visible procedure.")));

        var fact = VisualRulebookCatalogPolicy.toPageFact(sparseMetadata);

        assertThat(fact.keywords()).containsExactly("page 9");
        assertThat(fact.schemaVersion()).isEqualTo(PageFact.CURRENT_SCHEMA_VERSION);
        assertThat(fact.ruleGroupIdentifiers()).containsExactly("R-1");
        assertThat(fact.ruleGroupFacts()).containsExactlyElementsOf(sparseMetadata.ruleGroupFacts());
        assertThat(fact.ruleGroupInventoryComplete()).isTrue();
        assertThat(VisualRulebookCatalogPolicy.hasReusableCompleteRuleLedger(fact)).isTrue();
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
                complete,
                complete
                        ? List.of(new RuleGroupFact("TURN", "TURN", factualSummary))
                        : List.of());
    }

    private static List<RuleGroupFact> ruleFacts() {
        return List.of(
                new RuleGroupFact("MOVE", "MOVE", "Move one pawn."),
                new RuleGroupFact("BUILD", "BUILD", "Place one building."));
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
