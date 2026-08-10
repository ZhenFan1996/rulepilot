package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.LessonQualityReport;
import com.rulepilot.teaching.domain.LessonQualityReport.OverallStatus;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LessonCandidateComparisonPolicyTest {

    private final LessonCandidateComparisonPolicy policy = new LessonCandidateComparisonPolicy();

    @Test
    void promotesOnlyWhenTheCandidateStrictlyImprovesDeterministicQuality() {
        TeachingPlan plan = plan();
        IllustratedLesson active = lesson(plan, LessonStatus.DRAFT_READY, EvidenceStatus.CITED_DRAFT);
        IllustratedLesson candidate = lesson(plan, LessonStatus.COMPLETE, EvidenceStatus.SUPPORTED);

        var comparison = policy.compare(plan, active, candidate, quality(50), quality(67));

        assertThat(comparison.recommendation()).isEqualTo(LessonCandidateRecommendation.PROMOTE_CANDIDATE);
        assertThat(comparison.reasons()).anyMatch(reason -> reason.contains("完整状态"));
    }

    @Test
    void keepsTheActiveLessonWhenTheCandidateIsIncomplete() {
        TeachingPlan plan = plan();
        IllustratedLesson active = lesson(plan, LessonStatus.COMPLETE, EvidenceStatus.SUPPORTED);
        IllustratedLesson candidate = lesson(plan, LessonStatus.INCOMPLETE, EvidenceStatus.INSUFFICIENT_EVIDENCE);

        var comparison = policy.compare(plan, active, candidate, quality(67), quality(17));

        assertThat(comparison.recommendation()).isEqualTo(LessonCandidateRecommendation.KEEP_ACTIVE);
    }

    @Test
    void keepsTheStableActiveLessonWhenMeasuredQualityTies() {
        TeachingPlan plan = plan();
        IllustratedLesson active = lesson(plan, LessonStatus.COMPLETE, EvidenceStatus.SUPPORTED);
        IllustratedLesson candidate = lesson(plan, LessonStatus.COMPLETE, EvidenceStatus.SUPPORTED);

        var comparison = policy.compare(plan, active, candidate, quality(67), quality(67));

        assertThat(comparison.recommendation()).isEqualTo(LessonCandidateRecommendation.KEEP_ACTIVE);
        assertThat(comparison.reasons()).containsExactly(
                "安全、覆盖和引用指标持平；为避免无证据的内容漂移，默认保留现行版本。");
    }

    private TeachingPlan plan() {
        UUID versionId = UUID.randomUUID();
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "Example",
                "Learn the rules.",
                List.of(new TeachingPlan.PlannedSection(
                        1,
                        "setup",
                        "Setup",
                        "Set up the table.",
                        true,
                        false,
                        List.of("setup"),
                        List.of("setup"))),
                "admin",
                Instant.parse("2026-08-05T00:00:00Z"));
    }

    private IllustratedLesson lesson(
            TeachingPlan plan, LessonStatus status, EvidenceStatus evidenceStatus) {
        UUID evidenceId = UUID.randomUUID();
        LessonSection section = new LessonSection(
                1,
                "setup",
                List.of("setup"),
                "Setup",
                true,
                evidenceStatus,
                VisualKind.TABLE_LAYOUT,
                "Set up the table.",
                List.of(2),
                evidenceStatus == EvidenceStatus.INSUFFICIENT_EVIDENCE ? List.of() : List.of(evidenceId),
                List.of(new LessonStep(
                        1,
                        "Place board",
                        TeachingMove.DO,
                        "Place the board in the center.",
                        evidenceStatus == EvidenceStatus.INSUFFICIENT_EVIDENCE ? List.of() : List.of(2),
                        evidenceStatus == EvidenceStatus.INSUFFICIENT_EVIDENCE ? List.of() : List.of(evidenceId))));
        return new IllustratedLesson(
                UUID.randomUUID(), plan.id(), status, List.of(section), "candidate-test", Instant.now());
    }

    private LessonQualityReport quality(int score) {
        return new LessonQualityReport(OverallStatus.NEEDS_REVIEW, score, List.of());
    }
}
