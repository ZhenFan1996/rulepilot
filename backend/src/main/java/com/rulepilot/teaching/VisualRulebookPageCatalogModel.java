package com.rulepilot.teaching;

import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
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

    record CatalogRequest(List<PageImageInput> pages, String modelConfigurationOwner, String rulebookTitle) {

        public CatalogRequest(List<PageImageInput> pages, String modelConfigurationOwner) {
            this(pages, modelConfigurationOwner, null);
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

    record PageSummary(int pageNumber, String printedTerms, String factualSummary, List<String> keywords) {
        public PageSummary {
            if (pageNumber < 1
                    || (printedTerms != null && printedTerms.length() > 1_600)
                    || (factualSummary != null && factualSummary.length() > 1_600)
                    || (keywords != null && (keywords.size() > 16
                            || keywords.stream().anyMatch(keyword -> keyword == null || keyword.isBlank() || keyword.length() > 120)))) {
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
        }
    }
}
