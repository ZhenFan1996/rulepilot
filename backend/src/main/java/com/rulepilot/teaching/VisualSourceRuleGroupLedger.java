package com.rulepilot.teaching;

import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.PageLedgerState;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Extracts page-owned rule-group identifiers already admitted by a visual page catalog. */
public final class VisualSourceRuleGroupLedger {

    private VisualSourceRuleGroupLedger() {}

    public static List<String> identifiers(PageInput page) {
        if (page == null) return List.of();
        return page.sourceRuleGroupIdentifiers();
    }

    /** Exact protocol identity. Semantic or character-normalized matching is intentionally forbidden here. */
    public static String identity(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * A completed page ledger is meaningful only when every page-owned identifier has one exact, non-empty fact.
     * A verified non-gameplay page may own an empty inventory. The relationship is normalized rather than
     * translated: document-local vocabulary remains the authority.
     */
    public static boolean hasExactFactBindings(List<String> identifiers, List<RuleGroupFact> facts) {
        if (identifiers == null || facts == null) return false;
        Set<String> expected = Set.copyOf(identifiers);
        Set<String> actual = facts.stream().map(RuleGroupFact::identifier).collect(Collectors.toSet());
        return expected.size() == identifiers.size()
                && actual.size() == facts.size()
                && expected.equals(actual);
    }

    public static boolean hasExactFactBinding(String identifier, List<RuleGroupFact> facts) {
        if (identifier == null || identifier.isBlank() || facts == null) return false;
        return facts.stream().anyMatch(fact -> identifier.equals(fact.identifier()));
    }

    /**
     * Verifies the durable page-input contract without inferring identifiers from display text. A completed
     * non-gameplay page may own an empty identifier inventory, but it still needs a non-empty factual observation.
     */
    public static boolean hasCompleteExactFactLedger(PageInput page) {
        if (page == null || !page.sourceRuleGroupInventoryComplete()) return false;
        return hasExactFactBindings(page.sourceRuleGroupIdentifiers(), page.sourceRuleGroupFacts());
    }

    /**
     * A typed visual outline may retain an incomplete page inventory when every admitted claim is still exactly
     * bound to a typed fact. Inventory completeness controls what the lesson may claim about whole-book coverage; it
     * does not erase already admitted rule anchors. Legacy text remains outside this protocol, and at least one typed
     * rule anchor is required before planning.
     */
    public static boolean supportsTypedCanonicalOutline(List<PageInput> pages) {
        return pages != null
                && !pages.isEmpty()
                && pages.stream().allMatch(VisualSourceRuleGroupLedger::hasSafeTypedPageLedger)
                && pages.stream()
                        .filter(page -> page.pageLedgerState() == PageLedgerState.VISUAL_EXACT_COMPLETE
                                || page.pageLedgerState() == PageLedgerState.VISUAL_PARTIAL)
                        .anyMatch(page -> !page.sourceRuleGroupIdentifiers().isEmpty());
    }

    private static boolean hasSafeTypedPageLedger(PageInput page) {
        if (page == null) return false;
        return switch (page.pageLedgerState()) {
            case VISUAL_EXACT_COMPLETE -> hasCompleteExactFactLedger(page);
            case VISUAL_PARTIAL -> !page.sourceRuleGroupInventoryComplete()
                    && hasExactFactBindings(page.sourceRuleGroupIdentifiers(), page.sourceRuleGroupFacts());
            case VISUAL_EXPLICITLY_UNAVAILABLE -> !page.sourceRuleGroupInventoryComplete()
                    && page.sourceDependencies().isEmpty()
                    && page.sourceRuleGroupIdentifiers().isEmpty()
                    && page.sourceRuleGroupFacts().isEmpty();
            case LEGACY_TEXT -> false;
        };
    }

    public static boolean usesTypedVisualPageProtocol(List<PageInput> pages) {
        return pages != null
                && pages.stream().anyMatch(page -> page.pageLedgerState() != PageLedgerState.LEGACY_TEXT);
    }
}
