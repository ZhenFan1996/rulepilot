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
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LessonQualityEvaluator {

    public LessonQualityReport evaluate(TeachingPlan plan, IllustratedLesson lesson) {
        List<QualityCheck> checks = new ArrayList<>();
        checks.add(requiredCoverage(plan, lesson));
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
                "必需章节 " + available + " / " + required,
                available < required
                        ? "仍有必需章节缺少可引用的规则证据。"
                        : reviewed < required
                                ? "完整基础讲解已经可读，其中 " + reviewed + " 章已完成二次核对。"
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
                executable ? "Setup 具备可执行步骤" : "Setup 尚不可执行",
                !executable
                        ? "需要补充并引用按顺序可执行的开局布置步骤。"
                        : reviewed
                                ? "Setup 包含有页码依据且已核对的执行步骤。"
                                : "Setup 已有带页码的执行步骤，可以使用，细节仍在二次核对。");
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
                missing.isEmpty() ? "结束与计分完整" : "结束与计分仍有缺口",
                missing.isEmpty()
                        ? reviewed
                                ? "结束条件、最终计分和同分处理均有证据并已核对。"
                                : "结束条件与最终计分已有引用，可以使用，细节仍在二次核对。"
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
