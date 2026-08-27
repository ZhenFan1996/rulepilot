package com.rulepilot.teaching;

import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
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
            List<SourceDependency> sourceDependencies,
            List<String> ruleGroupIdentifiers,
            boolean ruleGroupInventoryComplete,
            List<RuleGroupFact> ruleGroupFacts) {

        // Schema 26 preserves dense-page tile facts and binds each visible list/grid identifier to its own rule.
        // Schema 27 verifies ambiguous cell pictograms against labeled reference artwork from the active document.
        // Schema 28 retains shared rules alongside independently bound cells on dense catalog pages.
        // Schema 29 rebuilds page ledgers with numerical aggregation owners, multipliers, and worked formulas intact.
        // Schema 30 retains every independently inventoried visual rule group for source-coverage review.
        // Schema 31 persists explicitly named external-source dependencies separately from executable page rules.
        // Schema 32 persists the complete page-owned gameplay rule-group inventory used by teaching coverage gates.
        // Schema 33 rejects summaries that would lose a bound rule-group fact at the durable 4,000-character edge.
        // Schema 34 embeds validated, page- and rule-group-bound quantity observations in the factual ledger while
        // preserving their original short source spans and refusing unsafe arithmetic.
        // Schema 35 gives every visual rule group a page-local structural identity. Visible headings remain labels,
        // so one heading can legitimately own several distinct list items without invalidating the whole page.
        // Schema 36 persists the model's typed ruleGroups objects. Coverage no longer reconstructs identifiers and
        // facts by splitting the natural-language factual summary.
        public static final int CURRENT_SCHEMA_VERSION = 36;

        public PageFact(int pageNumber, String printedTerms, String factualSummary, List<String> keywords) {
            this(
                    pageNumber,
                    printedTerms,
                    factualSummary,
                    keywords,
                    List.of(),
                    CURRENT_SCHEMA_VERSION,
                    List.of(),
                    List.of(),
                    false,
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
                    List.of(),
                    List.of(),
                    false,
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
                    List.of(),
                    List.of(),
                    false,
                    List.of());
        }

        public PageFact {
            if (pageNumber < 1 || printedTerms == null || printedTerms.isBlank() || factualSummary == null
                    || factualSummary.isBlank() || keywords == null || keywords.isEmpty() || visualAnchors == null
                    || sourceDependencies == null) {
                throw new IllegalArgumentException("visual page fact is invalid");
            }
            if (keywords.stream().anyMatch(keyword -> keyword == null || keyword.isBlank())
                    || visualAnchors.stream().anyMatch(java.util.Objects::isNull)
                    || sourceDependencies.stream().anyMatch(java.util.Objects::isNull)
                    || ruleGroupIdentifiers == null
                    || ruleGroupIdentifiers.stream()
                            .anyMatch(identifier -> identifier == null || identifier.isBlank())
                    || ruleGroupFacts == null
                    || ruleGroupFacts.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("visual page fact is too large");
            }
            if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA_VERSION) {
                throw new IllegalArgumentException("visual page fact schema version is invalid");
            }
            printedTerms = printedTerms.strip();
            factualSummary = factualSummary.strip();
            keywords = keywords.stream().map(String::strip).distinct().toList();
            visualAnchors = visualAnchors.stream().distinct().toList();
            sourceDependencies = sourceDependencies.stream().distinct().toList();
            ruleGroupIdentifiers = ruleGroupIdentifiers.stream().map(String::strip).distinct().toList();
            ruleGroupFacts = ruleGroupFacts.stream().distinct().toList();
            // Historical rows must remain deserializable so the cataloger can identify and rebuild them. Schema 36
            // is the first schema that persisted typed rule-group facts; an older completeness flag described the
            // old prose ledger and must never be promoted to current completeness by this constructor.
            if (schemaVersion == CURRENT_SCHEMA_VERSION
                    && ruleGroupInventoryComplete
                    && !VisualSourceRuleGroupLedger.hasExactFactBindings(ruleGroupIdentifiers, ruleGroupFacts)) {
                throw new IllegalArgumentException(
                        "complete rule-group inventory requires one exact non-empty fact per identifier");
            }
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
