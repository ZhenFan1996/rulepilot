package com.rulepilot.teaching;

import com.rulepilot.teaching.SpeechSynthesisPort.SynthesizedSpeech;
import com.rulepilot.teaching.domain.ChapterVideo;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.NarrationScript;

public interface VideoCompositionPort {

    ChapterVideo compose(
            IllustratedLesson lesson,
            NarrationScript script,
            SynthesizedSpeech speech);
}
