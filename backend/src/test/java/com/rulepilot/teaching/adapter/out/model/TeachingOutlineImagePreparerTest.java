package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.TeachingLessonModel;
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
        BufferedImage source = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
        var graphics = source.createGraphics();
        graphics.setColor(Color.ORANGE);
        graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
        graphics.dispose();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "jpeg", encoded);

        PageImageInput prepared = preparer.prepare(new PageImageInput(3, "image/jpeg", encoded.toByteArray()));
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(prepared.content()));

        assertThat(prepared.mediaType()).isEqualTo("image/jpeg");
        assertThat(result.getWidth()).isEqualTo(768);
        assertThat(result.getHeight()).isEqualTo(576);
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
    void reducesLargeLessonEvidencePagesBeforeVisionModelUse() throws Exception {
        BufferedImage source = new BufferedImage(1200, 1600, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "jpeg", encoded);

        TeachingLessonModel.PageImageInput prepared = preparer.prepare(
                new TeachingLessonModel.PageImageInput(8, "image/jpeg", encoded.toByteArray(), 1200, 1600));

        assertThat(prepared.width()).isEqualTo(576);
        assertThat(prepared.height()).isEqualTo(768);
    }
}
