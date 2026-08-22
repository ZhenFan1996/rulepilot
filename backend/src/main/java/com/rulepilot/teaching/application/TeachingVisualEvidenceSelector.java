package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RulePageImage;
import com.rulepilot.teaching.TeachingLessonModel.PageImageInput;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Deterministically selects a small, topic-relevant set of rulebook images for one teaching section.
 *
 * <p>This selector only consumes already retrieved evidence. It cannot read pages, inspect an image, or invoke a
 * teaching model.</p>
 */
final class TeachingVisualEvidenceSelector {

    private static final int MAX_PAGE_IMAGES = 2;

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
                .limit(MAX_PAGE_IMAGES)
                .map(images::get)
                .map(image -> new PageImageInput(
                        image.pageNumber(), image.mediaType(), image.content(), image.width(), image.height()))
                .toList();
    }

}
