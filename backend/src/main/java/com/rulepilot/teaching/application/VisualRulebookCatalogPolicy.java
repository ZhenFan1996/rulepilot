package com.rulepilot.teaching.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Page-local visual observations; no model-authored completeness flag controls whether a page remains usable. */
final class VisualRulebookCatalogPolicy {

    static final String VISUAL_CATALOG_PREFIX = "[Visual page evidence; verify against page image]";

    private VisualRulebookCatalogPolicy() {}

    static Set<Integer> missingPages(Set<Integer> requestedPages, List<PageFact> cached) {
        LinkedHashSet<Integer> missing = new LinkedHashSet<>(requestedPages);
        cached.stream()
                .filter(VisualRulebookCatalogPolicy::hasReusablePageObservation)
                .map(PageFact::pageNumber)
                .forEach(missing::remove);
        return Set.copyOf(missing);
    }

    static boolean hasReusablePageObservation(PageFact fact) {
        return fact != null && fact.schemaVersion() == PageFact.CURRENT_SCHEMA_VERSION;
    }

    static Set<Integer> anchorlessPages(List<PageFact> cached) {
        return cached.stream()
                .filter(fact -> fact.visualAnchors().isEmpty())
                .map(PageFact::pageNumber)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static List<PageFact> mergeFreshFacts(List<PageFact> cached, List<PageFact> fresh) {
        return mergeByPage(cached, fresh, false);
    }

    static List<PageFact> backfillAnchors(List<PageFact> cached, List<PageFact> fresh) {
        return mergeByPage(cached, fresh, true);
    }

    private static List<PageFact> mergeByPage(List<PageFact> cached, List<PageFact> fresh, boolean retainExistingText) {
        Map<Integer, PageFact> merged = cached.stream().collect(Collectors.toMap(
                PageFact::pageNumber, Function.identity(), (first, ignored) -> first, LinkedHashMap::new));
        for (PageFact observation : fresh) {
            merged.merge(observation.pageNumber(), observation, (existing, supplied) -> new PageFact(
                    supplied.pageNumber(),
                    retainExistingText ? existing.printedTerms() : supplied.printedTerms(),
                    retainExistingText ? existing.factualSummary() : supplied.factualSummary(),
                    retainExistingText ? existing.keywords() : supplied.keywords(),
                    supplied.visualAnchors().isEmpty() ? existing.visualAnchors() : supplied.visualAnchors(),
                    Math.max(existing.schemaVersion(), supplied.schemaVersion()),
                    retainExistingText ? existing.ruleGroupFacts() : supplied.ruleGroupFacts()));
        }
        return merged.values().stream().sorted(Comparator.comparingInt(PageFact::pageNumber)).toList();
    }

    static PageFact toPageFact(PageSummary summary) {
        return new PageFact(
                summary.pageNumber(),
                summary.printedTerms(),
                summary.factualSummary(),
                summary.keywords(),
                summary.visualAnchors(),
                PageFact.CURRENT_SCHEMA_VERSION,
                summary.ruleGroupFacts());
    }

    static List<PageInput> pageInputs(List<DocumentProcessing.PageView> documentPages, List<PageFact> facts) {
        Map<Integer, PageFact> byPage = facts.stream().collect(Collectors.toMap(
                PageFact::pageNumber, Function.identity(), (first, ignored) -> first));
        return documentPages.stream().map(page -> pageInput(page.pageNumber(), byPage.get(page.pageNumber()))).toList();
    }

    static List<PageInput> appendFactsToPageInputs(List<PageInput> pages, List<PageFact> facts) {
        if (pages == null || pages.isEmpty() || facts == null || facts.isEmpty()) {
            return pages == null ? List.of() : List.copyOf(pages);
        }
        Map<Integer, PageFact> byPage = facts.stream()
                .filter(VisualRulebookCatalogPolicy::hasReusablePageObservation)
                .collect(Collectors.toMap(PageFact::pageNumber, Function.identity(), (first, ignored) -> first));
        return pages.stream().map(page -> {
            PageFact fact = byPage.get(page.pageNumber());
            if (fact == null) return page;
            PageInput observed = pageInput(page.pageNumber(), fact);
            return new PageInput(
                    page.pageNumber(),
                    page.text() + "\n\n" + observed.text(),
                    page.available() && observed.available());
        }).toList();
    }

    static List<List<Integer>> singlePageBatches(List<Integer> pages) {
        return pages.stream().map(List::of).toList();
    }

    static List<List<Integer>> teachingStartupBatches(List<Integer> pages) {
        return singlePageBatches(pages);
    }

    static PageSummary teachingStartupFact(PageSummary summary) {
        return withoutRetiredMetadata(summary);
    }

    static PageSummary mergePersistedPageObservation(PageSummary existing, PageSummary observation) {
        if (existing.pageNumber() != observation.pageNumber()) {
            throw new IllegalArgumentException("visual page observation does not match the persisted page");
        }
        LinkedHashMap<String, RuleGroupFact> facts = new LinkedHashMap<>();
        Stream.concat(existing.ruleGroupFacts().stream(), observation.ruleGroupFacts().stream())
                .forEach(fact -> facts.putIfAbsent(fact.identifier() + "\u0000" + fact.fact(), fact));
        PageSummary merged = new PageSummary(
                existing.pageNumber(),
                mergeText(existing.printedTerms(), observation.printedTerms(), "; "),
                mergeText(existing.factualSummary(), observation.factualSummary(), "\n"),
                Stream.concat(existing.keywords().stream(), observation.keywords().stream()).distinct().toList(),
                observation.visualAnchors().isEmpty() ? existing.visualAnchors() : observation.visualAnchors(),
                List.copyOf(facts.values()));
        return withoutRetiredMetadata(merged);
    }

    static PageFact mergePersistedPageFact(PageFact existing, PageFact observation) {
        if (existing.pageNumber() != observation.pageNumber()) {
            throw new IllegalArgumentException("visual page fact does not match the persisted page");
        }
        return mergeByPage(List.of(existing), List.of(observation), false).getFirst();
    }

    private static PageSummary withoutRetiredMetadata(PageSummary summary) {
        return new PageSummary(
                summary.pageNumber(),
                summary.printedTerms(),
                summary.factualSummary(),
                summary.keywords(),
                summary.visualAnchors(),
                summary.ruleGroupFacts());
    }

    private static String mergeText(String first, String second, String separator) {
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
                    VISUAL_CATALOG_PREFIX + "\nNo readable page observation is available.",
                    false);
        }
        String ruleGroups = fact.ruleGroupFacts().stream()
                .map(group -> group.identifier() + ": [" + group.label() + "] " + group.fact())
                .collect(Collectors.joining("\n"));
        return new PageInput(
                pageNumber,
                VISUAL_CATALOG_PREFIX
                        + "\nPrinted terms: " + fact.printedTerms()
                        + "\nVisible facts: " + fact.factualSummary()
                        + "\nKeywords: " + String.join(", ", fact.keywords())
                        + (ruleGroups.isBlank() ? "" : "\nPage-local rule facts:\n" + ruleGroups),
                true);
    }
}
