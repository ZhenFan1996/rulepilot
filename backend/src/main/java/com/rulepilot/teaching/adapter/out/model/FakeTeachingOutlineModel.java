package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.VisualSourceRuleGroupLedger;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import com.rulepilot.teaching.VisualRulebookPageClassifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Source-preserving fallback used when a configured outline model cannot return a valid schema.
 *
 * <p>This adapter deliberately does not infer that a page is setup, scoring, a cover, or any other semantic role from
 * vocabulary. Text rulebooks retain the four product-level teaching obligations as retrieval questions. Visual-only
 * rulebooks are grouped in document order and keep every admitted page so the later evidence-bound lesson generator
 * can either teach what the page actually proves or publish an insufficient-evidence section.</p>
 */
@Component
public class FakeTeachingOutlineModel implements TeachingOutlineModel {

    private static final int MAX_TOPIC_QUERIES = 8;
    private static final int MAX_SOURCE_PAGES_PER_TOPIC = 5;
    private static final int MAX_VISUAL_SOURCE_TOPICS = 10;
    private static final int MAX_TOPICS = 16;
    private static final List<String> CORE_TAGS = List.of("setup", "core_loop", "end", "scoring");

    @Override
    public OutlineDraft organize(OutlineRequest request) {
        return isVisualCatalog(request) ? visualCatalogOutline(request) : textFallbackOutline(request);
    }

    private OutlineDraft textFallbackOutline(OutlineRequest request) {
        Set<String> explicitlyMissing = request.pages().stream()
                .flatMap(page -> page.sourceDependencies().stream())
                .flatMap(dependency -> dependency.missingCoverageTags().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<TopicDraft> topics = new ArrayList<>();
        if (!explicitlyMissing.contains("setup")) {
            topics.add(coreTopic(
                    "setup",
                    "准备游戏",
                    "从整份规则书检索并说明开始游戏前必须完成的准备；只保留有引用支持的步骤。",
                    List.of("How is the game set up? What changes by player count?"),
                    List.of("setup")));
        }
        if (!explicitlyMissing.contains("core_loop")) {
            topics.add(coreTopic(
                    "core-loop",
                    "进行回合与主要行动",
                    "从整份规则书检索玩家实际轮流做什么、选择如何生效，以及阶段如何推进。",
                    List.of("What may a player do on a turn, and how does play advance?"),
                    List.of("core_loop")));
        }
        if (!explicitlyMissing.contains("end")) {
            topics.add(coreTopic(
                    "ending",
                    "确认游戏何时结束",
                    "从整份规则书检索结束触发与最后处理；不要把邻近的流程描述当作结束条件。",
                    List.of("What exactly triggers the end of the game, and what happens next?"),
                    List.of("end")));
        }
        if (!explicitlyMissing.contains("scoring")) {
            topics.add(coreTopic(
                    "scoring",
                    "完成计分并判定结果",
                    "从整份规则书检索所有计分来源、胜者判定与同分处理；缺失时明确说明。",
                    List.of("How are final results, scoring, the winner, and ties resolved?"),
                    List.of("scoring")));
        }
        request.pages().stream()
                .filter(page -> !page.sourceDependencies().isEmpty())
                .map(this::sourceDependencyTopic)
                .forEach(topics::add);
        if (topics.size() > MAX_TOPICS) {
            throw new IllegalArgumentException(
                    "text rulebook exceeds source-preserving fallback capacity; a semantic outline model is required");
        }
        return new OutlineDraft(
                "Imported rulebook",
                "按准备、主要流程、结束与计分四项通用学习目标检索原规则；没有可核对证据时明确保留空缺。",
                List.copyOf(topics));
    }

    private TopicDraft coreTopic(
            String key, String title, String objective, List<String> queries, List<String> coverageTags) {
        return new TopicDraft(key, title, objective, true, false, queries, coverageTags, List.of());
    }

    private OutlineDraft visualCatalogOutline(OutlineRequest request) {
        List<PageInput> admitted = request.pages().stream()
                .filter(page -> VisualRulebookPageClassifier.isSubstantive(page.pageNumber(), page.text())
                        || !page.sourceDependencies().isEmpty())
                .toList();
        List<PageInput> sourcePages = admitted.isEmpty() ? request.pages() : admitted;
        long dependencyTopics = sourcePages.stream()
                .filter(page -> !page.sourceDependencies().isEmpty())
                .count();
        List<SourceTopic> sourceTopics = sourceTopics(sourcePages);
        if (sourceTopics.size() > MAX_VISUAL_SOURCE_TOPICS
                || sourceTopics.size() + dependencyTopics > MAX_TOPICS) {
            sourceTopics = packConsecutiveSourceTopics(sourceTopics);
        }
        if (sourceTopics.size() > MAX_VISUAL_SOURCE_TOPICS
                || sourceTopics.size() + dependencyTopics > MAX_TOPICS) {
            throw new IllegalArgumentException(
                    "visual rulebook exceeds source-preserving fallback capacity; a semantic outline model is required");
        }

        LinkedHashSet<String> explicitlyMissing = sourcePages.stream()
                .flatMap(page -> page.sourceDependencies().stream())
                .flatMap(dependency -> dependency.missingCoverageTags().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        List<TopicDraft> topics = new ArrayList<>();
        for (int index = 0; index < sourceTopics.size(); index++) {
            SourceTopic sourceTopic = sourceTopics.get(index);
            LinkedHashSet<String> coverageTags = new LinkedHashSet<>();
            if (topics.isEmpty()) {
                CORE_TAGS.stream().filter(tag -> !explicitlyMissing.contains(tag)).forEach(coverageTags::add);
            }
            coverageTags.add("source_coverage");
            topics.add(sourceTopic.toDraft(index + 1, List.copyOf(coverageTags)));
        }
        sourcePages.stream()
                .filter(page -> !page.sourceDependencies().isEmpty())
                .map(this::sourceDependencyTopic)
                .forEach(topics::add);
        return new OutlineDraft(
                "Imported rulebook",
                "逐页核对视觉规则证据，再由证据约束的讲解模型组织玩家可执行的说明；无法确认的内容保持为空缺。",
                List.copyOf(topics));
    }

    private List<List<String>> chunks(List<String> queries) {
        List<List<String>> chunks = new ArrayList<>();
        for (int start = 0; start < queries.size(); start += MAX_TOPIC_QUERIES) {
            chunks.add(queries.subList(start, Math.min(start + MAX_TOPIC_QUERIES, queries.size())));
        }
        return List.copyOf(chunks);
    }

    private List<SourceTopic> sourceTopics(List<PageInput> sourcePages) {
        List<SourceTopic> topics = new ArrayList<>();
        for (PageInput page : sourcePages) {
            List<List<String>> pageChunks = chunks(VisualSourceRuleGroupLedger.identifiers(page));
            if (pageChunks.isEmpty()) {
                pageChunks = List.of(List.of(
                        "Inspect the cited rulebook page and report only directly visible rules."));
            }
            for (int index = 0; index < pageChunks.size(); index++) {
                topics.add(new SourceTopic(
                        List.of(page.pageNumber()),
                        pageChunks.get(index),
                        index + 1,
                        pageChunks.size()));
            }
        }
        return List.copyOf(topics);
    }

    private List<SourceTopic> packConsecutiveSourceTopics(List<SourceTopic> topics) {
        List<SourceTopic> packed = new ArrayList<>();
        SourceTopic current = null;
        for (SourceTopic topic : topics) {
            if (current == null) {
                current = topic;
                continue;
            }
            SourceTopic merged = current.mergeIfBounded(topic);
            if (merged == null) {
                packed.add(current);
                current = topic;
            } else {
                current = merged;
            }
        }
        if (current != null) packed.add(current);
        return List.copyOf(packed);
    }

    private TopicDraft sourceDependencyTopic(PageInput page) {
        List<String> titles = page.sourceDependencies().stream()
                .map(SourceDependency::title)
                .distinct()
                .toList();
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        tags.add("source_dependency");
        page.sourceDependencies().stream()
                .flatMap(dependency -> dependency.missingCoverageTags().stream())
                .map(tag -> "missing_" + tag + "_source")
                .forEach(tags::add);
        return new TopicDraft(
                "source-dependency-page-" + page.pageNumber(),
                "当前规则书还需要的资料",
                "第 " + page.pageNumber() + " 页只证明当前规则书要求另行查看“"
                        + String.join("、", titles)
                        + "”；明确告诉玩家来源尚未导入，不得重建其中缺少的步骤。",
                true,
                true,
                titles,
                List.copyOf(tags),
                List.of(page.pageNumber()));
    }

    private boolean isVisualCatalog(OutlineRequest request) {
        return request.pages().stream().allMatch(page -> page.text().startsWith("[Visual page catalog;"));
    }

    private record SourceTopic(
            List<Integer> pageNumbers,
            List<String> queries,
            int pagePartNumber,
            int pagePartCount) {

        private SourceTopic {
            pageNumbers = List.copyOf(pageNumbers);
            queries = List.copyOf(queries);
        }

        private SourceTopic mergeIfBounded(SourceTopic other) {
            LinkedHashSet<Integer> mergedPages = new LinkedHashSet<>(pageNumbers);
            mergedPages.addAll(other.pageNumbers);
            LinkedHashSet<String> mergedQueries = new LinkedHashSet<>(queries);
            mergedQueries.addAll(other.queries);
            if (mergedPages.size() > MAX_SOURCE_PAGES_PER_TOPIC
                    || mergedQueries.size() > MAX_TOPIC_QUERIES) return null;
            return new SourceTopic(List.copyOf(mergedPages), List.copyOf(mergedQueries), 0, 0);
        }

        private TopicDraft toDraft(int sourceTopicNumber, List<String> coverageTags) {
            if (pageNumbers.size() == 1 && pagePartNumber > 0) {
                int page = pageNumbers.getFirst();
                String part = pagePartCount == 1 ? "" : "（第 " + pagePartNumber + " 组）";
                return new TopicDraft(
                        "source-page-" + page + "-group-" + pagePartNumber,
                        "核对规则书第 " + page + " 页" + part,
                        "仅依据规则书第 " + page
                                + " 页的可见事实讲解它实际支持的规则；每个原始标识都只归属这一页，"
                                + "不要根据页码、位置或通用词汇猜测页面职责。",
                        true,
                        true,
                        queries,
                        coverageTags,
                        pageNumbers);
            }
            int firstPage = pageNumbers.getFirst();
            int lastPage = pageNumbers.getLast();
            return new TopicDraft(
                    "source-pages-" + firstPage + "-" + lastPage + "-group-" + sourceTopicNumber,
                    "核对规则书第 " + firstPage + "–" + lastPage + " 页",
                    "仅依据这些连续来源页的可见事实讲解它们实际支持的规则；保留每个原始标识及其精确页绑定，"
                            + "不要根据页码、位置或通用词汇猜测页面职责。",
                    true,
                    true,
                    queries,
                    coverageTags,
                    pageNumbers);
        }
    }
}
