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

    /**
     * Adds or refreshes only the supplied rendered pages without discarding observations for the rest of a rulebook.
     *
     * <p>Catalog work is deliberately incremental: later lesson topics can reveal a relevant icon or worked example
     * that was not selected during the first outline pass. Implementations that do not retain facts may treat this as
     * a replacement, but the durable adapter must preserve unaffected pages.</p>
     */
    default void merge(UUID documentVersionId, List<PageFact> pages) {
        replace(documentVersionId, pages);
    }

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

    record PageFact(
            int pageNumber,
            String printedTerms,
            String factualSummary,
            List<String> keywords,
            List<VisualAnchor> visualAnchors) {

        public PageFact(int pageNumber, String printedTerms, String factualSummary, List<String> keywords) {
            this(pageNumber, printedTerms, factualSummary, keywords, List.of());
        }

        public PageFact {
            if (pageNumber < 1 || printedTerms == null || printedTerms.isBlank() || factualSummary == null
                    || factualSummary.isBlank() || keywords == null || keywords.isEmpty() || visualAnchors == null) {
                throw new IllegalArgumentException("visual page fact is invalid");
            }
            if (printedTerms.length() > 2_000 || factualSummary.length() > 2_000 || keywords.size() > 12
                    || keywords.stream().anyMatch(keyword -> keyword == null || keyword.isBlank() || keyword.length() > 120)
                    || visualAnchors.size() > 8) {
                throw new IllegalArgumentException("visual page fact is too large");
            }
            printedTerms = printedTerms.strip();
            factualSummary = factualSummary.strip();
            keywords = keywords.stream().map(String::strip).distinct().toList();
            visualAnchors = visualAnchors.stream().distinct().toList();
        }

        public String evidenceText() {
            return "Visual page facts (verify against the attached page image).\nPrinted terms: " + printedTerms
                    + "\nVisible facts: " + factualSummary + "\nKeywords: " + String.join(", ", keywords);
        }
    }

    /**
     * A bounded, page-local visual landmark found while reading the rendered rulebook image.
     *
     * <p>It is a retrieval boundary only: later vision still checks the original page before a crop is shown to a
     * player. Keeping the literal label and visible description separately prevents this index from becoming an
     * uncited rule explanation.</p>
     */
    record VisualAnchor(
            String kind,
            String label,
            String visibleDescription,
            int x,
            int y,
            int width,
            int height) {

        public VisualAnchor {
            if (kind == null || kind.isBlank() || label == null || label.isBlank()
                    || visibleDescription == null || visibleDescription.isBlank()
                    || kind.length() > 60 || label.length() > 180 || visibleDescription.length() > 480
                    || x < 0 || y < 0 || width < 20 || height < 20 || x + width > 1_000 || y + height > 1_000) {
                throw new IllegalArgumentException("visual anchor is invalid");
            }
            kind = kind.strip();
            label = label.strip();
            visibleDescription = visibleDescription.strip();
        }

        public String retrievalText() {
            return kind + " " + label + " " + visibleDescription;
        }
    }
}
