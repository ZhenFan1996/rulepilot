package com.rulepilot.document;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

public interface DocumentProcessing {

    InputStream open(UUID documentVersionId);

    void markValidating(UUID documentVersionId);

    void markExtracting(UUID documentVersionId);

    void markStructuring(UUID documentVersionId);

    void markChunking(UUID documentVersionId);

    void markEmbedding(UUID documentVersionId);

    void markIndexing(UUID documentVersionId);

    void markReady(UUID documentVersionId);

    void markFailed(UUID documentVersionId);

    void prepareRetry(UUID documentVersionId, DocumentProcessingStage stage);

    void replacePages(UUID documentVersionId, List<ExtractedPage> pages);

    int pageCount(UUID documentVersionId);

    List<PageView> pages(UUID documentVersionId);

    record ExtractedPage(int pageNumber, String text, List<ExtractedTextBlock> textBlocks) {
        public ExtractedPage {
            if (pageNumber < 1 || text == null) {
                throw new IllegalArgumentException("page number and text are required");
            }
            textBlocks = List.copyOf(textBlocks == null ? List.of() : textBlocks);
        }

        public ExtractedPage(int pageNumber, String text) {
            this(pageNumber, text, List.of());
        }
    }

    /**
     * A positioned text fragment captured from the source PDF. Coordinates use a page-relative
     * 0-1000 space so persisted evidence stays independent of PDF rendering resolution.
     */
    record ExtractedTextBlock(
            int readingOrder, String text, int x, int y, int width, int height) {
        public ExtractedTextBlock {
            if (readingOrder < 0 || text == null || text.isBlank()
                    || x < 0 || x > 1_000 || y < 0 || y > 1_000
                    || width < 1 || width > 1_000 || height < 1 || height > 1_000
                    || x + width > 1_000 || y + height > 1_000) {
                throw new IllegalArgumentException("extracted text block is invalid");
            }
            text = text.strip();
        }
    }

    record PageView(int pageNumber, String text, int characterCount) {}
}
