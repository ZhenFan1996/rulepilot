package com.rulepilot.teaching.application;

import com.rulepilot.document.DocumentPageImages.PageImage;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IdentifierCellInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IdentifierLocation;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/** Generic two-pass policy for dense catalogs whose entries carry short printed identifiers. */
final class PrintedIdentifierCellPolicy {

    private static final Pattern IDENTIFIER = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])([\\p{L}]{1,4}\\s*[#_-]\\s*\\d{1,3})(?![\\p{L}\\p{N}])");
    private static final int PAGE_SIZE = 1_000;
    private static final int SAME_ROW_TOLERANCE = 45;

    private PrintedIdentifierCellPolicy() {}

    static List<String> identifiers(String printedTerms) {
        if (printedTerms == null || printedTerms.isBlank()) return List.of();
        Map<String, String> unique = new LinkedHashMap<>();
        Matcher matcher = IDENTIFIER.matcher(printedTerms);
        while (matcher.find() && unique.size() < 24) {
            String visible = matcher.group(1).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            unique.putIfAbsent(normalized(visible), visible);
        }
        return unique.size() < 4 ? List.of() : List.copyOf(unique.values());
    }

    static List<IdentifierLocation> verifiedLocations(
            List<String> requested, List<IdentifierLocation> proposed) {
        Map<String, String> allowed = new LinkedHashMap<>();
        requested.forEach(value -> allowed.put(normalized(value), value));
        Map<String, IdentifierLocation> accepted = new LinkedHashMap<>();
        if (proposed != null) {
            for (IdentifierLocation location : proposed) {
                String requestedSpelling = allowed.get(normalized(location.identifier()));
                if (requestedSpelling == null || accepted.containsKey(normalized(requestedSpelling))) continue;
                accepted.put(normalized(requestedSpelling), new IdentifierLocation(
                        requestedSpelling, location.x(), location.y(), location.width(), location.height()));
            }
        }
        return accepted.size() < 4 ? List.of() : List.copyOf(accepted.values());
    }

    static List<IdentifierCellInput> cells(PageImage page, List<IdentifierLocation> locations) {
        if (locations == null || locations.size() < 4) return List.of();
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(page.content()));
            if (source == null) throw new IllegalArgumentException("rulebook page image cannot be decoded");
            List<Row> rows = rows(locations);
            List<IdentifierCellInput> cells = new ArrayList<>();
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Row row = rows.get(rowIndex);
                int previousBaseline = rowIndex == 0 ? -1 : rows.get(rowIndex - 1).baseline();
                int top = Math.max(0, Math.max(row.baseline() - 190, previousBaseline + 15));
                int bottom = Math.min(PAGE_SIZE, row.baseline() + 35);
                List<IdentifierLocation> ordered = row.locations().stream()
                        .sorted(Comparator.comparingInt(IdentifierLocation::x))
                        .toList();
                for (int column = 0; column < ordered.size(); column++) {
                    IdentifierLocation current = ordered.get(column);
                    int left = Math.max(0, current.x() - 25);
                    int right = column + 1 < ordered.size()
                            ? Math.max(left + 200, ordered.get(column + 1).x() - 25)
                            : PAGE_SIZE - Math.min(20, left);
                    right = Math.min(PAGE_SIZE, right);
                    if (right - left < 200 || bottom - top < 100) continue;
                    cells.add(new IdentifierCellInput(
                            current.identifier(),
                            new PageImageInput(page.pageNumber(), "image/jpeg", crop(source, left, top, right, bottom))));
                }
            }
            return List.copyOf(cells);
        } catch (IOException exception) {
            throw new UncheckedIOException("could not crop printed identifier cells", exception);
        }
    }

    static PageImageInput referenceCrop(PageImage page, int x, int y, int width, int height) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(page.content()));
            if (source == null) throw new IllegalArgumentException("rulebook reference page image cannot be decoded");
            int left = Math.max(0, x - 30);
            int top = Math.max(0, y - 30);
            int right = Math.min(PAGE_SIZE, x + width + 30);
            int bottom = Math.min(PAGE_SIZE, y + height + 30);
            return new PageImageInput(page.pageNumber(), "image/jpeg", crop(source, left, top, right, bottom));
        } catch (IOException exception) {
            throw new UncheckedIOException("could not crop identifier reference page", exception);
        }
    }

    private static List<Row> rows(List<IdentifierLocation> locations) {
        List<RowBuilder> rows = new ArrayList<>();
        locations.stream()
                .sorted(Comparator.comparingInt(PrintedIdentifierCellPolicy::centerY))
                .forEach(location -> {
                    RowBuilder current = rows.isEmpty() ? null : rows.getLast();
                    if (current == null || Math.abs(current.baseline() - centerY(location)) > SAME_ROW_TOLERANCE) {
                        current = new RowBuilder();
                        rows.add(current);
                    }
                    current.add(location);
                });
        return rows.stream().map(RowBuilder::build).toList();
    }

    static int centerY(IdentifierLocation location) {
        return location.y() + location.height() / 2;
    }

    private static byte[] crop(BufferedImage source, int left, int top, int right, int bottom) throws IOException {
        int pixelLeft = left * source.getWidth() / PAGE_SIZE;
        int pixelTop = top * source.getHeight() / PAGE_SIZE;
        int pixelRight = Math.min(source.getWidth(), (right * source.getWidth() + 999) / PAGE_SIZE);
        int pixelBottom = Math.min(source.getHeight(), (bottom * source.getHeight() + 999) / PAGE_SIZE);
        BufferedImage selected = source.getSubimage(
                pixelLeft, pixelTop, pixelRight - pixelLeft, pixelBottom - pixelTop);
        double readableScale = Math.max(
                1.0,
                Math.max(512.0 / selected.getWidth(), 512.0 / selected.getHeight()));
        readableScale = Math.min(
                readableScale,
                1_600.0 / Math.max(selected.getWidth(), selected.getHeight()));
        int targetWidth = Math.max(1, (int) Math.round(selected.getWidth() * readableScale));
        int targetHeight = Math.max(1, (int) Math.round(selected.getHeight() * readableScale));
        BufferedImage rgb = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(selected, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(rgb, "jpeg", output)) throw new IOException("JPEG image writer is unavailable");
        return output.toByteArray();
    }

    private static String normalized(String value) {
        return value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private record Row(int baseline, List<IdentifierLocation> locations) {}

    private static final class RowBuilder {
        private final List<IdentifierLocation> locations = new ArrayList<>();

        void add(IdentifierLocation location) {
            locations.add(location);
        }

        int baseline() {
            return (int) Math.round(locations.stream().mapToInt(PrintedIdentifierCellPolicy::centerY).average().orElse(0));
        }

        Row build() {
            return new Row(baseline(), List.copyOf(locations));
        }
    }
}
