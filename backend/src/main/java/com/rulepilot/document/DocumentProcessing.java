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

    void replacePages(UUID documentVersionId, List<ExtractedPage> pages);

    List<PageView> pages(UUID documentVersionId);

    record ExtractedPage(int pageNumber, String text) {
        public ExtractedPage {
            if (pageNumber < 1 || text == null) {
                throw new IllegalArgumentException("page number and text are required");
            }
        }
    }

    record PageView(int pageNumber, String text, int characterCount) {}
}
