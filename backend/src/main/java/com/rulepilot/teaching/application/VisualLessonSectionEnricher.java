package com.rulepilot.teaching.application;

import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Enriches one cited lesson section with distinct, exact-step visual references.
 *
 * <p>It owns only bounded section-local image work. Section selection, broad-crop cleanup, outcome presentation,
 * and progressive lesson publication remain in {@link VisualLessonEnricher}.</p>
 */
final class VisualLessonSectionEnricher {

    private final VisualReaderCropPolicy cropPolicy;
    private final VisualLessonMergePolicy mergePolicy;
    private final VisualLessonStepLocator stepLocator;
    private final int maxVisualStepsPerSection;
    private final int requestParallelism;

    VisualLessonSectionEnricher(
            VisualReaderCropPolicy cropPolicy,
            VisualLessonMergePolicy mergePolicy,
            VisualLessonStepLocator stepLocator,
            int maxVisualStepsPerSection,
            int requestParallelism) {
        this.cropPolicy = cropPolicy;
        this.mergePolicy = mergePolicy;
        this.stepLocator = stepLocator;
        this.maxVisualStepsPerSection = maxVisualStepsPerSection;
        this.requestParallelism = requestParallelism;
    }

    boolean supportsVisualEvidence(String modelConfigurationOwner) {
        return stepLocator.supportsVisualEvidence(modelConfigurationOwner);
    }

    Result enrich(
            RulebookUnderstanding understanding,
            UUID documentVersionId,
            LessonSection section,
            String modelConfigurationOwner,
            VisualLessonEnricher.VisualProgressListener progress,
            List<VisualFocus> acceptedVisuals,
            java.util.Set<Integer> explicitVisualStepPositions) {
        return enrich(
                understanding,
                documentVersionId,
                section,
                modelConfigurationOwner,
                null,
                progress,
                acceptedVisuals,
                explicitVisualStepPositions);
    }

    Result enrich(
            RulebookUnderstanding understanding,
            UUID documentVersionId,
            LessonSection section,
            String modelConfigurationOwner,
            UUID runId,
            VisualLessonEnricher.VisualProgressListener progress,
            List<VisualFocus> acceptedVisuals,
            java.util.Set<Integer> explicitVisualStepPositions) {
        if (explicitVisualStepPositions == null) {
            throw new IllegalArgumentException("explicit visual step positions are required");
        }
        int existingVisualSteps = (int) section.steps().stream()
                .filter(step -> step.kind() == TeachingMove.VISUAL)
                .filter(step -> step.visualFocus() != null)
                .filter(step -> !cropPolicy.needsTighterReaderCrop(step.visualFocus()))
                .count();
        if (existingVisualSteps >= maxVisualStepsPerSection) {
            return Result.rejected(section, VisualLessonEnricher.Outcome.ALREADY_PRESENT);
        }
        List<VisualRegionLocator.LocatedRegion> accepted = new ArrayList<>();
        VisualLessonEnricher.Outcome rejected = null;
        int availableStepSlots = (int) section.steps().stream()
                .filter(step -> step.kind() != TeachingMove.VISUAL
                        || step.visualFocus() == null
                        || cropPolicy.needsTighterReaderCrop(step.visualFocus()))
                .count();
        int limit = Math.min(maxVisualStepsPerSection - existingVisualSteps, availableStepSlots);
        List<LessonStep> targets = visualTargets(section, limit, explicitVisualStepPositions);
        if (targets.isEmpty()) return Result.rejected(section, VisualLessonEnricher.Outcome.NO_CITED_CANDIDATE);
        try (var executor = Executors.newFixedThreadPool(Math.min(requestParallelism, targets.size()))) {
            List<Future<VisualLessonStepLocator.Result>> attempts = targets.stream()
                    .map(step -> executor.submit(() -> locateWithProgress(
                            understanding, documentVersionId, section, step, modelConfigurationOwner, runId, progress)))
                    .toList();
            for (Future<VisualLessonStepLocator.Result> attempt : attempts) {
                VisualLessonStepLocator.Result location = awaitLocation(attempt);
                if (location.region() != null) accepted.add(location.region());
                else if (location.rejection() != null) rejected = location.rejection();
            }
        }
        if (accepted.isEmpty()) {
            return Result.rejected(
                    section,
                    rejected == null ? VisualLessonEnricher.Outcome.LOCATOR_RETURNED_NONE : rejected);
        }
        VisualLessonMergePolicy.MergedVisualSection merged = mergePolicy.mergeVisualIntoSupportedSteps(section, accepted);
        if (merged.addedCount() == 0) {
            return Result.rejected(section, VisualLessonEnricher.Outcome.REJECTED_UNKNOWN_EVIDENCE);
        }
        VisualLessonMergePolicy.DistinctVisualSection distinct =
                mergePolicy.keepDistinctVisuals(section, merged.section(), acceptedVisuals);
        if (!distinct.hadDuplicate()) {
            return Result.added(merged.section(), merged.addedCount(), merged.claimConflictCount());
        }
        if (distinct.addedCount() == 0) {
            return Result.rejected(section, VisualLessonEnricher.Outcome.REJECTED_DUPLICATE);
        }
        return Result.added(distinct.section(), distinct.addedCount(), merged.claimConflictCount());
    }

    private VisualLessonStepLocator.Result locateWithProgress(
            RulebookUnderstanding understanding,
            UUID documentVersionId,
            LessonSection section,
            LessonStep step,
            String modelConfigurationOwner,
            UUID runId,
            VisualLessonEnricher.VisualProgressListener progress) {
        VisualLessonEnricher.VisualTarget target = new VisualLessonEnricher.VisualTarget(
                section.position(), section.title(), step.position(), step.heading());
        progress.targetStarted(target);
        VisualLessonStepLocator.Result location = stepLocator.locate(
                understanding, documentVersionId, section, step, modelConfigurationOwner, runId);
        progress.targetFinished(target, location.region() == null
                ? location.rejection() == null
                        ? VisualLessonEnricher.Outcome.LOCATOR_RETURNED_NONE
                        : location.rejection()
                : VisualLessonEnricher.Outcome.ADDED);
        return location;
    }

    private VisualLessonStepLocator.Result awaitLocation(Future<VisualLessonStepLocator.Result> attempt) {
        try {
            return attempt.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("visual lesson enrichment was interrupted", interrupted);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("visual lesson enrichment failed", failed.getCause());
        }
    }

    private List<LessonStep> visualTargets(
            LessonSection section, int limit, java.util.Set<Integer> explicitVisualStepPositions) {
        List<LessonStep> eligible = section.steps().stream()
                .filter(step -> step.kind() != TeachingMove.VISUAL
                        || step.visualFocus() == null
                        || cropPolicy.needsTighterReaderCrop(step.visualFocus()))
                .filter(step -> !step.sourcePages().isEmpty() && !step.sourceChunkIds().isEmpty())
                .toList();
        boolean hasExplicitVisualIntent = !explicitVisualStepPositions.isEmpty()
                || eligible.stream().anyMatch(step -> step.kind() == TeachingMove.VISUAL);
        return eligible.stream()
                .filter(step -> !hasExplicitVisualIntent
                        || explicitVisualStepPositions.contains(step.position())
                        || step.kind() == TeachingMove.VISUAL)
                .sorted(java.util.Comparator.comparingInt(this::visualAffinity).reversed()
                        .thenComparingInt(LessonStep::position))
                .limit(limit)
                .toList();
    }

    private int visualAffinity(LessonStep step) {
        String target = (step.heading() + " " + step.text()).toLowerCase(java.util.Locale.ROOT);
        int score = 0;
        for (String cue : List.of(
                "图标", "符号", "卡牌", "卡片", "玩家板", "棋盘", "网格", "地图", "轨道", "骰子", "资源",
                "令牌", "标记", "方块", "建筑", "放置", "建造", "布局", "计分", "分数", "示例", "组件",
                "icon", "symbol", "card", "board", "grid", "map", "track", "dice", "resource", "token",
                "marker", "building", "score", "example", "component")) {
            if (target.contains(cue)) score++;
        }
        return score;
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
