package com.rulepilot.teaching;

import com.rulepilot.teaching.domain.NarrationScript;

public interface SpeechSynthesisPort {

    SynthesizedSpeech synthesize(NarrationScript script);

    record SynthesizedSpeech(String contentType, String provider, byte[] audio, long durationMillis) {

        public SynthesizedSpeech {
            if (contentType == null || contentType.isBlank() || provider == null || provider.isBlank()) {
                throw new IllegalArgumentException("speech metadata is required");
            }
            if (audio == null || audio.length == 0 || durationMillis < 0) {
                throw new IllegalArgumentException("speech audio is required");
            }
            audio = audio.clone();
        }

        @Override
        public byte[] audio() {
            return audio.clone();
        }
    }
}
