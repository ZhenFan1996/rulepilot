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
import java.util.LinkedHashMap;
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

    private final RulebookUnderstandingCatalog understanding;
    private final DocumentPageImages pageImages;
    private final VisualRegionCandidateSelector candidates;
    private final VisualRegionLocator locator;
    private final VisualSectionPrioritizer prioritizer;
    private final int maxSections;

    @Autowired
    public VisualLessonEnricher(
            RulebookUnderstandingCatalog understanding,
            DocumentPageImages pageImages,
            VisualRegionCandidateSelector candidates,
            @Qualifier("boundedVisualRegionLocator") VisualRegionLocator locator,
            VisualSectionPrioritizer prioritizer,
            @Value("${rulepilot.visual.max-sections:3}") int maxSections) {
        this.understanding = understanding;
        this.pageImages = pageImages;
        this.candidates = candidates;
        this.locator = locator;
        this.prioritizer = prioritizer;
        if (maxSections < 1 || maxSections > 6) {
            throw new IllegalArgumentException("visual section limit must be between one and six");
        }
        this.maxSections = maxSections;
    }

    public VisualLessonEnricher(
            RulebookUnderstandingCatalog understanding,
            DocumentPageImages pageImages,
            VisualRegionCandidateSelector candidates,
            VisualRegionLocator locator) {
        this(understanding, pageImages, candidates, locator, new VisualSectionPrioritizer(), 3);
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
        Set<Integer> selectedPositions = prioritizer.positions(lesson.sections(), maxSections);
        List<SectionResult> sectionResults = lesson.sections().stream()
                .filter(section -> selectedPositions.contains(section.position()))
                .map(section -> enrichSection(map, documentVersionId, section, modelConfigurationOwner))
                .toList();
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
        if (section.steps().stream().anyMatch(step -> step.kind() == TeachingMove.VISUAL)) {
            return result(section, Outcome.ALREADY_PRESENT);
        }
        Set<Integer> citedPages = section.steps().stream()
                .flatMap(step -> step.sourcePages().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<VisualRegionCandidateSelector.Candidate> selected = candidates.select(
                understanding, citedPages, terms(section));
        if (selected.isEmpty()) {
            log.info("No cited visual candidates for section {}", section.title());
            return result(section, Outcome.NO_CITED_CANDIDATE);
        }
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
        if (pages.isEmpty()) {
            log.info("No page image available for visual section {}", section.title());
            return result(section, Outcome.NO_PAGE_IMAGE);
        }
        Set<Integer> attachedPages = pages.stream()
                .map(PageImage::pageNumber)
                .collect(Collectors.toUnmodifiableSet());
        List<VisualRegionCandidateSelector.Candidate> attachedCandidates = selected.stream()
                .filter(candidate -> attachedPages.contains(candidate.pageNumber()))
                .toList();
        List<Claim> claims = claims(section);
        var location = locator.locateWithResult(new VisualRegionLocator.VisualLocationRequest(
                section.title(), claims, attachedCandidates, pages, modelConfigurationOwner));
        var located = location.region();
        if (located.isEmpty()) {
            log.info("No cited visual region accepted for section {}", section.title());
            return result(section, outcomeFor(location.diagnostic()));
        }
        if (!isCompactReaderCrop(located.get())) return result(section, Outcome.REJECTED_WHOLE_PAGE);
        if (located.get().visibleDescription().isBlank()) return result(section, Outcome.REJECTED_MISSING_OBSERVATION);
        if (!isUsefulPlayerVisual(located.get())) return result(section, Outcome.REJECTED_NON_VISUAL);
        if (!intersectsCandidate(located.get(), attachedCandidates)) return result(section, Outcome.REJECTED_OUTSIDE_CANDIDATE);
        Set<UUID> evidenceIds = claims.stream().map(Claim::evidenceId).collect(Collectors.toSet());
        if (!evidenceIds.containsAll(located.get().supportedEvidenceIds())) {
            log.info("Visual region cited an unknown claim for section {}", section.title());
            return result(section, Outcome.REJECTED_UNKNOWN_EVIDENCE);
        }
        return new SectionResult(mergeVisualIntoSupportedStep(section, located.get()), new SectionOutcome(
                section.position(), Outcome.ADDED, outcomeSummary(section.position(), Outcome.ADDED)));
    }

    private SectionResult result(LessonSection section, Outcome outcome) {
        return new SectionResult(section, new SectionOutcome(section.position(), outcome, outcomeSummary(section.position(), outcome)));
    }

    private Outcome outcomeFor(VisualRegionLocator.Diagnostic diagnostic) {
        return switch (diagnostic) {
            case NO_REGION -> Outcome.LOCATOR_RETURNED_NONE;
            case MODEL_UNAVAILABLE -> Outcome.MODEL_UNAVAILABLE;
            case EXPLICIT_NO_REGION -> Outcome.MODEL_EXPLICIT_NO_REGION;
            case MALFORMED_RESPONSE -> Outcome.MODEL_MALFORMED_RESPONSE;
            case UNSUPPORTED_SCOPE -> Outcome.MODEL_UNSUPPORTED_SCOPE;
            case INVALID_GEOMETRY -> Outcome.MODEL_INVALID_GEOMETRY;
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
            case MODEL_TIMEOUT -> "第 " + sectionPosition + " 节的视觉模型响应超时";
            case MODEL_INTERRUPTED -> "第 " + sectionPosition + " 节的视觉模型工作被安全中断";
            case MODEL_BUSY -> "第 " + sectionPosition + " 节的视觉模型正在处理其他任务";
            case MODEL_PROVIDER_FAILURE -> "第 " + sectionPosition + " 节的视觉模型调用失败，已保留正文";
            case REJECTED_WHOLE_PAGE -> "第 " + sectionPosition + " 节返回整页，未把它误作局部讲解";
            case REJECTED_MISSING_OBSERVATION -> "第 " + sectionPosition + " 节的截图没有可核对的图中说明，已跳过";
            case REJECTED_NON_VISUAL -> "第 " + sectionPosition + " 节返回的区域只有文字或标题，已跳过";
            case REJECTED_OUTSIDE_CANDIDATE -> "第 " + sectionPosition + " 节返回区域不在可引用范围内，已跳过";
            case REJECTED_UNKNOWN_EVIDENCE -> "第 " + sectionPosition + " 节的截图没有对应规则依据，已跳过";
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
        MODEL_TIMEOUT,
        MODEL_INTERRUPTED,
        MODEL_BUSY,
        MODEL_PROVIDER_FAILURE,
        REJECTED_WHOLE_PAGE,
        REJECTED_MISSING_OBSERVATION,
        REJECTED_NON_VISUAL,
        REJECTED_OUTSIDE_CANDIDATE,
        REJECTED_UNKNOWN_EVIDENCE
    }

    private record SectionResult(LessonSection section, SectionOutcome outcome) {}

    private List<String> terms(LessonSection section) {
        List<String> result = new ArrayList<>();
        result.add(section.title());
        result.addAll(section.coverageTags());
        section.steps().forEach(step -> {
            result.add(step.heading());
            result.add(step.text());
        });
        return List.copyOf(result);
    }

    private List<Claim> claims(LessonSection section) {
        Map<UUID, String> claims = new LinkedHashMap<>();
        section.steps().forEach(step -> step.sourceChunkIds()
                .forEach(id -> claims.putIfAbsent(id, step.text())));
        return claims.entrySet().stream().map(entry -> new Claim(entry.getKey(), entry.getValue())).toList();
    }

    private boolean intersectsCandidate(
            VisualRegionLocator.LocatedRegion region, List<VisualRegionCandidateSelector.Candidate> candidates) {
        return candidates.stream().anyMatch(candidate -> candidate.pageNumber() == region.pageNumber()
                && intersects(candidate.rectangle(), region.x(), region.y(), region.width(), region.height()));
    }

    private boolean isCompactReaderCrop(VisualRegionLocator.LocatedRegion region) {
        return region.x() != 0 || region.y() != 0 || region.width() != 1_000 || region.height() != 1_000;
    }

    private boolean isUsefulPlayerVisual(VisualRegionLocator.LocatedRegion region) {
        String description = region.visibleDescription().toLowerCase(java.util.Locale.ROOT);
        String label = region.label().toLowerCase(java.util.Locale.ROOT);
        return !description.contains("section header")
                && !description.contains("page title")
                && !description.contains("introduction paragraph")
                && !description.contains("text for")
                && !description.contains("list of")
                && !description.contains("介绍性段落")
                && !description.contains("章节标题")
                && !description.contains("页面标题")
                && !label.contains("section header")
                && !label.matches(".*\\b(text|header|paragraph)\\b.*");
    }

    private boolean intersects(Rectangle candidate, int x, int y, int width, int height) {
        return candidate.x() < x + width && x < candidate.x() + candidate.width()
                && candidate.y() < y + height && y < candidate.y() + candidate.height();
    }

    private LessonSection mergeVisualIntoSupportedStep(
            LessonSection section, VisualRegionLocator.LocatedRegion region) {
        List<LessonStep> steps = new ArrayList<>(section.steps());
        Set<UUID> supportedEvidence = Set.copyOf(region.supportedEvidenceIds());
        int supportedStepIndex = java.util.stream.IntStream.range(0, steps.size())
                .filter(index -> steps.get(index).sourceChunkIds().stream().anyMatch(supportedEvidence::contains))
                .findFirst()
                .orElse(0);
        LessonStep supportedStep = steps.get(supportedStepIndex);
        String observation = stripTrailingPunctuation(region.visibleDescription());
        String visualText = visualText(observation, supportedStep.text());
        String label = containsHan(region.label()) ? region.label().strip() : supportedStep.heading();
        steps.set(supportedStepIndex, new LessonStep(
                supportedStep.position(),
                supportedStep.heading(),
                TeachingMove.VISUAL,
                visualText,
                distinct(supportedStep.sourcePages(), region.pageNumber()),
                distinct(supportedStep.sourceChunkIds(), region.supportedEvidenceIds()),
                new VisualFocus(region.pageNumber(), label, region.x(), region.y(), region.width(), region.height())));
        return new LessonSection(
                section.position(), section.topicKey(), section.coverageTags(), section.title(), section.required(),
                section.evidenceStatus(), section.visualKind(), section.visualCaption(),
                distinct(section.visualSourcePages(), region.pageNumber()),
                distinct(section.visualSourceChunkIds(), region.supportedEvidenceIds()), steps);
    }

    private String visualText(String observation, String ruleText) {
        String prefix = "图中可见" + observation + "。结合图片完成这一步：";
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
}
