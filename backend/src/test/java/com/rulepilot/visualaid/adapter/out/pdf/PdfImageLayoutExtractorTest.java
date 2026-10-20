package com.rulepilot.visualaid.adapter.out.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

class PdfImageLayoutExtractorTest {
    @Test void overlappingSourceObjectsStayTogetherWithoutMergingSeparateIllustrations() {
        var first = new com.rulepilot.visualaid.VisualRegionCatalog.Region(1, "PDF_IMAGE", 100, 100, 200, 300);
        var overlay = new com.rulepilot.visualaid.VisualRegionCatalog.Region(1, "PDF_IMAGE", 250, 120, 200, 300);
        var separate = new com.rulepilot.visualaid.VisualRegionCatalog.Region(1, "PDF_IMAGE", 600, 100, 200, 300);
        assertThat(PdfImageLayoutExtractor.completeCompositions(java.util.List.of(first, overlay, separate)))
                .containsExactly(new com.rulepilot.visualaid.VisualRegionCatalog.Region(1, "PDF_IMAGE", 100, 100, 350, 320), separate);
    }

    @Test void preservesEmbeddedImageBoundsOnCroppedAndRotatedPagesWithoutReturningBackgroundAsCrop() throws Exception {
        try (var pdf = new PDDocument()) {
            var page = new PDPage(new PDRectangle(400, 600));
            page.setCropBox(new PDRectangle(20, 40, 200, 400));
            page.setRotation(90);
            pdf.addPage(page);
            var image = LosslessFactory.createFromImage(pdf, new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB));
            try (var stream = new PDPageContentStream(pdf, page)) {
                stream.drawImage(image, 20, 40, 200, 400);
                stream.drawImage(image, 40, 80, 60, 100);
            }
            var bytes = new ByteArrayOutputStream(); pdf.save(bytes);
            var result = new PdfImageLayoutExtractor().extract(new ByteArrayInputStream(bytes.toByteArray()));
            assertThat(result.regions()).singleElement().satisfies(region -> {
                assertThat(region.x()).isBetween(99, 100); // outward rounding may include one normalized pixel
                assertThat(region.y()).isBetween(99, 100);
                assertThat(region.x() + region.width()).isBetween(350, 351);
                assertThat(region.y() + region.height()).isBetween(400, 401);
            });
        }
    }
}
