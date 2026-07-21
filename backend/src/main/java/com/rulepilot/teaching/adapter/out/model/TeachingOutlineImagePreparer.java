package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/** Reduces overview images before they are sent to a vision model; original pages remain stored unchanged. */
final class TeachingOutlineImagePreparer {

    private static final int MAX_EDGE_PIXELS = 768;
    private static final float JPEG_QUALITY = 0.76f;

    PageImageInput prepare(PageImageInput input) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(input.content()));
            if (source == null) throw new IllegalArgumentException("rulebook outline page image cannot be decoded");
            BufferedImage resized = resize(source);
            return new PageImageInput(input.pageNumber(), "image/jpeg", encode(resized));
        } catch (IOException exception) {
            throw new UncheckedIOException("could not prepare rulebook outline page image", exception);
        }
    }

    private BufferedImage resize(BufferedImage source) {
        int largestEdge = Math.max(source.getWidth(), source.getHeight());
        if (largestEdge <= MAX_EDGE_PIXELS) return source;
        double ratio = (double) MAX_EDGE_PIXELS / largestEdge;
        int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
            return scaled;
        } finally {
            graphics.dispose();
        }
    }

    private byte[] encode(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("JPEG image writer is unavailable");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), parameters);
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
