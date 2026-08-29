package com.rulepilot.teaching.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.teaching.TeachingOutlineModel.PageLedgerState;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.VisualSourceRuleGroupLedger;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.VisualQuantityObservation;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Deterministic visual-page catalog transformations; model and storage work stay in the caller. */
final class VisualRulebookCatalogPolicy {

    static final String VISUAL_CATALOG_PREFIX = "[Visual page catalog; verify against page image]";

    private VisualRulebookCatalogPolicy() {}

    static Set<Integer> missingPages(Set<Integer> requestedPages, List<PageFact> cached) {
        LinkedHashSet<Integer> missing = new LinkedHashSet<>(requestedPages);
        cached.stream()
                .filter(VisualRulebookCatalogPolicy::hasReusableCompleteRuleLedger)
                .map(PageFact::pageNumber)
                .forEach(missing::remove);
        return Collections.unmodifiableSet(missing);
    }

    static boolean hasReusableCompleteRuleLedger(PageFact fact) {
        return fact.schemaVersion() == PageFact.CURRENT_SCHEMA_VERSION
                && fact.ruleGroupInventoryComplete()
                && hasRuleGroupFactBindings(fact.ruleGroupIdentifiers(), fact.ruleGroupFacts());
    }

    static Set<Integer> anchorlessPages(List<PageFact> cached) {
        return cached.stream()
                .filter(fact -> fact.visualAnchors().isEmpty())
                .map(PageFact::pageNumber)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static List<PageFact> mergeFreshFacts(List<PageFact> cached, List<PageFact> fresh) {
        return Stream.concat(cached.stream(), fresh.stream())
                .collect(Collectors.toMap(
                        PageFact::pageNumber,
                        Function.identity(),
                        (existing, ignored) -> existing,
                        LinkedHashMap::new))
                .values().stream()
                .sorted(Comparator.comparingInt(PageFact::pageNumber))
                .toList();
    }

    static List<PageFact> backfillAnchors(List<PageFact> cached, List<PageFact> fresh) {
        Map<Integer, PageFact> freshByPage = fresh.stream().collect(Collectors.toMap(
                PageFact::pageNumber, Function.identity(), (first, ignored) -> first));
        List<PageFact> retained = cached.stream()
                .map(existing -> {
                    PageFact refreshed = freshByPage.remove(existing.pageNumber());
                    if (refreshed == null) return existing;
                    if (refreshed.schemaVersion() > existing.schemaVersion()) return refreshed;
                    if (hasReusableCompleteRuleLedger(refreshed) && !hasReusableCompleteRuleLedger(existing)) {
                        return new PageFact(
                                refreshed.pageNumber(),
                                refreshed.printedTerms(),
                                refreshed.factualSummary(),
                                refreshed.keywords(),
                                refreshed.visualAnchors().isEmpty()
                                        ? existing.visualAnchors()
                                        : refreshed.visualAnchors(),
                                refreshed.schemaVersion(),
                                refreshed.sourceDependencies(),
                                refreshed.ruleGroupIdentifiers(),
                                true,
                                refreshed.ruleGroupFacts());
                    }
                    if (refreshed.visualAnchors().isEmpty()) return existing;
                    return new PageFact(
                            existing.pageNumber(),
                            existing.printedTerms(),
                            existing.factualSummary(),
                            existing.keywords(),
                            refreshed.visualAnchors().isEmpty()
                                    ? existing.visualAnchors()
                                    : refreshed.visualAnchors(),
                            existing.schemaVersion(),
                            existing.sourceDependencies(),
                            existing.ruleGroupIdentifiers(),
                            existing.ruleGroupInventoryComplete(),
                            existing.ruleGroupFacts());
                })
                .toList();
        return Stream.concat(retained.stream(), freshByPage.values().stream())
                .sorted(Comparator.comparingInt(PageFact::pageNumber))
                .toList();
    }

    /**
     * Narrows the model-facing summary contract to the durable fact contract. The model may return up to sixteen
     * bounded retrieval keywords and the durable ledger preserves them intact. A valid page must not be discarded
     * merely because its non-authoritative retrieval metadata is sparse or dense.
     */
    static PageFact toPageFact(
            com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary summary) {
        return new PageFact(
                summary.pageNumber(),
                summary.printedTerms(),
                VisualQuantityObservation.appendEvidence(
                        summary.factualSummary(), summary.quantityObservations()),
                summary.keywords().stream().distinct().toList(),
                summary.visualAnchors(),
                PageFact.CURRENT_SCHEMA_VERSION,
                summary.sourceDependencies(),
                summary.ruleGroupIdentifiers(),
                summary.ruleGroupInventoryComplete(),
                summary.ruleGroupFacts());
    }

    static List<PageInput> pageInputs(List<DocumentProcessing.PageView> documentPages, List<PageFact> facts) {
        Map<Integer, PageFact> factsByPage = facts.stream().collect(Collectors.toMap(
                PageFact::pageNumber, Function.identity(), (first, duplicate) -> first));
        return documentPages.stream()
                .map(page -> pageInput(page.pageNumber(), factsByPage.get(page.pageNumber())))
                .toList();
    }

    static List<PageInput> appendFactsToPageInputs(List<PageInput> pages, List<PageFact> facts) {
        if (pages == null || pages.isEmpty() || facts == null || facts.isEmpty()) {
            return pages == null ? List.of() : List.copyOf(pages);
        }
        Map<Integer, PageFact> factsByPage = facts.stream()
                .filter(VisualRulebookCatalogPolicy::hasReusableCompleteRuleLedger)
                .collect(Collectors.toMap(PageFact::pageNumber, Function.identity(), (first, ignored) -> first));
        return pages.stream()
                .map(page -> {
                    PageFact fact = factsByPage.get(page.pageNumber());
                    if (fact == null) return page;
                    return new PageInput(
                            page.pageNumber(),
                            page.text() + "\n\n" + pageInput(page.pageNumber(), fact).text(),
                            Stream.concat(page.sourceDependencies().stream(), fact.sourceDependencies().stream())
                                    .distinct()
                                    .toList(),
                            fact.ruleGroupIdentifiers(),
                            fact.ruleGroupInventoryComplete(),
                            fact.ruleGroupFacts(),
                            page.pageLedgerState() == PageLedgerState.LEGACY_TEXT
                                    ? PageLedgerState.LEGACY_TEXT
                                    : PageLedgerState.VISUAL_EXACT_COMPLETE);
                })
                .toList();
    }

    /**
     * A response is accepted only when it covers every supplied image. Keep pages independent so a partial visual
     * response can never discard a legend or a gameplay page that has already been read successfully.
     */
    static List<List<Integer>> singlePageBatches(List<Integer> pages) {
        return pages.stream().map(List::of).toList();
    }

    /**
     * The Teaching ledger is deliberately complete, not a thumbnail summary: one dense page may contain sixteen
     * rule groups plus quantity observations. Keep that output budget page-local from the first request. Sending the
     * transport's eight-image maximum here made a valid provider response exceed its completion budget, after which
     * the caller had to repeat the same work as single-page recovery requests. Page-local calls are independently
     * retryable, preserve every successful page, and make real progress visible while the rulebook is read.
     */
    static List<List<Integer>> teachingStartupBatches(List<Integer> pages) {
        return singlePageBatches(pages);
    }

    /** The bounded Teaching ledger keeps only page-owned source facts; spatial enrichment remains independent. */
    static com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary teachingStartupFact(
            com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary summary) {
        if (summary.ruleGroupInventoryComplete()) {
            validateRuleGroupFactBindings(summary.ruleGroupIdentifiers(), summary.ruleGroupFacts());
        }
        return new com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary(
                summary.pageNumber(),
                summary.printedTerms(),
                summary.factualSummary(),
                summary.keywords(),
                List.of(),
                summary.sourceDependencies(),
                summary.ruleGroupIdentifiers(),
                summary.ruleGroupInventoryComplete(),
                summary.quantityObservations(),
                summary.ruleGroupFacts());
    }

    /**
     * A later application-validated complete page observation owns the canonical rule ledger for that immutable
     * source page. It replaces a stale or partial ledger while retaining independently completed visual localization.
     * Incomplete observations remain supplemental and therefore cannot promote the persisted completeness marker.
     */
    static com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary mergePersistedPageObservation(
            com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary existing,
            com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary observation) {
        if (!observation.ruleGroupInventoryComplete()) {
            if (existing.pageNumber() != observation.pageNumber()) {
                throw new IllegalArgumentException("persisted visual page observation does not match its existing page");
            }
            List<String> ruleGroups = existing.ruleGroupIdentifiers();
            return new com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary(
                    existing.pageNumber(),
                    existing.printedTerms(),
                    mergeTextBlocks(existing.factualSummary(), observation.factualSummary(), "\n"),
                    Stream.concat(existing.keywords().stream(), observation.keywords().stream()).distinct().toList(),
                    existing.visualAnchors().isEmpty() ? observation.visualAnchors() : existing.visualAnchors(),
                    Stream.concat(existing.sourceDependencies().stream(), observation.sourceDependencies().stream())
                            .distinct()
                            .toList(),
                    ruleGroups,
                    existing.ruleGroupInventoryComplete(),
                    compatibleQuantityObservations(ruleGroups, existing, observation),
                    existing.ruleGroupFacts());
        }
        if (existing.pageNumber() != observation.pageNumber()) {
            throw new IllegalArgumentException("persisted visual page observation does not match its existing page");
        }
        validateRuleGroupFactBindings(observation.ruleGroupIdentifiers(), observation.ruleGroupFacts());
        return new com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary(
                observation.pageNumber(),
                observation.printedTerms(),
                observation.factualSummary(),
                observation.keywords(),
                observation.visualAnchors().isEmpty()
                        ? existing.visualAnchors()
                        : observation.visualAnchors(),
                observation.sourceDependencies(),
                observation.ruleGroupIdentifiers(),
                true,
                observation.quantityObservations(),
                observation.ruleGroupFacts());
    }

    static PageFact mergePersistedPageFact(PageFact existing, PageFact observation) {
        if (existing.pageNumber() != observation.pageNumber()) {
            throw new IllegalArgumentException("persisted visual page fact does not match its existing page");
        }
        if (observation.schemaVersion() != PageFact.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("new visual page fact does not use the current schema");
        }
        if (existing.schemaVersion() != PageFact.CURRENT_SCHEMA_VERSION) return observation;
        return toPageFact(mergePersistedPageObservation(pageSummary(existing), pageSummary(observation)));
    }

    private static com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary pageSummary(PageFact fact) {
        return new com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary(
                fact.pageNumber(),
                fact.printedTerms(),
                fact.factualSummary(),
                fact.keywords(),
                fact.visualAnchors(),
                fact.sourceDependencies(),
                fact.ruleGroupIdentifiers(),
                fact.ruleGroupInventoryComplete(),
                List.of(),
                fact.ruleGroupFacts());
    }

    private static List<VisualQuantityObservation> compatibleQuantityObservations(
            List<String> ruleGroups,
            com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary first,
            com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary second) {
        Set<String> identities = Set.copyOf(ruleGroups);
        return Stream.concat(first.quantityObservations().stream(), second.quantityObservations().stream())
                .filter(observation -> identities.contains(observation.ruleGroupIdentifier()))
                .distinct()
                .toList();
    }

    static void validateRuleGroupFactBindings(
            List<String> identifiers,
            List<com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact> facts) {
        if (hasRuleGroupFactBindings(identifiers, facts)) return;
        for (String identifier : identifiers) {
            if (!VisualSourceRuleGroupLedger.hasExactFactBinding(identifier, facts)) {
                throw new IllegalArgumentException(
                        "complete visual page lost a rule-group fact while merging supplemental evidence: "
                                + identifier);
            }
        }
    }

    private static boolean hasRuleGroupFactBindings(
            List<String> identifiers,
            List<com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact> facts) {
        return VisualSourceRuleGroupLedger.hasExactFactBindings(identifiers, facts);
    }

    private static String mergeTextBlocks(String first, String second, String separator) {
        String left = first == null ? "" : first.strip();
        String right = second == null ? "" : second.strip();
        if (left.isBlank()) return right;
        if (right.isBlank() || left.equals(right)) return left;
        return left + separator + right;
    }

    private static PageInput pageInput(int pageNumber, PageFact fact) {
        if (fact == null) {
            return new PageInput(
                    pageNumber,
                    VISUAL_CATALOG_PREFIX
                            + "\nPrinted terms: unavailable because visual interpretation did not finish."
                            + "\nVisible facts: No factual visual claim is available for this page. Keep its source binding"
                            + " and verify the original page image before teaching any detail."
                            + "\nKeywords: visual source page "
                            + pageNumber
                            + ", incomplete visual catalog",
                    List.of(),
                    List.of(),
                    false,
                    List.of(),
                    PageLedgerState.VISUAL_EXPLICITLY_UNAVAILABLE);
        }
        boolean exactCompleteLedger = hasReusableCompleteRuleLedger(fact);
        return new PageInput(
                pageNumber,
                VISUAL_CATALOG_PREFIX
                        + "\nPrinted terms: "
                        + fact.printedTerms()
                        + "\nVisible facts: "
                        + fact.factualSummary()
                        + "\nKeywords: "
                        + String.join(", ", fact.keywords()),
                fact.sourceDependencies(),
                fact.ruleGroupIdentifiers(),
                exactCompleteLedger,
                fact.ruleGroupFacts(),
                exactCompleteLedger
                        ? PageLedgerState.VISUAL_EXACT_COMPLETE
                        : PageLedgerState.VISUAL_PARTIAL);
    }
}
