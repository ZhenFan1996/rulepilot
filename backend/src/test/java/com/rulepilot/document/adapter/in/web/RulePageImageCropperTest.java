package com.rulepilot.document.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.rulepilot.document.DocumentPageImages.PageImage;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class RulePageImageCropperTest {

    private final RulePageImageCropper cropper = new RulePageImageCropper();

    @Test
    void returnsTheFocusedRegionWithAThinContextMargin() throws IOException {
        BufferedImage source = new BufferedImage(100, 200, BufferedImage.TYPE_INT_RGB);
        var graphics = source.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 100, 200);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(25, 50, 50, 100);
        graphics.dispose();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "jpeg", encoded);

        byte[] result = cropper.crop(
                new PageImage(4, "image/jpeg", encoded.toByteArray(), 100, 200),
                250, 250, 500, 500);

        BufferedImage cropped = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(cropped.getWidth()).isEqualTo(58);
        assertThat(cropped.getHeight()).isEqualTo(114);
        assertThat(cropped.getWidth()).isLessThan(source.getWidth());
        assertThat(cropped.getHeight()).isLessThan(source.getHeight());
    }

    @Test
    void rejectsCoordinatesOutsideTheNormalizedPage() {
        PageImage page = new PageImage(1, "image/jpeg", new byte[] {1}, 100, 100);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> cropper.crop(page, 990, 0, 20, 100));
    }

    @Test
    void acceptsTheMinimumModelValidatedIconRectangle() throws IOException {
        BufferedImage source = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "jpeg", encoded);

        byte[] result = cropper.crop(
                new PageImage(1, "image/jpeg", encoded.toByteArray(), 100, 100),
                100,
                100,
                12,
                12);

        assertThat(ImageIO.read(new ByteArrayInputStream(result))).isNotNull();
    }

    @Test
    void supportsACompactContextMarginForIconQuickReferenceCrops() throws IOException {
        BufferedImage source = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "jpeg", encoded);

        byte[] result = cropper.crop(
                new PageImage(1, "image/jpeg", encoded.toByteArray(), 100, 100),
                250,
                250,
                200,
                200,
                10);

        BufferedImage cropped = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(cropped.getWidth()).isEqualTo(22);
        assertThat(cropped.getHeight()).isEqualTo(22);
    }
}
