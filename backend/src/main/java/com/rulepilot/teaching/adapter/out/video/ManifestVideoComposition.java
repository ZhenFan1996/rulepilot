package com.rulepilot.teaching.adapter.out.video;

import com.rulepilot.teaching.SpeechSynthesisPort.SpeechCue;
import com.rulepilot.teaching.SpeechSynthesisPort.SynthesizedSpeech;
import com.rulepilot.teaching.VideoCompositionPort;
import com.rulepilot.teaching.domain.ChapterVideo;
import com.rulepilot.teaching.domain.ChapterVideo.VideoChapter;
import com.rulepilot.teaching.domain.ChapterVideo.VideoFrame;
import com.rulepilot.teaching.domain.ChapterVideo.VideoStatus;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.NarrationScript;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rulepilot.video", name = "provider", havingValue = "manifest", matchIfMissing = true)
public class ManifestVideoComposition implements VideoCompositionPort {

    @Override
    public ChapterVideo compose(
            IllustratedLesson lesson,
            NarrationScript script,
            SynthesizedSpeech speech) {
        if (!script.illustratedLessonId().equals(lesson.id())) {
            throw new IllegalArgumentException("narration does not belong to lesson");
        }
        Map<CueKey, SpeechCue> cues = speech.cues().stream().collect(Collectors.toUnmodifiableMap(
                cue -> new CueKey(cue.chapterPosition(), cue.segmentPosition()),
                Function.identity()));
        var chapters = lesson.sections().stream()
                .map(section -> chapter(section, script, cues))
                .toList();
        var status = chapters.stream().allMatch(chapter -> chapter.evidenceStatus() == EvidenceStatus.SUPPORTED)
                ? VideoStatus.READY
                : VideoStatus.INCOMPLETE;
        var id = UUID.nameUUIDFromBytes(
                ("video:" + lesson.id() + ":" + script.id()).getBytes(StandardCharsets.UTF_8));
        return new ChapterVideo(
                id, lesson.id(), status, speech.durationMillis(), chapters, lesson.createdAt());
    }

    private VideoChapter chapter(
            IllustratedLesson.LessonSection section,
            NarrationScript script,
            Map<CueKey, SpeechCue> cues) {
        var narration = script.chapters().stream()
                .filter(candidate -> candidate.position() == section.position())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("narration chapter is missing"));
        var frames = narration.segments().stream().map(segment -> {
            var cue = cue(cues, section.position(), segment.position());
            return new VideoFrame(
                    segment.position(),
                    cue.startMillis(),
                    cue.endMillis(),
                    segment.text(),
                    segment.sourcePages());
        }).toList();
        return new VideoChapter(
                section.position(),
                section.topicKey(),
                section.title(),
                section.evidenceStatus(),
                section.visualKind(),
                section.visualCaption(),
                frames.getFirst().startMillis(),
                frames.getLast().endMillis(),
                frames);
    }

    private SpeechCue cue(Map<CueKey, SpeechCue> cues, int chapterPosition, int segmentPosition) {
        var cue = cues.get(new CueKey(chapterPosition, segmentPosition));
        if (cue == null) {
            throw new IllegalArgumentException("speech cue is missing for video frame");
        }
        return cue;
    }

    private record CueKey(int chapterPosition, int segmentPosition) {}
}
