package com.rulepilot.ingestion.adapter.out.pdf;

import com.rulepilot.document.DocumentPageImageStore.RenderedPageImage;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.ingestion.application.PdfRulebookPreparation;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.ImageInputStream;
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
import org.springframework.beans.factory.annotation.Autowired;
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
    // on compact publisher page sizes, which makes both visual models and reader crops unreliable.
    private static final float RENDER_DPI = 200;
    private static final float JPEG_QUALITY = 0.90f;
    private static final Pattern POPPLER_PROGRESS = Pattern.compile("^([0-9]+)\\s+([0-9]+)\\s+(.+)$");
    static final int MAX_RENDER_SESSION_PAGES = 8;

    private final int maxPages;
    private final int maxExtractedCharacters;
    private final int renderSessionPages;
    private final VisualRenderer visualRenderer;
    private final boolean streamCompletedPopplerPages;

    @Autowired
    public PdfBoxRulebookPreparation(
            @Value("${rulepilot.document.max-pdf-pages:500}") int maxPages,
            @Value("${rulepilot.document.max-extracted-characters:5000000}") int maxExtractedCharacters,
            @Value("${rulepilot.document.render-session-pages:8}") int renderSessionPages,
            @Value("${rulepilot.document.visual-renderer:poppler}") String visualRenderer) {
        this(maxPages, maxExtractedCharacters, renderSessionPages, visualRenderer, true);
    }

    PdfBoxRulebookPreparation(
            int maxPages,
            int maxExtractedCharacters,
            int renderSessionPages,
            String visualRenderer,
            boolean streamCompletedPopplerPages) {
        if (maxPages < 1 || maxExtractedCharacters < 1
                || renderSessionPages < 1 || renderSessionPages > MAX_RENDER_SESSION_PAGES) {
            throw new IllegalArgumentException(
                    "PDF extraction limits must be positive and render session size must be 1-8");
        }
        this.maxPages = maxPages;
        this.maxExtractedCharacters = maxExtractedCharacters;
        this.renderSessionPages = renderSessionPages;
        this.visualRenderer = VisualRenderer.from(visualRenderer);
        this.streamCompletedPopplerPages = streamCompletedPopplerPages;
    }

    PdfBoxRulebookPreparation(int maxPages, int maxExtractedCharacters, int renderSessionPages) {
        this(maxPages, maxExtractedCharacters, renderSessionPages, "pdfbox");
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
            int expectedPageCount;
            try (PDDocument document = Loader.loadPDF(temporaryPdf.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
                validateDocument(document, maxPages);
                extractedPages = extractPages(document, maxExtractedCharacters);
                expectedPageCount = extractedPages.size();
            }
            extractedPagesConsumer.accept(extractedPages);
            // The consumer has durably stored page text and derived structure. Do not keep its page/block graph
            // reachable while PDFBox allocates visual evidence; that graph can dominate the Worker heap.
            extractedPages = null;
            // PDFBox retains decoded resources in a document session. Rendering short page batches prevents an
            // illustrated rulebook from accumulating every prior spread's artwork in the constrained Worker heap.
            renderPages(temporaryPdf, expectedPageCount, pageImageConsumer);
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

    private void renderPages(
            Path temporaryPdf, int expectedPageCount, Consumer<RenderedPageImage> pageImageConsumer) throws IOException {
        if (visualRenderer == VisualRenderer.POPPLER) {
            renderPagesWithPoppler(temporaryPdf, expectedPageCount, pageImageConsumer);
            return;
        }
        renderPagesWithPdfBox(temporaryPdf, expectedPageCount, pageImageConsumer);
    }

    private void renderPagesWithPdfBox(
            Path temporaryPdf, int expectedPageCount, Consumer<RenderedPageImage> pageImageConsumer) throws IOException {
        for (int batchStart = 0; batchStart < expectedPageCount; batchStart += renderSessionPages) {
            try (PDDocument document = Loader.loadPDF(temporaryPdf.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
                if (document.getNumberOfPages() != expectedPageCount) {
                    throw new PdfExtractionException("PDF page count changed while preparing visual evidence");
                }
                PDFRenderer renderer = configuredRenderer(document);
                int batchEnd = Math.min(batchStart + renderSessionPages, expectedPageCount);
                for (int index = batchStart; index < batchEnd; index++) {
                    BufferedImage image = renderer.renderImageWithDPI(index, RENDER_DPI, ImageType.RGB);
                    try {
                        pageImageConsumer.accept(new RenderedPageImage(
                                index + 1, encodeJpeg(image), image.getWidth(), image.getHeight()));
                    } finally {
                        image.flush();
                    }
                }
            }
        }
    }

    private void renderPagesWithPoppler(
            Path temporaryPdf, int expectedPageCount, Consumer<RenderedPageImage> pageImageConsumer) throws IOException {
        Path outputDirectory = Files.createTempDirectory("rulepilot-poppler-render-");
        try {
            for (int firstPage = 1; firstPage <= expectedPageCount; firstPage += renderSessionPages) {
                int lastPage = Math.min(firstPage + renderSessionPages - 1, expectedPageCount);
                if (streamCompletedPopplerPages) {
                    renderPopplerSession(
                            temporaryPdf, outputDirectory, firstPage, lastPage, pageImageConsumer);
                } else {
                    renderPopplerBatch(temporaryPdf, outputDirectory, firstPage, lastPage, pageImageConsumer);
                }
            }
        } finally {
            deleteTemporaryDirectory(outputDirectory);
        }
    }

    private void renderPopplerSession(
            Path pdf,
            Path outputDirectory,
            int firstPage,
            int lastPage,
            Consumer<RenderedPageImage> pageImageConsumer) throws IOException {
        Process process = startPopplerRender(pdf, outputDirectory, firstPage, lastPage, true);
        int emittedPages = firstPage - 1;
        try (BufferedReader progress = new BufferedReader(
                new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = progress.readLine()) != null) {
                OptionalInt completedPage = completedPopplerPage(line, firstPage, lastPage);
                if (completedPage.isEmpty()) continue;
                int pageNumber = completedPage.orElseThrow();
                if (pageNumber != emittedPages + 1) {
                    throw new IOException("Poppler reported pages out of order");
                }
                emitPopplerPage(outputDirectory, pageNumber, pageImageConsumer);
                emittedPages = pageNumber;
            }
        } catch (RuntimeException | IOException | Error failure) {
            stopPoppler(process);
            throw failure;
        }
        int exitCode = waitForPoppler(process);
        if (exitCode != 0 || emittedPages != lastPage) {
            throw new IOException("Poppler could not render the complete rulebook session");
        }
    }

    private void renderPopplerBatch(
            Path pdf,
            Path outputDirectory,
            int firstPage,
            int lastPage,
            Consumer<RenderedPageImage> pageImageConsumer) throws IOException {
        Process process = startPopplerRender(pdf, outputDirectory, firstPage, lastPage, false);
        if (waitForPoppler(process) != 0) {
            throw new IOException("Poppler could not render the rulebook pages");
        }
        for (int pageNumber = firstPage; pageNumber <= lastPage; pageNumber++) {
            emitPopplerPage(outputDirectory, pageNumber, pageImageConsumer);
        }
    }

    private Process startPopplerRender(
            Path pdf, Path outputDirectory, int firstPage, int lastPage, boolean reportProgress) throws IOException {
        List<String> command = new ArrayList<>(List.of(
                "pdftoppm",
                "-q",
                "-r", String.valueOf((int) RENDER_DPI),
                "-jpeg",
                "-jpegopt", "quality=" + Math.round(JPEG_QUALITY * 100),
                "-f", String.valueOf(firstPage),
                "-l", String.valueOf(lastPage)));
        if (reportProgress) {
            command.add("-progress");
        }
        command.add(pdf.toString());
        command.add(outputDirectory.resolve("page").toString());
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(reportProgress
                        ? ProcessBuilder.Redirect.PIPE
                        : ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    static OptionalInt completedPopplerPage(String progress, int firstPage, int lastPage) throws IOException {
        var frame = POPPLER_PROGRESS.matcher(progress == null ? "" : progress.strip());
        if (!frame.matches()) return OptionalInt.empty();
        try {
            int pageNumber = Integer.parseInt(frame.group(1));
            int reportedLastPage = Integer.parseInt(frame.group(2));
            if (pageNumber < firstPage || pageNumber > lastPage || reportedLastPage != lastPage) {
                throw new IOException("Poppler progress output is outside the expected page range");
            }
            return OptionalInt.of(pageNumber);
        } catch (NumberFormatException invalidProgress) {
            throw new IOException("Poppler progress output is invalid", invalidProgress);
        }
    }

    private void emitPopplerPage(
            Path outputDirectory, int pageNumber, Consumer<RenderedPageImage> pageImageConsumer) throws IOException {
        Path renderedPage = locatePopplerPage(outputDirectory, pageNumber);
        try {
            PageDimensions dimensions = jpegDimensions(renderedPage);
            pageImageConsumer.accept(new RenderedPageImage(
                    pageNumber, Files.readAllBytes(renderedPage), dimensions.width(), dimensions.height()));
        } finally {
            Files.deleteIfExists(renderedPage);
        }
    }

    private int waitForPoppler(Process process) throws IOException {
        try {
            return process.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Poppler rendering was interrupted", interrupted);
        }
    }

    private void stopPoppler(Process process) {
        process.destroyForcibly();
        try {
            process.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Path locatePopplerPage(Path outputDirectory, int pageNumber) throws IOException {
        try (Stream<Path> entries = Files.list(outputDirectory)) {
            return entries
                    .filter(path -> path.getFileName().toString().startsWith("page-"))
                    .filter(path -> path.getFileName().toString().endsWith(".jpg"))
                    .filter(path -> popplerPageNumber(path.getFileName().toString()) == pageNumber)
                    .findFirst()
                    .orElseThrow(() -> new IOException("Poppler did not create rendered page " + pageNumber));
        }
    }

    private int popplerPageNumber(String filename) {
        String page = filename.substring("page-".length(), filename.length() - ".jpg".length());
        try {
            return Integer.parseInt(page);
        } catch (NumberFormatException invalidFilename) {
            return -1;
        }
    }

    private PageDimensions jpegDimensions(Path renderedPage) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(renderedPage.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Poppler output is not a readable JPEG");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new PageDimensions(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }

    private void deleteTemporaryDirectory(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            entries.forEach(this::deleteTemporaryPath);
        } catch (IOException cleanupFailure) {
            directory.toFile().deleteOnExit();
            return;
        }
        try {
            Files.deleteIfExists(directory);
        } catch (IOException cleanupFailure) {
            directory.toFile().deleteOnExit();
        }
    }

    private void deleteTemporaryPath(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            path.toFile().deleteOnExit();
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
        // requested 200 DPI output, preserving crop dimensions while avoiding needless memory and CPU work.
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
            if (textByPage.size() == blocksByPage.size()) {
                textByPage.add("");
            }
            blocksByPage.add(List.copyOf(blocks));
            super.endPage(page);
        }

        String capturedText(int pageNumber) {
            return pageNumber <= textByPage.size() ? textByPage.get(pageNumber - 1) : "";
        }

        List<DocumentProcessing.ExtractedTextBlock> capturedBlocks(int pageNumber) {
            return pageNumber <= blocksByPage.size() ? blocksByPage.get(pageNumber - 1) : List.of();
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

    private record PageDimensions(int width, int height) {
        private PageDimensions {
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("rendered page dimensions are invalid");
            }
        }
    }

    private enum VisualRenderer {
        PDFBOX,
        POPPLER;

        private static VisualRenderer from(String configured) {
            try {
                return valueOf(configured.strip().toUpperCase(java.util.Locale.ROOT));
            } catch (RuntimeException invalidRenderer) {
                throw new IllegalArgumentException("rulebook visual renderer must be pdfbox or poppler", invalidRenderer);
            }
        }
    }
}
