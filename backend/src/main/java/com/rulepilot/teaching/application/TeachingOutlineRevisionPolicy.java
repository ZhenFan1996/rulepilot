package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingOutlineModel;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Structural outline revision policy; semantic chapter ownership remains a model/Critic responsibility. */
final class TeachingOutlineRevisionPolicy {

    static final String VISUAL_CATALOG_PREFIX = "[Visual page catalog; verify against page image]";
    private static final int MIN_SOURCE_CHARACTERS = 40;

    private TeachingOutlineRevisionPolicy() {}

    static boolean requiresChapterOwnershipRerun(
            TeachingOutlineModel.OutlineDraft outlineBeforeCoverageRevision,
            TeachingOutlineModel.OutlineDraft outlineAfterCoverageRevision) {
        if (outlineBeforeCoverageRevision == null || outlineAfterCoverageRevision == null) {
            throw new IllegalArgumentException("outline revisions are required");
        }
        return !outlineBeforeCoverageRevision.equals(outlineAfterCoverageRevision);
    }

    static Optional<String> chapterOwnershipRevisionFeedback(TeachingOutlineModel.OutlineDraft outline) {
        if (outline == null) throw new IllegalArgumentException("outline is required");
        return Optional.empty();
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
