package com.rulepilot.teaching.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.VisualSourceRuleGroupLedger;
import com.rulepilot.teaching.VisualRulebookPageClassifier;
import java.text.Normalizer;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Structural source-page and visual-budget policy for teaching outlines. */
final class VisualOutlineEvidencePolicy {

    static final int MAX_INTERPRETED_VISUAL_PAGES = 4;
    private static final Set<String> CORE_COVERAGE_TAGS = Set.of("setup", "core_loop", "end", "scoring");

    private VisualOutlineEvidencePolicy() {}

    static TeachingOutlineModel.OutlineDraft bindIconLegendEvidence(
            TeachingOutlineModel.OutlineDraft outline, List<DocumentProcessing.PageView> pages) {
        return outline;
    }

    static void validateVisualRulebookCoverage(
            TeachingOutlineModel.OutlineDraft outline, List<PageInput> visualCatalogPages) {
        Set<Integer> expected = visualCatalogPages.stream()
                .filter(page -> VisualRulebookPageClassifier.isSubstantive(page.pageNumber(), page.text()))
                .map(PageInput::pageNumber)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        outline.topics().stream()
                .filter(topic -> topic.coverageTags().stream()
                        .map(VisualOutlineEvidencePolicy::identityKey)
                        .noneMatch("source_dependency"::equals))
                .flatMap(topic -> topic.sourcePageNumbers().stream())
                .forEach(expected::remove);
        if (!expected.isEmpty()) {
            throw new IllegalArgumentException("visual rulebook outline omitted admitted source pages " + expected);
        }
        validateVisualSourceRuleGroups(outline, visualCatalogPages);
    }

    private static void validateVisualSourceRuleGroups(
            TeachingOutlineModel.OutlineDraft outline, List<PageInput> visualCatalogPages) {
        List<TeachingOutlineModel.TopicDraft> ruleTopics = outline.topics().stream()
                .filter(topic -> topic.coverageTags().stream()
                        .map(VisualOutlineEvidencePolicy::identityKey)
                        .noneMatch("source_dependency"::equals))
                .toList();
        for (PageInput page : visualCatalogPages) {
            if (!VisualRulebookPageClassifier.isSubstantive(page.pageNumber(), page.text())) continue;
            for (String identifier : VisualSourceRuleGroupLedger.identifiers(page)) {
                String required = VisualSourceRuleGroupLedger.identity(identifier);
                boolean preserved = ruleTopics.stream()
                        .filter(topic -> topic.sourcePageNumbers().contains(page.pageNumber()))
                        .flatMap(topic -> topic.retrievalQueries().stream())
                        .map(VisualSourceRuleGroupLedger::identity)
                        .anyMatch(required::equals);
                if (!preserved) {
                    throw new IllegalArgumentException(
                            "visual rulebook outline omitted source rule group on page "
                                    + page.pageNumber() + ": " + identifier);
                }
            }
        }
    }

    static void validateVisualCoreTopicBindings(
            TeachingOutlineModel.OutlineDraft outline, List<PageInput> visualCatalogPages) {
        Set<Integer> availablePages = visualCatalogPages.stream()
                .map(PageInput::pageNumber)
                .collect(Collectors.toUnmodifiableSet());
        for (String tag : CORE_COVERAGE_TAGS) {
            boolean bound = outline.topics().stream()
                    .filter(topic -> accountsForCoreObligation(topic, tag))
                    .flatMap(topic -> topic.sourcePageNumbers().stream())
                    .anyMatch(availablePages::contains);
            if (!bound) {
                throw new IllegalArgumentException(
                        "visual rulebook outline must bind core coverage or an explicit missing source for " + tag);
            }
        }
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

    private static boolean accountsForCoreObligation(TeachingOutlineModel.TopicDraft topic, String tag) {
        Set<String> tags = topic.coverageTags().stream()
                .map(VisualOutlineEvidencePolicy::identityKey)
                .collect(Collectors.toUnmodifiableSet());
        return tags.contains(tag)
                || (tags.contains("source_dependency") && tags.contains("missing_" + tag + "_source"));
    }

    static TeachingOutlineModel.OutlineDraft bindVisualCoreTopicEvidence(
            TeachingOutlineModel.OutlineDraft outline, List<PageInput> visualCatalogPages) {
        return outline;
    }

    static void validateVisualFastBaseline(TeachingOutlineModel.OutlineDraft outline) {
        if (exceedsFastBaseline(outline)) {
            throw new IllegalArgumentException(
                    "visual rulebook outline exceeds the ten-section fast baseline and must be compacted");
        }
    }

    static boolean exceedsFastBaseline(TeachingOutlineModel.OutlineDraft outline) {
        long teachingTopics = outline.topics().stream()
                .filter(topic -> topic.coverageTags().stream()
                        .map(VisualOutlineEvidencePolicy::identityKey)
                        .noneMatch("source_dependency"::equals))
                .count();
        return teachingTopics > 10 || outline.topics().size() > 16;
    }

    static Set<Integer> selectedVisualPageNumbers(
            TeachingOutlineModel.OutlineDraft outline, List<DocumentProcessing.PageView> pages) {
        Set<Integer> available = pages.stream()
                .map(DocumentProcessing.PageView::pageNumber)
                .collect(Collectors.toUnmodifiableSet());
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        outline.topics().stream()
                .filter(TeachingOutlineModel.TopicDraft::visualEvidenceRecommended)
                .flatMap(topic -> topic.sourcePageNumbers().stream())
                .filter(available::contains)
                .forEach(page -> addBounded(selected, page));
        if (selected.size() < MAX_INTERPRETED_VISUAL_PAGES) {
            outline.topics().stream()
                    .flatMap(topic -> topic.sourcePageNumbers().stream())
                    .filter(available::contains)
                    .forEach(page -> addBounded(selected, page));
        }
        return Collections.unmodifiableSet(selected);
    }

    static Set<Integer> unownedSparseVisualCoveragePageNumbers(
            TeachingOutlineModel.OutlineDraft outline,
            List<DocumentProcessing.PageView> pages,
            int maximumPages) {
        if (outline == null || pages == null || pages.isEmpty() || maximumPages < 1) return Set.of();
        Set<Integer> owned = outline.topics().stream()
                .flatMap(topic -> topic.sourcePageNumbers().stream())
                .collect(Collectors.toSet());
        List<Integer> candidates = pages.stream()
                .filter(page -> !owned.contains(page.pageNumber()))
                .filter(VisualOutlineEvidencePolicy::hasSparseExtractedText)
                .map(DocumentProcessing.PageView::pageNumber)
                .toList();
        if (candidates.isEmpty()) return Set.of();
        int slots = Math.min(maximumPages, candidates.size());
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        if (slots == 1) {
            selected.add(candidates.get(candidates.size() / 2));
        } else {
            for (int slot = 0; slot < slots; slot++) {
                int index = (int) Math.round((double) slot * (candidates.size() - 1) / (slots - 1));
                selected.add(candidates.get(index));
            }
        }
        return Collections.unmodifiableSet(selected);
    }

    private static boolean hasSparseExtractedText(DocumentProcessing.PageView page) {
        String text = page.text() == null ? "" : page.text();
        return text.codePoints().filter(Character::isLetterOrDigit).limit(281).count() <= 280;
    }

    private static void addBounded(LinkedHashSet<Integer> pages, int pageNumber) {
        if (pages.size() < MAX_INTERPRETED_VISUAL_PAGES) pages.add(pageNumber);
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
