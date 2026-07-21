package com.rulepilot.teaching;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Durable, page-scoped observations made from a rendered rulebook page.
 *
 * <p>These facts are an aid for retrieval and citation. They never replace the original page image and are kept
 * deliberately separate from player-facing lesson prose.</p>
 */
public interface VisualRulebookPageFacts {

    void replace(UUID documentVersionId, List<PageFact> pages);

    List<PageFact> find(UUID documentVersionId, Set<Integer> pageNumbers);

    static VisualRulebookPageFacts empty() {
        return new VisualRulebookPageFacts() {
            @Override
            public void replace(UUID documentVersionId, List<PageFact> pages) {}

            @Override
            public List<PageFact> find(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of();
            }
        };
    }

    record PageFact(int pageNumber, String printedTerms, String factualSummary, List<String> keywords) {
        public PageFact {
            if (pageNumber < 1 || printedTerms == null || printedTerms.isBlank() || factualSummary == null
                    || factualSummary.isBlank() || keywords == null || keywords.isEmpty()) {
                throw new IllegalArgumentException("visual page fact is invalid");
            }
            if (printedTerms.length() > 2_000 || factualSummary.length() > 2_000 || keywords.size() > 12
                    || keywords.stream().anyMatch(keyword -> keyword == null || keyword.isBlank() || keyword.length() > 120)) {
                throw new IllegalArgumentException("visual page fact is too large");
            }
            printedTerms = printedTerms.strip();
            factualSummary = factualSummary.strip();
            keywords = keywords.stream().map(String::strip).distinct().toList();
        }

        public String evidenceText() {
            return "Visual page facts (verify against the attached page image).\nPrinted terms: " + printedTerms
                    + "\nVisible facts: " + factualSummary + "\nKeywords: " + String.join(", ", keywords);
        }
    }
}
