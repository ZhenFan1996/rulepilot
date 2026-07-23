package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.document.DocumentTeachingPreparation;
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
import java.util.Locale;
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
import java.util.regex.Pattern;
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
    private static final int MAX_INTERPRETED_VISUAL_PAGES = 4;
    private static final int MAX_TOPIC_SOURCE_PAGES = 5;
    private static final int MAX_CHAPTER_OWNERSHIP_REFINEMENTS = 3;
    private static final int MAX_SOURCE_COVERAGE_REFINEMENTS = 1;
    private static final int VISUAL_CATALOG_BATCH_SIZE = 2;
    private static final String VISUAL_CATALOG_PREFIX = "[Visual page catalog; verify against page image]";
    private static final String VISUAL_PAGE_CATALOG =
            "页面文字无法提取；请依据随附的规则书页面图像理解此页内容。";
    private static final Pattern LIKELY_MISSING_INLINE_ICON = Pattern.compile(
            "(?iu)(?:pay|gain|spend|cost|trade|have|place|with|point|支付|获得|花费|费用|交换|拥有|"
                    + "放置|得分)[^\\n]{0,80}(?:\\d+\\s{2,}|\\s{2,}[,.;，。；])");
    private static final Pattern COMPONENT_TERM = Pattern.compile(
            "(?iu)\\b(?:components?|contents?|tokens?|markers?|tiles?|cards?|pieces?|meeples?|cubes?|discs?|"
                    + "coins?|resources?)\\b|组件|配件|内容物|标记|指示物|令牌|板块|卡牌|棋子|方块|圆片|金币|资源");
    private static final Pattern COMPONENT_LEGEND_CUE = Pattern.compile(
            "(?iu)\\b(?:component|contents?|legend|reference|icon|symbol|setup|setting up)\\b|"
                    + "组件|配件|内容物|图例|速查|图标|符号|设置");
    private static final Pattern COMPONENT_ALLOCATION_CUE = Pattern.compile(
            "(?iu)\\b(?:each player|give|receive|take|place|front|behind|starting supply)\\b|"
                    + "每位玩家|发给|获得|拿取|放置|面前|屏风后|初始资源");
    private static final List<ChapterOwnershipDomain> CHAPTER_OWNERSHIP_DOMAINS = List.of(
            new ChapterOwnershipDomain(
                    "cost or imprint procedure",
                    List.of("cost", "payment", "imprint", "memory", "费用", "成本", "支付", "铭刻", "记忆"),
                    List.of("cost", "pay", "payment", "spend", "discount", "extra cost", "费用", "成本", "支付", "花费", "折扣", "额外成本")),
            new ChapterOwnershipDomain(
                    "emotion-card procedure",
                    List.of("emotion", "card", "feel", "情感", "情绪", "卡牌", "拿牌"),
                    List.of("face-up", "draw", "take a card", "matching card", "bonus", "面朝上", "抽牌", "拿取", "额外抽", "奖励")),
            new ChapterOwnershipDomain(
                    "cleanup procedure",
                    List.of("cleanup", "discard", "refill", "清理", "弃牌", "补充"),
                    List.of("hand limit", "discard pile", "refill", "replenish", "手牌上限", "弃牌堆", "补牌", "补充")),
            new ChapterOwnershipDomain(
                    "game-end trigger",
                    List.of("end of game", "game end", "finish", "inner compass", "游戏结束", "终局", "指南针"),
                    List.of("game end", "end trigger", "finish the game", "inner compass", "游戏结束", "结束触发", "终局", "内心指南针")),
            new ChapterOwnershipDomain(
                    "final scoring or tie breaker",
                    List.of("final scoring", "tie", "winner", "最终计分", "平局", "胜者"),
                    List.of("final scoring", "tie", "winner", "最终计分", "平局", "胜者")),
            new ChapterOwnershipDomain(
                    "component or icon mapping",
                    List.of("component", "icon", "组件", "图标"),
                    List.of("component", "icon", "symbol", "组件", "图标", "符号")));
    private final DocumentProcessing documents;
    private final DocumentPageImages pageImages;
    private final DocumentVersionScopeLookup documentScopes;
    private final DocumentTeachingPreparation documentPreparation;
    private final VisualRulebookPageCatalogModel visualCatalog;
    private final VisualRulebookPageFacts visualFacts;
    private final TeachingOutlineModel outlines;
    private final AuditedAgentInvocations invocations;
    private final TeachingPlanFactory plans;
    private final TeachingPlanRepository repository;
    private final Duration visualCatalogTimeout;
    private final int visualCoverageProbePages;

    public TeachingPlanService(
            DocumentProcessing documents,
            DocumentPageImages pageImages,
            DocumentVersionScopeLookup documentScopes,
            DocumentTeachingPreparation documentPreparation,
            VisualRulebookPageCatalogModel visualCatalog,
            VisualRulebookPageFacts visualFacts,
            TeachingOutlineModel outlines,
            AuditedAgentInvocations invocations,
            TeachingPlanFactory plans,
            TeachingPlanRepository repository,
            @Value("${rulepilot.visual.catalog-timeout:PT45S}") Duration visualCatalogTimeout,
            @Value("${rulepilot.visual.coverage-probe-pages:4}") int visualCoverageProbePages) {
        this.documents = documents;
        this.pageImages = pageImages;
        this.documentScopes = documentScopes;
        this.documentPreparation = documentPreparation;
        this.visualCatalog = visualCatalog;
        this.visualFacts = visualFacts;
        this.outlines = outlines;
        this.invocations = invocations;
        this.plans = plans;
        this.repository = repository;
        if (visualCatalogTimeout == null || visualCatalogTimeout.isZero() || visualCatalogTimeout.isNegative()) {
            throw new IllegalArgumentException("visual catalog timeout must be positive");
        }
        if (visualCoverageProbePages < 1 || visualCoverageProbePages > MAX_INTERPRETED_VISUAL_PAGES) {
            throw new IllegalArgumentException("visual coverage probe pages must be between one and "
                    + MAX_INTERPRETED_VISUAL_PAGES);
        }
        this.visualCatalogTimeout = visualCatalogTimeout;
        this.visualCoverageProbePages = visualCoverageProbePages;
    }

    @Transactional
    public TeachingPlan create(
            UUID documentVersionId, int playerCount, int beginnerCount, int durationMinutes, String createdBy) {
        return create(documentVersionId, playerCount, beginnerCount, durationMinutes, createdBy, null);
    }

    @Transactional
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
                bindIconLegendEvidence(invokeModel(
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
        if (requiresChapterOwnershipRerun(outlineBeforeCoverageRevision, outline)) {
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
            if (visualOnly) validateVisualFastBaseline(outline);
            plans.validate(outline);
            if (visualOnly) {
                outline = bindVisualCoreTopicEvidence(outline, pages);
                validateVisualCoreTopicBindings(outline, pages);
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
                    scope.documentTitle(), bindIconLegendEvidence(outlines.fallback(outlineRequest), documentPages));
            plans.validate(outline);
            if (visualOnly) {
                outline = bindVisualCoreTopicEvidence(outline, pages);
                validateVisualCoreTopicBindings(outline, pages);
            }
        }
        if (visualOnly) {
            try {
                validateVisualRulebookCoverage(outline, pages);
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
                        scope.documentTitle(), bindIconLegendEvidence(outlines.fallback(outlineRequest), documentPages));
                outline = augmentVisualCoverage(outline, sourceOutline);
                if (outline.topics().size() > 10) {
                    outline = keepFastVisualBaseline(outline, sourceOutline);
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
                outline = bindVisualCoreTopicEvidence(outline, pages);
                // A direct core proof can replace the final source page in a bounded topic. Retain that displaced
                // page as a small source-derived companion instead of silently weakening whole-rulebook coverage.
                outline = augmentVisualCoverage(outline, sourceOutline);
                plans.validate(outline);
                validateVisualCoreTopicBindings(outline, pages);
                validateVisualRulebookCoverage(outline, pages);
            }
        }
        if (visualOnly) validateVisualPageBindings(outline, documentPages);
        if (!visualOnly && visualCatalog.available(createdBy)) {
            Set<Integer> selectedVisualPages = selectedVisualPageNumbers(outline, documentPages);
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
                                iconLegendPage(documentPages).orElse(null),
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
        documentPreparation.prepare(documentVersionId, createdBy, outline.gameTitle());
        log.info(
                "Teaching outline generated for documentVersionId={}: gameTitle={}, topics={}",
                documentVersionId,
                outline.gameTitle(),
                outline.topics().stream()
                        .map(topic -> topic.key() + " visual=" + topic.visualEvidenceRecommended()
                                + " tags=" + topic.coverageTags() + " queries=" + topic.retrievalQueries())
                        .toList());
        return repository.save(plans.create(
                documentVersionId,
                playerCount,
                beginnerCount,
                durationMinutes,
                createdBy,
                outline));
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
        Set<Integer> selected = unownedSparseVisualCoveragePageNumbers(outline, documentPages, visualCoverageProbePages);
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
            Optional<String> feedback = chapterOwnershipRevisionFeedback(current);
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
                current = preferDocumentTitle(documentTitle, bindIconLegendEvidence(refined, documentPages));
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
            Optional<String> feedback = sourcePageCoverageRevisionFeedback(current, pages);
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
                current = preferDocumentTitle(documentTitle, bindIconLegendEvidence(refined, documentPages));
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

    static boolean requiresChapterOwnershipRerun(
            TeachingOutlineModel.OutlineDraft outlineBeforeCoverageRevision,
            TeachingOutlineModel.OutlineDraft outlineAfterCoverageRevision) {
        if (outlineBeforeCoverageRevision == null || outlineAfterCoverageRevision == null) {
            throw new IllegalArgumentException("outline revisions are required");
        }
        return !outlineBeforeCoverageRevision.equals(outlineAfterCoverageRevision);
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
                    VISUAL_CATALOG_PREFIX
                            + "\nPrinted terms: unavailable because visual interpretation did not finish."
                            + "\nVisible facts: No factual visual claim is available for this page. Keep its source binding"
                            + " and verify the original page image before teaching any detail."
                            + "\nKeywords: visual source page "
                            + pageNumber
                            + ", incomplete visual catalog");
        }
        return new PageInput(
                pageNumber,
                VISUAL_CATALOG_PREFIX
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

    static TeachingOutlineModel.OutlineDraft bindIconLegendEvidence(
            TeachingOutlineModel.OutlineDraft outline, List<DocumentProcessing.PageView> pages) {
        Optional<Integer> legend = iconLegendPage(pages);
        if (legend.isEmpty()) return outline;
        Map<Integer, DocumentProcessing.PageView> pagesByNumber = pages.stream().collect(Collectors.toMap(
                DocumentProcessing.PageView::pageNumber, java.util.function.Function.identity()));
        List<TeachingOutlineModel.TopicDraft> topics = outline.topics().stream()
                .map(topic -> {
                    boolean needsLegend = topic.sourcePageNumbers().stream()
                            .map(pagesByNumber::get)
                            .filter(java.util.Objects::nonNull)
                    .anyMatch(page -> missingInlineIconScore(page.text()) > 0);
                    if (!needsLegend || topic.sourcePageNumbers().contains(legend.get())) return topic;
                    List<Integer> sourcePages = new java.util.ArrayList<>(topic.sourcePageNumbers());
                    if (sourcePages.size() == MAX_TOPIC_SOURCE_PAGES) {
                        sourcePages.removeLast();
                    }
                    sourcePages.add(legend.get());
                    return new TeachingOutlineModel.TopicDraft(
                            topic.key(),
                            topic.title(),
                            topic.objective(),
                            topic.required(),
                            topic.visualEvidenceRecommended(),
                            topic.retrievalQueries(),
                            topic.coverageTags(),
                            sourcePages);
                })
                .toList();
        return new TeachingOutlineModel.OutlineDraft(outline.gameTitle(), outline.premise(), topics);
    }

    static TeachingOutlineModel.OutlineDraft preferDocumentTitle(
            String documentTitle, TeachingOutlineModel.OutlineDraft outline) {
        if (documentTitle == null || documentTitle.isBlank()) return outline;
        return new TeachingOutlineModel.OutlineDraft(documentTitle.strip(), outline.premise(), outline.topics());
    }

    static void validateVisualRulebookCoverage(
            TeachingOutlineModel.OutlineDraft outline, List<PageInput> visualCatalogPages) {
        Set<Integer> expected = visualCatalogPages.stream()
                .filter(page -> substantiveVisualCatalogPage(page.text()))
                .map(PageInput::pageNumber)
                .collect(Collectors.toSet());
        Set<Integer> covered = outline.topics().stream()
                .flatMap(topic -> topic.sourcePageNumbers().stream())
                .collect(Collectors.toSet());
        expected.removeAll(covered);
        if (!expected.isEmpty()) {
            throw new IllegalArgumentException("visual rulebook outline omitted substantive source pages " + expected);
        }
    }

    static void validateVisualCoreTopicBindings(
            TeachingOutlineModel.OutlineDraft outline, List<PageInput> visualCatalogPages) {
        Map<Integer, String> pages = visualCatalogPages.stream().collect(Collectors.toMap(
                PageInput::pageNumber, PageInput::text, (first, duplicate) -> first));
        for (String tag : List.of("setup", "core_loop", "end", "scoring")) {
            boolean directlyBound = outline.topics().stream()
                    .filter(topic -> topic.coverageTags().stream()
                            .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                            .anyMatch(tag::equals))
                    .flatMap(topic -> topic.sourcePageNumbers().stream())
                    .map(pages::get)
                    .anyMatch(page -> page != null && directVisualEvidenceFor(tag, page));
            if (!directlyBound) {
                throw new IllegalArgumentException(
                        "visual rulebook outline must bind " + tag + " to a page whose visible facts support it");
            }
        }
    }

    static TeachingOutlineModel.OutlineDraft bindVisualCoreTopicEvidence(
            TeachingOutlineModel.OutlineDraft outline, List<PageInput> visualCatalogPages) {
        Map<String, Integer> supportedPageByTag = new LinkedHashMap<>();
        Map<Integer, String> pageFacts = visualCatalogPages.stream().collect(Collectors.toMap(
                PageInput::pageNumber, PageInput::text, (first, duplicate) -> first, LinkedHashMap::new));
        for (String tag : List.of("setup", "core_loop", "end", "scoring")) {
            visualCatalogPages.stream()
                    .filter(page -> directVisualEvidenceFor(tag, page.text()))
                    .map(PageInput::pageNumber)
                    .findFirst()
                    .ifPresent(pageNumber -> supportedPageByTag.put(tag, pageNumber));
        }
        if (supportedPageByTag.isEmpty()) return outline;
        List<TeachingOutlineModel.TopicDraft> boundTopics = outline.topics().stream()
                .map(topic -> {
                    LinkedHashSet<Integer> directCorePages = new LinkedHashSet<>();
                    topic.coverageTags().stream()
                            .map(tag -> tag.toLowerCase(java.util.Locale.ROOT))
                            .filter(supportedPageByTag::containsKey)
                            .filter(tag -> topic.sourcePageNumbers().stream()
                                    .map(pageFacts::get)
                                    .noneMatch(page -> page != null && directVisualEvidenceFor(tag, page)))
                            .map(supportedPageByTag::get)
                            .forEach(directCorePages::add);
                    if (directCorePages.isEmpty()) return topic;
                    LinkedHashSet<Integer> sourcePages = new LinkedHashSet<>(directCorePages);
                    sourcePages.addAll(topic.sourcePageNumbers());
                    List<Integer> boundedPages = sourcePages.stream().limit(MAX_TOPIC_SOURCE_PAGES).toList();
                    return new TeachingOutlineModel.TopicDraft(
                            topic.key(),
                            topic.title(),
                            topic.objective(),
                            topic.required(),
                            topic.visualEvidenceRecommended(),
                            topic.retrievalQueries(),
                            topic.coverageTags(),
                            boundedPages);
                })
                .toList();
        return new TeachingOutlineModel.OutlineDraft(outline.gameTitle(), outline.premise(), boundTopics);
    }

    static void validateVisualFastBaseline(TeachingOutlineModel.OutlineDraft outline) {
        if (outline.topics().size() > 10) {
            throw new IllegalArgumentException(
                    "visual rulebook outline exceeds the ten-section fast baseline and must be compacted");
        }
    }

    static TeachingOutlineModel.OutlineDraft keepFastVisualBaseline(
            TeachingOutlineModel.OutlineDraft expanded, TeachingOutlineModel.OutlineDraft sourceDerived) {
        return expanded.topics().size() <= 10 ? expanded : sourceDerived;
    }

    private static boolean directVisualEvidenceFor(String tag, String page) {
        String facts = page.toLowerCase(java.util.Locale.ROOT);
        return switch (tag) {
            case "setup" -> containsAny(facts,
                    "set up", "setup", "setting up", "player setup", "设置", "准备", "起始资源");
            case "core_loop" -> containsAny(facts,
                    "how to play", "gameplay", "turn", "round", "phase", "roll phase", "run phase", "action",
                    "move", "游戏流程", "回合", "轮次", "阶段", "行动", "移动");
            case "end" -> hasCompleteEndingEvidence(facts);
            case "scoring" -> containsAny(facts,
                    "winner", "victory", "how to win", "scoring", "score", "points",
                    "获胜", "胜者", "胜利", "计分", "分数", "平局");
            default -> false;
        };
    }

    private static boolean hasCompleteEndingEvidence(String facts) {
        boolean endingTrigger = containsAny(facts,
                "end of game", "game over", "finish space", "游戏结束", "终局", "到达终点", "终点空间");
        boolean resolution = containsAny(facts,
                "winner", "victory", "how to win", "scoring", "score", "tie",
                "获胜", "胜者", "胜利", "计分", "分数", "平局");
        return endingTrigger && resolution;
    }

    private static boolean containsAny(String value, String... needles) {
        return java.util.Arrays.stream(needles).anyMatch(value::contains);
    }

    static TeachingOutlineModel.OutlineDraft augmentVisualCoverage(
            TeachingOutlineModel.OutlineDraft modelOutline, TeachingOutlineModel.OutlineDraft sourceOutline) {
        Set<Integer> covered = modelOutline.topics().stream()
                .flatMap(topic -> topic.sourcePageNumbers().stream())
                .collect(Collectors.toSet());
        List<TeachingOutlineModel.TopicDraft> topics = new java.util.ArrayList<>(modelOutline.topics());
        for (TeachingOutlineModel.TopicDraft sourceTopic : sourceOutline.topics()) {
            List<Integer> missingPages = sourceTopic.sourcePageNumbers().stream()
                    .filter(page -> !covered.contains(page))
                    .toList();
            if (missingPages.isEmpty()) continue;
            int existingTopic = matchingCoverageTopic(topics, sourceTopic);
            if (existingTopic >= 0) {
                TeachingOutlineModel.TopicDraft modelTopic = topics.get(existingTopic);
                topics.set(existingTopic, mergeCoveragePages(modelTopic, sourceTopic, missingPages));
                covered.addAll(missingPages);
                continue;
            }
            topics.add(new TeachingOutlineModel.TopicDraft(
                    "source-coverage-" + (topics.size() + 1),
                    sourceTopic.title(),
                    sourceTopic.objective(),
                    sourceTopic.required(),
                    sourceTopic.visualEvidenceRecommended(),
                    sourceTopic.retrievalQueries(),
                    sourceTopic.coverageTags(),
                    missingPages));
            covered.addAll(missingPages);
        }
        return new TeachingOutlineModel.OutlineDraft(modelOutline.gameTitle(), modelOutline.premise(), topics);
    }

    private static int matchingCoverageTopic(
            List<TeachingOutlineModel.TopicDraft> topics, TeachingOutlineModel.TopicDraft sourceTopic) {
        for (int index = 0; index < topics.size(); index++) {
            TeachingOutlineModel.TopicDraft candidate = topics.get(index);
            if (candidate.key().equalsIgnoreCase(sourceTopic.key())
                    || canonicalTopicTitle(candidate.title()).equals(canonicalTopicTitle(sourceTopic.title()))
                    || sameCompoundCoverage(candidate.coverageTags(), sourceTopic.coverageTags())) {
                return index;
            }
        }
        return -1;
    }

    /**
     * A model and the source fallback can name the same compound rule differently (for example, "game end and
     * scoring" versus "end, scoring, and winner"). Merge that one concept instead of creating a second required
     * chapter that competes for the same evidence. A lone broad tag such as {@code core_loop} is intentionally not
     * enough: it would collapse unrelated setup, action, and reference material.
     */
    private static boolean sameCompoundCoverage(List<String> candidateTags, List<String> sourceTags) {
        Set<String> candidate = new LinkedHashSet<>(candidateTags);
        Set<String> source = new LinkedHashSet<>(sourceTags);
        return (candidate.size() >= 2 && source.containsAll(candidate))
                || (source.size() >= 2 && candidate.containsAll(source));
    }

    private static TeachingOutlineModel.TopicDraft mergeCoveragePages(
            TeachingOutlineModel.TopicDraft modelTopic,
            TeachingOutlineModel.TopicDraft sourceTopic,
            List<Integer> missingPages) {
        LinkedHashSet<Integer> pages = new LinkedHashSet<>(modelTopic.sourcePageNumbers());
        pages.addAll(missingPages);
        LinkedHashSet<String> queries = new LinkedHashSet<>(modelTopic.retrievalQueries());
        queries.addAll(sourceTopic.retrievalQueries());
        LinkedHashSet<String> tags = new LinkedHashSet<>(modelTopic.coverageTags());
        tags.addAll(sourceTopic.coverageTags());
        return new TeachingOutlineModel.TopicDraft(
                modelTopic.key(),
                modelTopic.title(),
                modelTopic.objective(),
                modelTopic.required() || sourceTopic.required(),
                modelTopic.visualEvidenceRecommended() || sourceTopic.visualEvidenceRecommended(),
                queries.stream().limit(4).toList(),
                List.copyOf(tags),
                List.copyOf(pages));
    }

    private static String canonicalTopicTitle(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "")
                .strip();
    }

    private static boolean substantiveVisualCatalogPage(String text) {
        String normalized = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        boolean credits = normalized.contains("credits") || normalized.contains("鸣谢");
        boolean cover = (normalized.contains("cover") || normalized.contains("封面"))
                && (normalized.contains("no game mechanism")
                        || normalized.contains("no rule text")
                        || normalized.contains("no gameplay rules")
                        || normalized.contains("no operational instructions")
                        || normalized.contains("visual cover")
                        || normalized.contains("无游戏机制")
                        || normalized.contains("无游戏规则")
                        || normalized.contains("仅作为视觉封面"));
        boolean storageOnlyInsert = normalized.contains("storage or assembly instructions")
                && (normalized.contains("not gameplay")
                        || normalized.contains("non-gameplay")
                        || normalized.contains("this page is")
                        || normalized.contains("only for storage")
                        || normalized.contains("仅为收纳或组装说明"));
        boolean nonGameplayInsert = normalized.contains("非游戏规则")
                || normalized.contains("非游戏玩法")
                || normalized.contains("non-gameplay material")
                || normalized.contains("non-gameplay rule")
                || normalized.contains("宣传页")
                || normalized.contains("宣传广告")
                || normalized.contains("广告页")
                || normalized.contains("advertisement for another")
                || normalized.contains("仅为收纳或组装说明")
                || storageOnlyInsert
                || normalized.contains("仅为封面设计");
        return !credits && !cover && !nonGameplayInsert;
    }

    static Set<Integer> selectedVisualPageNumbers(
            TeachingOutlineModel.OutlineDraft outline, List<DocumentProcessing.PageView> pages) {
        Set<Integer> topicPages = outline.topics().stream()
                .flatMap(topic -> topic.sourcePageNumbers().stream())
                .collect(java.util.stream.Collectors.toSet());
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        iconLegendPage(pages).ifPresent(selected::add);
        pages.stream()
                .filter(page -> topicPages.contains(page.pageNumber()))
                .map(page -> Map.entry(page.pageNumber(), missingInlineIconScore(page.text())))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .forEach(page -> addBounded(selected, page));
        outline.topics().stream()
                .filter(TeachingOutlineModel.TopicDraft::visualEvidenceRecommended)
                .flatMap(topic -> topic.sourcePageNumbers().stream())
                .distinct()
                .forEach(page -> addBounded(selected, page));
        return java.util.Collections.unmodifiableSet(selected);
    }

    /**
     * Samples only unowned pages that have too little extractable text for the normal coverage detector. Sampling is
     * spread across the rulebook rather than always choosing the first few pages, which are commonly a cover or
     * contents. The vision catalog decides whether a sampled page is gameplay material before it can trigger an
     * outline revision.
     */
    static Set<Integer> unownedSparseVisualCoveragePageNumbers(
            TeachingOutlineModel.OutlineDraft outline,
            List<DocumentProcessing.PageView> pages,
            int maximumPages) {
        if (outline == null || pages == null || pages.isEmpty() || maximumPages < 1) return Set.of();
        Set<Integer> owned = outline.topics().stream()
                .flatMap(topic -> topic.sourcePageNumbers().stream())
                .collect(Collectors.toSet());
        List<Integer> candidates = pages.stream()
                // Page one is normally the cover. If it contains an actual printed rule, ordinary text coverage
                // already handles it; otherwise a cover must not spend the sparse-page visual budget.
                .filter(page -> page.pageNumber() > 1)
                .filter(page -> !owned.contains(page.pageNumber()))
                .filter(TeachingPlanService::hasSparseExtractedText)
                .filter(page -> !isSubstantiveRulebookText(page.text()))
                .map(DocumentProcessing.PageView::pageNumber)
                .toList();
        if (candidates.isEmpty()) return Set.of();
        int slots = Math.min(maximumPages, candidates.size());
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        if (slots == 1) {
            selected.add(candidates.get(candidates.size() / 2));
        } else {
            for (int slot = 0; slot < slots; slot++) {
                int index = (int) Math.round((double) slot * (candidates.size() - 1) / (slots - 1));
                selected.add(candidates.get(index));
            }
        }
        return java.util.Collections.unmodifiableSet(selected);
    }

    private static boolean hasSparseExtractedText(DocumentProcessing.PageView page) {
        String text = page.text() == null ? "" : page.text();
        long meaningfulCharacters = text.codePoints()
                .filter(codePoint -> Character.isLetterOrDigit(codePoint))
                .count();
        return meaningfulCharacters <= 280;
    }

    private static boolean isSubstantiveRulebookText(String text) {
        if (text == null || text.isBlank()) return false;
        return isSubstantiveRulebookPage(new PageInput(1, text));
    }

    static Optional<String> chapterOwnershipRevisionFeedback(TeachingOutlineModel.OutlineDraft outline) {
        if (outline == null || outline.topics().size() < 2) return Optional.empty();
        List<String> conflicts = new java.util.ArrayList<>();
        for (TeachingOutlineModel.TopicDraft current : outline.topics()) {
            for (ChapterOwnershipDomain domain : CHAPTER_OWNERSHIP_DOMAINS) {
                if (!containsAny(topicObjective(current), domain.detailTerms())
                        || containsAny(current.key() + " " + current.title(), domain.ownerTerms())) {
                    continue;
                }
                List<TeachingOutlineModel.TopicDraft> owners = outline.topics()
                        .stream()
                        .filter(topic -> topic != current)
                        .filter(topic -> containsAny(topic.key() + " " + topic.title(), domain.ownerTerms()))
                        .limit(3)
                        .toList();
                if (owners.isEmpty()) continue;
                String ownerTitles = owners.stream()
                        .map(TeachingOutlineModel.TopicDraft::title)
                        .collect(Collectors.joining("、"));
                conflicts.add("“" + current.title() + "” currently includes " + domain.label() + ": “"
                        + boundedOwnershipObjective(current.objective()) + "”; chapter(s) “" + ownerTitles
                        + "” should own that nested detail.");
            }
        }
        appendPlayerJourneyOrderConflicts(outline, conflicts);
        if (conflicts.isEmpty()) return Optional.empty();
        return Optional.of("""
                Rebuild this complete lesson outline, not merely the listed chapters. Give every material detailed rule one primary chapter owner.
                A chapter may retain the stage, order, immediate choice, or result it needs to connect the lesson, but must not explain a nested cost, payment, card procedure, cleanup procedure, end trigger, scoring calculation, component mapping, or exception that a later detail chapter owns. Keep the bridge; move only the nested detail to its later owner. Do not delete coverage, source-page bindings, or source-language retrieval queries.
                The lesson must remain playable in reading order: finish the ordinary turn and its mandatory closure before teaching game end or final scoring. Put detailed scoring criteria before the final scoring conclusion.
                Detected chapter-boundary conflicts:
                """ + String.join("\n", conflicts));
    }

    static Optional<String> sourcePageCoverageRevisionFeedback(
            TeachingOutlineModel.OutlineDraft outline, List<PageInput> pages) {
        if (outline == null || pages == null || pages.isEmpty()) return Optional.empty();
        Set<Integer> boundPages = outline.topics().stream()
                .flatMap(topic -> topic.sourcePageNumbers().stream())
                .collect(Collectors.toSet());
        List<PageInput> missing = pages.stream()
                .filter(TeachingPlanService::isSubstantiveRulebookPage)
                .filter(page -> !boundPages.contains(page.pageNumber()))
                .limit(4)
                .toList();
        if (missing.isEmpty()) return Optional.empty();
        String pageCatalog = missing.stream()
                .map(page -> "Page " + page.pageNumber() + ": " + boundedCoveragePageText(page.text()))
                .collect(Collectors.joining("\n"));
        return Optional.of("""
                Rebuild the complete lesson outline so every substantive rulebook page has a teaching owner. The listed
                page(s) are not currently bound to any topic's sourcePageNumbers. Add or expand a game-specific topic
                for the actual rule, variant, icon, exception, example, or procedure on that page. Preserve the current
                lesson's covered rules, source-language retrieval queries, and chapter order; do not hide an omitted page
                by attaching it to an unrelated topic.
                A `[Visual page catalog]` entry is a page-local observation to navigate the original page image, not a
                free-standing rule conclusion. Use it only to decide whether the page needs a teaching owner. Keep the
                source page binding and do not invent an action, condition, or icon meaning that is not visibly stated.
                Unowned substantive source pages:
                """ + pageCatalog);
    }

    private static boolean isSubstantiveRulebookPage(PageInput page) {
        String text = page.text() == null ? "" : page.text().toLowerCase(Locale.ROOT);
        if (text.contains(VISUAL_CATALOG_PREFIX.toLowerCase(Locale.ROOT))) {
            return hasConcreteVisualGameplayEvidence(text);
        }
        return text.matches("(?s).*\\b(?:setup|turn|action|round|gameplay|game end|score|rule|component|"
                + "card|token|player|must|may|place|move|discard|variant|advanced)\\b.*")
                || text.matches("(?s).*(?:设置|回合|行动|结束|计分|规则|组件|卡牌|令牌|玩家|必须|可以|放置|移动|弃牌|变体|高级).*" );
    }

    private static boolean hasConcreteVisualGameplayEvidence(String normalizedText) {
        if (!substantiveVisualCatalogPage(normalizedText)) return false;
        return !normalizedText.contains("no factual visual claim is available")
                && !normalizedText.contains("unreadable")
                && !normalizedText.contains("不可可靠转写")
                && !normalizedText.contains("no legible printed term")
                && !normalizedText.contains("visual cover")
                && !normalizedText.contains("盒面")
                && !normalizedText.contains("封面")
                && !normalizedText.contains("contents")
                && !normalizedText.contains("目录");
    }

    private static String boundedCoveragePageText(String text) {
        String value = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        int visualCatalog = value.indexOf(VISUAL_CATALOG_PREFIX);
        if (visualCatalog >= 0) value = value.substring(visualCatalog);
        return value.length() <= 420 ? value : value.substring(0, 419) + "…";
    }

    private static void appendPlayerJourneyOrderConflicts(
            TeachingOutlineModel.OutlineDraft outline, List<String> conflicts) {
        int firstFinale = java.util.stream.IntStream.range(0, outline.topics().size())
                .filter(index -> isFinaleTopic(outline.topics().get(index)))
                .findFirst()
                .orElse(-1);
        if (firstFinale < 0) return;

        TeachingOutlineModel.TopicDraft finale = outline.topics().get(firstFinale);
        for (int index = firstFinale + 1; index < outline.topics().size(); index++) {
            TeachingOutlineModel.TopicDraft later = outline.topics().get(index);
            if (isTurnClosureTopic(later)) {
                conflicts.add("“" + later.title() + "” teaches the normal turn's closing procedure after finale chapter “"
                        + finale.title() + "”. Move the complete cleanup, replenishment, hand-limit, or reset procedure "
                        + "before game end and final scoring so a new player can finish a real turn first.");
            }
            if (isScoringDetailTopic(later) && !isFinaleTopic(later)) {
                conflicts.add("“" + later.title() + "” gives a scoring criterion after finale chapter “"
                        + finale.title() + "”. Move that scoring detail before the end/final-scoring conclusion, while "
                        + "keeping the final total and tie break in the finale chapter.");
            }
        }
    }

    private static boolean isFinaleTopic(TeachingOutlineModel.TopicDraft topic) {
        return containsAny(topic.key() + " " + topic.title() + " " + topic.objective(), List.of(
                "end of game", "game end", "end trigger", "final scoring", "tie breaker", "winner",
                "游戏结束", "终局", "结束触发", "最终计分", "平局", "胜者"));
    }

    private static boolean isTurnClosureTopic(TeachingOutlineModel.TopicDraft topic) {
        return containsAny(topic.key() + " " + topic.title() + " " + topic.objective(), List.of(
                "cleanup", "hand limit", "discard pile", "refill", "replenish", "end of round",
                "清理", "手牌上限", "弃牌堆", "补牌", "补充", "回合结束"));
    }

    private static boolean isScoringDetailTopic(TeachingOutlineModel.TopicDraft topic) {
        return containsAny(topic.key() + " " + topic.title() + " " + topic.objective(), List.of(
                "score", "points", "victory point", "scoring table", "得分", "计分", "点数", "分数", "品质瓷砖", "品质板"));
    }

    private static String topicObjective(TeachingOutlineModel.TopicDraft topic) {
        return topic.objective().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, List<String> terms) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return terms.stream().anyMatch(normalized::contains);
    }

    private static String boundedOwnershipObjective(String objective) {
        String value = objective.strip();
        return value.length() <= 320 ? value : value.substring(0, 319) + "…";
    }

    private record ChapterOwnershipDomain(String label, List<String> ownerTerms, List<String> detailTerms) {}

    private static void addBounded(LinkedHashSet<Integer> pages, int pageNumber) {
        if (pages.size() < MAX_INTERPRETED_VISUAL_PAGES) pages.add(pageNumber);
    }

    private static int missingInlineIconScore(String text) {
        if (text == null || text.isBlank()) return 0;
        var matcher = LIKELY_MISSING_INLINE_ICON.matcher(text);
        int score = 0;
        while (matcher.find()) score++;
        return score;
    }

    private static Optional<Integer> iconLegendPage(List<DocumentProcessing.PageView> pages) {
        return pages.stream()
                .filter(page -> componentTermCount(page.text()) >= 2)
                .filter(page -> iconLegendScore(page) >= 8)
                .sorted(java.util.Comparator
                        .comparingInt(TeachingPlanService::iconLegendScore)
                        .reversed()
                        .thenComparingInt(DocumentProcessing.PageView::pageNumber))
                .map(DocumentProcessing.PageView::pageNumber)
                .findFirst();
    }

    private static int iconLegendScore(DocumentProcessing.PageView page) {
        String text = page.text() == null ? "" : page.text();
        int score = Math.min(componentTermCount(text), 8) * 2 + missingInlineIconScore(text) * 3;
        var cues = COMPONENT_LEGEND_CUE.matcher(text);
        while (cues.find()) score++;
        var allocations = COMPONENT_ALLOCATION_CUE.matcher(text);
        while (allocations.find()) score += 3;
        return score;
    }

    private static int componentTermCount(String text) {
        if (text == null || text.isBlank()) return 0;
        int count = 0;
        var terms = COMPONENT_TERM.matcher(text);
        while (terms.find()) count++;
        return count;
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
