package com.rulepilot.teaching.application;

import com.rulepilot.teaching.SpeechSynthesisPort;
import com.rulepilot.teaching.SpeechSynthesisPort.SynthesizedSpeech;
import com.rulepilot.teaching.domain.NarrationScript;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class NarrationService {

    private final IllustratedLessonRepository lessons;
    private final NarrationScriptFactory scripts;
    private final SpeechSynthesisPort speech;

    public NarrationService(
            IllustratedLessonRepository lessons,
            NarrationScriptFactory scripts,
            SpeechSynthesisPort speech) {
        this.lessons = lessons;
        this.scripts = scripts;
        this.speech = speech;
    }

    @Transactional(readOnly = true)
    public NarrationScript script(UUID teachingPlanId) {
        var lesson = lessons.findLatestByPlan(teachingPlanId)
                .orElseThrow(() -> new IllegalArgumentException("lesson does not exist"));
        return scripts.create(lesson);
    }

    @Transactional(readOnly = true)
    public SynthesizedSpeech audio(UUID teachingPlanId) {
        return speech.synthesize(script(teachingPlanId));
    }
}
