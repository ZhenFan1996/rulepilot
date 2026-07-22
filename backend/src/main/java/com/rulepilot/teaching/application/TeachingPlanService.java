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
            @Value("${rulepilot.visual.catalog-timeout:PT45S}") Duration visualCatalogTimeout) {
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
        this.visualCatalogTimeout = visualCatalogTimeout;
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
        var outlineRequest = new OutlineRequest(
                playerCount, beginnerCount, durationMinutes, pages, outlineImages, createdBy);
        var outline = preferDocumentTitle(
                scope.documentTitle(),
                bindIconLegendEvidence(invokeModel(
                        assistantRunId,
                        "organizeTeachingOutline",
                        outlineInputTokens(pages),
                        "Rulebook lesson topics organized",
                        () -> outlines.organize(outlineRequest),
                        this::outlineOutputTokens), documentPages));
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
                    List<PageFact> interpreted = catalogPageFacts(
                            documentVersionId,
                            selectedVisualPages,
                            iconLegendPage(documentPages).orElse(null),
                            scope.documentTitle(),
                            createdBy,
                            assistantRunId);
                    if (interpreted.isEmpty()) {
                        log.warn(
                                "Visual page interpretation produced no usable facts for document {} pages {}",
                                documentVersionId,
                                selectedVisualPages);
                    } else {
                        visualFacts.replace(documentVersionId, interpreted);
                        log.info(
                                "Visual page interpretation stored document {} pages {}",
                                documentVersionId,
                                interpreted.stream().map(PageFact::pageNumber).toList());
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
        if (!cached.isEmpty() && assistantRunId != null) {
            invocations.record(
                    assistantRunId,
                    ActivityType.VALIDATION,
                    "reuseVisualPageFacts",
                    ActivityOutcome.SUCCEEDED,
                    "Reused " + cached.size() + " page-scoped visual facts from this immutable rulebook version");
        }
        // A first pass catalogs every rendered page. On later plan refreshes the immutable version cache is
        // authoritative: an unavailable page remains explicitly unavailable until a focused teaching step asks for it.
        // Re-reading a mostly complete rulebook just to retry one slow page delays the first usable lesson.
        List<PageFact> fresh = cached.isEmpty()
                ? catalogPageFacts(documentVersionId, requestedPages, null, rulebookTitle, owner, assistantRunId)
                : List.of();
        if (!cached.isEmpty() && !missingPages.isEmpty() && assistantRunId != null) {
            invocations.record(
                    assistantRunId,
                    ActivityType.VALIDATION,
                    "deferUncatalogedVisualPages",
                    ActivityOutcome.REJECTED,
                    "Deferred " + missingPages.size()
                            + " uncataloged page(s) to focused visual retrieval; cached page facts can start the lesson");
        }
        List<PageFact> facts = mergeVisualPageFacts(cached, fresh);
        if (facts.isEmpty()) {
            throw new IllegalArgumentException("visual rulebook catalog did not produce any reliable page facts");
        }
        if (!fresh.isEmpty()) visualFacts.replace(documentVersionId, facts);
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

    static List<PageInput> visualPageInputs(
            List<DocumentProcessing.PageView> documentPages, List<PageFact> facts) {
        Map<Integer, PageFact> factsByPage = facts.stream().collect(Collectors.toMap(
                PageFact::pageNumber, java.util.function.Function.identity(), (first, duplicate) -> first));
        return documentPages.stream()
                .map(page -> visualPageInput(page.pageNumber(), factsByPage.get(page.pageNumber())))
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
                        summary.pageNumber(), summary.printedTerms(), summary.factualSummary(), summary.keywords()))
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
                            .map(supportedPageByTag::get)
                            .filter(java.util.Objects::nonNull)
                            .forEach(directCorePages::add);
                    LinkedHashSet<Integer> sourcePages = new LinkedHashSet<>(directCorePages);
                    sourcePages.addAll(topic.sourcePageNumbers());
                    List<Integer> boundedPages = sourcePages.stream().limit(4).toList();
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
