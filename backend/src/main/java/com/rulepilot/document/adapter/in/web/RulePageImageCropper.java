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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
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
    private static final long MAX_SOURCE_PAGE_PIXELS = 40L * 1_000 * 1_000;
    private static final long MAX_PROJECTED_IMAGE_PIXELS = 16L * 1_000 * 1_000;
    private static final long MAX_CONCURRENT_DECODE_WORK_PIXELS = 64L * 1_000 * 1_000;
    private static final long PIXELS_PER_PERMIT = 1_000_000L;

    private final Semaphore decodeWorkPermits;

    RulePageImageCropper() {
        this(MAX_CONCURRENT_DECODE_WORK_PIXELS);
    }

    RulePageImageCropper(long maximumConcurrentDecodeWorkPixels) {
        if (maximumConcurrentDecodeWorkPixels < 1) {
            throw new IllegalArgumentException("document page decode capacity is invalid");
        }
        this.decodeWorkPermits = new Semaphore(permitsFor(maximumConcurrentDecodeWorkPixels), true);
    }

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
        CropProjection projection = cropProjection(page, x, y, width, height, contextPadding);
        int permits = acquireDecodeWork(projection.totalWorkPixels());
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(page.content()))) {
            if (input == null) throw new IllegalStateException("document page image cannot be decoded");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalStateException("document page image cannot be decoded");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                int sourceWidth = reader.getWidth(0);
                int sourceHeight = reader.getHeight(0);
                requireMatchingDimensions(page, sourceWidth, sourceHeight);
                ImageReadParam parameters = reader.getDefaultReadParam();
                parameters.setSourceRegion(new Rectangle(
                        projection.left(),
                        projection.top(),
                        projection.width(),
                        projection.height()));
                BufferedImage decoded = reader.read(0, parameters);
                if (decoded == null) throw new IllegalStateException("document page image cannot be decoded");
                return encodeRgb(decoded);
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("could not crop document page image", exception);
        } finally {
            decodeWorkPermits.release(permits);
        }
    }

    @Override
    public byte[] preview(PageImage page) {
        long sourcePixels = sourcePixels(page);
        PreviewSize output = previewSize(page.width(), page.height());
        int subsampling = previewSubsampling(page.width(), page.height(), output);
        long sampledPixels = sampledPixels(page.width(), page.height(), subsampling);
        validateProjectedPixels(sampledPixels);
        int permits = acquireDecodeWork(sourcePixels + sampledPixels);
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(page.content()))) {
            if (input == null) throw new IllegalStateException("document page image cannot be decoded");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalStateException("document page image cannot be decoded");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                int sourceWidth = reader.getWidth(0);
                int sourceHeight = reader.getHeight(0);
                requireMatchingDimensions(page, sourceWidth, sourceHeight);
                ImageReadParam parameters = reader.getDefaultReadParam();
                parameters.setSourceSubsampling(subsampling, subsampling, 0, 0);
                BufferedImage decoded = reader.read(0, parameters);
                if (decoded == null) throw new IllegalStateException("document page image cannot be decoded");
                return encodeRgb(resize(decoded, output.width(), output.height()));
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("could not preview document page image", exception);
        } finally {
            decodeWorkPermits.release(permits);
        }
    }

    private CropProjection cropProjection(
            PageImage page,
            int x,
            int y,
            int width,
            int height,
            int contextPadding) {
        long sourcePixels = sourcePixels(page);
        int left = pixel(Math.max(0, x - contextPadding), page.width());
        int top = pixel(Math.max(0, y - contextPadding), page.height());
        int right = pixelCeiling(
                Math.min(NORMALIZED_PAGE_SIZE, x + width + contextPadding), page.width());
        int bottom = pixelCeiling(
                Math.min(NORMALIZED_PAGE_SIZE, y + height + contextPadding), page.height());
        int projectedWidth = right - left;
        int projectedHeight = bottom - top;
        long projectedPixels = (long) projectedWidth * projectedHeight;
        validateProjectedPixels(projectedPixels);
        return new CropProjection(
                left,
                top,
                projectedWidth,
                projectedHeight,
                sourcePixels + projectedPixels);
    }

    private long sourcePixels(PageImage page) {
        long pixels = (long) page.width() * page.height();
        if (pixels > MAX_SOURCE_PAGE_PIXELS) {
            throw new IllegalArgumentException("document page image exceeds the source pixel limit");
        }
        return pixels;
    }

    private void validateProjectedPixels(long projectedPixels) {
        if (projectedPixels > MAX_PROJECTED_IMAGE_PIXELS) {
            throw new IllegalArgumentException("document page image exceeds the projected pixel limit");
        }
    }

    private int acquireDecodeWork(long pixels) {
        int permits = permitsFor(pixels);
        if (!decodeWorkPermits.tryAcquire(permits)) {
            throw new RejectedExecutionException("document page image decode capacity is exhausted");
        }
        return permits;
    }

    private static int permitsFor(long pixels) {
        long permits = pixels < 1 ? 1 : 1 + (pixels - 1) / PIXELS_PER_PERMIT;
        if (permits > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("document page decode work is invalid");
        }
        return (int) permits;
    }

    private int previewSubsampling(int sourceWidth, int sourceHeight, PreviewSize output) {
        return Math.max(1, Math.min(
                sourceWidth / output.width(),
                sourceHeight / output.height()));
    }

    private long sampledPixels(int sourceWidth, int sourceHeight, int subsampling) {
        long sampledWidth = ((long) sourceWidth + subsampling - 1) / subsampling;
        long sampledHeight = ((long) sourceHeight + subsampling - 1) / subsampling;
        return sampledWidth * sampledHeight;
    }

    private void requireMatchingDimensions(PageImage page, int decodedWidth, int decodedHeight) {
        if (decodedWidth != page.width() || decodedHeight != page.height()) {
            throw new IllegalStateException("document page image dimensions do not match stored metadata");
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
        return Math.min(imageSize - 1, (int) ((long) normalized * imageSize / NORMALIZED_PAGE_SIZE));
    }

    private int pixelCeiling(int normalized, int imageSize) {
        return Math.max(1, Math.min(imageSize,
                (int) (((long) normalized * imageSize + NORMALIZED_PAGE_SIZE - 1) / NORMALIZED_PAGE_SIZE)));
    }

    private record CropProjection(int left, int top, int width, int height, long totalWorkPixels) {}

    private record PreviewSize(int width, int height) {}
}
