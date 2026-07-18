package com.rulepilot.ingestion.adapter.out.pdf;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

class PdfBoxPageExtractorTest {

    @Test
    void rejectsDocumentsOverThePageLimit() throws IOException {
        byte[] pdf = pdfWithPages(2);

        assertThatThrownBy(() -> new PdfBoxPageExtractor(1, 10_000).extract(new ByteArrayInputStream(pdf)))
                .isInstanceOf(PdfExtractionException.class)
                .hasMessage("PDF exceeds the configured page limit");
    }

    @Test
    void rejectsPdfOpenActionsBeforeTextExtraction() throws IOException {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.getDocumentCatalog().getCOSObject().setItem(COSName.OPEN_ACTION, new COSDictionary());
            document.save(output);
            pdf = output.toByteArray();
        }

        assertThatThrownBy(() -> new PdfBoxPageExtractor(10, 10_000).extract(new ByteArrayInputStream(pdf)))
                .isInstanceOf(PdfExtractionException.class)
                .hasMessage("PDF contains active or embedded content");
    }

    private byte[] pdfWithPages(int pages) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int page = 0; page < pages; page++) {
                document.addPage(new PDPage());
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
