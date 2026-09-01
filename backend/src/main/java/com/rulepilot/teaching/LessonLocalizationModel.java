package com.rulepilot.teaching;

import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.LessonLocalization.SectionTranslation;

/** Bounded translation port: translation may change player prose, never the source lesson's evidence structure. */
public interface LessonLocalizationModel {

    boolean available(String modelConfigurationOwner);

    SectionTranslation translate(LessonSection section, PlayerLocale targetLanguage, String modelConfigurationOwner);

    default SectionTranslation translate(
            LessonSection section,
            PlayerLocale targetLanguage,
            String modelConfigurationOwner,
            CaptureHandle capture,
            TraceEventContext context,
            int attempt) {
        return translate(section, targetLanguage, modelConfigurationOwner);
    }
}
