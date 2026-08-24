package com.rulepilot.teaching.application;

import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Selects only plan-bound source pages; semantic crop choice belongs to the visual model's typed response. */
@Component
public final class VisualRegionCandidateSelector {

    private static final int DEFAULT_PAGE_BUDGET = 6;

    public List<Candidate> select(
            RulebookUnderstanding understanding,
            Set<Integer> citedPages,
            List<String> sectionTerms) {
        return select(understanding, citedPages, sectionTerms, DEFAULT_PAGE_BUDGET);
    }

    public List<Candidate> select(
            RulebookUnderstanding understanding,
            Set<Integer> citedPages,
            List<String> sectionTerms,
            int pageBudget) {
        if (understanding == null || citedPages == null || sectionTerms == null) {
            throw new IllegalArgumentException("visual region selection input is required");
        }
        if (pageBudget < 1 || pageBudget > 12) {
            throw new IllegalArgumentException("visual candidate page budget must be between one and twelve");
        }
        if (citedPages.stream().anyMatch(page -> page == null || page < 1)) {
            throw new IllegalArgumentException("visual region cited pages are invalid");
        }
        if (citedPages.isEmpty()) return List.of();

        return citedPages.stream()
                .sorted()
                .limit(pageBudget)
                .map(this::citedPageCandidate)
                .toList();
    }

    private Candidate citedPageCandidate(int pageNumber) {
        return new Candidate(
                pageNumber,
                new Rectangle(0, 0, 1_000, 1_000),
                "Cited page " + pageNumber + " visual context");
    }

    /**
     * A cataloged anchor remains readable for stored/legacy requests, but new selection never guesses one from lesson
     * prose. The visual model receives the full cited page and returns its own page/claim/geometry binding.
     */
    public record Candidate(int pageNumber, Rectangle rectangle, String sourceText, VisualAnchor catalogedAnchor) {
        public Candidate {
            if (pageNumber < 1 || rectangle == null || sourceText == null || sourceText.isBlank()) {
                throw new IllegalArgumentException("visual region candidate is invalid");
            }
            sourceText = sourceText.strip();
        }

        public Candidate(int pageNumber, Rectangle rectangle, String sourceText) {
            this(pageNumber, rectangle, sourceText, null);
        }

        /**
         * The candidate contract is structural. Adapters must never infer its origin from the human-readable
         * sourceText because that text is diagnostic context, not a protocol discriminator.
         */
        public CandidateKind kind() {
            return catalogedAnchor == null ? CandidateKind.CITED_PAGE_CONTEXT : CandidateKind.CATALOGED_VISUAL_ANCHOR;
        }
    }

    public enum CandidateKind {
        CITED_PAGE_CONTEXT,
        CATALOGED_VISUAL_ANCHOR
    }
}
