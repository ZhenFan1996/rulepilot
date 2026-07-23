package com.rulepilot.teaching.adapter.out.cover;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class PublicCoverThumbnailerTest {

    @Test
    void downscales_a_large_origin_image_to_card_bounds_and_encodes_jpeg() throws Exception {
        BufferedImage original = new BufferedImage(1_600, 1_200, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        graphics.setColor(new Color(31, 79, 121));
        graphics.fillRect(0, 0, original.getWidth(), original.getHeight());
        graphics.dispose();
        var source = new ByteArrayOutputStream();
        ImageIO.write(original, "png", source);

        var thumbnail = new PublicCoverThumbnailer().create(source.toByteArray());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumbnail.content()));

        assertThat(decoded.getWidth()).isEqualTo(480);
        assertThat(decoded.getHeight()).isEqualTo(360);
        assertThat(thumbnail.content()).startsWith((byte) 0xff, (byte) 0xd8);
        assertThat(thumbnail.content().length).isLessThan(100_000);
    }
}
