package com.rulepilot.visualaid.application;

import com.rulepilot.visualaid.VisualRegionCatalog;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Owns visual catalog access and translates crop-local provider geometry back to the original page. */
@Service
@Profile("!test")
public class VisualRegionService implements VisualRegionCatalog {
    private final VisualRegionIndex index;
    private final VisualLayoutExtractor extractor;

    public VisualRegionService(VisualRegionIndex index, VisualLayoutExtractor extractor) {
        this.index = index;
        this.extractor = extractor;
    }

    @Override public List<Region> find(UUID version, Set<Integer> pages) { return index.find(version, pages); }
    @Override public boolean supportsRefinement() { return extractor.configured(); }

    @Override
    public List<Region> refine(Region parent, byte[] pageImage, Duration timeout) {
        if (parent == null || pageImage == null || timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("visual refinement input is invalid");
        }
        long started = System.nanoTime();
        try {
            BufferedImage page = ImageIO.read(new ByteArrayInputStream(pageImage));
            if (page == null) throw new IllegalArgumentException("visual refinement page is unreadable");
            try {
                // Round inwards: neither encoded pixels nor remapped children may escape the offered parent.
                int left = (int) Math.ceil(parent.x() * page.getWidth() / 1000.0);
                int top = (int) Math.ceil(parent.y() * page.getHeight() / 1000.0);
                int right = (parent.x() + parent.width()) * page.getWidth() / 1000;
                int bottom = (parent.y() + parent.height()) * page.getHeight() / 1000;
                if (right <= left || bottom <= top) return List.of();
                var bytes = new ByteArrayOutputStream();
                ImageIO.write(page.getSubimage(left, top, right - left, bottom - top), "png", bytes);
                Duration remaining = timeout.minusNanos(System.nanoTime() - started);
                if (remaining.isNegative() || remaining.isZero()) return List.of();
                var extraction = extractor.extractImage(bytes.toByteArray(), remaining);
                if (extraction.pageCount() != 1) throw new IllegalStateException("a crop must have exactly one page");
                List<Region> children = new ArrayList<>();
                for (Region child : extraction.regions()) {
                    int x = Math.max(parent.x(), (int) Math.floor((left + child.x() * (right - left) / 1000.0) * 1000 / page.getWidth()));
                    int y = Math.max(parent.y(), (int) Math.floor((top + child.y() * (bottom - top) / 1000.0) * 1000 / page.getHeight()));
                    int r = Math.min(parent.x() + parent.width(), (int) Math.ceil((left + (child.x() + child.width()) * (right - left) / 1000.0) * 1000 / page.getWidth()));
                    int b = Math.min(parent.y() + parent.height(), (int) Math.ceil((top + (child.y() + child.height()) * (bottom - top) / 1000.0) * 1000 / page.getHeight()));
                    if (r - x < 20 || b - y < 20 || (r - x) * (b - y) >= parent.width() * parent.height()) continue;
                    children.add(new Region(parent.pageNumber(), child.kind(), x, y, r - x, b - y));
                }
                return children.stream().distinct().toList();
            } finally {
                page.flush();
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("visual refinement image preparation failed", failure);
        }
    }
}
