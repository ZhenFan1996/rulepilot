package com.rulepilot.teaching.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Structural source-page and visual-budget policy for teaching outlines. */
final class VisualOutlineEvidencePolicy {

    private VisualOutlineEvidencePolicy() {}

    static TeachingOutlineModel.OutlineDraft bindIconLegendEvidence(
            TeachingOutlineModel.OutlineDraft outline, List<DocumentProcessing.PageView> pages) {
        return outline;
    }

    static void validateVisualSourceDependencies(
            TeachingOutlineModel.OutlineDraft outline, List<PageInput> visualCatalogPages) {
        java.util.Map<Integer, List<com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency>> expected =
                visualCatalogPages.stream()
                        .filter(page -> !page.sourceDependencies().isEmpty())
                        .collect(Collectors.toUnmodifiableMap(
                                PageInput::pageNumber,
                                PageInput::sourceDependencies,
                                (first, ignored) -> first));
        List<TeachingOutlineModel.TopicDraft> dependencyTopics = outline.topics().stream()
                .filter(topic -> topic.coverageTags().stream()
                        .map(VisualOutlineEvidencePolicy::identityKey)
                        .anyMatch("source_dependency"::equals))
                .toList();

        for (TeachingOutlineModel.TopicDraft topic : dependencyTopics) {
            List<com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency> allowed =
                    topic.sourcePageNumbers().stream()
                            .flatMap(page -> expected.getOrDefault(page, List.of()).stream())
                            .toList();
            if (allowed.isEmpty()) {
                throw new IllegalArgumentException(
                        "visual teaching outline invented an external source dependency");
            }
            Set<String> allowedTitles = allowed.stream()
                    .map(dependency -> sourceTitleKey(dependency.title()))
                    .collect(Collectors.toUnmodifiableSet());
            if (topic.retrievalQueries().stream()
                    .map(VisualOutlineEvidencePolicy::sourceTitleKey)
                    .anyMatch(query -> !allowedTitles.contains(query))) {
                throw new IllegalArgumentException(
                        "visual teaching outline invented an external source title");
            }
            Set<String> allowedMissingMarkers = allowed.stream()
                    .flatMap(dependency -> dependency.missingCoverageTags().stream())
                    .map(tag -> "missing_" + tag + "_source")
                    .collect(Collectors.toUnmodifiableSet());
            Set<String> actualMissingMarkers = topic.coverageTags().stream()
                    .map(VisualOutlineEvidencePolicy::identityKey)
                    .filter(tag -> tag.startsWith("missing_") && tag.endsWith("_source"))
                    .collect(Collectors.toUnmodifiableSet());
            if (!allowedMissingMarkers.containsAll(actualMissingMarkers)) {
                throw new IllegalArgumentException(
                        "visual teaching outline invented a missing-source responsibility");
            }
        }

        expected.forEach((pageNumber, dependencies) -> dependencies.forEach(dependency -> {
            String requiredTitle = sourceTitleKey(dependency.title());
            Set<String> requiredMarkers = dependency.missingCoverageTags().stream()
                    .map(tag -> "missing_" + tag + "_source")
                    .collect(Collectors.toUnmodifiableSet());
            boolean preserved = dependencyTopics.stream()
                    .filter(topic -> topic.sourcePageNumbers().contains(pageNumber))
                    .filter(topic -> topic.retrievalQueries().stream()
                            .map(VisualOutlineEvidencePolicy::sourceTitleKey)
                            .anyMatch(requiredTitle::equals))
                    .map(topic -> topic.coverageTags().stream()
                            .map(VisualOutlineEvidencePolicy::identityKey)
                            .collect(Collectors.toUnmodifiableSet()))
                    .anyMatch(tags -> tags.containsAll(requiredMarkers));
            if (!preserved) {
                throw new IllegalArgumentException(
                        "visual teaching outline omitted external source dependency " + dependency.title());
            }
        }));
    }

    private static String identityKey(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .strip()
                .toLowerCase(Locale.ROOT);
    }

    private static String sourceTitleKey(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ");
    }
}
