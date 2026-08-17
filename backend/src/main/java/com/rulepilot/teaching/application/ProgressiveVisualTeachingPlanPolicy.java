package com.rulepilot.teaching.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageSlotDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.ProgressiveTeachingStartDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupCoverage;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingPageRole;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingPageSketch;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds a complete immutable source-page plan from one bounded progressive visual observation. */
final class ProgressiveVisualTeachingPlanPolicy {

    private static final String TOPIC_PREFIX = "progressive-visual-page-";

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
                .sorted(java.util.Comparator.comparingInt(page -> sourceOrder.get(page.pageNumber())))
                .forEach(ordered::add);
        start.pages().stream()
                .filter(page -> page.role() == TeachingPageRole.UNCERTAIN)
                .sorted(java.util.Comparator.comparingInt(page -> sourceOrder.get(page.pageNumber())))
                .forEach(ordered::add);

        List<TeachingOutlineModel.TopicDraft> topics = new ArrayList<>(java.util.stream.IntStream.range(0, ordered.size())
                .mapToObj(index -> topic(ordered.get(index)))
                .toList());
        start.pages().stream()
                .filter(page -> !page.sourceDependencies().isEmpty())
                .sorted(java.util.Comparator.comparingInt(page -> sourceOrder.get(page.pageNumber())))
                .map(ProgressiveVisualTeachingPlanPolicy::sourceDependencyTopic)
                .forEach(topics::add);
        boolean explicitSourceContract = hasExplicitSourceContract(start);
        return new TeachingOutlineModel.OutlineDraft(
                gameTitle,
                "先发布一个有逐页证据的可读章节，再在后台按同一不可变计划补齐其余玩法页；"
                        + "每条规则仍需通过原页引用验证。",
                List.copyOf(topics),
                explicitSourceContract ? sourceCoverageSlots(start) : List.of(),
                explicitSourceContract);
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
        if (selected.role() != TeachingPageRole.GAMEPLAY_RULES) {
            throw new IllegalArgumentException("progressive visual teaching selected no verifiable gameplay page");
        }
    }

    private static boolean hasExplicitSourceContract(ProgressiveTeachingStartDraft start) {
        return start.pages().stream()
                .filter(page -> page.role() == TeachingPageRole.GAMEPLAY_RULES)
                .allMatch(page -> !page.ruleGroupCoverage().isEmpty());
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
                sourceDependencyTopicKey(page),
                "当前规则书还需要的资料",
                "第 " + page.pageNumber() + " 页只证明当前规则书要求另行查看“" + joinedTitles
                        + "”；明确告诉玩家这份资料尚未导入，不要补写缺失资料中的规则。",
                true,
                true,
                titles,
                List.copyOf(tags),
                List.of(page.pageNumber()));
    }

    private static TeachingOutlineModel.TopicDraft topic(TeachingPageSketch page) {
        boolean gameplay = page.role() == TeachingPageRole.GAMEPLAY_RULES;
        String title = page.visibleHeading().isBlank()
                ? page.visibleTerms().stream().findFirst().orElse("核对规则书第 " + page.pageNumber() + " 页")
                : page.visibleHeading();
        LinkedHashSet<String> queries = new LinkedHashSet<>(page.visibleTerms());
        if (queries.isEmpty()) {
            queries.add("Inspect only the rules visibly supported on PDF page " + page.pageNumber() + ".");
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>(page.coverageTags());
        tags.add("source_coverage");
        page.ruleGroupCoverage().stream()
                .map(RuleGroupCoverage::role)
                .map(ProgressiveVisualTeachingPlanPolicy::roleCoverageTag)
                .filter(java.util.Objects::nonNull)
                .forEach(tags::add);
        return new TeachingOutlineModel.TopicDraft(
                topicKey(page),
                title,
                gameplay
                        ? "仅依据规则书第 " + page.pageNumber()
                                + " 页可核对的原子事实讲解本页实际支持的规则；不要补写其他页面或常识中的细节。"
                        : "核对规则书第 " + page.pageNumber()
                                + " 页是否包含可验证的玩法规则；若页面证据仍不足，明确保留空缺。",
                gameplay,
                true,
                List.copyOf(queries),
                List.copyOf(tags),
                List.of(page.pageNumber()));
    }

    private static List<SourceCoverageSlotDraft> sourceCoverageSlots(ProgressiveTeachingStartDraft start) {
        List<SourceCoverageSlotDraft> slots = new ArrayList<>();
        for (TeachingPageSketch page : start.pages()) {
            for (int index = 0; index < page.ruleGroupCoverage().size(); index++) {
                RuleGroupCoverage coverage = page.ruleGroupCoverage().get(index);
                slots.add(new SourceCoverageSlotDraft(
                        "page-" + page.pageNumber() + "-rule-" + (index + 1),
                        coverage.role(),
                        coverage.identifier(),
                        List.of(page.pageNumber()),
                        topicKey(page),
                        SourceCoverageAvailability.SOURCED));
            }
            for (int dependencyIndex = 0; dependencyIndex < page.sourceDependencies().size(); dependencyIndex++) {
                SourceDependency dependency = page.sourceDependencies().get(dependencyIndex);
                for (String missing : dependency.missingCoverageTags()) {
                    slots.add(new SourceCoverageSlotDraft(
                            "page-" + page.pageNumber() + "-source-" + (dependencyIndex + 1)
                                    + "-missing-" + missing.replace('_', '-'),
                            missingRole(missing),
                            dependency.title(),
                            List.of(page.pageNumber()),
                            sourceDependencyTopicKey(page),
                            SourceCoverageAvailability.MISSING_EXTERNAL_SOURCE));
                }
            }
        }
        return List.copyOf(slots);
    }

    private static String topicKey(TeachingPageSketch page) {
        return TOPIC_PREFIX
                + (page.role() == TeachingPageRole.GAMEPLAY_RULES ? "rules-" : "uncertain-")
                + page.pageNumber();
    }

    private static String sourceDependencyTopicKey(TeachingPageSketch page) {
        return TOPIC_PREFIX + "source-dependency-" + page.pageNumber();
    }

    private static String roleCoverageTag(SourceCoverageRole role) {
        return switch (role) {
            case SETUP -> "setup";
            case CORE_LOOP -> "core_loop";
            case LEGAL_ACTION -> "legal_action";
            case ENDING -> "end";
            case SCORING -> "scoring";
            case NECESSARY_EXCEPTION -> "necessary_exception";
            case SUPPORTING_RULE -> null;
        };
    }

    private static SourceCoverageRole missingRole(String coverageTag) {
        return switch (coverageTag) {
            case "setup" -> SourceCoverageRole.SETUP;
            case "core_loop" -> SourceCoverageRole.CORE_LOOP;
            case "end" -> SourceCoverageRole.ENDING;
            case "scoring" -> SourceCoverageRole.SCORING;
            default -> SourceCoverageRole.SUPPORTING_RULE;
        };
    }
}
