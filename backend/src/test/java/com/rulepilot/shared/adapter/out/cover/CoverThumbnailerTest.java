package com.rulepilot.shared.adapter.out.cover;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.shared.cover.CoverThumbnailCache.Thumbnail;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class CoverThumbnailerTest {

    @Test
    void retainsEnoughPixelsForRetinaCatalogAndIdentityCards() throws Exception {
        BufferedImage sourceImage = new BufferedImage(1_200, 1_600, BufferedImage.TYPE_INT_RGB);
        var graphics = sourceImage.createGraphics();
        graphics.setColor(new Color(31, 79, 121));
        graphics.fillRect(0, 0, sourceImage.getWidth(), sourceImage.getHeight());
        graphics.dispose();
        var source = new ByteArrayOutputStream();
        ImageIO.write(sourceImage, "png", source);

        Thumbnail thumbnail = new CoverThumbnailer().create(source.toByteArray());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumbnail.content()));

        assertThat(decoded.getWidth()).isEqualTo(960);
        assertThat(decoded.getHeight()).isEqualTo(1_280);
        assertThat(thumbnail.content()).startsWith((byte) 0xff, (byte) 0xd8);
        assertThat(thumbnail.content()).hasSizeLessThan(Thumbnail.MAX_CONTENT_BYTES);
    }
}
