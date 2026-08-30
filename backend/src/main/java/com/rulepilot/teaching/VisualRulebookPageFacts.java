package com.rulepilot.teaching;

import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
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
     * <p>Catalog work is deliberately incremental: later lesson topics can reveal a relevant diagram or worked example
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
            List<VisualAnchor> visualAnchors,
            int schemaVersion,
            List<RuleGroupFact> ruleGroupFacts) {

        public static final int CURRENT_SCHEMA_VERSION = 37;

        public PageFact(int pageNumber, String printedTerms, String factualSummary, List<String> keywords) {
            this(
                    pageNumber,
                    printedTerms,
                    factualSummary,
                    keywords,
                    List.of(),
                    CURRENT_SCHEMA_VERSION,
                    List.of());
        }

        public PageFact(
                int pageNumber,
                String printedTerms,
                String factualSummary,
                List<String> keywords,
                List<VisualAnchor> visualAnchors) {
            this(
                    pageNumber,
                    printedTerms,
                    factualSummary,
                    keywords,
                    visualAnchors,
                    CURRENT_SCHEMA_VERSION,
                    List.of());
        }

        public PageFact(
                int pageNumber,
                String printedTerms,
                String factualSummary,
                List<String> keywords,
                List<VisualAnchor> visualAnchors,
                int schemaVersion) {
            this(
                    pageNumber,
                    printedTerms,
                    factualSummary,
                    keywords,
                    visualAnchors,
                    schemaVersion,
                    List.of());
        }

        public PageFact {
            if (pageNumber < 1 || printedTerms == null || printedTerms.isBlank() || factualSummary == null
                    || factualSummary.isBlank() || keywords == null || visualAnchors == null
                    || ruleGroupFacts == null) {
                throw new IllegalArgumentException("visual page fact is invalid");
            }
            if (keywords.stream().anyMatch(keyword -> keyword == null || keyword.isBlank())
                    || visualAnchors.stream().anyMatch(java.util.Objects::isNull)
                    || ruleGroupFacts.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("visual page fact contains an invalid item");
            }
            if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA_VERSION) {
                throw new IllegalArgumentException("visual page fact schema version is invalid");
            }
            printedTerms = printedTerms.strip();
            factualSummary = factualSummary.strip();
            keywords = keywords.stream().map(String::strip).distinct().toList();
            visualAnchors = visualAnchors.stream().distinct().toList();
            ruleGroupFacts = ruleGroupFacts.stream().distinct().toList();
        }

        public String evidenceText() {
            String anchors = visualAnchors.stream()
                    .map(anchor -> anchor.kind() + " | " + anchor.label()
                            + " | " + anchor.visibleDescription()
                            + " | rect=" + anchor.x() + "," + anchor.y() + ","
                            + anchor.width() + "," + anchor.height())
                    .collect(java.util.stream.Collectors.joining("\n- ", "\n- ", ""));
            return "Visual page facts (interpreted from the rendered page; cited text still controls rule effects)."
                    + "\nPrinted terms: " + printedTerms
                    + "\nVisible facts: " + factualSummary
                    + "\nKeywords: " + String.join(", ", keywords)
                    + (visualAnchors.isEmpty()
                            ? "\nCataloged visual anchors: none"
                            : "\nCataloged visual anchors (0-1000 page coordinates):" + anchors);
        }

        /**
         * Lesson prose may use the visual pass for presentation, never as a second source of rule effects.
         * Keeping the factual summary out of this projection prevents an image interpretation from silently
         * overriding or supplementing the cited PDF text.
         */
        public String presentationEvidenceText() {
            String anchors = visualAnchors.stream()
                    .map(anchor -> anchor.kind() + " | " + anchor.label()
                            + " | " + anchor.visibleDescription())
                    .collect(java.util.stream.Collectors.joining("\n- ", "\n- ", ""));
            return "Visual presentation data only. Do not use it to state a rule effect, condition, quantity, score, "
                    + "timing, or exception. It may inform only a typed VISUAL intent and the literal relationship "
                    + "a later page-grounded visual aid should help the player notice."
                    + "\nPrinted terms: " + printedTerms
                    + (visualAnchors.isEmpty()
                            ? "\nCataloged visual anchors: none"
                            : "\nCataloged visual anchors:" + anchors);
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
