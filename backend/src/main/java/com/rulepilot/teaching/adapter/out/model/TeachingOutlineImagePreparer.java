package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.VisualRegionLocator;
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

    // Keep an input page large enough for icons and callouts, but not so large that a two-page vision batch spends
    // its whole request budget rasterizing pixels. The original page stays in object storage for reader-facing crops.
    private static final int MAX_EDGE_PIXELS = 1_024;
    private static final int MAX_RULE_TRANSCRIPTION_EDGE_PIXELS = 2_000;
    private static final float JPEG_QUALITY = 0.85f;
    private static final float TRANSCRIPTION_JPEG_QUALITY = 0.95f;

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

    /**
     * Keeps the stored page's original pixels and encoding on the OCR path whenever it already fits the provider
     * boundary. Only over-limit pages are resized; PNG stays lossless and JPEG uses a high-quality encoding.
     */
    PageImageInput prepareForRuleTranscription(PageImageInput input) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(input.content()));
            if (source == null) throw new IllegalArgumentException("rulebook outline page image cannot be decoded");
            int largestEdge = Math.max(source.getWidth(), source.getHeight());
            String mediaType = input.mediaType().toLowerCase(java.util.Locale.ROOT);
            if (largestEdge <= MAX_RULE_TRANSCRIPTION_EDGE_PIXELS
                    && (mediaType.equals("image/png")
                            || mediaType.equals("image/jpeg")
                            || mediaType.equals("image/jpg"))) {
                return input;
            }
            boolean preserveAlpha = mediaType.equals("image/png");
            BufferedImage resized = largestEdge <= MAX_RULE_TRANSCRIPTION_EDGE_PIXELS
                    ? source
                    : resizeToLargestEdge(source, MAX_RULE_TRANSCRIPTION_EDGE_PIXELS, preserveAlpha);
            if (preserveAlpha) {
                return new PageImageInput(input.pageNumber(), "image/png", encodePng(resized));
            }
            return new PageImageInput(
                    input.pageNumber(), "image/jpeg", encodeJpeg(resized, TRANSCRIPTION_JPEG_QUALITY));
        } catch (IOException exception) {
            throw new UncheckedIOException("could not prepare rulebook transcription page image", exception);
        }
    }

    TeachingLessonModel.PageImageInput prepare(TeachingLessonModel.PageImageInput input) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(input.content()));
            if (source == null) throw new IllegalArgumentException("rulebook lesson page image cannot be decoded");
            BufferedImage resized = resize(source);
            return new TeachingLessonModel.PageImageInput(
                    input.pageNumber(), "image/jpeg", encode(resized), resized.getWidth(), resized.getHeight());
        } catch (IOException exception) {
            throw new UncheckedIOException("could not prepare rulebook lesson page image", exception);
        }
    }

    VisualRegionLocator.PageImage prepare(VisualRegionLocator.PageImage input) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(input.content()));
            if (source == null) throw new IllegalArgumentException("rulebook visual locator page image cannot be decoded");
            BufferedImage resized = resize(source);
            return new VisualRegionLocator.PageImage(input.pageNumber(), "image/jpeg", encode(resized));
        } catch (IOException exception) {
            throw new UncheckedIOException("could not prepare rulebook visual locator page", exception);
        }
    }

    private BufferedImage resize(BufferedImage source) {
        int largestEdge = Math.max(source.getWidth(), source.getHeight());
        if (largestEdge <= MAX_EDGE_PIXELS) return source;
        return resizeToLargestEdge(source, MAX_EDGE_PIXELS);
    }

    private BufferedImage resizeToLargestEdge(BufferedImage source, int targetEdge) {
        return resizeToLargestEdge(source, targetEdge, false);
    }

    private BufferedImage resizeToLargestEdge(BufferedImage source, int targetEdge, boolean preserveAlpha) {
        int largestEdge = Math.max(source.getWidth(), source.getHeight());
        if (largestEdge == targetEdge) return source;
        double ratio = (double) targetEdge / largestEdge;
        int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        BufferedImage scaled = new BufferedImage(
                width,
                height,
                preserveAlpha && source.getColorModel().hasAlpha()
                        ? BufferedImage.TYPE_INT_ARGB
                        : BufferedImage.TYPE_INT_RGB);
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
        return encodeJpeg(image, JPEG_QUALITY);
    }

    private byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("JPEG image writer is unavailable");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), parameters);
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private byte[] encodePng(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IOException("PNG image writer is unavailable");
            return output.toByteArray();
        }
    }
}
