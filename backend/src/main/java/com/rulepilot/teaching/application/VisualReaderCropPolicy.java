package com.rulepilot.teaching.application;

import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.util.List;

/** Geometry- and provenance-only policy for player-facing visual crops. */
final class VisualReaderCropPolicy {

    boolean isReadableForPlayer(LocatedRegion region) {
        return region.width() >= 32 && region.height() >= 32;
    }

    boolean isUsefulPlayerVisual(LocatedRegion region) {
        return !region.claimContradicted();
    }

    boolean matchesCandidate(LocatedRegion region, List<VisualRegionCandidateSelector.Candidate> candidates) {
        return candidates.stream().anyMatch(candidate -> candidate.pageNumber() == region.pageNumber()
                && candidate.sourceKind() == region.sourceKind()
                && candidate.rectangle().equals(new Rectangle(
                        region.x(), region.y(), region.width(), region.height())));
    }

    boolean overlapsSubstantially(VisualFocus first, VisualFocus second) {
        if (first.pageNumber() != second.pageNumber() || first.sourceKind() != second.sourceKind()) return false;
        int overlapWidth = Math.max(0, Math.min(first.x() + first.width(), second.x() + second.width())
                - Math.max(first.x(), second.x()));
        int overlapHeight = Math.max(0, Math.min(first.y() + first.height(), second.y() + second.height())
                - Math.max(first.y(), second.y()));
        long overlapArea = (long) overlapWidth * overlapHeight;
        long smallerArea = Math.min((long) first.width() * first.height(), (long) second.width() * second.height());
        return smallerArea > 0 && overlapArea * 100 >= smallerArea * 75;
    }

    boolean overlapsSubstantially(
            VisualRegionCandidateSelector.Candidate candidate,
            VisualFocus existing) {
        if (candidate.pageNumber() != existing.pageNumber()
                || candidate.sourceKind() != existing.sourceKind()) return false;
        Rectangle rectangle = candidate.rectangle();
        int overlapWidth = Math.max(0, Math.min(rectangle.x() + rectangle.width(), existing.x() + existing.width())
                - Math.max(rectangle.x(), existing.x()));
        int overlapHeight = Math.max(0, Math.min(rectangle.y() + rectangle.height(), existing.y() + existing.height())
                - Math.max(rectangle.y(), existing.y()));
        long overlapArea = (long) overlapWidth * overlapHeight;
        long smallerArea = Math.min(
                (long) rectangle.width() * rectangle.height(),
                (long) existing.width() * existing.height());
        return smallerArea > 0 && overlapArea * 100 >= smallerArea * 75;
    }

}
