package com.rulepilot.teaching.application;

import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Pure reader-lesson mutations after a visual region has passed evidence and relevance checks. */
final class VisualLessonMergePolicy {

    private final VisualReaderCropPolicy cropPolicy;

    VisualLessonMergePolicy(VisualReaderCropPolicy cropPolicy) {
        this.cropPolicy = cropPolicy;
    }

    /** Restore cited prose when a prior reader crop is too broad to be useful. */
    IllustratedLesson discardOverlyBroadVisuals(IllustratedLesson lesson) {
        boolean changed = false;
        List<LessonSection> sections = new ArrayList<>();
        for (LessonSection section : lesson.sections()) {
            List<LessonStep> steps = new ArrayList<>();
            for (LessonStep step : section.steps()) {
                if (step.kind() == TeachingMove.VISUAL && cropPolicy.needsTighterReaderCrop(step.visualFocus())) {
                    steps.add(new LessonStep(
                            step.position(),
                            step.heading(),
                            TeachingMove.DO,
                            step.text(),
                            step.sourcePages(),
                            step.sourceChunkIds(),
                            step.ruleFacts(),
                            null));
                    changed = true;
                } else {
                    steps.add(step);
                }
            }
            Set<Integer> retainedVisualPages = steps.stream()
                    .filter(step -> step.kind() == TeachingMove.VISUAL && step.visualFocus() != null)
                    .map(step -> step.visualFocus().pageNumber())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<UUID> retainedVisualChunks = steps.stream()
                    .filter(step -> step.kind() == TeachingMove.VISUAL)
                    .flatMap(step -> step.sourceChunkIds().stream())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            sections.add(new LessonSection(
                    section.position(),
                    section.topicKey(),
                    section.coverageTags(),
                    section.title(),
                    section.required(),
                    section.evidenceStatus(),
                    section.visualKind(),
                    section.visualCaption(),
                    section.visualSourcePages().stream().filter(retainedVisualPages::contains).toList(),
                    section.visualSourceChunkIds().stream().filter(retainedVisualChunks::contains).toList(),
                    steps));
        }
        if (!changed) return lesson;
        return new IllustratedLesson(
                lesson.id(),
                lesson.teachingPlanId(),
                lesson.status(),
                sections,
                lesson.generatorVersion(),
                lesson.createdAt());
    }

    /**
     * Keep the first grounded use of a substantially identical viewport, restoring later steps to their cited prose.
     */
    DistinctVisualSection keepDistinctVisuals(
            LessonSection original, LessonSection candidate, List<VisualFocus> acceptedVisuals) {
        Map<Integer, LessonStep> originalSteps = original.steps().stream()
                .collect(Collectors.toMap(LessonStep::position, step -> step));
        List<LessonStep> filtered = new ArrayList<>();
        int added = 0;
        boolean duplicate = false;
        for (LessonStep step : candidate.steps()) {
            LessonStep originalStep = originalSteps.get(step.position());
            boolean newlyVisual = originalStep != null
                    && (originalStep.kind() != TeachingMove.VISUAL
                            || originalStep.visualFocus() == null
                            || cropPolicy.needsTighterReaderCrop(originalStep.visualFocus()))
                    && step.kind() == TeachingMove.VISUAL
                    && step.visualFocus() != null;
            if (newlyVisual && acceptedVisuals.stream().anyMatch(existing -> cropPolicy.overlapsSubstantially(
                    existing, step.visualFocus()))) {
                filtered.add(originalStep);
                duplicate = true;
                continue;
            }
            filtered.add(step);
            if (newlyVisual) {
                acceptedVisuals.add(step.visualFocus());
                added++;
            }
        }
        if (!duplicate) return new DistinctVisualSection(candidate, added, false);
        if (added == 0) return new DistinctVisualSection(original, 0, true);
        List<Integer> visualPages = original.visualSourcePages();
        List<UUID> visualChunks = original.visualSourceChunkIds();
        for (LessonStep step : filtered) {
            if (step.kind() == TeachingMove.VISUAL && step.visualFocus() != null) {
                visualPages = distinct(visualPages, step.visualFocus().pageNumber());
                visualChunks = distinct(visualChunks, step.sourceChunkIds());
            }
        }
        LessonSection distinct = new LessonSection(
                original.position(),
                original.topicKey(),
                original.coverageTags(),
                original.title(),
                original.required(),
                candidate.evidenceStatus(),
                original.visualKind(),
                original.visualCaption(),
                visualPages,
                visualChunks,
                filtered);
        return new DistinctVisualSection(distinct, added, true);
    }

    MergedVisualSection mergeVisualIntoSupportedSteps(
            LessonSection section, List<VisualRegionLocator.LocatedRegion> regions) {
        List<LessonStep> steps = new ArrayList<>(section.steps());
        Set<Integer> availableIndexes = java.util.stream.IntStream.range(0, steps.size())
                .filter(index -> steps.get(index).kind() != TeachingMove.VISUAL
                        || steps.get(index).visualFocus() == null
                        || cropPolicy.needsTighterReaderCrop(steps.get(index).visualFocus()))
                .boxed()
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Integer> sourcePages = section.visualSourcePages();
        List<UUID> sourceChunkIds = section.visualSourceChunkIds();
        int added = 0;
        int claimConflicts = 0;
        for (VisualRegionLocator.LocatedRegion region : regions) {
            // A visual observation is optional enrichment. If it conflicts with the already validated cited prose,
            // reject that observation locally instead of attaching it and downgrading the whole section.
            if (region.claimContradicted()) {
                claimConflicts++;
                continue;
            }
            if (availableIndexes.isEmpty()) break;
            Set<UUID> supportedEvidence = Set.copyOf(region.supportedEvidenceIds());
            java.util.Optional<Integer> supportedStepIndex = availableIndexes.stream()
                    .filter(index -> region.supportedStepPositions().isEmpty()
                            || region.supportedStepPositions().contains(steps.get(index).position()))
                    .filter(index -> steps.get(index).sourcePages().contains(region.pageNumber()))
                    .filter(index -> steps.get(index).sourceChunkIds().stream().anyMatch(supportedEvidence::contains))
                    .findFirst();
            if (supportedStepIndex.isEmpty()) continue;
            LessonStep supportedStep = steps.get(supportedStepIndex.get());
            steps.set(supportedStepIndex.get(), new LessonStep(
                    supportedStep.position(),
                    supportedStep.heading(),
                    TeachingMove.VISUAL,
                    supportedStep.text(),
                    distinct(supportedStep.sourcePages(), region.pageNumber()),
                    distinct(supportedStep.sourceChunkIds(), region.supportedEvidenceIds()),
                    supportedStep.ruleFacts(),
                    new VisualFocus(
                            region.pageNumber(),
                            region.label(),
                            region.visibleDescription(),
                            region.x(),
                            region.y(),
                            region.width(),
                            region.height())));
            sourcePages = distinct(sourcePages, region.pageNumber());
            sourceChunkIds = distinct(sourceChunkIds, region.supportedEvidenceIds());
            availableIndexes.remove(supportedStepIndex.get());
            added++;
        }
        LessonSection enriched = new LessonSection(
                section.position(),
                section.topicKey(),
                section.coverageTags(),
                section.title(),
                section.required(),
                section.evidenceStatus(),
                section.visualKind(),
                section.visualCaption(),
                sourcePages,
                sourceChunkIds,
                steps);
        return new MergedVisualSection(enriched, added, claimConflicts);
    }

    private <T> List<T> distinct(List<T> existing, T addition) {
        LinkedHashSet<T> values = new LinkedHashSet<>(existing);
        values.add(addition);
        return List.copyOf(values);
    }

    private <T> List<T> distinct(List<T> existing, List<T> additions) {
        LinkedHashSet<T> values = new LinkedHashSet<>(existing);
        values.addAll(additions);
        return List.copyOf(values);
    }

    record MergedVisualSection(LessonSection section, int addedCount, int claimConflictCount) {}

    record DistinctVisualSection(LessonSection section, int addedCount, boolean hadDuplicate) {}
}
