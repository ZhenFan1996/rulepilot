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
        var map = understanding.understanding(documentVersionId);
        Set<Integer> selectedPositions = prioritizer.positions(lesson.sections(), maxSections);
        List<LessonSection> sections = lesson.sections().stream()
                .map(section -> selectedPositions.contains(section.position())
                        ? enrichSection(map, documentVersionId, section, modelConfigurationOwner)
                        : section)
                .toList();
        return new IllustratedLesson(
                lesson.id(), lesson.teachingPlanId(), lesson.status(), sections, lesson.generatorVersion(), lesson.createdAt());
    }

    private LessonSection enrichSection(
            com.rulepilot.ingestion.layout.RulebookUnderstanding understanding,
            UUID documentVersionId,
            LessonSection section,
            String modelConfigurationOwner) {
        if (section.steps().stream().anyMatch(step -> step.kind() == TeachingMove.VISUAL)) return section;
        Set<Integer> citedPages = section.steps().stream()
                .flatMap(step -> step.sourcePages().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<VisualRegionCandidateSelector.Candidate> selected = candidates.select(
                understanding, citedPages, terms(section));
        if (selected.isEmpty()) {
            log.info("No cited visual candidates for section {}", section.title());
            return section;
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
            return section;
        }
        Set<Integer> attachedPages = pages.stream()
                .map(PageImage::pageNumber)
                .collect(Collectors.toUnmodifiableSet());
        List<VisualRegionCandidateSelector.Candidate> attachedCandidates = selected.stream()
                .filter(candidate -> attachedPages.contains(candidate.pageNumber()))
                .toList();
        List<Claim> claims = claims(section);
        var located = locator.locate(new VisualRegionLocator.VisualLocationRequest(
                        section.title(), claims, attachedCandidates, pages, modelConfigurationOwner))
                .filter(this::isCompactReaderCrop)
                .filter(this::isUsefulPlayerVisual)
                .filter(region -> intersectsCandidate(region, attachedCandidates));
        if (located.isEmpty()) {
            log.info("No cited visual region accepted for section {}", section.title());
            return section;
        }
        Set<UUID> evidenceIds = claims.stream().map(Claim::evidenceId).collect(Collectors.toSet());
        if (!evidenceIds.containsAll(located.get().supportedEvidenceIds())) {
            log.info("Visual region cited an unknown claim for section {}", section.title());
            return section;
        }
        return appendVisual(section, located.get());
    }

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
        if (description.isBlank()) return true;
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

    private LessonSection appendVisual(LessonSection section, VisualRegionLocator.LocatedRegion region) {
        List<LessonStep> steps = new ArrayList<>(section.steps());
        String observation = stripTrailingPunctuation(region.visibleDescription());
        String visualText = observation.isBlank()
                ? "查看图中的“" + region.label() + "”。"
                : "看图：" + observation + "。这就是本节要定位的“" + region.label() + "”。";
        steps.add(new LessonStep(
                steps.size() + 1,
                "看图定位",
                TeachingMove.VISUAL,
                visualText,
                List.of(region.pageNumber()),
                region.supportedEvidenceIds(),
                new VisualFocus(region.pageNumber(), region.label(), region.x(), region.y(), region.width(), region.height())));
        return new LessonSection(
                section.position(), section.topicKey(), section.coverageTags(), section.title(), section.required(),
                section.evidenceStatus(), section.visualKind(), section.visualCaption(),
                distinct(section.visualSourcePages(), region.pageNumber()),
                distinct(section.visualSourceChunkIds(), region.supportedEvidenceIds()), steps);
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
