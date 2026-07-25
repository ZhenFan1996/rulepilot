package com.rulepilot.ingestion.adapter.out.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.document.DocumentPageImageStore.RenderedPageImage;
import com.rulepilot.document.DocumentProcessing.ExtractedPage;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class PdfBoxRulebookPreparationTest {

    @Test
    void rejectsDocumentsOverThePageLimitBeforeCallingConsumers() throws IOException {
        var preparation = preparation(1, 10_000);

        assertThatThrownBy(() -> preparation.prepare(chunked(pdfWithPages(2)), ignored -> {}, ignored -> {}))
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
        List<ExtractedPage> pages = new ArrayList<>();

        preparation(10, 10_000).prepare(chunked(pdf), pages::addAll, ignored -> {});

        assertThat(pages).hasSize(1);
    }

    @Test
    void rejectsJavaScriptOpenActionBeforeTextExtractionOrRendering() throws IOException {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            COSDictionary openAction = new COSDictionary();
            openAction.setName(COSName.S, "JavaScript");
            document.getDocumentCatalog().getCOSObject().setItem(COSName.OPEN_ACTION, openAction);
            document.save(output);
            pdf = output.toByteArray();
        }

        assertThatThrownBy(() -> preparation(10, 10_000).prepare(chunked(pdf), ignored -> {}, ignored -> {}))
                .isInstanceOf(PdfExtractionException.class)
                .hasMessage("PDF contains active or embedded content");
    }

    @Test
    void extractsPositionedTextBeforeRenderingReadableBoundedJpegs() throws IOException {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(
                        new PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                content.newLineAtOffset(72, 720);
                content.showText("SETUP");
                content.endText();
                content.setNonStrokingColor(Color.BLUE);
                content.addRect(40, 40, 200, 100);
                content.fill();
            }
            document.save(output);
            pdf = output.toByteArray();
        }
        List<String> callbacks = new ArrayList<>();
        List<ExtractedPage> pages = new ArrayList<>();
        List<RenderedPageImage> rendered = new ArrayList<>();

        preparation(10, 10_000).prepare(
                chunked(pdf),
                extracted -> {
                    callbacks.add("pages");
                    pages.addAll(extracted);
                },
                image -> {
                    callbacks.add("image");
                    rendered.add(image);
                });

        assertThat(callbacks).containsExactly("pages", "image");
        assertThat(pages).singleElement().satisfies(page -> {
            assertThat(page.text()).contains("SETUP");
            assertThat(page.textBlocks()).singleElement().satisfies(block -> {
                assertThat(block.text()).isEqualTo("SETUP");
                assertThat(block.x()).isBetween(1, 999);
                assertThat(block.y()).isBetween(1, 999);
                assertThat(block.width()).isPositive();
                assertThat(block.height()).isPositive();
            });
        });
        assertThat(rendered).singleElement().satisfies(image -> {
            assertThat(image.pageNumber()).isEqualTo(1);
            assertThat(image.content().length).isGreaterThan(1_000);
            BufferedImage decoded = read(image.content());
            assertThat(decoded.getWidth()).isEqualTo(image.width());
            assertThat(decoded.getHeight()).isEqualTo(image.height());
            assertThat(decoded.getWidth()).isGreaterThan(1_600);
            assertThat(decoded.getHeight()).isGreaterThan(2_000);
        });
    }

    @Test
    void keepsTextAndPositionedBlocksBoundToTheirOriginalPagesInOnePreparation() throws IOException {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            addTextPage(document, "SETUP");
            addTextPage(document, "SCORING");
            document.save(output);
            pdf = output.toByteArray();
        }
        List<ExtractedPage> pages = new ArrayList<>();

        preparation(10, 10_000).prepare(chunked(pdf), pages::addAll, ignored -> {});

        assertThat(pages).extracting(ExtractedPage::text).containsExactly("SETUP", "SCORING");
        assertThat(pages.getFirst().textBlocks()).extracting(block -> block.text()).containsExactly("SETUP");
        assertThat(pages.get(1).textBlocks()).extracting(block -> block.text()).containsExactly("SCORING");
    }

    @Test
    void keepsSubsamplingEnabledAtTheEvidenceResolution() throws IOException {
        try (PDDocument document = new PDDocument()) {
            assertThat(PdfBoxRulebookPreparation.configuredRenderer(document).isSubsamplingAllowed())
                    .isTrue();
        }
    }

    @Test
    void rendersEveryPageInOrderWhenTheEvidenceSessionIsBounded() throws IOException {
        List<Integer> renderedPageNumbers = new ArrayList<>();

        new PdfBoxRulebookPreparation(10, 10_000, 2)
                .prepare(chunked(pdfWithPages(5)), ignored -> {}, image -> renderedPageNumbers.add(image.pageNumber()));

        assertThat(renderedPageNumbers).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void rendersReadableJpegsWithPopplerWhenTheRuntimeProvidesIt() throws IOException {
        Assumptions.assumeTrue(popplerAvailable());
        List<RenderedPageImage> rendered = new ArrayList<>();

        new PdfBoxRulebookPreparation(10, 10_000, 2, "poppler")
                .prepare(chunked(pdfWithPages(2)), ignored -> {}, rendered::add);

        assertThat(rendered).extracting(RenderedPageImage::pageNumber).containsExactly(1, 2);
        assertThat(rendered).allSatisfy(image -> {
            assertThat(image.content()).startsWith((byte) 0xFF, (byte) 0xD8);
            assertThat(image.width()).isGreaterThan(1_600);
            assertThat(image.height()).isGreaterThan(2_000);
        });
    }

    private PdfBoxRulebookPreparation preparation(int maxPages, int maxExtractedCharacters) {
        return new PdfBoxRulebookPreparation(maxPages, maxExtractedCharacters, 4);
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

    private void addTextPage(PDDocument document, String text) throws IOException {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            stream.beginText();
            stream.setFont(new PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_BOLD), 14);
            stream.newLineAtOffset(72, 720);
            stream.showText(text);
            stream.endText();
        }
    }

    private BufferedImage read(byte[] content) {
        try {
            return ImageIO.read(new ByteArrayInputStream(content));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private InputStream chunked(byte[] content) {
        return new FilterInputStream(new ByteArrayInputStream(content)) {
            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                return super.read(buffer, offset, Math.min(length, 17));
            }
        };
    }

    private boolean popplerAvailable() {
        try {
            Process process = new ProcessBuilder("pdftoppm", "-v")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor() == 0;
        } catch (IOException unavailable) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
