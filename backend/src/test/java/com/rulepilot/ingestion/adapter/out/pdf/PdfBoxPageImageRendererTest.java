package com.rulepilot.ingestion.adapter.out.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.junit.jupiter.api.Test;

class PdfBoxPageImageRendererTest {

    @Test
    void rendersEachPdfPageAsAReadableBoundedJpeg() throws IOException {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(Color.BLUE);
                content.addRect(40, 40, 200, 100);
                content.fill();
            }
            document.save(output);
            pdf = output.toByteArray();
        }
        var rendered = new ArrayList<com.rulepilot.document.DocumentPageImageStore.RenderedPageImage>();

        int count = new PdfBoxPageImageRenderer(10).render(chunked(pdf), rendered::add);

        assertThat(count).isEqualTo(1);
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
}
