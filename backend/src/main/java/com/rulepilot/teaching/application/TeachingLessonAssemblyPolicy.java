package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingLessonModel.PriorSectionContext;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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
        if (sections.size() < plan.sections().size()) return LessonStatus.INCOMPLETE;
        List<LessonSection> required = sections.stream().filter(LessonSection::required).toList();
        if (required.stream().anyMatch(section -> section.evidenceStatus() == EvidenceStatus.INSUFFICIENT_EVIDENCE)) {
            return LessonStatus.INCOMPLETE;
        }
        return required.stream().allMatch(section -> section.evidenceStatus() == EvidenceStatus.SUPPORTED)
                ? LessonStatus.COMPLETE
                : LessonStatus.DRAFT_READY;
    }

    Map<String, LessonSection> reusableSections(
            TeachingPlan plan,
            IllustratedLesson previousLesson,
            Set<String> reusableGeneratorVersions,
            boolean supportsVisualEvidence) {
        if (previousLesson == null
                || !plan.id().equals(previousLesson.teachingPlanId())
                || !reusableGeneratorVersions.contains(previousLesson.generatorVersion())) {
            return Map.of();
        }
        Set<String> currentTopics = plan.sections().stream()
                .map(TeachingPlan.PlannedSection::topicKey)
                .collect(Collectors.toUnmodifiableSet());
        Map<String, Boolean> visualRequirements = plan.sections().stream()
                .collect(Collectors.toUnmodifiableMap(
                        TeachingPlan.PlannedSection::topicKey,
                        TeachingPlan.PlannedSection::visualEvidenceRecommended));
        return previousLesson.sections().stream()
                .filter(section -> section.evidenceStatus() == EvidenceStatus.SUPPORTED)
                .filter(section -> currentTopics.contains(section.topicKey()))
                .filter(section -> !supportsVisualEvidence
                        || !visualRequirements.getOrDefault(section.topicKey(), false)
                        || section.steps().stream().anyMatch(step -> step.kind() == TeachingMove.VISUAL
                                && step.visualFocus() != null))
                .collect(Collectors.toUnmodifiableMap(LessonSection::topicKey, Function.identity()));
    }

    List<PriorSectionContext> continuityContext(List<LessonSection> sections) {
        List<LessonSection> supported = sections.stream()
                .filter(section -> section.evidenceStatus() != EvidenceStatus.INSUFFICIENT_EVIDENCE)
                .toList();
        int fromIndex = Math.max(0, supported.size() - 2);
        return supported.subList(fromIndex, supported.size()).stream()
                .map(section -> new PriorSectionContext(
                        section.topicKey(), section.title(), section.steps().getLast().text()))
                .toList();
    }

    LessonSection insufficient(TeachingPlan.PlannedSection planned) {
        return new LessonSection(
                planned.position(),
                planned.topicKey(),
                planned.coverageTags(),
                planned.title(),
                planned.required(),
                EvidenceStatus.INSUFFICIENT_EVIDENCE,
                VisualKind.REFERENCE_CARD,
                "本节等待可验证的规则证据",
                List.of(new LessonStep(
                        1,
                        "暂时跳过",
                        TeachingMove.WATCH,
                        "规则资料中尚未找到这一节所需的可靠证据。",
                        List.of(),
                        List.of())));
    }
}
