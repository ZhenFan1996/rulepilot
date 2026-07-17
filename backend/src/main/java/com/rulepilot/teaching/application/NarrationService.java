package com.rulepilot.teaching.application;

import com.rulepilot.teaching.SpeechSynthesisPort;
import com.rulepilot.teaching.SpeechSynthesisPort.SpeechCue;
import com.rulepilot.teaching.SpeechSynthesisPort.SynthesizedSpeech;
import com.rulepilot.teaching.domain.NarrationScript;
import java.util.List;
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

    @Transactional(readOnly = true)
    public NarrationPlayback playback(UUID teachingPlanId) {
        var script = script(teachingPlanId);
        var synthesized = speech.synthesize(script);
        return new NarrationPlayback(
                script, synthesized.provider(), synthesized.durationMillis(), synthesized.cues());
    }

    public record NarrationPlayback(
            NarrationScript script,
            String provider,
            long durationMillis,
            List<SpeechCue> cues) {

        public NarrationPlayback {
            cues = List.copyOf(cues);
        }
    }
}
