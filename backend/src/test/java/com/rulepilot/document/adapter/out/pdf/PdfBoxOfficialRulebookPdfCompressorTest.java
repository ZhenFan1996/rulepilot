package com.rulepilot.document.adapter.out.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class PdfBoxOfficialRulebookPdfCompressorTest {

    @Test
    void compressesLargePageImagesWhilePreservingPageCountAndSearchableRuleText() throws Exception {
        byte[] source = imageHeavyPdf();
        long maximumBytes = 2_500_000;
        assertThat(source.length).isGreaterThan(Math.toIntExact(maximumBytes));
        var compressor = new PdfBoxOfficialRulebookPdfCompressor();

        byte[] compressed = compressor.compress(source, maximumBytes);

        assertThat(compressed.length).isBetween(1, Math.toIntExact(maximumBytes));
        assertThat(compressed.length).isLessThan(source.length);
        assertThat(new String(compressed, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (PDDocument document = Loader.loadPDF(compressed)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(new PDFTextStripper().getText(document)).contains("Compression keeps this rule text searchable");
        }
    }

    @Test
    void rejectsAPdfThatCannotFitTheBoundedOutputEvenAfterAllPasses() throws Exception {
        byte[] source = textPdf();
        var compressor = new PdfBoxOfficialRulebookPdfCompressor();

        assertThatThrownBy(() -> compressor.compress(source, 128))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("could not be compressed");
    }

    private byte[] imageHeavyPdf() throws Exception {
        var image = new BufferedImage(1_800, 1_800, BufferedImage.TYPE_INT_RGB);
        var random = new java.util.SplittableRandom(42);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, random.nextInt(0x0100_0000));
            }
        }
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            var pageImage = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream canvas = new PDPageContentStream(document, page)) {
                canvas.drawImage(pageImage, 18, 18, 559, 805);
                canvas.beginText();
                canvas.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                canvas.newLineAtOffset(24, 24);
                canvas.showText("Compression keeps this rule text searchable");
                canvas.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] textPdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream canvas = new PDPageContentStream(document, page)) {
                canvas.beginText();
                canvas.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                canvas.newLineAtOffset(72, 720);
                canvas.showText("A compact rules page still has structural PDF overhead.");
                canvas.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
