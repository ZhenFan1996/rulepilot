package com.rulepilot.document.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.rulepilot.document.DocumentPageImages.PageImage;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
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

    @Test
    void normalizesAFullEvidencePageToABrowserSafeRgbJpeg() throws IOException {
        BufferedImage source = new BufferedImage(80, 120, BufferedImage.TYPE_4BYTE_ABGR);
        var graphics = source.createGraphics();
        graphics.setColor(new Color(245, 240, 232, 180));
        graphics.fillRect(0, 0, 80, 120);
        graphics.dispose();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "png", encoded);

        byte[] result = cropper.crop(
                new PageImage(1, "image/png", encoded.toByteArray(), 80, 120),
                0,
                0,
                1_000,
                1_000,
                0);

        BufferedImage normalized = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(normalized.getWidth()).isEqualTo(80);
        assertThat(normalized.getHeight()).isEqualTo(120);
        assertThat(normalized.getColorModel().hasAlpha()).isFalse();
    }

    @Test
    void decodesOnlyTheRequestedRegionOfALargeEvidencePage() throws IOException {
        BufferedImage source = new BufferedImage(2_000, 3_000, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "jpeg", encoded);

        byte[] result = cropper.crop(
                new PageImage(1, "image/jpeg", encoded.toByteArray(), 2_000, 3_000),
                500,
                500,
                20,
                20,
                10);

        BufferedImage cropped = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(cropped.getWidth()).isEqualTo(80);
        assertThat(cropped.getHeight()).isEqualTo(120);
    }

    @Test
    void servesConcurrentIconCropsWithoutMaterializingTheWholePageForEachRequest() throws Exception {
        BufferedImage source = new BufferedImage(2_000, 3_000, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "jpeg", encoded);
        PageImage page = new PageImage(1, "image/jpeg", encoded.toByteArray(), 2_000, 3_000);
        List<Callable<byte[]>> requests = IntStream.range(0, 8)
                .mapToObj(ignored -> (Callable<byte[]>) () -> cropper.crop(page, 500, 500, 20, 20, 10))
                .toList();

        try (var executor = Executors.newFixedThreadPool(requests.size())) {
            var results = executor.invokeAll(requests);

            for (var result : results) {
                BufferedImage cropped = ImageIO.read(new ByteArrayInputStream(result.get()));
                assertThat(cropped.getWidth()).isEqualTo(80);
                assertThat(cropped.getHeight()).isEqualTo(120);
            }
        }
    }
}
