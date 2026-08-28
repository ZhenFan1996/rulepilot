package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.AssistantRuns.WorkloadDemand;
import com.rulepilot.catalog.CatalogEditionLookup;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.document.RulebookTitleInferencePolicy;
import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineGenerationException;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.ModelCall;
import com.rulepilot.teaching.TeachingOutlineModel.ModelCallExecutor;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class TeachingPlanService {

    private static final Logger log = LoggerFactory.getLogger(TeachingPlanService.class);
    // The first focused rewrite receives every detected boundary conflict. Repeating whole-outline rewrites tends to
    // oscillate on wording while delaying a fully cited lesson; section-level validation still protects every claim.
    private static final int MAX_CHAPTER_OWNERSHIP_REFINEMENTS = 1;
    private static final int MAX_SOURCE_COVERAGE_REFINEMENTS = 1;
    private static final int MAX_VISUAL_PAGE_MODEL_ATTEMPTS = 2;
    private static final int MAX_OUTLINE_STAGE_MODEL_ATTEMPTS = 4;
    // Canonical ownership is page-local: all typed slots from one source page are classified together, and pages may
    // run independently. Keeping one shard per page preserves relationships among rules visible on the same page and
    // prevents dense ledgers from expanding into one serial model stage per slot.
    private static final int MAX_CANONICAL_SHARDS_PER_VISUAL_PAGE = 1;
    private static final String VISUAL_PAGE_CATALOG =
            "页面文字无法提取；请依据随附的规则书页面图像理解此页内容。";
    private final DocumentProcessing documents;
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
            DocumentVersionScopeLookup documentScopes,
            CatalogEditionLookup catalog,
            VisualRulebookCataloger visualCataloger,
            TeachingOutlineModel outlines,
            AuditedAgentInvocations invocations,
            TeachingPlanFactory plans,
            TeachingPlanRepository repository,
            TeachingPlanPublication publication) {
        this.documents = documents;
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
                                        : TeachingPageCatalogText.bounded(page.text())))
                        .toList();
        var initialOutlineRequest = new OutlineRequest(
                pages, List.of(), learningGoal, createdBy);
        var outline = organizeInitialOutline(
                visualOnly,
                playerGameTitle,
                initialOutlineRequest,
                pages,
                documentPages,
                assistantRunId);
        OutlineRequest outlineRequest = initialOutlineRequest;
        if (textRulebookVisualCatalogAvailable) {
            List<PageFact> coverageFacts = visualCataloger.inspectUnownedSparseVisualPages(
                    documentVersionId, outline, documentPages, scope.documentTitle(), createdBy, assistantRunId);
            if (!coverageFacts.isEmpty()) {
                pages = VisualRulebookCatalogPolicy.appendFactsToPageInputs(pages, coverageFacts);
                outlineRequest = new OutlineRequest(
                        pages, List.of(), learningGoal, createdBy);
            }
        }
        if (requiresModelSourcePageCoverageRevision(visualOnly)) {
            outline = refineSourcePageCoverage(
                    outlineRequest, outline, pages, assistantRunId, documentPages, playerGameTitle);
        } else if (assistantRunId != null) {
            invocations.record(
                    assistantRunId,
                    ActivityType.VALIDATION,
                    "skipPageLengthOutlineCoverageRevision",
                    ActivityOutcome.SUCCEEDED,
                    "The source-bound concept and coverage contracts remain authoritative; page text length alone did not trigger a second outline model call");
        }
        outline = refineChapterOwnership(
                outlineRequest, outline, assistantRunId, documentPages, playerGameTitle);
        try {
            if (visualOnly || hasStructuredSourceDependencies(pages)) {
                VisualOutlineEvidencePolicy.validateVisualSourceDependencies(outline, pages);
            }
            TeachingSourceCoverageContract.validateAgainstSources(outlineRequest, outline);
            plans.validate(outline);
        } catch (IllegalArgumentException invalidOutline) {
            log.warn("Source-bound teaching outline was incomplete; rejecting preparation: {}", invalidOutline.getMessage());
            if (assistantRunId != null) {
                invocations.record(
                        assistantRunId,
                        ActivityType.VALIDATION,
                        "rejectIncompleteSemanticOutline",
                        ActivityOutcome.REJECTED,
                        "Lesson preparation stopped because the source-bound plan did not satisfy its authoritative contract");
            }
            throw new IllegalStateException(
                    "source-bound teaching outline was incomplete; retry preparation",
                    invalidOutline);
        }
        if (visualOnly || hasStructuredSourceDependencies(pages)) {
            VisualOutlineEvidencePolicy.validateVisualSourceDependencies(outline, pages);
        }
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
        TeachingSourceCoverageContract.validateAgainstSources(outlineRequest, outline);
        try {
            TeachingWholeGameUnderstandingPolicy.validateComplete(outline);
        } catch (IllegalArgumentException incompleteWholeGameUnderstanding) {
            if (assistantRunId != null) {
                invocations.record(
                        assistantRunId,
                        ActivityType.VALIDATION,
                        "rejectIncompleteWholeGameTeachingUnderstanding",
                        ActivityOutcome.REJECTED,
                        "Lesson preparation stopped before chapter fan-out because the source-bound whole-game understanding was incomplete");
            }
            throw new IllegalStateException(
                    "teaching outline did not form a source-bound whole-game understanding; retry preparation",
                    incompleteWholeGameUnderstanding);
        }
        if (assistantRunId != null) {
            invocations.record(
                    assistantRunId,
                    ActivityType.VALIDATION,
                    "completeWholeGameTeachingUnderstanding",
                    ActivityOutcome.SUCCEEDED,
                    "Source-bound whole-game understanding persisted before lesson chapter generation");
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

    WorkloadDemand preparationWorkload(UUID documentVersionId, String createdBy) {
        var scope = documentScopes.findVersion(documentVersionId)
                .filter(found -> found.createdBy().equals(createdBy))
                .orElseThrow(() -> new IllegalArgumentException("rule document does not exist"));
        if (!"READY".equals(scope.processingStatus())) {
            throw new IllegalArgumentException("rule document is not ready for teaching");
        }
        List<DocumentProcessing.PageView> pages = documents.pages(documentVersionId);
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("rule document has no pages to teach");
        }
        boolean visualOnly = pages.stream().allMatch(page -> page.text() == null || page.text().isBlank());
        return preparationWorkload(visualOnly, pages.size());
    }

    static WorkloadDemand preparationWorkload(boolean visualOnly, int pageCount) {
        if (pageCount < 1) throw new IllegalArgumentException("teaching preparation page count is invalid");
        long visualPageCalls = (long) MAX_VISUAL_PAGE_MODEL_ATTEMPTS
                * (visualOnly
                        ? pageCount
                        : Math.min(pageCount, VisualOutlineEvidencePolicy.MAX_INTERPRETED_VISUAL_PAGES));
        // Every planner stage has at most one transport replay and one complete structured-output replacement, whose
        // own transport may replay once: four actual provider calls. Dense visual planning has at most one local
        // ownership shard per page, one global ordering stage, and at most one later global ownership refinement.
        long plannerStages = visualOnly
                ? (long) MAX_CANONICAL_SHARDS_PER_VISUAL_PAGE * pageCount + 2
                : 2;
        long requiredModelCalls = visualPageCalls + MAX_OUTLINE_STAGE_MODEL_ATTEMPTS * plannerStages;
        if (requiredModelCalls > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("teaching preparation workload is too large");
        }
        return new WorkloadDemand(0, (int) requiredModelCalls);
    }

    static boolean requiresExtendedPreparationLane(WorkloadDemand workload) {
        if (workload == null) throw new IllegalArgumentException("teaching preparation workload is required");
        int ordinaryCallCeiling = MAX_VISUAL_PAGE_MODEL_ATTEMPTS
                        * VisualOutlineEvidencePolicy.MAX_INTERPRETED_VISUAL_PAGES
                + MAX_OUTLINE_STAGE_MODEL_ATTEMPTS * 2;
        return workload.requiredModelCalls() > ordinaryCallCeiling;
    }

    void refreshVisualEvidence(
            UUID documentVersionId,
            String createdBy,
            UUID assistantRunId) {
        var scope = documentScopes.findVersion(documentVersionId)
                .filter(found -> found.createdBy().equals(createdBy))
                .orElseThrow(() -> new IllegalArgumentException("rule document does not exist"));
        if (!"READY".equals(scope.processingStatus())) {
            throw new IllegalArgumentException("rule document is not ready for teaching");
        }
        var documentPages = documents.pages(documentVersionId);
        boolean visualOnly = !documentPages.isEmpty()
                && documentPages.stream().allMatch(page -> page.text() == null || page.text().isBlank());
        if (visualOnly) {
            visualCataloger.catalogVisualPages(
                    documentVersionId,
                    documentPages,
                    scope.documentTitle(),
                    createdBy,
                    assistantRunId);
        }
    }

    private static boolean hasStructuredSourceDependencies(List<PageInput> pages) {
        return pages.stream().anyMatch(page -> !page.sourceDependencies().isEmpty());
    }

    private TeachingOutlineModel.OutlineDraft organizeInitialOutline(
            boolean visualOnly,
            String playerGameTitle,
            OutlineRequest request,
            List<PageInput> pages,
            List<DocumentProcessing.PageView> documentPages,
            UUID assistantRunId) {
        TeachingOutlineModel.OutlineDraft organized;
        try {
            organized = assistantRunId == null
                    ? outlines.organize(request)
                    : outlines.organize(request, modelCalls(assistantRunId));
        } catch (OutlineGenerationException generationFailure) {
            log.warn(
                    "Teaching outline generation failed before a source-bound whole-game plan was available "
                            + "(visualOnly={}, failureType={})",
                    visualOnly,
                    generationFailure.getCause() == null
                            ? generationFailure.getClass().getSimpleName()
                            : generationFailure.getCause().getClass().getSimpleName());
            if (assistantRunId != null) {
                invocations.record(
                        assistantRunId,
                        ActivityType.VALIDATION,
                        "rejectTeachingOutlineGenerationFailure",
                        ActivityOutcome.REJECTED,
                        "Lesson preparation stopped because no source-bound whole-game outline survived the bounded model repair");
            }
            throw generationFailure;
        }
        return preferDocumentTitle(
                playerGameTitle,
                VisualOutlineEvidencePolicy.bindIconLegendEvidence(organized, documentPages),
                pages);
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
                var refined = assistantRunId == null
                        ? outlines.refineChapterOwnership(request, beforeRefinement, feedback.get())
                        : outlines.refineChapterOwnership(
                                request,
                                beforeRefinement,
                                feedback.get(),
                                modelCalls(assistantRunId));
                current = preferDocumentTitle(
                        documentTitle,
                        VisualOutlineEvidencePolicy.bindIconLegendEvidence(refined, documentPages),
                        request.pages());
                TeachingSourceCoverageContract.validateAgainstSources(request, current);
                plans.validate(current);
                TeachingWholeGameUnderstandingPolicy.validateComplete(current);
                if (current.equals(beforeRefinement)) return current;
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
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
        return false;
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
                var refined = assistantRunId == null
                        ? outlines.refineChapterOwnership(request, beforeRefinement, feedback.get())
                        : outlines.refineChapterOwnership(
                                request,
                                beforeRefinement,
                                feedback.get(),
                                modelCalls(assistantRunId));
                current = preferDocumentTitle(
                        documentTitle,
                        VisualOutlineEvidencePolicy.bindIconLegendEvidence(refined, documentPages),
                        request.pages());
                TeachingSourceCoverageContract.validateAgainstSources(request, current);
                plans.validate(current);
                TeachingWholeGameUnderstandingPolicy.validateComplete(current);
                if (current.equals(beforeRefinement)) return current;
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
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

    static TeachingOutlineModel.OutlineDraft preferDocumentTitle(
            String documentTitle,
            TeachingOutlineModel.OutlineDraft outline,
            List<PageInput> activeDocumentPages) {
        String selectedTitle = RulebookTitleInferencePolicy.selectPlayerTitle(
                documentTitle,
                outline.gameTitle(),
                activeDocumentPages.stream().map(PageInput::text).toList());
        return new TeachingOutlineModel.OutlineDraft(
                selectedTitle,
                outline.premise(),
                outline.topics(),
                outline.sourceCoverageSlots(),
                outline.sourceCoverageInventoryComplete(),
                outline.wholeGameUnderstanding());
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
        return new TeachingOutlineModel.OutlineDraft(
                gameTitle.strip(),
                outline.premise(),
                outline.topics(),
                outline.sourceCoverageSlots(),
                outline.sourceCoverageInventoryComplete(),
                outline.wholeGameUnderstanding());
    }

    private ModelCallExecutor modelCalls(UUID assistantRunId) {
        if (assistantRunId == null) return ModelCallExecutor.direct();
        return new ModelCallExecutor() {
            @Override
            public <T> T invoke(
                    ModelCall call,
                    java.util.function.Supplier<T> invocation,
                    java.util.function.ToIntFunction<T> outputTokens) {
                return invocations.invoke(
                        assistantRunId,
                        ActivityType.MODEL,
                        call.operation(),
                        call.estimatedInputTokens(),
                        call.successSummary(),
                        invocation,
                        outputTokens);
            }
        };
    }
}
