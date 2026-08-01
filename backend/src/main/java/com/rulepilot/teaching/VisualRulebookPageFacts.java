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
            List<VisualAnchor> visualAnchors,
            List<IconOccurrence> iconOccurrences,
            boolean iconInventoryComplete,
            int schemaVersion) {

        // Schema 23 records the crop-review rectangle after it has been projected back to source-page coordinates.
        // Schema 22 facts were cataloged before the application published that refined rectangle and must be rebuilt.
        public static final int CURRENT_SCHEMA_VERSION = 23;

        public PageFact(int pageNumber, String printedTerms, String factualSummary, List<String> keywords) {
            this(pageNumber, printedTerms, factualSummary, keywords, List.of(), List.of(), false, CURRENT_SCHEMA_VERSION);
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
                    List.of(),
                    false,
                    CURRENT_SCHEMA_VERSION);
        }

        public PageFact(
                int pageNumber,
                String printedTerms,
                String factualSummary,
                List<String> keywords,
                List<VisualAnchor> visualAnchors,
                int schemaVersion) {
            this(pageNumber, printedTerms, factualSummary, keywords, visualAnchors, List.of(), false, schemaVersion);
        }

        public PageFact {
            if (pageNumber < 1 || printedTerms == null || printedTerms.isBlank() || factualSummary == null
                    || factualSummary.isBlank() || keywords == null || keywords.isEmpty() || visualAnchors == null
                    || iconOccurrences == null) {
                throw new IllegalArgumentException("visual page fact is invalid");
            }
            if (printedTerms.length() > 2_000 || factualSummary.length() > 2_000 || keywords.size() > 12
                    || keywords.stream().anyMatch(keyword -> keyword == null || keyword.isBlank() || keyword.length() > 120)
                    || visualAnchors.size() > 8
                    || iconOccurrences.size() > 32) {
                throw new IllegalArgumentException("visual page fact is too large");
            }
            if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA_VERSION) {
                throw new IllegalArgumentException("visual page fact schema version is invalid");
            }
            printedTerms = printedTerms.strip();
            factualSummary = factualSummary.strip();
            keywords = keywords.stream().map(String::strip).distinct().toList();
            visualAnchors = visualAnchors.stream().distinct().toList();
            iconOccurrences = iconOccurrences.stream().distinct().toList();
        }

        public String evidenceText() {
            String anchors = visualAnchors.stream()
                    .limit(4)
                    .map(anchor -> anchor.kind() + " | " + bounded(anchor.label(), 120)
                            + " | " + bounded(anchor.visibleDescription(), 240)
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
                    .limit(4)
                    .map(anchor -> anchor.kind() + " | " + bounded(anchor.label(), 120)
                            + " | " + bounded(anchor.visibleDescription(), 240)
                            + " | rect=" + anchor.x() + "," + anchor.y() + ","
                            + anchor.width() + "," + anchor.height())
                    .collect(java.util.stream.Collectors.joining("\n- ", "\n- ", ""));
            return "Visual presentation data only. Do not use it to state a rule effect, condition, quantity, score, "
                    + "timing, or exception. It may populate only visualFocus, its literal visibleDescription, and "
                    + "player-facing directions about where to look."
                    + "\nPrinted terms: " + printedTerms
                    + (visualAnchors.isEmpty()
                            ? "\nCataloged visual anchors: none"
                            : "\nCataloged visual anchors (0-1000 page coordinates):" + anchors);
        }

        /**
         * Image-only PDFs have no ordinary extracted prose to ground a lesson. In that case the bounded page-level
         * vision pass becomes a transcription adapter: its factual ledger is usable, but only at the granularity it
         * actually recorded. Printed-term bags are intentionally excluded because detached numbers and labels can be
         * associated with the wrong subject.
         */
        public String transcribedRuleEvidenceText() {
            String anchors = visualAnchors.stream()
                    .limit(4)
                    .map(anchor -> anchor.kind() + " | " + bounded(anchor.label(), 120)
                            + " | " + bounded(anchor.visibleDescription(), 240)
                            + " | rect=" + anchor.x() + "," + anchor.y() + ","
                            + anchor.width() + "," + anchor.height())
                    .collect(java.util.stream.Collectors.joining("\n- ", "\n- ", ""));
            return "Visual-transcribed rule evidence. Only the statements under Visible rule facts are rule evidence. "
                    + "Do not derive a per-item value from a worked total, attach a detached number to a nearby label, "
                    + "or fill a missing prerequisite, action, timing, score, or exception."
                    + "\nVisible rule facts: " + factualSummary
                    + (visualAnchors.isEmpty()
                            ? "\nCataloged visual anchors: none"
                            : "\nCataloged visual anchors (presentation only; 0-1000 page coordinates):" + anchors);
        }

        public static boolean isTranscribedRuleEvidence(String value) {
            return value != null && value.startsWith("Visual-transcribed rule evidence.");
        }

        private static String bounded(String value, int maximum) {
            return value.length() <= maximum ? value : value.substring(0, maximum - 1).stripTrailing() + "…";
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

    /**
     * One representative appearance of a gameplay icon on a rendered source page.
     *
     * <p>The icon crop proves appearance only. {@link IconMeaningStatus#IDENTIFIED} records an exact printed label
     * without pretending that the label explains a gameplay effect. A player-facing rule explanation is publishable
     * only when {@code meaningStatus} is {@link IconMeaningStatus#EXPLICIT} and {@code evidenceText} records the
     * visible rulebook wording that maps the symbol to that meaning. Unexplained icons remain useful visual vocabulary
     * but cannot acquire a guessed rule effect.</p>
     */
    record IconOccurrence(
            String groupKey,
            String name,
            String visualDescription,
            String explanation,
            String evidenceText,
            String verifiedVisualLabel,
            IconMeaningStatus meaningStatus,
            int x,
            int y,
            int width,
            int height) {

        public IconOccurrence(
                String groupKey,
                String name,
                String visualDescription,
                String explanation,
                String evidenceText,
                IconMeaningStatus meaningStatus,
                int x,
                int y,
                int width,
                int height) {
            this(
                    groupKey,
                    name,
                    visualDescription,
                    explanation,
                    evidenceText,
                    "",
                    meaningStatus,
                    x,
                    y,
                    width,
                    height);
        }

        public IconOccurrence {
            if (groupKey == null || groupKey.isBlank() || groupKey.length() > 160
                    || name == null || name.isBlank() || name.length() > 180
                    || visualDescription == null || visualDescription.isBlank() || visualDescription.length() > 480
                    || explanation == null || explanation.length() > 600
                    || evidenceText == null || evidenceText.length() > 480
                    || (verifiedVisualLabel != null && verifiedVisualLabel.length() > 80)
                    || meaningStatus == null
                    || x < 0 || y < 0 || width < 12 || height < 12
                    || x + width > 1_000 || y + height > 1_000) {
                throw new IllegalArgumentException("visual icon occurrence is invalid");
            }
            groupKey = groupKey.strip();
            name = name.strip();
            visualDescription = visualDescription.strip();
            explanation = explanation.strip();
            evidenceText = evidenceText.strip();
            verifiedVisualLabel = verifiedVisualLabel == null ? "" : verifiedVisualLabel.strip();
            if (meaningStatus == IconMeaningStatus.EXPLICIT
                    && (explanation.isBlank() || evidenceText.isBlank())) {
                throw new IllegalArgumentException("explained visual icon requires visible rulebook evidence");
            }
            if (meaningStatus == IconMeaningStatus.IDENTIFIED
                    && (!explanation.isBlank() || evidenceText.isBlank())) {
                throw new IllegalArgumentException("identified visual icon requires a label but no rule meaning");
            }
            if (meaningStatus == IconMeaningStatus.UNEXPLAINED
                    && (!explanation.isBlank() || !evidenceText.isBlank())) {
                throw new IllegalArgumentException("unexplained visual icon cannot carry a rule meaning");
            }
        }
    }

    enum IconMeaningStatus {
        EXPLICIT,
        IDENTIFIED,
        UNEXPLAINED
    }
}
