package com.rulepilot.visualaid.adapter.out.pdf;

import com.rulepilot.visualaid.VisualRegionCatalog.Region;
import com.rulepilot.visualaid.application.VisualLayoutExtractor;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.cos.COSName;

/** Uses original PDF image placements; never guesses object boundaries from rendered pixels. */
@org.springframework.stereotype.Component
public final class PdfImageLayoutExtractor implements VisualLayoutExtractor {
    @Override public Extraction extract(InputStream source) {
        try (var document = Loader.loadPDF(source.readAllBytes())) {
            var regions = new ArrayList<Region>();
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                new ImagePlacements(document.getPage(index), index + 1, regions).processPage(document.getPage(index));
            }
            return new Extraction("pdf-image-placements", document.getNumberOfPages(), completeCompositions(regions));
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read source image placements", failure);
        }
    }

    /** Overlapping PDF images form one visible composition: a crop must not cut an overlaid component. */
    static List<Region> completeCompositions(List<Region> placements) {
        var remaining = new ArrayList<>(placements.stream().distinct().toList());
        var complete = new ArrayList<Region>();
        while (!remaining.isEmpty()) {
            var group = new ArrayList<Region>();
            group.add(remaining.removeFirst());
            boolean expanded;
            do {
                expanded = false;
                for (var iterator = remaining.iterator(); iterator.hasNext();) {
                    Region candidate = iterator.next();
                    if (group.stream().anyMatch(member -> member.pageNumber() == candidate.pageNumber()
                            && member.x() < candidate.x() + candidate.width() && candidate.x() < member.x() + member.width()
                            && member.y() < candidate.y() + candidate.height() && candidate.y() < member.y() + member.height())) {
                        group.add(candidate); iterator.remove(); expanded = true;
                    }
                }
            } while (expanded);
            int x = group.stream().mapToInt(Region::x).min().orElseThrow();
            int y = group.stream().mapToInt(Region::y).min().orElseThrow();
            int right = group.stream().mapToInt(r -> r.x() + r.width()).max().orElseThrow();
            int bottom = group.stream().mapToInt(r -> r.y() + r.height()).max().orElseThrow();
            if (x != 0 || y != 0 || right != 1000 || bottom != 1000) {
                complete.add(new Region(group.getFirst().pageNumber(), "PDF_IMAGE", x, y, right - x, bottom - y));
            }
        }
        return List.copyOf(complete);
    }

    private static final class ImagePlacements extends PDFGraphicsStreamEngine {
        private final int number;
        private final List<Region> regions;
        private Point2D current = new Point2D.Float();
        ImagePlacements(PDPage page, int number, List<Region> regions) {
            super(page); this.number = number; this.regions = regions;
        }
        @Override public void drawImage(PDImage image) {
            var box = getPage().getCropBox();
            var matrix = getGraphicsState().getCurrentTransformationMatrix();
            double left = Double.POSITIVE_INFINITY, top = left, right = Double.NEGATIVE_INFINITY, bottom = right;
            int rotation = Math.floorMod(getPage().getRotation(), 360);
            for (float[] corner : new float[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}}) {
                var point = matrix.transformPoint(corner[0], corner[1]);
                double x = (point.x - box.getLowerLeftX()) / box.getWidth();
                double y = 1 - (point.y - box.getLowerLeftY()) / box.getHeight();
                double px = switch (rotation) { case 90 -> 1 - y; case 180 -> 1 - x; case 270 -> y; default -> x; };
                double py = switch (rotation) { case 90 -> x; case 180 -> 1 - y; case 270 -> 1 - x; default -> y; };
                left = Math.min(left, px); top = Math.min(top, py); right = Math.max(right, px); bottom = Math.max(bottom, py);
            }
            // Outward rounding preserves the complete original object at normalized-coordinate precision.
            int x = Math.max(0, (int) Math.floor(left * 1000)), y = Math.max(0, (int) Math.floor(top * 1000));
            int r = Math.min(1000, (int) Math.ceil(right * 1000)), b = Math.min(1000, (int) Math.ceil(bottom * 1000));
            if (r - x < 20 || b - y < 20 || (x == 0 && y == 0 && r == 1000 && b == 1000)) return;
            regions.add(new Region(number, "PDF_IMAGE", x, y, r - x, b - y));
        }
        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) { current = p0; }
        @Override public void clip(int windingRule) { }
        @Override public void moveTo(float x, float y) { current = new Point2D.Float(x, y); }
        @Override public void lineTo(float x, float y) { current = new Point2D.Float(x, y); }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { current = new Point2D.Float(x3, y3); }
        @Override public Point2D getCurrentPoint() { return current; }
        @Override public void closePath() { }
        @Override public void endPath() { }
        @Override public void strokePath() { }
        @Override public void fillPath(int windingRule) { }
        @Override public void fillAndStrokePath(int windingRule) { }
        @Override public void shadingFill(COSName shadingName) { }
    }
}
