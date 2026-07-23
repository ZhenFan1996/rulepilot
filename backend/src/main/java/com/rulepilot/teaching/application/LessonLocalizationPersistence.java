package com.rulepilot.teaching.application;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.LessonLocalization;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns short transactions around cached player-language projections, never around a model call. */
@Service
@Profile("!test")
public class LessonLocalizationPersistence {

    private final TeachingPlanRepository plans;
    private final IllustratedLessonRepository lessons;
    private final LessonLocalizationRepository localizations;
    private final Clock clock;

    @Autowired
    public LessonLocalizationPersistence(
            TeachingPlanRepository plans,
            IllustratedLessonRepository lessons,
            LessonLocalizationRepository localizations) {
        this(plans, lessons, localizations, Clock.systemUTC());
    }

    LessonLocalizationPersistence(
            TeachingPlanRepository plans,
            IllustratedLessonRepository lessons,
            LessonLocalizationRepository localizations,
            Clock clock) {
        this.plans = plans;
        this.lessons = lessons;
        this.localizations = localizations;
        this.clock = clock;
    }

    @Transactional
    public Preparation prepare(UUID planId, String owner, PlayerLocale language) {
        TeachingPlan plan = plans.findByIdAndCreatedBy(planId, owner)
                .orElseThrow(() -> new IllegalArgumentException("teaching plan does not exist"));
        IllustratedLesson lesson = lessons.findLatestByPlan(plan.id())
                .orElseThrow(() -> new IllegalArgumentException("lesson does not exist"));
        if (lesson.status() == IllustratedLesson.LessonStatus.INCOMPLETE) {
            throw new IllegalArgumentException("incomplete lesson cannot be localized");
        }
        Optional<LessonLocalization> existing = localizations.find(lesson.id(), language);
        if (existing.isPresent() && existing.get().status() != LessonLocalization.Status.FAILED) {
            return new Preparation(plan.id(), lesson.id(), plan.createdBy(), existing.get(), true);
        }
        LessonLocalization pending = LessonLocalization.pending(lesson.id(), language, Instant.now(clock));
        localizations.save(pending);
        return new Preparation(plan.id(), lesson.id(), plan.createdBy(), pending, false);
    }

    @Transactional(readOnly = true)
    public TranslationInput input(UUID planId, UUID lessonId, String owner, PlayerLocale language) {
        TeachingPlan plan = plans.findByIdAndCreatedBy(planId, owner)
                .orElseThrow(() -> new IllegalArgumentException("teaching plan does not exist"));
        IllustratedLesson lesson = lessons.findLatestByPlan(plan.id())
                .filter(found -> found.id().equals(lessonId))
                .orElseThrow(() -> new IllegalArgumentException("lesson changed before localization"));
        LessonLocalization localization = localizations.find(lesson.id(), language)
                .orElseThrow(() -> new IllegalArgumentException("lesson localization does not exist"));
        return new TranslationInput(plan.id(), lesson, plan.createdBy(), localization);
    }

    @Transactional
    public void markRunning(UUID lessonId, PlayerLocale language) {
        LessonLocalization localization = required(lessonId, language);
        localizations.save(localization.running(Instant.now(clock)));
    }

    @Transactional
    public void complete(UUID lessonId, PlayerLocale language, java.util.List<LessonLocalization.SectionTranslation> sections) {
        LessonLocalization localization = required(lessonId, language);
        localizations.save(localization.complete(sections, Instant.now(clock)));
    }

    @Transactional
    public void fail(UUID lessonId, PlayerLocale language, String failureCode) {
        LessonLocalization localization = required(lessonId, language);
        localizations.save(localization.fail(failureCode, Instant.now(clock)));
    }

    @Transactional(readOnly = true)
    public Optional<LessonLocalization> find(UUID lessonId, PlayerLocale language) {
        return localizations.find(lessonId, language);
    }

    private LessonLocalization required(UUID lessonId, PlayerLocale language) {
        return localizations.find(lessonId, language)
                .orElseThrow(() -> new IllegalArgumentException("lesson localization does not exist"));
    }

    public record Preparation(UUID planId, UUID lessonId, String owner, LessonLocalization localization, boolean reused) {}

    public record TranslationInput(UUID planId, IllustratedLesson lesson, String owner, LessonLocalization localization) {}
}
