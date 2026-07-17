package com.rulepilot.teaching.adapter.out.speech;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.domain.NarrationScript;
import com.rulepilot.teaching.domain.NarrationScript.NarrationChapter;
import com.rulepilot.teaching.domain.NarrationScript.NarrationSegment;
import com.rulepilot.teaching.domain.NarrationScript.ScriptStatus;
import com.rulepilot.teaching.domain.TeachingSectionType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FakeSpeechSynthesisTest {

    @Test
    void createsAValidLocalWavWithoutCallingAnExternalProvider() {
        var script = new NarrationScript(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ScriptStatus.READY,
                List.of(new NarrationChapter(
                        1,
                        TeachingSectionType.SETUP,
                        "Setup",
                        true,
                        List.of(new NarrationSegment(1, "Place the board.", List.of(1))))),
                Instant.now());

        var speech = new FakeSpeechSynthesis().synthesize(script);

        assertThat(speech.contentType()).isEqualTo("audio/wav");
        assertThat(speech.provider()).isEqualTo("fake");
        assertThat(new String(speech.audio(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(speech.audio(), 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
        assertThat(speech.durationMillis()).isEqualTo(2_140);
        assertThat(speech.cues()).singleElement().satisfies(cue -> {
            assertThat(cue.chapterPosition()).isEqualTo(1);
            assertThat(cue.segmentPosition()).isEqualTo(1);
            assertThat(cue.startMillis()).isZero();
            assertThat(cue.endMillis()).isEqualTo(2_140);
        });
    }
}
