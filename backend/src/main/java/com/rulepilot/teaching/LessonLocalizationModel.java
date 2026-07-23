package com.rulepilot.teaching;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.LessonLocalization.SectionTranslation;

/** Bounded translation port: translation may change player prose, never the source lesson's evidence structure. */
public interface LessonLocalizationModel {

    boolean available(String modelConfigurationOwner);

    SectionTranslation translate(LessonSection section, PlayerLocale targetLanguage, String modelConfigurationOwner);
}
