package com.rulepilot.teaching.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
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
                .filter(fact -> fact.schemaVersion() == PageFact.CURRENT_SCHEMA_VERSION)
                .map(PageFact::pageNumber)
                .forEach(missing::remove);
        return Collections.unmodifiableSet(missing);
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
                            existing.schemaVersion());
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
                summary.factualSummary(),
                summary.keywords().stream().distinct().limit(12).toList(),
                summary.visualAnchors(),
                IconEvidencePolicy.sanitize(summary.iconOccurrences()),
                summary.iconInventoryComplete(),
                PageFact.CURRENT_SCHEMA_VERSION);
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
        Map<Integer, PageFact> factsByPage = facts.stream().collect(Collectors.toMap(
                PageFact::pageNumber, Function.identity(), (first, ignored) -> first));
        return pages.stream()
                .map(page -> {
                    PageFact fact = factsByPage.get(page.pageNumber());
                    if (fact == null) return page;
                    return new PageInput(
                            page.pageNumber(),
                            page.text() + "\n\n" + pageInput(page.pageNumber(), fact).text());
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
     * The startup contract contains only bounded page facts, so three labeled images fit in one request without the
     * large tail and truncation risk of the complete icon-inventory contract. Requests remain serial in production.
     */
    static List<List<Integer>> teachingStartupBatches(List<Integer> pages) {
        java.util.ArrayList<List<Integer>> batches = new java.util.ArrayList<>();
        for (int start = 0; start < pages.size(); start += 3) {
            batches.add(List.copyOf(pages.subList(start, Math.min(start + 3, pages.size()))));
        }
        return List.copyOf(batches);
    }

    /** Model output cannot promote the bounded Teaching ledger into a completed icon or spatial audit. */
    static com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary teachingStartupFact(
            com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary summary) {
        return new com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary(
                summary.pageNumber(),
                summary.printedTerms(),
                summary.factualSummary(),
                summary.keywords(),
                List.of(),
                List.of(),
                false);
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
        Map<String, IconOccurrence> icons = new LinkedHashMap<>();
        Stream.concat(fullPage.iconOccurrences().stream(), tileAudit.iconOccurrences().stream())
                .forEach(icon -> icons.merge(
                        normalizedIdentity(icon.groupKey()), icon, VisualRulebookCatalogPolicy::preferIconEvidence));
        boolean withinLimit = icons.size() <= 32;
        return new com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary(
                fullPage.pageNumber(),
                mergeBoundedLines(fullPage.printedTerms(), tileAudit.printedTerms(), 1_600, "; "),
                // The tile pass exists because dense row/cell text was not reliably readable at full-page scale.
                // Preserve its higher-resolution atomic facts first, then use remaining space for page-level context.
                mergeBoundedLines(tileAudit.factualSummary(), fullPage.factualSummary(), 4_000, "\n"),
                Stream.concat(fullPage.keywords().stream(), tileAudit.keywords().stream())
                        .distinct()
                        .limit(12)
                        .toList(),
                fullPage.visualAnchors().isEmpty() ? tileAudit.visualAnchors() : fullPage.visualAnchors(),
                icons.values().stream().limit(32).toList(),
                tileAudit.iconInventoryComplete() && withinLimit);
    }

    private static String mergeBoundedLines(String first, String second, int maxLength, String separator) {
        LinkedHashSet<String> values = Stream.of(first, second)
                .filter(java.util.Objects::nonNull)
                .flatMap(value -> java.util.Arrays.stream(value.split(separator.equals("\n") ? "\\R+" : ";")))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        StringBuilder merged = new StringBuilder();
        for (String value : values) {
            int remaining = maxLength - merged.length() - (merged.isEmpty() ? 0 : separator.length());
            if (remaining <= 0) break;
            if (!merged.isEmpty()) merged.append(separator);
            merged.append(value, 0, Math.min(value.length(), remaining));
        }
        return merged.toString();
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
                        + String.join(", ", fact.keywords()));
    }
}
