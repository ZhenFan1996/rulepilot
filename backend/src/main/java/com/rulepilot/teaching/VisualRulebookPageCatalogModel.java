package com.rulepilot.teaching;

import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
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

    default CatalogDraft summarize(
            CatalogRequest request,
            CaptureHandle capture,
            TraceEventContext context) {
        return summarize(request);
    }

    /**
     * Reads only the page-scoped rule facts needed to start an evidence-bound lesson. Implementations should avoid
     * icon inventories, spatial localization, and dense-cell audits here; those slower enrichments run after the
     * first readable lesson section. The default keeps non-vision test adapters source-compatible.
     */
    default CatalogDraft summarizeForTeaching(CatalogRequest request) {
        return summarize(request);
    }

    default CatalogDraft summarizeForTeaching(
            CatalogRequest request,
            CaptureHandle capture,
            TraceEventContext context) {
        return summarizeForTeaching(request);
    }

    /**
     * Whether this adapter can run a page-local document transcription before semantic rule grouping. The
     * transcription is a separate, audited model call: implementations must not hide it inside
     * {@link #summarizeForTeaching(CatalogRequest)}.
     */
    default boolean supportsTeachingPageTranscription(String modelConfigurationOwner) {
        return false;
    }

    /**
     * Copies visible page text without interpreting rules. The returned text is untrusted source input and must stay
     * bound to the supplied page number when it is passed to the semantic catalog model.
     */
    default PageTranscript transcribeTeachingPage(PageImageInput page, String modelConfigurationOwner) {
        throw new UnsupportedOperationException("visual page transcription is unavailable");
    }

    default PageTranscript transcribeTeachingPage(
            PageImageInput page,
            String modelConfigurationOwner,
            CaptureHandle capture,
            TraceEventContext context) {
        return transcribeTeachingPage(page, modelConfigurationOwner);
    }

    default Optional<ModelExecutionIdentity> teachingPageTranscriptionExecutionIdentity(
            String modelConfigurationOwner) {
        return Optional.empty();
    }

    /**
     * Selects one source page that can safely support the first cited lesson section while returning only a compact
     * structural sketch for every other supplied page. This is an optional fast path: implementations that cannot
     * provide exact structured page bindings leave the existing complete Teaching catalog unchanged.
     */
    default Optional<ProgressiveTeachingStartDraft> selectProgressiveTeachingStart(CatalogRequest request) {
        return Optional.empty();
    }

    default Optional<ProgressiveTeachingStartDraft> selectProgressiveTeachingStart(
            CatalogRequest request,
            CaptureHandle capture,
            TraceEventContext context) {
        return selectProgressiveTeachingStart(request);
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

    default IconLocalizationDraft localizeIcons(
            IconLocalizationRequest request,
            CaptureHandle capture,
            TraceEventContext context) {
        return localizeIcons(request);
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

    default IconCropReviewDraft reviewIconCrops(
            IconCropReviewRequest request,
            CaptureHandle capture,
            TraceEventContext context) {
        return reviewIconCrops(request);
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

    record PageTranscript(int pageNumber, String text) {

        public PageTranscript {
            if (pageNumber < 1 || text == null || text.isBlank()) {
                throw new IllegalArgumentException("visual page transcript is invalid");
            }
        }
    }

    record CatalogRequest(
            List<PageImageInput> pages,
            String modelConfigurationOwner,
            String rulebookTitle,
            PageViewport viewport,
            List<PageTranscript> transcripts) {

        public CatalogRequest(List<PageImageInput> pages, String modelConfigurationOwner) {
            this(pages, modelConfigurationOwner, null, null, List.of());
        }

        public CatalogRequest(List<PageImageInput> pages, String modelConfigurationOwner, String rulebookTitle) {
            this(pages, modelConfigurationOwner, rulebookTitle, null, List.of());
        }

        public CatalogRequest(
                List<PageImageInput> pages,
                String modelConfigurationOwner,
                String rulebookTitle,
                PageViewport viewport) {
            this(pages, modelConfigurationOwner, rulebookTitle, viewport, List.of());
        }

        public CatalogRequest(
                List<PageImageInput> pages,
                String modelConfigurationOwner,
                String rulebookTitle,
                List<PageTranscript> transcripts) {
            this(pages, modelConfigurationOwner, rulebookTitle, null, transcripts);
        }

        public CatalogRequest {
            if (pages == null || pages.isEmpty() || pages.size() > MAX_PAGES_PER_REQUEST) {
                throw new IllegalArgumentException("visual page catalog request is invalid");
            }
            pages = List.copyOf(pages);
            modelConfigurationOwner = modelConfigurationOwner == null || modelConfigurationOwner.isBlank()
                    ? null
                    : modelConfigurationOwner.strip();
            rulebookTitle = rulebookTitle == null || rulebookTitle.isBlank() ? null : rulebookTitle.strip();
            if (viewport != null
                    && (pages.size() != 1 || pages.getFirst().pageNumber() != viewport.pageNumber())) {
                throw new IllegalArgumentException("visual page viewport does not match its image");
            }
            transcripts = transcripts == null ? List.of() : List.copyOf(transcripts);
            if (!transcripts.isEmpty()) {
                Set<Integer> pageNumbers = pages.stream()
                        .map(PageImageInput::pageNumber)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                Set<Integer> transcriptPages = transcripts.stream()
                        .map(PageTranscript::pageNumber)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                if (transcriptPages.size() != transcripts.size() || !transcriptPages.equals(pageNumbers)) {
                    throw new IllegalArgumentException(
                            "visual page transcripts must bind every requested page exactly once");
                }
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

        public SourceDependency {
            if (title == null || title.isBlank()
                    || missingCoverageTags == null
                    || missingCoverageTags.stream().anyMatch(tag -> tag == null || tag.isBlank())) {
                throw new IllegalArgumentException("visual teaching source dependency is invalid");
            }
            title = title.strip();
            missingCoverageTags = missingCoverageTags.stream().map(String::strip).distinct().toList();
        }
    }

    /** Source-derived semantic role for one literal rule-group identifier on a progressive visual page. */
    record RuleGroupCoverage(String identifier, SourceCoverageRole role) {
        public RuleGroupCoverage {
            if (identifier == null || identifier.isBlank() || role == null) {
                throw new IllegalArgumentException("visual teaching rule-group coverage is invalid");
            }
            identifier = identifier.strip();
        }
    }

    record TeachingPageSketch(
            int pageNumber,
            TeachingPageRole role,
            String visibleHeading,
            List<String> visibleTerms,
            List<String> coverageTags,
            boolean ruleGroupInventoryComplete,
            List<SourceDependency> sourceDependencies,
            List<RuleGroupCoverage> ruleGroupCoverage) {

        public TeachingPageSketch {
            if (pageNumber < 1 || role == null
                    || visibleTerms == null
                    || visibleTerms.stream().anyMatch(term -> term == null || term.isBlank())
                    || coverageTags == null
                    || coverageTags.stream().anyMatch(tag -> tag == null || tag.isBlank())
                    || sourceDependencies == null
                    || sourceDependencies.stream().anyMatch(java.util.Objects::isNull)
                    || ruleGroupCoverage == null
                    || ruleGroupCoverage.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("visual teaching page sketch is invalid");
            }
            visibleHeading = visibleHeading == null ? "" : visibleHeading.strip();
            visibleTerms = visibleTerms.stream().map(String::strip).distinct().toList();
            coverageTags = coverageTags.stream().map(String::strip).distinct().toList();
            sourceDependencies = sourceDependencies.stream().distinct().toList();
            ruleGroupCoverage = ruleGroupCoverage.stream().distinct().toList();
            if (role != TeachingPageRole.GAMEPLAY_RULES && !ruleGroupCoverage.isEmpty()) {
                throw new IllegalArgumentException("non-gameplay visual pages cannot own rule-group coverage");
            }
        }

        public TeachingPageSketch(
                int pageNumber,
                TeachingPageRole role,
                String visibleHeading,
                List<String> visibleTerms,
                List<String> coverageTags,
                boolean ruleGroupInventoryComplete,
                List<SourceDependency> sourceDependencies) {
            this(
                    pageNumber,
                    role,
                    visibleHeading,
                    visibleTerms,
                    coverageTags,
                    ruleGroupInventoryComplete,
                    sourceDependencies,
                    List.of());
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
                    List.of(),
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
                    List.of(),
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
            provider = auditValue(provider, "provider");
            model = auditValue(model, "model");
        }

        public String auditLabel() {
            return provider + "/" + model;
        }

        private static String auditValue(String value, String label) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("visual model " + label + " is required");
            }
            return value.strip();
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
            observedLabel = present && observedLabel != null ? observedLabel.strip() : "";
        }

        public static IconLocation absent(int candidateIndex) {
            return new IconLocation(candidateIndex, false, 0, 0, 0, 0, "");
        }
    }

    /** One page-owned rule relation returned as JSON. Player-facing prose is never parsed to rebuild this binding. */
    record RuleGroupFact(String identifier, String label, String fact) {
        public RuleGroupFact {
            if (identifier == null || identifier.isBlank()
                    || label == null || label.isBlank()
                    || fact == null || fact.isBlank()) {
                throw new IllegalArgumentException("visual rule-group fact is invalid");
            }
            identifier = identifier.strip();
            label = label.strip();
            fact = fact.strip();
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
            boolean ruleGroupInventoryComplete,
            List<VisualQuantityObservation> quantityObservations,
            List<RuleGroupFact> ruleGroupFacts) {

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
                    false,
                    List.of(),
                    List.of());
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
                    false,
                    List.of(),
                    List.of());
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
                    false,
                    List.of(),
                    List.of());
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
                    false,
                    List.of(),
                    List.of());
        }

        public PageSummary(
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
            this(
                    pageNumber,
                    printedTerms,
                    factualSummary,
                    keywords,
                    visualAnchors,
                    iconOccurrences,
                    iconInventoryComplete,
                    sourceDependencies,
                    ruleGroupIdentifiers,
                    ruleGroupInventoryComplete,
                    List.of(),
                    List.of());
        }

        public PageSummary(
                int pageNumber,
                String printedTerms,
                String factualSummary,
                List<String> keywords,
                List<VisualAnchor> visualAnchors,
                List<IconOccurrence> iconOccurrences,
                boolean iconInventoryComplete,
                List<SourceDependency> sourceDependencies,
                List<String> ruleGroupIdentifiers,
                boolean ruleGroupInventoryComplete,
                List<VisualQuantityObservation> quantityObservations) {
            this(
                    pageNumber,
                    printedTerms,
                    factualSummary,
                    keywords,
                    visualAnchors,
                    iconOccurrences,
                    iconInventoryComplete,
                    sourceDependencies,
                    ruleGroupIdentifiers,
                    ruleGroupInventoryComplete,
                    quantityObservations,
                    List.of());
        }

        public PageSummary {
            if (pageNumber < 1
                    || (keywords != null
                            && keywords.stream().anyMatch(keyword -> keyword == null || keyword.isBlank()))
                    || (visualAnchors != null && visualAnchors.stream().anyMatch(java.util.Objects::isNull))
                    || (iconOccurrences != null && iconOccurrences.stream().anyMatch(java.util.Objects::isNull))
                    || sourceDependencies == null
                    || sourceDependencies.stream().anyMatch(java.util.Objects::isNull)
                    || ruleGroupIdentifiers == null
                    || ruleGroupIdentifiers.stream()
                            .anyMatch(identifier -> identifier == null || identifier.isBlank())
                    || quantityObservations == null
                    || quantityObservations.stream().anyMatch(java.util.Objects::isNull)
                    || ruleGroupFacts == null
                    || ruleGroupFacts.stream().anyMatch(java.util.Objects::isNull)) {
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
            quantityObservations = List.copyOf(new java.util.LinkedHashSet<>(quantityObservations));
            ruleGroupFacts = ruleGroupFacts.stream().distinct().toList();
            Set<String> ruleGroupIdentities = Set.copyOf(ruleGroupIdentifiers);
            Set<String> factIdentities = ruleGroupFacts.stream()
                    .map(RuleGroupFact::identifier)
                    .collect(java.util.stream.Collectors.toSet());
            if (ruleGroupInventoryComplete && !factIdentities.equals(ruleGroupIdentities)) {
                throw new IllegalArgumentException("visual rule-group facts must exactly match their JSON identifiers");
            }
            if (quantityObservations.stream().anyMatch(observation -> observation.pageNumber() != pageNumber
                    || !ruleGroupIdentities.contains(observation.ruleGroupIdentifier()))) {
                throw new IllegalArgumentException(
                        "visual quantity observation must match its page and rule group");
            }
            VisualQuantityObservation.appendEvidence(factualSummary, quantityObservations);
        }
    }
}
