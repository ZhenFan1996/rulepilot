package com.rulepilot.teaching;

import com.rulepilot.teaching.domain.NarrationScript;
import java.util.List;

public interface SpeechSynthesisPort {

    SynthesizedSpeech synthesize(NarrationScript script);

    record SynthesizedSpeech(
            String contentType,
            String provider,
            byte[] audio,
            long durationMillis,
            List<SpeechCue> cues) {

        public SynthesizedSpeech {
            if (contentType == null || contentType.isBlank() || provider == null || provider.isBlank()) {
                throw new IllegalArgumentException("speech metadata is required");
            }
            if (audio == null || audio.length == 0 || durationMillis < 0) {
                throw new IllegalArgumentException("speech audio is required");
            }
            audio = audio.clone();
            cues = List.copyOf(cues);
        }

        @Override
        public byte[] audio() {
            return audio.clone();
        }
    }

    record SpeechCue(
            int chapterPosition,
            int segmentPosition,
            long startMillis,
            long endMillis) {

        public SpeechCue {
            if (chapterPosition < 1 || segmentPosition < 1 || startMillis < 0 || endMillis <= startMillis) {
                throw new IllegalArgumentException("speech cue range is invalid");
            }
        }
    }
}
