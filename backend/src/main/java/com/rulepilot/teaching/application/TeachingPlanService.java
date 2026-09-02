package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
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
import com.rulepilot.teaching.VisualRegionProposer;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.visualaid.VisualRegionCatalog;
import java.util.LinkedHashSet;
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
    private static final int VISUAL_PAGE_BASELINE_MODEL_CALLS = 1;
    private static final int OUTLINE_AGENT_BASELINE_MODEL_CALLS = 1;
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
    private final VisualRegionCatalog visualRegions;
    private final VisualRegionProposer visualRegionProposer;

    public TeachingPlanService(
            DocumentProcessing documents,
            DocumentVersionScopeLookup documentScopes,
            CatalogEditionLookup catalog,
            VisualRulebookCataloger visualCataloger,
            TeachingOutlineModel outlines,
            AuditedAgentInvocations invocations,
            TeachingPlanFactory plans,
            TeachingPlanRepository repository,
            TeachingPlanPublication publication,
            VisualRegionCatalog visualRegions,
            VisualRegionProposer visualRegionProposer) {
        this.documents = documents;
        this.documentScopes = documentScopes;
        this.catalog = catalog;
        this.visualCataloger = visualCataloger;
        this.outlines = outlines;
        this.invocations = invocations;
        this.plans = plans;
        this.repository = repository;
        this.publication = publication;
        this.visualRegions = visualRegions;
        this.visualRegionProposer = visualRegionProposer;
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
        boolean canonicalPagePlanning = requiresCanonicalPagePlanning(documentPages);
        var pages = canonicalPagePlanning
                ? visualCataloger.catalogVisualPages(
                        documentVersionId, documentPages, scope.documentTitle(), createdBy, assistantRunId)
                : documentPages.stream()
                        .map(page -> new PageInput(
                                page.pageNumber(), page.text() == null || page.text().isBlank()
                                        ? VISUAL_PAGE_CATALOG
                                        : page.text().strip()))
                        .toList();
        pages = withVisualAidAvailability(documentVersionId, pages, documentPages);
        var outlineRequest = new OutlineRequest(
                pages, List.of(), learningGoal, createdBy);
        var outline = organizeInitialOutline(playerGameTitle, outlineRequest, pages, assistantRunId);
        try {
            plans.validate(outline);
        } catch (IllegalArgumentException invalidOutline) {
            log.warn("Teaching outline action result was unusable: {}", invalidOutline.getMessage());
            if (assistantRunId != null) {
                invocations.record(
                        assistantRunId,
                        ActivityType.VALIDATION,
                        "rejectTeachingOutlineActionResult",
                        ActivityOutcome.REJECTED,
                        "Lesson preparation stopped because the Agent published no usable chapter plan");
            }
            throw new IllegalStateException("teaching outline was unusable; retry preparation", invalidOutline);
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
                                + " pages=" + topic.sourcePageNumbers()
                                + " visualPages=" + topic.visualSourcePageNumbers())
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
        boolean canonicalPagePlanning = requiresCanonicalPagePlanning(pages);
        return preparationWorkload(canonicalPagePlanning, pages.size());
    }

    static WorkloadDemand preparationWorkload(boolean canonicalPagePlanning, int pageCount) {
        if (pageCount < 1) throw new IllegalArgumentException("teaching preparation page count is invalid");
        long visualPageCalls = canonicalPagePlanning
                ? (long) VISUAL_PAGE_BASELINE_MODEL_CALLS * pageCount
                : 0;
        // This sizes queue capacity only. The same outline Agent chooses how many read/publish turns it needs, while
        // the durable run deadline and token reservation remain the resource boundary.
        long estimatedModelCalls = visualPageCalls + OUTLINE_AGENT_BASELINE_MODEL_CALLS;
        if (estimatedModelCalls > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("teaching preparation workload is too large");
        }
        return new WorkloadDemand((int) estimatedModelCalls);
    }

    static boolean requiresExtendedPreparationLane(WorkloadDemand workload) {
        if (workload == null) throw new IllegalArgumentException("teaching preparation workload is required");
        int ordinaryCapacityBaseline = OUTLINE_AGENT_BASELINE_MODEL_CALLS;
        return workload.estimatedModelCalls() > ordinaryCapacityBaseline;
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
        if (requiresCanonicalPagePlanning(documentPages)) {
            visualCataloger.catalogVisualPages(
                    documentVersionId,
                    documentPages,
                    scope.documentTitle(),
                    createdBy,
                    assistantRunId);
        }
    }

    private TeachingOutlineModel.OutlineDraft organizeInitialOutline(
            String playerGameTitle,
            OutlineRequest request,
            List<PageInput> pages,
            UUID assistantRunId) {
        TeachingOutlineModel.OutlineDraft organized;
        try {
            organized = assistantRunId == null
                    ? outlines.organize(request)
                    : outlines.organize(request, modelCalls(assistantRunId));
        } catch (OutlineGenerationException generationFailure) {
            log.warn(
                    "Teaching outline Agent stopped before it published a usable plan (failureType={})",
                    generationFailure.getCause() == null
                            ? generationFailure.getClass().getSimpleName()
                            : generationFailure.getCause().getClass().getSimpleName());
            if (assistantRunId != null) {
                invocations.record(
                        assistantRunId,
                        ActivityType.VALIDATION,
                        "rejectTeachingOutlineGenerationFailure",
                        ActivityOutcome.REJECTED,
                        "Lesson preparation stopped because the outline Agent published no usable chapter before durable execution stopped");
            }
            throw generationFailure;
        }
        return preferDocumentTitle(
                playerGameTitle,
                organized,
                pages);
    }

    static boolean requiresCanonicalPagePlanning(List<DocumentProcessing.PageView> pages) {
        if (pages == null || pages.isEmpty()) return false;
        return pages.stream().allMatch(page -> page.text() == null || page.text().isBlank());
    }

    private List<PageInput> withVisualAidAvailability(
            UUID documentVersionId,
            List<PageInput> pages,
            List<DocumentProcessing.PageView> documentPages) {
        List<VisualRegionCatalog.Region> indexedRegions = new java.util.ArrayList<>();
        List<Integer> pageNumbers = pages.stream().map(PageInput::pageNumber).distinct().sorted().toList();
        if (visualRegions.configured()) {
            try {
                for (int start = 0; start < pageNumbers.size(); start += 64) {
                    Set<Integer> batch = new LinkedHashSet<>(pageNumbers.subList(
                            start, Math.min(start + 64, pageNumbers.size())));
                    indexedRegions.addAll(visualRegions.find(documentVersionId, batch));
                }
            } catch (RuntimeException unavailableIndex) {
                log.warn("Visual aid index was unavailable during lesson planning; continuing with local candidates");
                indexedRegions.clear();
            }
        }
        Set<Integer> visualPages = availableVisualPageNumbers(
                documentPages, visualRegionProposer.configured(), indexedRegions);
        return pages.stream()
                .map(page -> new PageInput(
                        page.pageNumber(),
                        page.text(),
                        page.available(),
                        visualPages.contains(page.pageNumber())))
                .toList();
    }

    static Set<Integer> availableVisualPageNumbers(
            List<DocumentProcessing.PageView> pages,
            boolean localProposalConfigured,
            List<VisualRegionCatalog.Region> indexedRegions) {
        Set<Integer> renderedPages = pages.stream()
                .filter(DocumentProcessing.PageView::imageAvailable)
                .map(DocumentProcessing.PageView::pageNumber)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (localProposalConfigured) return Set.copyOf(renderedPages);
        Set<Integer> indexedPicturePages = indexedRegions.stream()
                .filter(region -> "PICTURE".equals(region.kind()))
                .map(VisualRegionCatalog.Region::pageNumber)
                .collect(java.util.stream.Collectors.toSet());
        renderedPages.retainAll(indexedPicturePages);
        return Set.copyOf(renderedPages);
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
                outline.topicDependencies(),
                outline.unresolvedTopics());
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
                outline.topicDependencies(),
                outline.unresolvedTopics());
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

            @Override
            public void recordRejection(String operation, String summary) {
                invocations.record(
                        assistantRunId,
                        ActivityType.VALIDATION,
                        operation,
                        ActivityOutcome.REJECTED,
                        summary);
            }
        };
    }
}
