package com.rulepilot.teaching.application;

import com.rulepilot.teaching.PublicCoverThumbnailCache.Thumbnail;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/** Converts a bounded source image into the small JPEG used by public cover cards. */
public final class CoverThumbnailer {

    static final int MAX_WIDTH = 480;
    static final int MAX_HEIGHT = 640;
    private static final int MAX_SOURCE_EDGE = 6_000;
    private static final long MAX_SOURCE_PIXELS = 18_000_000L;

    public Thumbnail create(byte[] sourceContent) {
        BufferedImage source = read(sourceContent);
        int[] dimensions = dimensions(source.getWidth(), source.getHeight());
        BufferedImage resized = new BufferedImage(dimensions[0], dimensions[1], BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setColor(new Color(245, 240, 232));
            graphics.fillRect(0, 0, resized.getWidth(), resized.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, resized.getWidth(), resized.getHeight(), null);
        } finally {
            graphics.dispose();
        }
        return new Thumbnail(writeJpeg(resized));
    }

    private BufferedImage read(byte[] sourceContent) {
        if (sourceContent == null || sourceContent.length == 0) throw new IllegalArgumentException("cover image is empty");
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(sourceContent))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("cover image format is unsupported");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_SOURCE_EDGE || height > MAX_SOURCE_EDGE
                        || (long) width * height > MAX_SOURCE_PIXELS) {
                    throw new IllegalArgumentException("cover image dimensions are unsafe");
                }
                return reader.read(0, new ImageReadParam());
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("cover image could not be decoded", exception);
        }
    }

    private int[] dimensions(int width, int height) {
        double scale = Math.min(1d, Math.min((double) MAX_WIDTH / width, (double) MAX_HEIGHT / height));
        return new int[] { Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale)) };
    }

    private byte[] writeJpeg(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IllegalStateException("JPEG encoder is unavailable");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if (parameters.canWriteCompressed()) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(0.82f);
            }
            writer.write(null, new IIOImage(image, null, null), parameters);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("cover thumbnail could not be encoded", exception);
        } finally {
            writer.dispose();
        }
    }
}
