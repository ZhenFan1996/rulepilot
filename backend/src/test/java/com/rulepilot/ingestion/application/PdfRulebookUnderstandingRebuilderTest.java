package com.rulepilot.ingestion.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentProcessing.ExtractedPage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PdfRulebookUnderstandingRebuilderTest {

    @Test
    void recreatesStructureFromTheOriginalPdf() {
        DocumentProcessing documents = Mockito.mock(DocumentProcessing.class);
        PdfPageExtractor extractor = Mockito.mock(PdfPageExtractor.class);
        RuleStructureService structures = Mockito.mock(RuleStructureService.class);
        UUID versionId = UUID.randomUUID();
        List<ExtractedPage> pages = List.of(new ExtractedPage(1, "Setup"));
        when(documents.open(versionId)).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(extractor.extract(any())).thenReturn(pages);

        new PdfRulebookUnderstandingRebuilder(documents, extractor, structures).rebuild(versionId);

        verify(structures).organize(versionId, pages);
    }
}
