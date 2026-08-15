package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.LessonQualityReport;
import com.rulepilot.teaching.domain.LessonQualityReport.CheckStatus;
import com.rulepilot.teaching.domain.LessonQualityReport.CheckType;
import com.rulepilot.teaching.domain.LessonQualityReport.OverallStatus;
import com.rulepilot.teaching.domain.LessonQualityReport.QualityCheck;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class LessonQualityEvaluator {

    public LessonQualityReport evaluate(TeachingPlan plan, IllustratedLesson lesson) {
        List<QualityCheck> checks = new ArrayList<>();
        checks.add(requiredCoverage(plan, lesson));
        if (plan.sections().stream().anyMatch(section -> section.coverageTags().contains("source_coverage"))) {
            checks.add(sourceRuleGroupCoverage(plan, lesson));
        }
        if (plan.sections().stream().anyMatch(section -> section.coverageTags().contains("source_dependency"))) {
            checks.add(sourceAvailability(plan));
        }
        checks.add(citationSupport(lesson));
        checks.add(setupExecutability(lesson));
        checks.add(endAndScoring(lesson));
        checks.add(new QualityCheck(
                CheckType.EXPANSION_SCOPE,
                CheckStatus.NOT_EVALUATED,
                "扩展范围尚未验证",
                "当前教学计划未保存扩展集合快照，不能确认讲解是否包含或排除了扩展规则。"));

        long passed = checks.stream().filter(check -> check.status() == CheckStatus.PASS).count();
        int score = (int) Math.round(passed * 100.0 / checks.size());
        OverallStatus status = checks.stream().anyMatch(check -> check.status() == CheckStatus.FAIL)
                ? OverallStatus.BLOCKED
                : checks.stream().anyMatch(check -> check.status() == CheckStatus.NOT_EVALUATED)
                        ? OverallStatus.NEEDS_REVIEW
                        : OverallStatus.READY;
        return new LessonQualityReport(status, score, checks);
    }

    private QualityCheck requiredCoverage(TeachingPlan plan, IllustratedLesson lesson) {
        long required = plan.sections().stream().filter(TeachingPlan.PlannedSection::required).count();
        long available = lesson.sections().stream()
                .filter(LessonSection::required)
                .filter(this::hasCitedEvidence)
                .count();
        long reviewed = lesson.sections().stream()
                .filter(LessonSection::required)
                .filter(section -> section.evidenceStatus() == EvidenceStatus.SUPPORTED)
                .count();
        CheckStatus status = available < required
                ? CheckStatus.FAIL
                : reviewed < required ? CheckStatus.NOT_EVALUATED : CheckStatus.PASS;
        return new QualityCheck(
                CheckType.REQUIRED_SECTION_COVERAGE,
                status,
                "必需章节已核对 " + reviewed + " / " + required,
                available < required
                        ? "有 " + (required - available) + " 个必需章节缺少可引用的规则证据。"
                        : reviewed < required
                                ? available + " 个必需章节已有引用，但还有 " + (required - reviewed)
                                        + " 个未完成独立事实核对；不能确认整套讲解可以独立开桌。"
                                : "所有必需章节都有规则证据并已完成核对。");
    }

    private QualityCheck citationSupport(IllustratedLesson lesson) {
        var supportedSteps = lesson.sections().stream()
                .filter(this::hasCitedEvidence)
                .flatMap(section -> section.steps().stream())
                .toList();
        long cited = supportedSteps.stream().filter(step -> !step.sourcePages().isEmpty()).count();
        int percentage = supportedSteps.isEmpty()
                ? 0
                : (int) Math.round(cited * 100.0 / supportedSteps.size());
        return new QualityCheck(
                CheckType.CITATION_SUPPORT,
                !supportedSteps.isEmpty() && percentage >= 95 ? CheckStatus.PASS : CheckStatus.FAIL,
                "规则步骤引用支持率 " + percentage + "%",
                cited + " / " + supportedSteps.size() + " 个有规则内容的步骤带来源页码；目标为至少 95%。");
    }

    private QualityCheck sourceRuleGroupCoverage(TeachingPlan plan, IllustratedLesson lesson) {
        List<TeachingPlan.PlannedSection> sourceSections = plan.sections().stream()
                .filter(section -> section.coverageTags().contains("source_coverage"))
                .toList();
        long required = sourceSections.stream()
                .mapToLong(section -> section.retrievalQueries().size())
                .sum();
        long available = sourceSections.stream()
                .mapToLong(planned -> coveredSourceGroups(planned, lesson, false))
                .sum();
        long reviewed = sourceSections.stream()
                .mapToLong(planned -> coveredSourceGroups(planned, lesson, true))
                .sum();
        CheckStatus status = available < required
                ? CheckStatus.FAIL
                : reviewed < required ? CheckStatus.NOT_EVALUATED : CheckStatus.PASS;
        return new QualityCheck(
                CheckType.SOURCE_RULE_GROUP_COVERAGE,
                status,
                "来源规则组已核对 " + reviewed + " / " + required,
                available < required
                        ? "有 " + (required - available) + " 个从规则页清点出的规则组，尚未逐项在带原始标识和"
                                + "来源页的讲解步骤中出现；不能把整章通过自动算成全部规则组已核对。"
                        : reviewed < required
                                ? available + " 个来源规则组已有引用，但还有 " + (required - reviewed)
                                        + " 个未通过逐项完整证据窗口的独立核对；"
                                        + "不能确认全部可读规则组均已进入讲解。"
                                : "每个从规则页清点出的可读规则组，都以原始标识和同页引用进入讲解并通过独立核对。");
    }

    private long coveredSourceGroups(
            TeachingPlan.PlannedSection planned,
            IllustratedLesson lesson,
            boolean requireReview) {
        LessonSection section = lesson.sections().stream()
                .filter(candidate -> candidate.topicKey().equals(planned.topicKey()))
                .findFirst()
                .orElse(null);
        if (section == null
                || !hasCitedEvidence(section)
                || (requireReview && section.evidenceStatus() != EvidenceStatus.SUPPORTED)) {
            return 0;
        }
        return planned.retrievalQueries().stream()
                .filter(query -> section.steps().stream().anyMatch(step -> {
                    if (step.sourcePages().isEmpty()) return false;
                    if (!planned.sourcePageNumbers().isEmpty()
                            && step.sourcePages().stream().noneMatch(planned.sourcePageNumbers()::contains)) {
                        return false;
                    }
                    String visibleStep = normalized(step.heading() + " " + step.text());
                    return containsSourceIdentifier(visibleStep, normalized(query));
                }))
                .count();
    }

    private boolean containsSourceIdentifier(String text, String identifier) {
        if (identifier.isBlank()) return false;
        boolean ascii = identifier.codePoints().allMatch(codePoint -> codePoint < 128);
        if (!ascii) return text.contains(identifier);
        int firstWordCharacter = java.util.stream.IntStream.range(0, identifier.length())
                .filter(index -> Character.isLetterOrDigit(identifier.charAt(index)))
                .findFirst()
                .orElse(-1);
        int lastWordCharacter = java.util.stream.IntStream.iterate(
                        identifier.length() - 1, index -> index >= 0, index -> index - 1)
                .filter(index -> Character.isLetterOrDigit(identifier.charAt(index)))
                .findFirst()
                .orElse(-1);
        if (firstWordCharacter < 0 || lastWordCharacter < 0) return text.contains(identifier);
        int from = 0;
        while (from <= text.length() - identifier.length()) {
            int match = text.indexOf(identifier, from);
            if (match < 0) return false;
            int left = match + firstWordCharacter - 1;
            int right = match + lastWordCharacter + 1;
            boolean leftBoundary = left < 0 || !Character.isLetterOrDigit(text.charAt(left));
            boolean rightBoundary = right >= text.length() || !Character.isLetterOrDigit(text.charAt(right));
            if (leftBoundary && rightBoundary) return true;
            from = match + 1;
        }
        return false;
    }

    private QualityCheck sourceAvailability(TeachingPlan plan) {
        List<TeachingPlan.PlannedSection> dependencies = plan.sections().stream()
                .filter(section -> section.coverageTags().contains("source_dependency"))
                .toList();
        List<String> sources = dependencies.stream()
                .flatMap(section -> section.retrievalQueries().stream()
                        .map(title -> sourceReference(section.sourcePageNumbers(), title)))
                .distinct()
                .toList();
        long sourceCount = dependencies.stream()
                .flatMap(section -> section.retrievalQueries().stream())
                .map(this::normalized)
                .distinct()
                .count();
        Set<String> missingResponsibilities = new LinkedHashSet<>();
        dependencies.stream()
                .flatMap(section -> section.coverageTags().stream())
                .filter(tag -> tag.startsWith("missing_") && tag.endsWith("_source"))
                .map(tag -> tag.substring("missing_".length(), tag.length() - "_source".length()))
                .map(this::responsibilityLabel)
                .forEach(missingResponsibilities::add);
        String missingDetail = missingResponsibilities.isEmpty()
                ? "当前文档不包含这些被引用资料中的具体规则。"
                : "当前文档不包含" + String.join("、", missingResponsibilities) + "。";
        return new QualityCheck(
                CheckType.SOURCE_AVAILABILITY,
                CheckStatus.FAIL,
                "当前规则书还缺 " + sourceCount + " 份被明确引用的资料",
                String.join("；", sources) + "；这些名称只证明来源依赖存在，" + missingDetail
                        + "需要合法补充相应资料后才能核对并补齐讲解。");
    }

    private String sourceReference(List<Integer> pages, String title) {
        String pageLabel = pages.isEmpty()
                ? "来源页"
                : "第 " + pages.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("、")) + " 页";
        return pageLabel + "指向 " + title;
    }

    private String responsibilityLabel(String tag) {
        return switch (tag) {
            case "setup" -> "开局步骤";
            case "core_loop" -> "核心回合流程";
            case "end" -> "结束规则";
            case "scoring" -> "计分规则";
            default -> "对应规则";
        };
    }

    private String normalized(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
    }

    private QualityCheck setupExecutability(IllustratedLesson lesson) {
        var setup = section(lesson, "setup");
        boolean executable = setup != null
                && hasCitedEvidence(setup)
                && !setup.steps().isEmpty()
                && setup.steps().stream().allMatch(step -> !step.sourcePages().isEmpty());
        boolean reviewed = executable && setup.evidenceStatus() == EvidenceStatus.SUPPORTED;
        return new QualityCheck(
                CheckType.SETUP_EXECUTABILITY,
                !executable ? CheckStatus.FAIL : reviewed ? CheckStatus.PASS : CheckStatus.NOT_EVALUATED,
                !executable
                        ? "Setup 尚不可执行"
                        : reviewed ? "Setup 可执行性已核对" : "Setup 有引用，待核对可执行性",
                !executable
                        ? "需要补充并引用按顺序可执行的开局布置步骤。"
                        : reviewed
                                ? "Setup 包含有页码依据且已核对的执行步骤。"
                                : "Setup 步骤带有来源页码，但尚未完成独立事实与缺项核对；"
                                        + "不能确认仅照这些步骤即可完成开局。");
    }

    private QualityCheck endAndScoring(IllustratedLesson lesson) {
        List<String> required = List.of("end", "scoring");
        List<String> missing = required.stream()
                .filter(tag -> {
                    var section = section(lesson, tag);
                    return section == null || !hasCitedEvidence(section);
                })
                .toList();
        boolean reviewed = missing.isEmpty() && required.stream()
                .map(tag -> section(lesson, tag))
                .allMatch(section -> section.evidenceStatus() == EvidenceStatus.SUPPORTED);
        return new QualityCheck(
                CheckType.END_AND_SCORING_COMPLETENESS,
                !missing.isEmpty() ? CheckStatus.FAIL : reviewed ? CheckStatus.PASS : CheckStatus.NOT_EVALUATED,
                !missing.isEmpty()
                        ? "结束与计分仍有缺口"
                        : reviewed ? "结束与计分已核对" : "结束与计分待核对",
                missing.isEmpty()
                        ? reviewed
                                ? "结束条件与最终计分均有证据并已完成独立核对。"
                                : "结束与计分章节已有引用，但尚未完成独立事实与缺项核对；"
                                        + "不能确认聚合方式、例外或适用的同分规则已经完整。"
                        : "缺少：" + missing.stream().map(this::label).collect(java.util.stream.Collectors.joining("、")));
    }

    private boolean hasCitedEvidence(LessonSection section) {
        return section.evidenceStatus() != EvidenceStatus.INSUFFICIENT_EVIDENCE;
    }

    private LessonSection section(IllustratedLesson lesson, String coverageTag) {
        return lesson.sections().stream()
                .filter(section -> section.coverageTags().contains(coverageTag))
                .findFirst()
                .orElse(null);
    }

    private String label(String tag) {
        return switch (tag) {
            case "end" -> "结束条件";
            case "scoring" -> "最终计分";
            default -> tag;
        };
    }
}
