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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Deterministic visual-page catalog transformations; model and storage work stay in the caller. */
final class VisualRulebookCatalogPolicy {

    private static final Pattern ICON_BEARING_ANCHOR = Pattern.compile(
            "(?iu)(?:\\b(?:icon|symbol|legend|key|token|marker|badge|scor(?:e|ing)|resource|pattern)\\b"
                    + "|图标|符号|图例|标记|代币|计分|得分|资源|花纹|图案)");
    private static final Pattern NON_ICON_SUBJECT = Pattern.compile(
            "(?iu)(?:\\b(?:example|illustration|photograph|whole\\s+(?:card|tile|token)|"
                    + "(?:card|tile|token))\\b$|示例|插图|照片|整张卡|整个(?:图块|瓦片|代币)|(?:图块|瓦片|代币)$)");
    private static final Pattern NON_GAMEPLAY_IDENTITY = Pattern.compile(
            "(?iu)(?:\\b(?:logo|publisher|compliance|certification|trademark|safety\\s+warning|"
                    + "age\\s+restriction)\\b|徽标|商标|出版商|合规|认证标志|年龄限制|安全警告)");
    private static final Pattern NON_ICON_DESCRIPTION = Pattern.compile(
            "(?iu)(?:\\b(?:uppercase|lowercase|letters?|numbers?|numeral|printed\\s+text|text\\s+(?:label|mention)|"
                    + "whole\\s+(?:card|tile|token)|"
                    + "(?:hexagonal|connected|component)\\s+tiles?|empty\\s+circle|"
                    + "(?:beige|colored)\\s+background)\\b"
                    + "|\\btext\\s+(?:labels?|mention(?:s|ing)?)\\b"
                    + "|字母|数字|文本|瓷砖|板块|图块|瓦片)");

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
     * Retry an apparently empty page at higher visual resolution only when the full-page catalog found a region
     * whose own label says it is icon-bearing. A cover illustration, title block, or ordinary setup prose is not
     * enough evidence to spend four more provider calls.
     */
    static boolean needsIconTileFallback(
            com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary summary) {
        if (!summary.iconOccurrences().isEmpty() || summary.visualAnchors().isEmpty()) return false;
        return summary.visualAnchors().stream()
                .anyMatch(anchor -> ICON_BEARING_ANCHOR
                        .matcher(anchor.kind() + " " + anchor.label())
                        .find());
    }

    /**
     * A glossary crop represents one compact symbol, never a page callout or a component-sized region. This
     * geometry and vocabulary gate is intentionally game-independent and runs after model localization.
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
        String identity = (icon.groupKey() + " " + icon.name()).strip();
        if (icon.name().matches("(?iu)[a-z0-9]") || NON_ICON_DESCRIPTION.matcher(icon.visualDescription()).find()) {
            return false;
        }
        return !NON_ICON_SUBJECT.matcher(identity).find()
                && !NON_GAMEPLAY_IDENTITY.matcher(identity).find();
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
