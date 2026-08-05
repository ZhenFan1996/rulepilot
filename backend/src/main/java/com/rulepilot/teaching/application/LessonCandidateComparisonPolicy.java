package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.LessonQualityReport;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.ArrayList;
import java.util.List;

/** Compares only deterministic safety and coverage signals; prose taste remains visible for human review. */
final class LessonCandidateComparisonPolicy {

    Comparison compare(
            TeachingPlan plan,
            IllustratedLesson active,
            IllustratedLesson candidate,
            LessonQualityReport activeQuality,
            LessonQualityReport candidateQuality) {
        Score activeScore = score(plan, active, activeQuality);
        Score candidateScore = score(plan, candidate, candidateQuality);
        int ordering = candidateScore.compareTo(activeScore);
        LessonCandidateRecommendation recommendation = ordering > 0
                ? LessonCandidateRecommendation.PROMOTE_CANDIDATE
                : LessonCandidateRecommendation.KEEP_ACTIVE;
        List<String> reasons = reasons(activeScore, candidateScore, recommendation);
        return new Comparison(recommendation, reasons, activeScore, candidateScore);
    }

    private Score score(TeachingPlan plan, IllustratedLesson lesson, LessonQualityReport quality) {
        List<LessonSection> required = lesson.sections().stream().filter(LessonSection::required).toList();
        int requiredPlanned = Math.toIntExact(plan.sections().stream()
                .filter(TeachingPlan.PlannedSection::required)
                .count());
        int requiredCited = (int) required.stream()
                .filter(section -> section.evidenceStatus() != EvidenceStatus.INSUFFICIENT_EVIDENCE)
                .count();
        int requiredSupported = (int) required.stream()
                .filter(section -> section.evidenceStatus() == EvidenceStatus.SUPPORTED)
                .count();
        int citedSteps = (int) lesson.sections().stream()
                .flatMap(section -> section.steps().stream())
                .filter(step -> !step.sourcePages().isEmpty() && !step.sourceChunkIds().isEmpty())
                .count();
        int totalSteps = lesson.sections().stream().mapToInt(section -> section.steps().size()).sum();
        int citationPercentage = totalSteps == 0 ? 0 : (int) Math.round(citedSteps * 100.0 / totalSteps);
        return new Score(
                statusRank(lesson.status()),
                requiredCited == requiredPlanned ? 1 : 0,
                requiredSupported,
                quality.score(),
                citationPercentage);
    }

    private int statusRank(LessonStatus status) {
        return switch (status) {
            case INCOMPLETE -> 0;
            case DRAFT_READY -> 1;
            case COMPLETE -> 2;
        };
    }

    private List<String> reasons(
            Score active, Score candidate, LessonCandidateRecommendation recommendation) {
        List<String> reasons = new ArrayList<>();
        if (candidate.statusRank() != active.statusRank()) {
            reasons.add("完整状态：现行 " + active.statusRank() + "，候选 " + candidate.statusRank());
        }
        if (candidate.requiredCoverageComplete() != active.requiredCoverageComplete()) {
            reasons.add("必需章节覆盖：现行 " + label(active.requiredCoverageComplete())
                    + "，候选 " + label(candidate.requiredCoverageComplete()));
        }
        if (candidate.requiredSupported() != active.requiredSupported()) {
            reasons.add("已复核必需章节：现行 " + active.requiredSupported()
                    + "，候选 " + candidate.requiredSupported());
        }
        if (candidate.qualityScore() != active.qualityScore()) {
            reasons.add("质量门分数：现行 " + active.qualityScore() + "，候选 " + candidate.qualityScore());
        }
        if (candidate.citationPercentage() != active.citationPercentage()) {
            reasons.add("步骤双重引用率：现行 " + active.citationPercentage()
                    + "% ，候选 " + candidate.citationPercentage() + "%");
        }
        if (reasons.isEmpty()) {
            reasons.add("安全、覆盖和引用指标持平；为避免无证据的内容漂移，默认保留现行版本。");
        } else if (recommendation == LessonCandidateRecommendation.KEEP_ACTIVE) {
            reasons.add("候选未在确定性指标上严格胜出，默认保留现行版本。");
        }
        return List.copyOf(reasons);
    }

    private String label(int complete) {
        return complete == 1 ? "完整" : "不完整";
    }

    record Comparison(
            LessonCandidateRecommendation recommendation,
            List<String> reasons,
            Score activeScore,
            Score candidateScore) {}

    record Score(
            int statusRank,
            int requiredCoverageComplete,
            int requiredSupported,
            int qualityScore,
            int citationPercentage) implements Comparable<Score> {

        @Override
        public int compareTo(Score other) {
            int comparison = Integer.compare(statusRank, other.statusRank);
            if (comparison != 0) return comparison;
            comparison = Integer.compare(requiredCoverageComplete, other.requiredCoverageComplete);
            if (comparison != 0) return comparison;
            comparison = Integer.compare(requiredSupported, other.requiredSupported);
            if (comparison != 0) return comparison;
            comparison = Integer.compare(qualityScore, other.qualityScore);
            if (comparison != 0) return comparison;
            return Integer.compare(citationPercentage, other.citationPercentage);
        }
    }
}
