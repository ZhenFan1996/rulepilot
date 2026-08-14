package com.rulepilot.document.adapter.in.web;

import com.rulepilot.document.DocumentPageImages.PageImage;
import com.rulepilot.document.DocumentPageImageCropper;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Component;

@Component
final class RulePageImageCropper implements DocumentPageImageCropper {

    private static final int NORMALIZED_PAGE_SIZE = 1_000;
    private static final int CONTEXT_PADDING = 35;
    private static final int PREVIEW_MAX_WIDTH = 480;
    private static final int PREVIEW_MAX_HEIGHT = 680;

    @Override
    public byte[] crop(PageImage page, int x, int y, int width, int height) {
        return crop(page, x, y, width, height, CONTEXT_PADDING);
    }

    @Override
    public byte[] crop(PageImage page, int x, int y, int width, int height, int contextPadding) {
        validateFocus(x, y, width, height);
        if (contextPadding < 0 || contextPadding > 100) {
            throw new IllegalArgumentException("document page crop padding is invalid");
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(page.content()))) {
            if (input == null) throw new IllegalArgumentException("document page image cannot be decoded");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("document page image cannot be decoded");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                int sourceWidth = reader.getWidth(0);
                int sourceHeight = reader.getHeight(0);
                int left = pixel(Math.max(0, x - contextPadding), sourceWidth);
                int top = pixel(Math.max(0, y - contextPadding), sourceHeight);
                int right = pixelCeiling(
                        Math.min(NORMALIZED_PAGE_SIZE, x + width + contextPadding), sourceWidth);
                int bottom = pixelCeiling(
                        Math.min(NORMALIZED_PAGE_SIZE, y + height + contextPadding), sourceHeight);
                ImageReadParam parameters = reader.getDefaultReadParam();
                parameters.setSourceRegion(new Rectangle(left, top, right - left, bottom - top));
                return encodeRgb(reader.read(0, parameters));
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("could not crop document page image", exception);
        }
    }

    @Override
    public byte[] preview(PageImage page) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(page.content()))) {
            if (input == null) throw new IllegalArgumentException("document page image cannot be decoded");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("document page image cannot be decoded");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                int sourceWidth = reader.getWidth(0);
                int sourceHeight = reader.getHeight(0);
                PreviewSize output = previewSize(sourceWidth, sourceHeight);
                int subsampling = Math.max(1, Math.min(
                        sourceWidth / output.width(),
                        sourceHeight / output.height()));
                ImageReadParam parameters = reader.getDefaultReadParam();
                parameters.setSourceSubsampling(subsampling, subsampling, 0, 0);
                return encodeRgb(resize(reader.read(0, parameters), output.width(), output.height()));
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("could not preview document page image", exception);
        }
    }

    private BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return resized;
    }

    private PreviewSize previewSize(int sourceWidth, int sourceHeight) {
        double scale = Math.min(1d, Math.min(
                (double) PREVIEW_MAX_WIDTH / sourceWidth,
                (double) PREVIEW_MAX_HEIGHT / sourceHeight));
        return new PreviewSize(
                Math.max(1, (int) Math.round(sourceWidth * scale)),
                Math.max(1, (int) Math.round(sourceHeight * scale)));
    }

    private byte[] encodeRgb(BufferedImage crop) throws IOException {
        BufferedImage rgb = new BufferedImage(crop.getWidth(), crop.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.drawImage(crop, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(rgb, "jpeg", output)) {
            throw new IllegalStateException("JPEG image writer is unavailable");
        }
        return output.toByteArray();
    }

    private void validateFocus(int x, int y, int width, int height) {
        if (x < 0 || x > 980 || y < 0 || y > 980
                || width < 12 || width > NORMALIZED_PAGE_SIZE - x
                || height < 12 || height > NORMALIZED_PAGE_SIZE - y) {
            throw new IllegalArgumentException("document page crop focus is invalid");
        }
    }

    private int pixel(int normalized, int imageSize) {
        return Math.min(imageSize - 1, normalized * imageSize / NORMALIZED_PAGE_SIZE);
    }

    private int pixelCeiling(int normalized, int imageSize) {
        return Math.max(1, Math.min(imageSize,
                (normalized * imageSize + NORMALIZED_PAGE_SIZE - 1) / NORMALIZED_PAGE_SIZE));
    }

    private record PreviewSize(int width, int height) {}
}
