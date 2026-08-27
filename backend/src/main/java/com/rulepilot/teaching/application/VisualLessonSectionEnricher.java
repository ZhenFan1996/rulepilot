package com.rulepilot.teaching.application;

import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Enriches one cited lesson section with distinct, exact-step visual references.
 *
 * <p>It owns only bounded section-local image work. Section selection, outcome presentation,
 * and incremental lesson publication remain in {@link VisualLessonEnricher}.</p>
 */
final class VisualLessonSectionEnricher {

    private final VisualLessonMergePolicy mergePolicy;
    private final VisualLessonStepLocator stepLocator;

    VisualLessonSectionEnricher(
            VisualLessonMergePolicy mergePolicy,
            VisualLessonStepLocator stepLocator) {
        this.mergePolicy = mergePolicy;
        this.stepLocator = stepLocator;
    }

    boolean supportsVisualEvidence(String modelConfigurationOwner) {
        return stepLocator.supportsVisualEvidence(modelConfigurationOwner);
    }

    VisualLessonStepLocator.ProposalToolCircuit beginProposalWorkflow() {
        return stepLocator.beginProposalWorkflow();
    }

    Result enrich(
            RulebookUnderstanding understanding,
            UUID documentVersionId,
            LessonSection section,
            String modelConfigurationOwner,
            UUID runId,
            Instant compatibilityDeadline,
            VisualLessonStepLocator.ProposalToolCircuit proposalToolCircuit,
            VisualLessonEnricher.VisualProgressListener progress,
            List<VisualFocus> acceptedVisuals,
            java.util.Set<Integer> explicitVisualStepPositions) {
        if (explicitVisualStepPositions == null) {
            throw new IllegalArgumentException("explicit visual step positions are required");
        }
        List<LessonStep> targets = visualTargets(section, explicitVisualStepPositions);
        if (targets.isEmpty()) return Result.rejected(section, VisualLessonEnricher.Outcome.NO_CITED_CANDIDATE);
        targets.forEach(step -> progress.targetStarted(target(section, step)));
        VisualLessonStepLocator.Result location = stepLocator.locate(
                understanding,
                documentVersionId,
                section,
                targets,
                modelConfigurationOwner,
                runId,
                compatibilityDeadline,
                proposalToolCircuit);
        for (LessonStep step : targets) {
            boolean acceptedForStep = location.regions().stream().anyMatch(region ->
                    region.supportedStepPositions().isEmpty()
                            || region.supportedStepPositions().contains(step.position()));
            progress.targetFinished(
                    target(section, step),
                    acceptedForStep
                            ? VisualLessonEnricher.Outcome.ADDED
                            : location.rejection() == null
                                    ? VisualLessonEnricher.Outcome.LOCATOR_RETURNED_NONE
                                    : location.rejection());
        }
        if (location.regions().isEmpty()) {
            return Result.rejected(section, location.rejection() == null
                    ? VisualLessonEnricher.Outcome.LOCATOR_RETURNED_NONE
                    : location.rejection());
        }
        VisualLessonMergePolicy.MergedVisualSection merged = mergePolicy.mergeVisualIntoSupportedSteps(
                section, location.regions(), acceptedVisuals);
        if (merged.addedCount() == 0) {
            return Result.rejected(
                    section,
                    merged.claimConflictCount() > 0
                            ? VisualLessonEnricher.Outcome.REJECTED_CLAIM_CONFLICT
                            : merged.duplicateCount() > 0
                                    ? VisualLessonEnricher.Outcome.REJECTED_DUPLICATE
                            : VisualLessonEnricher.Outcome.REJECTED_UNKNOWN_EVIDENCE);
        }
        return Result.added(merged.section(), merged.addedCount(), merged.claimConflictCount());
    }

    private VisualLessonEnricher.VisualTarget target(LessonSection section, LessonStep step) {
        return new VisualLessonEnricher.VisualTarget(
                section.position(), section.title(), step.position(), step.heading());
    }

    private List<LessonStep> visualTargets(
            LessonSection section, java.util.Set<Integer> explicitVisualStepPositions) {
        List<LessonStep> eligible = section.steps().stream()
                .filter(step -> !step.sourcePages().isEmpty() && !step.sourceChunkIds().isEmpty())
                .toList();
        boolean hasExplicitVisualIntent = !explicitVisualStepPositions.isEmpty()
                || eligible.stream().anyMatch(step -> step.kind() == TeachingMove.VISUAL);
        return eligible.stream()
                .filter(step -> !hasExplicitVisualIntent
                        || explicitVisualStepPositions.contains(step.position())
                        || step.kind() == TeachingMove.VISUAL)
                .sorted(java.util.Comparator.comparingInt(LessonStep::position))
                .toList();
    }

    record Result(LessonSection section, VisualLessonEnricher.Outcome outcome, int addedCount) {

        static Result added(LessonSection section, int addedCount, int claimConflictCount) {
            return new Result(
                    section,
                    claimConflictCount > 0
                            ? VisualLessonEnricher.Outcome.ADDED_WITH_CLAIM_CONFLICT
                            : VisualLessonEnricher.Outcome.ADDED,
                    addedCount);
        }

        static Result rejected(LessonSection section, VisualLessonEnricher.Outcome outcome) {
            return new Result(section, outcome, 0);
        }
    }
}
