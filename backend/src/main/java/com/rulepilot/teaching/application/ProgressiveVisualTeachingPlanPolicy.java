package com.rulepilot.teaching.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.ProgressiveTeachingStartDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingPageRole;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingPageSketch;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds a complete immutable source-page plan from one bounded progressive visual observation. */
final class ProgressiveVisualTeachingPlanPolicy {

    private static final String TOPIC_PREFIX = "progressive-visual-page-";
    private static final Set<String> CORE_COVERAGE = Set.of("setup", "core_loop", "end", "scoring");
    private static final Set<String> EARLY_JOURNEY_COVERAGE = Set.of("setup", "core_loop");

    private ProgressiveVisualTeachingPlanPolicy() {}

    static TeachingOutlineModel.OutlineDraft outline(
            String gameTitle,
            List<DocumentProcessing.PageView> documentPages,
            ProgressiveTeachingStartDraft start) {
        validate(documentPages, start);
        Map<Integer, Integer> sourceOrder = java.util.stream.IntStream.range(0, documentPages.size())
                .boxed()
                .collect(Collectors.toUnmodifiableMap(
                        index -> documentPages.get(index).pageNumber(), Function.identity()));
        TeachingPageSketch selected = start.pages().stream()
                .filter(page -> page.pageNumber() == start.selectedPageFacts().pageNumber())
                .findFirst()
                .orElseThrow();
        List<TeachingPageSketch> ordered = new ArrayList<>();
        ordered.add(selected);
        start.pages().stream()
                .filter(page -> page.pageNumber() != selected.pageNumber())
                .filter(page -> page.role() == TeachingPageRole.GAMEPLAY_RULES)
                .sorted(Comparator.comparingInt(ProgressiveVisualTeachingPlanPolicy::journeyOrder)
                        .thenComparingInt(page -> sourceOrder.get(page.pageNumber())))
                .forEach(ordered::add);
        start.pages().stream()
                .filter(page -> page.role() == TeachingPageRole.UNCERTAIN)
                .sorted(Comparator.comparingInt(page -> sourceOrder.get(page.pageNumber())))
                .forEach(ordered::add);

        List<TeachingOutlineModel.TopicDraft> topics = new ArrayList<>(java.util.stream.IntStream.range(0, ordered.size())
                .mapToObj(index -> topic(ordered.get(index), start.selectedPageFacts(), index == 0))
                .toList());
        start.pages().stream()
                .filter(page -> !page.sourceDependencies().isEmpty())
                .sorted(Comparator.comparingInt(page -> sourceOrder.get(page.pageNumber())))
                .map(ProgressiveVisualTeachingPlanPolicy::sourceDependencyTopic)
                .forEach(topics::add);
        return new TeachingOutlineModel.OutlineDraft(
                gameTitle,
                "先发布一个有逐页证据的可读章节，再在后台按同一不可变计划补齐其余玩法页；"
                        + "每条规则仍需通过原页引用验证。",
                List.copyOf(topics));
    }

    static boolean isProgressive(TeachingPlan plan) {
        return plan != null
                && !plan.sections().isEmpty()
                && plan.sections().stream().allMatch(section -> section.topicKey().startsWith(TOPIC_PREFIX));
    }

    static void validate(
            List<DocumentProcessing.PageView> documentPages,
            ProgressiveTeachingStartDraft start) {
        if (documentPages == null || documentPages.isEmpty() || start == null) {
            throw new IllegalArgumentException("progressive visual teaching source is invalid");
        }
        List<Integer> expected = documentPages.stream().map(DocumentProcessing.PageView::pageNumber).toList();
        List<Integer> returned = start.pages().stream().map(TeachingPageSketch::pageNumber).toList();
        if (returned.size() != expected.size()
                || new LinkedHashSet<>(returned).size() != returned.size()
                || !Set.copyOf(returned).equals(Set.copyOf(expected))) {
            throw new IllegalArgumentException("progressive visual teaching did not bind every supplied page exactly");
        }
        TeachingPageSketch selected = start.pages().stream()
                .filter(page -> page.pageNumber() == start.selectedPageFacts().pageNumber())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("progressive visual teaching selected an unknown page"));
        if (selected.role() != TeachingPageRole.GAMEPLAY_RULES || selected.visibleTerms().isEmpty()) {
            throw new IllegalArgumentException("progressive visual teaching selected no verifiable gameplay page");
        }
        boolean earlyJourneyPageAvailable = start.pages().stream()
                .filter(page -> page.role() == TeachingPageRole.GAMEPLAY_RULES)
                .flatMap(page -> page.coverageTags().stream())
                .anyMatch(EARLY_JOURNEY_COVERAGE::contains);
        boolean selectedSupportsEarlyJourney = selected.coverageTags().stream()
                .anyMatch(EARLY_JOURNEY_COVERAGE::contains);
        if (earlyJourneyPageAvailable && !selectedSupportsEarlyJourney) {
            throw new IllegalArgumentException(
                    "progressive visual teaching did not start from the visible early player journey");
        }
        if (start.pages().stream()
                .filter(page -> page.role() == TeachingPageRole.GAMEPLAY_RULES)
                .anyMatch(page -> !page.ruleGroupInventoryComplete())) {
            throw new IllegalArgumentException(
                    "progressive visual teaching did not inventory every visible gameplay rule group");
        }
        validateSelectedFacts(start.selectedPageFacts());
        Set<String> covered = start.pages().stream()
                .filter(page -> page.role() == TeachingPageRole.GAMEPLAY_RULES)
                .flatMap(page -> page.coverageTags().stream())
                .collect(Collectors.toSet());
        Set<String> explicitlyMissing = start.pages().stream()
                .flatMap(page -> page.sourceDependencies().stream())
                .flatMap(dependency -> dependency.missingCoverageTags().stream())
                .collect(Collectors.toSet());
        Set<String> accountedFor = new LinkedHashSet<>(covered);
        accountedFor.addAll(explicitlyMissing);
        if (!accountedFor.containsAll(CORE_COVERAGE)) {
            throw new IllegalArgumentException("progressive visual teaching did not locate every core learning obligation");
        }
    }

    private static TeachingOutlineModel.TopicDraft sourceDependencyTopic(TeachingPageSketch page) {
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
        String joinedTitles = String.join("、", titles);
        return new TeachingOutlineModel.TopicDraft(
                TOPIC_PREFIX + "source-dependency-" + page.pageNumber(),
                "当前规则书还需要的资料",
                bounded(
                        "第 " + page.pageNumber() + " 页只证明当前规则书要求另行查看“" + joinedTitles
                                + "”；明确告诉玩家这份资料尚未导入，不能补写开局步骤、回合流程、结束或计分规则。",
                        600),
                true,
                true,
                titles,
                List.copyOf(tags),
                List.of(page.pageNumber()));
    }

    private static TeachingOutlineModel.TopicDraft topic(
            TeachingPageSketch page,
            PageSummary selectedFacts,
            boolean selected) {
        boolean gameplay = page.role() == TeachingPageRole.GAMEPLAY_RULES;
        String title = page.visibleHeading().isBlank()
                ? page.visibleTerms().stream().findFirst().orElse("核对规则书第 " + page.pageNumber() + " 页")
                : page.visibleHeading();
        LinkedHashSet<String> queries = new LinkedHashSet<>(page.visibleTerms());
        if (selected) queries.addAll(selectedFacts.keywords());
        if (queries.isEmpty()) {
            queries.add("Inspect only the rules visibly supported on PDF page " + page.pageNumber() + ".");
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>(page.coverageTags());
        tags.add("source_coverage");
        return new TeachingOutlineModel.TopicDraft(
                TOPIC_PREFIX + (gameplay ? "rules-" : "uncertain-") + page.pageNumber(),
                bounded(title, 160),
                gameplay
                        ? "仅依据规则书第 " + page.pageNumber()
                                + " 页可核对的原子事实讲解本页实际支持的规则；不要补写其他页面或常识中的细节。"
                        : "核对规则书第 " + page.pageNumber()
                                + " 页是否包含可验证的玩法规则；若页面证据仍不足，明确保留空缺。",
                gameplay,
                true,
                queries.stream().map(value -> bounded(value, 300)).limit(8).toList(),
                List.copyOf(tags),
                List.of(page.pageNumber()));
    }

    private static int journeyOrder(TeachingPageSketch page) {
        if (page.coverageTags().contains("setup")) return 0;
        if (page.coverageTags().contains("core_loop")) return 1;
        if (page.coverageTags().contains("end")) return 3;
        if (page.coverageTags().contains("scoring")) return 4;
        return 2;
    }

    private static void validateSelectedFacts(PageSummary facts) {
        long factCharacters = facts.factualSummary().codePoints()
                .filter(Character::isLetterOrDigit)
                .limit(257)
                .count();
        long factStatements = facts.factualSummary().lines()
                .filter(statement -> !statement.isBlank())
                .limit(10)
                .count();
        String defaultKeyword = "page " + facts.pageNumber();
        if (facts.printedTerms().equals("No legible printed term on this page.")
                || facts.factualSummary().equals("该页没有可可靠转写的规则文字；请直接查看页面图像。")
                || facts.keywords().size() < 2
                || facts.keywords().size() > 6
                || facts.keywords().stream().anyMatch(keyword -> keyword.equalsIgnoreCase(defaultKeyword))
                || factStatements > 8
                || factCharacters < 16) {
            throw new IllegalArgumentException("progressive visual teaching selected page facts are insufficient");
        }
    }

    private static String bounded(String value, int maximum) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        if (normalized.isBlank()) return "核对规则书原页";
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum).stripTrailing();
    }
}
