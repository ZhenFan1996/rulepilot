package com.rulepilot.teaching.application;

import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.util.List;

/** Geometry- and provenance-only policy for player-facing visual crops. */
final class VisualReaderCropPolicy {

    private static final int MIN_READER_VIEWPORT_WIDTH = 180;
    private static final int MIN_READER_VIEWPORT_HEIGHT = 120;
    private static final long MAX_READER_CROP_AREA = 600_000L;

    boolean isCompactReaderCrop(LocatedRegion region) {
        return (region.x() != 0 || region.y() != 0 || region.width() != 1_000 || region.height() != 1_000)
                && (long) region.width() * region.height() <= MAX_READER_CROP_AREA;
    }

    boolean needsTighterReaderCrop(VisualFocus focus) {
        return focus != null && (long) focus.width() * focus.height() > MAX_READER_CROP_AREA;
    }

    boolean isReadableForPlayer(LocatedRegion region) {
        return region.width() >= 32 && region.height() >= 32;
    }

    boolean needsReaderViewport(LocatedRegion region) {
        return region.width() < 80 || region.height() < 60;
    }

    boolean canExpandIntoReaderViewport(LocatedRegion region) {
        return !region.claimContradicted();
    }

    LocatedRegion expandIntoReaderViewport(LocatedRegion region) {
        int width = Math.max(MIN_READER_VIEWPORT_WIDTH, region.width());
        int height = Math.max(MIN_READER_VIEWPORT_HEIGHT, region.height());
        int x = centeredAndBounded(region.x(), region.width(), width);
        int y = centeredAndBounded(region.y(), region.height(), height);
        return new LocatedRegion(
                region.pageNumber(),
                region.label(),
                region.visibleDescription(),
                x,
                y,
                width,
                height,
                region.supportedEvidenceIds(),
                region.supportedStepPositions(),
                region.claimContradicted());
    }

    boolean isUsefulPlayerVisual(LocatedRegion region) {
        return !region.claimContradicted();
    }

    boolean intersectsCandidate(LocatedRegion region, List<VisualRegionCandidateSelector.Candidate> candidates) {
        return candidates.stream().anyMatch(candidate -> candidate.pageNumber() == region.pageNumber()
                && intersects(candidate.rectangle(), region.x(), region.y(), region.width(), region.height()));
    }

    boolean overlapsSubstantially(VisualFocus first, VisualFocus second) {
        if (first.pageNumber() != second.pageNumber()) return false;
        int overlapWidth = Math.max(0, Math.min(first.x() + first.width(), second.x() + second.width())
                - Math.max(first.x(), second.x()));
        int overlapHeight = Math.max(0, Math.min(first.y() + first.height(), second.y() + second.height())
                - Math.max(first.y(), second.y()));
        long overlapArea = (long) overlapWidth * overlapHeight;
        long smallerArea = Math.min((long) first.width() * first.height(), (long) second.width() * second.height());
        return smallerArea > 0 && overlapArea * 100 >= smallerArea * 75;
    }

    private int centeredAndBounded(int origin, int focusSize, int viewportSize) {
        int centered = origin + (focusSize - viewportSize) / 2;
        return Math.max(0, Math.min(1_000 - viewportSize, centered));
    }

    private boolean intersects(Rectangle candidate, int x, int y, int width, int height) {
        return candidate.x() < x + width && x < candidate.x() + candidate.width()
                && candidate.y() < y + height && y < candidate.y() + candidate.height();
    }
}
