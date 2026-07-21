package com.rulepilot.ingestion.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.document.DocumentPageImageStore;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentProcessing.ExtractedPage;
import com.rulepilot.document.DocumentProcessing.ExtractedTextBlock;
import com.rulepilot.document.DocumentProcessingStage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class UploadedDocumentIngestionTest {

    @Test
    void rebuildsStructureFromSourcePagesSoLayoutBlocksSurviveTheChunkStage() {
        DocumentProcessing documents = Mockito.mock(DocumentProcessing.class);
        PdfPageExtractor extractor = Mockito.mock(PdfPageExtractor.class);
        PdfPageImageRenderer renderer = Mockito.mock(PdfPageImageRenderer.class);
        DocumentPageImageStore pageImages = Mockito.mock(DocumentPageImageStore.class);
        ProcessingProgressTracker progress = Mockito.mock(ProcessingProgressTracker.class);
        RuleStructureService structures = Mockito.mock(RuleStructureService.class);
        RuleChunkEmbeddingService embeddings = Mockito.mock(RuleChunkEmbeddingService.class);
        UploadedDocumentIngestion ingestion = new UploadedDocumentIngestion(
                documents, extractor, renderer, pageImages, progress, structures, embeddings);
        UUID versionId = UUID.randomUUID();
        List<ExtractedPage> sourcePages = List.of(new ExtractedPage(1, "Setup", List.of(
                new ExtractedTextBlock(0, "Setup", 100, 120, 240, 40))));

        when(documents.pages(versionId)).thenReturn(List.of(new DocumentProcessing.PageView(1, "Setup", 5)));
        when(documents.open(versionId)).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(extractor.extract(any())).thenReturn(sourcePages);

        ingestion.process(versionId, DocumentProcessingStage.CHUNK);

        verify(documents).markStructuring(versionId);
        verify(extractor).extract(any());
        verify(structures).organize(versionId, sourcePages);
        verify(documents).markChunking(versionId);
    }
}
