package com.rulepilot.teaching.application;

import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.PageBlock;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** Chooses compact, document-derived regions before a vision model is asked to locate a final crop. */
@Component
public final class VisualRegionCandidateSelector {

    private static final int MAX_CANDIDATES = 4;

    public List<Candidate> select(
            RulebookUnderstanding understanding,
            Set<Integer> citedPages,
            List<String> sectionTerms) {
        return select(understanding, citedPages, sectionTerms, List.of());
    }

    /**
     * Uses durable observations from the rendered page as a retrieval hint when a rule's text and its visual example
     * use different words or languages. The hint can only rank already cited pages; the vision model must still verify
     * every selected object in the attached page image before it reaches a player.
     */
    public List<Candidate> select(
            RulebookUnderstanding understanding,
            Set<Integer> citedPages,
            List<String> sectionTerms,
            List<PageFact> visualPageFacts) {
        if (understanding == null || citedPages == null || sectionTerms == null) {
            throw new IllegalArgumentException("visual region selection input is required");
        }
        if (visualPageFacts == null) {
            throw new IllegalArgumentException("visual page facts are required");
        }
        Set<String> terms = normalizedTerms(sectionTerms);
        if (citedPages.isEmpty()) return List.of();
        var factsByPage = visualPageFacts.stream()
                .filter(fact -> citedPages.contains(fact.pageNumber()))
                .collect(java.util.stream.Collectors.toMap(
                        PageFact::pageNumber,
                        java.util.function.Function.identity(),
                        (first, ignored) -> first));
        List<ScoredBlock> citedBlocks = understanding.pageBlocks().stream()
                .filter(block -> block.role() != RulebookUnderstanding.BlockRole.FOOTER)
                .filter(block -> citedPages.contains(block.pageNumber()))
                .map(block -> new ScoredBlock(block, score(block, terms)))
                .toList();
        List<ScoredBlock> lexicalMatches = citedBlocks.stream()
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparingInt(ScoredBlock::score).reversed()
                        .thenComparing(candidate -> candidate.block().pageNumber())
                        .thenComparing(candidate -> candidate.block().readingOrder()))
                .toList();
        List<ScoredPage> visualMatches = factsByPage.values().stream()
                .map(fact -> new ScoredPage(fact.pageNumber(), score(fact, terms)))
                .filter(candidate -> candidate.score() > 0)
                .toList();
        if (lexicalMatches.isEmpty() && visualMatches.isEmpty()) {
            return citedPageCandidates(citedPages);
        }
        List<Integer> visualPages = rankedPages(citedPages, lexicalMatches, visualMatches);
        List<Candidate> selected = visualPages.stream()
                // A full cited page is a search boundary, not a crop that will be shown to the player. It lets the
                // visual walkthrough find a legend, icon group, or worked state that the neighbouring text names.
                .map(page -> citedPageCandidate(page, factsByPage.get(page)))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        lexicalMatches.stream()
                .filter(candidate -> visualPages.contains(candidate.block().pageNumber()))
                .limit(MAX_CANDIDATES - selected.size())
                .map(candidate -> Candidate.from(candidate.block()))
                .forEach(selected::add);
        return List.copyOf(selected);
    }

    private List<Integer> rankedPages(
            Set<Integer> citedPages, List<ScoredBlock> lexicalMatches, List<ScoredPage> visualMatches) {
        return citedPages.stream()
                .sorted(Comparator
                        .comparingInt((Integer page) -> pageScore(page, lexicalMatches, visualMatches))
                        .reversed()
                        .thenComparingInt(Integer::intValue))
                .limit(2)
                .toList();
    }

    private List<Candidate> citedPageCandidates(Set<Integer> citedPages) {
        List<Integer> pages = citedPages.stream().sorted().toList();
        List<Integer> coveredPages = pages.size() <= 2
                ? pages
                : List.of(pages.getFirst(), pages.getLast());
        return coveredPages.stream()
                .map(this::citedPageCandidate)
                .toList();
    }

    private Candidate citedPageCandidate(int pageNumber) {
        return citedPageCandidate(pageNumber, null);
    }

    private Candidate citedPageCandidate(int pageNumber, PageFact pageFact) {
        // A translated lesson has no reliable text-level anchor in an English (or other-language)
        // source. Keep the page citation boundary, but let vision locate the visible teaching aid.
        return new Candidate(
                pageNumber,
                new RulebookUnderstanding.Rectangle(0, 0, 1_000, 1_000),
                pageFact == null
                        ? "Cited page " + pageNumber + " visual context"
                        : "Cited page " + pageNumber + " visual context. Visual retrieval hint (verify against the attached image): "
                                + compactFactHint(pageFact));
    }

    private int pageScore(int pageNumber, List<ScoredBlock> lexicalMatches, List<ScoredPage> visualMatches) {
        int textScore = lexicalMatches.stream()
                .filter(match -> match.block().pageNumber() == pageNumber)
                .mapToInt(ScoredBlock::score)
                .sum();
        int visualScore = visualMatches.stream()
                .filter(match -> match.pageNumber() == pageNumber)
                .mapToInt(ScoredPage::score)
                .sum();
        // A page image catalog captures icon names, layout relationships, and examples that extraction often drops.
        // It informs retrieval but does not outrank a strong text match by itself.
        return textScore + visualScore * 8;
    }

    private int score(PageBlock block, Set<String> terms) {
        Set<String> words = normalizedTerms(List.of(block.text()));
        int overlap = (int) terms.stream().filter(words::contains).count();
        if (overlap == 0) return 0;
        int headingBonus = block.role() == RulebookUnderstanding.BlockRole.HEADING ? 3 : 0;
        int compactBonus = block.rectangle().width() * block.rectangle().height() <= 350_000 ? 1 : 0;
        return overlap * 10 + headingBonus + compactBonus;
    }

    private int score(PageFact pageFact, Set<String> terms) {
        Set<String> factTerms = normalizedTerms(List.of(
                pageFact.printedTerms(), pageFact.factualSummary(), String.join(" ", pageFact.keywords())));
        int overlap = (int) terms.stream().filter(factTerms::contains).count();
        if (overlap == 0) return 0;
        int keywordOverlap = (int) terms.stream()
                .filter(normalizedTerms(pageFact.keywords())::contains)
                .count();
        return overlap * 10 + keywordOverlap * 4;
    }

    private String compactFactHint(PageFact pageFact) {
        String keywords = String.join(", ", pageFact.keywords());
        String summary = pageFact.factualSummary();
        String hint = keywords.isBlank() ? summary : keywords + "; " + summary;
        return hint.length() <= 360 ? hint : hint.substring(0, 359) + "…";
    }

    private Set<String> normalizedTerms(List<String> values) {
        return values.stream()
                .filter(value -> value != null)
                .flatMap(this::terms)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Stream<String> terms(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[\\p{IsHan}]+|[\\p{L}\\p{N}]+")
                .matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.codePoints().allMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)) {
                if (token.length() >= 2) {
                    for (int offset = 0; offset < token.length() - 1; offset++) {
                        terms.add(token.substring(offset, offset + 2));
                    }
                }
            } else if (token.length() >= 2) {
                terms.add(token);
            }
        }
        return terms.stream();
    }

    public record Candidate(int pageNumber, Rectangle rectangle, String sourceText) {
        public Candidate {
            if (pageNumber < 1 || rectangle == null || sourceText == null || sourceText.isBlank()) {
                throw new IllegalArgumentException("visual region candidate is invalid");
            }
            sourceText = sourceText.strip();
        }

        private static Candidate from(PageBlock block) {
            return new Candidate(block.pageNumber(), block.rectangle(), block.text());
        }
    }

    private record ScoredBlock(PageBlock block, int score) {}

    private record ScoredPage(int pageNumber, int score) {}
}
