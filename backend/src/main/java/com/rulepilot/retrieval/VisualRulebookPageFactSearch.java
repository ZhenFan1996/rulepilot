package com.rulepilot.retrieval;

import java.util.List;
import java.util.UUID;

/** Finds page-scoped observations previously extracted from visual rulebook pages. */
public interface VisualRulebookPageFactSearch {

    List<PageFactMatch> search(UUID documentVersionId, String query, int limit);

    static VisualRulebookPageFactSearch empty() {
        return (documentVersionId, query, limit) -> List.of();
    }

    record PageFactMatch(
            int pageNumber,
            String printedTerms,
            String factualSummary,
            List<String> keywords,
            double score) {
        public PageFactMatch {
            if (pageNumber < 1 || printedTerms == null || printedTerms.isBlank() || factualSummary == null
                    || factualSummary.isBlank() || keywords == null || keywords.isEmpty()
                    || !Double.isFinite(score) || score < 0) {
                throw new IllegalArgumentException("visual page fact match is invalid");
            }
            printedTerms = printedTerms.strip();
            factualSummary = factualSummary.strip();
            keywords = keywords.stream().map(String::strip).filter(value -> !value.isBlank()).distinct().toList();
        }

        public String evidenceText() {
            return "Visual page facts (verify against the cited rulebook page).\nPrinted terms: " + printedTerms
                    + "\nVisible facts: " + factualSummary + "\nKeywords: " + String.join(", ", keywords);
        }
    }
}
