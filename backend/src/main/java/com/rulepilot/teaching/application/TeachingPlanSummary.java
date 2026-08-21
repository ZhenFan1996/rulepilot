package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.IllustratedLesson;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The small, player-facing projection used by account and work-status lists. */
public record TeachingPlanSummary(
        UUID id,
        UUID documentVersionId,
        String gameTitle,
        String premise,
        List<SectionSummary> sections,
        LessonProgress lesson,
        String createdBy,
        Instant createdAt) {

    public TeachingPlanSummary {
        if (id == null || documentVersionId == null || createdAt == null
                || gameTitle == null || gameTitle.isBlank()
                || premise == null || premise.isBlank()
                || createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("teaching plan summary is invalid");
        }
        sections = List.copyOf(sections);
    }

    public static TeachingPlanSummary from(TeachingPlan plan) {
        return new TeachingPlanSummary(
                plan.id(),
                plan.documentVersionId(),
                plan.gameTitle(),
                plan.premise(),
                plan.sections().stream().map(SectionSummary::from).toList(),
                null,
                plan.createdBy(),
                plan.createdAt());
    }

    TeachingPlanSummary withLesson(IllustratedLessonRepository.ProgressSummary progress) {
        if (progress == null) return this;
        return new TeachingPlanSummary(
                id, documentVersionId, gameTitle, premise, sections,
                LessonProgress.from(progress),
                createdBy, createdAt);
    }

    public record SectionSummary(
            int position,
            String topicKey,
            String title,
            boolean required,
            boolean visualEvidenceRecommended) {

        static SectionSummary from(TeachingPlan.PlannedSection section) {
            return new SectionSummary(
                    section.position(),
                    section.topicKey(),
                    section.title(),
                    section.required(),
                    section.visualEvidenceRecommended());
        }
    }

    public record LessonProgress(
            UUID id,
            UUID teachingPlanId,
            IllustratedLesson.LessonStatus status,
            List<SectionProgress> sections) {

        public static LessonProgress from(IllustratedLessonRepository.ProgressSummary progress) {
            return new LessonProgress(
                    progress.id(),
                    progress.teachingPlanId(),
                    progress.status(),
                    progress.evidenceStatuses().stream().map(SectionProgress::new).toList());
        }
    }

    public record SectionProgress(IllustratedLesson.EvidenceStatus evidenceStatus) {}
}
