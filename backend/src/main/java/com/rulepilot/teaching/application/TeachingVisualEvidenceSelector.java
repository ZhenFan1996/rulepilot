package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RulePageImage;
import com.rulepilot.teaching.TeachingLessonModel.PageImageInput;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Deterministically selects a small, topic-relevant set of rulebook images for one teaching section.
 *
 * <p>This selector only consumes already retrieved evidence. It cannot read pages, inspect an image, or invoke a
 * teaching model.</p>
 */
final class TeachingVisualEvidenceSelector {

    private static final int ABSOLUTE_PAGE_IMAGE_BUDGET = 12;

    private TeachingVisualEvidenceSelector() {}

    static boolean hasVisualPageEvidence(List<RuleEvidence> evidence) {
        return evidence.stream().anyMatch(TeachingVisualEvidenceSelector::isVisualPageEvidence);
    }

    static boolean isVisualPageEvidence(RuleEvidence evidence) {
        return evidence.contentKind() == RuleEvidence.ContentKind.VISUAL_PLACEHOLDER;
    }

    static List<PageImageInput> select(
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> evidence,
            boolean modelSupportsVisualEvidence) {
        return select(planned, evidence, modelSupportsVisualEvidence, visualBudget(planned, evidence));
    }

    static List<PageImageInput> select(
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> evidence,
            boolean modelSupportsVisualEvidence,
            int visualBudget) {
        if (visualBudget < 1 || visualBudget > ABSOLUTE_PAGE_IMAGE_BUDGET) {
            throw new IllegalArgumentException("teaching visual input budget is invalid");
        }
        boolean imageOnlyEvidence = hasVisualPageEvidence(evidence);
        if ((!planned.visualEvidenceRecommended() && !imageOnlyEvidence) || !modelSupportsVisualEvidence) {
            return List.of();
        }
        Map<Integer, RulePageImage> images = new LinkedHashMap<>();
        Map<Integer, Integer> scores = new LinkedHashMap<>();
        Map<Integer, Integer> firstEvidenceRank = new LinkedHashMap<>();
        IntStream.range(0, evidence.size()).forEach(index -> {
            RuleEvidence source = evidence.get(index);
            boolean plannedPage = planned.sourcePageNumbers().stream()
                    .anyMatch(page -> page >= source.pageFrom() && page <= source.pageTo());
            int sourceScore = 100 + (plannedPage ? 40 : 0) + (source.pageFrom() == source.pageTo() ? 20 : 0);
            source.pageImages().stream()
                    .filter(image -> image.pageNumber() >= source.pageFrom()
                            && image.pageNumber() <= source.pageTo())
                    .forEach(image -> {
                        images.putIfAbsent(image.pageNumber(), image);
                        scores.merge(image.pageNumber(), sourceScore, Integer::max);
                        firstEvidenceRank.putIfAbsent(image.pageNumber(), index);
                    });
        });
        return images.keySet().stream()
                .sorted(Comparator
                        .<Integer>comparingInt(page -> scores.getOrDefault(page, 0))
                        .reversed()
                        .thenComparingInt(page -> firstEvidenceRank.getOrDefault(page, Integer.MAX_VALUE))
                        .thenComparingInt(Integer::intValue))
                .limit(visualBudget)
                .map(images::get)
                .map(image -> new PageImageInput(
                        image.pageNumber(), image.mediaType(), image.content(), image.width(), image.height()))
                .toList();
    }

    /** Derives the request budget from typed source ownership instead of a fixed image count. */
    static int visualBudget(TeachingPlan.PlannedSection planned, List<RuleEvidence> evidence) {
        if (planned == null || evidence == null) {
            throw new IllegalArgumentException("teaching visual input is required");
        }
        Set<Integer> plannedSourcePages = Set.copyOf(planned.sourcePageNumbers());
        long ownedImagePages = evidence.stream()
                .flatMap(source -> source.pageImages().stream())
                .filter(image -> plannedSourcePages.isEmpty() || plannedSourcePages.contains(image.pageNumber()))
                .map(RulePageImage::pageNumber)
                .distinct()
                .count();
        long plannedPages = plannedSourcePages.size();
        long boundedDemand = Math.max(1, Math.max(plannedPages, ownedImagePages));
        return (int) Math.min(ABSOLUTE_PAGE_IMAGE_BUDGET, boundedDemand);
    }

}
