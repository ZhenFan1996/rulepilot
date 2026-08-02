package com.rulepilot.document.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.document.DocumentPageImages.PageImage;
import com.rulepilot.document.DocumentProcessing;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class DocumentPageControllerTest {

    private final DocumentProcessing documents = mock(DocumentProcessing.class);
    private final DocumentPageImages pageImages = mock(DocumentPageImages.class);
    private final DocumentPageController controller =
            new DocumentPageController(documents, pageImages, new RulePageImageCropper());

    @Test
    void servesTheWholeEvidencePageAsANormalizedBrowserSafeJpeg() throws Exception {
        UUID versionId = UUID.randomUUID();
        BufferedImage source = new BufferedImage(80, 120, BufferedImage.TYPE_4BYTE_ABGR);
        var graphics = source.createGraphics();
        graphics.setColor(new Color(20, 40, 80, 180));
        graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
        graphics.dispose();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "png", encoded);
        when(pageImages.read(versionId, Set.of(1)))
                .thenReturn(List.of(new PageImage(1, "image/png", encoded.toByteArray(), 80, 120)));

        var response = controller.pageImage(versionId, 1);

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(response.getBody()));
        assertThat(decoded.getWidth()).isEqualTo(80);
        assertThat(decoded.getHeight()).isEqualTo(120);
        assertThat(decoded.getColorModel().hasAlpha()).isFalse();
    }
}
