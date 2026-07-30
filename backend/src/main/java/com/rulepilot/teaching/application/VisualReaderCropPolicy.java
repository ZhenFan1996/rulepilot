package com.rulepilot.teaching.application;

import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.util.List;
import java.util.Locale;

/** Pure reader-facing crop policy; source evidence and model decisions stay in the enrichment workflow. */
final class VisualReaderCropPolicy {

    private static final int MIN_READER_VIEWPORT_WIDTH = 180;
    private static final int MIN_READER_VIEWPORT_HEIGHT = 120;
    private static final long MAX_READER_CROP_AREA = 600_000L;

    boolean isCompactReaderCrop(LocatedRegion region) {
        return (region.x() != 0 || region.y() != 0 || region.width() != 1_000 || region.height() != 1_000)
                && (long) region.width() * region.height() <= MAX_READER_CROP_AREA
                && !isNarrowScoreExampleViewport(region.label() + " " + region.visibleDescription(), region.width(), region.height());
    }

    boolean needsTighterReaderCrop(VisualFocus focus) {
        return focus != null
                && ((long) focus.width() * focus.height() > MAX_READER_CROP_AREA || isNarrowScoreExampleViewport(focus));
    }

    boolean isReadableForPlayer(LocatedRegion region) {
        if (region.width() >= 80 && region.height() >= 60) return true;
        // A focused icon group may be small only before it is expanded into a reader-sized viewport.
        return region.width() >= 32 && region.height() >= 32 && hasCompactVisualHandle(region);
    }

    boolean needsReaderViewport(LocatedRegion region) {
        return region.width() < 80 || region.height() < 60;
    }

    boolean canExpandIntoReaderViewport(LocatedRegion region) {
        return !isTextOnlyFocus(region) && hasCompactVisualHandle(region);
    }

    /**
     * Vision commonly finds the exact icon first. Keep that literal observation, but show players enough surrounding
     * card, legend, arrow, or board state to recognise it without a microscopic cross-reference.
     */
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
        String description = region.visibleDescription().toLowerCase(Locale.ROOT);
        String label = region.label().toLowerCase(Locale.ROOT);
        return !description.contains("section header")
                && !description.contains("page title")
                && !description.contains("introduction paragraph")
                && !description.contains("text for")
                && !description.contains("list of")
                && !description.contains("段落")
                && !description.contains("文字描述")
                && !description.contains("介绍性段落")
                && !description.contains("章节标题")
                && !description.contains("页面标题")
                && !isTextOnlyFocus(region)
                && !label.contains("section header")
                && !label.contains("段落")
                && !label.matches(".*\\b(text|header|paragraph)\\b.*");
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

    private boolean isNarrowScoreExampleViewport(VisualFocus focus) {
        return focus != null && isNarrowScoreExampleViewport(focus.label(), focus.width(), focus.height());
    }

    private boolean isNarrowScoreExampleViewport(String description, int width, int height) {
        String normalized = description.toLowerCase(Locale.ROOT);
        boolean scoreExample = containsAny(normalized, "计分", "得分", "分数", "score", "scoring", "points")
                && containsAny(normalized, "示例", "example");
        return scoreExample && width <= 340 && height * 4 > width * 3;
    }

    private int centeredAndBounded(int origin, int focusSize, int viewportSize) {
        int centered = origin + (focusSize - viewportSize) / 2;
        return Math.max(0, Math.min(1_000 - viewportSize, centered));
    }

    private boolean hasCompactVisualHandle(LocatedRegion region) {
        String observation = observation(region);
        return observation.contains("图标")
                || observation.contains("符号")
                || observation.contains("令牌")
                || observation.contains("标记")
                || observation.contains("骰子")
                || observation.contains("箭头")
                || observation.contains("指示物")
                || observation.contains("花色")
                || observation.contains("卡牌")
                || observation.contains("棋子")
                || observation.contains("板块")
                || observation.contains("轨道")
                || observation.contains("地图")
                || observation.matches(".*\\b(icon|symbol|token|marker|die|dice|meeple|card|board|track|map|component)\\b.*");
    }

    private boolean isTextOnlyFocus(LocatedRegion region) {
        String observation = observation(region);
        if (isStructuredPlayerReference(observation)) return false;
        return observation.contains("文字")
                || observation.contains("文本")
                || observation.contains("规则框")
                || observation.contains("词语")
                || observation.contains("标签文字")
                || observation.contains("组件列表")
                || observation.contains("配件清单")
                || observation.matches(".*\\b(word|text|printed label|label only|text box|rule box|contents|table of contents|component list|parts list)\\b.*");
    }

    /** A scorepad, turn-order table, or icon legend is still useful even when it contains printed labels. */
    private boolean isStructuredPlayerReference(String observation) {
        return observation.contains("计分表")
                || observation.contains("分数表")
                || observation.contains("对照表")
                || observation.contains("流程图")
                || observation.contains("顺序表")
                || observation.contains("阶段表")
                || observation.contains("表格")
                || observation.matches(".*\\b(scorepad|score table|scoring table|reference table|flowchart|sequence chart)\\b.*");
    }

    private boolean intersects(Rectangle candidate, int x, int y, int width, int height) {
        return candidate.x() < x + width && x < candidate.x() + candidate.width()
                && candidate.y() < y + height && y < candidate.y() + candidate.height();
    }

    private String observation(LocatedRegion region) {
        return (region.label() + " " + region.visibleDescription()).toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String value, String... tokens) {
        return java.util.Arrays.stream(tokens).anyMatch(value::contains);
    }
}
