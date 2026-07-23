package com.rulepilot.document.adapter.out.pdf;

import com.rulepilot.document.application.PhotographedRulebookAssembler;
import com.rulepilot.document.application.PhotographedRulebookUploadService.PhotoPage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

@Component
public class PdfBoxPhotographedRulebookAssembler implements PhotographedRulebookAssembler {

    private static final int MINIMUM_EDGE_PIXELS = 320;
    private static final long MAXIMUM_PIXELS = 24_000_000L;
    private static final float PAGE_MARGIN = 18f;

    @Override
    public AssembledRulebook assemble(List<PhotoPage> pages) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (PhotoPage photographedPage : pages) {
                addPage(document, photographedPage);
            }
            document.save(output);
            return new AssembledRulebook("photographed-rulebook.pdf", output.toByteArray());
        } catch (IOException exception) {
            throw new IllegalArgumentException("could not read one of the photographed rulebook pages", exception);
        }
    }

    private void addPage(PDDocument document, PhotoPage photographedPage) throws IOException {
        BufferedImage source = ImageIO.read(new java.io.ByteArrayInputStream(photographedPage.content()));
        if (source == null) {
            throw new IllegalArgumentException("photographed page is not a readable image");
        }
        if (source.getWidth() < MINIMUM_EDGE_PIXELS || source.getHeight() < MINIMUM_EDGE_PIXELS
                || (long) source.getWidth() * source.getHeight() > MAXIMUM_PIXELS) {
            throw new IllegalArgumentException("photographed page resolution is not suitable for reading");
        }

        PDImageXObject image = "image/jpeg".equalsIgnoreCase(photographedPage.contentType())
                ? JPEGFactory.createFromByteArray(document, photographedPage.content())
                : LosslessFactory.createFromImage(document, source);
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        float availableWidth = page.getMediaBox().getWidth() - PAGE_MARGIN * 2;
        float availableHeight = page.getMediaBox().getHeight() - PAGE_MARGIN * 2;
        float scale = Math.min(availableWidth / image.getWidth(), availableHeight / image.getHeight());
        float width = image.getWidth() * scale;
        float height = image.getHeight() * scale;
        float x = (page.getMediaBox().getWidth() - width) / 2;
        float y = (page.getMediaBox().getHeight() - height) / 2;
        try (PDPageContentStream canvas = new PDPageContentStream(document, page)) {
            canvas.drawImage(image, x, y, width, height);
        }
    }
}
