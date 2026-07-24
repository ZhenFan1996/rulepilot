package com.rulepilot.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rulepilot.document.DocumentPageImageStore;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentProcessing.ExtractedPage;
import com.rulepilot.document.DocumentProcessing.ExtractedTextBlock;
import com.rulepilot.document.DocumentProcessingStage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class UploadedDocumentIngestionTest {

    @Test
    void reportsBoundedPageRenderingProgressAfterTextExtraction() {
        DocumentProcessing documents = Mockito.mock(DocumentProcessing.class);
        PdfPageExtractor extractor = Mockito.mock(PdfPageExtractor.class);
        PdfPageImageRenderer renderer = Mockito.mock(PdfPageImageRenderer.class);
        DocumentPageImageStore pageImages = Mockito.mock(DocumentPageImageStore.class);
        ProcessingProgressTracker progress = Mockito.mock(ProcessingProgressTracker.class);
        RuleStructureService structures = Mockito.mock(RuleStructureService.class);
        RuleChunkEmbeddingService embeddings = Mockito.mock(RuleChunkEmbeddingService.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        UploadedDocumentIngestion ingestion = new UploadedDocumentIngestion(
                documents, extractor, renderer, pageImages, progress, structures, embeddings, metrics);
        UUID versionId = UUID.randomUUID();
        List<ExtractedPage> pages = List.of(
                page(1, "Setup"),
                page(2, "Turn"),
                page(3, "Scoring"));

        when(documents.open(versionId)).thenAnswer(ignored -> new ByteArrayInputStream(new byte[] {1}));
        when(extractor.extract(any())).thenReturn(pages);
        doAnswer(invocation -> {
                    Consumer<DocumentPageImageStore.RenderedPageImage> consumer = invocation.getArgument(1);
                    for (int pageNumber = 1; pageNumber <= 3; pageNumber++) {
                        consumer.accept(new DocumentPageImageStore.RenderedPageImage(
                                pageNumber, new byte[] {1}, 100, 100));
                    }
                    return 3;
                })
                .when(renderer)
                .render(any(), any());

        ingestion.process(versionId, DocumentProcessingStage.PARSE);

        verify(progress).update(versionId, "RENDERING", 40, 0, 3, false);
        verify(progress).update(versionId, "RENDERING", 48, 1, 3, false);
        verify(progress).update(versionId, "RENDERING", 57, 2, 3, false);
        verify(progress).update(versionId, "RENDERING", 65, 3, 3, false);
        verify(progress).update(versionId, "STRUCTURING", 75, 3, 3, false);
        verify(documents).markStructuring(versionId);
        verify(structures).organize(versionId, pages);
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
    void completesChunkingWithoutReopeningTheAlreadyStructuredPdf() {
        DocumentProcessing documents = Mockito.mock(DocumentProcessing.class);
        PdfPageExtractor extractor = Mockito.mock(PdfPageExtractor.class);
        PdfPageImageRenderer renderer = Mockito.mock(PdfPageImageRenderer.class);
        DocumentPageImageStore pageImages = Mockito.mock(DocumentPageImageStore.class);
        ProcessingProgressTracker progress = Mockito.mock(ProcessingProgressTracker.class);
        RuleStructureService structures = Mockito.mock(RuleStructureService.class);
        RuleChunkEmbeddingService embeddings = Mockito.mock(RuleChunkEmbeddingService.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        UploadedDocumentIngestion ingestion = new UploadedDocumentIngestion(
                documents, extractor, renderer, pageImages, progress, structures, embeddings, metrics);
        UUID versionId = UUID.randomUUID();
        when(documents.pages(versionId)).thenReturn(List.of(new DocumentProcessing.PageView(1, "Setup", 5)));

        ingestion.process(versionId, DocumentProcessingStage.CHUNK);

        verify(documents).markChunking(versionId);
        verifyNoInteractions(extractor, renderer, pageImages, structures, embeddings);
    }

    private ExtractedPage page(int pageNumber, String text) {
        return new ExtractedPage(pageNumber, text, List.of(
                new ExtractedTextBlock(0, text, 100, 120, 240, 40)));
    }
}
