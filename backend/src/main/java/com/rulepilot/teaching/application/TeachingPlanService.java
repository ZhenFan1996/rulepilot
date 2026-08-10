package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.catalog.CatalogEditionLookup;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.document.RulebookTitleInferencePolicy;
import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.springframework.context.annotation.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class TeachingPlanService {

    private static final Logger log = LoggerFactory.getLogger(TeachingPlanService.class);
    private static final int MAX_PAGE_CATALOG_CHARACTERS = 3_200;
    private static final int MAX_OUTLINE_PAGE_IMAGES = 4;
    // The first focused rewrite receives every detected boundary conflict. Repeating whole-outline rewrites tends to
    // oscillate on wording while delaying a fully cited lesson; section-level validation still protects every claim.
    private static final int MAX_CHAPTER_OWNERSHIP_REFINEMENTS = 1;
    private static final int MAX_SOURCE_COVERAGE_REFINEMENTS = 1;
    private static final String VISUAL_PAGE_CATALOG =
            "页面文字无法提取；请依据随附的规则书页面图像理解此页内容。";
    private final DocumentProcessing documents;
    private final DocumentPageImages pageImages;
    private final DocumentVersionScopeLookup documentScopes;
    private final CatalogEditionLookup catalog;
    private final VisualRulebookCataloger visualCataloger;
    private final TeachingOutlineModel outlines;
    private final AuditedAgentInvocations invocations;
    private final TeachingPlanFactory plans;
    private final TeachingPlanRepository repository;
    private final TeachingPlanPublication publication;

    public TeachingPlanService(
            DocumentProcessing documents,
            DocumentPageImages pageImages,
            DocumentVersionScopeLookup documentScopes,
            CatalogEditionLookup catalog,
            VisualRulebookCataloger visualCataloger,
            TeachingOutlineModel outlines,
            AuditedAgentInvocations invocations,
            TeachingPlanFactory plans,
            TeachingPlanRepository repository,
            TeachingPlanPublication publication) {
        this.documents = documents;
        this.pageImages = pageImages;
        this.documentScopes = documentScopes;
        this.catalog = catalog;
        this.visualCataloger = visualCataloger;
        this.outlines = outlines;
        this.invocations = invocations;
        this.plans = plans;
        this.repository = repository;
        this.publication = publication;
    }

    public TeachingPlan create(
            UUID documentVersionId,
            String learningGoal,
            String createdBy,
            UUID assistantRunId) {
        var scope = documentScopes.findVersion(documentVersionId)
                .filter(found -> found.createdBy().equals(createdBy))
                .orElseThrow(() -> new IllegalArgumentException("rule document does not exist"));
        if (!"READY".equals(scope.processingStatus())) {
            throw new IllegalArgumentException("rule document is not ready for teaching");
        }
        Optional<String> catalogGameTitle = boundCatalogGameTitle(scope, catalog);
        String playerGameTitle = catalogGameTitle.orElse(scope.documentTitle());
        var documentPages = documents.pages(documentVersionId);
        boolean visualOnly = documentPages.stream().allMatch(page -> page.text() == null || page.text().isBlank());
        boolean textRulebookVisualCatalogAvailable = !visualOnly && visualCataloger.available(createdBy);
        var pages = visualOnly
                ? visualCataloger.catalogVisualPages(
                        documentVersionId, documentPages, scope.documentTitle(), createdBy, assistantRunId)
                : documentPages.stream()
                        .map(page -> new PageInput(
                                page.pageNumber(), page.text() == null || page.text().isBlank()
                                        ? VISUAL_PAGE_CATALOG
                                        : boundedPageText(page.text())))
                        .toList();
        var outlineImages = pageImages.read(
                        documentVersionId,
                        representativePageNumbers(documentPages))
                .stream()
                .map(image -> new PageImageInput(
                        image.pageNumber(), image.mediaType(), image.content()))
                .toList();
        var initialOutlineRequest = new OutlineRequest(
                pages, outlineImages, learningGoal, createdBy);
        var outline = preferDocumentTitle(
                playerGameTitle,
                VisualOutlineEvidencePolicy.bindIconLegendEvidence(invokeModel(
                        assistantRunId,
                        "organizeTeachingOutline",
                        outlineInputTokens(pages),
                        "Rulebook lesson topics organized",
                        () -> outlines.organize(initialOutlineRequest),
                        this::outlineOutputTokens), documentPages),
                pages);
        outline = refineChapterOwnership(
                initialOutlineRequest, outline, assistantRunId, documentPages, playerGameTitle);
        OutlineRequest outlineRequest = initialOutlineRequest;
        if (textRulebookVisualCatalogAvailable) {
            List<PageFact> coverageFacts = visualCataloger.inspectUnownedSparseVisualPages(
                    documentVersionId, outline, documentPages, scope.documentTitle(), createdBy, assistantRunId);
            if (!coverageFacts.isEmpty()) {
                pages = VisualRulebookCatalogPolicy.appendFactsToPageInputs(pages, coverageFacts);
                outlineRequest = new OutlineRequest(
                        pages, outlineImages, learningGoal, createdBy);
            }
        }
        if (requiresModelSourcePageCoverageRevision(visualOnly)) {
            var outlineBeforeCoverageRevision = outline;
            outline = refineSourcePageCoverage(
                    outlineRequest, outline, pages, assistantRunId, documentPages, playerGameTitle);
            if (TeachingOutlineRevisionPolicy.requiresChapterOwnershipRerun(outlineBeforeCoverageRevision, outline)) {
                outline = refineChapterOwnership(outlineRequest, outline, assistantRunId, documentPages, playerGameTitle);
            } else if (assistantRunId != null) {
                invocations.record(
                        assistantRunId,
                        ActivityType.VALIDATION,
                        "skipRedundantTeachingOutlineOwnership",
                        ActivityOutcome.SUCCEEDED,
                        "Source-page coverage did not change chapter ownership; skipped a duplicate outline revision");
            }
        } else if (assistantRunId != null) {
            invocations.record(
                    assistantRunId,
                    ActivityType.VALIDATION,
                    "skipVisualOutlineCoverageRevision",
                    ActivityOutcome.SUCCEEDED,
                    "Visual catalog pages use deterministic whole-rulebook coverage validation; skipped a redundant model revision");
        }
        try {
            if (visualOnly) VisualOutlineEvidencePolicy.validateVisualFastBaseline(outline);
            plans.validate(outline);
            if (visualOnly) {
                outline = VisualOutlineEvidencePolicy.bindVisualCoreTopicEvidence(outline, pages);
                VisualOutlineEvidencePolicy.validateVisualCoreTopicBindings(outline, pages);
            }
        } catch (IllegalArgumentException invalidOutline) {
            log.warn("Teaching outline was incomplete; continuing with a source-derived outline: {}", invalidOutline.getMessage());
            if (assistantRunId != null) {
                invocations.record(
                        assistantRunId,
                        ActivityType.VALIDATION,
                        "fallbackToSourceOutline",
                        ActivityOutcome.REJECTED,
                        "Model outline was incomplete; continuing with a rulebook-derived lesson plan");
            }
            outline = preferDocumentTitle(
                    playerGameTitle, VisualOutlineEvidencePolicy.bindIconLegendEvidence(
                            outlines.fallback(outlineRequest), documentPages), outlineRequest.pages());
            plans.validate(outline);
            if (visualOnly) {
                outline = VisualOutlineEvidencePolicy.bindVisualCoreTopicEvidence(outline, pages);
                VisualOutlineEvidencePolicy.validateVisualCoreTopicBindings(outline, pages);
            }
        }
        if (visualOnly) {
            try {
                VisualOutlineEvidencePolicy.validateVisualRulebookCoverage(outline, pages);
            } catch (IllegalArgumentException incompleteCoverage) {
                log.warn(
                        "Teaching outline omitted visual source pages; adding source-derived coverage: {}",
                        incompleteCoverage.getMessage());
                if (assistantRunId != null) {
                    invocations.record(
                            assistantRunId,
                            ActivityType.VALIDATION,
                            "augmentOutlineCoverageFromSource",
                            ActivityOutcome.REJECTED,
                            "Model outline omitted source pages; source-derived sections were added");
                }
                var sourceOutline = preferDocumentTitle(
                        playerGameTitle, VisualOutlineEvidencePolicy.bindIconLegendEvidence(
                                outlines.fallback(outlineRequest), documentPages), outlineRequest.pages());
                outline = VisualOutlineEvidencePolicy.augmentVisualCoverage(outline, sourceOutline);
                if (outline.topics().size() > 10) {
                    outline = VisualOutlineEvidencePolicy.keepFastVisualBaseline(outline, sourceOutline);
                    if (assistantRunId != null) {
                        invocations.record(
                                assistantRunId,
                                ActivityType.VALIDATION,
                                "compactVisualOutline",
                                ActivityOutcome.REJECTED,
                                "Page coverage made the model outline too fragmented; source-derived teaching groups were retained");
                    }
                }
                log.info(
                        "Using compact visual teaching outline for documentVersionId={}: {}",
                        documentVersionId,
                        outline.topics().stream()
                                .map(topic -> topic.key() + "=" + topic.sourcePageNumbers()
                                        + " tags=" + topic.coverageTags())
                                .toList());
                outline = VisualOutlineEvidencePolicy.bindVisualCoreTopicEvidence(outline, pages);
                // A direct core proof can replace the final source page in a bounded topic. Retain that displaced
                // page as a small source-derived companion instead of silently weakening whole-rulebook coverage.
                outline = VisualOutlineEvidencePolicy.augmentVisualCoverage(outline, sourceOutline);
                plans.validate(outline);
                VisualOutlineEvidencePolicy.validateVisualCoreTopicBindings(outline, pages);
                VisualOutlineEvidencePolicy.validateVisualRulebookCoverage(outline, pages);
            }
        }
        if (visualOnly) validateVisualPageBindings(outline, documentPages);
        if (textRulebookVisualCatalogAvailable && assistantRunId != null) {
            invocations.record(
                    assistantRunId,
                    ActivityType.VALIDATION,
                    "deferSelectedVisualPageCatalog",
                    ActivityOutcome.SUCCEEDED,
                    "Optional selected-page visual interpretation was removed from preparation; later visual workflows interpret evidence on demand");
        }
        if (catalogGameTitle.isPresent()) {
            outline = withGameTitle(catalogGameTitle.orElseThrow(), outline);
        }
        log.info(
                "Teaching outline generated for documentVersionId={}: gameTitle={}, topics={}",
                documentVersionId,
                outline.gameTitle(),
                outline.topics().stream()
                        .map(topic -> topic.key() + " visual=" + topic.visualEvidenceRecommended()
                                + " tags=" + topic.coverageTags() + " queries=" + topic.retrievalQueries())
                        .toList());
        return publication.publish(plans.create(
                documentVersionId,
                learningGoal,
                createdBy,
                outline), outline.gameTitle());
    }

    private TeachingOutlineModel.OutlineDraft refineChapterOwnership(
            OutlineRequest request,
            TeachingOutlineModel.OutlineDraft outline,
            UUID assistantRunId,
            List<DocumentProcessing.PageView> documentPages,
            String documentTitle) {
        TeachingOutlineModel.OutlineDraft current = outline;
        for (int attempt = 1; attempt <= MAX_CHAPTER_OWNERSHIP_REFINEMENTS; attempt++) {
            Optional<String> feedback = TeachingOutlineRevisionPolicy.chapterOwnershipRevisionFeedback(current);
            if (feedback.isEmpty()) return current;
            TeachingOutlineModel.OutlineDraft beforeRefinement = current;
            try {
                var refined = invokeModel(
                        assistantRunId,
                        "refineTeachingOutlineOwnership",
                        Math.max(1, outlineOutputTokens(beforeRefinement) + feedback.get().length() / 4),
                        "Lesson chapters separated so each detailed rule has one home",
                        () -> outlines.refineChapterOwnership(request, beforeRefinement, feedback.get()),
                        this::outlineOutputTokens);
                current = preferDocumentTitle(
                        documentTitle,
                        VisualOutlineEvidencePolicy.bindIconLegendEvidence(refined, documentPages),
                        request.pages());
                if (current.equals(beforeRefinement)) return current;
            } catch (RuntimeException refinementFailure) {
                log.warn("Teaching outline ownership refinement was skipped: {}", refinementFailure.getMessage());
                if (assistantRunId != null) {
                    invocations.record(
                            assistantRunId,
                            ActivityType.VALIDATION,
                            "retainOutlineAfterOwnershipRefinementFailure",
                            ActivityOutcome.REJECTED,
                            "Outline refinement did not complete; the original chapter plan was retained");
                }
                return beforeRefinement;
            }
        }
        return current;
    }

    static boolean requiresModelSourcePageCoverageRevision(boolean visualOnly) {
        return !visualOnly;
    }

    private TeachingOutlineModel.OutlineDraft refineSourcePageCoverage(
            OutlineRequest request,
            TeachingOutlineModel.OutlineDraft outline,
            List<PageInput> pages,
            UUID assistantRunId,
            List<DocumentProcessing.PageView> documentPages,
            String documentTitle) {
        TeachingOutlineModel.OutlineDraft current = outline;
        for (int attempt = 1; attempt <= MAX_SOURCE_COVERAGE_REFINEMENTS; attempt++) {
            Optional<String> feedback = TeachingOutlineRevisionPolicy.sourcePageCoverageRevisionFeedback(current, pages);
            if (feedback.isEmpty()) return current;
            TeachingOutlineModel.OutlineDraft beforeRefinement = current;
            try {
                var refined = invokeModel(
                        assistantRunId,
                        "refineTeachingOutlineCoverage",
                        Math.max(1, outlineOutputTokens(beforeRefinement) + feedback.get().length() / 4),
                        "Lesson topics expanded to cover omitted rulebook pages",
                        () -> outlines.refineChapterOwnership(request, beforeRefinement, feedback.get()),
                        this::outlineOutputTokens);
                current = preferDocumentTitle(
                        documentTitle,
                        VisualOutlineEvidencePolicy.bindIconLegendEvidence(refined, documentPages),
                        request.pages());
                if (current.equals(beforeRefinement)) return current;
            } catch (RuntimeException refinementFailure) {
                log.warn("Teaching outline source-coverage refinement was skipped: {}", refinementFailure.getMessage());
                if (assistantRunId != null) {
                    invocations.record(
                            assistantRunId,
                            ActivityType.VALIDATION,
                            "retainOutlineAfterCoverageRefinementFailure",
                            ActivityOutcome.REJECTED,
                            "Source-page coverage refinement did not complete; the current chapter plan was retained");
                }
                return beforeRefinement;
            }
        }
        return current;
    }

    @Transactional(readOnly = true)
    public Optional<TeachingPlan> latest(UUID documentVersionId, String createdBy) {
        return repository.findLatest(documentVersionId, createdBy);
    }

    @Transactional(readOnly = true)
    public Optional<TeachingPlan> find(UUID planId) {
        return repository.findById(planId);
    }

    @Transactional(readOnly = true)
    public Optional<TeachingPlan> findOwned(UUID planId, String createdBy) {
        return repository.findByIdAndCreatedBy(planId, createdBy);
    }

    @Transactional(readOnly = true)
    public List<TeachingPlan> listOwned(String createdBy) {
        return repository.findAllByCreatedBy(createdBy);
    }

    private String boundedPageText(String text) {
        String value = text.strip();
        return value.length() <= MAX_PAGE_CATALOG_CHARACTERS
                ? value
                : value.substring(0, MAX_PAGE_CATALOG_CHARACTERS) + "…";
    }

    static TeachingOutlineModel.OutlineDraft preferDocumentTitle(
            String documentTitle,
            TeachingOutlineModel.OutlineDraft outline,
            List<PageInput> activeDocumentPages) {
        String selectedTitle = RulebookTitleInferencePolicy.selectPlayerTitle(
                documentTitle,
                outline.gameTitle(),
                activeDocumentPages.stream().map(PageInput::text).toList());
        return new TeachingOutlineModel.OutlineDraft(selectedTitle, outline.premise(), outline.topics());
    }

    static String playerGameTitle(
            DocumentVersionScopeLookup.VersionScope scope,
            CatalogEditionLookup catalog) {
        return boundCatalogGameTitle(scope, catalog).orElse(scope.documentTitle());
    }

    static Optional<String> boundCatalogGameTitle(
            DocumentVersionScopeLookup.VersionScope scope,
            CatalogEditionLookup catalog) {
        if (scope.editionId() == null) return Optional.empty();
        return catalog.findEdition(scope.editionId())
                .map(CatalogEditionLookup.EditionReference::gameName)
                .map(String::strip);
    }

    static TeachingOutlineModel.OutlineDraft withGameTitle(
            String gameTitle,
            TeachingOutlineModel.OutlineDraft outline) {
        return new TeachingOutlineModel.OutlineDraft(gameTitle.strip(), outline.premise(), outline.topics());
    }

    private <T> T invokeModel(
            UUID assistantRunId,
            String operation,
            int inputTokens,
            String successSummary,
            Supplier<T> invocation,
            ToIntFunction<T> outputTokens) {
        if (assistantRunId == null) return invocation.get();
        return invocations.invoke(
                assistantRunId,
                ActivityType.MODEL,
                operation,
                inputTokens,
                successSummary,
                invocation,
                outputTokens);
    }

    private int outlineInputTokens(List<PageInput> pages) {
        return Math.max(1, pages.stream().mapToInt(page -> page.text().length()).sum() / 4);
    }

    private int outlineOutputTokens(TeachingOutlineModel.OutlineDraft outline) {
        int characters = outline.gameTitle().length() + outline.premise().length();
        characters += outline.topics().stream()
                .mapToInt(topic -> topic.title().length()
                        + topic.objective().length()
                        + topic.retrievalQueries().stream().mapToInt(String::length).sum())
                .sum();
        return Math.max(1, characters / 4);
    }

    private void validateVisualPageBindings(
            TeachingOutlineModel.OutlineDraft outline, List<DocumentProcessing.PageView> documentPages) {
        Set<Integer> knownPages = documentPages.stream()
                .map(DocumentProcessing.PageView::pageNumber)
                .collect(java.util.stream.Collectors.toSet());
        boolean invalid = outline.topics().stream().anyMatch(topic -> topic.sourcePageNumbers().isEmpty()
                || topic.sourcePageNumbers().stream().anyMatch(page -> !knownPages.contains(page)));
        if (invalid) {
            throw new IllegalArgumentException("visual rulebook outline must bind every topic to cataloged source pages");
        }
    }

    private Set<Integer> representativePageNumbers(List<DocumentProcessing.PageView> pages) {
        if (pages.size() <= MAX_OUTLINE_PAGE_IMAGES) {
            return pages.stream().map(DocumentProcessing.PageView::pageNumber).collect(java.util.stream.Collectors.toSet());
        }
        Set<Integer> selected = new LinkedHashSet<>();
        for (int index = 0; index < MAX_OUTLINE_PAGE_IMAGES; index++) {
            int pageIndex = (int) Math.round((double) index * (pages.size() - 1) / (MAX_OUTLINE_PAGE_IMAGES - 1));
            selected.add(pages.get(pageIndex).pageNumber());
        }
        return Set.copyOf(selected);
    }
}
