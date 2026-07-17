package com.rulepilot.teaching.application;

import com.rulepilot.teaching.SpeechSynthesisPort;
import com.rulepilot.teaching.VideoCompositionPort;
import com.rulepilot.teaching.domain.MediaConsistencyReport;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class MediaConsistencyService {

    private final IllustratedLessonRepository lessons;
    private final NarrationScriptFactory scripts;
    private final SpeechSynthesisPort speech;
    private final VideoCompositionPort videos;
    private final MediaConsistencyEvaluator evaluator;

    public MediaConsistencyService(
            IllustratedLessonRepository lessons,
            NarrationScriptFactory scripts,
            SpeechSynthesisPort speech,
            VideoCompositionPort videos,
            MediaConsistencyEvaluator evaluator) {
        this.lessons = lessons;
        this.scripts = scripts;
        this.speech = speech;
        this.videos = videos;
        this.evaluator = evaluator;
    }

    @Transactional(readOnly = true)
    public MediaConsistencyReport evaluate(UUID teachingPlanId) {
        var lesson = lessons.findLatestByPlan(teachingPlanId)
                .orElseThrow(() -> new IllegalArgumentException("lesson does not exist"));
        var narration = scripts.create(lesson);
        var synthesized = speech.synthesize(narration);
        var video = videos.compose(lesson, narration, synthesized);
        return evaluator.evaluate(lesson, narration, synthesized, video);
    }
}
