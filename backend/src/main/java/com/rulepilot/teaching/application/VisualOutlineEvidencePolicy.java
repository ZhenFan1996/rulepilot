package com.rulepilot.teaching.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Deterministic source-page decisions for visual rulebook teaching outlines.
 *
 * <p>This policy neither reads documents nor invokes a visual model. It only relates already extracted page text
 * and verified page facts to a bounded teaching outline, so model output cannot silently drop a needed source page
 * or invent a cross-page icon mapping.</p>
 */
final class VisualOutlineEvidencePolicy {

    static final int MAX_INTERPRETED_VISUAL_PAGES = 4;
    private static final int MAX_TOPIC_SOURCE_PAGES = 5;
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

    private VisualOutlineEvidencePolicy() {}

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
                            .filter(Objects::nonNull)
                            .anyMatch(page -> missingInlineIconScore(page.text()) > 0);
                    if (!needsLegend || topic.sourcePageNumbers().contains(legend.get())) return topic;
                    List<Integer> sourcePages = new ArrayList<>(topic.sourcePageNumbers());
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

    static void validateVisualRulebookCoverage(
            TeachingOutlineModel.OutlineDraft outline, List<PageInput> visualCatalogPages) {
        Set<Integer> expected = visualCatalogPages.stream()
                .filter(page -> TeachingOutlineRevisionPolicy.isSubstantiveVisualCatalogPage(page.text()))
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
                            .map(value -> value.toLowerCase(Locale.ROOT))
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
                            .map(tag -> tag.toLowerCase(Locale.ROOT))
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

    static TeachingOutlineModel.OutlineDraft augmentVisualCoverage(
            TeachingOutlineModel.OutlineDraft modelOutline, TeachingOutlineModel.OutlineDraft sourceOutline) {
        Set<Integer> covered = modelOutline.topics().stream()
                .flatMap(topic -> topic.sourcePageNumbers().stream())
                .collect(Collectors.toSet());
        List<TeachingOutlineModel.TopicDraft> topics = new ArrayList<>(modelOutline.topics());
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

    static Set<Integer> selectedVisualPageNumbers(
            TeachingOutlineModel.OutlineDraft outline, List<DocumentProcessing.PageView> pages) {
        Set<Integer> topicPages = outline.topics().stream()
                .flatMap(topic -> topic.sourcePageNumbers().stream())
                .collect(Collectors.toSet());
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
        return Collections.unmodifiableSet(selected);
    }

    /**
     * Samples only unowned pages with too little extracted text for ordinary coverage checks. The sample is spread
     * across the rulebook rather than concentrating on front matter, and a cover never consumes the visual budget.
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
                .filter(page -> page.pageNumber() > 1)
                .filter(page -> !owned.contains(page.pageNumber()))
                .filter(VisualOutlineEvidencePolicy::hasSparseExtractedText)
                .filter(page -> !TeachingOutlineRevisionPolicy.isSubstantiveRulebookText(page.text()))
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
        return Collections.unmodifiableSet(selected);
    }

    static Optional<Integer> iconLegendPage(List<DocumentProcessing.PageView> pages) {
        return pages.stream()
                .filter(page -> componentTermCount(page.text()) >= 2)
                .filter(page -> iconLegendScore(page) >= 8)
                .sorted(Comparator
                        .comparingInt(VisualOutlineEvidencePolicy::iconLegendScore)
                        .reversed()
                        .thenComparingInt(DocumentProcessing.PageView::pageNumber))
                .map(DocumentProcessing.PageView::pageNumber)
                .findFirst();
    }

    private static boolean directVisualEvidenceFor(String tag, String page) {
        String facts = page.toLowerCase(Locale.ROOT);
        return switch (tag) {
            case "setup" -> containsAny(facts,
                    "set up", "setup", "setting up", "player setup", "设置", "准备", "起始资源");
            case "core_loop" -> containsAny(facts,
                    "how to play", "gameplay", "turn", "round", "phase", "roll phase", "run phase", "action",
                    "move", "游戏流程", "回合", "轮次", "阶段", "行动", "移动");
            case "end" -> hasEndConditionEvidence(facts);
            case "scoring" -> containsAny(facts,
                    "winner", "victory", "how to win", "scoring", "score", "points",
                    "获胜", "胜者", "胜利", "计分", "分数", "平局");
            default -> false;
        };
    }

    /**
     * An end-condition page is sufficient evidence for the end chapter even when winner, tie, or scoring details
     * live on a following page. Requiring all of those facts on one page made scanned books bind their end chapter
     * to an unrelated card that happened to say "win" and skipped the actual trigger.
     */
    private static boolean hasEndConditionEvidence(String facts) {
        boolean endingTrigger = containsAny(facts,
                "end of game", "game end", "game over", "finish space", "游戏结束", "终局", "到达终点", "终点空间");
        boolean triggerCondition = containsAny(facts,
                "when ", "once ", "reaches", "reach ", "taken", "trigger", "at least",
                "当", "一旦", "达到", "触发", "完成");
        return endingTrigger && triggerCondition;
    }

    private static boolean containsAny(String value, String... needles) {
        return Arrays.stream(needles).anyMatch(value::contains);
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
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "")
                .strip();
    }

    private static boolean hasSparseExtractedText(DocumentProcessing.PageView page) {
        String text = page.text() == null ? "" : page.text();
        long meaningfulCharacters = text.codePoints()
                .filter(codePoint -> Character.isLetterOrDigit(codePoint))
                .count();
        return meaningfulCharacters <= 280;
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
}
