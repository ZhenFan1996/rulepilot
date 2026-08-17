package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.VisualRegionLocator;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class TeachingOutlineImagePreparerTest {

    private final TeachingOutlineImagePreparer preparer = new TeachingOutlineImagePreparer();

    @Test
    void reducesLargePageImagesBeforeVisionModelUse() throws Exception {
        BufferedImage source = new BufferedImage(2400, 1800, BufferedImage.TYPE_INT_RGB);
        var graphics = source.createGraphics();
        graphics.setColor(Color.ORANGE);
        graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
        graphics.dispose();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "jpeg", encoded);

        PageImageInput prepared = preparer.prepare(new PageImageInput(3, "image/jpeg", encoded.toByteArray()));
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(prepared.content()));

        assertThat(prepared.mediaType()).isEqualTo("image/jpeg");
        assertThat(result.getWidth()).isEqualTo(1024);
        assertThat(result.getHeight()).isEqualTo(768);
    }

    @Test
    void keepsSmallPageDimensions() throws Exception {
        BufferedImage source = new BufferedImage(400, 600, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "jpeg", encoded);

        PageImageInput prepared = preparer.prepare(new PageImageInput(1, "image/jpeg", encoded.toByteArray()));
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(prepared.content()));

        assertThat(result.getWidth()).isEqualTo(400);
        assertThat(result.getHeight()).isEqualTo(600);
    }

    @Test
    void enlargesSmallImageGalleryPagesOnlyForRuleTranscription() throws Exception {
        BufferedImage source = new BufferedImage(595, 793, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "png", encoded);

        PageImageInput prepared = preparer.prepareForRuleTranscription(
                new PageImageInput(16, "image/png", encoded.toByteArray()));
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(prepared.content()));

        assertThat(prepared.pageNumber()).isEqualTo(16);
        assertThat(prepared.mediaType()).isEqualTo("image/jpeg");
        assertThat(result.getWidth()).isEqualTo(1_190);
        assertThat(result.getHeight()).isEqualTo(1_586);
    }

    @Test
    void reducesLargeLessonEvidencePagesBeforeVisionModelUse() throws Exception {
        BufferedImage source = new BufferedImage(1800, 2400, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "jpeg", encoded);

        TeachingLessonModel.PageImageInput prepared = preparer.prepare(
                new TeachingLessonModel.PageImageInput(8, "image/jpeg", encoded.toByteArray(), 1800, 2400));

        assertThat(prepared.width()).isEqualTo(768);
        assertThat(prepared.height()).isEqualTo(1024);
    }

    @Test
    void reducesLargeVisualLocatorPagesBeforeVisionModelUse() throws Exception {
        BufferedImage source = new BufferedImage(2400, 1800, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "jpeg", encoded);

        VisualRegionLocator.PageImage prepared = preparer.prepare(
                new VisualRegionLocator.PageImage(5, "image/jpeg", encoded.toByteArray()));
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(prepared.content()));

        assertThat(prepared.pageNumber()).isEqualTo(5);
        assertThat(prepared.mediaType()).isEqualTo("image/jpeg");
        assertThat(result.getWidth()).isEqualTo(1024);
        assertThat(result.getHeight()).isEqualTo(768);
    }
}
