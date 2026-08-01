package com.rulepilot.teaching;

import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import java.util.List;

/**
 * Reads a small batch of rendered rulebook pages before lesson planning. Its output is a page-scoped retrieval aid,
 * never player-facing lesson prose or an uncited rule answer.
 */
public interface VisualRulebookPageCatalogModel {

    CatalogDraft summarize(CatalogRequest request);

    /**
     * Rechecks model-proposed icon rectangles in a dedicated spatial-grounding pass. Implementations that cannot
     * perform a second visual pass preserve the proposed locations; the application still treats those pages as
     * incomplete when a configured real model fails the check.
     */
    default IconLocalizationDraft localizeIcons(IconLocalizationRequest request) {
        return new IconLocalizationDraft(java.util.stream.IntStream.range(0, request.candidates().size())
                .mapToObj(index -> {
                    IconOccurrence icon = request.candidates().get(index);
                    return new IconLocation(index, true, icon.x(), icon.y(), icon.width(), icon.height(), "");
                })
                .toList());
    }

    /**
     * Rechecks already localized close-up crops. A full-page locator can still land on adjacent prose or a similar
     * mark; implementations with a real vision model should inspect the proposed region at readable scale.
     */
    default IconCropReviewDraft reviewIconCrops(IconCropReviewRequest request) {
        return new IconCropReviewDraft(request.locations().stream()
                .map(location -> new IconCropDecision(
                        location.candidateIndex(),
                        true,
                        location.x(),
                        location.y(),
                        location.width(),
                        location.height()))
                .toList());
    }

    default boolean available(String modelConfigurationOwner) {
        return true;
    }

    static VisualRulebookPageCatalogModel unavailable() {
        return new VisualRulebookPageCatalogModel() {
            @Override
            public CatalogDraft summarize(CatalogRequest request) {
                throw new IllegalStateException("visual page catalog is unavailable");
            }

            @Override
            public boolean available(String modelConfigurationOwner) {
                return false;
            }
        };
    }

    record CatalogRequest(
            List<PageImageInput> pages,
            String modelConfigurationOwner,
            String rulebookTitle,
            PageViewport viewport) {

        public CatalogRequest(List<PageImageInput> pages, String modelConfigurationOwner) {
            this(pages, modelConfigurationOwner, null, null);
        }

        public CatalogRequest(List<PageImageInput> pages, String modelConfigurationOwner, String rulebookTitle) {
            this(pages, modelConfigurationOwner, rulebookTitle, null);
        }

        public CatalogRequest {
            if (pages == null || pages.isEmpty() || pages.size() > 4) {
                throw new IllegalArgumentException("visual page catalog request is invalid");
            }
            pages = List.copyOf(pages);
            modelConfigurationOwner = modelConfigurationOwner == null || modelConfigurationOwner.isBlank()
                    ? null
                    : modelConfigurationOwner.strip();
            if (rulebookTitle != null && rulebookTitle.length() > 160) {
                throw new IllegalArgumentException("visual page catalog rulebook title is invalid");
            }
            rulebookTitle = rulebookTitle == null || rulebookTitle.isBlank() ? null : rulebookTitle.strip();
            if (viewport != null
                    && (pages.size() != 1 || pages.getFirst().pageNumber() != viewport.pageNumber())) {
                throw new IllegalArgumentException("visual page viewport does not match its image");
            }
        }
    }

    /**
     * Normalized bounds of a supplied page tile. Model coordinates stay tile-relative and are projected back to the
     * immutable source page by the application layer.
     */
    record PageViewport(int pageNumber, int x, int y, int width, int height) {

        public PageViewport {
            if (pageNumber < 1
                    || x < 0 || y < 0 || width < 200 || height < 200
                    || x + width > 1_000 || y + height > 1_000) {
                throw new IllegalArgumentException("visual page viewport is invalid");
            }
        }
    }

    record CatalogDraft(List<PageSummary> pages) {
        public CatalogDraft {
            if (pages == null || pages.isEmpty() || pages.size() > 4) {
                throw new IllegalArgumentException("visual page catalog draft is invalid");
            }
            pages = List.copyOf(pages);
        }
    }

    record IconLocalizationRequest(
            PageImageInput page,
            List<IconOccurrence> candidates,
            String modelConfigurationOwner) {

        public IconLocalizationRequest {
            if (page == null || candidates == null || candidates.isEmpty() || candidates.size() > 32) {
                throw new IllegalArgumentException("visual icon localization request is invalid");
            }
            candidates = List.copyOf(candidates);
            modelConfigurationOwner = modelConfigurationOwner == null || modelConfigurationOwner.isBlank()
                    ? null
                    : modelConfigurationOwner.strip();
        }
    }

    record IconLocalizationDraft(List<IconLocation> locations) {
        public IconLocalizationDraft {
            if (locations == null || locations.isEmpty() || locations.size() > 32) {
                throw new IllegalArgumentException("visual icon localization draft is invalid");
            }
            locations = List.copyOf(locations);
        }
    }

    record IconCropReviewRequest(
            PageImageInput page,
            List<IconOccurrence> candidates,
            List<IconLocation> locations,
            String modelConfigurationOwner) {

        public IconCropReviewRequest {
            if (page == null || candidates == null || locations == null
                    || candidates.isEmpty() || candidates.size() > 8 || candidates.size() != locations.size()
                    || locations.stream().anyMatch(location -> !location.present())) {
                throw new IllegalArgumentException("visual icon crop review request is invalid");
            }
            candidates = List.copyOf(candidates);
            locations = List.copyOf(locations);
            modelConfigurationOwner = modelConfigurationOwner == null || modelConfigurationOwner.isBlank()
                    ? null
                    : modelConfigurationOwner.strip();
        }
    }

    record IconCropReviewDraft(List<IconCropDecision> decisions) {
        public IconCropReviewDraft {
            if (decisions == null || decisions.isEmpty() || decisions.size() > 8) {
                throw new IllegalArgumentException("visual icon crop review draft is invalid");
            }
            decisions = List.copyOf(decisions);
        }
    }

    record IconCropDecision(
            int candidateIndex,
            boolean matchesAppearance,
            int x,
            int y,
            int width,
            int height) {

        public IconCropDecision(int candidateIndex, boolean matchesAppearance) {
            this(candidateIndex, matchesAppearance, 0, 0, 0, 0);
        }

        public IconCropDecision {
            if (candidateIndex < 0 || candidateIndex > 31) {
                throw new IllegalArgumentException("visual icon crop review candidate is invalid");
            }
            if (matchesAppearance && (x < 0 || x > 980 || y < 0 || y > 980
                    || width < 12 || height < 12 || x + width > 1_000 || y + height > 1_000)) {
                throw new IllegalArgumentException("visual icon crop review rectangle is invalid");
            }
            if (!matchesAppearance && (x != 0 || y != 0 || width != 0 || height != 0)) {
                throw new IllegalArgumentException("rejected visual icon crop must not have a rectangle");
            }
        }

        public static IconCropDecision rejected(int candidateIndex) {
            return new IconCropDecision(candidateIndex, false);
        }
    }

    record IconLocation(
            int candidateIndex,
            boolean present,
            int x,
            int y,
            int width,
            int height,
            String observedLabel) {

        public IconLocation(int candidateIndex, boolean present, int x, int y, int width, int height) {
            this(candidateIndex, present, x, y, width, height, "");
        }

        public IconLocation {
            if (candidateIndex < 0 || candidateIndex > 31) {
                throw new IllegalArgumentException("visual icon localization candidate is invalid");
            }
            if (present && (x < 0 || x > 980 || y < 0 || y > 980
                    || width < 12 || height < 12 || x + width > 1_000 || y + height > 1_000)) {
                throw new IllegalArgumentException("visual icon localization rectangle is invalid");
            }
            if (!present && (x != 0 || y != 0 || width != 0 || height != 0)) {
                throw new IllegalArgumentException("absent visual icon localization must not have a rectangle");
            }
            if (observedLabel != null && observedLabel.length() > 80) {
                throw new IllegalArgumentException("visual icon localization label is invalid");
            }
            observedLabel = present && observedLabel != null ? observedLabel.strip() : "";
        }

        public static IconLocation absent(int candidateIndex) {
            return new IconLocation(candidateIndex, false, 0, 0, 0, 0, "");
        }
    }

    record PageSummary(
            int pageNumber,
            String printedTerms,
            String factualSummary,
            List<String> keywords,
            List<VisualAnchor> visualAnchors,
            List<IconOccurrence> iconOccurrences,
            boolean iconInventoryComplete) {

        public PageSummary(int pageNumber, String printedTerms, String factualSummary, List<String> keywords) {
            this(pageNumber, printedTerms, factualSummary, keywords, List.of(), List.of(), false);
        }

        public PageSummary(
                int pageNumber,
                String printedTerms,
                String factualSummary,
                List<String> keywords,
                List<VisualAnchor> visualAnchors) {
            this(pageNumber, printedTerms, factualSummary, keywords, visualAnchors, List.of(), false);
        }

        public PageSummary {
            if (pageNumber < 1
                    || (printedTerms != null && printedTerms.length() > 1_600)
                    || (factualSummary != null && factualSummary.length() > 2_400)
                    || (keywords != null && (keywords.size() > 16
                            || keywords.stream().anyMatch(keyword -> keyword == null || keyword.isBlank() || keyword.length() > 120)))
                    || (visualAnchors != null && visualAnchors.size() > 8)
                    || (iconOccurrences != null && iconOccurrences.size() > 32)) {
                throw new IllegalArgumentException("visual page summary is invalid");
            }
            printedTerms = printedTerms == null || printedTerms.isBlank()
                    ? "No legible printed term on this page."
                    : printedTerms.strip();
            factualSummary = factualSummary == null || factualSummary.isBlank()
                    ? "该页没有可可靠转写的规则文字；请直接查看页面图像。"
                    : factualSummary.strip();
            keywords = keywords == null || keywords.isEmpty()
                    ? List.of("page " + pageNumber)
                    : keywords.stream().map(String::strip).distinct().toList();
            visualAnchors = visualAnchors == null ? List.of() : visualAnchors.stream().distinct().toList();
            iconOccurrences = iconOccurrences == null ? List.of() : iconOccurrences.stream().distinct().toList();
        }
    }
}
