package com.rulepilot.teaching.application;

import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.ingestion.RulebookUnderstandingCatalog;
import com.rulepilot.ingestion.domain.RulebookUnderstanding.Rectangle;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionLocator.Claim;
import com.rulepilot.teaching.VisualRegionLocator.PageImage;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Adds a verified visual reference without changing an already published text lesson. */
@Service
@Profile("!test")
public class VisualLessonEnricher {

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
            VisualRegionLocator locator,
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
            com.rulepilot.ingestion.domain.RulebookUnderstanding understanding,
            UUID documentVersionId,
            LessonSection section,
            String modelConfigurationOwner) {
        if (section.steps().stream().anyMatch(step -> step.kind() == TeachingMove.VISUAL)) return section;
        Set<Integer> citedPages = section.steps().stream()
                .flatMap(step -> step.sourcePages().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<VisualRegionCandidateSelector.Candidate> selected = candidates.select(
                understanding, citedPages, terms(section));
        if (selected.isEmpty()) return section;
        Set<Integer> candidatePages = selected.stream().map(VisualRegionCandidateSelector.Candidate::pageNumber)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<PageImage> pages = pageImages.read(documentVersionId, candidatePages).stream()
                .sorted(Comparator.comparingInt(DocumentPageImages.PageImage::pageNumber))
                .limit(2)
                .map(image -> new PageImage(image.pageNumber(), image.mediaType(), image.content()))
                .toList();
        if (pages.isEmpty()) return section;
        List<Claim> claims = claims(section);
        return locator.locate(new VisualRegionLocator.VisualLocationRequest(
                        section.title(), claims, selected, pages, modelConfigurationOwner))
                .filter(region -> intersectsCandidate(region, selected))
                .filter(region -> claims.stream().map(Claim::evidenceId).collect(Collectors.toSet())
                        .containsAll(region.supportedEvidenceIds()))
                .map(region -> appendVisual(section, region))
                .orElse(section);
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

    private boolean intersects(Rectangle candidate, int x, int y, int width, int height) {
        return candidate.x() < x + width && x < candidate.x() + candidate.width()
                && candidate.y() < y + height && y < candidate.y() + candidate.height();
    }

    private LessonSection appendVisual(LessonSection section, VisualRegionLocator.LocatedRegion region) {
        List<LessonStep> steps = new ArrayList<>(section.steps());
        steps.add(new LessonStep(
                steps.size() + 1,
                "看图定位",
                TeachingMove.VISUAL,
                "查看图中的“" + region.label() + "”。",
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
}
