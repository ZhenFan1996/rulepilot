package com.rulepilot.teaching;

import com.rulepilot.document.DocumentPageImages.PageImage;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Reads whole source pages into useful, complete illustrations before chapter composition. */
public interface VisualRegionLocator {
    List<VisualFocus> observe(Request request);

    default List<VisualFocus> observe(Request request, Duration remainingTime) {
        return observe(request);
    }

    default boolean supportsVisualEvidence(String owner) { return true; }

    record Request(String chapterObjective, List<PageImage> pages, java.util.Map<String, VisualFocus> illustrations, String owner, UUID runId, com.rulepilot.shared.PlayerLocale outputLanguage) {
        public Request {
            if (chapterObjective == null || pages == null || pages.isEmpty() || owner == null || owner.isBlank()) {
                throw new IllegalArgumentException("source illustration request is incomplete");
            }
            java.util.Objects.requireNonNull(outputLanguage, "illustration language is required");
            pages = List.copyOf(pages);
            illustrations = java.util.Map.copyOf(illustrations);
        }
    }
}
