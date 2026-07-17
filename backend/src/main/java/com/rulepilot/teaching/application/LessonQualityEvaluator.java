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
import com.rulepilot.teaching.domain.TeachingSectionType;
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
                CheckType.PLAYER_COUNT_SCOPE,
                CheckStatus.NOT_EVALUATED,
                "人数适配尚未验证",
                "计划面向 " + plan.playerCount() + " 人，其中 " + plan.beginnerCount()
                        + " 位新手；当前规则证据尚未标注人数适用范围。"));
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
        long supported = lesson.sections().stream()
                .filter(LessonSection::required)
                .filter(section -> section.evidenceStatus() == EvidenceStatus.SUPPORTED)
                .count();
        return new QualityCheck(
                CheckType.REQUIRED_SECTION_COVERAGE,
                supported == required ? CheckStatus.PASS : CheckStatus.FAIL,
                "必需章节 " + supported + " / " + required,
                supported == required ? "所有必需章节都有规则证据。" : "缺失章节必须补充证据后才能交付完整讲解。");
    }

    private QualityCheck citationSupport(IllustratedLesson lesson) {
        var supportedSteps = lesson.sections().stream()
                .filter(section -> section.evidenceStatus() == EvidenceStatus.SUPPORTED)
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
        var setup = section(lesson, TeachingSectionType.SETUP);
        boolean executable = setup != null
                && setup.evidenceStatus() == EvidenceStatus.SUPPORTED
                && !setup.steps().isEmpty()
                && setup.steps().stream().allMatch(step -> !step.sourcePages().isEmpty());
        return new QualityCheck(
                CheckType.SETUP_EXECUTABILITY,
                executable ? CheckStatus.PASS : CheckStatus.FAIL,
                executable ? "Setup 具备可执行步骤" : "Setup 尚不可执行",
                executable ? "Setup 至少包含一个有页码依据的步骤。" : "需要补充并引用按顺序可执行的开局布置步骤。");
    }

    private QualityCheck endAndScoring(IllustratedLesson lesson) {
        List<TeachingSectionType> required = List.of(
                TeachingSectionType.END_CONDITIONS,
                TeachingSectionType.SCORING,
                TeachingSectionType.TIE_BREAKERS);
        List<TeachingSectionType> missing = required.stream()
                .filter(type -> {
                    var section = section(lesson, type);
                    return section == null || section.evidenceStatus() != EvidenceStatus.SUPPORTED;
                })
                .toList();
        return new QualityCheck(
                CheckType.END_AND_SCORING_COMPLETENESS,
                missing.isEmpty() ? CheckStatus.PASS : CheckStatus.FAIL,
                missing.isEmpty() ? "结束与计分完整" : "结束与计分仍有缺口",
                missing.isEmpty()
                        ? "结束条件、最终计分和同分处理均有证据。"
                        : "缺少：" + missing.stream().map(this::label).collect(java.util.stream.Collectors.joining("、")));
    }

    private LessonSection section(IllustratedLesson lesson, TeachingSectionType type) {
        return lesson.sections().stream()
                .filter(section -> section.type() == type)
                .findFirst()
                .orElse(null);
    }

    private String label(TeachingSectionType type) {
        return switch (type) {
            case END_CONDITIONS -> "结束条件";
            case SCORING -> "最终计分";
            case TIE_BREAKERS -> "同分处理";
            default -> type.name();
        };
    }
}
