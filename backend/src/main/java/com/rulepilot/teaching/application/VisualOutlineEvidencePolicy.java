package com.rulepilot.teaching.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.VisualRulebookPageClassifier;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Structural source-page and visual-budget policy for teaching outlines. */
final class VisualOutlineEvidencePolicy {

    static final int MAX_INTERPRETED_VISUAL_PAGES = 4;
    private static final int MAX_TOPIC_SOURCE_PAGES = 5;
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
                .flatMap(topic -> topic.sourcePageNumbers().stream())
                .forEach(expected::remove);
        if (!expected.isEmpty()) {
            throw new IllegalArgumentException("visual rulebook outline omitted admitted source pages " + expected);
        }
    }

    static void validateVisualCoreTopicBindings(
            TeachingOutlineModel.OutlineDraft outline, List<PageInput> visualCatalogPages) {
        Set<Integer> availablePages = visualCatalogPages.stream()
                .map(PageInput::pageNumber)
                .collect(Collectors.toUnmodifiableSet());
        for (String tag : CORE_COVERAGE_TAGS) {
            boolean bound = outline.topics().stream()
                    .filter(topic -> topic.coverageTags().stream().map(VisualOutlineEvidencePolicy::identityKey)
                            .anyMatch(tag::equals))
                    .flatMap(topic -> topic.sourcePageNumbers().stream())
                    .anyMatch(availablePages::contains);
            if (!bound) {
                throw new IllegalArgumentException("visual rulebook outline must bind core coverage tag " + tag);
            }
        }
    }

    static TeachingOutlineModel.OutlineDraft bindVisualCoreTopicEvidence(
            TeachingOutlineModel.OutlineDraft outline, List<PageInput> visualCatalogPages) {
        return outline;
    }

    static void validateVisualFastBaseline(TeachingOutlineModel.OutlineDraft outline) {
        if (outline.topics().size() > 10) {
            throw new IllegalArgumentException(
                    "visual rulebook outline exceeds the ten-section fast baseline and must be compacted");
        }
    }

    static TeachingOutlineModel.OutlineDraft keepFastVisualBaseline(
            TeachingOutlineModel.OutlineDraft expanded, TeachingOutlineModel.OutlineDraft sourceDerived) {
        return expanded.topics().size() <= 10 ? expanded : sourceDerived;
    }

    static TeachingOutlineModel.OutlineDraft augmentVisualCoverage(
            TeachingOutlineModel.OutlineDraft modelOutline, TeachingOutlineModel.OutlineDraft sourceOutline) {
        Set<Integer> covered = modelOutline.topics().stream()
                .flatMap(topic -> topic.sourcePageNumbers().stream())
                .collect(Collectors.toSet());
        List<TeachingOutlineModel.TopicDraft> topics = new ArrayList<>(modelOutline.topics());
        for (TeachingOutlineModel.TopicDraft sourceTopic : sourceOutline.topics()) {
            List<Integer> remainingPages = sourceTopic.sourcePageNumbers().stream()
                    .filter(page -> !covered.contains(page))
                    .toList();
            if (remainingPages.isEmpty()) continue;
            int existingTopic = matchingCoverageTopic(topics, sourceTopic);
            if (existingTopic >= 0) {
                TeachingOutlineModel.TopicDraft modelTopic = topics.get(existingTopic);
                int capacity = MAX_TOPIC_SOURCE_PAGES - modelTopic.sourcePageNumbers().size();
                if (capacity > 0) {
                    List<Integer> mergedPages = remainingPages.stream().limit(capacity).toList();
                    topics.set(existingTopic, mergeCoveragePages(modelTopic, sourceTopic, mergedPages));
                    covered.addAll(mergedPages);
                    remainingPages = remainingPages.subList(mergedPages.size(), remainingPages.size());
                }
            }
            while (!remainingPages.isEmpty()) {
                List<Integer> companionPages = remainingPages.stream().limit(MAX_TOPIC_SOURCE_PAGES).toList();
                topics.add(new TeachingOutlineModel.TopicDraft(
                        "source-coverage-" + (topics.size() + 1),
                        sourceTopic.title(),
                        sourceTopic.objective(),
                        sourceTopic.required(),
                        sourceTopic.visualEvidenceRecommended(),
                        sourceTopic.retrievalQueries(),
                        sourceTopic.coverageTags(),
                        companionPages));
                covered.addAll(companionPages);
                remainingPages = remainingPages.subList(companionPages.size(), remainingPages.size());
            }
        }
        return new TeachingOutlineModel.OutlineDraft(modelOutline.gameTitle(), modelOutline.premise(), topics);
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

    private static int matchingCoverageTopic(
            List<TeachingOutlineModel.TopicDraft> topics, TeachingOutlineModel.TopicDraft sourceTopic) {
        for (int index = 0; index < topics.size(); index++) {
            TeachingOutlineModel.TopicDraft candidate = topics.get(index);
            if (identityKey(candidate.key()).equals(identityKey(sourceTopic.key()))
                    || sameCompoundCoverage(candidate.coverageTags(), sourceTopic.coverageTags())) {
                return index;
            }
        }
        return -1;
    }

    private static boolean sameCompoundCoverage(List<String> candidateTags, List<String> sourceTags) {
        Set<String> candidate = candidateTags.stream()
                .map(VisualOutlineEvidencePolicy::identityKey)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> source = sourceTags.stream()
                .map(VisualOutlineEvidencePolicy::identityKey)
                .collect(Collectors.toUnmodifiableSet());
        return (candidate.size() >= 2 && source.containsAll(candidate))
                || (source.size() >= 2 && candidate.containsAll(source));
    }

    private static TeachingOutlineModel.TopicDraft mergeCoveragePages(
            TeachingOutlineModel.TopicDraft modelTopic,
            TeachingOutlineModel.TopicDraft sourceTopic,
            List<Integer> missingPages) {
        LinkedHashSet<Integer> pages = new LinkedHashSet<>(modelTopic.sourcePageNumbers());
        pages.addAll(missingPages);
        LinkedHashSet<String> queries = new LinkedHashSet<>(modelTopic.retrievalQueries());
        queries.addAll(sourceTopic.retrievalQueries());
        LinkedHashSet<String> tags = new LinkedHashSet<>(modelTopic.coverageTags());
        tags.addAll(sourceTopic.coverageTags());
        return new TeachingOutlineModel.TopicDraft(
                modelTopic.key(),
                modelTopic.title(),
                modelTopic.objective(),
                modelTopic.required() || sourceTopic.required(),
                modelTopic.visualEvidenceRecommended() || sourceTopic.visualEvidenceRecommended(),
                queries.stream().limit(4).toList(),
                List.copyOf(tags),
                pages.stream().limit(MAX_TOPIC_SOURCE_PAGES).toList());
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
}
