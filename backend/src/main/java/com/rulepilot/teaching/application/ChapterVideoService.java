package com.rulepilot.teaching.application;

import com.rulepilot.teaching.SpeechSynthesisPort;
import com.rulepilot.teaching.VideoCompositionPort;
import com.rulepilot.teaching.domain.ChapterVideo;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class ChapterVideoService {

    private final IllustratedLessonRepository lessons;
    private final NarrationScriptFactory scripts;
    private final SpeechSynthesisPort speech;
    private final VideoCompositionPort videos;

    public ChapterVideoService(
            IllustratedLessonRepository lessons,
            NarrationScriptFactory scripts,
            SpeechSynthesisPort speech,
            VideoCompositionPort videos) {
        this.lessons = lessons;
        this.scripts = scripts;
        this.speech = speech;
        this.videos = videos;
    }

    @Transactional(readOnly = true)
    public ChapterVideo compose(UUID teachingPlanId) {
        var lesson = lessons.findLatestByPlan(teachingPlanId)
                .orElseThrow(() -> new IllegalArgumentException("lesson does not exist"));
        var script = scripts.create(lesson);
        return videos.compose(lesson, script, speech.synthesize(script));
    }
}
