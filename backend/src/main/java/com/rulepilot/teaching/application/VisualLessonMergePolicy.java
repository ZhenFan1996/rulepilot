package com.rulepilot.teaching.application;

import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Pure reader-lesson mutations after a visual region has passed evidence and relevance checks. */
final class VisualLessonMergePolicy {

    private final VisualReaderCropPolicy cropPolicy;

    VisualLessonMergePolicy(VisualReaderCropPolicy cropPolicy) {
        this.cropPolicy = cropPolicy;
    }

    MergedVisualSection mergeVisualIntoSupportedSteps(
            LessonSection section,
            List<VisualRegionLocator.LocatedRegion> regions,
            List<VisualFocus> acceptedVisuals) {
        if (acceptedVisuals == null) throw new IllegalArgumentException("accepted visual registry is required");
        List<LessonStep> steps = new ArrayList<>(section.steps());
        List<Integer> sourcePages = section.visualSourcePages();
        List<UUID> sourceChunkIds = section.visualSourceChunkIds();
        int added = 0;
        int claimConflicts = 0;
        int duplicates = 0;
        for (VisualRegionLocator.LocatedRegion region : regions) {
            // A visual observation is optional enrichment. If it conflicts with the already validated cited prose,
            // reject that observation locally instead of attaching it and downgrading the whole section.
            if (region.claimContradicted()) {
                claimConflicts++;
                continue;
            }
            Set<UUID> supportedEvidence = Set.copyOf(region.supportedEvidenceIds());
            java.util.Optional<Integer> supportedStepIndex = java.util.stream.IntStream.range(0, steps.size())
                    .boxed()
                    .filter(index -> region.supportedStepPositions().isEmpty()
                            || region.supportedStepPositions().contains(steps.get(index).position()))
                    .filter(index -> steps.get(index).sourceChunkIds().stream().anyMatch(supportedEvidence::contains))
                    .findFirst();
            if (supportedStepIndex.isEmpty()) continue;
            LessonStep supportedStep = steps.get(supportedStepIndex.get());
            VisualFocus focus = new VisualFocus(
                    region.pageNumber(),
                    region.label(),
                    region.visibleDescription(),
                    region.x(),
                    region.y(),
                    region.width(),
                    region.height(),
                    region.sourceKind());
            if (acceptedVisuals.stream().anyMatch(existing -> cropPolicy.overlapsSubstantially(existing, focus))
                    || supportedStep.visualFoci().stream()
                            .anyMatch(existing -> cropPolicy.overlapsSubstantially(existing, focus))) {
                duplicates++;
                continue;
            }
            List<VisualFocus> stepVisuals = distinct(supportedStep.visualFoci(), focus);
            LessonStep visuallyGrounded = new LessonStep(
                    supportedStep.position(),
                    supportedStep.heading(),
                    TeachingMove.VISUAL,
                    supportedStep.text(),
                    supportedStep.sourcePages(),
                    distinct(supportedStep.sourceChunkIds(), region.supportedEvidenceIds()),
                    supportedStep.ruleFacts(),
                    stepVisuals.getFirst(),
                    stepVisuals);
            steps.set(supportedStepIndex.get(), visuallyGrounded);
            sourcePages = distinct(sourcePages, region.pageNumber());
            sourceChunkIds = distinct(sourceChunkIds, region.supportedEvidenceIds());
            acceptedVisuals.add(focus);
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
        return new MergedVisualSection(enriched, added, claimConflicts, duplicates);
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

    record MergedVisualSection(
            LessonSection section,
            int addedCount,
            int claimConflictCount,
            int duplicateCount) {}
}
