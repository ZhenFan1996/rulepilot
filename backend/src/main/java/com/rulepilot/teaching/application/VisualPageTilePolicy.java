package com.rulepilot.teaching.application;

import com.rulepilot.document.DocumentPageImages.PageImage;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageViewport;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Bounded fallback for a visually dense rulebook page.
 *
 * <p>Four overlapping viewports reduce each model response without guessing a game-specific layout. Coordinates are
 * projected back to the immutable full page before anything is persisted or shown to a player.</p>
 */
final class VisualPageTilePolicy {

    private static final int NORMALIZED_PAGE_SIZE = 1_000;
    private static final int TILE_SIZE = 550;
    private static final int SECOND_TILE_ORIGIN = NORMALIZED_PAGE_SIZE - TILE_SIZE;

    private VisualPageTilePolicy() {}

    static List<PageTile> tiles(PageImage page) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(page.content()));
            if (source == null) throw new IllegalArgumentException("rulebook page image cannot be decoded");
            List<PageTile> tiles = new ArrayList<>(4);
            for (int y : List.of(0, SECOND_TILE_ORIGIN)) {
                for (int x : List.of(0, SECOND_TILE_ORIGIN)) {
                    PageViewport viewport = new PageViewport(page.pageNumber(), x, y, TILE_SIZE, TILE_SIZE);
                    tiles.add(new PageTile(viewport, crop(source, viewport)));
                }
            }
            return List.copyOf(tiles);
        } catch (IOException exception) {
            throw new UncheckedIOException("could not tile rulebook page image", exception);
        }
    }

    static PageSummary merge(int pageNumber, List<TileSummary> completedTiles) {
        if (completedTiles == null || completedTiles.isEmpty()) {
            throw new IllegalArgumentException("at least one completed page tile is required");
        }
        Map<String, IconOccurrence> icons = new LinkedHashMap<>();
        List<VisualAnchor> anchors = new ArrayList<>();
        for (TileSummary tile : completedTiles) {
            for (IconOccurrence icon : tile.summary().iconOccurrences()) {
                IconOccurrence projected = project(icon, tile.viewport());
                icons.merge(normalized(projected.groupKey()), projected, VisualPageTilePolicy::preferGrounded);
            }
            tile.summary().visualAnchors().stream()
                    .map(anchor -> project(anchor, tile.viewport()))
                    .filter(anchor -> anchors.stream().noneMatch(existing -> substantiallySame(existing, anchor)))
                    .limit(Math.max(0, 8 - anchors.size()))
                    .forEach(anchors::add);
        }
        List<String> keywords = completedTiles.stream()
                .flatMap(tile -> tile.summary().keywords().stream())
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(12)
                .toList();
        boolean complete = completedTiles.size() == 4
                && completedTiles.stream().allMatch(tile -> tile.summary().iconInventoryComplete());
        return new PageSummary(
                pageNumber,
                joined(completedTiles.stream().map(tile -> tile.summary().printedTerms()).toList(), 1_600),
                joined(completedTiles.stream().map(tile -> tile.summary().factualSummary()).toList(), 1_600),
                keywords,
                anchors,
                icons.values().stream().limit(32).toList(),
                complete && icons.size() <= 32);
    }

    private static byte[] crop(BufferedImage source, PageViewport viewport) throws IOException {
        int left = viewport.x() * source.getWidth() / NORMALIZED_PAGE_SIZE;
        int top = viewport.y() * source.getHeight() / NORMALIZED_PAGE_SIZE;
        int right = Math.min(
                source.getWidth(),
                (viewport.x() + viewport.width()) * source.getWidth() / NORMALIZED_PAGE_SIZE);
        int bottom = Math.min(
                source.getHeight(),
                (viewport.y() + viewport.height()) * source.getHeight() / NORMALIZED_PAGE_SIZE);
        BufferedImage selected = source.getSubimage(left, top, right - left, bottom - top);
        BufferedImage rgb = new BufferedImage(selected.getWidth(), selected.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.drawImage(selected, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(rgb, "jpeg", output)) throw new IOException("JPEG image writer is unavailable");
        return output.toByteArray();
    }

    private static IconOccurrence project(IconOccurrence icon, PageViewport viewport) {
        int[] bounds = projectBounds(icon.x(), icon.y(), icon.width(), icon.height(), viewport, 12);
        return new IconOccurrence(
                icon.groupKey(),
                icon.name(),
                icon.visualDescription(),
                icon.explanation(),
                icon.evidenceText(),
                icon.meaningStatus(),
                bounds[0],
                bounds[1],
                bounds[2],
                bounds[3]);
    }

    private static VisualAnchor project(VisualAnchor anchor, PageViewport viewport) {
        int[] bounds = projectBounds(
                anchor.x(), anchor.y(), anchor.width(), anchor.height(), viewport, 20);
        return new VisualAnchor(
                anchor.kind(),
                anchor.label(),
                anchor.visibleDescription(),
                bounds[0],
                bounds[1],
                bounds[2],
                bounds[3]);
    }

    private static int[] projectBounds(
            int x,
            int y,
            int width,
            int height,
            PageViewport viewport,
            int minimumSize) {
        int projectedX = viewport.x() + x * viewport.width() / NORMALIZED_PAGE_SIZE;
        int projectedY = viewport.y() + y * viewport.height() / NORMALIZED_PAGE_SIZE;
        int right = viewport.x()
                + (int) Math.ceil((x + width) * viewport.width() / (double) NORMALIZED_PAGE_SIZE);
        int bottom = viewport.y()
                + (int) Math.ceil((y + height) * viewport.height() / (double) NORMALIZED_PAGE_SIZE);
        int projectedWidth = Math.max(minimumSize, right - projectedX);
        int projectedHeight = Math.max(minimumSize, bottom - projectedY);
        projectedX = Math.min(projectedX, NORMALIZED_PAGE_SIZE - projectedWidth);
        projectedY = Math.min(projectedY, NORMALIZED_PAGE_SIZE - projectedHeight);
        return new int[] {projectedX, projectedY, projectedWidth, projectedHeight};
    }

    private static IconOccurrence preferGrounded(IconOccurrence first, IconOccurrence second) {
        if (meaningRank(second.meaningStatus()) > meaningRank(first.meaningStatus())) {
            return second;
        }
        return first;
    }

    private static int meaningRank(IconMeaningStatus status) {
        return switch (status) {
            case EXPLICIT -> 2;
            case IDENTIFIED -> 1;
            case UNEXPLAINED -> 0;
        };
    }

    private static boolean substantiallySame(VisualAnchor first, VisualAnchor second) {
        if (!normalized(first.label()).equals(normalized(second.label()))) return false;
        int overlapWidth = Math.max(
                0,
                Math.min(first.x() + first.width(), second.x() + second.width())
                        - Math.max(first.x(), second.x()));
        int overlapHeight = Math.max(
                0,
                Math.min(first.y() + first.height(), second.y() + second.height())
                        - Math.max(first.y(), second.y()));
        long overlap = (long) overlapWidth * overlapHeight;
        long smaller = Math.min(
                (long) first.width() * first.height(),
                (long) second.width() * second.height());
        return overlap * 2 >= smaller;
    }

    private static String joined(List<String> values, int maximumLength) {
        StringBuilder joined = new StringBuilder();
        values.stream().map(String::strip).filter(value -> !value.isBlank()).distinct().forEach(value -> {
            if (joined.length() >= maximumLength) return;
            if (!joined.isEmpty()) joined.append('\n');
            int remaining = maximumLength - joined.length();
            joined.append(value, 0, Math.min(value.length(), remaining));
        });
        return joined.isEmpty() ? "No legible printed term in the completed page tiles." : joined.toString();
    }

    private static String normalized(String value) {
        return value.strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{Zs}]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    record PageTile(PageViewport viewport, PageImageInput image) {

        PageTile(PageViewport viewport, byte[] content) {
            this(viewport, new PageImageInput(viewport.pageNumber(), "image/jpeg", content));
        }
    }

    record TileSummary(PageViewport viewport, PageSummary summary) {}
}
