package com.rulepilot.teaching.domain;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A player-language overlay. It never changes lesson order, citations, source pages, or crop coordinates from the
 * cited source lesson.
 */
public record LessonLocalization(
        UUID lessonId,
        PlayerLocale language,
        Status status,
        List<SectionTranslation> sections,
        String failureCode,
        Instant createdAt,
        Instant updatedAt) {

    public LessonLocalization {
        if (lessonId == null || language == null || status == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("lesson localization identity is required");
        }
        sections = sections == null ? List.of() : List.copyOf(sections);
        failureCode = failureCode == null ? null : failureCode.strip();
        if (status == Status.READY && sections.isEmpty()) {
            throw new IllegalArgumentException("ready localization needs translated sections");
        }
        if (status != Status.READY && !sections.isEmpty()) {
            throw new IllegalArgumentException("unfinished localization cannot expose translated sections");
        }
        if (status == Status.FAILED && (failureCode == null || failureCode.isBlank())) {
            throw new IllegalArgumentException("failed localization needs a failure code");
        }
    }

    public static LessonLocalization pending(UUID lessonId, PlayerLocale language, Instant now) {
        return new LessonLocalization(lessonId, language, Status.PENDING, List.of(), null, now, now);
    }

    public LessonLocalization running(Instant now) {
        return new LessonLocalization(lessonId, language, Status.RUNNING, List.of(), null, createdAt, now);
    }

    public LessonLocalization complete(List<SectionTranslation> translatedSections, Instant now) {
        return new LessonLocalization(lessonId, language, Status.READY, translatedSections, null, createdAt, now);
    }

    public LessonLocalization fail(String code, Instant now) {
        return new LessonLocalization(lessonId, language, Status.FAILED, List.of(), code, createdAt, now);
    }

    public IllustratedLesson applyTo(IllustratedLesson source) {
        if (status != Status.READY || !lessonId.equals(source.id())) {
            throw new IllegalArgumentException("ready localization must match its source lesson");
        }
        Map<Integer, SectionTranslation> translatedByPosition = sections.stream()
                .collect(Collectors.toMap(SectionTranslation::position, Function.identity()));
        if (translatedByPosition.size() != source.sections().size()) {
            throw new IllegalArgumentException("localized sections do not match the source lesson");
        }
        List<LessonSection> localized = source.sections().stream()
                .map(section -> apply(section, translatedByPosition.get(section.position())))
                .toList();
        return new IllustratedLesson(
                source.id(),
                source.teachingPlanId(),
                source.status(),
                localized,
                source.generatorVersion(),
                source.createdAt());
    }

    private LessonSection apply(LessonSection source, SectionTranslation translated) {
        if (translated == null) throw new IllegalArgumentException("source section has no translation");
        Map<Integer, StepTranslation> translatedByPosition = translated.steps().stream()
                .collect(Collectors.toMap(StepTranslation::position, Function.identity()));
        if (translatedByPosition.size() != source.steps().size()) {
            throw new IllegalArgumentException("localized steps do not match the source section");
        }
        List<LessonStep> steps = source.steps().stream()
                .map(step -> apply(step, translatedByPosition.get(step.position())))
                .toList();
        return new LessonSection(
                source.position(),
                source.topicKey(),
                source.coverageTags(),
                translated.title(),
                source.required(),
                source.evidenceStatus(),
                source.visualKind(),
                translated.visualCaption(),
                source.visualSourcePages(),
                source.visualSourceChunkIds(),
                steps);
    }

    private LessonStep apply(LessonStep source, StepTranslation translated) {
        if (translated == null) throw new IllegalArgumentException("source step has no translation");
        VisualFocus focus = source.visualFocus() == null
                ? null
                : new VisualFocus(
                        source.visualFocus().pageNumber(),
                        translated.visualLabel().isBlank() ? source.visualFocus().label() : translated.visualLabel(),
                        source.visualFocus().x(),
                        source.visualFocus().y(),
                        source.visualFocus().width(),
                        source.visualFocus().height());
        return new LessonStep(
                source.position(),
                translated.heading(),
                source.kind(),
                translated.text(),
                source.sourcePages(),
                source.sourceChunkIds(),
                focus);
    }

    public enum Status {
        PENDING,
        RUNNING,
        READY,
        FAILED
    }

    public record SectionTranslation(int position, String title, String visualCaption, List<StepTranslation> steps) {
        public SectionTranslation {
            if (position < 1 || blank(title) || visualCaption == null || steps == null || steps.isEmpty()) {
                throw new IllegalArgumentException("localized lesson section is invalid");
            }
            title = title.strip();
            visualCaption = visualCaption.strip();
            steps = List.copyOf(steps);
        }
    }

    public record StepTranslation(int position, String heading, String text, String visualLabel) {
        public StepTranslation {
            if (position < 1 || blank(heading) || blank(text) || visualLabel == null) {
                throw new IllegalArgumentException("localized lesson step is invalid");
            }
            heading = heading.strip();
            text = text.strip();
            visualLabel = visualLabel.strip();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
