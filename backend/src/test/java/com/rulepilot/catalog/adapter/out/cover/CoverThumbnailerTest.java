package com.rulepilot.catalog.adapter.out.cover;

import static com.rulepilot.catalog.adapter.out.cover.DurableCoverThumbnailService.Profile.COMPACT_PROFILE;
import static com.rulepilot.catalog.adapter.out.cover.DurableCoverThumbnailService.Profile.DISPLAY_PROFILE;
import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.catalog.adapter.out.cover.CoverThumbnailCache.Thumbnail;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class CoverThumbnailerTest {

    @Test
    void createsDistinctBoundedCompactAndDisplayProfiles() throws Exception {
        BufferedImage sourceImage = new BufferedImage(1_200, 1_600, BufferedImage.TYPE_INT_RGB);
        var graphics = sourceImage.createGraphics();
        graphics.setColor(new Color(31, 79, 121));
        graphics.fillRect(0, 0, sourceImage.getWidth(), sourceImage.getHeight());
        graphics.dispose();
        var source = new ByteArrayOutputStream();
        ImageIO.write(sourceImage, "png", source);

        Thumbnail compact = new CoverThumbnailer().create(source.toByteArray(), COMPACT_PROFILE);
        Thumbnail display = new CoverThumbnailer().create(source.toByteArray(), DISPLAY_PROFILE);
        BufferedImage compactImage = ImageIO.read(new ByteArrayInputStream(compact.content()));
        BufferedImage displayImage = ImageIO.read(new ByteArrayInputStream(display.content()));

        assertThat(compactImage.getWidth()).isEqualTo(360);
        assertThat(compactImage.getHeight()).isEqualTo(480);
        assertThat(displayImage.getWidth()).isEqualTo(960);
        assertThat(displayImage.getHeight()).isEqualTo(1_280);
        assertThat(compact.content()).startsWith((byte) 0xff, (byte) 0xd8);
        assertThat(display.content()).hasSizeLessThan(Thumbnail.MAX_CONTENT_BYTES);
    }
}
