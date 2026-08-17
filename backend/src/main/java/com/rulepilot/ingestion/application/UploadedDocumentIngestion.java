package com.rulepilot.ingestion.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentProcessingStage;
import com.rulepilot.document.DocumentPageImageStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
@ConditionalOnProperty(name = "rulepilot.runtime.worker-enabled", havingValue = "true", matchIfMissing = true)
public class UploadedDocumentIngestion {

    private static final Logger LOGGER = LoggerFactory.getLogger(UploadedDocumentIngestion.class);
    private static final int RENDERING_START_PERCENTAGE = 40;
    private static final int RENDERING_COMPLETE_PERCENTAGE = 65;
    private static final int MAX_RENDERING_PROGRESS_EVENTS = 20;
    static final String PARSE_PHASE_DURATION_METRIC = "rulepilot.document.processing.parse.phase.duration";

    private final DocumentProcessing documents;
    private final PdfRulebookPreparation pdfPreparation;
    private final DocumentPageImageStore pageImages;
    private final BoundedPageImageStoragePipeline pageImageStorage;
    private final ProcessingProgressTracker progress;
    private final RuleStructureService structures;
    private final RuleChunkEmbeddingService embeddings;
    private final MeterRegistry metrics;

    public UploadedDocumentIngestion(
            DocumentProcessing documents,
            PdfRulebookPreparation pdfPreparation,
            DocumentPageImageStore pageImages,
            BoundedPageImageStoragePipeline pageImageStorage,
            ProcessingProgressTracker progress,
            RuleStructureService structures,
            RuleChunkEmbeddingService embeddings,
            MeterRegistry metrics) {
        this.documents = documents;
        this.pdfPreparation = pdfPreparation;
        this.pageImages = pageImages;
        this.pageImageStorage = pageImageStorage;
        this.progress = progress;
        this.structures = structures;
        this.embeddings = embeddings;
        this.metrics = metrics;
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
        long extractionStartedAt = System.nanoTime();
        AtomicLong extractionNanos = new AtomicLong();
        AtomicLong structuringNanos = new AtomicLong();
        AtomicInteger totalPageCount = new AtomicInteger();
        AtomicLong renderingStartedAt = new AtomicLong();
        AtomicInteger renderedPageCount = new AtomicInteger();
        AtomicLong pageStorageNanos = new AtomicLong();
        var storageBatch = pageImageStorage.openBatch(image -> {
            int totalPages = totalPageCount.get();
            if (totalPages < 1) {
                throw new IllegalStateException("PDF images must follow extracted pages");
            }
            long pageStorageStartedAt = System.nanoTime();
            pageImages.store(documentVersionId, image);
            pageStorageNanos.addAndGet(recordParsePhase("page-storage", pageStorageStartedAt));
            // Storage can finish out of page order. Publish the completion count while holding the same tiny critical
            // section that advances it so a later percentage can never overtake an earlier listener/store update.
            synchronized (renderedPageCount) {
                int completedPages = renderedPageCount.incrementAndGet();
                if (completedPages % renderingUpdateInterval(totalPages) == 0 || completedPages == totalPages) {
                    progress.update(
                            documentVersionId,
                            "RENDERING",
                            renderingPercentage(completedPages, totalPages),
                            completedPages,
                            totalPages,
                            false);
                }
            }
        });
        try {
            pdfPreparation.prepare(
                    documents.open(documentVersionId),
                    pages -> {
                        extractionNanos.set(recordParsePhase("extraction", extractionStartedAt));
                        documents.replacePages(documentVersionId, pages);
                        int totalPages = pages.size();
                        totalPageCount.set(totalPages);
                        // The positioned extraction can be very large for illustrated rulebooks. Persist every derived
                        // structure before image rendering so neither this lambda nor the PDF adapter must retain the
                        // full page/block graph while PDFBox decodes artwork.
                        long structuringStartedAt = System.nanoTime();
                        structures.organize(documentVersionId, pages);
                        structuringNanos.set(recordParsePhase("structuring", structuringStartedAt));
                        progress.update(
                                documentVersionId, "RENDERING", RENDERING_START_PERCENTAGE, 0, totalPages, false);
                        renderingStartedAt.set(System.nanoTime());
                    },
                    storageBatch::submit);
        } catch (RuntimeException preparationFailure) {
            awaitAcceptedPageImages(storageBatch, preparationFailure);
            throw preparationFailure;
        }
        long finalStorageDrainStartedAt = System.nanoTime();
        storageBatch.awaitCompletion();
        long finalStorageDrainNanos = recordParsePhase("page-storage-final-drain", finalStorageDrainStartedAt);
        int totalPages = totalPageCount.get();
        if (totalPages < 1) {
            throw new IllegalStateException("PDF preparation completed without extracted pages");
        }
        long renderingAndStoreNanos = recordParsePhase("render-and-store", renderingStartedAt.get());
        if (renderedPageCount.get() != totalPages) {
            throw new IllegalStateException("rendered page count does not match extracted page count");
        }
        // Keep the positioned extraction that just produced the durable page text. Re-opening the same PDF in the
        // next queue stage adds substantial work on a small worker and can only reproduce these same source blocks.
        documents.markStructuring(documentVersionId);
        progress.update(documentVersionId, "STRUCTURING", 75, totalPages, totalPages, false);
        // RuleStructureService.organize already persisted every retrieval chunk before rendering began. Record that
        // completed business state here so the Worker can publish EMBED directly without a status-only broker hop.
        documents.markChunking(documentVersionId);
        progress.update(documentVersionId, "CHUNKING", 85, totalPages, false);
        LOGGER.info(
                "Document parse completed: pages={}, extractionMs={}, renderAndStoreMs={}, pageStorageWorkMs={}, "
                        + "pageStorageFinalDrainMs={}, structuringMs={}",
                totalPages,
                milliseconds(extractionNanos.get()),
                milliseconds(renderingAndStoreNanos),
                milliseconds(pageStorageNanos.get()),
                milliseconds(finalStorageDrainNanos),
                milliseconds(structuringNanos.get()));
    }

    private void awaitAcceptedPageImages(
            BoundedPageImageStoragePipeline.Batch storageBatch, RuntimeException preparationFailure) {
        try {
            storageBatch.awaitCompletion();
        } catch (RuntimeException storageFailure) {
            if (storageFailure != preparationFailure) {
                preparationFailure.addSuppressed(storageFailure);
            }
        }
    }

    private void chunk(UUID documentVersionId) {
        // Compatibility bridge for CHUNK messages published by a pre-P27-23 Worker during a rolling deployment.
        int storedPageCount = documents.pageCount(documentVersionId);
        documents.markChunking(documentVersionId);
        progress.update(documentVersionId, "CHUNKING", 85, storedPageCount, false);
    }

    private void embed(UUID documentVersionId) {
        int pageCount = documents.pageCount(documentVersionId);
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

    private long recordParsePhase(String phase, long startedAt) {
        long duration = System.nanoTime() - startedAt;
        Timer.builder(PARSE_PHASE_DURATION_METRIC)
                .description("Document parse phase duration")
                .tag("phase", phase)
                .register(metrics)
                .record(duration, TimeUnit.NANOSECONDS);
        return duration;
    }

    private long milliseconds(long duration) {
        return TimeUnit.NANOSECONDS.toMillis(duration);
    }
}
