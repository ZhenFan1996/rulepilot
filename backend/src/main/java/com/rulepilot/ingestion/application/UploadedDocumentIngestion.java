package com.rulepilot.ingestion.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentProcessingStage;
import com.rulepilot.document.DocumentPageImageStore;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class UploadedDocumentIngestion {

    private static final Logger LOGGER = LoggerFactory.getLogger(UploadedDocumentIngestion.class);

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
        int renderedPages = pageImageRenderer.render(
                documents.open(documentVersionId), image -> pageImages.store(documentVersionId, image));
        if (renderedPages != pages.size()) {
            throw new IllegalStateException("rendered page count does not match extracted page count");
        }
        progress.update(documentVersionId, "EXTRACTING", 65, pages.size(), false);
    }

    private void chunk(UUID documentVersionId) {
        var pages = documents.pages(documentVersionId).stream()
                .map(page -> new DocumentProcessing.ExtractedPage(page.pageNumber(), page.text()))
                .toList();
        documents.markStructuring(documentVersionId);
        progress.update(documentVersionId, "STRUCTURING", 75, pages.size(), false);
        structures.organize(documentVersionId, pages);
        documents.markChunking(documentVersionId);
        progress.update(documentVersionId, "CHUNKING", 85, pages.size(), false);
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
}
