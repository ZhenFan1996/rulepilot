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
                            originalRuleText(step),
                            step.sourcePages(),
                            step.sourceChunkIds()));
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
                original.evidenceStatus(),
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
                        || cropPolicy.needsTighterReaderCrop(steps.get(index).visualFocus()))
                .boxed()
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Integer> sourcePages = section.visualSourcePages();
        List<UUID> sourceChunkIds = section.visualSourceChunkIds();
        int added = 0;
        for (VisualRegionLocator.LocatedRegion region : regions) {
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
            String observation = stripTrailingPunctuation(region.visibleDescription());
            String visualText = visualText(observation, originalRuleText(supportedStep), cropPolicy.isIconFocused(region));
            String label = containsHan(region.label()) ? region.label().strip() : supportedStep.heading();
            steps.set(supportedStepIndex.get(), new LessonStep(
                    supportedStep.position(),
                    supportedStep.heading(),
                    TeachingMove.VISUAL,
                    visualText,
                    distinct(supportedStep.sourcePages(), region.pageNumber()),
                    distinct(supportedStep.sourceChunkIds(), region.supportedEvidenceIds()),
                    new VisualFocus(region.pageNumber(), label, region.x(), region.y(), region.width(), region.height())));
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
        return new MergedVisualSection(enriched, added);
    }

    private String visualText(String observation, String ruleText, boolean iconCluster) {
        String prefix = iconCluster
                ? "图中图标提示：" + observation + "。先认出这组图标，再按规则处理："
                : (startsWithImageIntroducer(observation) ? observation : "图中可见" + observation) + "。结合图片完成这一步：";
        String combined = prefix + ruleText;
        return combined.length() <= 600 ? combined : ruleText;
    }

    private boolean startsWithImageIntroducer(String observation) {
        String normalized = observation == null ? "" : observation.strip();
        return normalized.startsWith("图中") || normalized.startsWith("这张图") || normalized.startsWith("此图");
    }

    private String originalRuleText(LessonStep step) {
        if (step.kind() != TeachingMove.VISUAL) return step.text();
        for (String connector : List.of("。结合图片完成这一步：", "。先认出这组图标，再按规则处理：")) {
            int boundary = step.text().indexOf(connector);
            if (boundary >= 0) {
                String text = step.text().substring(boundary + connector.length()).strip();
                if (!text.isBlank()) return text;
            }
        }
        return step.text();
    }

    private boolean containsHan(String text) {
        return text != null && text.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                == Character.UnicodeScript.HAN);
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

    private String stripTrailingPunctuation(String text) {
        return text == null ? "" : text.strip().replaceFirst("[。.!！?？]+$", "");
    }

    record MergedVisualSection(LessonSection section, int addedCount) {}

    record DistinctVisualSection(LessonSection section, int addedCount, boolean hadDuplicate) {}
}
