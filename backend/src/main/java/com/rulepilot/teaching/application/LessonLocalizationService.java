package com.rulepilot.teaching.application;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.LessonLocalization;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/** Starts owner-authorized localization and presents an existing projection without exposing an owner publicly. */
@Service
@Profile("!test")
public class LessonLocalizationService {

    private final LessonLocalizationPersistence persistence;
    private final LessonLocalizationWorker worker;
    private final TaskExecutor executor;

    public LessonLocalizationService(
            LessonLocalizationPersistence persistence,
            LessonLocalizationWorker worker,
            @Qualifier("teachingGenerationExecutor") TaskExecutor executor) {
        this.persistence = persistence;
        this.worker = worker;
        this.executor = executor;
    }

    public synchronized LocalizationView prepare(UUID planId, String owner, PlayerLocale language) {
        if (language == PlayerLocale.ZH_CN) throw new IllegalArgumentException("source lesson is already Chinese");
        var preparation = persistence.prepare(planId, owner, language);
        if (!preparation.reused()) {
            try {
                executor.execute(() -> worker.translate(preparation));
            } catch (RuntimeException queueFailure) {
                persistence.fail(preparation.lessonId(), language, "QUEUE_UNAVAILABLE");
                throw queueFailure;
            }
        }
        return view(preparation.localization(), null);
    }

    public LocalizationView view(IllustratedLesson source, PlayerLocale language) {
        if (language == PlayerLocale.ZH_CN) return new LocalizationView(PlayerLocale.ZH_CN, LessonLocalization.Status.READY, source, null);
        return persistence.find(source.id(), language)
                .map(localization -> view(localization, source))
                .orElseGet(() -> new LocalizationView(language, null, source, null));
    }

    private LocalizationView view(LessonLocalization localization, IllustratedLesson source) {
        IllustratedLesson translated = source != null && localization.status() == LessonLocalization.Status.READY
                ? localization.applyTo(source)
                : source;
        return new LocalizationView(localization.language(), localization.status(), translated, localization.failureCode());
    }

    public record LocalizationView(
            PlayerLocale language,
            LessonLocalization.Status status,
            IllustratedLesson lesson,
            String failureCode) {}
}
