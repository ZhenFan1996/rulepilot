package com.rulepilot.teaching.domain;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.RuleFact;
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
        Map<Integer, RuleFactTranslation> translatedFacts = translated.ruleFacts().stream()
                .collect(Collectors.toMap(RuleFactTranslation::position, Function.identity()));
        if (translatedFacts.size() != source.ruleFacts().size()) {
            throw new IllegalArgumentException("localized rule facts do not match the source step");
        }
        List<RuleFact> ruleFacts = source.ruleFacts().stream()
                .map(fact -> {
                    RuleFactTranslation translatedFact = translatedFacts.get(fact.position());
                    if (translatedFact == null) {
                        throw new IllegalArgumentException("source rule fact has no translation");
                    }
                    return new RuleFact(
                            fact.position(),
                            fact.role(),
                            translatedFact.text(),
                            fact.sourcePages(),
                            fact.sourceChunkIds());
                })
                .toList();
        if (source.visualFocus() != null
                && (translated.visualLabel().isBlank() || translated.visualDescription().isBlank())) {
            throw new IllegalArgumentException("localized visual focus is incomplete");
        }
        VisualFocus focus = source.visualFocus() == null
                ? null
                : new VisualFocus(
                        source.visualFocus().pageNumber(),
                        translated.visualLabel(),
                        translated.visualDescription(),
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
                ruleFacts,
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

    public record StepTranslation(
            int position,
            String heading,
            String text,
            String visualLabel,
            String visualDescription,
            List<RuleFactTranslation> ruleFacts) {
        public StepTranslation(int position, String heading, String text, String visualLabel) {
            this(position, heading, text, visualLabel, "", List.of());
        }

        public StepTranslation(
                int position,
                String heading,
                String text,
                String visualLabel,
                String visualDescription) {
            this(position, heading, text, visualLabel, visualDescription, List.of());
        }

        public StepTranslation {
            ruleFacts = ruleFacts == null ? List.of() : List.copyOf(ruleFacts);
            if (position < 1 || blank(heading) || blank(text) || visualLabel == null || visualDescription == null) {
                throw new IllegalArgumentException("localized lesson step is invalid");
            }
            heading = heading.strip();
            text = text.strip();
            visualLabel = visualLabel.strip();
            visualDescription = visualDescription.strip();
        }
    }

    public record RuleFactTranslation(int position, String text) {
        public RuleFactTranslation {
            if (position < 1 || blank(text)) {
                throw new IllegalArgumentException("localized rule fact is invalid");
            }
            text = text.strip();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
