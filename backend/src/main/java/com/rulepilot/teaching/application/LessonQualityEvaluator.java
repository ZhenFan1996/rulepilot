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
import java.util.List;
import org.springframework.stereotype.Component;

/** Reports the two durable player-safety boundaries without reconstructing the retired coverage ledger. */
@Component
public class LessonQualityEvaluator {

    public LessonQualityReport evaluate(TeachingPlan plan, IllustratedLesson lesson) {
        List<QualityCheck> checks = List.of(
                readableChapterCoverage(plan, lesson),
                citationIdentity(lesson),
                new QualityCheck(
                        CheckType.EXPANSION_SCOPE,
                        CheckStatus.NOT_EVALUATED,
                        "扩展范围尚未验证",
                        "当前教学计划未保存扩展集合快照。"));
        long passed = checks.stream().filter(check -> check.status() == CheckStatus.PASS).count();
        int score = Math.toIntExact(passed * 100 / checks.size());
        OverallStatus status = checks.stream().anyMatch(check -> check.status() == CheckStatus.FAIL)
                ? OverallStatus.BLOCKED
                : OverallStatus.NEEDS_REVIEW;
        return new LessonQualityReport(status, score, checks);
    }

    private QualityCheck readableChapterCoverage(TeachingPlan plan, IllustratedLesson lesson) {
        long planned = plan.sections().stream().filter(TeachingPlan.PlannedSection::required).count();
        long readable = lesson.sections().stream()
                .filter(LessonSection::required)
                .filter(section -> section.evidenceStatus() != EvidenceStatus.INSUFFICIENT_EVIDENCE)
                .count();
        return new QualityCheck(
                CheckType.REQUIRED_SECTION_COVERAGE,
                readable == planned ? CheckStatus.PASS : CheckStatus.NOT_EVALUATED,
                "可读章节 " + readable + " / " + planned,
                readable == planned
                        ? "每个规划章节都保留了可读、可引用的正文。"
                        : "已验证章节继续可读；未完成主题作为明确缺口保留，不抹掉已有内容。");
    }

    private QualityCheck citationIdentity(IllustratedLesson lesson) {
        var steps = lesson.sections().stream().flatMap(section -> section.steps().stream()).toList();
        long cited = steps.stream()
                .filter(step -> !step.sourcePages().isEmpty() && !step.sourceChunkIds().isEmpty())
                .count();
        boolean valid = !steps.isEmpty() && cited == steps.size();
        return new QualityCheck(
                CheckType.CITATION_SUPPORT,
                valid ? CheckStatus.PASS : CheckStatus.FAIL,
                "带来源身份的步骤 " + cited + " / " + steps.size(),
                valid
                        ? "每个已发布步骤都携带当前规则书的页码与证据身份。"
                        : "至少一个玩家可见步骤缺少来源页码或证据身份。");
    }
}
