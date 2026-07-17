package com.rulepilot.teaching.adapter.out.speech;

import com.rulepilot.teaching.SpeechSynthesisPort;
import com.rulepilot.teaching.domain.NarrationScript;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rulepilot.speech", name = "provider", havingValue = "fake", matchIfMissing = true)
public class FakeSpeechSynthesis implements SpeechSynthesisPort {

    private static final int SAMPLE_RATE = 8_000;
    private static final int MILLISECONDS_PER_CHAPTER = 180;

    @Override
    public SynthesizedSpeech synthesize(NarrationScript script) {
        int chapterCount = Math.max(1, script.chapters().size());
        int sampleCount = SAMPLE_RATE * MILLISECONDS_PER_CHAPTER * chapterCount / 1_000;
        int dataSize = sampleCount * Short.BYTES;
        var wav = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        writeHeader(wav, dataSize);
        writeChapterMarkers(wav, sampleCount);
        return new SynthesizedSpeech(
                "audio/wav", "fake", wav.array(), (long) MILLISECONDS_PER_CHAPTER * chapterCount);
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

    private void writeChapterMarkers(ByteBuffer wav, int sampleCount) {
        int samplesPerChapter = SAMPLE_RATE * MILLISECONDS_PER_CHAPTER / 1_000;
        for (int sample = 0; sample < sampleCount; sample++) {
            int withinChapter = sample % samplesPerChapter;
            double seconds = withinChapter / (double) SAMPLE_RATE;
            short amplitude = withinChapter < SAMPLE_RATE / 12
                    ? (short) (Math.sin(2 * Math.PI * 440 * seconds) * 2_000)
                    : 0;
            wav.putShort(amplitude);
        }
    }
}
