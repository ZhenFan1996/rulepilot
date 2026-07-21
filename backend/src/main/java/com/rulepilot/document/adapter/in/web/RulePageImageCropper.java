package com.rulepilot.document.adapter.in.web;

import com.rulepilot.document.DocumentPageImages.PageImage;
import com.rulepilot.document.DocumentPageImageCropper;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
final class RulePageImageCropper implements DocumentPageImageCropper {

    private static final int NORMALIZED_PAGE_SIZE = 1_000;
    private static final int CONTEXT_PADDING = 35;

    @Override
    public byte[] crop(PageImage page, int x, int y, int width, int height) {
        validateFocus(x, y, width, height);
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(page.content()));
            if (source == null) throw new IllegalArgumentException("document page image cannot be decoded");

            int left = pixel(Math.max(0, x - CONTEXT_PADDING), source.getWidth());
            int top = pixel(Math.max(0, y - CONTEXT_PADDING), source.getHeight());
            int right = pixelCeiling(
                    Math.min(NORMALIZED_PAGE_SIZE, x + width + CONTEXT_PADDING), source.getWidth());
            int bottom = pixelCeiling(
                    Math.min(NORMALIZED_PAGE_SIZE, y + height + CONTEXT_PADDING), source.getHeight());
            BufferedImage crop = source.getSubimage(left, top, right - left, bottom - top);
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
        } catch (IOException exception) {
            throw new UncheckedIOException("could not crop document page image", exception);
        }
    }

    private void validateFocus(int x, int y, int width, int height) {
        if (x < 0 || x > 980 || y < 0 || y > 980
                || width < 20 || width > NORMALIZED_PAGE_SIZE - x
                || height < 20 || height > NORMALIZED_PAGE_SIZE - y) {
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
}
