package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
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
    private static final int MAX_CHAPTER_OWNERSHIP_REFINEMENTS = 3;
    private static final int MAX_SOURCE_COVERAGE_REFINEMENTS = 1;
    private static final int VISUAL_CATALOG_BATCH_SIZE = 2;
    private static final String VISUAL_PAGE_CATALOG =
            "页面文字无法提取；请依据随附的规则书页面图像理解此页内容。";
    private final DocumentProcessing documents;
    private final DocumentPageImages pageImages;
    private final DocumentVersionScopeLookup documentScopes;
    private final VisualRulebookPageCatalogModel visualCatalog;
    private final VisualRulebookPageFacts visualFacts;
    private final TeachingOutlineModel outlines;
    private final AuditedAgentInvocations invocations;
    private final TeachingPlanFactory plans;
    private final TeachingPlanRepository repository;
    private final TeachingPlanPublication publication;
    private final Duration visualCatalogTimeout;
    private final int visualCoverageProbePages;

    public TeachingPlanService(
            DocumentProcessing documents,
            DocumentPageImages pageImages,
            DocumentVersionScopeLookup documentScopes,
            VisualRulebookPageCatalogModel visualCatalog,
            VisualRulebookPageFacts visualFacts,
            TeachingOutlineModel outlines,
            AuditedAgentInvocations invocations,
            TeachingPlanFactory plans,
            TeachingPlanRepository repository,
            TeachingPlanPublication publication,
            @Value("${rulepilot.visual.catalog-timeout:PT45S}") Duration visualCatalogTimeout,
            @Value("${rulepilot.visual.coverage-probe-pages:4}") int visualCoverageProbePages) {
        this.documents = documents;
        this.pageImages = pageImages;
        this.documentScopes = documentScopes;
        this.visualCatalog = visualCatalog;
        this.visualFacts = visualFacts;
        this.outlines = outlines;
        this.invocations = invocations;
        this.plans = plans;
        this.repository = repository;
        this.publication = publication;
        if (visualCatalogTimeout == null || visualCatalogTimeout.isZero() || visualCatalogTimeout.isNegative()) {
            throw new IllegalArgumentException("visual catalog timeout must be positive");
        }
        if (visualCoverageProbePages < 1 || visualCoverageProbePages > VisualOutlineEvidencePolicy.MAX_INTERPRETED_VISUAL_PAGES) {
            throw new IllegalArgumentException("visual coverage probe pages must be between one and "
                    + VisualOutlineEvidencePolicy.MAX_INTERPRETED_VISUAL_PAGES);
        }
        this.visualCatalogTimeout = visualCatalogTimeout;
        this.visualCoverageProbePages = visualCoverageProbePages;
    }

    public TeachingPlan create(
            UUID documentVersionId, int playerCount, int beginnerCount, int durationMinutes, String createdBy) {
        return create(documentVersionId, playerCount, beginnerCount, durationMinutes, createdBy, null);
    }

    public TeachingPlan create(
            UUID documentVersionId,
            int playerCount,
            int beginnerCount,
            int durationMinutes,
            String createdBy,
            UUID assistantRunId) {
        var scope = documentScopes.findVersion(documentVersionId)
                .filter(found -> found.createdBy().equals(createdBy))
                .orElseThrow(() -> new IllegalArgumentException("rule document does not exist"));
        if (!"READY".equals(scope.processingStatus())) {
            throw new IllegalArgumentException("rule document is not ready for teaching");
        }
        var documentPages = documents.pages(documentVersionId);
        boolean visualOnly = documentPages.stream().allMatch(page -> page.text() == null || page.text().isBlank());
        var pages = visualOnly
                ? catalogVisualPages(documentVersionId, documentPages, scope.documentTitle(), createdBy, assistantRunId)
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
                playerCount, beginnerCount, durationMinutes, pages, outlineImages, createdBy);
        var outline = preferDocumentTitle(
                scope.documentTitle(),
                VisualOutlineEvidencePolicy.bindIconLegendEvidence(invokeModel(
                        assistantRunId,
                        "organizeTeachingOutline",
                        outlineInputTokens(pages),
                        "Rulebook lesson topics organized",
                        () -> outlines.organize(initialOutlineRequest),
                        this::outlineOutputTokens), documentPages));
        outline = refineChapterOwnership(
                initialOutlineRequest, outline, assistantRunId, documentPages, scope.documentTitle());
        OutlineRequest outlineRequest = initialOutlineRequest;
        if (!visualOnly && visualCatalog.available(createdBy)) {
            List<PageFact> coverageFacts = inspectUnownedSparseVisualPages(
                    documentVersionId, outline, documentPages, scope.documentTitle(), createdBy, assistantRunId);
            if (!coverageFacts.isEmpty()) {
                pages = mergeVisualFactsIntoPageInputs(pages, coverageFacts);
                outlineRequest = new OutlineRequest(
                        playerCount, beginnerCount, durationMinutes, pages, outlineImages, createdBy);
            }
        }
        var outlineBeforeCoverageRevision = outline;
        outline = refineSourcePageCoverage(outlineRequest, outline, pages, assistantRunId, documentPages, scope.documentTitle());
        if (TeachingOutlineRevisionPolicy.requiresChapterOwnershipRerun(outlineBeforeCoverageRevision, outline)) {
            outline = refineChapterOwnership(outlineRequest, outline, assistantRunId, documentPages, scope.documentTitle());
        } else if (assistantRunId != null) {
            invocations.record(
                    assistantRunId,
                    ActivityType.VALIDATION,
                    "skipRedundantTeachingOutlineOwnership",
                    ActivityOutcome.SUCCEEDED,
                    "Source-page coverage did not change chapter ownership; skipped a duplicate outline revision");
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
                    scope.documentTitle(), VisualOutlineEvidencePolicy.bindIconLegendEvidence(
                            outlines.fallback(outlineRequest), documentPages));
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
                        scope.documentTitle(), VisualOutlineEvidencePolicy.bindIconLegendEvidence(
                                outlines.fallback(outlineRequest), documentPages));
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
        if (!visualOnly && visualCatalog.available(createdBy)) {
            Set<Integer> selectedVisualPages = VisualOutlineEvidencePolicy.selectedVisualPageNumbers(outline, documentPages);
            if (!selectedVisualPages.isEmpty()) {
                try {
                    Set<Integer> cachedPages = visualFacts.find(documentVersionId, selectedVisualPages).stream()
                            .map(PageFact::pageNumber)
                            .collect(Collectors.toSet());
                    Set<Integer> uncatalogedPages = selectedVisualPages.stream()
                            .filter(page -> !cachedPages.contains(page))
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                    if (uncatalogedPages.isEmpty()) {
                        log.info(
                                "Visual page interpretation reused existing facts for document {} pages {}",
                                documentVersionId,
                                selectedVisualPages);
                    } else {
                        List<PageFact> interpreted = catalogPageFacts(
                                documentVersionId,
                                uncatalogedPages,
                                VisualOutlineEvidencePolicy.iconLegendPage(documentPages).orElse(null),
                                scope.documentTitle(),
                                createdBy,
                                assistantRunId);
                        if (interpreted.isEmpty()) {
                            log.warn(
                                    "Visual page interpretation produced no usable facts for document {} pages {}",
                                    documentVersionId,
                                    uncatalogedPages);
                        } else {
                            visualFacts.merge(documentVersionId, interpreted);
                            log.info(
                                    "Visual page interpretation stored document {} pages {}",
                                    documentVersionId,
                                    interpreted.stream().map(PageFact::pageNumber).toList());
                        }
                    }
                } catch (RuntimeException visualFailure) {
                    log.warn(
                            "Visual page interpretation skipped for document {} pages {}",
                            documentVersionId,
                            selectedVisualPages,
                            visualFailure);
                }
            }
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
                playerCount,
                beginnerCount,
                durationMinutes,
                createdBy,
                outline), outline.gameTitle());
    }

    /**
     * Reads only the currently unowned pages whose extracted text is too sparse for the normal coverage ledger.
     * A page enters a later plan revision only if the vision catalog returns a concrete page-local observation; a
     * missing, unreadable, cover, or non-gameplay page remains unbound rather than becoming a made-up chapter.
     */
    private List<PageFact> inspectUnownedSparseVisualPages(
            UUID documentVersionId,
            TeachingOutlineModel.OutlineDraft outline,
            List<DocumentProcessing.PageView> documentPages,
            String rulebookTitle,
            String owner,
            UUID assistantRunId) {
        Set<Integer> selected = VisualOutlineEvidencePolicy.unownedSparseVisualCoveragePageNumbers(
                outline, documentPages, visualCoverageProbePages);
        if (selected.isEmpty()) return List.of();
        List<PageFact> cached = visualFacts.find(documentVersionId, selected);
        Set<Integer> cachedPages = cached.stream().map(PageFact::pageNumber).collect(Collectors.toSet());
        Set<Integer> missing = selected.stream()
                .filter(page -> !cachedPages.contains(page))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<PageFact> fresh;
        try {
            fresh = missing.isEmpty()
                    ? List.of()
                    : catalogPageFacts(documentVersionId, missing, null, rulebookTitle, owner, assistantRunId);
        } catch (RuntimeException visualFailure) {
            log.warn(
                    "Sparse-page visual coverage probe skipped for document {} pages {}",
                    documentVersionId,
                    missing,
                    visualFailure);
            return cached;
        }
        if (!fresh.isEmpty()) {
            visualFacts.merge(documentVersionId, fresh);
            log.info(
                    "Sparse-page visual coverage probe stored document {} pages {}",
                    documentVersionId,
                    fresh.stream().map(PageFact::pageNumber).toList());
        }
        return mergeVisualPageFacts(cached, fresh);
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
                        documentTitle, VisualOutlineEvidencePolicy.bindIconLegendEvidence(refined, documentPages));
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
                        documentTitle, VisualOutlineEvidencePolicy.bindIconLegendEvidence(refined, documentPages));
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

    private List<PageInput> catalogVisualPages(
            UUID documentVersionId,
            List<DocumentProcessing.PageView> documentPages,
            String rulebookTitle,
            String owner,
            UUID assistantRunId) {
        Set<Integer> requestedPages = documentPages.stream()
                .map(DocumentProcessing.PageView::pageNumber)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<PageFact> cached = visualFacts.find(documentVersionId, requestedPages);
        Set<Integer> missingPages = missingVisualCatalogPages(requestedPages, cached);
        Set<Integer> anchorlessPages = anchorlessVisualCatalogPages(cached);
        if (!cached.isEmpty() && assistantRunId != null) {
            invocations.record(
                    assistantRunId,
                    ActivityType.VALIDATION,
                    "reuseVisualPageFacts",
                    ActivityOutcome.SUCCEEDED,
                    "Reused " + cached.size() + " page-scoped visual facts from this immutable rulebook version");
        }
        // A first pass catalogs every rendered page. Existing ledgers are authoritative, except for a one-time
        // compact-anchor backfill after the visual index schema gains anchors. That backfill preserves the prior
        // factual ledger and only adds newly verified page-local rectangles, so it cannot rewrite a published lesson.
        List<PageFact> fresh = cached.isEmpty()
                ? catalogPageFacts(documentVersionId, requestedPages, null, rulebookTitle, owner, assistantRunId)
                : anchorlessPages.isEmpty()
                        ? List.of()
                        : catalogPageFacts(documentVersionId, anchorlessPages, null, rulebookTitle, owner, assistantRunId);
        if (!cached.isEmpty() && !anchorlessPages.isEmpty() && assistantRunId != null) {
            invocations.record(
                    assistantRunId,
                    ActivityType.VALIDATION,
                    "backfillVisualAnchors",
                    ActivityOutcome.SUCCEEDED,
                    "Rechecked " + anchorlessPages.size()
                            + " cached visual page(s) for compact icon, diagram, and example anchors");
        }
        if (!cached.isEmpty() && !missingPages.isEmpty() && assistantRunId != null) {
            invocations.record(
                    assistantRunId,
                    ActivityType.VALIDATION,
                    "deferUncatalogedVisualPages",
                    ActivityOutcome.REJECTED,
                    "Deferred " + missingPages.size()
                            + " uncataloged page(s) to focused visual retrieval; cached page facts can start the lesson");
        }
        List<PageFact> facts = cached.isEmpty()
                ? mergeVisualPageFacts(cached, fresh)
                : mergeVisualPageAnchorBackfill(cached, fresh);
        if (facts.isEmpty()) {
            throw new IllegalArgumentException("visual rulebook catalog did not produce any reliable page facts");
        }
        if (!fresh.isEmpty()) visualFacts.merge(documentVersionId, facts);
        int unavailablePages = documentPages.size() - facts.size();
        if (unavailablePages > 0) {
            log.warn(
                    "Visual catalog completed {} of {} pages for document {}; retaining {} source pages without visual claims",
                    facts.size(), documentPages.size(), documentVersionId, unavailablePages);
            if (assistantRunId != null) {
                invocations.record(
                        assistantRunId,
                        ActivityType.VALIDATION,
                        "retainPartialVisualCatalog",
                        ActivityOutcome.REJECTED,
                        "Visual catalog was incomplete; completed facts are used and remaining source pages stay in the outline");
            }
        }
        return visualPageInputs(documentPages, facts);
    }

    static Set<Integer> missingVisualCatalogPages(Set<Integer> requestedPages, List<PageFact> cached) {
        LinkedHashSet<Integer> missing = new LinkedHashSet<>(requestedPages);
        cached.stream().map(PageFact::pageNumber).forEach(missing::remove);
        return java.util.Collections.unmodifiableSet(missing);
    }

    static Set<Integer> anchorlessVisualCatalogPages(List<PageFact> cached) {
        return cached.stream()
                .filter(fact -> fact.visualAnchors().isEmpty())
                .map(PageFact::pageNumber)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    static List<PageFact> mergeVisualPageFacts(List<PageFact> cached, List<PageFact> fresh) {
        return java.util.stream.Stream.concat(cached.stream(), fresh.stream())
                .collect(java.util.stream.Collectors.toMap(
                        PageFact::pageNumber,
                        java.util.function.Function.identity(),
                        (existing, ignored) -> existing,
                        java.util.LinkedHashMap::new))
                .values().stream()
                .sorted(java.util.Comparator.comparingInt(PageFact::pageNumber))
                .toList();
    }

    static List<PageFact> mergeVisualPageAnchorBackfill(List<PageFact> cached, List<PageFact> fresh) {
        Map<Integer, PageFact> freshByPage = fresh.stream().collect(Collectors.toMap(
                PageFact::pageNumber, java.util.function.Function.identity(), (first, ignored) -> first));
        List<PageFact> retained = cached.stream()
                .map(existing -> {
                    PageFact refreshed = freshByPage.remove(existing.pageNumber());
                    if (refreshed == null || refreshed.visualAnchors().isEmpty()) return existing;
                    return new PageFact(
                            existing.pageNumber(),
                            existing.printedTerms(),
                            existing.factualSummary(),
                            existing.keywords(),
                            refreshed.visualAnchors());
                })
                .toList();
        return java.util.stream.Stream.concat(retained.stream(), freshByPage.values().stream())
                .sorted(java.util.Comparator.comparingInt(PageFact::pageNumber))
                .toList();
    }

    static List<PageInput> visualPageInputs(
            List<DocumentProcessing.PageView> documentPages, List<PageFact> facts) {
        Map<Integer, PageFact> factsByPage = facts.stream().collect(Collectors.toMap(
                PageFact::pageNumber, java.util.function.Function.identity(), (first, duplicate) -> first));
        return documentPages.stream()
                .map(page -> visualPageInput(page.pageNumber(), factsByPage.get(page.pageNumber())))
                .toList();
    }

    /**
     * Preserves the extracted text used by ordinary outline planning while appending a verified page-local visual
     * ledger for an otherwise sparse source page. The catalog is deliberately marked as navigation data: a later
     * teaching step still has to retrieve and cite immutable source evidence from that exact page.
    */
    static List<PageInput> mergeVisualFactsIntoPageInputs(List<PageInput> pages, List<PageFact> facts) {
        if (pages == null || pages.isEmpty() || facts == null || facts.isEmpty()) {
            return pages == null ? List.of() : List.copyOf(pages);
        }
        Map<Integer, PageFact> factsByPage = facts.stream().collect(Collectors.toMap(
                PageFact::pageNumber, java.util.function.Function.identity(), (first, ignored) -> first));
        return pages.stream()
                .map(page -> {
                    PageFact fact = factsByPage.get(page.pageNumber());
                    if (fact == null) return page;
                    return new PageInput(
                            page.pageNumber(),
                            page.text() + "\n\n" + visualPageInput(page.pageNumber(), fact).text());
                })
                .toList();
    }

    private static PageInput visualPageInput(int pageNumber, PageFact fact) {
        if (fact == null) {
            return new PageInput(
                    pageNumber,
                    TeachingOutlineRevisionPolicy.VISUAL_CATALOG_PREFIX
                            + "\nPrinted terms: unavailable because visual interpretation did not finish."
                            + "\nVisible facts: No factual visual claim is available for this page. Keep its source binding"
                            + " and verify the original page image before teaching any detail."
                            + "\nKeywords: visual source page "
                            + pageNumber
                            + ", incomplete visual catalog");
        }
        return new PageInput(
                pageNumber,
                TeachingOutlineRevisionPolicy.VISUAL_CATALOG_PREFIX
                        + "\nPrinted terms: "
                        + fact.printedTerms()
                        + "\nVisible facts: "
                        + fact.factualSummary()
                        + "\nKeywords: "
                        + String.join(", ", fact.keywords()));
    }

    private List<PageFact> catalogPageFacts(
            UUID documentVersionId,
            Set<Integer> pageNumbers,
            Integer iconLegendPage,
            String rulebookTitle,
            String owner,
            UUID assistantRunId) {
        List<Integer> orderedPages = pageNumbers.stream().sorted().toList();
        List<List<Integer>> batches = iconLegendPage != null && pageNumbers.contains(iconLegendPage)
                ? crossPageIconBatches(orderedPages, iconLegendPage)
                : java.util.stream.IntStream.range(0, orderedPages.size())
                        .boxed()
                        .collect(java.util.stream.Collectors.groupingBy(
                                index -> index / VISUAL_CATALOG_BATCH_SIZE,
                                LinkedHashMap::new,
                                java.util.stream.Collectors.mapping(orderedPages::get, java.util.stream.Collectors.toList())))
                        .values().stream().toList();
        if (batches.isEmpty()) throw new IllegalArgumentException("rulebook has no pages to catalog");
        List<VisualRulebookPageCatalogModel.PageSummary> summaries = new java.util.ArrayList<>();
        int parallelism = Math.min(4, batches.size());
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<VisualRulebookPageCatalogModel.CatalogDraft>> futures = java.util.stream.IntStream
                    .range(0, batches.size())
                    .mapToObj(batchIndex -> executor.submit(() -> {
                        List<PageImageInput> images = pageImages.read(
                                        documentVersionId, new LinkedHashSet<>(batches.get(batchIndex)))
                                .stream()
                                .map(image -> new PageImageInput(
                                        image.pageNumber(), image.mediaType(), image.content()))
                                .toList();
                        var request = new VisualRulebookPageCatalogModel.CatalogRequest(images, owner, rulebookTitle);
                        return invokeModel(
                                assistantRunId,
                                "inspectRulebookVisualBatch|" + (batchIndex + 1),
                                Math.max(1, images.size() * 800),
                                "Rulebook visual batch interpreted",
                                () -> visualCatalog.summarize(request),
                                this::catalogOutputTokens);
                    }))
                    .toList();
            for (int index = 0; index < futures.size(); index++) {
                try {
                    summaries.addAll(awaitCatalog(futures.get(index), visualCatalogTimeout).pages());
                } catch (RuntimeException failedBatch) {
                    String operation = "inspectRulebookVisualBatch|" + (index + 1);
                    if (catalogTimedOut(failedBatch)) {
                        invocations.stopRunning(
                                assistantRunId,
                                operation,
                                ActivityOutcome.FAILED,
                                "Visual page batch timed out; retaining completed page facts");
                    }
                    log.warn(
                            "Visual page interpretation skipped failed batch {} for document {}",
                            batches.get(index),
                            documentVersionId,
                            failedBatch);
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return summaries.stream()
                .sorted(java.util.Comparator.comparingInt(VisualRulebookPageCatalogModel.PageSummary::pageNumber))
                .collect(java.util.stream.Collectors.toMap(
                        VisualRulebookPageCatalogModel.PageSummary::pageNumber,
                        java.util.function.Function.identity(),
                        (first, duplicate) -> first,
                        LinkedHashMap::new))
                .values().stream()
                .filter(summary -> pageNumbers.contains(summary.pageNumber()))
                .map(summary -> new PageFact(
                        summary.pageNumber(),
                        summary.printedTerms(),
                        summary.factualSummary(),
                        summary.keywords(),
                        summary.visualAnchors()))
                .toList();
    }

    private List<List<Integer>> crossPageIconBatches(List<Integer> pages, int legendPage) {
        List<Integer> targets = pages.stream().filter(page -> page != legendPage).toList();
        if (targets.isEmpty()) return List.of(List.of(legendPage));
        java.util.ArrayList<List<Integer>> batches = new java.util.ArrayList<>();
        batches.add(List.of(legendPage));
        targets.forEach(page -> batches.add(List.of(legendPage, page)));
        return List.copyOf(batches);
    }

    static TeachingOutlineModel.OutlineDraft preferDocumentTitle(
            String documentTitle, TeachingOutlineModel.OutlineDraft outline) {
        if (documentTitle == null || documentTitle.isBlank()) return outline;
        return new TeachingOutlineModel.OutlineDraft(documentTitle.strip(), outline.premise(), outline.topics());
    }

    static VisualRulebookPageCatalogModel.CatalogDraft awaitCatalog(
            Future<VisualRulebookPageCatalogModel.CatalogDraft> future, Duration timeout) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException slowProvider) {
            future.cancel(true);
            throw new IllegalStateException(
                    "visual rulebook catalog timed out after " + timeout.toSeconds() + " seconds", slowProvider);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("visual rulebook catalog was interrupted", interrupted);
        } catch (ExecutionException failed) {
            throw new IllegalStateException("visual rulebook catalog failed", failed.getCause());
        }
    }

    private static boolean catalogTimedOut(RuntimeException failure) {
        return failure.getCause() instanceof TimeoutException;
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

    private int catalogOutputTokens(VisualRulebookPageCatalogModel.CatalogDraft catalog) {
        int characters = catalog.pages().stream()
                .mapToInt(page -> page.printedTerms().length()
                        + page.factualSummary().length()
                        + page.keywords().stream().mapToInt(String::length).sum())
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
