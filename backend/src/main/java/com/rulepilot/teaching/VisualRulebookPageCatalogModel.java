package com.rulepilot.teaching;

import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads a small batch of rendered rulebook pages before lesson planning. Its output is a page-scoped retrieval aid,
 * never player-facing lesson prose or an uncited rule answer.
 */
public interface VisualRulebookPageCatalogModel {

    /**
     * A teaching-start request may cover a short rulebook in one provider round trip. The complete icon catalog
     * remains page-scoped in the application layer, while this bounded ceiling prevents an unbounded PDF from
     * exhausting the model context or response budget.
     */
    int MAX_PAGES_PER_REQUEST = 8;

    CatalogDraft summarize(CatalogRequest request);

    /**
     * Reads only the page-scoped rule facts needed to start an evidence-bound lesson. Implementations should avoid
     * icon inventories, spatial localization, and dense-cell audits here; those slower enrichments run after the
     * first readable lesson section. The default keeps non-vision test adapters source-compatible.
     */
    default CatalogDraft summarizeForTeaching(CatalogRequest request) {
        return summarize(request);
    }

    /**
     * Selects one source page that can safely support the first cited lesson section while returning only a compact
     * structural sketch for every other supplied page. This is an optional fast path: implementations that cannot
     * provide exact structured page bindings leave the existing complete Teaching catalog unchanged.
     */
    default Optional<ProgressiveTeachingStartDraft> selectProgressiveTeachingStart(CatalogRequest request) {
        return Optional.empty();
    }

    default boolean supportsProgressiveTeachingStart(String modelConfigurationOwner) {
        return false;
    }

    /**
     * Identifies the provider request that actually serves the lightweight Teaching-start pass. Implementations may
     * use a lower-latency request-local model than the configured model while keeping the same provider credentials.
     * The value is operational metadata only: it never changes evidence or lesson content.
     */
    default Optional<ModelExecutionIdentity> teachingStartupExecutionIdentity(String modelConfigurationOwner) {
        return Optional.empty();
    }

    /** Locates document-derived short item identifiers before their surrounding cells are read separately. */
    default IdentifierLocalizationDraft locateIdentifiers(IdentifierLocalizationRequest request) {
        return new IdentifierLocalizationDraft(List.of());
    }

    /** Reads bounded cells whose identifiers were independently supplied and spatially verified. */
    default IdentifierCellDraft summarizeIdentifierCells(IdentifierCellRequest request) {
        return new IdentifierCellDraft(List.of());
    }

    default IdentifierCellVerificationDraft verifyIdentifierCell(IdentifierCellVerificationRequest request) {
        return new IdentifierCellVerificationDraft(
                request.cell().identifier(), "NONE", 0, request.draftSummary());
    }

    /**
     * Rechecks model-proposed icon rectangles in a dedicated spatial-grounding pass. Implementations that cannot
     * perform a second visual pass preserve the proposed locations; the application still treats those pages as
     * incomplete when a configured real model fails the check.
     */
    default IconLocalizationDraft localizeIcons(IconLocalizationRequest request) {
        return new IconLocalizationDraft(java.util.stream.IntStream.range(0, request.candidates().size())
                .mapToObj(index -> {
                    IconOccurrence icon = request.candidates().get(index);
                    return new IconLocation(index, true, icon.x(), icon.y(), icon.width(), icon.height(), "");
                })
                .toList());
    }

    /**
     * Rechecks already localized close-up crops. A full-page locator can still land on adjacent prose or a similar
     * mark; implementations with a real vision model should inspect the proposed region at readable scale.
     */
    default IconCropReviewDraft reviewIconCrops(IconCropReviewRequest request) {
        return new IconCropReviewDraft(request.locations().stream()
                .map(location -> new IconCropDecision(
                        location.candidateIndex(),
                        true,
                        location.x(),
                        location.y(),
                        location.width(),
                        location.height()))
                .toList());
    }

    default boolean available(String modelConfigurationOwner) {
        return true;
    }

    static VisualRulebookPageCatalogModel unavailable() {
        return new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new IllegalStateException("visual page catalog is unavailable");
            }

            @Override
            public boolean available(String modelConfigurationOwner) {
                return false;
            }
        };
    }

    record CatalogRequest(
            List<PageImageInput> pages,
            String modelConfigurationOwner,
            String rulebookTitle,
            PageViewport viewport) {

        public CatalogRequest(List<PageImageInput> pages, String modelConfigurationOwner) {
            this(pages, modelConfigurationOwner, null, null);
        }

        public CatalogRequest(List<PageImageInput> pages, String modelConfigurationOwner, String rulebookTitle) {
            this(pages, modelConfigurationOwner, rulebookTitle, null);
        }

        public CatalogRequest {
            if (pages == null || pages.isEmpty() || pages.size() > MAX_PAGES_PER_REQUEST) {
                throw new IllegalArgumentException("visual page catalog request is invalid");
            }
            pages = List.copyOf(pages);
            modelConfigurationOwner = modelConfigurationOwner == null || modelConfigurationOwner.isBlank()
                    ? null
                    : modelConfigurationOwner.strip();
            if (rulebookTitle != null && rulebookTitle.length() > 160) {
                throw new IllegalArgumentException("visual page catalog rulebook title is invalid");
            }
            rulebookTitle = rulebookTitle == null || rulebookTitle.isBlank() ? null : rulebookTitle.strip();
            if (viewport != null
                    && (pages.size() != 1 || pages.getFirst().pageNumber() != viewport.pageNumber())) {
                throw new IllegalArgumentException("visual page viewport does not match its image");
            }
        }
    }

    /**
     * Normalized bounds of a supplied page tile. Model coordinates stay tile-relative and are projected back to the
     * immutable source page by the application layer.
     */
    record PageViewport(int pageNumber, int x, int y, int width, int height) {

        public PageViewport {
            if (pageNumber < 1
                    || x < 0 || y < 0 || width < 200 || height < 200
                    || x + width > 1_000 || y + height > 1_000) {
                throw new IllegalArgumentException("visual page viewport is invalid");
            }
        }
    }

    record CatalogDraft(List<PageSummary> pages) {
        public CatalogDraft {
            if (pages == null || pages.isEmpty() || pages.size() > MAX_PAGES_PER_REQUEST) {
                throw new IllegalArgumentException("visual page catalog draft is invalid");
            }
            pages = List.copyOf(pages);
        }
    }

    enum TeachingPageRole {
        GAMEPLAY_RULES,
        NON_GAMEPLAY,
        UNCERTAIN
    }

    /**
     * A compact page-role ledger used only to build an immutable source-bound plan. Coverage tags express teaching
     * obligations visible on a page; they are not themselves rule claims and never replace the page evidence.
     */
    record SourceDependency(String title, List<String> missingCoverageTags) {

        private static final Set<String> ALLOWED_MISSING_COVERAGE =
                Set.of("setup", "core_loop", "end", "scoring");

        public SourceDependency {
            if (title == null || title.isBlank() || title.length() > 160
                    || missingCoverageTags == null || missingCoverageTags.size() > ALLOWED_MISSING_COVERAGE.size()
                    || missingCoverageTags.stream()
                            .anyMatch(tag -> tag == null || !ALLOWED_MISSING_COVERAGE.contains(tag))) {
                throw new IllegalArgumentException("visual teaching source dependency is invalid");
            }
            title = title.strip().replaceAll("\\s+", " ");
            missingCoverageTags = missingCoverageTags.stream().distinct().toList();
        }
    }

    record TeachingPageSketch(
            int pageNumber,
            TeachingPageRole role,
            String visibleHeading,
            List<String> visibleTerms,
            List<String> coverageTags,
            boolean ruleGroupInventoryComplete,
            List<SourceDependency> sourceDependencies) {

        private static final Set<String> ALLOWED_COVERAGE_TAGS =
                Set.of("setup", "core_loop", "end", "scoring", "source_coverage");

        public TeachingPageSketch {
            if (pageNumber < 1 || role == null
                    || (visibleHeading != null && visibleHeading.length() > 160)
                    || visibleTerms == null || visibleTerms.size() > 8
                    || visibleTerms.stream().anyMatch(term -> term == null || term.isBlank() || term.length() > 120)
                    || coverageTags == null || coverageTags.size() > ALLOWED_COVERAGE_TAGS.size()
                    || coverageTags.stream().anyMatch(tag -> tag == null || !ALLOWED_COVERAGE_TAGS.contains(tag))
                    || sourceDependencies == null || sourceDependencies.size() > 4) {
                throw new IllegalArgumentException("visual teaching page sketch is invalid");
            }
            visibleHeading = visibleHeading == null ? "" : visibleHeading.strip();
            visibleTerms = visibleTerms.stream().map(String::strip).distinct().toList();
            coverageTags = coverageTags.stream().distinct().toList();
            sourceDependencies = sourceDependencies.stream().distinct().toList();
            if (role != TeachingPageRole.GAMEPLAY_RULES && !coverageTags.isEmpty()) {
                throw new IllegalArgumentException("non-gameplay visual pages cannot claim teaching coverage");
            }
            if (role != TeachingPageRole.GAMEPLAY_RULES && ruleGroupInventoryComplete) {
                throw new IllegalArgumentException("non-gameplay visual pages cannot complete a gameplay inventory");
            }
            if (role == TeachingPageRole.GAMEPLAY_RULES && coverageTags.isEmpty()) {
                throw new IllegalArgumentException("gameplay visual pages need a bounded teaching role");
            }
            if (role == TeachingPageRole.GAMEPLAY_RULES
                    && ruleGroupInventoryComplete
                    && visibleTerms.isEmpty()) {
                throw new IllegalArgumentException("complete gameplay inventory cannot be empty");
            }
        }

        public TeachingPageSketch(
                int pageNumber,
                TeachingPageRole role,
                String visibleHeading,
                List<String> visibleTerms,
                List<String> coverageTags,
                boolean ruleGroupInventoryComplete) {
            this(
                    pageNumber,
                    role,
                    visibleHeading,
                    visibleTerms,
                    coverageTags,
                    ruleGroupInventoryComplete,
                    List.of());
        }

        public TeachingPageSketch(
                int pageNumber,
                TeachingPageRole role,
                String visibleHeading,
                List<String> visibleTerms,
                List<String> coverageTags) {
            this(
                    pageNumber,
                    role,
                    visibleHeading,
                    visibleTerms,
                    coverageTags,
                    role == TeachingPageRole.GAMEPLAY_RULES,
                    List.of());
        }
    }

    /**
     * The selected page carries the only detailed factual ledger on the startup path. Remaining page facts are read
     * on the continuation lane after this page has produced a durable cited section.
     */
    record ProgressiveTeachingStartDraft(
            List<TeachingPageSketch> pages,
            PageSummary selectedPageFacts) {

        public ProgressiveTeachingStartDraft {
            if (pages == null || pages.isEmpty() || pages.size() > MAX_PAGES_PER_REQUEST
                    || selectedPageFacts == null) {
                throw new IllegalArgumentException("progressive visual teaching start is invalid");
            }
            pages = List.copyOf(pages);
            Set<Integer> pageNumbers = pages.stream()
                    .map(TeachingPageSketch::pageNumber)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (pageNumbers.size() != pages.size() || !pageNumbers.contains(selectedPageFacts.pageNumber())) {
                throw new IllegalArgumentException("progressive visual teaching start page bindings are invalid");
            }
            TeachingPageSketch selected = pages.stream()
                    .filter(page -> page.pageNumber() == selectedPageFacts.pageNumber())
                    .findFirst()
                    .orElseThrow();
            if (selected.role() != TeachingPageRole.GAMEPLAY_RULES) {
                throw new IllegalArgumentException("progressive visual teaching start selected a non-gameplay page");
            }
        }
    }

    record ModelExecutionIdentity(String provider, String model) {
        public ModelExecutionIdentity {
            provider = auditValue(provider, "provider", 40);
            model = auditValue(model, "model", 200);
        }

        public String auditLabel() {
            return provider + "/" + model;
        }

        private static String auditValue(String value, String label, int maxLength) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("visual model " + label + " is required");
            }
            String normalized = value.strip().replaceAll("\\s+", " ");
            if (normalized.length() > maxLength) {
                throw new IllegalArgumentException("visual model " + label + " is too long");
            }
            return normalized;
        }
    }

    record IdentifierLocalizationRequest(
            PageImageInput page,
            List<String> identifiers,
            String modelConfigurationOwner) {
        public IdentifierLocalizationRequest {
            if (page == null || identifiers == null || identifiers.size() < 4 || identifiers.size() > 24) {
                throw new IllegalArgumentException("visual identifier localization request is invalid");
            }
            identifiers = identifiers.stream().map(String::strip).distinct().toList();
            if (identifiers.size() < 4 || identifiers.stream().anyMatch(value -> value.isBlank() || value.length() > 24)) {
                throw new IllegalArgumentException("visual identifiers are invalid");
            }
            modelConfigurationOwner = modelConfigurationOwner == null || modelConfigurationOwner.isBlank()
                    ? null : modelConfigurationOwner.strip();
        }
    }

    record IdentifierLocalizationDraft(List<IdentifierLocation> locations) {
        public IdentifierLocalizationDraft {
            locations = locations == null ? List.of() : List.copyOf(locations);
            if (locations.size() > 24) throw new IllegalArgumentException("too many visual identifier locations");
        }
    }

    record IdentifierLocation(String identifier, int x, int y, int width, int height) {
        public IdentifierLocation {
            if (identifier == null || identifier.isBlank() || identifier.length() > 24
                    || x < 0 || y < 0 || width < 4 || height < 4
                    || x + width > 1_000 || y + height > 1_000) {
                throw new IllegalArgumentException("visual identifier location is invalid");
            }
            identifier = identifier.strip();
        }
    }

    record IdentifierCellInput(String identifier, PageImageInput image) {
        public IdentifierCellInput {
            if (identifier == null || identifier.isBlank() || identifier.length() > 24 || image == null) {
                throw new IllegalArgumentException("visual identifier cell is invalid");
            }
            identifier = identifier.strip();
        }
    }

    record IdentifierCellRequest(
            List<IdentifierCellInput> cells,
            List<IdentifierReferencePage> referencePages,
            String modelConfigurationOwner) {
        public IdentifierCellRequest(List<IdentifierCellInput> cells, String modelConfigurationOwner) {
            this(cells, List.of(), modelConfigurationOwner);
        }

        public IdentifierCellRequest {
            if (cells == null || cells.isEmpty() || cells.size() > 4) {
                throw new IllegalArgumentException("visual identifier cell request is invalid");
            }
            cells = List.copyOf(cells);
            referencePages = referencePages == null ? List.of() : List.copyOf(referencePages);
            if (referencePages.size() > 3) throw new IllegalArgumentException("too many identifier reference pages");
            modelConfigurationOwner = modelConfigurationOwner == null || modelConfigurationOwner.isBlank()
                    ? null : modelConfigurationOwner.strip();
        }
    }

    record IdentifierReferencePage(PageImageInput image, String evidenceText) {
        public IdentifierReferencePage {
            if (image == null || evidenceText == null || evidenceText.isBlank() || evidenceText.length() > 1_600) {
                throw new IllegalArgumentException("visual identifier reference page is invalid");
            }
            evidenceText = evidenceText.strip();
        }
    }

    record IdentifierCellVerificationRequest(
            IdentifierCellInput cell,
            IdentifierReferencePage referencePage,
            List<String> allowedLabels,
            String draftSummary,
            String modelConfigurationOwner) {
        public IdentifierCellVerificationRequest {
            if (cell == null || referencePage == null || allowedLabels == null || allowedLabels.size() < 2
                    || allowedLabels.size() > 12 || draftSummary == null || draftSummary.isBlank()
                    || draftSummary.length() > 800) {
                throw new IllegalArgumentException("identifier cell verification request is invalid");
            }
            allowedLabels = allowedLabels.stream().map(String::strip).filter(value -> !value.isBlank()).distinct().toList();
            if (allowedLabels.size() < 2 || allowedLabels.stream().anyMatch(value -> value.length() > 60)) {
                throw new IllegalArgumentException("identifier cell verification labels are invalid");
            }
            draftSummary = draftSummary.strip();
            modelConfigurationOwner = modelConfigurationOwner == null || modelConfigurationOwner.isBlank()
                    ? null : modelConfigurationOwner.strip();
        }
    }

    record IdentifierCellVerificationDraft(String identifier, String matchedLabel, int quantity, String factualSummary) {
        public IdentifierCellVerificationDraft {
            if (identifier == null || identifier.isBlank() || identifier.length() > 24
                    || matchedLabel == null || matchedLabel.isBlank() || matchedLabel.length() > 60
                    || quantity < 0 || quantity > 99
                    || factualSummary == null || factualSummary.isBlank() || factualSummary.length() > 800) {
                throw new IllegalArgumentException("identifier cell verification draft is invalid");
            }
            identifier = identifier.strip();
            matchedLabel = matchedLabel.strip();
            factualSummary = factualSummary.strip();
        }
    }

    record IdentifierCellDraft(List<IdentifierCellFact> facts) {
        public IdentifierCellDraft {
            facts = facts == null ? List.of() : List.copyOf(facts);
            if (facts.size() > 4) throw new IllegalArgumentException("too many visual identifier cell facts");
        }
    }

    record IdentifierCellFact(String identifier, String factualSummary) {
        public IdentifierCellFact {
            if (identifier == null || identifier.isBlank() || identifier.length() > 24
                    || factualSummary == null || factualSummary.isBlank() || factualSummary.length() > 800) {
                throw new IllegalArgumentException("visual identifier cell fact is invalid");
            }
            identifier = identifier.strip();
            factualSummary = factualSummary.strip();
        }
    }

    record IconLocalizationRequest(
            PageImageInput page,
            List<IconOccurrence> candidates,
            String modelConfigurationOwner) {

        public IconLocalizationRequest {
            if (page == null || candidates == null || candidates.isEmpty() || candidates.size() > 32) {
                throw new IllegalArgumentException("visual icon localization request is invalid");
            }
            candidates = List.copyOf(candidates);
            modelConfigurationOwner = modelConfigurationOwner == null || modelConfigurationOwner.isBlank()
                    ? null
                    : modelConfigurationOwner.strip();
        }
    }

    record IconLocalizationDraft(List<IconLocation> locations) {
        public IconLocalizationDraft {
            if (locations == null || locations.isEmpty() || locations.size() > 32) {
                throw new IllegalArgumentException("visual icon localization draft is invalid");
            }
            locations = List.copyOf(locations);
        }
    }

    record IconCropReviewRequest(
            PageImageInput page,
            List<IconOccurrence> candidates,
            List<IconLocation> locations,
            String modelConfigurationOwner) {

        public IconCropReviewRequest {
            if (page == null || candidates == null || locations == null
                    || candidates.isEmpty() || candidates.size() > 8 || candidates.size() != locations.size()
                    || locations.stream().anyMatch(location -> !location.present())) {
                throw new IllegalArgumentException("visual icon crop review request is invalid");
            }
            candidates = List.copyOf(candidates);
            locations = List.copyOf(locations);
            modelConfigurationOwner = modelConfigurationOwner == null || modelConfigurationOwner.isBlank()
                    ? null
                    : modelConfigurationOwner.strip();
        }
    }

    record IconCropReviewDraft(List<IconCropDecision> decisions) {
        public IconCropReviewDraft {
            if (decisions == null || decisions.isEmpty() || decisions.size() > 8) {
                throw new IllegalArgumentException("visual icon crop review draft is invalid");
            }
            decisions = List.copyOf(decisions);
        }
    }

    record IconCropDecision(
            int candidateIndex,
            boolean matchesAppearance,
            int x,
            int y,
            int width,
            int height) {

        public IconCropDecision(int candidateIndex, boolean matchesAppearance) {
            this(candidateIndex, matchesAppearance, 0, 0, 0, 0);
        }

        public IconCropDecision {
            if (candidateIndex < 0 || candidateIndex > 31) {
                throw new IllegalArgumentException("visual icon crop review candidate is invalid");
            }
            if (matchesAppearance && (x < 0 || x > 980 || y < 0 || y > 980
                    || width < 12 || height < 12 || x + width > 1_000 || y + height > 1_000)) {
                throw new IllegalArgumentException("visual icon crop review rectangle is invalid");
            }
            if (!matchesAppearance && (x != 0 || y != 0 || width != 0 || height != 0)) {
                throw new IllegalArgumentException("rejected visual icon crop must not have a rectangle");
            }
        }

        public static IconCropDecision rejected(int candidateIndex) {
            return new IconCropDecision(candidateIndex, false);
        }
    }

    record IconLocation(
            int candidateIndex,
            boolean present,
            int x,
            int y,
            int width,
            int height,
            String observedLabel) {

        public IconLocation(int candidateIndex, boolean present, int x, int y, int width, int height) {
            this(candidateIndex, present, x, y, width, height, "");
        }

        public IconLocation {
            if (candidateIndex < 0 || candidateIndex > 31) {
                throw new IllegalArgumentException("visual icon localization candidate is invalid");
            }
            if (present && (x < 0 || x > 980 || y < 0 || y > 980
                    || width < 12 || height < 12 || x + width > 1_000 || y + height > 1_000)) {
                throw new IllegalArgumentException("visual icon localization rectangle is invalid");
            }
            if (!present && (x != 0 || y != 0 || width != 0 || height != 0)) {
                throw new IllegalArgumentException("absent visual icon localization must not have a rectangle");
            }
            if (observedLabel != null && observedLabel.length() > 80) {
                throw new IllegalArgumentException("visual icon localization label is invalid");
            }
            observedLabel = present && observedLabel != null ? observedLabel.strip() : "";
        }

        public static IconLocation absent(int candidateIndex) {
            return new IconLocation(candidateIndex, false, 0, 0, 0, 0, "");
        }
    }

    record PageSummary(
            int pageNumber,
            String printedTerms,
            String factualSummary,
            List<String> keywords,
            List<VisualAnchor> visualAnchors,
            List<IconOccurrence> iconOccurrences,
            boolean iconInventoryComplete,
            List<SourceDependency> sourceDependencies,
            List<String> ruleGroupIdentifiers,
            boolean ruleGroupInventoryComplete) {

        public PageSummary(int pageNumber, String printedTerms, String factualSummary, List<String> keywords) {
            this(
                    pageNumber,
                    printedTerms,
                    factualSummary,
                    keywords,
                    List.of(),
                    List.of(),
                    false,
                    List.of(),
                    List.of(),
                    false);
        }

        public PageSummary(
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
                    List.of(),
                    List.of(),
                    false);
        }

        public PageSummary(
                int pageNumber,
                String printedTerms,
                String factualSummary,
                List<String> keywords,
                List<VisualAnchor> visualAnchors,
                List<IconOccurrence> iconOccurrences,
                boolean iconInventoryComplete) {
            this(
                    pageNumber,
                    printedTerms,
                    factualSummary,
                    keywords,
                    visualAnchors,
                    iconOccurrences,
                    iconInventoryComplete,
                    List.of(),
                    List.of(),
                    false);
        }

        public PageSummary(
                int pageNumber,
                String printedTerms,
                String factualSummary,
                List<String> keywords,
                List<VisualAnchor> visualAnchors,
                List<IconOccurrence> iconOccurrences,
                boolean iconInventoryComplete,
                List<SourceDependency> sourceDependencies) {
            this(
                    pageNumber,
                    printedTerms,
                    factualSummary,
                    keywords,
                    visualAnchors,
                    iconOccurrences,
                    iconInventoryComplete,
                    sourceDependencies,
                    List.of(),
                    false);
        }

        public PageSummary {
            if (pageNumber < 1
                    || (printedTerms != null && printedTerms.length() > 1_600)
                    || (factualSummary != null && factualSummary.length() > 4_000)
                    || (keywords != null && (keywords.size() > 16
                            || keywords.stream().anyMatch(keyword -> keyword == null || keyword.isBlank() || keyword.length() > 120)))
                    || (visualAnchors != null && visualAnchors.size() > 8)
                    || (iconOccurrences != null && iconOccurrences.size() > 32)
                    || sourceDependencies == null
                    || sourceDependencies.size() > 4
                    || ruleGroupIdentifiers == null || ruleGroupIdentifiers.size() > 16
                    || ruleGroupIdentifiers.stream()
                            .anyMatch(identifier -> identifier == null || identifier.isBlank() || identifier.length() > 120)) {
                throw new IllegalArgumentException("visual page summary is invalid");
            }
            printedTerms = printedTerms == null || printedTerms.isBlank()
                    ? "No legible printed term on this page."
                    : printedTerms.strip();
            factualSummary = factualSummary == null || factualSummary.isBlank()
                    ? "该页没有可可靠转写的规则文字；请直接查看页面图像。"
                    : factualSummary.strip();
            keywords = keywords == null || keywords.isEmpty()
                    ? List.of("page " + pageNumber)
                    : keywords.stream().map(String::strip).distinct().toList();
            visualAnchors = visualAnchors == null ? List.of() : visualAnchors.stream().distinct().toList();
            iconOccurrences = iconOccurrences == null ? List.of() : iconOccurrences.stream().distinct().toList();
            sourceDependencies = sourceDependencies.stream().distinct().toList();
            ruleGroupIdentifiers = ruleGroupIdentifiers.stream().map(String::strip).distinct().toList();
        }
    }
}
