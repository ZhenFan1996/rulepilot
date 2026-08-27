package com.rulepilot.retrieval;

import java.util.List;
import java.util.UUID;

/** Finds page-scoped observations previously extracted from visual rulebook pages. */
public interface VisualRulebookPageFactSearch {

    List<PageFactMatch> search(UUID documentVersionId, String query, int limit);

    default List<PageFactMatch> findByPageNumbers(UUID documentVersionId, java.util.Set<Integer> pageNumbers) {
        return List.of();
    }

    static VisualRulebookPageFactSearch empty() {
        return (documentVersionId, query, limit) -> List.of();
    }

    record PageFactMatch(
            int pageNumber,
            String printedTerms,
            String factualSummary,
            List<String> keywords,
            double score,
            RuleFactStatus ruleFactStatus) {
        public PageFactMatch {
            if (pageNumber < 1 || printedTerms == null || printedTerms.isBlank() || factualSummary == null
                    || factualSummary.isBlank() || keywords == null || keywords.isEmpty()
                    || !Double.isFinite(score) || score < 0 || ruleFactStatus == null) {
                throw new IllegalArgumentException("visual page fact match is invalid");
            }
            printedTerms = printedTerms.strip();
            factualSummary = factualSummary.strip();
            keywords = keywords.stream().map(String::strip).filter(value -> !value.isBlank()).distinct().toList();
        }

        public boolean supportsRuleClaims() {
            return ruleFactStatus == RuleFactStatus.CURRENT_RULE_FACTS;
        }

        public String evidenceText() {
            return "Visual page facts (verify against the cited rulebook page).\nPrinted terms: " + printedTerms
                    + "\nVisible facts: " + factualSummary + "\nKeywords: " + String.join(", ", keywords);
        }
    }

    enum RuleFactStatus {
        CURRENT_RULE_FACTS,
        NO_RULE_CONTENT,
        FACTS_INCOMPLETE
    }
}
