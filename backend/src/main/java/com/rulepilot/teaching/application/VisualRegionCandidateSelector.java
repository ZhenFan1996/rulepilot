package com.rulepilot.teaching.application;

import com.rulepilot.ingestion.domain.RulebookUnderstanding;
import com.rulepilot.ingestion.domain.RulebookUnderstanding.PageBlock;
import com.rulepilot.ingestion.domain.RulebookUnderstanding.Rectangle;
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
        if (understanding == null || citedPages == null || sectionTerms == null) {
            throw new IllegalArgumentException("visual region selection input is required");
        }
        Set<String> terms = normalizedTerms(sectionTerms);
        if (citedPages.isEmpty() || terms.isEmpty()) return List.of();
        return understanding.pageBlocks().stream()
                .filter(block -> block.role() != RulebookUnderstanding.BlockRole.FOOTER)
                .filter(block -> citedPages.contains(block.pageNumber()))
                .map(block -> new ScoredBlock(block, score(block, terms)))
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparingInt(ScoredBlock::score).reversed()
                        .thenComparing(candidate -> candidate.block().pageNumber())
                        .thenComparing(candidate -> candidate.block().readingOrder()))
                .limit(MAX_CANDIDATES)
                .map(candidate -> Candidate.from(candidate.block()))
                .toList();
    }

    private int score(PageBlock block, Set<String> terms) {
        Set<String> words = normalizedTerms(List.of(block.text()));
        int overlap = (int) terms.stream().filter(words::contains).count();
        if (overlap == 0) return 0;
        int headingBonus = block.role() == RulebookUnderstanding.BlockRole.HEADING ? 3 : 0;
        int compactBonus = block.rectangle().width() * block.rectangle().height() <= 350_000 ? 1 : 0;
        return overlap * 10 + headingBonus + compactBonus;
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
}
