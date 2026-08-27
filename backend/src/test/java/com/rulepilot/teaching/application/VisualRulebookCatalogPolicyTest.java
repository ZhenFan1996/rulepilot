package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.DocumentProcessing.PageView;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.PageLedgerState;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
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
                        List.of("TURN"),
                        true,
                        List.of(),
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly match");
    }

    @Test
    void teachingStartupFactsCanNeverClaimSpatialEnrichment() {
        PageSummary overreachingModelOutput = new PageSummary(
                4,
                "TURN",
                "TURN: A visible turn rule.",
                List.of("turn"),
                List.of(new VisualAnchor("diagram", "turn", "Turn diagram.", 10, 10, 100, 100)),
                List.of(),
                List.of("TURN"),
                true,
                List.of(),
                List.of(new RuleGroupFact("TURN", "TURN", "A visible turn rule.")));

        PageSummary bounded = VisualRulebookCatalogPolicy.teachingStartupFact(overreachingModelOutput);

        assertThat(bounded.factualSummary()).isEqualTo("TURN: A visible turn rule.");
        assertThat(bounded.visualAnchors()).isEmpty();
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
    void keepsVisualMissesAndPartialFactsInTheirTypedLedgerStates() {
        var inputs = VisualRulebookCatalogPolicy.pageInputs(
                List.of(new PageView(4, "", 0), new PageView(5, "", 0), new PageView(6, "", 0)),
                List.of(
                        visualPageFact(4, "MOVE", true),
                        visualPageFact(6, "SCORE", false)));

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

        var enhanced = VisualRulebookCatalogPolicy.appendFactsToPageInputs(
                List.of(legacyText), List.of(visualPageFact(4, "MOVE", true)));

        assertThat(enhanced).singleElement().satisfies(page -> {
            assertThat(page.pageLedgerState()).isEqualTo(PageLedgerState.LEGACY_TEXT);
            assertThat(page.sourceRuleGroupInventoryComplete()).isTrue();
        });
    }

    private PageFact visualPageFact(int pageNumber, String identifier, boolean complete) {
        return new PageFact(
                pageNumber,
                identifier,
                identifier + ": A directly visible observation.",
                List.of(identifier.toLowerCase(java.util.Locale.ROOT)),
                List.of(),
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of(identifier),
                complete,
                List.of(new RuleGroupFact(identifier, identifier, "A directly visible observation.")));
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
                List.of("MOVE"),
                false,
                List.of(),
                List.of());

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
                List.of("MOVE", "BUILD"),
                true,
                List.of(),
                ruleFacts());

        PageSummary retained = VisualRulebookCatalogPolicy.teachingStartupFact(structured);

        assertThat(retained.ruleGroupFacts()).containsExactlyElementsOf(ruleFacts());
        assertThat(retained.ruleGroupInventoryComplete()).isTrue();
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
                        List.of("MOVE", "BUILD"),
                        true,
                        List.of(),
                        List.of(new RuleGroupFact("MOVE", "MOVE", "Move one pawn."))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly match");
    }

    @Test
    void completePersistedObservationReplacesTheRuleLedgerAndKeepsIndependentVisualEvidence() {
        VisualAnchor priorAnchor = new VisualAnchor(
                "diagram", "Board map", "A previously localized board map.", 40, 50, 300, 220);
        PageSummary incompleteExisting = new PageSummary(
                3,
                "OLD PARTIAL",
                "An earlier observation admitted that it was partial.",
                List.of("old"),
                List.of(priorAnchor),
                List.of(new SourceDependency("Obsolete leaflet", List.of("setup"))),
                List.of("OLD PARTIAL"),
                false,
                List.of(),
                List.of());
        PageSummary completeObservation = new PageSummary(
                3,
                "MOVE; BUILD",
                "MOVE: Move one pawn.\nBUILD: Place one building.",
                List.of("move", "build"),
                List.of(),
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
    }

    @Test
    void persistsEveryKeywordFromADenseValidPage() {
        PageSummary dense = new PageSummary(
                3,
                "Visible labels",
                "A complete page-scoped factual ledger.",
                IntStream.rangeClosed(1, 16).mapToObj(index -> "keyword-" + index).toList(),
                List.of());

        var fact = VisualRulebookCatalogPolicy.toPageFact(dense);

        assertThat(fact.pageNumber()).isEqualTo(3);
        assertThat(fact.factualSummary()).isEqualTo(dense.factualSummary());
        assertThat(fact.keywords()).containsExactlyElementsOf(dense.keywords());
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

    private static PageSummary summary(VisualAnchor anchor) {
        return new PageSummary(1, "VISIBLE TEXT", "Visible page facts.", List.of("reference"), List.of(anchor));
    }

    private static PageFact pageFact(int schemaVersion, String factualSummary, boolean complete) {
        return new PageFact(
                1,
                "TURN",
                factualSummary,
                List.of("turn"),
                List.of(),
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

}
