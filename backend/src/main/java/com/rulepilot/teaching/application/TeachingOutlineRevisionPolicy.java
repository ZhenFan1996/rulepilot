package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Structural outline revision policy; semantic chapter ownership remains a model/Critic responsibility. */
final class TeachingOutlineRevisionPolicy {

    static final String VISUAL_CATALOG_PREFIX = "[Visual page catalog; verify against page image]";
    private static final int MIN_SOURCE_CHARACTERS = 40;

    private TeachingOutlineRevisionPolicy() {}

    static Optional<String> chapterOwnershipRevisionFeedback(TeachingOutlineModel.OutlineDraft outline) {
        if (outline == null) throw new IllegalArgumentException("outline is required");
        Map<String, Set<String>> ownersByTag = new LinkedHashMap<>();
        outline.topics().forEach(topic -> topic.coverageTags().stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(TeachingOutlineRevisionPolicy::normalizedTag)
                .distinct()
                .forEach(tag -> ownersByTag
                        .computeIfAbsent(tag, ignored -> new LinkedHashSet<>())
                        .add(topic.key())));

        List<String> overlappingTopics = outline.topics().stream()
                .map(topic -> overlappingTopic(topic, ownersByTag))
                .flatMap(Optional::stream)
                .toList();
        if (overlappingTopics.isEmpty()) return Optional.empty();
        return Optional.of("""
                Rebuild the complete lesson outline in the dependency order a first-time player needs, rather than
                mirroring source-page order. The structural ownership ledger below shows broad topics that claim at
                least four coverage dimensions also owned by multiple other topics. Give every detailed procedure,
                exception, scoring rule, and setup requirement one primary teaching home. An overview may preview
                later ideas, but must not teach their full details twice. Preserve all supported clauses, retrieval
                queries, source-page bindings, and coverage; move them to the appropriate owner instead of deleting
                them. Do not infer semantics from topic names or vocabulary; use the supplied source content.
                Keep every topic within the output contract: objective at most 600 characters, at most four retrieval
                queries, and at most five source pages.
                Overlapping ownership:
                """ + String.join("\n", overlappingTopics));
    }

    private static Optional<String> overlappingTopic(
            TeachingOutlineModel.TopicDraft topic, Map<String, Set<String>> ownersByTag) {
        List<String> repeatedTags = topic.coverageTags().stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(TeachingOutlineRevisionPolicy::normalizedTag)
                .distinct()
                .filter(tag -> ownersByTag.getOrDefault(tag, Set.of()).size() > 1)
                .toList();
        Set<String> otherOwners = repeatedTags.stream()
                .flatMap(tag -> ownersByTag.getOrDefault(tag, Set.of()).stream())
                .filter(owner -> !owner.equals(topic.key()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (repeatedTags.size() < 4 || otherOwners.size() < 2) return Optional.empty();
        return Optional.of("- topic=" + topic.key() + "; repeatedTags=" + repeatedTags
                + "; otherOwners=" + otherOwners);
    }

    private static String normalizedTag(String tag) {
        return tag.strip().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    static Optional<String> sourcePageCoverageRevisionFeedback(
            TeachingOutlineModel.OutlineDraft outline, List<PageInput> pages) {
        if (outline == null || pages == null || pages.isEmpty()) return Optional.empty();
        Set<Integer> boundPages = outline.topics().stream()
                .flatMap(topic -> topic.sourcePageNumbers().stream())
                .collect(Collectors.toSet());
        List<PageInput> missing = pages.stream()
                .filter(page -> isSubstantiveRulebookText(page.text()))
                .filter(page -> !boundPages.contains(page.pageNumber()))
                .sorted(Comparator.comparingInt(PageInput::pageNumber))
                .limit(4)
                .toList();
        if (missing.isEmpty()) return Optional.empty();
        String pageCatalog = missing.stream()
                .map(page -> "Page " + page.pageNumber() + ": " + boundedPageText(page.text()))
                .collect(Collectors.joining("\n"));
        return Optional.of("""
                Rebuild the complete lesson outline so every listed source page with substantive extracted content has
                one appropriate teaching owner. Preserve current coverage, source-language retrieval queries, source
                bindings, and player-journey order. Do not infer a page's semantic role from its position or generic
                vocabulary; read the supplied page content and bind it only to a topic it actually supports.
                Unowned source pages:
                """ + pageCatalog);
    }

    static boolean isSubstantiveRulebookText(String text) {
        if (text == null || text.isBlank()) return false;
        return text.codePoints().filter(Character::isLetterOrDigit).limit(MIN_SOURCE_CHARACTERS).count()
                >= MIN_SOURCE_CHARACTERS;
    }

    private static String boundedPageText(String text) {
        String value = text == null ? "" : text.strip();
        return value.length() <= 420 ? value : value.substring(0, 419) + "…";
    }
}
