package com.rulepilot.ingestion.adapter.out.pdf;

import com.rulepilot.document.DocumentPageImageStore.RenderedPageImage;
import com.rulepilot.ingestion.application.PdfPageImageRenderer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.function.Consumer;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PdfBoxPageImageRenderer implements PdfPageImageRenderer {

    // Small inline resource icons are rule-bearing evidence. At 120 DPI they can collapse into nearby bullet marks
    // on compact publisher page sizes, which makes both visual models and reader crops unreliable.
    private static final float RENDER_DPI = 200;
    private static final float JPEG_QUALITY = 0.90f;

    private final int maxPages;

    public PdfBoxPageImageRenderer(@Value("${rulepilot.document.max-pdf-pages:500}") int maxPages) {
        this.maxPages = maxPages;
    }

    @Override
    public int render(InputStream input, Consumer<RenderedPageImage> pageConsumer) {
        if (input == null || pageConsumer == null) {
            throw new IllegalArgumentException("PDF input and page image consumer are required");
        }
        Path temporaryPdf = null;
        try (input) {
            temporaryPdf = Files.createTempFile("rulepilot-render-", ".pdf");
            Files.copy(input, temporaryPdf, StandardCopyOption.REPLACE_EXISTING);
            try (PDDocument document = Loader.loadPDF(temporaryPdf.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
                if (document.getNumberOfPages() > maxPages) {
                    throw new PdfExtractionException("PDF exceeds the configured page limit");
                }
                PDFRenderer renderer = new PDFRenderer(document);
                for (int index = 0; index < document.getNumberOfPages(); index++) {
                    BufferedImage image = renderer.renderImageWithDPI(index, RENDER_DPI, ImageType.RGB);
                    try {
                        pageConsumer.accept(new RenderedPageImage(
                                index + 1, encodeJpeg(image), image.getWidth(), image.getHeight()));
                    } finally {
                        image.flush();
                    }
                }
                return document.getNumberOfPages();
            }
        } catch (PdfExtractionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new PdfExtractionException("could not render PDF page images", exception);
        } finally {
            deleteTemporaryPdf(temporaryPdf);
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
}
