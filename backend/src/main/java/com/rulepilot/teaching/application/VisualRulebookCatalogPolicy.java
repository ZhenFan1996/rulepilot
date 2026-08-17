package com.rulepilot.teaching.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.VisualSourceRuleGroupLedger;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
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
                && hasRuleGroupFactBindings(fact.ruleGroupIdentifiers(), fact.factualSummary());
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
                                refreshed.iconOccurrences().isEmpty()
                                        ? existing.iconOccurrences()
                                        : refreshed.iconOccurrences(),
                                existing.iconInventoryComplete() || refreshed.iconInventoryComplete(),
                                refreshed.schemaVersion(),
                                refreshed.sourceDependencies(),
                                refreshed.ruleGroupIdentifiers(),
                                true);
                    }
                    if (refreshed.visualAnchors().isEmpty()
                            && refreshed.iconOccurrences().isEmpty()
                            && !refreshed.iconInventoryComplete()) return existing;
                    return new PageFact(
                            existing.pageNumber(),
                            existing.printedTerms(),
                            existing.factualSummary(),
                            existing.keywords(),
                            refreshed.visualAnchors().isEmpty()
                                    ? existing.visualAnchors()
                                    : refreshed.visualAnchors(),
                            refreshed.iconOccurrences().isEmpty()
                                    ? existing.iconOccurrences()
                                    : refreshed.iconOccurrences(),
                            existing.iconInventoryComplete() || refreshed.iconInventoryComplete(),
                            existing.schemaVersion(),
                            existing.sourceDependencies(),
                            existing.ruleGroupIdentifiers(),
                            existing.ruleGroupInventoryComplete());
                })
                .toList();
        return Stream.concat(retained.stream(), freshByPage.values().stream())
                .sorted(Comparator.comparingInt(PageFact::pageNumber))
                .toList();
    }

    /**
     * Narrows the model-facing summary contract to the durable fact contract. The model may return up to sixteen
     * retrieval keywords so parsing can preserve a dense page, while the stored ledger deliberately indexes twelve.
     * A valid page must not be discarded merely because it used the larger upstream allowance.
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
                IconEvidencePolicy.sanitize(summary.iconOccurrences()),
                summary.iconInventoryComplete(),
                PageFact.CURRENT_SCHEMA_VERSION,
                summary.sourceDependencies(),
                summary.ruleGroupIdentifiers(),
                summary.ruleGroupInventoryComplete());
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
                            fact.ruleGroupInventoryComplete());
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

    /** Model output cannot promote the bounded Teaching ledger into a completed icon or spatial audit. */
    static com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary teachingStartupFact(
            com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary summary) {
        if (summary.ruleGroupInventoryComplete()) {
            validateRuleGroupFactBindings(summary.ruleGroupIdentifiers(), summary.factualSummary());
        }
        return new com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary(
                summary.pageNumber(),
                summary.printedTerms(),
                summary.factualSummary(),
                summary.keywords(),
                List.of(),
                List.of(),
                false,
                summary.sourceDependencies(),
                summary.ruleGroupIdentifiers(),
                summary.ruleGroupInventoryComplete(),
                summary.quantityObservations());
    }

    /**
     * Audit an icon-bearing page at higher visual resolution when the full-page pass is empty despite an icon-bearing
     * anchor, or when a label-dense page contains multiple proposed symbols. The latter catches overconfident partial
     * inventories without relying on a particular game's vocabulary.
     */
    static boolean needsIconTileFallback(
            com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary summary) {
        long visibleLabels = summary.printedTerms().lines()
                .flatMap(line -> java.util.Arrays.stream(line.split(";")))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .distinct()
                .count();
        if (visibleLabels >= 8 || summary.iconOccurrences().size() >= 8) return true;
        return summary.iconOccurrences().isEmpty() && !summary.visualAnchors().isEmpty();
    }

    /**
     * A tile audit is complementary evidence, not a replacement for the full-page pass. Keep every independently
     * observed identity and atomic row fact, prefer a grounded definition or independently verified label on
     * collisions, and let the four-tile result decide whether the audited inventory is complete.
     */
    static com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary mergeIconTileAudit(
            com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary fullPage,
            com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary tileAudit) {
        if (fullPage.pageNumber() != tileAudit.pageNumber()) {
            throw new IllegalArgumentException("icon tile audit page does not match its full-page summary");
        }
        Map<String, IconOccurrence> icons = mergedIcons(fullPage, tileAudit);
        // The four-tile pass is authorized to complete the icon inventory only. It has no whole-page rule-group
        // contract, so even an overreaching model response cannot promote partial rule observations to completeness.
        List<String> ruleGroups = fullPage.ruleGroupIdentifiers();
        boolean ruleGroupsComplete = fullPage.ruleGroupInventoryComplete();
        boolean fullPageOwnsCompleteFacts = ruleGroupsComplete
                && hasRuleGroupFactBindings(ruleGroups, fullPage.factualSummary());
        boolean laterObservationRepairsCompleteFacts = ruleGroupsComplete
                && !fullPageOwnsCompleteFacts
                && hasRuleGroupFactBindings(ruleGroups, tileAudit.factualSummary());
        String mergedFacts = fullPageOwnsCompleteFacts || (ruleGroupsComplete && !laterObservationRepairsCompleteFacts)
                ? mergeLines(fullPage.factualSummary(), tileAudit.factualSummary(), "\n")
                : mergeLines(tileAudit.factualSummary(), fullPage.factualSummary(), "\n");
        if (ruleGroupsComplete) {
            validateRuleGroupFactBindings(ruleGroups, mergedFacts);
        }
        return new com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary(
                fullPage.pageNumber(),
                mergeLines(fullPage.printedTerms(), tileAudit.printedTerms(), "; "),
                mergedFacts,
                Stream.concat(fullPage.keywords().stream(), tileAudit.keywords().stream())
                        .distinct()
                        .toList(),
                fullPage.visualAnchors().isEmpty() ? tileAudit.visualAnchors() : fullPage.visualAnchors(),
                List.copyOf(icons.values()),
                tileAudit.iconInventoryComplete(),
                Stream.concat(fullPage.sourceDependencies().stream(), tileAudit.sourceDependencies().stream())
                        .distinct()
                        .toList(),
                ruleGroups,
                ruleGroupsComplete,
                compatibleQuantityObservations(ruleGroups, fullPage, tileAudit));
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
            return mergeIconTileAudit(existing, observation);
        }
        if (existing.pageNumber() != observation.pageNumber()) {
            throw new IllegalArgumentException("persisted visual page observation does not match its existing page");
        }
        validateRuleGroupFactBindings(observation.ruleGroupIdentifiers(), observation.factualSummary());
        Map<String, IconOccurrence> icons = mergedIcons(observation, existing);
        return new com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary(
                observation.pageNumber(),
                observation.printedTerms(),
                observation.factualSummary(),
                observation.keywords(),
                observation.visualAnchors().isEmpty()
                        ? existing.visualAnchors()
                        : observation.visualAnchors(),
                List.copyOf(icons.values()),
                observation.iconInventoryComplete() || existing.iconInventoryComplete(),
                observation.sourceDependencies(),
                observation.ruleGroupIdentifiers(),
                true,
                observation.quantityObservations());
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
                fact.iconOccurrences(),
                fact.iconInventoryComplete(),
                fact.sourceDependencies(),
                fact.ruleGroupIdentifiers(),
                fact.ruleGroupInventoryComplete());
    }

    private static List<VisualQuantityObservation> compatibleQuantityObservations(
            List<String> ruleGroups,
            com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary first,
            com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary second) {
        Set<String> identities = ruleGroups.stream()
                .map(VisualSourceRuleGroupLedger::identity)
                .collect(Collectors.toSet());
        return Stream.concat(first.quantityObservations().stream(), second.quantityObservations().stream())
                .filter(observation -> identities.contains(
                        VisualSourceRuleGroupLedger.identity(observation.ruleGroupIdentifier())))
                .distinct()
                .toList();
    }

    private static Map<String, IconOccurrence> mergedIcons(
            com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary first,
            com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary second) {
        Map<String, IconOccurrence> icons = new LinkedHashMap<>();
        Stream.concat(first.iconOccurrences().stream(), second.iconOccurrences().stream())
                .forEach(icon -> icons.merge(
                        normalizedIdentity(icon.groupKey()), icon, VisualRulebookCatalogPolicy::preferIconEvidence));
        return icons;
    }

    static void validateRuleGroupFactBindings(List<String> identifiers, String factualSummary) {
        if (hasRuleGroupFactBindings(identifiers, factualSummary)) return;
        for (String identifier : identifiers) {
            if (!VisualSourceRuleGroupLedger.hasExactFactBinding(identifier, factualSummary)) {
                throw new IllegalArgumentException(
                        "complete visual page lost a rule-group fact while merging supplemental evidence: "
                                + identifier);
            }
        }
    }

    private static boolean hasRuleGroupFactBindings(List<String> identifiers, String factualSummary) {
        return VisualSourceRuleGroupLedger.hasExactFactBindings(identifiers, factualSummary);
    }

    private static String mergeLines(String first, String second, String separator) {
        Stream<String> values = Stream.of(first, second)
                .filter(java.util.Objects::nonNull)
                .flatMap(value -> separator.equals("; ")
                        ? java.util.Arrays.stream(value.split(";"))
                        : Stream.of(value))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .distinct();
        return values.collect(java.util.stream.Collectors.joining(separator));
    }

    /**
     * A glossary crop must be a compact, bounded region rather than a page- or component-sized rectangle. Semantic
     * classification belongs to the structured visual model; this gate checks geometry and a non-trivial label only.
     */
    static boolean publishableLocalizedIcon(
            IconOccurrence icon, int x, int y, int width, int height) {
        if (x < 0 || y < 0 || width < 12 || height < 12 || x + width > 1_000 || y + height > 1_000) return false;
        int longSide = Math.max(width, height);
        int shortSide = Math.min(width, height);
        long area = (long) width * height;
        if (longSide > 180 || area > 15_000L || (longSide >= 150 && area >= 12_000L)
                || longSide > shortSide * 4) {
            return false;
        }
        return icon.name().codePointCount(0, icon.name().length()) >= 2;
    }

    private static IconOccurrence preferIconEvidence(IconOccurrence first, IconOccurrence second) {
        if (first.meaningStatus() != second.meaningStatus()) {
            return meaningRank(first.meaningStatus()) > meaningRank(second.meaningStatus()) ? first : second;
        }
        if (first.verifiedVisualLabel().isBlank() && !second.verifiedVisualLabel().isBlank()) return second;
        return first;
    }

    private static int meaningRank(
            com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus status) {
        return switch (status) {
            case EXPLICIT -> 2;
            case IDENTIFIED -> 1;
            case UNEXPLAINED -> 0;
        };
    }

    private static String normalizedIdentity(String value) {
        return value.strip()
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{Zs}]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static PageInput pageInput(int pageNumber, PageFact fact) {
        if (fact == null) {
            return new PageInput(
                    pageNumber,
                    TeachingOutlineRevisionPolicy.VISUAL_CATALOG_PREFIX
                            + "\nPrinted terms: unavailable because visual interpretation did not finish."
                            + "\nVisible facts: No factual visual claim is available for this page. Keep its source binding"
                            + " and verify the original page image before teaching any detail."
                            + "\nKeywords: visual source page "
                            + pageNumber
                            + ", incomplete visual catalog");
        }
        return new PageInput(
                pageNumber,
                TeachingOutlineRevisionPolicy.VISUAL_CATALOG_PREFIX
                        + "\nPrinted terms: "
                        + fact.printedTerms()
                        + "\nVisible facts: "
                        + fact.factualSummary()
                        + "\nKeywords: "
                        + String.join(", ", fact.keywords()),
                fact.sourceDependencies(),
                fact.ruleGroupIdentifiers(),
                fact.ruleGroupInventoryComplete());
    }
}
