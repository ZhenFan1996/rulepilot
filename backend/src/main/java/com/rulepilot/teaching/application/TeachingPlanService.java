package com.rulepilot.teaching.application;

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
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
    private static final int MAX_INTERPRETED_VISUAL_PAGES = 4;
    private static final int VISUAL_CATALOG_BATCH_SIZE = 2;
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
    private final TeachingPlanFactory plans;
    private final TeachingPlanRepository repository;

    public TeachingPlanService(
            DocumentProcessing documents,
            DocumentPageImages pageImages,
            DocumentVersionScopeLookup documentScopes,
            DocumentTeachingPreparation documentPreparation,
            VisualRulebookPageCatalogModel visualCatalog,
            VisualRulebookPageFacts visualFacts,
            TeachingOutlineModel outlines,
            TeachingPlanFactory plans,
            TeachingPlanRepository repository) {
        this.documents = documents;
        this.pageImages = pageImages;
        this.documentScopes = documentScopes;
        this.documentPreparation = documentPreparation;
        this.visualCatalog = visualCatalog;
        this.visualFacts = visualFacts;
        this.outlines = outlines;
        this.plans = plans;
        this.repository = repository;
    }

    @Transactional
    public TeachingPlan create(
            UUID documentVersionId, int playerCount, int beginnerCount, int durationMinutes, String createdBy) {
        var scope = documentScopes.findVersion(documentVersionId)
                .filter(found -> found.createdBy().equals(createdBy))
                .orElseThrow(() -> new IllegalArgumentException("rule document does not exist"));
        if (!"READY".equals(scope.processingStatus())) {
            throw new IllegalArgumentException("rule document is not ready for teaching");
        }
        var documentPages = documents.pages(documentVersionId);
        boolean visualOnly = documentPages.stream().allMatch(page -> page.text() == null || page.text().isBlank());
        var pages = visualOnly
                ? catalogVisualPages(documentVersionId, documentPages, createdBy)
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
        var outline = bindIconLegendEvidence(outlines.organize(new OutlineRequest(
                playerCount, beginnerCount, durationMinutes, pages, outlineImages, createdBy)), documentPages);
        if (visualOnly) validateVisualPageBindings(outline, documentPages);
        if (!visualOnly && visualCatalog.available(createdBy)) {
            Set<Integer> selectedVisualPages = selectedVisualPageNumbers(outline, documentPages);
            if (!selectedVisualPages.isEmpty()) {
                try {
                    List<PageFact> interpreted = catalogPageFacts(
                            documentVersionId,
                            selectedVisualPages,
                            iconLegendPage(documentPages).orElse(null),
                            createdBy,
                            false);
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
            UUID documentVersionId, List<DocumentProcessing.PageView> documentPages, String owner) {
        List<PageFact> facts = catalogPageFacts(
                documentVersionId,
                documentPages.stream()
                        .map(DocumentProcessing.PageView::pageNumber)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                null,
                owner,
                true);
        visualFacts.replace(documentVersionId, facts);
        return facts.stream()
                .map(summary -> new PageInput(
                        summary.pageNumber(),
                        "[Visual page catalog; verify against page image]\nPrinted terms: " + summary.printedTerms()
                                + "\nVisible facts: " + summary.factualSummary()
                                + "\nKeywords: " + String.join(", ", summary.keywords())))
                .toList();
    }

    private List<PageFact> catalogPageFacts(
            UUID documentVersionId,
            Set<Integer> pageNumbers,
            Integer iconLegendPage,
            String owner,
            boolean requireEveryBatch) {
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
        int parallelism = Math.min(2, batches.size());
        try (var executor = Executors.newFixedThreadPool(parallelism)) {
            List<Future<VisualRulebookPageCatalogModel.CatalogDraft>> futures = batches.stream()
                    .map(batch -> executor.submit(() -> visualCatalog.summarize(new VisualRulebookPageCatalogModel.CatalogRequest(
                            pageImages.read(documentVersionId, new LinkedHashSet<>(batch)).stream()
                                    .map(image -> new PageImageInput(image.pageNumber(), image.mediaType(), image.content()))
                                    .toList(),
                            owner))))
                    .toList();
            for (int index = 0; index < futures.size(); index++) {
                try {
                    summaries.addAll(awaitCatalog(futures.get(index)).pages());
                } catch (RuntimeException failedBatch) {
                    if (requireEveryBatch) throw failedBatch;
                    log.warn(
                            "Visual page interpretation skipped failed batch {} for document {}",
                            batches.get(index),
                            documentVersionId,
                            failedBatch);
                }
            }
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

    private VisualRulebookPageCatalogModel.CatalogDraft awaitCatalog(
            Future<VisualRulebookPageCatalogModel.CatalogDraft> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("visual rulebook catalog was interrupted", interrupted);
        } catch (ExecutionException failed) {
            throw new IllegalStateException("visual rulebook catalog failed", failed.getCause());
        }
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
