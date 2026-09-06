package com.rulepilot.visualaid.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.rulepilot.visualaid.VisualRegionCatalog.Region;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class VisualRegionServiceTest {
    @Test
    void refinesOriginalPixelsAndMapsChildrenInsideTheParentOnTheSamePage() throws Exception {
        var extractor = mock(VisualLayoutExtractor.class);
        when(extractor.extractImage(any(), any())).thenAnswer(call -> {
            BufferedImage input = ImageIO.read(new ByteArrayInputStream(call.getArgument(0)));
            assertThat(input.getWidth()).isEqualTo(200);
            assertThat(input.getHeight()).isEqualTo(300);
            assertThat(input.getRGB(0, 0)).isEqualTo(0xff123456);
            assertThat((Duration) call.getArgument(1)).isPositive().isLessThan(Duration.ofSeconds(30));
            return new VisualLayoutExtractor.Extraction("layout", 1, List.of(
                    new Region(1, "PICTURE", 100, 200, 500, 400),
                    new Region(1, "PICTURE", 0, 0, 50, 50)));
        });
        var page = new BufferedImage(500, 1000, BufferedImage.TYPE_INT_RGB);
        page.setRGB(100, 300, 0xff123456);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(page, "png", bytes);
        var service = new VisualRegionService(mock(VisualRegionIndex.class), extractor);
        assertThat(service.refine(new Region(7, "PICTURE", 200, 300, 400, 300),
                bytes.toByteArray(), Duration.ofSeconds(30)))
                .containsExactly(new Region(7, "PICTURE", 240, 360, 200, 120));
    }
}
