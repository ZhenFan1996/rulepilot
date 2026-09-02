package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingLessonModel.PriorSectionContext;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Keeps deterministic lesson assembly separate from retrieval and model orchestration. */
final class TeachingLessonAssemblyPolicy {

    IllustratedLesson snapshot(
            UUID lessonId,
            TeachingPlan plan,
            List<LessonSection> sections,
            String generatorVersion,
            Instant createdAt) {
        return new IllustratedLesson(
                lessonId,
                plan.id(),
                status(plan, sections),
                sections,
                generatorVersion,
                createdAt);
    }

    LessonStatus status(TeachingPlan plan, List<LessonSection> sections) {
        boolean hasReadableSection = sections.stream()
                .anyMatch(section -> section.evidenceStatus() != EvidenceStatus.INSUFFICIENT_EVIDENCE);
        if (!hasReadableSection) return LessonStatus.INCOMPLETE;
        boolean everyPublishedSectionSupported = sections.stream()
                .allMatch(section -> section.evidenceStatus() == EvidenceStatus.SUPPORTED);
        boolean agentReportedUnresolved = !plan.wholeGameContext().unresolvedTopics().isEmpty();
        return sections.size() == plan.sections().size()
                        && everyPublishedSectionSupported
                        && !agentReportedUnresolved
                ? LessonStatus.COMPLETE
                : LessonStatus.DRAFT_READY;
    }

    Map<String, LessonSection> reusableSections(
            TeachingPlan plan,
            IllustratedLesson previousLesson,
            Set<String> reusableGeneratorVersions) {
        if (previousLesson == null
                || !plan.id().equals(previousLesson.teachingPlanId())
                || !reusableGeneratorVersions.contains(previousLesson.generatorVersion())) {
            return Map.of();
        }
        Set<String> currentTopics = plan.sections().stream()
                .map(TeachingPlan.PlannedSection::topicKey)
                .collect(Collectors.toUnmodifiableSet());
        return previousLesson.sections().stream()
                .filter(section -> section.evidenceStatus() == EvidenceStatus.SUPPORTED)
                .filter(section -> currentTopics.contains(section.topicKey()))
                .collect(Collectors.toUnmodifiableMap(LessonSection::topicKey, this::primaryVisualOnly));
    }

    private LessonSection primaryVisualOnly(LessonSection section) {
        List<LessonStep> steps = section.steps().stream().map(step -> {
            if (step.visualFoci().size() <= 1) return step;
            var primary = step.visualFoci().getFirst();
            return new LessonStep(
                    step.position(),
                    step.heading(),
                    step.kind(),
                    step.text(),
                    step.sourcePages(),
                    step.sourceChunkIds(),
                    step.ruleFacts(),
                    primary,
                    List.of(primary));
        }).toList();
        return new LessonSection(
                section.position(),
                section.topicKey(),
                section.coverageTags(),
                section.title(),
                section.required(),
                section.evidenceStatus(),
                section.visualKind(),
                section.visualCaption(),
                section.visualSourcePages(),
                section.visualSourceChunkIds(),
                steps);
    }

    List<PriorSectionContext> continuityContext(List<LessonSection> sections) {
        return sections.stream()
                .filter(section -> section.evidenceStatus() != EvidenceStatus.INSUFFICIENT_EVIDENCE)
                .map(section -> new PriorSectionContext(
                        section.topicKey(), section.title(), section.steps().getLast().text()))
                .toList();
    }

}
