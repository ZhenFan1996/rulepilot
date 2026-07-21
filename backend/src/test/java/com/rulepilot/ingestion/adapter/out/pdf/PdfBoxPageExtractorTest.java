package com.rulepilot.ingestion.adapter.out.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
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
    void allowsPassiveGoToOpenAction() throws IOException {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            COSDictionary openAction = new COSDictionary();
            openAction.setName(COSName.S, "GoTo");
            document.getDocumentCatalog().getCOSObject().setItem(COSName.OPEN_ACTION, openAction);
            document.save(output);
            pdf = output.toByteArray();
        }

        assertThat(new PdfBoxPageExtractor(10, 10_000).extract(new ByteArrayInputStream(pdf)))
                .hasSize(1);
    }

    @Test
    void rejectsJavaScriptOpenActionBeforeTextExtraction() throws IOException {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            COSDictionary openAction = new COSDictionary();
            openAction.setName(COSName.S, "JavaScript");
            document.getDocumentCatalog().getCOSObject().setItem(COSName.OPEN_ACTION, openAction);
            document.save(output);
            pdf = output.toByteArray();
        }

        assertThatThrownBy(() -> new PdfBoxPageExtractor(10, 10_000).extract(new ByteArrayInputStream(pdf)))
                .isInstanceOf(PdfExtractionException.class)
                .hasMessage("PDF contains active or embedded content");
    }

    @Test
    void capturesPositionedTextBlocksInPageRelativeCoordinates() throws IOException {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                stream.newLineAtOffset(72, 720);
                stream.showText("SETUP");
                stream.endText();
            }
            document.save(output);
            pdf = output.toByteArray();
        }

        var page = new PdfBoxPageExtractor(10, 10_000).extract(new ByteArrayInputStream(pdf)).getFirst();

        assertThat(page.text()).contains("SETUP");
        assertThat(page.textBlocks()).singleElement().satisfies(block -> {
            assertThat(block.text()).isEqualTo("SETUP");
            assertThat(block.x()).isBetween(1, 999);
            assertThat(block.y()).isBetween(1, 999);
            assertThat(block.width()).isPositive();
            assertThat(block.height()).isPositive();
        });
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
