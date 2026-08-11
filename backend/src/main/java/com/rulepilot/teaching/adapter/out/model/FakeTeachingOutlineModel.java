package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.VisualRulebookPageClassifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
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

    private static final int MAX_TOPIC_SOURCE_PAGES = 5;
    private static final int MAX_TOPICS = 16;
    private static final List<String> CORE_TAGS = List.of("setup", "core_loop", "end", "scoring");

    @Override
    public OutlineDraft organize(OutlineRequest request) {
        return isVisualCatalog(request) ? visualCatalogOutline(request) : textFallbackOutline();
    }

    private OutlineDraft textFallbackOutline() {
        return new OutlineDraft(
                "Imported rulebook",
                "按准备、主要流程、结束与计分四项通用学习目标检索原规则；没有可核对证据时明确保留空缺。",
                List.of(
                        coreTopic(
                                "setup",
                                "准备游戏",
                                "从整份规则书检索并说明开始游戏前必须完成的准备；只保留有引用支持的步骤。",
                                List.of("How is the game set up? What changes by player count?"),
                                List.of("setup")),
                        coreTopic(
                                "core-loop",
                                "进行回合与主要行动",
                                "从整份规则书检索玩家实际轮流做什么、选择如何生效，以及阶段如何推进。",
                                List.of("What may a player do on a turn, and how does play advance?"),
                                List.of("core_loop")),
                        coreTopic(
                                "ending",
                                "确认游戏何时结束",
                                "从整份规则书检索结束触发与最后处理；不要把邻近的流程描述当作结束条件。",
                                List.of("What exactly triggers the end of the game, and what happens next?"),
                                List.of("end")),
                        coreTopic(
                                "scoring",
                                "完成计分并判定结果",
                                "从整份规则书检索所有计分来源、胜者判定与同分处理；缺失时明确说明。",
                                List.of("How are final results, scoring, the winner, and ties resolved?"),
                                List.of("scoring"))));
    }

    private TopicDraft coreTopic(
            String key, String title, String objective, List<String> queries, List<String> coverageTags) {
        return new TopicDraft(key, title, objective, true, false, queries, coverageTags, List.of());
    }

    private OutlineDraft visualCatalogOutline(OutlineRequest request) {
        List<PageInput> admitted = request.pages().stream()
                .filter(page -> VisualRulebookPageClassifier.isSubstantive(page.pageNumber(), page.text()))
                .toList();
        List<PageInput> sourcePages = admitted.isEmpty() ? request.pages() : admitted;
        int requiredTopics = (sourcePages.size() + MAX_TOPIC_SOURCE_PAGES - 1) / MAX_TOPIC_SOURCE_PAGES;
        if (requiredTopics > MAX_TOPICS) {
            throw new IllegalArgumentException(
                    "visual rulebook exceeds source-preserving fallback capacity; a semantic outline model is required");
        }

        List<TopicDraft> topics = new ArrayList<>();
        for (int start = 0; start < sourcePages.size(); start += MAX_TOPIC_SOURCE_PAGES) {
            List<PageInput> group = sourcePages.subList(
                    start, Math.min(start + MAX_TOPIC_SOURCE_PAGES, sourcePages.size()));
            int firstPage = group.getFirst().pageNumber();
            int lastPage = group.getLast().pageNumber();
            String pageLabel = firstPage == lastPage ? String.valueOf(firstPage) : firstPage + "–" + lastPage;
            topics.add(new TopicDraft(
                    "source-pages-" + firstPage + "-" + lastPage,
                    "核对规则书第 " + pageLabel + " 页",
                    "仅依据这些页面的可见事实讲解它们实际支持的规则；不要根据页码、位置或通用词汇猜测页面职责。",
                    true,
                    true,
                    sourceQueries(group),
                    topics.isEmpty() ? CORE_TAGS : List.of("source_coverage"),
                    group.stream().map(PageInput::pageNumber).toList()));
        }
        return new OutlineDraft(
                "Imported rulebook",
                "逐页核对视觉规则证据，再由证据约束的讲解模型组织玩家可执行的说明；无法确认的内容保持为空缺。",
                List.copyOf(topics));
    }

    private List<String> sourceQueries(List<PageInput> pages) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        for (PageInput page : pages) {
            printedTerms(page.text()).stream().map(this::boundedQuery).forEach(queries::add);
            visibleFacts(page.text()).stream().map(this::boundedQuery).forEach(queries::add);
            if (queries.size() >= 4) break;
        }
        if (queries.isEmpty()) queries.add("Inspect the cited rulebook pages and report only directly visible rules.");
        return queries.stream().filter(value -> !value.isBlank()).limit(4).toList();
    }

    private List<String> printedTerms(String text) {
        return fieldValues(text, "Printed terms:", ";");
    }

    private List<String> visibleFacts(String text) {
        return fieldValues(text, "Visible facts:", "\n");
    }

    private List<String> fieldValues(String text, String marker, String separator) {
        return Arrays.stream(text.split("\\R"))
                .filter(line -> line.startsWith(marker))
                .map(line -> line.substring(marker.length()).strip())
                .flatMap(value -> Arrays.stream(value.split(separator)))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .limit(4)
                .toList();
    }

    private String boundedQuery(String value) {
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }

    private boolean isVisualCatalog(OutlineRequest request) {
        return request.pages().stream().allMatch(page -> page.text().startsWith("[Visual page catalog;"));
    }
}
