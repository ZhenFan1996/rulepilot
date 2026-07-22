package com.rulepilot.teaching.application;

import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.ingestion.RulebookUnderstandingCatalog;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionLocator.Claim;
import com.rulepilot.teaching.VisualRegionLocator.PageImage;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Adds a verified visual reference without changing an already published text lesson. */
@Service
@Profile("!test")
public class VisualLessonEnricher {

    private static final Logger log = LoggerFactory.getLogger(VisualLessonEnricher.class);
    private static final int MIN_READER_VIEWPORT_WIDTH = 180;
    private static final int MIN_READER_VIEWPORT_HEIGHT = 120;

    private final RulebookUnderstandingCatalog understanding;
    private final DocumentPageImages pageImages;
    private final VisualRegionCandidateSelector candidates;
    private final VisualRegionLocator locator;
    private final VisualSectionPrioritizer prioritizer;
    private final int maxSections;
    private final int maxVisualStepsPerSection;

    @Autowired
    public VisualLessonEnricher(
            RulebookUnderstandingCatalog understanding,
            DocumentPageImages pageImages,
            VisualRegionCandidateSelector candidates,
            @Qualifier("boundedVisualRegionLocator") VisualRegionLocator locator,
            VisualSectionPrioritizer prioritizer,
            @Value("${rulepilot.visual.max-sections:12}") int maxSections,
            @Value("${rulepilot.visual.max-steps-per-section:6}") int maxVisualStepsPerSection) {
        this.understanding = understanding;
        this.pageImages = pageImages;
        this.candidates = candidates;
        this.locator = locator;
        this.prioritizer = prioritizer;
        if (maxSections < 1 || maxSections > 20) {
            throw new IllegalArgumentException("visual section limit must be between one and twenty");
        }
        if (maxVisualStepsPerSection < 1 || maxVisualStepsPerSection > 6) {
            throw new IllegalArgumentException("visual step limit must be between one and six");
        }
        this.maxSections = maxSections;
        this.maxVisualStepsPerSection = maxVisualStepsPerSection;
    }

    public VisualLessonEnricher(
            RulebookUnderstandingCatalog understanding,
            DocumentPageImages pageImages,
            VisualRegionCandidateSelector candidates,
            VisualRegionLocator locator) {
        this(understanding, pageImages, candidates, locator, new VisualSectionPrioritizer(), 12, 6);
    }

    public IllustratedLesson enrich(UUID documentVersionId, IllustratedLesson lesson) {
        return enrich(documentVersionId, lesson, null);
    }

    public IllustratedLesson enrich(UUID documentVersionId, IllustratedLesson lesson, String modelConfigurationOwner) {
        return enrichWithReport(documentVersionId, lesson, modelConfigurationOwner).lesson();
    }

    /**
     * Returns bounded per-section observations so optional visual work can be inspected without changing the lesson
     * when a crop is unavailable.
     */
    public EnrichmentResult enrichWithReport(
            UUID documentVersionId, IllustratedLesson lesson, String modelConfigurationOwner) {
        var map = understanding.understanding(documentVersionId);
        Set<Integer> selectedPositions = prioritizer.positions(
                lesson.sections(), maxSections, maxVisualStepsPerSection);
        List<VisualFocus> acceptedVisuals = lesson.sections().stream()
                .flatMap(section -> section.steps().stream())
                .map(LessonStep::visualFocus)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));
        List<SectionResult> sectionResults = new ArrayList<>();
        for (LessonSection section : lesson.sections()) {
            if (!selectedPositions.contains(section.position())) continue;
            SectionResult enriched = enrichSection(map, documentVersionId, section, modelConfigurationOwner);
            sectionResults.add(keepDistinctVisuals(section, enriched, acceptedVisuals));
        }
        Map<Integer, SectionResult> byPosition = sectionResults.stream()
                .collect(Collectors.toMap(result -> result.section().position(), result -> result));
        List<LessonSection> sections = lesson.sections().stream()
                .map(section -> byPosition.getOrDefault(section.position(), new SectionResult(section, null)).section())
                .toList();
        IllustratedLesson enriched = new IllustratedLesson(
                lesson.id(), lesson.teachingPlanId(), lesson.status(), sections, lesson.generatorVersion(), lesson.createdAt());
        return new EnrichmentResult(
                enriched,
                sectionResults.stream().map(SectionResult::outcome).filter(java.util.Objects::nonNull).toList());
    }

    private SectionResult enrichSection(
            com.rulepilot.ingestion.layout.RulebookUnderstanding understanding,
            UUID documentVersionId,
            LessonSection section,
            String modelConfigurationOwner) {
        int existingVisualSteps = (int) section.steps().stream()
                .filter(step -> step.kind() == TeachingMove.VISUAL)
                .count();
        if (existingVisualSteps >= maxVisualStepsPerSection) {
            return result(section, Outcome.ALREADY_PRESENT);
        }
        List<VisualRegionLocator.LocatedRegion> accepted = new ArrayList<>();
        Outcome rejected = null;
        int availableStepSlots = (int) section.steps().stream().filter(step -> step.kind() != TeachingMove.VISUAL).count();
        int limit = Math.min(maxVisualStepsPerSection - existingVisualSteps, availableStepSlots);
        for (LessonStep step : visualTargets(section, limit)) {
            StepLocation location = locateForStep(
                    understanding, documentVersionId, section, step, modelConfigurationOwner);
            if (location.region() != null) accepted.add(location.region());
            else if (location.rejection() != null) rejected = location.rejection();
        }
        if (accepted.isEmpty()) return result(section, rejected == null ? Outcome.LOCATOR_RETURNED_NONE : rejected);
        MergedVisualSection merged = mergeVisualIntoSupportedSteps(section, accepted);
        if (merged.addedCount() == 0) return result(section, Outcome.REJECTED_UNKNOWN_EVIDENCE);
        return new SectionResult(merged.section(), new SectionOutcome(
                section.position(), Outcome.ADDED, addedSummary(section.position(), merged.addedCount())));
    }

    /**
     * Vision is strongest when it is asked to ground one player action at a time. Passing every paragraph from a
     * section invites a model to attach a perfectly real diagram to the wrong neighbouring rule.
     */
    private List<LessonStep> visualTargets(LessonSection section, int limit) {
        return section.steps().stream()
                .filter(step -> step.kind() != TeachingMove.VISUAL)
                .filter(step -> !step.sourcePages().isEmpty() && !step.sourceChunkIds().isEmpty())
                .sorted(java.util.Comparator.comparingInt(this::visualAffinity).reversed()
                        .thenComparingInt(LessonStep::position))
                .limit(limit)
                .toList();
    }

    private int visualAffinity(LessonStep step) {
        String target = (step.heading() + " " + step.text()).toLowerCase(java.util.Locale.ROOT);
        int score = 0;
        for (String cue : List.of(
                "图标", "符号", "卡牌", "卡片", "玩家板", "棋盘", "网格", "地图", "轨道", "骰子", "资源",
                "令牌", "标记", "方块", "建筑", "放置", "建造", "布局", "计分", "分数", "示例", "组件",
                "icon", "symbol", "card", "board", "grid", "map", "track", "dice", "resource", "token",
                "marker", "building", "score", "example", "component")) {
            if (target.contains(cue)) score++;
        }
        return score;
    }

    private StepLocation locateForStep(
            com.rulepilot.ingestion.layout.RulebookUnderstanding understanding,
            UUID documentVersionId,
            LessonSection section,
            LessonStep step,
            String modelConfigurationOwner) {
        Set<Integer> citedPages = new LinkedHashSet<>(step.sourcePages());
        List<VisualRegionCandidateSelector.Candidate> selected = candidates.select(
                understanding, citedPages, terms(section, step));
        if (selected.isEmpty()) return StepLocation.rejected(Outcome.NO_CITED_CANDIDATE);
        List<Integer> candidatePageOrder = selected.stream().map(VisualRegionCandidateSelector.Candidate::pageNumber)
                .distinct()
                .toList();
        Map<Integer, DocumentPageImages.PageImage> availablePages = pageImages.read(
                        documentVersionId, new LinkedHashSet<>(candidatePageOrder))
                .stream()
                .collect(Collectors.toMap(
                        DocumentPageImages.PageImage::pageNumber, image -> image, (first, ignored) -> first));
        List<PageImage> pages = candidatePageOrder.stream()
                .map(availablePages::get)
                .filter(java.util.Objects::nonNull)
                .limit(2)
                .map(image -> new PageImage(image.pageNumber(), image.mediaType(), image.content()))
                .toList();
        if (pages.isEmpty()) return StepLocation.rejected(Outcome.NO_PAGE_IMAGE);
        Set<Integer> attachedPages = pages.stream().map(PageImage::pageNumber).collect(Collectors.toUnmodifiableSet());
        List<VisualRegionCandidateSelector.Candidate> attachedCandidates = selected.stream()
                .filter(candidate -> attachedPages.contains(candidate.pageNumber()))
                .toList();
        List<Claim> claims = claims(step);
        var guide = locator.locateGuideWithResult(new VisualRegionLocator.VisualLocationRequest(
                section.title() + " · " + step.heading(), claims, attachedCandidates, pages, modelConfigurationOwner));
        if (guide.regions().isEmpty()) return StepLocation.rejected(outcomeFor(guide.diagnostic()));
        Set<UUID> evidenceIds = claims.stream().map(Claim::evidenceId).collect(Collectors.toSet());
        Outcome rejected = null;
        for (VisualRegionLocator.LocatedRegion candidate : guide.regions()) {
            VisualRegionLocator.LocatedRegion region = candidate;
            if (needsReaderViewport(region)) {
                if (!canExpandIntoReaderViewport(region)) {
                    rejected = Outcome.REJECTED_TOO_SMALL;
                    continue;
                }
                region = expandIntoReaderViewport(region);
            }
            Outcome rejection = rejectionFor(region, attachedCandidates, evidenceIds);
            if (rejection == null && !supportsExactStep(region, step)) {
                rejection = Outcome.REJECTED_STEP_MISMATCH;
            }
            if (rejection == null && !directlyIllustrates(step, region)) {
                rejection = Outcome.REJECTED_STEP_MISMATCH;
            }
            if (rejection == null) return StepLocation.accepted(region);
            rejected = rejection;
        }
        return StepLocation.rejected(rejected == null ? Outcome.REJECTED_UNKNOWN_EVIDENCE : rejected);
    }

    private boolean supportsExactStep(VisualRegionLocator.LocatedRegion region, LessonStep step) {
        return region.supportedStepPositions().isEmpty() || region.supportedStepPositions().contains(step.position());
    }

    /**
     * Reject only clear category errors that a same-page citation cannot catch. This remains a guard rather than a
     * semantic rule interpreter: vision still decides whether cards, icons, layouts, and examples are useful.
     */
    private boolean directlyIllustrates(LessonStep step, VisualRegionLocator.LocatedRegion region) {
        String heading = step.heading().toLowerCase(java.util.Locale.ROOT);
        String description = region.visibleDescription().toLowerCase(java.util.Locale.ROOT);
        String observation = (region.label() + " " + description).toLowerCase(java.util.Locale.ROOT);
        if (containsAny(heading, "放置", "移动", "移除", "place", "move", "remove")
                && containsAny(description, "初始设置", "初始布局", "组件总览", "组件摆放", "setup overview", "component overview", "component layout")
                && !containsAny(description, "已放置", "放入", "放在", "位于格", "箭头", "移除", "placed", "placement", "arrow", "removed")) {
            return false;
        }
        if (containsAny(heading, "玩家板", "个人板", "player board", "player mat")) {
            return containsAny(observation, "玩家板", "个人板", "棋盘", "网格", "board", "grid", "town");
        }
        if (containsAny(heading, "主建筑师", "起始玩家", "master builder", "first player")) {
            return containsAny(observation, "主建筑师", "起始玩家", "锤", "标记", "master builder", "first player", "hammer", "marker");
        }
        if (containsAny(heading, "结束", "终局", "game over", "end of game")) {
            return containsAny(observation, "结束", "终局", "最后", "game over", "end of game", "final");
        }
        return true;
    }

    private boolean containsAny(String value, String... tokens) {
        return java.util.Arrays.stream(tokens).anyMatch(value::contains);
    }

    private Outcome rejectionFor(
            VisualRegionLocator.LocatedRegion region,
            List<VisualRegionCandidateSelector.Candidate> attachedCandidates,
            Set<UUID> evidenceIds) {
        if (!isCompactReaderCrop(region)) return Outcome.REJECTED_WHOLE_PAGE;
        if (!isReadableForPlayer(region)) return Outcome.REJECTED_TOO_SMALL;
        if (region.visibleDescription().isBlank()) return Outcome.REJECTED_MISSING_OBSERVATION;
        if (!isUsefulPlayerVisual(region)) return Outcome.REJECTED_NON_VISUAL;
        if (!intersectsCandidate(region, attachedCandidates)) return Outcome.REJECTED_OUTSIDE_CANDIDATE;
        if (!evidenceIds.containsAll(region.supportedEvidenceIds())) return Outcome.REJECTED_UNKNOWN_EVIDENCE;
        return null;
    }

    private SectionResult result(LessonSection section, Outcome outcome) {
        return new SectionResult(section, new SectionOutcome(section.position(), outcome, outcomeSummary(section.position(), outcome)));
    }

    private String addedSummary(int sectionPosition, int count) {
        return count == 1
                ? outcomeSummary(sectionPosition, Outcome.ADDED)
                : "第 " + sectionPosition + " 节已加入 " + count + " 处可核对的局部规则书截图";
    }

    private Outcome outcomeFor(VisualRegionLocator.Diagnostic diagnostic) {
        return switch (diagnostic) {
            case NO_REGION -> Outcome.LOCATOR_RETURNED_NONE;
            case MODEL_UNAVAILABLE -> Outcome.MODEL_UNAVAILABLE;
            case EXPLICIT_NO_REGION -> Outcome.MODEL_EXPLICIT_NO_REGION;
            case MALFORMED_RESPONSE -> Outcome.MODEL_MALFORMED_RESPONSE;
            case UNSUPPORTED_SCOPE -> Outcome.MODEL_UNSUPPORTED_SCOPE;
            case INVALID_GEOMETRY -> Outcome.MODEL_INVALID_GEOMETRY;
            case NON_CHINESE_OBSERVATION -> Outcome.MODEL_NON_CHINESE_OBSERVATION;
            case TIMEOUT -> Outcome.MODEL_TIMEOUT;
            case INTERRUPTED -> Outcome.MODEL_INTERRUPTED;
            case EXECUTOR_BUSY -> Outcome.MODEL_BUSY;
            case PROVIDER_FAILURE -> Outcome.MODEL_PROVIDER_FAILURE;
            case FOUND -> throw new IllegalArgumentException("found visual location cannot be rejected");
        };
    }

    private String outcomeSummary(int sectionPosition, Outcome outcome) {
        return switch (outcome) {
            case ADDED -> "第 " + sectionPosition + " 节已加入可核对的局部规则书截图";
            case ALREADY_PRESENT -> "第 " + sectionPosition + " 节已有局部规则书截图，无需重复处理";
            case NO_CITED_CANDIDATE -> "第 " + sectionPosition + " 节没有可引用的图片候选区域";
            case NO_PAGE_IMAGE -> "第 " + sectionPosition + " 节的候选页没有可用图片";
            case LOCATOR_RETURNED_NONE -> "第 " + sectionPosition + " 节的视觉模型未找到可靠局部图示";
            case MODEL_UNAVAILABLE -> "第 " + sectionPosition + " 节没有可用的视觉模型";
            case MODEL_EXPLICIT_NO_REGION -> "第 " + sectionPosition + " 节的视觉模型确认没有合适局部图示";
            case MODEL_MALFORMED_RESPONSE -> "第 " + sectionPosition + " 节的视觉模型没有返回可用坐标";
            case MODEL_UNSUPPORTED_SCOPE -> "第 " + sectionPosition + " 节的视觉模型引用了未提供的页面或依据";
            case MODEL_INVALID_GEOMETRY -> "第 " + sectionPosition + " 节的视觉模型返回了无效截图坐标";
            case MODEL_NON_CHINESE_OBSERVATION -> "第 " + sectionPosition + " 节的视觉模型没有给出可读的中文图中说明，已跳过";
            case MODEL_TIMEOUT -> "第 " + sectionPosition + " 节的视觉模型响应超时";
            case MODEL_INTERRUPTED -> "第 " + sectionPosition + " 节的视觉模型工作被安全中断";
            case MODEL_BUSY -> "第 " + sectionPosition + " 节的视觉模型正在处理其他任务";
            case MODEL_PROVIDER_FAILURE -> "第 " + sectionPosition + " 节的视觉模型调用失败，已保留正文";
            case REJECTED_WHOLE_PAGE -> "第 " + sectionPosition + " 节返回整页，未把它误作局部讲解";
            case REJECTED_TOO_SMALL -> "第 " + sectionPosition + " 节的截图太小，无法辅助玩家理解，已跳过";
            case REJECTED_MISSING_OBSERVATION -> "第 " + sectionPosition + " 节的截图没有可核对的图中说明，已跳过";
            case REJECTED_NON_VISUAL -> "第 " + sectionPosition + " 节返回的区域只有文字或标题，已跳过";
            case REJECTED_OUTSIDE_CANDIDATE -> "第 " + sectionPosition + " 节返回区域不在可引用范围内，已跳过";
            case REJECTED_UNKNOWN_EVIDENCE -> "第 " + sectionPosition + " 节的截图没有对应规则依据，已跳过";
            case REJECTED_DUPLICATE -> "第 " + sectionPosition + " 节的截图与前文高度重复，已保留原规则步骤";
            case REJECTED_STEP_MISMATCH -> "第 " + sectionPosition + " 节的截图没有直接对应当前步骤，已保留原规则步骤";
        };
    }

    public record EnrichmentResult(IllustratedLesson lesson, List<SectionOutcome> outcomes) {
        public EnrichmentResult {
            if (lesson == null || outcomes == null) throw new IllegalArgumentException("visual enrichment result is invalid");
            outcomes = List.copyOf(outcomes);
        }
    }

    public record SectionOutcome(int sectionPosition, Outcome outcome, String summary) {
        public SectionOutcome {
            if (sectionPosition < 1 || outcome == null || summary == null || summary.isBlank() || summary.length() > 240) {
                throw new IllegalArgumentException("visual enrichment section outcome is invalid");
            }
            summary = summary.strip();
        }
    }

    public enum Outcome {
        ADDED,
        ALREADY_PRESENT,
        NO_CITED_CANDIDATE,
        NO_PAGE_IMAGE,
        LOCATOR_RETURNED_NONE,
        MODEL_UNAVAILABLE,
        MODEL_EXPLICIT_NO_REGION,
        MODEL_MALFORMED_RESPONSE,
        MODEL_UNSUPPORTED_SCOPE,
        MODEL_INVALID_GEOMETRY,
        MODEL_NON_CHINESE_OBSERVATION,
        MODEL_TIMEOUT,
        MODEL_INTERRUPTED,
        MODEL_BUSY,
        MODEL_PROVIDER_FAILURE,
        REJECTED_WHOLE_PAGE,
        REJECTED_TOO_SMALL,
        REJECTED_MISSING_OBSERVATION,
        REJECTED_NON_VISUAL,
        REJECTED_OUTSIDE_CANDIDATE,
        REJECTED_UNKNOWN_EVIDENCE,
        REJECTED_DUPLICATE,
        REJECTED_STEP_MISMATCH
    }

    private record SectionResult(LessonSection section, SectionOutcome outcome) {}

    private List<String> terms(LessonSection section, LessonStep step) {
        List<String> result = new ArrayList<>();
        result.add(section.title());
        result.addAll(section.coverageTags());
        result.add(step.heading());
        result.add(step.text());
        return List.copyOf(result);
    }

    private List<Claim> claims(LessonStep step) {
        return new LinkedHashSet<>(step.sourceChunkIds()).stream()
                .map(id -> new Claim(id, claimText(step), step.sourcePages(), step.position()))
                .toList();
    }

    /** The heading gives vision an unambiguous target when adjacent steps cite the same prose chunk. */
    private String claimText(LessonStep step) {
        String prefix = "步骤 " + step.position() + "（" + step.heading() + "）：";
        int remaining = 600 - prefix.length();
        if (remaining <= 0) return prefix.substring(0, 600);
        String body = step.text();
        return body.length() <= remaining ? prefix + body : prefix + body.substring(0, remaining);
    }

    private boolean intersectsCandidate(
            VisualRegionLocator.LocatedRegion region, List<VisualRegionCandidateSelector.Candidate> candidates) {
        return candidates.stream().anyMatch(candidate -> candidate.pageNumber() == region.pageNumber()
                && intersects(candidate.rectangle(), region.x(), region.y(), region.width(), region.height()));
    }

    private boolean isCompactReaderCrop(VisualRegionLocator.LocatedRegion region) {
        return region.x() != 0 || region.y() != 0 || region.width() != 1_000 || region.height() != 1_000;
    }

    private boolean isReadableForPlayer(VisualRegionLocator.LocatedRegion region) {
        if (region.width() >= 80 && region.height() >= 60) return true;
        // A focused icon group may be small only before it is expanded into a reader-sized viewport.
        return region.width() >= 32 && region.height() >= 32 && hasCompactVisualHandle(region);
    }

    private boolean needsReaderViewport(VisualRegionLocator.LocatedRegion region) {
        return region.width() < 80 || region.height() < 60;
    }

    private boolean canExpandIntoReaderViewport(VisualRegionLocator.LocatedRegion region) {
        return !isTextOnlyFocus(region) && hasCompactVisualHandle(region);
    }

    /**
     * Vision commonly finds the exact icon first. Keep that literal observation, but show players enough surrounding
     * card, legend, arrow, or board state to recognise it without having to cross-reference a microscopic crop.
     */
    private VisualRegionLocator.LocatedRegion expandIntoReaderViewport(VisualRegionLocator.LocatedRegion region) {
        int width = Math.max(MIN_READER_VIEWPORT_WIDTH, region.width());
        int height = Math.max(MIN_READER_VIEWPORT_HEIGHT, region.height());
        int x = centeredAndBounded(region.x(), region.width(), width);
        int y = centeredAndBounded(region.y(), region.height(), height);
        return new VisualRegionLocator.LocatedRegion(
                region.pageNumber(),
                region.label(),
                region.visibleDescription(),
                x,
                y,
                width,
                height,
                region.supportedEvidenceIds(),
                region.supportedStepPositions());
    }

    private int centeredAndBounded(int origin, int focusSize, int viewportSize) {
        int centered = origin + (focusSize - viewportSize) / 2;
        return Math.max(0, Math.min(1_000 - viewportSize, centered));
    }

    private boolean isUsefulPlayerVisual(VisualRegionLocator.LocatedRegion region) {
        String description = region.visibleDescription().toLowerCase(java.util.Locale.ROOT);
        String label = region.label().toLowerCase(java.util.Locale.ROOT);
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

    private boolean hasCompactVisualHandle(VisualRegionLocator.LocatedRegion region) {
        String observation = (region.label() + " " + region.visibleDescription()).toLowerCase(java.util.Locale.ROOT);
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

    private boolean isTextOnlyFocus(VisualRegionLocator.LocatedRegion region) {
        String observation = (region.label() + " " + region.visibleDescription()).toLowerCase(java.util.Locale.ROOT);
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

    /**
     * A scorepad, turn-order table, or icon legend is a visual reference even when it contains printed labels.
     * It gives a player a compact relationship to inspect; a prose rule box still does not.
     */
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

    private boolean isIconFocused(VisualRegionLocator.LocatedRegion region) {
        String observation = (region.label() + " " + region.visibleDescription()).toLowerCase(java.util.Locale.ROOT);
        return observation.contains("图标")
                || observation.contains("符号")
                || observation.contains("图例")
                || observation.matches(".*\\b(icon|symbol|legend)\\b.*");
    }

    private boolean intersects(Rectangle candidate, int x, int y, int width, int height) {
        return candidate.x() < x + width && x < candidate.x() + candidate.width()
                && candidate.y() < y + height && y < candidate.y() + candidate.height();
    }

    /**
     * A crop is a reading aid, not decorative repetition. Keep the first grounded use of a substantially identical
     * viewport and leave later steps as their original rule prose so a player does not see the same diagram twice.
     */
    private SectionResult keepDistinctVisuals(
            LessonSection original,
            SectionResult candidate,
            List<VisualFocus> acceptedVisuals) {
        if (candidate.outcome() == null || candidate.outcome().outcome() != Outcome.ADDED) return candidate;
        Map<Integer, LessonStep> originalSteps = original.steps().stream()
                .collect(Collectors.toMap(LessonStep::position, step -> step));
        List<LessonStep> filtered = new ArrayList<>();
        int added = 0;
        boolean duplicate = false;
        for (LessonStep step : candidate.section().steps()) {
            LessonStep originalStep = originalSteps.get(step.position());
            boolean newlyVisual = originalStep != null
                    && originalStep.kind() != TeachingMove.VISUAL
                    && step.kind() == TeachingMove.VISUAL
                    && step.visualFocus() != null;
            if (newlyVisual && acceptedVisuals.stream().anyMatch(existing -> overlapsSubstantially(
                    existing, step.visualFocus()))) {
                filtered.add(originalStep);
                duplicate = true;
                continue;
            }
            filtered.add(step);
            if (newlyVisual) {
                acceptedVisuals.add(step.visualFocus());
                added++;
            }
        }
        if (!duplicate) return candidate;
        if (added == 0) return result(original, Outcome.REJECTED_DUPLICATE);
        List<Integer> visualPages = original.visualSourcePages();
        List<UUID> visualChunks = original.visualSourceChunkIds();
        for (LessonStep step : filtered) {
            if (step.kind() == TeachingMove.VISUAL && step.visualFocus() != null) {
                visualPages = distinct(visualPages, step.visualFocus().pageNumber());
                visualChunks = distinct(visualChunks, step.sourceChunkIds());
            }
        }
        LessonSection distinct = new LessonSection(
                original.position(), original.topicKey(), original.coverageTags(), original.title(), original.required(),
                original.evidenceStatus(), original.visualKind(), original.visualCaption(),
                visualPages, visualChunks, filtered);
        return new SectionResult(distinct, new SectionOutcome(
                original.position(), Outcome.ADDED, addedSummary(original.position(), added)));
    }

    private boolean overlapsSubstantially(VisualFocus first, VisualFocus second) {
        if (first.pageNumber() != second.pageNumber()) return false;
        int overlapWidth = Math.max(0, Math.min(first.x() + first.width(), second.x() + second.width())
                - Math.max(first.x(), second.x()));
        int overlapHeight = Math.max(0, Math.min(first.y() + first.height(), second.y() + second.height())
                - Math.max(first.y(), second.y()));
        long overlapArea = (long) overlapWidth * overlapHeight;
        long smallerArea = Math.min((long) first.width() * first.height(), (long) second.width() * second.height());
        return smallerArea > 0 && overlapArea * 100 >= smallerArea * 75;
    }

    private MergedVisualSection mergeVisualIntoSupportedSteps(
            LessonSection section, List<VisualRegionLocator.LocatedRegion> regions) {
        List<LessonStep> steps = new ArrayList<>(section.steps());
        Set<Integer> availableIndexes = java.util.stream.IntStream.range(0, steps.size())
                .filter(index -> steps.get(index).kind() != TeachingMove.VISUAL)
                .boxed()
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Integer> sourcePages = section.visualSourcePages();
        List<UUID> sourceChunkIds = section.visualSourceChunkIds();
        int added = 0;
        for (VisualRegionLocator.LocatedRegion region : regions) {
            if (availableIndexes.isEmpty()) break;
            Set<UUID> supportedEvidence = Set.copyOf(region.supportedEvidenceIds());
            java.util.Optional<Integer> supportedStepIndex = availableIndexes.stream()
                    .filter(index -> region.supportedStepPositions().isEmpty()
                            || region.supportedStepPositions().contains(steps.get(index).position()))
                    .filter(index -> steps.get(index).sourcePages().contains(region.pageNumber()))
                    .filter(index -> steps.get(index).sourceChunkIds().stream().anyMatch(supportedEvidence::contains))
                    .findFirst();
            if (supportedStepIndex.isEmpty()) {
                log.info("Skipped visual region because no rule step shares its cited page {}", region.pageNumber());
                continue;
            }
            LessonStep supportedStep = steps.get(supportedStepIndex.get());
            String observation = stripTrailingPunctuation(region.visibleDescription());
            String visualText = visualText(observation, supportedStep.text(), isIconFocused(region));
            String label = containsHan(region.label()) ? region.label().strip() : supportedStep.heading();
            steps.set(supportedStepIndex.get(), new LessonStep(
                    supportedStep.position(),
                    supportedStep.heading(),
                    TeachingMove.VISUAL,
                    visualText,
                    distinct(supportedStep.sourcePages(), region.pageNumber()),
                    distinct(supportedStep.sourceChunkIds(), region.supportedEvidenceIds()),
                    new VisualFocus(region.pageNumber(), label, region.x(), region.y(), region.width(), region.height())));
            sourcePages = distinct(sourcePages, region.pageNumber());
            sourceChunkIds = distinct(sourceChunkIds, region.supportedEvidenceIds());
            availableIndexes.remove(supportedStepIndex.get());
            added++;
        }
        LessonSection enriched = new LessonSection(
                section.position(), section.topicKey(), section.coverageTags(), section.title(), section.required(),
                section.evidenceStatus(), section.visualKind(), section.visualCaption(),
                sourcePages, sourceChunkIds, steps);
        return new MergedVisualSection(enriched, added);
    }

    private String visualText(String observation, String ruleText, boolean iconCluster) {
        String prefix = iconCluster
                ? "图中图标提示：" + observation + "。先认出这组图标，再按规则处理："
                : "图中可见" + observation + "。结合图片完成这一步：";
        String combined = prefix + ruleText;
        return combined.length() <= 600 ? combined : ruleText;
    }

    private boolean containsHan(String text) {
        return text != null && text.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                == Character.UnicodeScript.HAN);
    }

    private <T> List<T> distinct(List<T> existing, T addition) {
        LinkedHashSet<T> values = new LinkedHashSet<>(existing);
        values.add(addition);
        return List.copyOf(values);
    }

    private <T> List<T> distinct(List<T> existing, List<T> additions) {
        LinkedHashSet<T> values = new LinkedHashSet<>(existing);
        values.addAll(additions);
        return List.copyOf(values);
    }

    private String stripTrailingPunctuation(String text) {
        return text == null ? "" : text.strip().replaceFirst("[。.!！?？]+$", "");
    }

    private record MergedVisualSection(LessonSection section, int addedCount) {}

    private record StepLocation(VisualRegionLocator.LocatedRegion region, Outcome rejection) {
        static StepLocation accepted(VisualRegionLocator.LocatedRegion region) {
            return new StepLocation(region, null);
        }

        static StepLocation rejected(Outcome rejection) {
            return new StepLocation(null, rejection);
        }
    }
}
