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
                    || (factualSummary != null && factualSummary.length() > 1_600)
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
