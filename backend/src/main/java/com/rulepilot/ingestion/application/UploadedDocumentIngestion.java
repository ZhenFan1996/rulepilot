package com.rulepilot.ingestion.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentProcessingStage;
import com.rulepilot.document.DocumentPageImageStore;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class UploadedDocumentIngestion {

    private static final Logger LOGGER = LoggerFactory.getLogger(UploadedDocumentIngestion.class);
    private static final int RENDERING_START_PERCENTAGE = 40;
    private static final int RENDERING_COMPLETE_PERCENTAGE = 65;
    private static final int MAX_RENDERING_PROGRESS_EVENTS = 20;

    private final DocumentProcessing documents;
    private final PdfPageExtractor extractor;
    private final PdfPageImageRenderer pageImageRenderer;
    private final DocumentPageImageStore pageImages;
    private final ProcessingProgressTracker progress;
    private final RuleStructureService structures;
    private final RuleChunkEmbeddingService embeddings;

    public UploadedDocumentIngestion(
            DocumentProcessing documents,
            PdfPageExtractor extractor,
            PdfPageImageRenderer pageImageRenderer,
            DocumentPageImageStore pageImages,
            ProcessingProgressTracker progress,
            RuleStructureService structures,
            RuleChunkEmbeddingService embeddings) {
        this.documents = documents;
        this.extractor = extractor;
        this.pageImageRenderer = pageImageRenderer;
        this.pageImages = pageImages;
        this.progress = progress;
        this.structures = structures;
        this.embeddings = embeddings;
    }

    public void process(UUID documentVersionId, DocumentProcessingStage stage) {
        switch (stage) {
            case PARSE -> parse(documentVersionId);
            case CHUNK -> chunk(documentVersionId);
            case EMBED -> embed(documentVersionId);
        }
    }

    public void fail(UUID documentVersionId) {
        try {
            documents.markFailed(documentVersionId);
            progress.update(documentVersionId, "FAILED", 100, 0, true);
        } catch (RuntimeException statusException) {
            LOGGER.error(
                    "Could not persist ingestion failure for documentVersionId={}",
                    documentVersionId,
                    statusException);
        }
    }

    private void parse(UUID documentVersionId) {
        progress.update(documentVersionId, "VALIDATING", 15, 0, false);
        documents.markValidating(documentVersionId);
        progress.update(documentVersionId, "EXTRACTING", 30, 0, false);
        documents.markExtracting(documentVersionId);
        var pages = extractor.extract(documents.open(documentVersionId));
        documents.replacePages(documentVersionId, pages);
        int totalPages = pages.size();
        progress.update(documentVersionId, "RENDERING", RENDERING_START_PERCENTAGE, 0, totalPages, false);
        AtomicInteger renderedPageCount = new AtomicInteger();
        int updateInterval = renderingUpdateInterval(totalPages);
        int renderedPageCountResult = pageImageRenderer.render(
                documents.open(documentVersionId), image -> {
                    pageImages.store(documentVersionId, image);
                    int completedPages = renderedPageCount.incrementAndGet();
                    if (completedPages % updateInterval == 0 || completedPages == totalPages) {
                        progress.update(
                                documentVersionId,
                                "RENDERING",
                                renderingPercentage(completedPages, totalPages),
                                completedPages,
                                totalPages,
                                false);
                    }
                });
        if (renderedPageCountResult != totalPages) {
            throw new IllegalStateException("rendered page count does not match extracted page count");
        }
        // Keep the positioned extraction that just produced the durable page text. Re-opening the same PDF in the
        // next queue stage adds substantial work on a small worker and can only reproduce these same source blocks.
        documents.markStructuring(documentVersionId);
        progress.update(documentVersionId, "STRUCTURING", 75, totalPages, totalPages, false);
        structures.organize(documentVersionId, pages);
    }

    private void chunk(UUID documentVersionId) {
        int storedPageCount = documents.pages(documentVersionId).size();
        documents.markChunking(documentVersionId);
        progress.update(documentVersionId, "CHUNKING", 85, storedPageCount, false);
    }

    private void embed(UUID documentVersionId) {
        int pageCount = documents.pages(documentVersionId).size();
        documents.markEmbedding(documentVersionId);
        progress.update(documentVersionId, "EMBEDDING", 90, pageCount, false);
        embeddings.index(documentVersionId);
        documents.markIndexing(documentVersionId);
        progress.update(documentVersionId, "INDEXING", 95, pageCount, false);
        documents.markReady(documentVersionId);
        progress.update(documentVersionId, "READY", 100, pageCount, true);
    }

    static int renderingPercentage(int completedPages, int totalPages) {
        if (totalPages <= 0 || completedPages < 0 || completedPages > totalPages) {
            throw new IllegalArgumentException("rendering page progress is invalid");
        }
        return RENDERING_START_PERCENTAGE
                + (int) Math.round((RENDERING_COMPLETE_PERCENTAGE - RENDERING_START_PERCENTAGE)
                        * (completedPages / (double) totalPages));
    }

    static int renderingUpdateInterval(int totalPages) {
        if (totalPages <= 0) {
            throw new IllegalArgumentException("rendering page count must be positive");
        }
        return Math.max(1, (int) Math.ceil(totalPages / (double) (MAX_RENDERING_PROGRESS_EVENTS - 1)));
    }
}
