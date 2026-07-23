package com.rulepilot.document.adapter.out.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.document.application.PhotographedRulebookUploadService.PhotoPage;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

class PdfBoxPhotographedRulebookAssemblerTest {

    private final PdfBoxPhotographedRulebookAssembler assembler = new PdfBoxPhotographedRulebookAssembler();

    @Test
    void preservesPhotographedPageOrderInsideThePdfSource() throws Exception {
        var assembled = assembler.assemble(List.of(
                new PhotoPage("cover.jpg", "image/jpeg", image("jpg", Color.RED)),
                new PhotoPage("rules.png", "image/png", image("png", Color.BLUE))));

        assertThat(assembled.originalFilename()).isEqualTo("photographed-rulebook.pdf");
        assertThat(assembled.pdf()).startsWith("%PDF-".getBytes());
        try (PDDocument document = Loader.loadPDF(assembled.pdf())) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
            assertThat(document.getPage(0).getResources().getXObjectNames()).isNotEmpty();
            assertThat(document.getPage(1).getResources().getXObjectNames()).isNotEmpty();
        }
    }

    @Test
    void rejectsAPhotoThatCannotBeReadAsAnImage() {
        assertThatThrownBy(() -> assembler.assemble(List.of(
                        new PhotoPage("not-a-page.jpg", "image/jpeg", "not an image".getBytes()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("photographed page is not a readable image");
    }

    private byte[] image(String format, Color color) throws Exception {
        BufferedImage image = new BufferedImage(800, 1000, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }
}
