package com.rulepilot.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rulepilot.document.DocumentPageImageStore;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentProcessing.ExtractedPage;
import com.rulepilot.document.DocumentProcessing.ExtractedTextBlock;
import com.rulepilot.document.DocumentProcessingStage;
import com.rulepilot.document.RetryableDocumentProcessingException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.InOrder;

class UploadedDocumentIngestionTest {

    @Test
    void reportsBoundedPageRenderingProgressAfterTextExtraction() {
        DocumentProcessing documents = Mockito.mock(DocumentProcessing.class);
        PdfRulebookPreparation pdfPreparation = Mockito.mock(PdfRulebookPreparation.class);
        DocumentPageImageStore pageImages = Mockito.mock(DocumentPageImageStore.class);
        ProcessingProgressTracker progress = Mockito.mock(ProcessingProgressTracker.class);
        RuleStructureService structures = Mockito.mock(RuleStructureService.class);
        RuleChunkEmbeddingService embeddings = Mockito.mock(RuleChunkEmbeddingService.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        UploadedDocumentIngestion ingestion = new UploadedDocumentIngestion(
                documents,
                pdfPreparation,
                pageImages,
                new BoundedPageImageStoragePipeline(Runnable::run, 1),
                progress,
                structures,
                embeddings,
                metrics);
        UUID versionId = UUID.randomUUID();
        List<ExtractedPage> pages = List.of(
                page(1, "Setup"),
                page(2, "Turn"),
                page(3, "Scoring"));

        when(documents.open(versionId)).thenAnswer(ignored -> new ByteArrayInputStream(new byte[] {1}));
        doAnswer(invocation -> {
                    Consumer<List<ExtractedPage>> extractedPagesConsumer = invocation.getArgument(1);
                    Consumer<DocumentPageImageStore.RenderedPageImage> pageImageConsumer = invocation.getArgument(2);
                    extractedPagesConsumer.accept(pages);
                    for (int pageNumber = 1; pageNumber <= 3; pageNumber++) {
                        pageImageConsumer.accept(new DocumentPageImageStore.RenderedPageImage(
                                pageNumber, new byte[] {1}, 100, 100));
                    }
                    return null;
                })
                .when(pdfPreparation)
                .prepare(any(), any(), any());

        ingestion.process(versionId, DocumentProcessingStage.PARSE);

        verify(progress).update(versionId, "RENDERING", 40, 0, 3, false);
        verify(progress).update(versionId, "RENDERING", 48, 1, 3, false);
        verify(progress).update(versionId, "RENDERING", 57, 2, 3, false);
        verify(progress).update(versionId, "RENDERING", 65, 3, 3, false);
        verify(progress).update(versionId, "STRUCTURING", 75, 3, 3, false);
        verify(progress).update(versionId, "CHUNKING", 85, 3, false);
        verify(documents).markStructuring(versionId);
        verify(documents).markChunking(versionId);
        InOrder statusOrder = inOrder(documents);
        statusOrder.verify(documents).markStructuring(versionId);
        statusOrder.verify(documents).markChunking(versionId);
        verify(documents).open(versionId);
        verify(structures).organize(versionId, pages);
        InOrder persistenceBeforeImages = inOrder(structures, pageImages);
        persistenceBeforeImages.verify(structures).organize(versionId, pages);
        persistenceBeforeImages.verify(pageImages, Mockito.times(3)).store(Mockito.eq(versionId), any());
        assertThat(metrics.find(UploadedDocumentIngestion.PARSE_PHASE_DURATION_METRIC)
                        .tag("phase", "extraction").timer().count())
                .isOne();
        assertThat(metrics.find(UploadedDocumentIngestion.PARSE_PHASE_DURATION_METRIC)
                        .tag("phase", "render-and-store").timer().count())
                .isOne();
        assertThat(metrics.find(UploadedDocumentIngestion.PARSE_PHASE_DURATION_METRIC)
                        .tag("phase", "page-storage").timer().count())
                .isEqualTo(3);
        assertThat(metrics.find(UploadedDocumentIngestion.PARSE_PHASE_DURATION_METRIC)
                        .tag("phase", "page-storage-final-drain").timer().count())
                .isOne();
        assertThat(metrics.find(UploadedDocumentIngestion.PARSE_PHASE_DURATION_METRIC)
                        .tag("phase", "structuring").timer().count())
                .isOne();
    }

    @Test
    void boundsTheEntireRenderingStageToTwentyOrFewerEvents() {
        assertThat(UploadedDocumentIngestion.renderingUpdateInterval(28)).isEqualTo(2);
        assertThat(UploadedDocumentIngestion.renderingUpdateInterval(500)).isEqualTo(27);
        assertThat(UploadedDocumentIngestion.renderingPercentage(1, 28)).isEqualTo(41);
        assertThat(UploadedDocumentIngestion.renderingPercentage(28, 28)).isEqualTo(65);
    }

    @Test
    void entersStructuringOnlyAfterEveryConcurrentImageWriteIsDurable() throws Exception {
        DocumentProcessing documents = Mockito.mock(DocumentProcessing.class);
        PdfRulebookPreparation pdfPreparation = Mockito.mock(PdfRulebookPreparation.class);
        DocumentPageImageStore pageImages = Mockito.mock(DocumentPageImageStore.class);
        ProcessingProgressTracker progress = Mockito.mock(ProcessingProgressTracker.class);
        RuleStructureService structures = Mockito.mock(RuleStructureService.class);
        RuleChunkEmbeddingService embeddings = Mockito.mock(RuleChunkEmbeddingService.class);
        ExecutorService storageLane = Executors.newFixedThreadPool(2);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            var firstTwoWritesStarted = new CountDownLatch(2);
            var releaseWrites = new CountDownLatch(1);
            doAnswer(invocation -> {
                        firstTwoWritesStarted.countDown();
                        if (!releaseWrites.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test page storage gate timed out");
                        }
                        return null;
                    })
                    .when(pageImages)
                    .store(any(), any());
            doAnswer(invocation -> {
                        Consumer<List<ExtractedPage>> extractedPagesConsumer = invocation.getArgument(1);
                        Consumer<DocumentPageImageStore.RenderedPageImage> pageImageConsumer = invocation.getArgument(2);
                        extractedPagesConsumer.accept(List.of(page(1, "Setup"), page(2, "Turn")));
                        pageImageConsumer.accept(new DocumentPageImageStore.RenderedPageImage(
                                1, new byte[] {1}, 100, 100));
                        pageImageConsumer.accept(new DocumentPageImageStore.RenderedPageImage(
                                2, new byte[] {2}, 100, 100));
                        return null;
                    })
                    .when(pdfPreparation)
                    .prepare(any(), any(), any());
            UUID versionId = UUID.randomUUID();
            when(documents.open(versionId)).thenAnswer(ignored -> new ByteArrayInputStream(new byte[] {1}));
            var ingestion = new UploadedDocumentIngestion(
                    documents,
                    pdfPreparation,
                    pageImages,
                    new BoundedPageImageStoragePipeline(storageLane, 2),
                    progress,
                    structures,
                    embeddings,
                    new SimpleMeterRegistry());

            var processing = worker.submit(() -> ingestion.process(versionId, DocumentProcessingStage.PARSE));
            assertThat(firstTwoWritesStarted.await(5, TimeUnit.SECONDS)).isTrue();
            verify(documents, never()).markStructuring(versionId);
            verify(progress, never()).update(versionId, "RENDERING", 65, 2, 2, false);

            releaseWrites.countDown();
            processing.get(5, TimeUnit.SECONDS);

            var order = inOrder(pageImages, documents);
            order.verify(pageImages, Mockito.times(2)).store(Mockito.eq(versionId), any());
            order.verify(documents).markStructuring(versionId);
            verify(progress).update(versionId, "RENDERING", 65, 2, 2, false);
            verify(progress).update(versionId, "STRUCTURING", 75, 2, 2, false);
        } finally {
            worker.shutdownNow();
            storageLane.shutdownNow();
        }
    }

    @Test
    void publishesMonotonicProgressWhenLaterPagesBecomeDurableFirst() throws Exception {
        DocumentProcessing documents = Mockito.mock(DocumentProcessing.class);
        PdfRulebookPreparation pdfPreparation = Mockito.mock(PdfRulebookPreparation.class);
        DocumentPageImageStore pageImages = Mockito.mock(DocumentPageImageStore.class);
        ProcessingProgressTracker progress = Mockito.mock(ProcessingProgressTracker.class);
        RuleStructureService structures = Mockito.mock(RuleStructureService.class);
        RuleChunkEmbeddingService embeddings = Mockito.mock(RuleChunkEmbeddingService.class);
        ExecutorService storageLane = Executors.newFixedThreadPool(2);
        try {
            var firstPageStarted = new CountDownLatch(1);
            var secondPageStored = new CountDownLatch(1);
            var releaseFirstPage = new CountDownLatch(1);
            doAnswer(invocation -> {
                        DocumentPageImageStore.RenderedPageImage image = invocation.getArgument(1);
                        if (image.pageNumber() == 1) {
                            firstPageStarted.countDown();
                            if (!releaseFirstPage.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test first page gate timed out");
                            }
                        } else {
                            secondPageStored.countDown();
                        }
                        return null;
                    })
                    .when(pageImages)
                    .store(any(), any());
            doAnswer(invocation -> {
                        Consumer<List<ExtractedPage>> extractedPagesConsumer = invocation.getArgument(1);
                        Consumer<DocumentPageImageStore.RenderedPageImage> pageImageConsumer = invocation.getArgument(2);
                        extractedPagesConsumer.accept(List.of(page(1, "Setup"), page(2, "Turn")));
                        pageImageConsumer.accept(new DocumentPageImageStore.RenderedPageImage(
                                1, new byte[] {1}, 100, 100));
                        assertThat(firstPageStarted.await(5, TimeUnit.SECONDS)).isTrue();
                        pageImageConsumer.accept(new DocumentPageImageStore.RenderedPageImage(
                                2, new byte[] {2}, 100, 100));
                        assertThat(secondPageStored.await(5, TimeUnit.SECONDS)).isTrue();
                        releaseFirstPage.countDown();
                        return null;
                    })
                    .when(pdfPreparation)
                    .prepare(any(), any(), any());
            UUID versionId = UUID.randomUUID();
            when(documents.open(versionId)).thenAnswer(ignored -> new ByteArrayInputStream(new byte[] {1}));
            var ingestion = new UploadedDocumentIngestion(
                    documents,
                    pdfPreparation,
                    pageImages,
                    new BoundedPageImageStoragePipeline(storageLane, 2),
                    progress,
                    structures,
                    embeddings,
                    new SimpleMeterRegistry());

            ingestion.process(versionId, DocumentProcessingStage.PARSE);

            var progressOrder = inOrder(progress);
            progressOrder.verify(progress).update(versionId, "VALIDATING", 15, 0, false);
            progressOrder.verify(progress).update(versionId, "EXTRACTING", 30, 0, false);
            progressOrder.verify(progress).update(versionId, "RENDERING", 40, 0, 2, false);
            progressOrder.verify(progress).update(versionId, "RENDERING", 53, 1, 2, false);
            progressOrder.verify(progress).update(versionId, "RENDERING", 65, 2, 2, false);
            progressOrder.verify(progress).update(versionId, "STRUCTURING", 75, 2, 2, false);
            progressOrder.verify(progress).update(versionId, "CHUNKING", 85, 2, false);
        } finally {
            storageLane.shutdownNow();
        }
    }

    @Test
    void propagatesTheOriginalRetryableStorageFailureWithoutAdvancingTheDocument() {
        DocumentProcessing documents = Mockito.mock(DocumentProcessing.class);
        PdfRulebookPreparation pdfPreparation = Mockito.mock(PdfRulebookPreparation.class);
        DocumentPageImageStore pageImages = Mockito.mock(DocumentPageImageStore.class);
        ProcessingProgressTracker progress = Mockito.mock(ProcessingProgressTracker.class);
        RuleStructureService structures = Mockito.mock(RuleStructureService.class);
        RuleChunkEmbeddingService embeddings = Mockito.mock(RuleChunkEmbeddingService.class);
        RetryableDocumentProcessingException storageFailure =
                new RetryableDocumentProcessingException("storage timeout", new IllegalStateException("offline"));
        UUID versionId = UUID.randomUUID();
        when(documents.open(versionId)).thenAnswer(ignored -> new ByteArrayInputStream(new byte[] {1}));
        doAnswer(invocation -> {
                    Consumer<List<ExtractedPage>> extractedPagesConsumer = invocation.getArgument(1);
                    Consumer<DocumentPageImageStore.RenderedPageImage> pageImageConsumer = invocation.getArgument(2);
                    extractedPagesConsumer.accept(List.of(page(1, "Setup")));
                    pageImageConsumer.accept(new DocumentPageImageStore.RenderedPageImage(
                            1, new byte[] {1}, 100, 100));
                    return null;
                })
                .when(pdfPreparation)
                .prepare(any(), any(), any());
        doAnswer(invocation -> {
                    throw storageFailure;
                })
                .when(pageImages)
                .store(Mockito.eq(versionId), any());
        var ingestion = new UploadedDocumentIngestion(
                documents,
                pdfPreparation,
                pageImages,
                new BoundedPageImageStoragePipeline(Runnable::run, 1),
                progress,
                structures,
                embeddings,
                new SimpleMeterRegistry());

        assertThatThrownBy(() -> ingestion.process(versionId, DocumentProcessingStage.PARSE))
                .isSameAs(storageFailure);
        verify(documents, never()).markStructuring(versionId);
        verify(progress, never()).update(versionId, "STRUCTURING", 75, 1, 1, false);
    }

    @Test
    void drainsAcceptedImageWritesBeforePropagatingAPdfPreparationFailure() throws Exception {
        DocumentProcessing documents = Mockito.mock(DocumentProcessing.class);
        PdfRulebookPreparation pdfPreparation = Mockito.mock(PdfRulebookPreparation.class);
        DocumentPageImageStore pageImages = Mockito.mock(DocumentPageImageStore.class);
        ProcessingProgressTracker progress = Mockito.mock(ProcessingProgressTracker.class);
        RuleStructureService structures = Mockito.mock(RuleStructureService.class);
        RuleChunkEmbeddingService embeddings = Mockito.mock(RuleChunkEmbeddingService.class);
        ExecutorService storageLane = Executors.newFixedThreadPool(2);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            var storageStarted = new CountDownLatch(1);
            var releaseStorage = new CountDownLatch(1);
            doAnswer(invocation -> {
                        storageStarted.countDown();
                        if (!releaseStorage.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test page storage gate timed out");
                        }
                        return null;
                    })
                    .when(pageImages)
                    .store(any(), any());
            IllegalArgumentException preparationFailure = new IllegalArgumentException("invalid rendered page");
            doAnswer(invocation -> {
                        Consumer<List<ExtractedPage>> extractedPagesConsumer = invocation.getArgument(1);
                        Consumer<DocumentPageImageStore.RenderedPageImage> pageImageConsumer = invocation.getArgument(2);
                        extractedPagesConsumer.accept(List.of(page(1, "Setup")));
                        pageImageConsumer.accept(new DocumentPageImageStore.RenderedPageImage(
                                1, new byte[] {1}, 100, 100));
                        throw preparationFailure;
                    })
                    .when(pdfPreparation)
                    .prepare(any(), any(), any());
            UUID versionId = UUID.randomUUID();
            when(documents.open(versionId)).thenAnswer(ignored -> new ByteArrayInputStream(new byte[] {1}));
            var ingestion = new UploadedDocumentIngestion(
                    documents,
                    pdfPreparation,
                    pageImages,
                    new BoundedPageImageStoragePipeline(storageLane, 2),
                    progress,
                    structures,
                    embeddings,
                    new SimpleMeterRegistry());

            var processing = worker.submit(() -> ingestion.process(versionId, DocumentProcessingStage.PARSE));
            assertThat(storageStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(processing.isDone()).isFalse();
            verify(documents, never()).markStructuring(versionId);

            releaseStorage.countDown();
            assertThatThrownBy(() -> processing.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCause(preparationFailure);
            verify(pageImages).store(Mockito.eq(versionId), any());
            verify(documents, never()).markStructuring(versionId);
        } finally {
            worker.shutdownNow();
            storageLane.shutdownNow();
        }
    }

    @Test
    void bridgesLegacyChunkingWithoutReopeningTheAlreadyStructuredPdf() {
        DocumentProcessing documents = Mockito.mock(DocumentProcessing.class);
        PdfRulebookPreparation pdfPreparation = Mockito.mock(PdfRulebookPreparation.class);
        DocumentPageImageStore pageImages = Mockito.mock(DocumentPageImageStore.class);
        ProcessingProgressTracker progress = Mockito.mock(ProcessingProgressTracker.class);
        RuleStructureService structures = Mockito.mock(RuleStructureService.class);
        RuleChunkEmbeddingService embeddings = Mockito.mock(RuleChunkEmbeddingService.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        UploadedDocumentIngestion ingestion = new UploadedDocumentIngestion(
                documents,
                pdfPreparation,
                pageImages,
                new BoundedPageImageStoragePipeline(Runnable::run, 1),
                progress,
                structures,
                embeddings,
                metrics);
        UUID versionId = UUID.randomUUID();
        when(documents.pageCount(versionId)).thenReturn(1);

        ingestion.process(versionId, DocumentProcessingStage.CHUNK);

        verify(documents).pageCount(versionId);
        verify(documents, never()).pages(versionId);
        verify(documents).markChunking(versionId);
        verifyNoInteractions(pdfPreparation, pageImages, structures, embeddings);
    }

    @Test
    void embeddingReadsOnlyThePageCountInsteadOfEveryPageBody() {
        DocumentProcessing documents = Mockito.mock(DocumentProcessing.class);
        PdfRulebookPreparation pdfPreparation = Mockito.mock(PdfRulebookPreparation.class);
        DocumentPageImageStore pageImages = Mockito.mock(DocumentPageImageStore.class);
        ProcessingProgressTracker progress = Mockito.mock(ProcessingProgressTracker.class);
        RuleStructureService structures = Mockito.mock(RuleStructureService.class);
        RuleChunkEmbeddingService embeddings = Mockito.mock(RuleChunkEmbeddingService.class);
        UUID versionId = UUID.randomUUID();
        when(documents.pageCount(versionId)).thenReturn(500);
        when(documents.pages(versionId)).thenAnswer(ignored -> {
            Thread.sleep(200);
            return List.of(new DocumentProcessing.PageView(1, "Setup", 5));
        });
        var ingestion = new UploadedDocumentIngestion(
                documents,
                pdfPreparation,
                pageImages,
                new BoundedPageImageStoragePipeline(Runnable::run, 1),
                progress,
                structures,
                embeddings,
                new SimpleMeterRegistry());

        long startedAt = System.nanoTime();
        ingestion.process(versionId, DocumentProcessingStage.EMBED);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(elapsedMillis).isLessThan(100);
        verify(documents).pageCount(versionId);
        verify(documents, never()).pages(versionId);
        verify(progress).update(versionId, "EMBEDDING", 90, 500, false);
        verify(progress).update(versionId, "INDEXING", 95, 500, false);
        verify(progress).update(versionId, "READY", 100, 500, true);
    }

    private ExtractedPage page(int pageNumber, String text) {
        return new ExtractedPage(pageNumber, text, List.of(
                new ExtractedTextBlock(0, text, 100, 120, 240, 40)));
    }
}
