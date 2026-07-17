package com.rulepilot.teaching.adapter.out.speech;

import com.rulepilot.teaching.SpeechSynthesisPort;
import com.rulepilot.teaching.SpeechSynthesisPort.SpeechCue;
import com.rulepilot.teaching.domain.NarrationScript;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rulepilot.speech", name = "provider", havingValue = "fake", matchIfMissing = true)
public class FakeSpeechSynthesis implements SpeechSynthesisPort {

    private static final int SAMPLE_RATE = 8_000;
    private static final int CHAPTER_GAP_MILLIS = 250;
    private static final int MARKER_MILLIS = 70;

    @Override
    public SynthesizedSpeech synthesize(NarrationScript script) {
        var cues = cues(script);
        long durationMillis = cues.isEmpty() ? 500 : cues.getLast().endMillis();
        int sampleCount = Math.toIntExact(SAMPLE_RATE * durationMillis / 1_000);
        int dataSize = sampleCount * Short.BYTES;
        var wav = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        writeHeader(wav, dataSize);
        writeSegmentMarkers(wav, sampleCount, cues);
        return new SynthesizedSpeech(
                "audio/wav", "fake", wav.array(), durationMillis, cues);
    }

    private List<SpeechCue> cues(NarrationScript script) {
        var cues = new ArrayList<SpeechCue>();
        long cursor = 0;
        for (var chapter : script.chapters()) {
            for (var segment : chapter.segments()) {
                long duration = Math.clamp(segment.text().codePointCount(0, segment.text().length()) * 90L + 700, 1_200, 7_000);
                cues.add(new SpeechCue(
                        chapter.position(), segment.position(), cursor, cursor + duration));
                cursor += duration;
            }
            if (chapter.position() < script.chapters().size()) {
                cursor += CHAPTER_GAP_MILLIS;
            }
        }
        return List.copyOf(cues);
    }

    private void writeHeader(ByteBuffer wav, int dataSize) {
        wav.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        wav.putInt(36 + dataSize);
        wav.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        wav.putInt(16);
        wav.putShort((short) 1);
        wav.putShort((short) 1);
        wav.putInt(SAMPLE_RATE);
        wav.putInt(SAMPLE_RATE * Short.BYTES);
        wav.putShort((short) Short.BYTES);
        wav.putShort((short) 16);
        wav.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        wav.putInt(dataSize);
    }

    private void writeSegmentMarkers(ByteBuffer wav, int sampleCount, List<SpeechCue> cues) {
        int cueIndex = 0;
        for (int sample = 0; sample < sampleCount; sample++) {
            long millis = sample * 1_000L / SAMPLE_RATE;
            while (cueIndex + 1 < cues.size() && millis >= cues.get(cueIndex + 1).startMillis()) {
                cueIndex++;
            }
            long withinCue = cues.isEmpty() ? Long.MAX_VALUE : millis - cues.get(cueIndex).startMillis();
            double seconds = sample / (double) SAMPLE_RATE;
            short amplitude = withinCue >= 0 && withinCue < MARKER_MILLIS
                    ? (short) (Math.sin(2 * Math.PI * 440 * seconds) * 2_000)
                    : 0;
            wav.putShort(amplitude);
        }
    }
}
