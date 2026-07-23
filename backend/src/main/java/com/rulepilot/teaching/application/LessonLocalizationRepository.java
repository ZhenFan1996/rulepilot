package com.rulepilot.teaching.application;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.teaching.domain.LessonLocalization;
import java.util.Optional;
import java.util.UUID;

public interface LessonLocalizationRepository {

    LessonLocalization save(LessonLocalization localization);

    Optional<LessonLocalization> find(UUID lessonId, PlayerLocale language);
}
