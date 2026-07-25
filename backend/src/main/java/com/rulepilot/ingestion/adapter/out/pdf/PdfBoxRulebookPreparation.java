package com.rulepilot.ingestion.adapter.out.pdf;

import com.rulepilot.document.DocumentPageImageStore.RenderedPageImage;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.ingestion.application.PdfRulebookPreparation;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * PDFBox adapter that stages a rulebook once, then uses short-lived extraction and rendering sessions over that local
 * source.
 */
@Component
public class PdfBoxRulebookPreparation implements PdfRulebookPreparation {

    private static final Set<COSName> ACTIVE_CONTENT_KEYS = Set.of(
            COSName.AA,
            COSName.JS,
            COSName.JAVA_SCRIPT,
            COSName.EMBEDDED_FILE,
            COSName.EMBEDDED_FILES);
    private static final Set<COSName> ACTIVE_CONTENT_TYPES = Set.of(
            COSName.getPDFName("Launch"),
            COSName.getPDFName("JavaScript"),
            COSName.getPDFName("RichMedia"),
            COSName.getPDFName("FileAttachment"));
    // Small inline resource icons are rule-bearing evidence. At 120 DPI they can collapse into nearby bullet marks
    // on compact publisher page sizes. 170 DPI was visually checked against 200 DPI on icon-dense setup and scoring
    // pages; it retains those rule-bearing marks while rendering 28% fewer pixels on the constrained Worker.
    private static final float RENDER_DPI = 170;
    private static final float JPEG_QUALITY = 0.90f;

    private final int maxPages;
    private final int maxExtractedCharacters;

    public PdfBoxRulebookPreparation(
            @Value("${rulepilot.document.max-pdf-pages:500}") int maxPages,
            @Value("${rulepilot.document.max-extracted-characters:5000000}") int maxExtractedCharacters) {
        if (maxPages < 1 || maxExtractedCharacters < 1) {
            throw new IllegalArgumentException("PDF extraction limits must be positive");
        }
        this.maxPages = maxPages;
        this.maxExtractedCharacters = maxExtractedCharacters;
    }

    @Override
    public void prepare(
            InputStream input,
            Consumer<List<DocumentProcessing.ExtractedPage>> extractedPagesConsumer,
            Consumer<RenderedPageImage> pageImageConsumer) {
        if (input == null || extractedPagesConsumer == null || pageImageConsumer == null) {
            throw new IllegalArgumentException("PDF input and page consumers are required");
        }
        Path temporaryPdf = null;
        try (input) {
            temporaryPdf = Files.createTempFile("rulepilot-prepare-", ".pdf");
            Files.copy(input, temporaryPdf, StandardCopyOption.REPLACE_EXISTING);
            List<DocumentProcessing.ExtractedPage> extractedPages;
            try (PDDocument document = Loader.loadPDF(temporaryPdf.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
                validateDocument(document, maxPages);
                extractedPages = extractPages(document, maxExtractedCharacters);
            }
            extractedPagesConsumer.accept(extractedPages);
            // Text extraction warms PDFBox resource caches across the whole rulebook. Closing that session before
            // 170-DPI rendering keeps the one-core / 560 MiB Worker from retaining both cache populations at once.
            try (PDDocument document = Loader.loadPDF(temporaryPdf.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
                if (document.getNumberOfPages() > maxPages) {
                    throw new PdfExtractionException("PDF exceeds the configured page limit");
                }
                renderPages(document, pageImageConsumer);
            }
        } catch (PdfExtractionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new PdfExtractionException("could not prepare PDF pages", exception);
        } finally {
            deleteTemporaryPdf(temporaryPdf);
        }
    }

    static void validateDocument(PDDocument document, int maxPages) {
        if (document.getNumberOfPages() > maxPages) {
            throw new PdfExtractionException("PDF exceeds the configured page limit");
        }
        rejectActiveContent(document.getDocument().getTrailer());
    }

    static List<DocumentProcessing.ExtractedPage> extractPages(PDDocument document, int maxExtractedCharacters)
            throws IOException {
        LayoutTextStripper stripper = new LayoutTextStripper();
        stripper.setSortByPosition(true);
        // PDFTextStripper initializes document and writer state through getText. LayoutTextStripper captures each
        // page's writer output itself, so this traverses the PDF page tree once instead of once per page.
        stripper.getText(document);
        List<DocumentProcessing.ExtractedPage> pages = new ArrayList<>(document.getNumberOfPages());
        int extractedCharacters = 0;
        for (int pageNumber = 1; pageNumber <= document.getNumberOfPages(); pageNumber++) {
            String text = stripper.capturedText(pageNumber).replace("\r\n", "\n").strip();
            extractedCharacters = Math.addExact(extractedCharacters, text.length());
            if (extractedCharacters > maxExtractedCharacters) {
                throw new PdfExtractionException("PDF exceeds the configured extracted-text limit");
            }
            pages.add(new DocumentProcessing.ExtractedPage(pageNumber, text, stripper.capturedBlocks(pageNumber)));
        }
        return List.copyOf(pages);
    }

    private void renderPages(PDDocument document, Consumer<RenderedPageImage> pageImageConsumer) throws IOException {
        PDFRenderer renderer = configuredRenderer(document);
        for (int index = 0; index < document.getNumberOfPages(); index++) {
            BufferedImage image = renderer.renderImageWithDPI(index, RENDER_DPI, ImageType.RGB);
            try {
                pageImageConsumer.accept(new RenderedPageImage(
                        index + 1, encodeJpeg(image), image.getWidth(), image.getHeight()));
            } finally {
                image.flush();
            }
        }
    }

    private static void rejectActiveContent(COSBase root) {
        Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<COSBase> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            COSBase value = pending.removeFirst();
            if (!visited.add(value)) {
                continue;
            }
            if (value instanceof COSObject object) {
                if (object.getObject() != null) {
                    pending.add(object.getObject());
                }
            } else if (value instanceof COSArray array) {
                array.forEach(item -> {
                    if (item != null) {
                        pending.add(item);
                    }
                });
            } else if (value instanceof COSDictionary dictionary) {
                dictionary.forEach((key, item) -> {
                    if (ACTIVE_CONTENT_KEYS.contains(key)
                            || (item instanceof COSName name && ACTIVE_CONTENT_TYPES.contains(name))) {
                        throw new PdfExtractionException("PDF contains active or embedded content");
                    }
                    if (item != null) {
                        pending.add(item);
                    }
                });
            }
        }
    }

    private void deleteTemporaryPdf(Path temporaryPdf) {
        if (temporaryPdf == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryPdf);
        } catch (IOException exception) {
            temporaryPdf.toFile().deleteOnExit();
        }
    }

    static PDFRenderer configuredRenderer(PDDocument document) {
        PDFRenderer renderer = new PDFRenderer(document);
        // Rulebooks commonly embed print-resolution artwork. PDFBox can decode only the pixels needed for the
        // requested 170 DPI output, preserving crop dimensions while avoiding needless memory and CPU work.
        renderer.setSubsamplingAllowed(true);
        return renderer;
    }

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG writer is unavailable");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), parameters);
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static final class LayoutTextStripper extends PDFTextStripper {

        private final List<DocumentProcessing.ExtractedTextBlock> blocks = new ArrayList<>();
        private final List<List<DocumentProcessing.ExtractedTextBlock>> blocksByPage = new ArrayList<>();
        private final List<String> textByPage = new ArrayList<>();
        private float pageWidth;
        private float pageHeight;

        private LayoutTextStripper() throws IOException {}

        @Override
        protected void startPage(PDPage page) throws IOException {
            super.startPage(page);
            pageWidth = page.getMediaBox().getWidth();
            pageHeight = page.getMediaBox().getHeight();
            blocks.clear();
        }

        @Override
        protected void writePage() throws IOException {
            var originalOutput = output;
            var pageOutput = new java.io.StringWriter();
            output = pageOutput;
            try {
                super.writePage();
                textByPage.add(pageOutput.toString());
            } finally {
                output = originalOutput;
            }
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            blocksByPage.add(List.copyOf(blocks));
            super.endPage(page);
        }

        String capturedText(int pageNumber) {
            return textByPage.get(pageNumber - 1);
        }

        List<DocumentProcessing.ExtractedTextBlock> capturedBlocks(int pageNumber) {
            return blocksByPage.get(pageNumber - 1);
        }

        @Override
        protected void writeString(String string, List<TextPosition> positions) throws IOException {
            super.writeString(string, positions);
            String text = string == null ? "" : string.replaceAll("\\s+", " ").strip();
            if (text.isEmpty() || positions == null || positions.isEmpty() || pageWidth <= 0 || pageHeight <= 0) {
                return;
            }

            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = 0;
            float maxY = 0;
            for (TextPosition position : positions) {
                minX = Math.min(minX, position.getXDirAdj());
                minY = Math.min(minY, position.getYDirAdj());
                maxX = Math.max(maxX, position.getXDirAdj() + position.getWidthDirAdj());
                maxY = Math.max(maxY, position.getYDirAdj() + position.getHeightDir());
            }
            int x = Math.min(999, normalized(minX, pageWidth));
            int y = Math.min(999, normalized(minY, pageHeight));
            int right = normalized(maxX, pageWidth);
            int bottom = normalized(maxY, pageHeight);
            int width = Math.max(1, right - x);
            int height = Math.max(1, bottom - y);
            if (x + width > 1_000) width = 1_000 - x;
            if (y + height > 1_000) height = 1_000 - y;
            blocks.add(new DocumentProcessing.ExtractedTextBlock(blocks.size(), text, x, y, width, height));
        }

        private int normalized(float value, float dimension) {
            return Math.max(0, Math.min(1_000, Math.round(value * 1_000 / dimension)));
        }
    }
}
