package com.rulepilot.teaching.application;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.teaching.LessonLocalizationModel;
import com.rulepilot.teaching.domain.LessonLocalization;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Runs bounded model work outside database transactions and persists only a whole structurally valid projection. */
@Service
@Profile("!test")
public class LessonLocalizationWorker {

    private static final Logger log = LoggerFactory.getLogger(LessonLocalizationWorker.class);

    private final LessonLocalizationPersistence persistence;
    private final LessonLocalizationModel model;

    public LessonLocalizationWorker(LessonLocalizationPersistence persistence, LessonLocalizationModel model) {
        this.persistence = persistence;
        this.model = model;
    }

    public void translate(LessonLocalizationPersistence.Preparation preparation) {
        PlayerLocale language = preparation.localization().language();
        try {
            persistence.markRunning(preparation.lessonId(), language);
            var input = persistence.input(preparation.planId(), preparation.lessonId(), preparation.owner(), language);
            if (!model.available(input.owner())) {
                persistence.fail(input.lesson().id(), language, "MODEL_UNAVAILABLE");
                return;
            }
            List<LessonLocalization.SectionTranslation> translated = input.lesson().sections().stream()
                    .map(section -> model.translate(section, language, input.owner()))
                    .toList();
            LessonLocalization completed = input.localization().complete(translated, java.time.Instant.now());
            completed.applyTo(input.lesson());
            persistence.complete(input.lesson().id(), language, translated);
        } catch (RuntimeException failure) {
            log.warn("Lesson localization failed safely for lesson {} language {}", preparation.lessonId(), language, failure);
            try {
                persistence.fail(preparation.lessonId(), language, "LOCALIZATION_FAILED");
            } catch (RuntimeException persistenceFailure) {
                failure.addSuppressed(persistenceFailure);
            }
        }
    }
}
